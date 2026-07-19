package website.sung.mangossh.session

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import website.sung.mangossh.R

/**
 * Owns user-initiated interactive sessions once the SSH engine is connected.
 * It deliberately does not start itself at app launch: Android only permits
 * foreground work after an explicit user action.
 */
class SessionForegroundService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.active_sessions_notification_title))
            .setContentText(getString(R.string.active_sessions_notification_text))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundType)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.active_sessions_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.active_sessions_channel_description)
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "active_ssh_sessions"
        private const val NOTIFICATION_ID = 4101

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SessionForegroundService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SessionForegroundService::class.java))
        }
    }
}
