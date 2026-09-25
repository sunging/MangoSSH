package website.sung.mangossh.session.ssh

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.connectbot.sshlib.AuthHandler
import org.connectbot.sshlib.AuthPublicKey
import org.connectbot.sshlib.AuthResult
import org.connectbot.sshlib.ConnectResult
import org.connectbot.sshlib.HostKeyVerifier
import org.connectbot.sshlib.KeyboardInteractiveCallback
import org.connectbot.sshlib.PingResult
import org.connectbot.sshlib.PublicKey
import org.connectbot.sshlib.SshClient
import org.connectbot.sshlib.SshClientConfig
import org.connectbot.sshlib.SshSigning
import org.connectbot.sshlib.SftpResult
import org.connectbot.sshlib.transport.KtorTcpTransportFactory
import org.connectbot.sshlib.transport.Transport
import org.connectbot.sshlib.transport.TransportFactory

/** Prompt fields contain remote text and must never be included in logs or diagnostics. */
internal class SshPromptField(val text: String, val echo: Boolean)

/** Credential callbacks are suspended inside the owning connection's cancellable task. */
internal interface SshCredentials {
    suspend fun key(): java.security.KeyPair? = null
    suspend fun password(): String? = null
    suspend fun interactive(name: String, instruction: String, fields: List<SshPromptField>): List<String>? = null
    suspend fun banner(text: String) = Unit
}

/** Protocol-neutral failure; never retains remote descriptions or credential-bearing causes. */
internal class SshFailure(val category: Category) : IOException(category.name) {
    enum class Category { CLOSED, HOST_KEY, ALGORITHMS, CONNECT, AUTHENTICATION, CHANNEL, KEEPALIVE }
}

/**
 * One connection generation owns its protocol jobs and transport. Closing is immediate to callers;
 * bounded cleanup targets captured resources only and cannot affect a replacement generation.
 */
