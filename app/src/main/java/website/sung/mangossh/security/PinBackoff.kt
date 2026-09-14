package website.sung.mangossh.security

/** Persisted PIN throttling state; the fifth failure starts a bounded exponential cooldown. */
internal data class PinBackoff(val failures: Int = 0, val blockedUntilMillis: Long = 0) {
    fun remainingMillis(now: Long): Long = (blockedUntilMillis - now).coerceIn(0, 15 * 60_000L)
    fun failed(now: Long): PinBackoff {
        val count = (failures + 1).coerceAtMost(100)
        val delay = if (count < 5) 0 else (30_000L shl (count - 5).coerceAtMost(5)).coerceAtMost(15 * 60_000L)
        return PinBackoff(count, now + delay)
    }
}
