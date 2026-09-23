package website.sung.mangossh.session

import website.sung.mangossh.session.ssh.SshConnection

/** Companion state shares the lifecycle monitor; an ended session can never publish a replacement. */
internal class SshFeatureConnection(private val lifecycle: SessionLifecycle, initial: SshConnection) {
    private var connection: SshConnection? = initial
    var current: SshConnection?
        get() = synchronized(lifecycle.lock) { connection }
        set(value) {
            val accepted = synchronized(lifecycle.lock) {
                if (value != null && !lifecycle.isOpen) false else { connection = value; true }
            }
            if (!accepted) { value?.close(); throw java.io.InterruptedIOException("SshChannel ended") }
        }
}
