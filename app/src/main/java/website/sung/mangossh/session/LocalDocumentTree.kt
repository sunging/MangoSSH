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
)

/** One local file discovered while enumerating a picked directory tree. */
internal data class LocalTreeEntry(
    val relativePath: String,
    val documentUri: Uri,
    val sizeBytes: Long?,
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

    /** Returns the document that a tree URI granted by the picker points at. */
    fun rootOf(treeUri: Uri): Uri = try {
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
    } catch (error: IllegalArgumentException) {
        throw LocalDocumentException(error)
    }

    /** Returns the display name of [documentUri], or null when the provider omits it. */
    fun displayName(resolver: ContentResolver, documentUri: Uri): String? = runCatching {
        resolver.query(
            documentUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    /** Lists the children of a directory document inside [treeUri]. */
    fun listChildren(
        resolver: ContentResolver,
        treeUri: Uri,
        parentDocumentUri: Uri,
    ): List<LocalDocument> {
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
        )
        val cursor = try {
            resolver.query(childrenUri, projection, null, null, null)
        } catch (error: Exception) {
            throw LocalDocumentException(error)
        } ?: throw LocalDocumentException()
        return cursor.use {
            buildList {
                while (cursor.moveToNext()) {
                    val documentId = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    val mimeType = cursor.getString(2).orEmpty()
                    val size = if (cursor.isNull(3)) null else cursor.getLong(3)
                    add(
                        LocalDocument(
                            documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
                            name = name,
                            mimeType = mimeType,
                            isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR,
                            sizeBytes = size,
                        ),
                    )
                }
            }
        }
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
    ): LocalTreeWalk {
        val root = rootOf(treeUri)
        val directories = mutableListOf<String>()
        val files = mutableListOf<LocalTreeEntry>()
        var totalBytes = 0L
        var truncated = false
        val pending = ArrayDeque<Triple<Uri, String, Int>>()
        pending.addLast(Triple(root, "", 0))
        while (pending.isNotEmpty()) {
            val (documentUri, prefix, depth) = pending.removeFirst()
            for (child in listChildren(resolver, treeUri, documentUri)) {
                if (runCatching { RemoteFilePaths.requireSafeRemoteName(child.name) }.isFailure) {
                    truncated = true
                    continue
                }
                val relative = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                if (child.isDirectory) {
                    if (depth + 1 > maxDepth) {
                        truncated = true
                        continue
                    }
                    directories += relative
                    pending.addLast(Triple(child.documentUri, relative, depth + 1))
                } else {
                    if (files.size >= maxEntries) {
                        truncated = true
                        continue
                    }
                    files += LocalTreeEntry(
                        relativePath = relative,
                        documentUri = child.documentUri,
                        sizeBytes = child.sizeBytes,
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
