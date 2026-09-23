package website.sung.mangossh.session

/**
 * Atomic ownership boundary for one connection generation. Closing removes all
 * owners under the monitor; actual cancellation/close callbacks always run outside it.
 */
internal class SessionLifecycle {
    val lock = Any()
    private var closed = false
    private val resources = LinkedHashMap<Any, () -> Unit>()

    val isOpen: Boolean get() = synchronized(lock) { !closed }

    /** Registers a newly created resource or releases it if teardown already won. */
    fun adopt(identity: Any, release: () -> Unit): Boolean {
        val accepted = synchronized(lock) {
            if (closed) false else { resources.putIfAbsent(identity, release); true }
        }
        if (!accepted) release()
        return accepted
    }

    /** Executes a short publication only while this generation still owns its state. */
    fun <T> whileOpen(action: () -> T): T? = synchronized(lock) {
        if (closed) null else action()
    }

    /** Gives the unique closer a detached list; subsequent closes have no work. */
    fun close(): List<() -> Unit>? = synchronized(lock) {
        if (closed) null else {
            closed = true
            resources.values.toList().asReversed().also { resources.clear() }
        }
    }

    /** Transfers ownership to a replacement/early closer without calling user code in the monitor. */
    fun detach(identity: Any): (() -> Unit)? = synchronized(lock) { resources.remove(identity) }
}
