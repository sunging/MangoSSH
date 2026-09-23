package website.sung.mangossh.session.ssh

import java.io.Closeable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.connectbot.sshlib.PortForwarder

/** Forward shutdown is bounded and is called by the controller's cleanup I/O worker. */
internal class SshForward(private val delegate: PortForwarder,
    private val released: (SshForward) -> Unit = {}) : Closeable {
    private val closed = java.util.concurrent.atomic.AtomicBoolean()
    val boundPort: Int get() = delegate.boundPort
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { runBlocking { withTimeout(1_000) { delegate.stop() } } }
        finally { released(this) }
    }
}
