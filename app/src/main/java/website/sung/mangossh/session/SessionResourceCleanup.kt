package website.sung.mangossh.session

import website.sung.mangossh.session.ssh.SshConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Protocol shutdown can block; cleanup survives the caller and always executes on an I/O dispatcher. */
internal fun closeForwardInBackground(scope: CoroutineScope, close: () -> Unit, completed: (Throwable?) -> Unit) =
    scope.launch(Dispatchers.IO) { completed(runCatching(close).exceptionOrNull()) }

/** Registers before connect, so teardown can interrupt a socket even while the peer has not sent its banner. */
internal fun adoptPendingConnection(lifecycle: SessionLifecycle, connection: SshConnection, cleanup: CoroutineScope): Any {
    val token = Any()
    if (!lifecycle.adopt(token) {
            connection.close()
            cleanup.launch(Dispatchers.IO) { runCatching { connection.close() } }
        }) throw java.io.InterruptedIOException()
    return token
}
