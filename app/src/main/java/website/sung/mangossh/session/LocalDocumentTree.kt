package website.sung.mangossh.session

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

/** Signals that a Storage Access Framework document could not be read or created. */
class LocalDocumentException(cause: Throwable? = null) : Exception(cause)

/** One child of a Storage Access Framework directory. */
internal data class LocalDocument(
    val documentUri: Uri,
    val name: String,
    val mimeType: String,
    val isDirectory: Boolean,
    val sizeBytes: Long?,
    val modifiedEpochMillis: Long? = null,
)

/** One local file discovered while enumerating a picked directory tree. */
internal data class LocalTreeEntry(
    val relativePath: String,
    val documentUri: Uri,
    val sizeBytes: Long?,
    val modifiedEpochMillis: Long? = null,
)

/**
 * Result of enumerating a picked local directory before uploading it.
 *
 * [directories] lists parents before children so the remote tree can be created
 * in order. [truncated] means the walk hit its entry or depth cap.
 */
internal data class LocalTreeWalk(
    val name: String,
    val directories: List<String>,
    val files: List<LocalTreeEntry>,
    val totalBytes: Long,
    val truncated: Boolean = false,
)

/**
 * Directory-tree operations over the Storage Access Framework.
 *
 * A directory transfer needs to create nested documents and enumerate a picked
 * tree, which the single-document contracts used elsewhere cannot express. Only
 * framework `DocumentsContract` calls are used, so no extra dependency is
 * needed. Document names and paths are user data: they are never logged.
 *
 * Every function blocks on a content provider and must be called off the main
 * dispatcher.
 */
internal object LocalDocumentTree {

    /** Per-execution directory cache; mutations are reflected before the next sibling is created. */
    class Targets(private val resolver: ContentResolver, private val treeUri: Uri) {
        private val children = mutableMapOf<Uri, MutableMap<String, LocalDocument>>()

        fun directory(parent: Uri, name: String): Uri = create(parent, name, DocumentsContract.Document.MIME_TYPE_DIR)
        fun file(parent: Uri, name: String, mimeType: String): Uri = create(parent, name, mimeType)

        /** Queries without creating, so conflict decisions describe the user's original files. */
        fun findFile(parent: Uri, name: String): Uri? = listChildren(resolver, treeUri, parent)
            .firstOrNull { it.name == name }?.also { if (it.isDirectory) throw LocalDocumentException() }?.documentUri

        /** Only used after staging and approval; never reuses an unexpectedly created target. */
        fun createFile(parent: Uri, name: String, mimeType: String): Uri {
            if (findFile(parent, name) != null) throw SourceChangedException()
            return createDocument(resolver, parent, mimeType, name)
        }


        private fun create(parent: Uri, name: String, mimeType: String): Uri {
            val entries = children.getOrPut(parent) {
                val listing = listChildren(resolver, treeUri, parent)
                if (listing.size > MAX_TRANSFER_TREE_ENTRIES) throw TransferTreeTooLargeException()
                listing.associateByTo(mutableMapOf()) { it.name }
            }
            entries[name]?.let {
                if (it.isDirectory != (mimeType == DocumentsContract.Document.MIME_TYPE_DIR)) throw LocalDocumentException()
                return it.documentUri
            }
            val uri = createDocument(resolver, parent, mimeType, name)
            entries[name] = LocalDocument(uri, name, mimeType, mimeType == DocumentsContract.Document.MIME_TYPE_DIR, null)
            return uri
        }
    }

    /** Returns the document that a tree URI granted by the picker points at. */
    fun rootOf(treeUri: Uri): Uri = try {
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
    } catch (error: IllegalArgumentException) {
        throw LocalDocumentException(error)
    }

    /** A provider query has its own deadline and is also cancelled with its parent transfer. */
    fun <T> queryDocument(resolver: ContentResolver, uri: Uri, columns: Array<String>,
        parent: TransferControl? = null, read: (android.database.Cursor) -> T): T = BlockingOperation(15_000).use { operation ->
        parent?.ownLocal(operation)
        val signal = android.os.CancellationSignal()
        val cancellation = java.io.Closeable { signal.cancel() }
        try {
            operation.ownLocal(cancellation)
            resolver.query(uri, columns, null, null, null, signal)?.use { cursor ->
                if (!operation.shouldContinue()) throw java.io.InterruptedIOException()
                read(cursor).also { if (!operation.shouldContinue()) throw java.io.InterruptedIOException() }
            } ?: throw LocalDocumentException()
        } finally {
            operation.releaseLocal(cancellation)
            parent?.releaseLocal(operation)
        }
    }

