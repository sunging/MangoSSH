package website.sung.mangossh.core

import android.util.Log

/**
 * Emits operational diagnostics without turning session data into telemetry.
 *
 * Callers deliberately supply a fixed [MangoLogEvent] instead of a free-form
 * message. This makes it difficult to accidentally write a hostname, command,
 * private key, OTP value, Mosh key, or WebDAV credential to Logcat.
 */
object MangoLog {
    private const val TAG = "MangoSSH"

    /** Records a successful state transition using a stable, non-sensitive event code. */
    fun info(event: MangoLogEvent) {
        Log.i(TAG, event.code)
    }

    /**
     * Records a failed transition and only exposes the exception class. The
     * exception message is intentionally omitted because protocol libraries
     * frequently include remote or credential-adjacent text in their messages.
     */
    fun warn(event: MangoLogEvent, error: Throwable? = null) {
        val suffix = error?.javaClass?.simpleName?.takeIf(String::isNotBlank)
        if (suffix == null) {
            Log.w(TAG, event.code)
        } else {
            Log.w(TAG, "${event.code}; cause=$suffix")
        }
    }
}

/** Fixed log events that are safe to publish from security-sensitive code. */
enum class MangoLogEvent(val code: String) {
    VAULT_OPEN_STARTED("vault.open.started"),
    VAULT_OPEN_SUCCEEDED("vault.open.succeeded"),
    VAULT_OPEN_FAILED("vault.open.failed"),
    VAULT_WRITE_SUCCEEDED("vault.write.succeeded"),
    VAULT_WRITE_FAILED("vault.write.failed"),
    SSH_CONNECT_STARTED("ssh.connect.started"),
    SSH_HOST_KEY_PROMPTED("ssh.host_key.prompted"),
    SSH_AUTH_SUCCEEDED("ssh.auth.succeeded"),
    SSH_AUTH_FAILED("ssh.auth.failed"),
    SSH_SESSION_OPENED("ssh.session.opened"),
    SSH_SESSION_CLOSED("ssh.session.closed"),
    SSH_SESSION_REMOTE_EXIT("ssh.session.remote_exit"),
    SSH_KEEPALIVE_FAILED("ssh.keepalive.failed"),
    SSH_SESSION_FAILED("ssh.session.failed"),

    /**
     * A session-scope coroutine ended with an unhandled throwable. Without the
     * handler that reports this, the throwable would reach Android's default
     * uncaught handler and kill the process instead of one transport.
     */
    SESSION_COROUTINE_FAILED("session.coroutine.failed"),

    /** A cleanup step inside session teardown failed; the remaining steps still ran. */
    SESSION_TEARDOWN_FAILED("session.teardown.failed"),

    /** A host-key or authentication prompt was released without a user answer. */
    SESSION_PROMPT_ABANDONED("session.prompt.abandoned"),
    FOREGROUND_SERVICE_STARTED("foreground_service.started"),
    FOREGROUND_SERVICE_STOPPED("foreground_service.stopped"),

    /** Android refused a foreground-service start or promotion for this process state. */
    FOREGROUND_SERVICE_START_DENIED("foreground_service.start.denied"),
    MOSH_BOOTSTRAP_STARTED("mosh.bootstrap.started"),
    MOSH_BOOTSTRAP_SUCCEEDED("mosh.bootstrap.succeeded"),
    MOSH_BOOTSTRAP_FAILED("mosh.bootstrap.failed"),
    MOSH_PROCESS_STARTED("mosh.process.started"),
    MOSH_PROCESS_STOPPED("mosh.process.stopped"),
    MOSH_RUNTIME_INSTALL_FAILED("mosh.runtime.install.failed"),
    MOSH_COMPANION_SSH_DISCONNECTED("mosh.companion_ssh.disconnected"),
    MOSH_COMPANION_SSH_RECONNECT_STARTED("mosh.companion_ssh.reconnect.started"),
    MOSH_COMPANION_SSH_RECONNECT_SUCCEEDED("mosh.companion_ssh.reconnect.succeeded"),
    MOSH_COMPANION_SSH_RECONNECT_FAILED("mosh.companion_ssh.reconnect.failed"),
    TSNET_STARTING("tsnet.starting"),
    TSNET_RUNNING("tsnet.running"),
    TSNET_FAILED("tsnet.failed"),
    TSNET_SERVER_START_FAILED("tsnet.server_start.failed"),
    TSNET_LOCAL_CLIENT_FAILED("tsnet.local_client.failed"),
    TSNET_LOOPBACK_FAILED("tsnet.loopback.failed"),
    TSNET_PROXY_LOOPBACK_FAILED("tsnet.proxy.loopback.failed"),
    TSNET_PROXY_AUTH_FAILED("tsnet.proxy.auth.failed"),
    TSNET_PROXY_CONNECT_FAILED("tsnet.proxy.connect.failed"),
    TSNET_PROXY_CONNECT_DENIED("tsnet.proxy.connect.denied"),
    TSNET_PROXY_NETWORK_UNREACHABLE("tsnet.proxy.network_unreachable"),
    TSNET_PROXY_HOST_UNREACHABLE("tsnet.proxy.host_unreachable"),
    TSNET_PROXY_CONNECTION_REFUSED("tsnet.proxy.connection_refused"),
    TSNET_PROXY_PROTOCOL_FAILED("tsnet.proxy.protocol.failed"),
    TSNET_STATE_RECOVERED("tsnet.state.recovered"),
    TSNET_LOGOUT_SUCCEEDED("tsnet.logout.succeeded"),
    TSNET_LOGOUT_FAILED("tsnet.logout.failed"),
    WEBDAV_UPLOAD_SUCCEEDED("webdav.upload.succeeded"),
    WEBDAV_UPLOAD_FAILED("webdav.upload.failed"),
    WEBDAV_DOWNLOAD_SUCCEEDED("webdav.download.succeeded"),
    WEBDAV_DOWNLOAD_FAILED("webdav.download.failed"),
    APP_UPDATE_CHECK_STARTED("app_update.check.started"),
    APP_UPDATE_CHECK_SUCCEEDED("app_update.check.succeeded"),
    APP_UPDATE_CHECK_FAILED("app_update.check.failed"),
    APP_UPDATE_DOWNLOAD_STARTED("app_update.download.started"),
    APP_UPDATE_DOWNLOAD_SUCCEEDED("app_update.download.succeeded"),
    APP_UPDATE_DOWNLOAD_FAILED("app_update.download.failed"),
    APP_UPDATE_DOWNLOAD_CANCELLED("app_update.download.cancelled"),
    APP_UPDATE_VERIFY_FAILED("app_update.verify.failed"),
    APP_UPDATE_INSTALL_HANDOFF("app_update.install.handoff"),
    APP_UPDATE_INSTALL_HANDOFF_FAILED("app_update.install.handoff.failed"),
    BIOMETRIC_UNLOCK_FAILED("biometric_unlock.failed"),
    BIOMETRIC_UNLOCK_KEY_INVALIDATED("biometric_unlock.key_invalidated"),

    /** A sanitized crash record was persisted before the process died. */
    CRASH_REPORT_WRITTEN("crash_report.written"),

    /** The crash record could not be persisted; the process still crashes normally. */
    CRASH_REPORT_FAILED("crash_report.failed"),
}
