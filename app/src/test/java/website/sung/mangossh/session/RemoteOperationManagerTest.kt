package website.sung.mangossh.session

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import website.sung.mangossh.session.ssh.SshFileAttributes

/** The fake filesystem records side effects so race/cancellation tests assert behavior, not implementation shape. */
class RemoteOperationManagerTest {
    private class Backend : RemoteOperationBackend {
        val nodes = linkedMapOf<String, RemoteNode>()
        val writes = mutableListOf<String>()
        var failAt: String? = null
        var atomic = true
        var commitEntered: CompletableDeferred<Unit>? = null
        var releaseCommit: CompletableDeferred<Unit>? = null
        fun add(path: String, kind: RemoteFileKind = RemoteFileKind.FILE, link: String? = null, mode: Int = 420) {
            nodes[path] = RemoteNode(path, SshFileAttributes(size = 4, uid = 1, gid = 1, mtime = 1, permissions = mode or when (kind) {
                RemoteFileKind.DIRECTORY -> 0x4000; RemoteFileKind.SYMLINK -> 0xa000; RemoteFileKind.FILE -> 0x8000; else -> 0x1000
            }), link)
        }
        override suspend fun node(path: String) = nodes[path]
        override suspend fun children(directory: RemoteNode) = nodes.values.filter { it.path != directory.path && RemoteFilePaths.parentOf(it.path) == directory.path }
        override suspend fun canonical(path: String) = path
        override suspend fun atomicReplace() = atomic
        override suspend fun digest(node: RemoteNode) = byteArrayOf(node.attributes.mtime!!.toByte())
        override suspend fun create(path: String, directory: Boolean) { writes += "create:$path"; add(path, if (directory) RemoteFileKind.DIRECTORY else RemoteFileKind.FILE) }
        override suspend fun remove(node: RemoteNode) {
            if (node.path == failAt) throw RemoteFileException(RemoteFileFailure.ACCESS_DENIED)
            commitEntered?.complete(Unit); releaseCommit?.await()
            node.requireSame(nodes[node.path], true)
            writes += "delete:${node.path}"; nodes.remove(node.path)
        }
        override suspend fun move(node: RemoteNode, destination: String, target: RemoteNode?) {
            if (node.path == failAt) throw RemoteFileException(RemoteFileFailure.IO_FAILURE)
            writes += "move:${node.path}"; nodes.remove(node.path); nodes[destination] = node.copy(path = destination)
        }
        override suspend fun chmod(node: RemoteNode, mode: Int) { writes += "chmod:${node.path}"; nodes[node.path] = node.copy(attributes = node.attributes.copy(permissions = node.attributes.permissions!! and 0xf000 or mode)) }
        override suspend fun copy(node: RemoteNode, destination: String, target: RemoteNode?, targetDigest: ByteArray?, direct: Boolean,
            progress: (Long) -> Unit, committing: suspend (suspend () -> Unit) -> Unit) { committing {
            writes += "copy:${node.path}"; nodes[destination] = node.copy(path = destination); progress(4)
        } }
        override suspend fun finishDirectory(source: RemoteNode, destination: String) { writes += "finish:${source.path}" }
    }
    private class Harness {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val backend = Backend()
        val manager = RemoteOperationManager(scope, TransferSupervisor(), { _, _ -> backend }, {})
        fun start(kind: RemoteOperationKind, sources: List<String>, destination: String? = null, permissions: RemotePermissionChange? = null) =
            manager.start(RemoteOperationRequest("session", kind, sources, destination, platform = RemotePlatform.POSIX, permissions = permissions))
        suspend fun prompt() = withTimeout(5_000) { manager.prompts.first { it.isNotEmpty() }.first() }
        suspend fun approve() { manager.decide(prompt().id, RemoteOperationDecision(RemoteOperationChoice.PROCEED)) }
        suspend fun finished(id: String) = withTimeout(5_000) { manager.operations.first { list -> list.any { it.id == id && !it.active } }.first { it.id == id } }
    }
    private fun runTest(action: suspend Harness.() -> Unit) = runBlocking {
        val h = Harness()
        try { h.action() } finally { h.scope.cancel() }
    }

