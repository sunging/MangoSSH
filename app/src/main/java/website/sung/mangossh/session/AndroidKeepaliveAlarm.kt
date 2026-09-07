package website.sung.mangossh.session

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import website.sung.mangossh.core.MangoLog
import website.sung.mangossh.core.MangoLogEvent

/**
 * Platform [KeepaliveAlarm] backed by [AlarmManager.setAndAllowWhileIdle].
 *
 * `setAndAllowWhileIdle` needs no special permission (it is inexact) and is the
 * only timer that keeps firing after the session wake lock is dropped and the
 * device enters Doze. A short self-releasing CPU lease is taken as each alarm is
 * delivered so the keepalive write that follows can complete before the device
 * settles back down.
 *
 * The receiver is registered for the life of the process against the application
 * context; there is nothing to unregister because the owning controller is
 * itself process-scoped.
 */
internal fun createKeepaliveAlarm(context: Context): KeepaliveAlarm {
    val appContext = context.applicationContext
    val alarmManager = appContext.getSystemService(AlarmManager::class.java)
    val powerManager = appContext.getSystemService(PowerManager::class.java)
    val action = "${appContext.packageName}.action.KEEPALIVE_ALARM"
    val nextId = AtomicInteger(1)
    val callbacks = ConcurrentHashMap<Int, () -> Unit>()

    val receiver = object : BroadcastReceiver() {
        override fun onReceive(received: Context, intent: Intent) {
            val id = intent.getIntExtra(EXTRA_ID, -1)
            // Self-releasing lease: covers the imminent socket write without a
            // reference to track, so a missed release can never strand it.
            runCatching {
                powerManager
                    ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MangoSSH:KeepaliveSend")
                    ?.apply { setReferenceCounted(false) }
                    ?.acquire(SEND_GRACE_MILLIS)
            }.onFailure { MangoLog.warn(MangoLogEvent.SESSION_WAKE_LOCK_FAILED, it) }
            callbacks.remove(id)?.invoke()
        }
    }
    ContextCompat.registerReceiver(
        appContext,
        receiver,
        IntentFilter(action),
        ContextCompat.RECEIVER_NOT_EXPORTED,
    )

    return KeepaliveAlarm { delayMillis, onFire ->
        val id = nextId.getAndIncrement()
        callbacks[id] = onFire
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            id,
            Intent(action).setPackage(appContext.packageName).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val triggerAt = SystemClock.elapsedRealtime() + delayMillis
        val scheduled = runCatching {
            alarmManager?.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pendingIntent,
            )
        }
        if (scheduled.isFailure) {
            // No alarm means the caller only resumes on foreground return; log
            // and let the connection monitor be the backstop for dead peers.
            MangoLog.warn(MangoLogEvent.SSH_KEEPALIVE_FAILED, scheduled.exceptionOrNull()!!)
        }

        return@KeepaliveAlarm {
            callbacks.remove(id)
            alarmManager?.cancel(pendingIntent)
        }
    }
}

private const val EXTRA_ID = "id"

/** Long enough for a non-blocking keepalive write, short enough to stay cheap. */
private const val SEND_GRACE_MILLIS = 10_000L