    /** Returns the display name of [documentUri], or null when the provider omits it. */
    fun displayName(resolver: ContentResolver, documentUri: Uri): String? = runCatching {
        queryDocument(resolver, documentUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)) {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()

    /** Lists the children of a directory document inside [treeUri]. */
    fun listChildren(
        resolver: ContentResolver,
        treeUri: Uri,
        parentDocumentUri: Uri,
        maxEntries: Int = MAX_TRANSFER_TREE_ENTRIES + 1,
        control: TransferControl? = null,
    ): List<LocalDocument> {
        if (control == null) return BlockingOperation(15_000).use { listChildren(resolver, treeUri, parentDocumentUri, maxEntries, it) }
        if (!control.shouldContinue()) throw java.io.InterruptedIOException()
        val cancellation = android.os.CancellationSignal()
        val cancelQuery = java.io.Closeable { cancellation.cancel() }
        control.ownLocal(cancelQuery)
        val childrenUri = try {
            DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                DocumentsContract.getDocumentId(parentDocumentUri),
            )
        } catch (error: IllegalArgumentException) {
            throw LocalDocumentException(error)
        }
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val cursor = try {
            resolver.query(childrenUri, projection, null, null, null, cancellation)
        } catch (error: Exception) {
            throw LocalDocumentException(error)
        } ?: throw LocalDocumentException()
        try { return cursor.use {
            buildList {
                while (size < maxEntries && cursor.moveToNext()) {
                    if (!control.shouldContinue()) throw java.io.InterruptedIOException()
                    val documentId = cursor.getString(0) ?: throw LocalDocumentException()
                    val name = cursor.getString(1) ?: throw LocalDocumentException()
                    val mimeType = cursor.getString(2).orEmpty()
                    val size = if (cursor.isNull(3)) null else cursor.getLong(3)
                    add(
                        LocalDocument(
                            documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
                            name = name,
                            mimeType = mimeType,
                            isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR,
                            sizeBytes = size,
                            modifiedEpochMillis = if (cursor.isNull(4)) null else cursor.getLong(4).takeIf { it > 0 },
                        ),
                    )
                }
            }
        } } finally { control.releaseLocal(cancelQuery) }
    }

    /**
     * Returns the directory named [name] under [parentDocumentUri], creating it
     * when it is missing.
     *
     * An existing directory is reused rather than duplicated, so re-running a
     * folder download merges into the tree the user already has instead of
     * producing `name (1)` copies.
     */
    fun createDirectory(
        resolver: ContentResolver,
        treeUri: Uri,
        parentDocumentUri: Uri,
        name: String,
    ): Uri {
        listChildren(resolver, treeUri, parentDocumentUri)
            .firstOrNull { it.isDirectory && it.name == name }
            ?.let { return it.documentUri }
        return createDocument(
            resolver = resolver,
            parentDocumentUri = parentDocumentUri,
            mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
            name = name,
        )
    }

    /**
     * Returns a writable document named [name] under [parentDocumentUri].
     *
     * An existing file of the same name is reused so a retried transfer
     * overwrites it instead of accumulating renamed copies.
     */
    fun createFile(
        resolver: ContentResolver,
        treeUri: Uri,
        parentDocumentUri: Uri,
        mimeType: String,
        name: String,
    ): Uri {
        listChildren(resolver, treeUri, parentDocumentUri)
            .firstOrNull { !it.isDirectory && it.name == name }
            ?.let { return it.documentUri }
        return createDocument(
            resolver = resolver,
            parentDocumentUri = parentDocumentUri,
            mimeType = mimeType,
            name = name,
        )
    }

    /**
     * Enumerates the tree under [treeUri] breadth-first for a folder upload.
     *
     * The walk stops at [maxEntries] and [maxDepth] and reports that as
     * truncation rather than uploading a partial tree silently.
     */
    fun walk(
        resolver: ContentResolver,
        treeUri: Uri,
        maxEntries: Int,
        maxDepth: Int,
        parentControl: TransferControl? = null,
    ): LocalTreeWalk = BlockingOperation(60_000).use { control ->
        parentControl?.ownLocal(control)
        try { scan(resolver, treeUri, maxEntries, maxDepth, control) }
        finally { parentControl?.releaseLocal(control) }
    }

    private fun scan(resolver: ContentResolver, treeUri: Uri, maxEntries: Int, maxDepth: Int, control: TransferControl): LocalTreeWalk {
        val root = rootOf(treeUri)
        val directories = mutableListOf<String>()
        val files = mutableListOf<LocalTreeEntry>()
        var totalBytes = 0L
        var truncated = false
        val pending = ArrayDeque<Triple<Uri, String, Int>>()
        pending.addLast(Triple(root, "", 0))
        var visited = 0
        traversal@ while (pending.isNotEmpty()) {
            if (!control.shouldContinue()) throw java.io.InterruptedIOException()
            val (documentUri, prefix, depth) = pending.removeFirst()
            for (child in listChildren(resolver, treeUri, documentUri, maxEntries - visited + 1, control)) {
                if (++visited > maxEntries) { truncated = true; break@traversal }
                if (runCatching { RemoteFilePaths.requireSafeRemoteName(child.name) }.isFailure) {
                    truncated = true
                    continue
                }
                val relative = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                if (child.isDirectory) {
                    if (depth + 1 > maxDepth) {
                        truncated = true
                        break@traversal
                    }
                    directories += relative
                    pending.addLast(Triple(child.documentUri, relative, depth + 1))
                } else {
                    if (files.size >= maxEntries) {
                        truncated = true
                        break@traversal
                    }
                    files += LocalTreeEntry(
                        relativePath = relative,
                        documentUri = child.documentUri,
                        sizeBytes = child.sizeBytes,
                        modifiedEpochMillis = child.modifiedEpochMillis,
                    )
                    totalBytes += child.sizeBytes ?: 0L
                }
            }
        }
        return LocalTreeWalk(
            name = displayName(resolver, root).orEmpty(),
            directories = directories,
            files = files,
            totalBytes = totalBytes,
            truncated = truncated,
        )
    }

    private fun createDocument(
        resolver: ContentResolver,
        parentDocumentUri: Uri,
        mimeType: String,
        name: String,
    ): Uri = try {
        DocumentsContract.createDocument(resolver, parentDocumentUri, mimeType, name)
            ?: throw LocalDocumentException()
    } catch (error: LocalDocumentException) {
        throw error
    } catch (error: Exception) {
        throw LocalDocumentException(error)
    }
}
