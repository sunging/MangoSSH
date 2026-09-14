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
) {
    fun encode(draft: String): ByteArray {
        val formatted = if (draft != text && newline == "\r\n" && !Regex("(?<!\\r)\\n").containsMatchIn(text)) draft.replace(Regex("(?<!\\r)\\n"), "\r\n") else draft
        return ((if (hasBom) "\uFEFF" else "") + formatted).toByteArray(Charsets.UTF_8)
            .also { require(it.size <= MAX_REMOTE_PREVIEW_BYTES) }
    }

    fun requireUnchanged(current: EditableRemoteText) {
        identity.requireMatches(current.identity)
        if (!MessageDigest.isEqual(digest, current.digest)) throw SourceChangedException()
    }
}
