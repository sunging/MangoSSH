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
import org.connectbot.sshlib.ConnectResult
import org.connectbot.sshlib.HostKeyVerifier
import org.connectbot.sshlib.PingResult
import org.connectbot.sshlib.PublicKey
import org.connectbot.sshlib.SshClient
import org.connectbot.sshlib.SshClientConfig
import org.connectbot.sshlib.SshSigning
import org.connectbot.sshlib.SftpResult
import org.connectbot.sshlib.transport.KtorTcpTransportFactory
import org.connectbot.sshlib.transport.Transport
import org.connectbot.sshlib.transport.TransportFactory
import website.sung.mangossh.core.MangoLogDetail
import website.sung.mangossh.core.MangoLog
import website.sung.mangossh.core.MangoLogEvent

/** Prompt fields contain remote text and must never be included in logs or diagnostics. */
internal class SshPromptField(val text: String, val echo: Boolean)

/** Credential callbacks are suspended inside the owning connection's cancellable task. */
internal interface SshCredentials {
    /** Only these methods may be sent after the initial none probe. */
    val supportedMethods: Set<String> get() = setOf("publickey", "keyboard-interactive", "password")
    val preferPasswordAuth: Boolean get() = false
    suspend fun key(): java.security.KeyPair? = null
    suspend fun password(): String? = null
    suspend fun interactive(name: String, instruction: String, fields: List<SshPromptField>): List<String>? = null
    suspend fun banner(text: String) = Unit
}

/**
 * Protocol-neutral failure; never retains remote descriptions or credential-bearing causes.
 * [detail] holds only class names, so logs can still say what went wrong.
 */
internal class SshFailure(val category: Category, private val detail: String? = null) :
    IOException(category.name), MangoLogDetail {
    enum class Category { CLOSED, HOST_KEY, ALGORITHMS, CONNECT, AUTHENTICATION, CHANNEL, KEEPALIVE }

    override val logDetail: String get() = listOfNotNull(category.name, detail).joinToString("/")
}

/** Names the failed connect stage and its root exception class, never their messages. */
internal fun ConnectResult.failureDetail(): String {
    val cause = when (this) {
        is ConnectResult.TransportError -> cause
        is ConnectResult.ProtocolError -> cause
        else -> null
    }
    val root = cause?.let { generateSequence(it) { current -> current.cause?.takeIf { next -> next !== current } }.last() }
    return listOfNotNull(javaClass.simpleName, root?.javaClass?.simpleName?.takeIf(String::isNotBlank)).joinToString("/")
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
    /**
     * Asks the server to listen on [bind]:[port]. The returned handle is the only way to
     * stop that listener: two rules may share a port on different addresses, so a
     * port number alone cannot identify which one to cancel.
     */
    suspend fun createRemotePortForwarder(bind: String, port: Int, host: String, targetPort: Int): SshForward =
        createForward { clientOrThrow().remotePortForward(bind, port, host, targetPort) }
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

    /**
     * Connects directly or over a previous hop without opening a local listener.
     * [trustedHostKeyFamilies] are the key types already trusted for this endpoint;
     * their algorithms are negotiated first.
     */
    suspend fun connect(timeoutMillis: Long, transportTimeoutMillis: Long = timeoutMillis,
        trustedHostKeyFamilies: Set<String> = emptySet(),
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
                        applyMangoAlgorithmPolicy(legacyAlgorithms, trustedHostKeyFamilies)
                    }
                    val active = SshClient(config)
                    check(client.compareAndSet(null, active)) { "Connection already started" }
                    currentCoroutineContext().ensureActive()
                    when (val result = active.connect()) {
                        ConnectResult.Success -> scope.launch {
                            active.disconnectedFlow.collect { cause ->
                                monitors.forEach { it(if (cause == null) null else SshFailure(SshFailure.Category.CLOSED)) }
                            }
                        }.let { Unit }
                        is ConnectResult.HostKeyRejected -> throw SshFailure(SshFailure.Category.HOST_KEY)
                        is ConnectResult.AlgorithmMismatch -> throw SshFailure(SshFailure.Category.ALGORITHMS)
                        else -> throw SshFailure(SshFailure.Category.CONNECT, result.failureDetail())
                    }
                }
            }
        } catch (error: Exception) {
            close()
            currentCoroutineContext().ensureActive()
            if (error is kotlinx.coroutines.TimeoutCancellationException) throw SshFailure(SshFailure.Category.CONNECT, "Timeout")
            throw error
        }
    }

    /** None authentication precedes optional credentials, including Tailscale SSH approval. */
    suspend fun authenticate(username: String, credentials: SshCredentials): Boolean = try {
        owned {
            val handler = SshAuthenticationHandler(credentials, legacyAlgorithms, banner)
            handler.resolve(clientOrThrow().authenticate(username, handler)).also { success ->
                if (!success) MangoLog.warn(MangoLogEvent.SSH_AUTH_FAILED,
                    SshAuthenticationFailure(SshAuthenticationFailure.Category.REJECTED, handler.stage))
            }
        }
    } catch (cancelled: CancellationException) {
        close()
        throw cancelled
    } catch (failure: SshAuthenticationFailure) {
        MangoLog.warn(MangoLogEvent.SSH_AUTH_FAILED, failure)
        throw failure
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
