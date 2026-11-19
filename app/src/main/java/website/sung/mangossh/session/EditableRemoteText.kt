package website.sung.mangossh.session

import java.security.MessageDigest

/** In-memory draft source; content and digests must never enter logs, diagnostics, or backups. */
internal data class EditableRemoteText(
    val path: String,
    val text: String,
    val hasBom: Boolean,
    val newline: String,
    val identity: SourceIdentity,
    val target: RemoteTarget,
    val digest: ByteArray,
    val platform: RemotePlatform = RemotePlatform.AUTO,
) {
    private val useCrLf = newline == "\r\n" && !LONE_LF.containsMatchIn(text)

    /** Size of the finished UTF-8 file, including the preserved BOM and newline conversion. */
    fun encodedSize(draft: String): Int {
        val bytes = encodeBytes(draft)
        return bytes.size.also { bytes.fill(0) }
    }

    fun encode(draft: String): ByteArray = encodeBytes(draft).also {
        if (it.size > MAX_REMOTE_PREVIEW_BYTES) {
            it.fill(0)
            throw RemoteFileException(RemoteFileFailure.TOO_LARGE)
        }
    }

    private fun encodeBytes(draft: String): ByteArray {
        val formatted = if (draft != text && useCrLf) draft.replace(LONE_LF, "\r\n") else draft
        return ((if (hasBom) "\uFEFF" else "") + formatted).encodeToByteArray()
    }

    fun requireUnchanged(current: EditableRemoteText) {
        identity.requireMatches(current.identity)
        if (!MessageDigest.isEqual(digest, current.digest)) throw SourceChangedException()
    }

    private companion object {
        val LONE_LF = Regex("(?<!\\r)\\n")
    }
}
