package website.sung.mangossh.presentation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/** Applies the Activity lock policy and observes screen-off even while a document picker has stopped it. */
internal class AppLockActivityObserver(
    private val activity: ComponentActivity,
    private val lock: AppLockCoordinator,
    private val cancelPrompt: () -> Unit,
) : DefaultLifecycleObserver {
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                lock.screenOff()
                if (lock.locked.value) cancelPrompt()
            }
        }
    }

    init {
        ContextCompat.registerReceiver(activity, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStart(owner: LifecycleOwner) { lock.evaluateAutoLock() }
    override fun onStop(owner: LifecycleOwner) {
        if (!activity.isChangingConfigurations) {
            lock.noteBackgrounded()
            if (lock.locked.value) cancelPrompt()
        }
    }
    override fun onDestroy(owner: LifecycleOwner) { activity.unregisterReceiver(screenOff) }
}
