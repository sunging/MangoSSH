package website.sung.mangossh.session

/** One provider row; fixture readiness never needs a URI, document name or file contents. */
internal data class DocumentWriteMetadata(val size: Long?, val modifiedMillis: Long?)

/**
 * Waits for a test-owned write's metadata before a transfer captures its baseline.
 * DownloadProvider publishes size and modification time in its asynchronous close callback.
 * [waitUntil] must be a bounded test wait; unknown size is never accepted as an empty file.
 * This is preparation only: later source changes still go through production identity checks.
 */
internal fun awaitDocumentWritePublication(
    expectedBytes: Long,
    readMetadata: () -> DocumentWriteMetadata,
    waitUntil: (() -> Boolean) -> Unit,
): DocumentWriteMetadata {
    require(expectedBytes >= 0)
    var published: DocumentWriteMetadata? = null
    waitUntil {
        val current = readMetadata()
        if (current.size == expectedBytes && current.modifiedMillis?.let { it > 0 } == true) {
            published = current
            true
        } else false
    }
    return checkNotNull(published) { "The fixture write metadata was not published" }
}