    @Test fun recursiveDeleteDoesNotFollowLinksAndDeletesChildrenFirst() = runTest {
        backend.add("/tree", RemoteFileKind.DIRECTORY)
        backend.add("/tree/file")
        backend.add("/tree/link", RemoteFileKind.SYMLINK, "/outside")
        backend.add("/outside")
        val id = start(RemoteOperationKind.DELETE, listOf("/tree"))
        assertEquals(3, prompt().paths.size)
        assertTrue(backend.writes.isEmpty())
        approve()
        assertEquals(RemoteOperationPhase.COMPLETED, finished(id).phase)
        assertEquals(listOf("delete:/tree/link", "delete:/tree/file", "delete:/tree"), backend.writes)
        assertNotNull(backend.nodes["/outside"])
    }
    @Test fun newEntryAfterApprovalInvalidatesEntirePreview() = runTest {
        backend.add("/tree", RemoteFileKind.DIRECTORY); backend.add("/tree/a")
        val id = start(RemoteOperationKind.DELETE, listOf("/tree"))
        val p = prompt(); backend.add("/tree/new")
        manager.decide(p.id, RemoteOperationDecision(RemoteOperationChoice.PROCEED))
        assertEquals(RemoteFileMessage.SourceChanged, finished(id).detail)
        assertTrue(backend.writes.isEmpty())
    }
    @Test fun failureStopsLaterItemsAndRetainsCompletedOutcomes() = runTest {
        listOf("/a", "/b", "/c").forEach { backend.add(it) }; backend.failAt = "/b"
        val id = start(RemoteOperationKind.DELETE, listOf("/a", "/b", "/c")); approve()
        val result = finished(id)
        assertEquals(listOf("delete:/c"), backend.writes)
        assertEquals(listOf(RemoteItemOutcome.PENDING, RemoteItemOutcome.FAILED, RemoteItemOutcome.COMPLETED), result.items.map { it.outcome })
    }
    @Test fun cancelAndDisconnectInvalidateOldConfirmations() = runTest {
        backend.add("/a")
        val id = start(RemoteOperationKind.DELETE, listOf("/a")); val p = prompt()
        manager.sessionEnded("session")
        assertEquals(RemoteOperationPhase.FAILED, finished(id).phase)
        manager.decide(p.id, RemoteOperationDecision(RemoteOperationChoice.PROCEED))
        assertTrue(backend.writes.isEmpty())
    }
    @Test fun cancellationCannotInterruptAnActiveCommit() = runTest {
        backend.add("/a")
        backend.commitEntered = CompletableDeferred(); backend.releaseCommit = CompletableDeferred()
        val id = start(RemoteOperationKind.DELETE, listOf("/a")); approve()
        withTimeout(5_000) { backend.commitEntered!!.await() }
        manager.cancel(id)
        assertEquals(RemoteOperationPhase.COMMITTING, manager.operations.value.single().phase)
        backend.releaseCommit!!.complete(Unit)
        assertEquals(RemoteOperationPhase.COMPLETED, finished(id).phase)
    }
    @Test fun cancellingQueuedTasksReleasesTheirSessionOwnership() = runTest {
        backend.add("/a")
        val scheduler = TransferSupervisor()
        val entered = CompletableDeferred<Unit>()
        val blocker = scope.launch { scheduler.run("session") { entered.complete(Unit); awaitCancellation() } }
        entered.await()
        val queued = RemoteOperationManager(scope, scheduler, { _, _ -> backend }, {})
        repeat(25) {
            val id = queued.start(RemoteOperationRequest("session", RemoteOperationKind.DELETE, listOf("/a")))
            queued.cancel(id)
        }
        withTimeout(5_000) {
            queued.operations.first { it.all { task -> !task.active } }
            while (queued.busy("session")) yield()
        }
        assertTrue(backend.writes.isEmpty())
        blocker.cancelAndJoin()
    }
    @Test fun recursivePermissionsSkipLinksAndProcessDirectoriesLast() = runTest {
        backend.add("/tree", RemoteFileKind.DIRECTORY); backend.add("/tree/sub", RemoteFileKind.DIRECTORY)
        backend.add("/tree/sub/file"); backend.add("/tree/link", RemoteFileKind.SYMLINK, "/outside")
        val id = start(RemoteOperationKind.PERMISSIONS, listOf("/tree"), permissions = RemotePermissionChange(384, 448, recursive = true)); approve()
        assertEquals(RemoteOperationPhase.COMPLETED, finished(id).phase)
        assertEquals(listOf("chmod:/tree/sub/file", "chmod:/tree/sub", "chmod:/tree"), backend.writes)
        assertEquals(RemoteItemOutcome.SKIPPED, finished(id).items.last().outcome)
    }
    @Test fun copyPreservesLinkTargetWithoutReadingItsDestination() = runTest {
        backend.add("/link", RemoteFileKind.SYMLINK, "../missing")
        val id = start(RemoteOperationKind.COPY, listOf("/link"), "/destination"); approve()
        assertEquals(RemoteOperationPhase.COMPLETED, finished(id).phase)
        assertEquals("../missing", backend.nodes["/destination/link"]?.linkTarget)
    }
    @Test fun directoryConflictNeverOffersMergeOrReplace() = runTest {
        backend.add("/tree", RemoteFileKind.DIRECTORY); backend.add("/dest/tree", RemoteFileKind.DIRECTORY)
        val id = start(RemoteOperationKind.COPY, listOf("/tree"), "/dest"); approve()
        val conflict = withTimeout(5_000) { manager.prompts.first { it.any { p -> p.conflict } }.first() }
        assertFalse(conflict.replaceAllowed)
        manager.decide(conflict.id, RemoteOperationDecision(RemoteOperationChoice.SKIP))
        assertEquals(RemoteItemOutcome.SKIPPED, finished(id).items.single().outcome)
        assertTrue(backend.writes.isEmpty())
    }
    @Test fun moveFailureNeverFallsBackToCopyOrDelete() = runTest {
        backend.add("/a"); backend.failAt = "/a"
        val id = start(RemoteOperationKind.MOVE, listOf("/a"), "/dest")
        assertEquals(RemoteFileMessage.OperationFailure(RemoteOperationFailure.MOVE_FAILED), finished(id).detail)
        assertNotNull(backend.nodes["/a"]); assertTrue(backend.writes.isEmpty())
    }
    @Test fun traversalLimitsFailBeforeConfirmationOrWrites() = runTest {
        backend.add("/tree", RemoteFileKind.DIRECTORY)
        repeat(MAX_TRANSFER_TREE_ENTRIES) { backend.add("/tree/$it") }
        val id = start(RemoteOperationKind.DELETE, listOf("/tree"))
        assertEquals(RemoteFileMessage.TreeTooLarge, finished(id).detail)
        assertTrue(manager.prompts.value.isEmpty()); assertTrue(backend.writes.isEmpty())
    }
    @Test fun copyingIntoDescendantAndMutatingRootAreRejected() = runTest {
        backend.add("/tree", RemoteFileKind.DIRECTORY)
        val id = start(RemoteOperationKind.COPY, listOf("/tree"), "/tree/sub")
        assertEquals(RemoteFileMessage.OperationFailure(RemoteOperationFailure.INVALID_DESTINATION), finished(id).detail)
        val root = start(RemoteOperationKind.DELETE, listOf("/"))
        assertEquals(RemoteFileMessage.OperationFailure(RemoteOperationFailure.ROOT), finished(root).detail)
        assertTrue(backend.writes.isEmpty())
    }
}
