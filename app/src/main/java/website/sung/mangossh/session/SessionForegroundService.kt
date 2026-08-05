package website.sung.mangossh.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import website.sung.mangossh.MainActivity
import website.sung.mangossh.MangoSshApplication
import website.sung.mangossh.R
import website.sung.mangossh.core.MangoLog
import website.sung.mangossh.core.MangoLogEvent

/**
 * Foreground owner for every live user-initiated terminal session.
 *
 * The service observes the application-scoped controller rather than an
 * Activity. It therefore keeps sessions alive after the task UI leaves the
 * screen and provides one notification shortcut per session for restoration.
 */
class SessionForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessionNotificationIds = mutableMapOf<String, Int>()
    private val nextNotificationId = AtomicInteger(FIRST_SESSION_NOTIFICATION_ID)
    private var foregroundStarted = false

    private val sessionController
        get() = (application as MangoSshApplication).sessionRuntime.sessionController
    private val embeddedTsnetManager
        get() = (application as MangoSshApplication).sessionRuntime.embeddedTsnetManager

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        // StateFlow emits its current value as soon as collection begins. A
        // failed connection can therefore leave the controller empty before
        // this service reaches onStartCommand. Enter the foreground first so
        // every startForegroundService call satisfies Android's contract even
        // when the service immediately discovers that there is nothing to own.
        ensureForeground()
        MangoLog.info(MangoLogEvent.FOREGROUND_SERVICE_STARTED)
        serviceScope.launch {
            combine(
                sessionController.sessions,
                embeddedTsnetManager.foregroundRequired,
            ) { sessions, tsnetRequired -> sessions to tsnetRequired }
                .collect { (sessions, tsnetRequired) -> renderWork(sessions, tsnetRequired) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        renderWork(
            sessions = sessionController.sessions.value,
            tsnetRequired = embeddedTsnetManager.foregroundRequired.value,
        )
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        clearSessionNotifications()
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        MangoLog.info(MangoLogEvent.FOREGROUND_SERVICE_STOPPED)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun renderWork(sessions: List<TerminalSessionState>, tsnetRequired: Boolean) {
        if (sessions.isEmpty() && !tsnetRequired) {
            clearSessionNotifications()
            if (foregroundStarted) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                foregroundStarted = false
            }
            stopSelf()
            return
        }

        ensureForeground()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(
            SUMMARY_NOTIFICATION_ID,
            buildSummaryNotification(tsnetOnly = sessions.isEmpty() && tsnetRequired),
        )
        val liveIds = sessions.mapTo(mutableSetOf()) { it.id }
        sessions.forEach { session ->
            manager.notify(notificationIdFor(session.id), buildSessionNotification(session))
        }
        sessionNotificationIds
            .filterKeys { it !in liveIds }
            .toMap()
            .forEach { (sessionId, notificationId) ->
                manager.cancel(notificationId)
                sessionNotificationIds.remove(sessionId)
            }
    }

    /**
     * Synchronously acknowledges a foreground-service launch before observing
     * mutable session state. Fast DNS or socket failures may remove the final
     * session before Android dispatches [onStartCommand], but they must never
     * leave a startForegroundService request unacknowledged.
     */
    private fun ensureForeground() {
        if (foregroundStarted) return
        ServiceCompat.startForeground(
            this,
            SUMMARY_NOTIFICATION_ID,
            buildSummaryNotification(tsnetOnly = false),
            foregroundServiceType(),
        )
        foregroundStarted = true
    }

    private fun buildSummaryNotification(tsnetOnly: Boolean): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(appString(R.string.active_sessions_notification_title))
        .setContentText(
            appString(
                if (tsnetOnly) {
                    R.string.embedded_tsnet_notification_text
                } else {
                    R.string.active_sessions_notification_text
                },
            ),
        )
        .setContentIntent(summaryPendingIntent())
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setGroup(GROUP_KEY)
        .setGroupSummary(true)
        .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setPublicVersion(buildPublicNotification())
        .build()

    private fun buildSessionNotification(session: TerminalSessionState): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(appString(R.string.active_session_notification_title, session.title))
            .setContentText(phaseText(session.phase))
            .setContentIntent(sessionPendingIntent(session.id))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setGroup(GROUP_KEY)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(buildPublicNotification())
            .build()

    /** Produces a lock-screen-safe version that never includes profile or endpoint data. */
    private fun buildPublicNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(appString(R.string.active_sessions_notification_public_title))
        .setContentText(appString(R.string.active_sessions_notification_public_text))
        .setContentIntent(summaryPendingIntent())
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    private fun phaseText(phase: TerminalSessionPhase): String = when (phase) {
        TerminalSessionPhase.CONNECTING -> appString(R.string.active_session_notification_connecting)
        TerminalSessionPhase.VERIFYING_HOST_KEY -> appString(R.string.active_session_notification_verifying)
        TerminalSessionPhase.AUTHENTICATING -> appString(R.string.active_session_notification_authenticating)
        TerminalSessionPhase.OPEN -> appString(R.string.active_session_notification_open)
        TerminalSessionPhase.FAILED,
        TerminalSessionPhase.CLOSED -> appString(R.string.active_sessions_notification_text)
    }

    private fun summaryPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        SUMMARY_PENDING_INTENT_REQUEST_CODE,
        Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_SESSIONS
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        },
        pendingIntentFlags(),
    )

    private fun sessionPendingIntent(sessionId: String): PendingIntent = PendingIntent.getActivity(
        this,
        notificationIdFor(sessionId),
        MainActivity.sessionIntent(this, sessionId),
        pendingIntentFlags(),
    )

    private fun pendingIntentFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun notificationIdFor(sessionId: String): Int =
        sessionNotificationIds.getOrPut(sessionId) { nextNotificationId.getAndIncrement() }

    private fun clearSessionNotifications() {
        val manager = getSystemService(NotificationManager::class.java)
        sessionNotificationIds.values.forEach { notificationId -> manager.cancel(notificationId) }
        sessionNotificationIds.clear()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                appString(R.string.active_sessions_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = appString(R.string.active_sessions_channel_description)
                setShowBadge(false)
            },
        )
    }

    private fun foregroundServiceType(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
    } else {
        0
    }

    companion object {
        private const val CHANNEL_ID = "active_ssh_sessions"
        private const val GROUP_KEY = "website.sung.mangossh.active_sessions"
        private const val SUMMARY_NOTIFICATION_ID = 4101
        private const val FIRST_SESSION_NOTIFICATION_ID = 4200
        private const val SUMMARY_PENDING_INTENT_REQUEST_CODE = 4101

        /** Starts service ownership only after the user begins a connection. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SessionForegroundService::class.java),
            )
        }

    }
}
