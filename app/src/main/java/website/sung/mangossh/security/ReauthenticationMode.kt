package website.sung.mangossh.security

/** Device-local opt-in; grants never enter the encrypted backup. */
enum class ReauthenticationMode { DISABLED, EVERY_ACTION, FIVE_MINUTES }

/** Monotonic, lock-generation-bound window shared by sensitive action entry points. */
internal class ReauthenticationWindow(private val access: AppAccessState, private val now: () -> Long = System::nanoTime) {
    private var grantedAt: Long? = null
    private var generation = -1L
    fun grant() { grantedAt = now(); generation = access.generation }
    fun isValid(): Boolean = !access.locked.value && generation == access.generation &&
        grantedAt?.let { now() - it in 0 until 300_000_000_000L } == true
}
