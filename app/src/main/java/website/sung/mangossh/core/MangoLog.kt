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
    SSH_SESSION_FAILED("ssh.session.failed"),
    MOSH_BOOTSTRAP_STARTED("mosh.bootstrap.started"),
    MOSH_BOOTSTRAP_SUCCEEDED("mosh.bootstrap.succeeded"),
    MOSH_BOOTSTRAP_FAILED("mosh.bootstrap.failed"),
    MOSH_PROCESS_STARTED("mosh.process.started"),
    MOSH_PROCESS_STOPPED("mosh.process.stopped"),
    MOSH_RUNTIME_INSTALL_FAILED("mosh.runtime.install.failed"),
    WEBDAV_UPLOAD_SUCCEEDED("webdav.upload.succeeded"),
    WEBDAV_UPLOAD_FAILED("webdav.upload.failed"),
    WEBDAV_DOWNLOAD_SUCCEEDED("webdav.download.succeeded"),
    WEBDAV_DOWNLOAD_FAILED("webdav.download.failed"),
}
