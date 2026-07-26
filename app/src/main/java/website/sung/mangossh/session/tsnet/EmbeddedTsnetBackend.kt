package website.sung.mangossh.session.tsnet

import android.content.Context
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import tsnetbridge.StateStore
import tsnetbridge.StatusListener
import tsnetbridge.Tsnetbridge

/** Process-local bridge abstraction that keeps gomobile types out of lifecycle tests. */
internal interface EmbeddedTsnetBackend : Closeable {
    fun start(authKey: String)
    fun socksAddress(): String
    fun socksSecret(): String
    fun startUdpRelay(host: String, port: Int): EmbeddedTsnetUdpRelay
    fun logout()
}

/** One loopback UDP port forwarding to a single tailnet destination. */
internal interface EmbeddedTsnetUdpRelay : Closeable {
    val localPort: Int
}

internal fun interface EmbeddedTsnetBackendFactory {
    fun create(
        stateDirectory: String,
        hostname: String,
        store: StateStore,
        listener: StatusListener,
    ): EmbeddedTsnetBackend
}

/** Production adapter around the restricted generated gomobile API. */
internal class GomobileTsnetBackendFactory(context: Context) : EmbeddedTsnetBackendFactory {
    private val appContext = context.applicationContext

    override fun create(
        stateDirectory: String,
        hostname: String,
        store: StateStore,
        listener: StatusListener,
    ): EmbeddedTsnetBackend {
        val networkState = AndroidTsnetNetworkStateSource(appContext)
        val runtime = Tsnetbridge.newRuntime(
            stateDirectory,
            hostname,
            store,
            networkState,
            listener,
        )
        val networkMonitor = AndroidTsnetNetworkMonitor(appContext, runtime::notifyNetworkChange)
        val closed = AtomicBoolean(false)
        return object : EmbeddedTsnetBackend {
            override fun start(authKey: String) {
                check(!closed.get())
                networkMonitor.start()
                try {
                    runtime.start(authKey)
                } catch (error: Exception) {
                    networkMonitor.close()
                    throw error
                }
            }

            override fun socksAddress(): String = runtime.socksAddress()

            override fun socksSecret(): String = runtime.socksSecret()

            override fun startUdpRelay(host: String, port: Int): EmbeddedTsnetUdpRelay {
                val relay = runtime.startUDPRelay(host, port.toLong())
                return object : EmbeddedTsnetUdpRelay {
                    override val localPort: Int
                        get() = relay.localPort().toInt()

                    override fun close() = relay.close()
                }
            }

            override fun logout() = runtime.logout()

            override fun close() {
                if (!closed.compareAndSet(false, true)) return
                networkMonitor.close()
                runtime.close()
            }
        }
    }
}
