package website.sung.mangossh.session.ssh

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async

/**
 * Delivers a connection-owned result once, or disposes it if its caller has left.
 * A sent channel handshake must consume its reply even after caller cancellation:
 * dropping the pending open makes a late valid reply poison the shared transport.
 * Such handshakes remain owned by [scope] until reply or connection shutdown.
 */
internal suspend fun <T> awaitOwnedSshOperation(
    scope: CoroutineScope,
    finishOnCancellation: Boolean = false,
    onDiscard: (T) -> Unit = {},
    block: suspend () -> T,
): T {
    val abandoned = AtomicBoolean()
    val result = AtomicReference<OwnedResult<T>?>()
    fun discard() { result.getAndSet(null)?.let { onDiscard(it.value) } }
    val pending = scope.async {
        block().also { value ->
            result.set(OwnedResult(value))
            if (abandoned.get()) discard()
        }
    }
    var delivered = false
    return try {
        pending.await().also { delivered = true; result.set(null) }
    } finally {
        if (!delivered) {
            abandoned.set(true)
            if (!finishOnCancellation) pending.cancel()
            discard()
            pending.invokeOnCompletion { discard() }
        }
    }
}

private class OwnedResult<T>(val value: T)
