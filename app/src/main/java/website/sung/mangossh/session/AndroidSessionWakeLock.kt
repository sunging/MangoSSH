package website.sung.mangossh.session

import android.content.Context
import android.os.PowerManager
import website.sung.mangossh.core.MangoLog
import website.sung.mangossh.core.MangoLogEvent

/**
 * Creates the service's CPU-only lease without keeping an Activity or screen awake.
 *
 * Platform creation is deferred to the guarded acquire callback, so a missing
 * system service or an OEM permission denial cannot crash service startup.
 * Neither the tag nor diagnostics contain profile, host, or credential data.
 */
internal fun createSessionWakeLock(context: Context): SessionWakeLock {
    val appContext = context.applicationContext
    var platformLock: PowerManager.WakeLock? = null
    return SessionWakeLock(
        acquire = { timeoutMillis ->
            val lock = platformLock ?: requireNotNull(
                appContext.getSystemService(PowerManager::class.java),
            ).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MangoSSH:ActiveSessions")
                .apply { setReferenceCounted(false) }
                .also { platformLock = it }
            // Renew even when held: the timeout must extend with the session,
            // rather than silently expiring during a long background command.
            lock.acquire(timeoutMillis)
        },
        release = {
            platformLock?.let { lock ->
                if (lock.isHeld) lock.release()
            }
        },
        onFailure = { error -> MangoLog.warn(MangoLogEvent.SESSION_WAKE_LOCK_FAILED, error) },
    )
}
