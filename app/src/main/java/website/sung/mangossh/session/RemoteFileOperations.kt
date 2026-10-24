package website.sung.mangossh.session

import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import website.sung.mangossh.session.ssh.SshConnection
import website.sung.mangossh.session.ssh.SshFileAttributes
import website.sung.mangossh.session.ssh.SshFileFailure
import website.sung.mangossh.session.ssh.SshFiles

/** LSTAT snapshot; atime is excluded because reading a file can legitimately change it. */
internal data class RemoteNode(val path: String, val attributes: SshFileAttributes, val linkTarget: String? = null) {
    val kind: RemoteFileKind get() = when {
        attributes.isDirectory -> RemoteFileKind.DIRECTORY
        attributes.isRegularFile -> RemoteFileKind.FILE
        attributes.isSymlink -> RemoteFileKind.SYMLINK
        else -> RemoteFileKind.OTHER
    }
    fun requireSame(other: RemoteNode?, directoryContentsMayChange: Boolean = false) {
        fun stable(a: SshFileAttributes) = if (directoryContentsMayChange && kind == RemoteFileKind.DIRECTORY)
            a.copy(atime = null, mtime = null, size = null) else a.copy(atime = null)
        if (other == null || path != other.path || stable(attributes) != stable(other.attributes) || linkTarget != other.linkTarget)
            throw SourceChangedException()
    }
}

/** Injectable SFTP boundary used by the task engine and isolated failure tests. */
internal interface RemoteOperationBackend {
    suspend fun node(path: String): RemoteNode?
    suspend fun children(directory: RemoteNode): List<RemoteNode>
    suspend fun canonical(path: String): String
    suspend fun atomicReplace(): Boolean
    suspend fun digest(node: RemoteNode): ByteArray
    suspend fun create(path: String, directory: Boolean)
    suspend fun remove(node: RemoteNode)
    suspend fun move(node: RemoteNode, destination: String, target: RemoteNode?)
    suspend fun chmod(node: RemoteNode, mode: Int)
    suspend fun copy(node: RemoteNode, destination: String, target: RemoteNode?, targetDigest: ByteArray?, direct: Boolean,
        progress: (Long) -> Unit, committing: suspend (suspend () -> Unit) -> Unit)
    suspend fun finishDirectory(source: RemoteNode, destination: String)
}

