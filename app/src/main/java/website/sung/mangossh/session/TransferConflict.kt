package website.sung.mangossh.session

/** A process-local prompt bound to one task; directory previews authorize no file replacement. */
data class TransferConflict(
    val id: String,
    val name: String,
    val direction: ScpTransferDirection,
    val targetExists: Boolean,
    val atomicReplace: Boolean,
    val taskId: String = id,
    val taskKind: ScpTransferKind = ScpTransferKind.FILE,
    val promptKind: TransferPromptKind = TransferPromptKind.FILE_PREVIEW,
    val destinationDirectory: String? = null,
    val totalFiles: Int? = null,
    val totalBytes: Long? = null,
    val verifySha256: Boolean = false,
)

/** Whole-directory approval is distinct from an individual destination conflict. */
enum class TransferPromptKind { FILE_PREVIEW, DIRECTORY_PREVIEW, FILE_CONFLICT }

/** Cancel ends the task, whereas skip only excludes a file from a directory task. */
enum class TransferConflictAction { CANCEL, PROCEED, SKIP, REPLACE, SAVE_AS, DIRECT_OVERWRITE }

/** Direct overwrite is accepted only after its own UI confirmation. */
data class TransferConflictDecision(
    val action: TransferConflictAction,
    val alternateName: String? = null,
    val localUri: String? = null,
    val applyToTask: Boolean = false,
    val verifySha256: Boolean = false,
    /** True only for the URI returned by the download CreateDocument contract. */
    val localDocumentCreated: Boolean = false,
)
