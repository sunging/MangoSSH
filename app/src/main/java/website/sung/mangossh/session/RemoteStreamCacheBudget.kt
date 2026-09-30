package website.sung.mangossh.session

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import java.nio.ByteBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide memory budget shared by every streamed remote file.
 *
 * Streamed bytes are cached in memory only and never written to storage.
 * Blocks live in direct buffers, which sit outside the Java heap limit, so a
 * cache of several hundred megabytes cannot starve Compose or the terminals of
 * heap. [ceilingBytes] caps the user's choice at what this device can afford;
 * the configured value itself is kept, so a later ceiling change honours it.
 *
 * Eviction takes blocks of streams nobody has open first, then blocks outside
 * an open stream's read-ahead window, least recently used first within each
 * tier. A pinned block is being copied out and is never reused until unpinned.
 * When every block is pinned or loading the budget is exceeded briefly rather
 * than blocking a reader.
 */
internal class RemoteStreamCacheBudget(
    configuredBytes: Long,
    private val ceilingBytes: Long,
    val blockBytes: Int = DEFAULT_BLOCK_BYTES,
    private val allocate: (Int) -> ByteBuffer = ByteBuffer::allocateDirect,
) {
    /** Bytes held by cached blocks against the limit that currently applies. */
    data class Usage(val usedBytes: Long, val limitBytes: Long, val ceilingBytes: Long)

    private val lock = Any()
    private val blocks = ArrayList<RemoteStreamBlock>()
    private val pool = ArrayDeque<ByteBuffer>()
    private var loadingBuffers = 0
    private var clock = 0L
    private var configured = configuredBytes

    private val _usage = MutableStateFlow(Usage(0L, effectiveBytes(), ceilingBytes))
    val usage: StateFlow<Usage> = _usage.asStateFlow()

    /** Blocks an open stream keeps ahead of its latest read. */
    val readAheadBlocks: Int get() = synchronized(lock) { (maxBlocks() / 4).coerceIn(1, MAX_READ_AHEAD_BLOCKS) }

    /** Applies a new user limit; lowering it evicts immediately. */
    fun setConfiguredBytes(bytes: Long) = synchronized(lock) {
        configured = bytes
        shrink()
        publish()
    }

    /**
     * Hands out a cleared buffer for one block, evicting to stay in budget.
     * The caller must pass it to exactly one of [register] or [recycle].
     */
    fun acquire(): ByteBuffer = synchronized(lock) {
        while (blocks.size + loadingBuffers >= maxBlocks()) {
            if (!evictOne()) break
        }
        loadingBuffers++
        (pool.removeFirstOrNull() ?: allocate(blockBytes)).also { it.clear() }
    }

    /** Returns an unused buffer from [acquire]. */
    fun recycle(buffer: ByteBuffer) = synchronized(lock) {
        loadingBuffers--
        keep(buffer)
    }

    /**
     * Publishes a filled buffer as block [index] of [owner]. A retired owner no
     * longer serves reads, so its late block goes straight back to the pool.
     */
    fun register(owner: RemoteStreamCache, index: Long, buffer: ByteBuffer, length: Int) = synchronized(lock) {
        loadingBuffers--
        if (owner.retired || owner.blocks.containsKey(index)) {
            keep(buffer)
        } else {
            val block = RemoteStreamBlock(owner, index, buffer, length, ++clock)
            blocks += block
            owner.blocks[index] = block
            publish()
        }
    }

    fun contains(owner: RemoteStreamCache, index: Long): Boolean = synchronized(lock) { owner.blocks.containsKey(index) }

    /** Pins block [index] of [owner] for copying, or returns null when it is not cached. */
    fun pin(owner: RemoteStreamCache, index: Long): RemoteStreamBlock? = synchronized(lock) {
        owner.blocks[index]?.also {
            it.pins++
            it.lastUsed = ++clock
        }
    }

    fun unpin(block: RemoteStreamBlock) = synchronized(lock) {
        block.pins--
        if (block.pins == 0 && block.owner.retired && block.owner.blocks[block.index] === block) {
            drop(block)
            publish()
        }
    }

    /** Drops every idle block of a stream that is going away. */
    fun release(owner: RemoteStreamCache) = synchronized(lock) {
        owner.retired = true
        owner.blocks.values.filter { it.pins == 0 }.forEach(::drop)
        trimPool()
        publish()
    }

    /**
     * Responds to system memory pressure: only blocks inside an open stream's
     * read-ahead window survive, and pooled buffers are handed back.
     */
    fun trim() = synchronized(lock) {
        blocks.filter { it.pins == 0 && tier(it) < PROTECTED_TIER }.forEach(::drop)
        pool.clear()
        publish()
    }

    /**
     * Trims only for real memory pressure. `TRIM_MEMORY_UI_HIDDEN` is ignored on
     * purpose: it arrives exactly when the user switches to the player.
     */
    @Suppress("DEPRECATION")
    fun onTrimMemory(level: Int) {
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        ) trim()
    }

    private fun effectiveBytes(): Long = minOf(configured, ceilingBytes).coerceAtLeast(MIN_BLOCKS.toLong() * blockBytes)

    private fun maxBlocks(): Int = (effectiveBytes() / blockBytes).coerceIn(MIN_BLOCKS.toLong(), Int.MAX_VALUE.toLong()).toInt()

    private fun shrink() {
        while (blocks.size + loadingBuffers > maxBlocks()) {
            if (!evictOne()) break
        }
        trimPool()
    }

    private fun evictOne(): Boolean {
        val victim = blocks.asSequence()
            .filter { it.pins == 0 }
            .minWithOrNull(compareBy<RemoteStreamBlock>({ tier(it) }, { it.lastUsed }))
            ?: return false
        drop(victim)
        return true
    }

    private fun tier(block: RemoteStreamBlock): Int {
        val owner = block.owner
        if (!owner.active) return 0
        val cursor = owner.cursorBlock
        val window = (maxBlocks() / 4).coerceIn(1, MAX_READ_AHEAD_BLOCKS)
        return if (block.index in cursor..cursor + window) PROTECTED_TIER else 1
    }

    private fun drop(block: RemoteStreamBlock) {
        blocks.remove(block)
        block.owner.blocks.remove(block.index)
        keep(block.buffer)
    }

    private fun keep(buffer: ByteBuffer) {
        if (blocks.size + loadingBuffers + pool.size < maxBlocks()) pool.addLast(buffer)
    }

    private fun trimPool() {
        while (pool.isNotEmpty() && blocks.size + loadingBuffers + pool.size > maxBlocks()) pool.removeLast()
    }

    private fun publish() {
        _usage.value = Usage(blocks.sumOf { it.length.toLong() }, effectiveBytes(), ceilingBytes)
    }

    companion object {
        /** A whole number of 32 KiB SFTP reads, large enough to amortize a round trip. */
        const val DEFAULT_BLOCK_BYTES = 1024 * 1024

        /** Enough for a reader, its read-ahead, and one block still loading. */
        const val MIN_BLOCKS = 4

        /** Read-ahead never grows past 8 MiB, however large the cache is. */
        const val MAX_READ_AHEAD_BLOCKS = 8

        private const val PROTECTED_TIER = 2

        private const val LOW_RAM_CEILING_BYTES = 64L * 1024 * 1024

        /**
         * The most this device should spend on streamed blocks: a quarter of
         * its RAM, and no more than 64 MiB on a device Android calls low-RAM.
         */
        fun deviceCeilingBytes(context: Context): Long {
            val manager = context.getSystemService(ActivityManager::class.java) ?: return LOW_RAM_CEILING_BYTES
            val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
            val quarter = memory.totalMem / 4
            return if (manager.isLowRamDevice) minOf(quarter, LOW_RAM_CEILING_BYTES) else quarter
        }
    }
}

/** One cached block. All mutable fields are guarded by the budget's lock. */
internal class RemoteStreamBlock(
    val owner: RemoteStreamCache,
    val index: Long,
    val buffer: ByteBuffer,
    val length: Int,
    var lastUsed: Long,
) {
    var pins = 0
}