/** Protocol-only mutations with revalidation at the last practical SFTP boundary. */
internal class SftpRemoteOperationBackend(
    private val files: RemoteFileClient,
    private val connection: SshConnection,
    private val platform: RemotePlatform,
    private val control: TransferControl,
) : RemoteOperationBackend {
    private suspend fun SshFiles.snapshot(path: String): RemoteNode? {
        val attrs = try { lstat(path) } catch (error: SshFileFailure) {
            if (error.status == 2 || error.status == 10) return null else throw error
        }
        return RemoteNode(path, attrs, if (attrs.isSymlink) readlink(path) else null)
    }

    override suspend fun node(path: String): RemoteNode? = files.withClient(connection, control) { it.snapshot(path) }
    override suspend fun canonical(path: String): String = files.withClient(connection, control) { RemoteFilePaths.normalize(it.canonicalPath(path)) }
    override suspend fun atomicReplace(): Boolean = platform == RemotePlatform.POSIX && files.withClient(connection, control) { it.atomicReplaceSupported }

    override suspend fun children(directory: RemoteNode): List<RemoteNode> = files.withClient(connection, control) { client ->
        directory.requireSame(client.snapshot(directory.path))
        if (directory.kind != RemoteFileKind.DIRECTORY) throw SourceChangedException()
        if (RemoteFilePaths.normalize(client.canonicalPath(directory.path)) != directory.path) throw SourceChangedException()
        val raw = client.list(directory.path, MAX_TRANSFER_TREE_ENTRIES + 3)
        if (raw.size >= MAX_TRANSFER_TREE_ENTRIES + 3) throw TransferTreeTooLargeException()
        val entries = raw.filter { it.filename != "." && it.filename != ".." }
        if (entries.size > MAX_TRANSFER_TREE_ENTRIES) throw TransferTreeTooLargeException()
        entries.map { entry ->
            RemoteOperationRules.requireName(entry.filename, platform)
            client.snapshot(RemoteFilePaths.join(directory.path, entry.filename)) ?: throw SourceChangedException()
        }.also {
            if (it.map(RemoteNode::path).distinct().size != it.size) throw SourceChangedException()
            directory.requireSame(client.snapshot(directory.path))
        }
    }

    override suspend fun digest(node: RemoteNode): ByteArray {
        node.requireSame(node(node.path))
        return files.sha256(connection, node.path, control).also { node.requireSame(node(node.path)) }
    }

    /** Only the parent is canonicalized: canonicalizing the final component would follow a link. */
    private suspend fun checkParent(client: SshFiles, path: String) {
        val parent = RemoteFilePaths.parentOf(path)
        if (RemoteFilePaths.normalize(client.canonicalPath(parent)) != parent) throw SourceChangedException()
        if (!client.lstat(parent).isDirectory) throw SourceChangedException()
    }

    override suspend fun create(path: String, directory: Boolean) = files.withClient(connection, control) { client ->
        checkParent(client, path)
        if (client.snapshot(path) != null) throw RemoteOperationException(RemoteOperationFailure.EXISTS)
        if (directory) client.mkdir(path, RemoteFileClient.DIRECTORY_PERMISSIONS)
        else client.close(client.open(path, write = true, create = true, exclusive = true, permissions = RemoteFileClient.NEW_FILE_PERMISSIONS))
    }

    override suspend fun remove(node: RemoteNode) = files.withClient(connection, control) { client ->
        checkParent(client, node.path)
        node.requireSame(client.snapshot(node.path), directoryContentsMayChange = true)
        when (node.kind) {
            RemoteFileKind.DIRECTORY -> client.rmdir(node.path)
            RemoteFileKind.FILE, RemoteFileKind.SYMLINK -> client.remove(node.path)
            else -> throw RemoteOperationException(RemoteOperationFailure.UNSUPPORTED)
        }
    }

    override suspend fun move(node: RemoteNode, destination: String, target: RemoteNode?) = files.withClient(connection, control) { client ->
        checkParent(client, node.path)
        checkParent(client, destination)
        node.requireSame(client.snapshot(node.path))
        if (target == null) {
            if (client.snapshot(destination) != null) throw SourceChangedException()
            client.rename(node.path, destination)
        } else {
            target.requireSame(client.snapshot(destination))
            if (platform != RemotePlatform.POSIX || node.kind != RemoteFileKind.FILE || target.kind != RemoteFileKind.FILE)
                throw RemoteOperationException(RemoteOperationFailure.UNSUPPORTED)
            client.replaceAtomically(node.path, destination)
        }
    }

    override suspend fun chmod(node: RemoteNode, mode: Int) = files.withClient(connection, control) { client ->
        if (platform != RemotePlatform.POSIX) throw RemoteOperationException(RemoteOperationFailure.PLATFORM_REQUIRED)
        checkParent(client, node.path)
        node.requireSame(client.snapshot(node.path))
        if (node.kind !in setOf(RemoteFileKind.FILE, RemoteFileKind.DIRECTORY)) throw RemoteOperationException(RemoteOperationFailure.UNSUPPORTED)
        client.setstat(node.path, SshFileAttributes(permissions = mode))
        val after = client.lstat(node.path)
        if (after.permissions?.and(0xfff) != mode || after.uid != node.attributes.uid || after.gid != node.attributes.gid)
            throw MetadataPreservationException()
    }

    override suspend fun copy(node: RemoteNode, destination: String, target: RemoteNode?, targetDigest: ByteArray?, direct: Boolean,
        progress: (Long) -> Unit, committing: suspend (suspend () -> Unit) -> Unit) {
        node.requireSame(node(node.path))
        if (node.kind == RemoteFileKind.SYMLINK) {
            if (target != null) throw RemoteOperationException(RemoteOperationFailure.EXISTS)
            committing { files.withClient(connection, control) { client ->
                checkParent(client, node.path)
                checkParent(client, destination)
                node.requireSame(client.snapshot(node.path))
                if (client.snapshot(destination) != null) throw SourceChangedException()
                client.symlink(checkNotNull(node.linkTarget), destination)
                val copied = client.snapshot(destination)
                if (copied?.kind != RemoteFileKind.SYMLINK || copied.linkTarget != node.linkTarget) throw SourceChangedException()
            } }
            return
        }
        if (node.kind != RemoteFileKind.FILE) throw RemoteOperationException(RemoteOperationFailure.UNSUPPORTED)
        val directory = RemoteFilePaths.parentOf(destination)
        val token = UUID.randomUUID().toString()
        val temporary = files.reserveTemporary(connection, directory, token, control)
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            files.withClient(connection, control) { client ->
                checkParent(client, node.path)
                checkParent(client, destination)
                node.requireSame(client.snapshot(node.path))
                val input = client.open(node.path)
                try {
                    RemoteStagingIdentity.requireMatches(node.path, node.attributes, client.fstat(input), client.lstat(node.path),
                        platform == RemotePlatform.WINDOWS)
                    val reserved = client.lstat(temporary)
                    val output = client.open(temporary, write = true)
                    try {
                        RemoteStagingIdentity.requireMatches(temporary, reserved, client.fstat(output), client.lstat(temporary),
                            platform == RemotePlatform.WINDOWS)
                        var offset = 0L
                        while (control.shouldContinue()) {
                            val bytes = client.read(input, offset, RemoteFileClient.SFTP_CHUNK_BYTES) ?: break
                            if (bytes.isEmpty()) throw SourceChangedException()
                            try {
                                client.write(output, offset, bytes)
                                hash.update(bytes)
                                offset += bytes.size
                                if (node.attributes.size != null && offset > node.attributes.size) throw SourceChangedException()
                                progress(bytes.size.toLong())
                                control.progressed()
                            } finally { bytes.fill(0) }
                        }
                        if (!control.shouldContinue()) throw kotlinx.coroutines.CancellationException()
                        if (node.attributes.size != null && offset != node.attributes.size) throw SourceChangedException()
                        node.requireSame(client.snapshot(node.path))
                    } finally { client.close(output) }
                } finally { client.close(input) }
            }
            val expected = hash.digest()
            if (!MessageDigest.isEqual(expected, digest(node)) || !MessageDigest.isEqual(expected, files.sha256(connection, temporary, control)))
                throw SourceChangedException()
            // A conflict decision authorizes the captured destination, not a later replacement.
            target?.requireSame(node(destination))
            val inspected = files.inspectTarget(connection, destination, control)
            if (target == null && inspected.identity != null) throw SourceChangedException()
            val safeTarget = inspected.copy(atomicReplace = inspected.atomicReplace && platform == RemotePlatform.POSIX)
            files.withClient(connection, control) { client ->
                if (node.attributes.mtime != null) client.setstat(temporary,
                    SshFileAttributes(atime = node.attributes.atime ?: node.attributes.mtime, mtime = node.attributes.mtime))
            }
            committing {
                files.commitTemporary(connection, temporary, destination, safeTarget, direct, control, targetDigest,
                    if (target == null && platform == RemotePlatform.POSIX) node.attributes.permissions?.and(0x1ff) else null)
                if (safeTarget.identity != null && !safeTarget.atomicReplace &&
                    !MessageDigest.isEqual(expected, files.sha256(connection, destination, control))) throw SourceChangedException()
                // Direct overwrite retains the target ACL; restore source mtime only after bytes are verified.
                files.withClient(connection, control) { client ->
                    if (node.attributes.mtime != null) {
                        client.setstat(destination, SshFileAttributes(atime = node.attributes.atime ?: node.attributes.mtime, mtime = node.attributes.mtime))
                        if (client.lstat(destination).mtime != node.attributes.mtime) throw MetadataPreservationException()
                    }
                }
            }
        } finally {
            withContext(NonCancellable) { runCatching { files.removeTemporary(connection, temporary, token) } }
        }
    }

    override suspend fun finishDirectory(source: RemoteNode, destination: String) = files.withClient(connection, control) { client ->
        source.requireSame(client.snapshot(source.path))
        checkParent(client, destination)
        if (!client.lstat(destination).isDirectory) throw SourceChangedException()
        val mode = if (platform == RemotePlatform.POSIX) source.attributes.permissions?.and(0x1ff) else null
        client.setstat(destination, SshFileAttributes(permissions = mode,
            atime = source.attributes.atime ?: source.attributes.mtime, mtime = source.attributes.mtime))
        val after = client.lstat(destination)
        if (mode != null && after.permissions?.and(0xfff) != mode || source.attributes.mtime != null && after.mtime != source.attributes.mtime)
            throw MetadataPreservationException()
    }
}
