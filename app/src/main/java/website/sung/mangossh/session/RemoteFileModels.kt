package website.sung.mangossh.session

import androidx.compose.runtime.Immutable
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Upper bound on entries the browser keeps for one directory. */
const val MAX_REMOTE_DIRECTORY_ENTRIES: Int = 5_000

/** Upper bound on the leading bytes fetched for a read-only text preview. */
const val MAX_REMOTE_PREVIEW_BYTES: Int = 128 * 1024

/** Coarse remote entry classification used to pick navigation and icon behavior. */
enum class RemoteFileKind {
    DIRECTORY,
    FILE,
    SYMLINK,
    OTHER,
}

/**
 * One entry of a remote directory listing.
 *
 * [path] is the absolute remote path already joined with its parent so the UI
 * never has to rebuild it. Every field originates from the remote server and is
 * user data: it must never be translated, and it must never be logged.
 */
@Immutable
data class RemoteFileEntry(
    val name: String,
    val path: String,
    val kind: RemoteFileKind,
    val sizeBytes: Long? = null,
    val modifiedEpochSeconds: Long? = null,
    val permissions: String? = null,
)

/**
 * A single remote directory snapshot.
 *
 * [truncated] reports that the server returned more entries than the browser is
 * willing to hold, so the list is intentionally incomplete.
 */
@Immutable
data class RemoteDirectoryListing(
    val sessionId: String,
    val path: String,
    val entries: List<RemoteFileEntry>,
    val truncated: Boolean = false,
)

/**
 * Read-only text rendering of a remote file.
 *
 * [truncated] means only the leading bytes were fetched; [totalSizeBytes] is the
 * full remote size when the server reported it.
 */
@Immutable
data class RemoteTextPreview(
    val path: String,
    val text: String,
    val truncated: Boolean,
    val totalSizeBytes: Long?,
)

/**
 * Absolute POSIX path arithmetic for the remote browser.
 *
 * SFTP carries paths as protocol fields rather than through a remote shell, so
 * unlike the SCP helpers in [SshSessionController] these functions deliberately
 * allow spaces and punctuation. Only characters that cannot appear inside a
 * single path component are rejected.
 */
object RemoteFilePaths {
    const val ROOT: String = "/"

    /** Collapses `.`/`..`/duplicate separators into an absolute canonical path. */
    fun normalize(path: String): String {
        val segments = ArrayDeque<String>()
        path.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> segments.removeLastOrNull()
                else -> segments.addLast(segment)
            }
        }
        return if (segments.isEmpty()) ROOT else segments.joinToString(separator = "/", prefix = "/")
    }

    /** Returns the absolute path of [name] inside [parent]. */
    fun join(parent: String, name: String): String {
        requireSafeRemoteName(name)
        val base = normalize(parent)
        return if (base == ROOT) "$ROOT$name" else "$base/$name"
    }

    /** Returns the containing directory, or [ROOT] when already at the top. */
    fun parentOf(path: String): String {
        val normalized = normalize(path)
        if (normalized == ROOT) return ROOT
        val cut = normalized.lastIndexOf('/')
        return if (cut <= 0) ROOT else normalized.substring(0, cut)
    }

    /** Returns the last component, or [ROOT] for the root directory itself. */
    fun nameOf(path: String): String {
        val normalized = normalize(path)
        if (normalized == ROOT) return ROOT
        return normalized.substringAfterLast('/')
    }

    /**
     * Returns the breadcrumb trail of [path] as label/target pairs, starting at
     * the root. Labels are remote-provided user data.
     */
    fun breadcrumbs(path: String): List<Pair<String, String>> {
        val normalized = normalize(path)
        val crumbs = mutableListOf(ROOT to ROOT)
        if (normalized == ROOT) return crumbs
        var current = ""
        normalized.trim('/').split('/').forEach { segment ->
            current = "$current/$segment"
            crumbs += segment to current
        }
        return crumbs
    }

    /** Rejects names that cannot be addressed as a single remote path component. */
    fun requireSafeRemoteName(name: String): String {
        require(name.isNotEmpty()) { "Remote name is empty" }
        require(name != "." && name != "..") { "Remote name is not a file" }
        require(name.none { it == '/' || it == '\n' || it == '\r' || it == '\u0000' }) {
            "Remote name contains unsupported characters"
        }
        return name
    }

    /** Orders a listing directories-first, then case-insensitively by name. */
    fun sortForDisplay(entries: List<RemoteFileEntry>): List<RemoteFileEntry> = entries.sortedWith(
        compareBy<RemoteFileEntry> { if (it.kind == RemoteFileKind.DIRECTORY) 0 else 1 }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
    )
}

/**
 * Decides whether remote bytes can be shown as text and decodes them.
 *
 * The remote server is untrusted, so a file is only rendered when it decodes as
 * strict UTF-8 and carries no NUL bytes. A capped read may end in the middle of
 * a multi-byte sequence, so the trailing partial character is dropped rather
 * than failing the whole preview.
 */
object RemoteTextDecoder {
    /** Returns null when [bytes] look binary and must not be rendered as text. */
    fun decode(
        path: String,
        bytes: ByteArray,
        truncated: Boolean,
        totalSizeBytes: Long?,
    ): RemoteTextPreview? {
        if (bytes.any { it == 0.toByte() }) return null
        val decodable = if (truncated) bytes.copyOf(completeSequenceLength(bytes)) else bytes
        val text = decodeStrictUtf8(decodable) ?: return null
        return RemoteTextPreview(
            path = path,
            text = text,
            truncated = truncated,
            totalSizeBytes = totalSizeBytes,
        )
    }

    /** Decodes with reporting error actions so invalid bytes fail instead of becoming U+FFFD. */
    private fun decodeStrictUtf8(bytes: ByteArray): String? {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching { decoder.decode(ByteBuffer.wrap(bytes)).toString() }.getOrNull()
    }

    /** Returns the length of [bytes] without a trailing incomplete UTF-8 sequence. */
    private fun completeSequenceLength(bytes: ByteArray): Int {
        var index = bytes.size - 1
        var continuations = 0
        while (index >= 0 && continuations <= MAX_UTF8_SEQUENCE) {
            val value = bytes[index].toInt() and 0xFF
            if (value and 0xC0 != 0x80) {
                val expected = when {
                    value and 0x80 == 0x00 -> 1
                    value and 0xE0 == 0xC0 -> 2
                    value and 0xF0 == 0xE0 -> 3
                    value and 0xF8 == 0xF0 -> 4
                    // A stray continuation-free invalid byte is left for the decoder to reject.
                    else -> return bytes.size
                }
                return if (bytes.size - index >= expected) bytes.size else index
            }
            continuations += 1
            index -= 1
        }
        return bytes.size
    }

    private const val MAX_UTF8_SEQUENCE = 4
}
