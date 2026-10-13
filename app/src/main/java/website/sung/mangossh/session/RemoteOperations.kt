package website.sung.mangossh.session

import androidx.compose.runtime.Immutable

/** Connection-local interpretation of SFTP paths and permission semantics. */
enum class RemotePlatform { AUTO, POSIX, WINDOWS;
    /** AUTO only recognizes an unambiguous drive path; it never guesses from a banner. */
    fun resolve(path: String): RemotePlatform = if (this == AUTO && RemoteStagingIdentity.isWindowsPath(path)) WINDOWS else this
}

/** Validation shared by dialogs and the operation engine; input is never trimmed or rewritten. */
object RemoteOperationRules {
    /** Rejects unsupported components without rewriting the user's chosen name. */
    fun requireName(name: String, platform: RemotePlatform): String {
        RemoteFilePaths.requireSafeRemoteName(name)
        if (platform == RemotePlatform.WINDOWS) {
            require(name.none { it.code < 32 || it in "\\<>:\"|?*" })
            require(!name.endsWith('.') && !name.endsWith(' '))
            val base = name.substringBefore('.').uppercase(java.util.Locale.ROOT)
            require(base !in setOf("CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$") &&
                !Regex("(COM|LPT)[1-9¹²³]").matches(base))
        }
        return name
    }

    /** A drive root and the protocol root are never mutation targets. */
    fun requireMutable(path: String) {
        val normalized = RemoteFilePaths.normalize(path)
        require(normalized != "/" && !Regex("/[A-Za-z]:").matches(normalized))
    }

    /** Tests component containment with the selected platform's case policy. */
    fun contains(parent: String, child: String, platform: RemotePlatform): Boolean {
        val base = RemoteFilePaths.normalize(parent)
        val target = RemoteFilePaths.normalize(child)
        val insensitive = platform == RemotePlatform.WINDOWS
        return target.equals(base, insensitive) || target.startsWith(if (base == "/") base else "$base/", insensitive)
    }

    /** Parses only an explicit three/four-digit octal mode. */
    fun mode(text: String): Int? = text.takeIf { Regex("[0-7]{3,4}").matches(it) }?.toIntOrNull(8)
}

/** Only these user-visible operations may change a remote tree. */
enum class RemoteOperationKind { CREATE_FILE, CREATE_DIRECTORY, RENAME, COPY, MOVE, DELETE, PERMISSIONS }

/** Special bits are preserved independently for every item unless explicitly supplied. */
@Immutable
data class RemotePermissionChange(val fileMode: Int, val directoryMode: Int, val specialBits: Int? = null, val recursive: Boolean = false) {
    init { require(fileMode in 0..0x1ff && directoryMode in 0..0x1ff && (specialBits == null || specialBits in 0..7)) }
    /** Applies ordinary bits and preserves per-item special bits unless explicitly overridden. */
    fun apply(previous: Int, directory: Boolean): Int = (if (directory) directoryMode else fileMode) or
        (specialBits?.shl(9) ?: (previous and 0xe00))
}

/** Immutable, process-local intent. Paths are private user data and must never enter logs. */
@Immutable
data class RemoteOperationRequest(
    val sessionId: String,
    val kind: RemoteOperationKind,
    val sources: List<String> = emptyList(),
    val destination: String? = null,
    val name: String? = null,
    val platform: RemotePlatform = RemotePlatform.AUTO,
    val permissions: RemotePermissionChange? = null,
)

/** A terminal task is never replayed automatically after disconnect or process death. */
enum class RemoteOperationPhase { QUEUED, SCANNING, WAITING, RUNNING, COMMITTING, COMPLETED, FAILED, CANCELLED }

/** Per-item outcomes remain visible even when a later item fails. */
enum class RemoteItemOutcome { PENDING, PARTIAL, COMPLETED, SKIPPED, FAILED }

@Immutable
/** One source item and its last known outcome; PARTIAL includes a created but unfinished folder. */
data class RemoteOperationItem(val path: String, val outcome: RemoteItemOutcome = RemoteItemOutcome.PENDING)

/** Progress and sanitized failure only; paths are rendered verbatim in the task panel. */
@Immutable
data class RemoteOperationState(
    val id: String,
    val request: RemoteOperationRequest,
    val phase: RemoteOperationPhase = RemoteOperationPhase.QUEUED,
    val items: List<RemoteOperationItem> = emptyList(),
    val currentPath: String? = null,
    val transferredBytes: Long = 0,
    val detail: RemoteFileMessage? = null,
) {
    val active: Boolean get() = phase !in setOf(RemoteOperationPhase.COMPLETED, RemoteOperationPhase.FAILED, RemoteOperationPhase.CANCELLED)
    val canCancel: Boolean get() = active && phase != RemoteOperationPhase.COMMITTING
}

/** One-use confirmation bound to an execution and its captured source/destination snapshots. */
@Immutable
data class RemoteOperationPrompt(
    val id: String,
    val taskId: String,
    val request: RemoteOperationRequest,
    val paths: List<String>,
    val conflict: Boolean = false,
    val replaceAllowed: Boolean = false,
    val directOverwrite: Boolean = false,
)

/** Explicit choices accepted by a single manager-owned preview or conflict. */
enum class RemoteOperationChoice { CANCEL, PROCEED, SKIP, SAVE_AS, REPLACE, DIRECT_OVERWRITE }

/** Direct replacement requires a separate UI confirmation and is never remembered task-wide. */
data class RemoteOperationDecision(val choice: RemoteOperationChoice, val name: String? = null, val applyToTask: Boolean = false)

/** Clipboard is scoped to one live connection and contains no file contents. */
@Immutable
data class RemoteClipboard(val sessionId: String, val paths: List<String>, val move: Boolean)

/** Sanitized operation-specific failures without server text. */
enum class RemoteOperationFailure { INVALID_NAME, ROOT, EXISTS, PLATFORM_REQUIRED, WINDOWS_PERMISSIONS, UNSUPPORTED,
    MOVE_FAILED, CASE_ONLY, INVALID_DESTINATION }

internal class RemoteOperationException(val reason: RemoteOperationFailure) : Exception()
