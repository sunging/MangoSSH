package website.sung.mangossh.session

import com.trilead.ssh2.Connection

/** Companion state shares the lifecycle monitor; an ended session can never publish a replacement. */
internal class SshFeatureConnection(private val lifecycle: SessionLifecycle, initial: Connection) {
    private var connection: Connection? = initial
    var current: Connection?
        get() = synchronized(lifecycle.lock) { connection }
        set(value) {
            val accepted = synchronized(lifecycle.lock) {
                if (value != null && !lifecycle.isOpen) false else { connection = value; true }
            }
            if (!accepted) { value?.abort(); throw java.io.InterruptedIOException("Session ended") }
        }
}
