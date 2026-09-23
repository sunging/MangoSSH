package website.sung.mangossh.security

import kotlinx.coroutines.flow.MutableStateFlow

/** Process-wide authorization boundary shared by the UI and forwarded-agent requests. */
internal class AppAccessState(initiallyLocked: Boolean) {
    val locked = MutableStateFlow(initiallyLocked)
    @Volatile var generation: Long = 0
        private set

    @Synchronized fun setLocked(value: Boolean) {
        if (value) generation++
        locked.value = value
    }
}
