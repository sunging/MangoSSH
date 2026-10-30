package website.sung.mangossh.security

import java.util.UUID

/**
 * Process-local grants for document activities launched by the unlocked app. Only opaque tokens
 * cross configuration changes; a restored token has no authority after process death or locking.
 * Main-thread ownership keeps launching and lifecycle callbacks in one ordering domain.
 */
internal class DocumentPickerRequests(
    private val access: AppAccessState,
    private val onBegin: () -> Unit = {},
) {
    private val requests = mutableMapOf<String, Long>()
    val active: Boolean get() = !access.locked.value && requests.values.any { it == access.generation }

    /** Must run before launching the external activity, including launches with synchronous results. */
    fun begin(): String? {
        if (access.locked.value) return null
        onBegin()
        return UUID.randomUUID().toString().also { requests[it] = access.generation }
    }

    /** Consumes the grant exactly once. Invalid or late results must be delivered as cancellation. */
    fun finish(token: String?): Boolean {
        val generation = requests.remove(token) ?: return false
        return !access.locked.value && generation == access.generation
    }

    /** Launch failure or removal of the owning screen cannot leave a permanent lock exemption. */
    fun abandon(token: String?) { requests.remove(token) }

    /** A lock revokes every outstanding request even if its external activity is still running. */
    fun invalidate() { requests.clear() }
}
