package website.sung.mangossh.session

/** Metadata captured once for a transfer; incomplete identities cannot authorize resume. */
data class SourceIdentity(val locator: String, val size: Long?, val modifiedMillis: Long?) {
    val resumable: Boolean get() = size != null && modifiedMillis != null

    fun requireMatches(current: SourceIdentity, requireComplete: Boolean = false) {
        if (locator != current.locator || size != current.size || modifiedMillis != current.modifiedMillis ||
            (requireComplete && (!resumable || !current.resumable))
        ) throw SourceChangedException()
    }
}

/** A source changed or cannot be identified well enough to safely resume. */
internal class SourceChangedException : java.io.IOException()