internal class SshConnection(
    val hostname: String,
    val port: Int,
    private val legacyAlgorithms: Boolean = false,
    private var through: SshConnection? = null,
    private val customTransport: TransportFactory? = null,
) : Closeable {
    private val closed = AtomicBoolean()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val transport = AtomicReference<Transport?>()
    private val client = AtomicReference<SshClient?>()
    private val channels = ConcurrentHashMap.newKeySet<SshChannel>()
    private val files = ConcurrentHashMap.newKeySet<SshFiles>()
    private var socketRoute: SshSocketRoute? = null
    private val pendingSocket = AtomicReference<java.net.Socket?>()
    private val monitors = java.util.concurrent.CopyOnWriteArrayList<(Throwable?) -> Unit>()
    @Volatile var lastPacketSentNanos: Long = 0; private set
    @Volatile var lastPacketReceivedNanos: Long = 0; private set
    var banner: suspend (String) -> Unit = {}
    private val forwards = ConcurrentHashMap.newKeySet<SshForward>()
    private val remoteForwards = ConcurrentHashMap<Int, SshForward>()
    private val probeLock = Any()
    private var probe: Deferred<Unit>? = null

    /** Test seam replacing the protocol ping; production leaves it null. */
    @Volatile internal var pinger: (suspend () -> PingResult)? = null
    fun useJump(previous: SshConnection) { check(client.get() == null); through = previous }
    fun useSocketRoute(route: SshSocketRoute) { check(client.get() == null); socketRoute = route }
    fun monitor(callback: (Throwable?) -> Unit) { monitors.add(callback) }

    /** Upstream validates the binding cryptographically before this policy grants any signature. */
    fun enableAgent(agent: SshAgent) {
        clientOrThrow().enableAgentForwarding(object : org.connectbot.sshlib.AgentProvider {
            override suspend fun getIdentities() = org.connectbot.sshlib.AgentResult.Success(
                agent.identities().map { org.connectbot.sshlib.AgentIdentity(it.publicKey, it.label) })
            override suspend fun signData(context: org.connectbot.sshlib.AgentSigningContext): org.connectbot.sshlib.AgentResult<ByteArray?> {
                if (!context.isBound || closed.get() || context.flags and 6.inv() != 0 || context.flags == 6)
                    return org.connectbot.sshlib.AgentResult.Success(null)
                val type = runCatching {
                    val input = java.io.DataInputStream(java.io.ByteArrayInputStream(context.publicKeyBlob))
                    val length = input.readInt()
                    require(length in 1..128 && length <= input.available())
                    ByteArray(length).also(input::readFully).toString(Charsets.US_ASCII)
                }.getOrNull() ?: return org.connectbot.sshlib.AgentResult.Success(null)
                val algorithm = if (type == "ssh-rsa") when {
                    context.flags and 4 != 0 -> "rsa-sha2-512"
                    context.flags and 2 != 0 -> "rsa-sha2-256"
                    legacyAlgorithms -> "ssh-rsa"
                    else -> return org.connectbot.sshlib.AgentResult.Success(null)
                } else {
                    if (context.flags != 0) return org.connectbot.sshlib.AgentResult.Success(null)
                    type
                }
                if (!validAgentSignature(context.dataToSign, context.sessionId, context.publicKeyBlob,
                        context.serverHostKey, algorithm)) return org.connectbot.sshlib.AgentResult.Success(null)
                val key = agent.keyForSignature(context.publicKeyBlob) ?: return org.connectbot.sshlib.AgentResult.Success(null)
                if (closed.get()) return org.connectbot.sshlib.AgentResult.Success(null)
                return org.connectbot.sshlib.AgentResult.Success(SshSigning.signWithKeyPair(algorithm, key, context.dataToSign))
            }
        })
    }

    suspend fun createLocalPortForwarder(bind: java.net.InetSocketAddress, host: String, port: Int): SshForward =
        createForward { clientOrThrow().localPortForward(bind, host, port) }
    suspend fun createDynamicPortForwarder(bind: java.net.InetSocketAddress): SshForward =
        createForward { clientOrThrow().dynamicPortForward(bind) }
    suspend fun requestRemotePortForwarding(bind: String, port: Int, host: String, targetPort: Int) {
        remoteForwards[port] = createForward { clientOrThrow().remotePortForward(bind, port, host, targetPort) }
    }
    fun cancelRemotePortForwarding(port: Int) { remoteForwards.remove(port)?.also { forwards.remove(it) }?.close() }
    private suspend fun createForward(open: suspend () -> org.connectbot.sshlib.PortForwarder?): SshForward =
        owned(onDiscard = { it.close() }) {
            SshForward(open() ?: throw SshFailure(SshFailure.Category.CHANNEL)) { forwards.remove(it) }.also {
                forwards.add(it)
                if (closed.get() || !currentCoroutineContext().isActive) {
                    it.close()
                    throw SshFailure(SshFailure.Category.CLOSED)
                }
            }
        }

    /** Connects directly or over a previous hop without opening a local listener. */
    suspend fun connect(timeoutMillis: Long, transportTimeoutMillis: Long = timeoutMillis,
        verify: suspend (String, ByteArray) -> Boolean) {
        try {
            withTimeout(timeoutMillis) {
                owned {
                    val factory = customTransport ?: socketRoute?.let { route -> TransportFactory {
                        withContext(Dispatchers.IO) {
                            SshSocketTransport(route.openSocket(hostname, port, transportTimeoutMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) { socket ->
                                pendingSocket.set(socket)
                                if (closed.get()) { socket.close(); throw SshFailure(SshFailure.Category.CLOSED) }
                            })
                        }
                    } } ?: through?.clientOrThrow()?.openDirectTcpipTransport(hostname, port)
                        ?: if (through == null) KtorTcpTransportFactory(hostname, port)
                        else throw SshFailure(SshFailure.Category.CLOSED)
                    val config = SshClientConfig {
                        transportFactory = TransportFactory {
                            var candidate: Transport? = null
                            val opened = try {
                                withTimeout(transportTimeoutMillis) { factory.create().also { candidate = it } }
                            } catch (error: Exception) {
                                withContext(NonCancellable) { withTimeoutOrNull(1_000) { candidate?.close() } }
                                throw error
                            }
                            if (closed.get() || !transport.compareAndSet(null, opened)) {
                                withContext(NonCancellable) { withTimeoutOrNull(1_000) { opened.close() } }
                                throw SshFailure(SshFailure.Category.CLOSED)
                            }
                            object : Transport by opened {
                                override suspend fun write(data: ByteArray) { opened.write(data); lastPacketSentNanos = System.nanoTime() }
                                override suspend fun read(count: Int): ByteArray = opened.read(count).also { lastPacketReceivedNanos = System.nanoTime() }
                            }
                        }
                        hostKeyVerifier = object : HostKeyVerifier {
                            override suspend fun verify(key: PublicKey): Boolean = verify(key.type, key.encoded.copyOf())
                        }
                        applyMangoAlgorithmPolicy(legacyAlgorithms)
                    }
                    val active = SshClient(config)
                    check(client.compareAndSet(null, active)) { "Connection already started" }
                    currentCoroutineContext().ensureActive()
                    when (active.connect()) {
                        ConnectResult.Success -> scope.launch {
                            active.disconnectedFlow.collect { cause ->
                                monitors.forEach { it(if (cause == null) null else SshFailure(SshFailure.Category.CLOSED)) }
                            }
                        }.let { Unit }
                        is ConnectResult.HostKeyRejected -> throw SshFailure(SshFailure.Category.HOST_KEY)
                        is ConnectResult.AlgorithmMismatch -> throw SshFailure(SshFailure.Category.ALGORITHMS)
                        else -> throw SshFailure(SshFailure.Category.CONNECT)
                    }
                }
            }
        } catch (error: Exception) {
            close()
            currentCoroutineContext().ensureActive()
            if (error is kotlinx.coroutines.TimeoutCancellationException) throw SshFailure(SshFailure.Category.CONNECT)
            throw error
        }
    }

    /** None authentication precedes optional credentials, including Tailscale SSH approval. */
    suspend fun authenticate(username: String, credentials: SshCredentials): Boolean = try {
        owned {
            var pair: java.security.KeyPair? = null
            clientOrThrow().authenticate(username, object : AuthHandler {
                override suspend fun onPublicKeysNeeded(): List<AuthPublicKey> {
                    pair = credentials.key()
                    return pair?.let { listOf(SshSigning.encodePublicKey(it)) } ?: emptyList()
                }
                override suspend fun onSignatureRequest(key: AuthPublicKey, dataToSign: ByteArray): ByteArray? {
                    if (key.algorithmName == "ssh-rsa" && !legacyAlgorithms) return null
                    return pair?.let { SshSigning.signWithKeyPair(key.algorithmName, it, dataToSign) }
                }
                override suspend fun onKeyboardInteractivePrompt(name: String, instruction: String,
                    prompts: List<KeyboardInteractiveCallback.Prompt>): List<String>? =
                    credentials.interactive(name, instruction, prompts.map { SshPromptField(it.text, it.echo) })
                override suspend fun onPasswordNeeded(): String? = credentials.password()
                override suspend fun onBanner(message: String) { banner(message); credentials.banner(message) }
            }) == AuthResult.Success
        }
    } catch (cancelled: CancellationException) {
        close()
        throw cancelled
    }

    /** A channel remains tracked until its owner closes it, including after remote EOF. */
    suspend fun openChannel(): SshChannel = owned(onDiscard = { it.close() }) {
        val delegate = clientOrThrow().openSession() ?: throw SshFailure(SshFailure.Category.CHANNEL)
        val channel = SshChannel(delegate) { channels.remove(it) }
        channels.add(channel)
        if (closed.get() || !currentCoroutineContext().isActive) {
            channel.close()
            throw SshFailure(SshFailure.Category.CLOSED)
        }
        channel
    }

    /**
     * Proves the peer still answers, closing this connection when it does not.
     *
     * The probe belongs to the connection, not to the caller: a browser refresh or a
     * paused transfer that stops waiting must not abort a ping that the shell, other
     * file operations and forwards on the same connection depend on. Concurrent callers
     * share one in-flight probe. Its deadline is the protocol's own ping timeout, which
     * includes queued writes and fallback global-request serialization.
     */
    suspend fun keepalive() {
        if (closed.get()) throw SshFailure(SshFailure.Category.CLOSED)
        try {
            sharedProbe().await()
        } catch (cancelled: CancellationException) {
            // Either this caller stopped waiting (rethrow its own cancellation) or the
            // connection closed underneath the probe (report that as a failure).
            currentCoroutineContext().ensureActive()
            throw SshFailure(if (closed.get()) SshFailure.Category.CLOSED else SshFailure.Category.KEEPALIVE)
        }
    }

    private fun sharedProbe(): Deferred<Unit> = synchronized(probeLock) {
        probe?.takeUnless { it.isCompleted } ?: scope.async {
            val result = try {
                pinger?.invoke() ?: clientOrThrow().ping()
            } catch (_: Exception) { null }
            if (result !is PingResult.Success) {
                close()
                throw SshFailure(SshFailure.Category.KEEPALIVE)
            }
        }.also { probe = it }
    }

    /** A file operation opens a private channel and can cancel without closing sibling channels. */
    suspend fun openFiles(): SshFiles = owned(onDiscard = { it.close() }) {
        val result = clientOrThrow().openSftp()
        val delegate = (result as? SftpResult.Success)?.value ?: throw SshFileFailure(8)
        val owned = SshFiles(delegate) { files.remove(it) }
        files.add(owned)
        if (closed.get() || !currentCoroutineContext().isActive) {
            owned.close()
            throw SshFailure(SshFailure.Category.CLOSED)
        }
        owned
    }

    private fun clientOrThrow(): SshClient = client.get()?.takeUnless { closed.get() }
        ?: throw SshFailure(SshFailure.Category.CLOSED)

    private suspend fun <T> owned(onDiscard: (T) -> Unit = {}, block: suspend () -> T): T {
        if (closed.get()) throw SshFailure(SshFailure.Category.CLOSED)
        val abandoned = AtomicBoolean()
        val result = AtomicReference<ResultBox<T>?>()
        fun discard() { result.getAndSet(null)?.let { onDiscard(it.value) } }
        val pending = scope.async {
            block().also { value ->
                result.set(ResultBox(value))
                if (abandoned.get()) discard()
            }
        }
        var delivered = false
        return try {
            pending.await().also { delivered = true; result.set(null) }
        } finally {
            if (!delivered) {
                abandoned.set(true)
                pending.cancel()
                discard()
                pending.invokeOnCompletion { discard() }
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        runCatching { pendingSocket.getAndSet(null)?.close() }
        channels.toList().forEach { it.close() }
        files.toList().forEach { it.close() }
        val ownedTransport = transport.getAndSet(null)
        val ownedClient = client.getAndSet(null)
        cleanup.launch {
            withTimeoutOrNull(1_000) { runCatching { ownedTransport?.close() } }
            forwards.toList().forEach { runCatching { it.close() } }
            withTimeoutOrNull(1_000) { runCatching { ownedClient?.disconnect() } }
        }
    }

    private companion object {
        val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private class ResultBox<T>(val value: T)
}
