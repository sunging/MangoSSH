package website.sung.mangossh.session

/** Metadata captured once for a transfer; incomplete identities cannot authorize resume. */
data class SourceIdentity(val locator: String, val size: Long?, val modifiedMillis: Long?) {
    val resumable: Boolean get() = size != null && modifiedMillis != null

    fun requireMatches(current: SourceIdentity, requireComplete: Boolean = false) {
        val reason = when {
            locator != current.locator -> SourceChangeReason.LOCATOR
            size != current.size -> SourceChangeReason.SIZE
            modifiedMillis != current.modifiedMillis -> SourceChangeReason.MODIFIED
            requireComplete && (!resumable || !current.resumable) -> SourceChangeReason.INCOMPLETE
            else -> return
        }
        throw SourceChangedException(reason)
    }
}

/** Diagnostic categories contain no locator, document name, timestamp or contents. */
internal enum class SourceChangeReason { UNKNOWN, LOCATOR, SIZE, MODIFIED, INCOMPLETE }

/** A source changed or cannot be identified well enough to safely resume. */
internal class SourceChangedException(val reason: SourceChangeReason = SourceChangeReason.UNKNOWN) : java.io.IOException()
