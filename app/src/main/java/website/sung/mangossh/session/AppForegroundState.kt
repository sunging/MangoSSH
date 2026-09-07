package website.sung.mangossh.session

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide "is any Activity visible" signal used to relax power usage while
 * the app is backgrounded.
 *
 * Backed by [ProcessLifecycleOwner], which already debounces the brief gap
 * between one Activity stopping and the next starting (a configuration change,
 * an in-app task switch), so transports do not thrash their wake-lock and
 * keepalive policy on a screen rotation.
 *
 * Deliberately separate from [website.sung.mangossh.presentation.MangoSshViewModel]'s
 * `noteBackgrounded`: that signal drives the app-lock PIN timer and intentionally
 * ignores configuration changes, which is the wrong policy for power management.
 */
class AppForegroundState private constructor(initialForeground: Boolean) {
    private val _foreground = MutableStateFlow(initialForeground)

    /** True while at least one Activity is in the STARTED (screen-visible) state. */
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    private val observer = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            _foreground.value = true
        }

        override fun onStop(owner: LifecycleOwner) {
            _foreground.value = false
        }
    }

    companion object {
        /**
         * Registers the observer immediately. Must be called from the main
         * thread, which `Application.onCreate` guarantees.
         */
        fun create(
            lifecycle: Lifecycle = ProcessLifecycleOwner.get().lifecycle,
        ): AppForegroundState {
            val state = AppForegroundState(
                initialForeground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
            )
            lifecycle.addObserver(state.observer)
            return state
        }

        /** Test/preview seam: a fixed state with no platform lifecycle attached. */
        fun fixed(foreground: Boolean): AppForegroundState = AppForegroundState(foreground)
    }
}
