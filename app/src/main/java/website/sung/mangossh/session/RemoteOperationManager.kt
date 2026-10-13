package website.sung.mangossh.session

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns process-local remote mutations independently of the browser. Each session shares
 * the transfer scheduler, and every approval belongs to exactly one live execution.
 * Completed side effects are reported rather than rolled back or replayed after disconnect.
 */
internal class RemoteOperationManager(
    private val scope: CoroutineScope,
    private val supervisor: TransferSupervisor,
    private val backendOf: suspend (RemoteOperationRequest, TransferControl) -> RemoteOperationBackend,
    private val onSessionIdle: (String) -> Unit,
) {
    private class Run(val request: RemoteOperationRequest) {
        val lock = Any()
        var committing = false
        var stopped = false
        var disconnected = false
        var control: BlockingOperation? = null
        var job: Job? = null
        var remembered: RemoteOperationDecision? = null
    }
    private data class Pending(val taskId: String, val result: CompletableDeferred<RemoteOperationDecision>)
    private val runs = ConcurrentHashMap<String, Run>()
    private val pending = ConcurrentHashMap<String, Pending>()
    private val _operations = MutableStateFlow<List<RemoteOperationState>>(emptyList())
    val operations = _operations.asStateFlow()
    private val _prompts = MutableStateFlow<List<RemoteOperationPrompt>>(emptyList())
    val prompts = _prompts.asStateFlow()

    fun start(request: RemoteOperationRequest): String {
        val id = UUID.randomUUID().toString()
        val run = Run(request.copy(sources = request.sources.toList()))
        _operations.update { it + RemoteOperationState(id, run.request) }
        runs[id] = run
        run.job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                supervisor.run(request.sessionId) {
                    BlockingOperation().use { control ->
                        synchronized(run.lock) { run.control = control; if (run.stopped) control.close() }
                        checkRunning(run)
                        execute(id, run, backendOf(run.request, control))
                    }
                }
                update(id) { it.copy(phase = RemoteOperationPhase.COMPLETED, currentPath = null) }
            } catch (_: CancellationException) {
                update(id) { it.copy(phase = if (run.disconnected) RemoteOperationPhase.FAILED else RemoteOperationPhase.CANCELLED,
                    detail = if (run.disconnected) RemoteFileMessage.TransferSessionClosed else it.detail,
                    items = it.items.map { item -> if (run.disconnected && item.path == it.currentPath && item.outcome == RemoteItemOutcome.PENDING)
                        item.copy(outcome = RemoteItemOutcome.FAILED) else item }) }
            } catch (error: Exception) {
                update(id) { state -> state.copy(phase = if (run.stopped && !run.disconnected) RemoteOperationPhase.CANCELLED else RemoteOperationPhase.FAILED,
                    detail = if (run.disconnected) RemoteFileMessage.TransferSessionClosed else if (run.stopped) null else error.toRemoteFileMessage(),
                    items = state.items.map { if ((!run.stopped || run.disconnected) && it.path == state.currentPath && it.outcome == RemoteItemOutcome.PENDING)
                        it.copy(outcome = RemoteItemOutcome.FAILED) else it }) }
            } finally {
                pending.entries.filter { it.value.taskId == id }.forEach { (key, value) -> pending.remove(key); value.result.cancel() }
                _prompts.update { it.filterNot { prompt -> prompt.taskId == id } }
                runs.remove(id)
                onSessionIdle(request.sessionId)
            }
        }
        // A queued coroutine can be cancelled before its body (and finally block) ever runs.
        run.job!!.invokeOnCompletion {
            if (runs.remove(id, run)) {
                update(id) { state -> if (state.active) state.copy(
                    phase = if (run.disconnected) RemoteOperationPhase.FAILED else RemoteOperationPhase.CANCELLED,
                    detail = if (run.disconnected) RemoteFileMessage.TransferSessionClosed else state.detail) else state }
                onSessionIdle(request.sessionId)
            }
        }
        synchronized(run.lock) { if (run.stopped) run.job!!.cancel() else run.job!!.start() }
        return id
    }

    fun busy(sessionId: String): Boolean = runs.values.any { it.request.sessionId == sessionId }
    fun clearFinished() { _operations.update { it.filter(RemoteOperationState::active) } }

    /** The committing flag and cancellation decision share one lock. */
    fun cancel(id: String) { runs[id]?.let { run -> synchronized(run.lock) {
        if (!run.committing) { run.stopped = true; run.control?.close(); run.job?.cancel() }
    } } }

    /** Losing a connection invalidates all approvals, including a write with an unknown outcome. */
    fun sessionEnded(sessionId: String) { runs.values.filter { it.request.sessionId == sessionId }.forEach { run ->
        synchronized(run.lock) { run.disconnected = true; run.stopped = true; run.control?.close(); run.job?.cancel() }
    } }

    fun decide(id: String, decision: RemoteOperationDecision) {
        val prompt = _prompts.value.firstOrNull { it.id == id } ?: return
        val run = runs[prompt.taskId] ?: return
        synchronized(run.lock) {
            if (!run.stopped) pending[id]?.result?.complete(decision)
        }
    }

    private fun update(id: String, transform: (RemoteOperationState) -> RemoteOperationState) {
        _operations.update { states -> states.map { if (it.id == id) transform(it) else it } }
    }
    private fun outcome(id: String, path: String, value: RemoteItemOutcome) {
        update(id) { it.copy(items = it.items.map { item -> if (item.path == path) item.copy(outcome = value) else item }) }
    }
    private fun checkRunning(run: Run) {
        if (run.stopped || run.control?.shouldContinue() == false) throw CancellationException()
        run.control?.progressed()
    }

    private suspend fun commit(id: String, run: Run, action: suspend () -> Unit) {
        synchronized(run.lock) { checkRunning(run); run.committing = true; update(id) { it.copy(phase = RemoteOperationPhase.COMMITTING) } }
        try { action() } finally { synchronized(run.lock) {
            run.committing = false
            update(id) { if (it.active) it.copy(phase = RemoteOperationPhase.RUNNING) else it }
        } }
    }

    private suspend fun prompt(id: String, run: Run, paths: List<String>, conflict: Boolean = false,
        replace: Boolean = false, direct: Boolean = false): RemoteOperationDecision {
        checkRunning(run)
        val promptId = UUID.randomUUID().toString()
        val answer = CompletableDeferred<RemoteOperationDecision>()
        pending[promptId] = Pending(id, answer)
        run.control?.awaitDecision(true)
        update(id) { it.copy(phase = RemoteOperationPhase.WAITING) }
        _prompts.update { it + RemoteOperationPrompt(promptId, id, run.request, paths, conflict, replace, direct) }
        return try {
            val decision = answer.await()
            checkRunning(run)
            if (decision.choice == RemoteOperationChoice.CANCEL) throw CancellationException()
            require(if (conflict) decision.choice in setOf(RemoteOperationChoice.SKIP, RemoteOperationChoice.SAVE_AS,
                RemoteOperationChoice.REPLACE, RemoteOperationChoice.DIRECT_OVERWRITE) else decision.choice == RemoteOperationChoice.PROCEED)
            require(decision.choice != RemoteOperationChoice.REPLACE || replace && !direct)
            require(decision.choice != RemoteOperationChoice.DIRECT_OVERWRITE || replace && direct)
            decision
        } finally {
            pending.remove(promptId)
            _prompts.update { it.filterNot { p -> p.id == promptId } }
            run.control?.awaitDecision(false)
            update(id) { it.copy(phase = RemoteOperationPhase.RUNNING) }
        }
    }

    private suspend fun scan(run: Run, backend: RemoteOperationBackend, roots: List<String>, recursive: Boolean): List<RemoteNode> {
        val found = mutableListOf<RemoteNode>()
        suspend fun visit(path: String, depth: Int) {
            checkRunning(run)
            if (found.size >= MAX_TRANSFER_TREE_ENTRIES || depth > MAX_TRANSFER_TREE_DEPTH) throw TransferTreeTooLargeException()
            val node = backend.node(path) ?: throw SourceChangedException()
            found += node
            if (recursive && node.kind == RemoteFileKind.DIRECTORY) {
                backend.children(node).forEach { visit(it.path, depth + 1) }
            }
        }
        roots.forEach { visit(it, 0) }
        return found
    }

    private suspend fun execute(id: String, run: Run, backend: RemoteOperationBackend) {
        val request = run.request
        val platform = request.platform
        update(id) { it.copy(phase = RemoteOperationPhase.SCANNING) }
        val creating = request.kind in setOf(RemoteOperationKind.CREATE_FILE, RemoteOperationKind.CREATE_DIRECTORY)
        val destination = request.destination?.let { backend.canonical(it) }
        if (creating) {
            val name = checkedName(checkNotNull(request.name), platform)
            val path = RemoteFilePaths.join(checkNotNull(destination), name)
            requireMutable(path)
            update(id) { it.copy(items = listOf(RemoteOperationItem(path)), currentPath = path) }
            requireAbsent(backend, path, platform)
            commit(id, run) { backend.create(path, request.kind == RemoteOperationKind.CREATE_DIRECTORY) }
            outcome(id, path, RemoteItemOutcome.COMPLETED)
            return
        }
        require(request.sources.isNotEmpty())
        val sources = request.sources.map(RemoteFilePaths::normalize).distinct()
        sources.forEach(::requireMutable)
        // Removing nested selections avoids duplicate operations on a subtree.
        val roots = sources.filter { path -> sources.none { it != path && RemoteOperationRules.contains(it, path, platform) } }
        val recursive = request.kind in setOf(RemoteOperationKind.COPY, RemoteOperationKind.DELETE) ||
            request.kind == RemoteOperationKind.PERMISSIONS && request.permissions?.recursive == true
        var nodes = scan(run, backend, roots, recursive)
        update(id) { it.copy(items = nodes.map { node -> RemoteOperationItem(node.path) }) }
        if (request.kind == RemoteOperationKind.PERMISSIONS) {
            if (platform != RemotePlatform.POSIX) throw RemoteOperationException(if (platform == RemotePlatform.WINDOWS)
                RemoteOperationFailure.WINDOWS_PERMISSIONS else RemoteOperationFailure.PLATFORM_REQUIRED)
            nodes.filter { it.kind !in setOf(RemoteFileKind.FILE, RemoteFileKind.DIRECTORY) }
                .forEach { outcome(id, it.path, RemoteItemOutcome.SKIPPED) }
        } else if (nodes.any { it.kind == RemoteFileKind.OTHER }) throw RemoteOperationException(RemoteOperationFailure.UNSUPPORTED)
        if (request.kind in setOf(RemoteOperationKind.COPY, RemoteOperationKind.MOVE)) {
            val target = checkNotNull(destination)
            if (nodes.any { it.kind == RemoteFileKind.DIRECTORY && RemoteOperationRules.contains(it.path, target, platform) })
                throw RemoteOperationException(RemoteOperationFailure.INVALID_DESTINATION)
        }
        if (request.kind in setOf(RemoteOperationKind.COPY, RemoteOperationKind.DELETE, RemoteOperationKind.PERMISSIONS)) {
            prompt(id, run, nodes.map(RemoteNode::path))
            val current = scan(run, backend, roots, recursive)
            if (nodes.map(RemoteNode::path).toSet() != current.map(RemoteNode::path).toSet()) throw SourceChangedException()
            val byPath = current.associateBy(RemoteNode::path)
            nodes.forEach { it.requireSame(byPath[it.path]) }
        }
        update(id) { it.copy(phase = RemoteOperationPhase.RUNNING) }
        when (request.kind) {
            RemoteOperationKind.DELETE -> nodes.asReversed().forEach { node ->
                checkRunning(run)
                update(id) { it.copy(currentPath = node.path) }
                commit(id, run) { backend.remove(node) }
                outcome(id, node.path, RemoteItemOutcome.COMPLETED)
            }
            RemoteOperationKind.PERMISSIONS -> {
                val change = checkNotNull(request.permissions)
                nodes = nodes.filter { it.kind in setOf(RemoteFileKind.FILE, RemoteFileKind.DIRECTORY) }
                    .sortedWith(compareBy<RemoteNode> { it.kind == RemoteFileKind.DIRECTORY }.thenByDescending { it.path.count { c -> c == '/' } })
                nodes.forEach { node ->
                    checkRunning(run)
                    update(id) { it.copy(currentPath = node.path) }
                    val old = node.attributes.permissions ?: throw MetadataPreservationException()
                    commit(id, run) { backend.chmod(node, change.apply(old, node.kind == RemoteFileKind.DIRECTORY)) }
                    outcome(id, node.path, RemoteItemOutcome.COMPLETED)
                }
            }
            RemoteOperationKind.RENAME -> {
                require(nodes.size == 1)
                val node = nodes.single()
                update(id) { it.copy(currentPath = node.path) }
                val path = RemoteFilePaths.join(RemoteFilePaths.parentOf(node.path), checkedName(checkNotNull(request.name), platform))
                if (path != node.path) {
                    if (platform == RemotePlatform.WINDOWS && path.equals(node.path, true)) throw RemoteOperationException(RemoteOperationFailure.CASE_ONLY)
                    requireAbsent(backend, path, platform)
                    commit(id, run) { backend.move(node, path, null) }
                }
                outcome(id, node.path, RemoteItemOutcome.COMPLETED)
            }
            RemoteOperationKind.COPY, RemoteOperationKind.MOVE -> {
                val copiedDirectories = mutableListOf<Pair<RemoteNode, String>>()
                for (root in roots) {
                    val source = nodes.first { it.path == root }
                    update(id) { it.copy(currentPath = root) }
                    val initial = RemoteFilePaths.join(checkNotNull(destination), checkedName(RemoteFilePaths.nameOf(root), platform))
                    val resolved = resolveTarget(id, run, backend, source, initial)
                    val subtree = nodes.filter { RemoteOperationRules.contains(root, it.path, platform) }
                    if (resolved == null) {
                        subtree.forEach { outcome(id, it.path, RemoteItemOutcome.SKIPPED) }
                        continue
                    }
                    val (target, previous, direct, approvedDigest) = resolved
                    if (request.kind == RemoteOperationKind.MOVE) {
                        try { commit(id, run) {
                            if (previous != null && approvedDigest != null && !java.security.MessageDigest.isEqual(approvedDigest, backend.digest(previous))) throw SourceChangedException()
                            backend.move(source, target, previous)
                        } }
                        catch (error: RemoteFileException) {
                            if (error.failure == RemoteFileFailure.IO_FAILURE) throw RemoteOperationException(RemoteOperationFailure.MOVE_FAILED)
                            throw error
                        }
                        outcome(id, root, RemoteItemOutcome.COMPLETED)
                    } else {
                        val targetHash = approvedDigest
                        for (node in subtree) {
                            checkRunning(run)
                            update(id) { it.copy(currentPath = node.path) }
                            val relative = RemoteFilePaths.relativize(root, node.path)
                            val copied = if (relative.isEmpty()) target else RemoteFilePaths.resolve(target, relative)
                            if (relative.isNotEmpty()) requireAbsent(backend, copied, platform)
                            if (node.kind == RemoteFileKind.DIRECTORY) {
                                node.requireSame(backend.node(node.path))
                                commit(id, run) { backend.create(copied, true) }
                                outcome(id, node.path, RemoteItemOutcome.PARTIAL)
                                copiedDirectories += node to copied
                            } else {
                                backend.copy(node, copied, if (relative.isEmpty()) previous else null,
                                    if (relative.isEmpty()) targetHash else null, direct,
                                    progress = { count -> update(id) { it.copy(transferredBytes = it.transferredBytes + count) } },
                                    committing = { action -> commit(id, run, action) })
                                outcome(id, node.path, RemoteItemOutcome.COMPLETED)
                            }
                        }
                    }
                }
                if (request.kind == RemoteOperationKind.COPY) {
                    val current = scan(run, backend, roots, true).associateBy(RemoteNode::path)
                    if (current.keys != nodes.map(RemoteNode::path).toSet()) throw SourceChangedException()
                    nodes.forEach { it.requireSame(current[it.path]) }
                }
                copiedDirectories.asReversed().forEach { (source, target) ->
                    checkRunning(run)
                    update(id) { it.copy(currentPath = source.path) }
                    commit(id, run) { backend.finishDirectory(source, target) }
                    outcome(id, source.path, RemoteItemOutcome.COMPLETED)
                }
            }
            else -> error("Unexpected remote operation")
        }
    }

    private data class Destination(val path: String, val previous: RemoteNode?, val direct: Boolean, val approvedDigest: ByteArray? = null)
    private suspend fun resolveTarget(id: String, run: Run, backend: RemoteOperationBackend, source: RemoteNode, initial: String): Destination? {
        var path = initial
        while (true) {
            checkRunning(run)
            if (RemoteOperationRules.contains(source.path, path, run.request.platform))
                throw RemoteOperationException(RemoteOperationFailure.INVALID_DESTINATION)
            val target = existing(backend, path, run.request.platform)
            if (target == null) return Destination(path, null, false)
            val regular = source.kind == RemoteFileKind.FILE && target.kind == RemoteFileKind.FILE
            val atomic = backend.atomicReplace()
            val replace = regular && (run.request.kind == RemoteOperationKind.COPY || atomic)
            val remembered = run.remembered?.takeIf { it.choice == RemoteOperationChoice.SKIP || replace && atomic && it.choice == RemoteOperationChoice.REPLACE }
            // Capture content before asking, so approval cannot silently authorize a changed target.
            val hash = if (regular) backend.digest(target) else null
            val decision = remembered ?: prompt(id, run, listOf(source.path, target.path), true, replace, regular && !atomic)
            source.requireSame(backend.node(source.path))
            target.requireSame(backend.node(target.path))
            if (hash != null && !java.security.MessageDigest.isEqual(hash, backend.digest(target))) throw SourceChangedException()
            if (decision.applyToTask && decision.choice in setOf(RemoteOperationChoice.SKIP, RemoteOperationChoice.REPLACE)) run.remembered = decision
            when (decision.choice) {
                RemoteOperationChoice.SKIP -> return null
                RemoteOperationChoice.SAVE_AS -> path = RemoteFilePaths.join(RemoteFilePaths.parentOf(path), checkedName(checkNotNull(decision.name), run.request.platform))
                RemoteOperationChoice.REPLACE, RemoteOperationChoice.DIRECT_OVERWRITE -> return Destination(target.path, target, decision.choice == RemoteOperationChoice.DIRECT_OVERWRITE, hash)
                else -> throw CancellationException()
            }
        }
    }

    private suspend fun existing(backend: RemoteOperationBackend, path: String, platform: RemotePlatform): RemoteNode? {
        backend.node(path)?.let { return it }
        if (platform != RemotePlatform.WINDOWS) return null
        val parent = backend.node(RemoteFilePaths.parentOf(path)) ?: throw SourceChangedException()
        return backend.children(parent).firstOrNull { RemoteFilePaths.nameOf(it.path).equals(RemoteFilePaths.nameOf(path), true) }
    }
    private suspend fun requireAbsent(backend: RemoteOperationBackend, path: String, platform: RemotePlatform) {
        if (existing(backend, path, platform) != null) throw RemoteOperationException(RemoteOperationFailure.EXISTS)
    }
    private fun checkedName(name: String, platform: RemotePlatform): String = try { RemoteOperationRules.requireName(name, platform) }
        catch (_: IllegalArgumentException) { throw RemoteOperationException(RemoteOperationFailure.INVALID_NAME) }
    private fun requireMutable(path: String) { try { RemoteOperationRules.requireMutable(path) }
        catch (_: IllegalArgumentException) { throw RemoteOperationException(RemoteOperationFailure.ROOT) } }
}
