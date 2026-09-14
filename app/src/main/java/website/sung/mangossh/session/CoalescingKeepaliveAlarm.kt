package website.sung.mangossh.session

/** Shares one background wakeup; a firing serves all currently waiting SSH connections. */
internal class CoalescingKeepaliveAlarm(private val delegate: KeepaliveAlarm,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 }) : KeepaliveAlarm {
    private val lock = Any()
    private val callbacks = linkedMapOf<Any, () -> Unit>()
    private var cancel: (() -> Unit)? = null
    private var deadline = Long.MAX_VALUE
    private var generation = 0L

    override fun schedule(delayMillis: Long, onFire: () -> Unit): () -> Unit {
        val token = Any()
        synchronized(lock) {
            callbacks[token] = onFire
            val requested = now() + delayMillis
            if (requested < deadline) {
                cancel?.invoke()
                deadline = requested
                val current = ++generation
                cancel = delegate.schedule(delayMillis) { fire(current) }
            }
        }
        return {
            synchronized(lock) {
                callbacks.remove(token)
                if (callbacks.isEmpty()) {
                    ++generation
                    cancel?.invoke()
                    cancel = null
                    deadline = Long.MAX_VALUE
                }
            }
        }
    }

    private fun fire(expected: Long) {
        val pending = synchronized(lock) {
            if (expected != generation) return
            callbacks.values.toList().also {
                callbacks.clear()
                cancel = null
                deadline = Long.MAX_VALUE
                ++generation
            }
        }
        pending.forEach { it() }
    }
}
