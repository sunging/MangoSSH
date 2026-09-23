package website.sung.mangossh.session

/** Per-file preview. Decisions are process-local and apply only to the identified transfer execution. */
data class TransferConflict(
    val id: String,
    val name: String,
    val direction: ScpTransferDirection,
    val targetExists: Boolean,
    val atomicReplace: Boolean,
)

enum class TransferConflictAction { SKIP, REPLACE, SAVE_AS, DIRECT_OVERWRITE }

/** Direct overwrite is accepted only after its own UI confirmation. */
data class TransferConflictDecision(
    val action: TransferConflictAction,
    val alternateName: String? = null,
    val localUri: String? = null,
    val applyToTask: Boolean = false,
    val verifySha256: Boolean = false,
)
