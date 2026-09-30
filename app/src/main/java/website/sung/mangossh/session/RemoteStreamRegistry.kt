package website.sung.mangossh.session

import java.io.InterruptedIOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A remote file offered to other apps through [RemoteStreamProvider].
 *
 * [token] is the only part of the content URI used for lookup; it is random,
 * so a URI cannot be guessed from a path. [identity] fixes the size and
 * modification time the file was offered with.
 */
internal data class RemoteStreamTarget(
    val token: String,
    val sessionId: String,
    val identity: SourceIdentity,
    val displayName: String,
    val mimeType: String,
) {
    val size: Long get() = requireNotNull(identity.size)
}

/**
 * Tracks streamed files and the descriptors external apps hold on them.
 *
 * A stream lives as long as its session, capped at [maxTokens] entries. Its
 * SFTP channel is open only while a descriptor is, and its cached blocks are
 * kept for [graceMillis] after the last descriptor closes, because players
 * often reopen the URI when seeking or resuming. While a stream is open or in
 * that grace period, [isBusy] keeps a browser-owned connection alive.
 *
 * Paths and names are user data and are never logged.
 */
internal class RemoteStreamRegistry(
    private val budget: RemoteStreamCacheBudget,
    private val scope: CoroutineScope,
    private val openReader: suspend (RemoteStreamTarget) -> RemoteChunkReader,
    private val onRevoked: (RemoteStreamTarget) -> Unit,
    private val onIdle: (sessionId: String) -> Unit,
    private val graceMillis: Long = 30_000L,
    private val maxTokens: Int = 16,
) {
    private val lock = Any()
    private val streams = LinkedHashMap<String, Stream>()

    /**
     * Offers [identity] from [sessionId]. An unchanged file already offered on
     * the same session keeps its token, so blocks still cached are reused.
     */
    fun register(sessionId: String, identity: SourceIdentity, displayName: String, mimeType: String): RemoteStreamTarget {
        requireNotNull(identity.size)
        val evicted = mutableListOf<Stream>()
        val target = synchronized(lock) {
            streams.values.firstOrNull {
                it.target.sessionId == sessionId && it.target.identity == identity && identity.modifiedMillis != null
            }?.let { return@synchronized it.target }
            val created = RemoteStreamTarget(UUID.randomUUID().toString(), sessionId, identity, displayName, mimeType)
            streams[created.token] = Stream(created)
            while (streams.size > maxTokens) {
                val oldest = streams.values.firstOrNull { it.idle } ?: break
                streams.remove(oldest.target.token)
                evicted += oldest
            }
            created
        }
        evicted.forEach(::retire)
        return target
    }

    fun find(token: String): RemoteStreamTarget? = synchronized(lock) { streams[token]?.target }

    /** Opens one descriptor on [token], or returns null when it is not offered any more. */
    fun acquire(token: String): Stream? = synchronized(lock) {
        val stream = streams.remove(token) ?: return null
        streams[token] = stream // Most recently used last, so the cap drops idle old tokens first.
        stream.descriptors++
        stream.grace?.cancel()
        stream.grace = null
        val cache = stream.cache ?: RemoteStreamCache(stream.target.size, budget, scope, stream::readAt)
            .also { stream.cache = it }
        cache.active = true
        stream
    }

    /** Closes one descriptor from [acquire]. */
    fun release(stream: Stream) {
        synchronized(lock) {
            stream.descriptors--
            // A withdrawn stream already released its cache and channel.
            if (stream.descriptors > 0 || stream.withdrawn) return
            stream.cache?.active = false
            stream.grace = scope.launch {
                delay(graceMillis)
                val cache = synchronized(lock) {
                    if (stream.descriptors > 0) return@launch
                    stream.grace = null
                    stream.cache.also { stream.cache = null }
                }
                cache?.let { it.close(); budget.release(it) }
                onIdle(stream.target.sessionId)
            }
        }
        stream.closeReader()
    }

    /** True while [sessionId] has an open stream or one inside its grace period. */
    fun isBusy(sessionId: String): Boolean = synchronized(lock) {
        streams.values.any { it.target.sessionId == sessionId && !it.idle }
    }

    /** Withdraws every stream of an ended session; open descriptors fail from now on. */
    fun onSessionEnded(sessionId: String) {
        val ended = synchronized(lock) {
            streams.values.filter { it.target.sessionId == sessionId }
                .onEach { streams.remove(it.target.token) }
        }
        ended.forEach(::retire)
    }

    private fun retire(stream: Stream) {
        val cache = synchronized(lock) {
            stream.withdrawn = true
            stream.grace?.cancel()
            stream.grace = null
            stream.cache.also { stream.cache = null }
        }
        cache?.let { it.close(); budget.release(it) }
        stream.closeReader()
        onRevoked(stream.target)
    }

    /** One offered file; mutable fields are guarded by the registry lock. */
    inner class Stream(val target: RemoteStreamTarget) {
        internal var descriptors = 0
        internal var cache: RemoteStreamCache? = null
        internal var grace: Job? = null
        internal var withdrawn = false
        private val readerLock = Mutex()
        private var reader: RemoteChunkReader? = null

        internal val idle: Boolean get() = descriptors == 0 && grace == null

        /** Serves one descriptor read; fails once the stream is withdrawn. */
        suspend fun read(offset: Long, destination: ByteArray, count: Int): Int {
            val cache = synchronized(lock) { cache } ?: throw InterruptedIOException()
            return cache.read(offset, destination, 0, count)
        }

        /**
         * Reads one chunk through the stream's SFTP channel, opening it on
         * demand. A broken channel is replaced once; a changed or missing file
         * is not retried, because its cached blocks would no longer match.
         */
        internal suspend fun readAt(offset: Long, count: Int): ByteArray? {
            repeat(2) { attempt ->
                val session = readerLock.withLock {
                    // Read-ahead can outlive the last descriptor; it must not reopen the channel.
                    if (synchronized(lock) { descriptors == 0 || withdrawn }) throw InterruptedIOException()
                    reader ?: openReader(target).also { reader = it }
                }
                try {
                    return session.readAt(offset, count)
                } catch (error: RemoteFileException) {
                    readerLock.withLock { if (reader === session) reader = null }
                    session.close()
                    if (attempt == 1 || error.failure != RemoteFileFailure.IO_FAILURE) throw error
                }
            }
            throw InterruptedIOException()
        }

        /**
         * Closes the SFTP channel once no descriptor is open. It waits for an
         * open already in progress, so a channel cannot be left behind by a
         * descriptor that closed while its first read was connecting.
         */
        internal fun closeReader() {
            scope.launch {
                readerLock.withLock {
                    if (synchronized(lock) { descriptors > 0 && !withdrawn }) return@withLock
                    reader?.close()
                    reader = null
                }
            }
        }
    }
}
