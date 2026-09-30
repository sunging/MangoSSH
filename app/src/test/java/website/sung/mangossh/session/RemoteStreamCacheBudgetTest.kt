package website.sung.mangossh.session

import android.content.ComponentCallbacks2
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteStreamCacheBudgetTest {
    private val scope = CoroutineScope(Job())
    private val allocations = mutableListOf<ByteBuffer>()

    @After fun tearDown() = scope.cancel()

    @Test fun theDeviceCeilingCapsTheConfiguredLimit() {
        val budget = budget(configuredBlocks = 100, ceilingBlocks = 6)
        assertEquals(6L * BLOCK, budget.usage.value.limitBytes)
    }

    @Test fun theLimitNeverFallsBelowTheMinimumBlockCount() {
        val budget = budget(configuredBlocks = 1)
        assertEquals(RemoteStreamCacheBudget.MIN_BLOCKS.toLong() * BLOCK, budget.usage.value.limitBytes)
    }

    @Test fun theLeastRecentlyUsedBlockIsEvictedFirst() {
        val budget = budget(configuredBlocks = 4)
        val owner = owner(budget)
        (0L until 4L).forEach { fill(budget, owner, it) }
        budget.unpin(requireNotNull(budget.pin(owner, 0)))
        owner.cursorBlock = 100 // Nothing near the reader, so recency alone decides.
        fill(budget, owner, 4)
        assertTrue(budget.contains(owner, 0))
        assertFalse(budget.contains(owner, 1))
        assertEquals(4L * BLOCK, budget.usage.value.usedBytes)
    }

    @Test fun idleStreamsAreEvictedBeforeOpenOnes() {
        val budget = budget(configuredBlocks = 4)
        val open = owner(budget).apply { cursorBlock = 100 }
        val idle = owner(budget).apply { active = false }
        fill(budget, idle, 0)
        (0L until 3L).forEach { fill(budget, open, it) }
        fill(budget, open, 3)
        assertFalse(budget.contains(idle, 0))
        (0L until 4L).forEach { assertTrue(budget.contains(open, it)) }
    }

    @Test fun aPinnedBlockIsNeverReused() {
        val budget = budget(configuredBlocks = 4)
        val owner = owner(budget).apply { cursorBlock = 100 }
        (0L until 4L).forEach { fill(budget, owner, it) }
        val pinned = requireNotNull(budget.pin(owner, 0))
        budget.unpin(requireNotNull(budget.pin(owner, 1)))
        budget.unpin(requireNotNull(budget.pin(owner, 2)))
        budget.unpin(requireNotNull(budget.pin(owner, 3)))
        fill(budget, owner, 4)
        assertTrue(budget.contains(owner, 0))
        budget.unpin(pinned)
    }

    @Test fun loweringTheLimitEvictsImmediately() {
        val budget = budget(configuredBlocks = 8)
        val owner = owner(budget).apply { cursorBlock = 100 }
        (0L until 8L).forEach { fill(budget, owner, it) }
        budget.setConfiguredBytes(4L * BLOCK)
        assertEquals(4L * BLOCK, budget.usage.value.usedBytes)
    }

    // Pre-API 34 devices still deliver the deprecated RUNNING_* levels.
    @Suppress("DEPRECATION")
    @Test fun memoryPressureKeepsOnlyTheReadAheadWindow() {
        val budget = budget(configuredBlocks = 64)
        val owner = owner(budget).apply { cursorBlock = 10 }
        (0L until 30L).forEach { fill(budget, owner, it) }
        budget.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertEquals(30L * BLOCK, budget.usage.value.usedBytes)
        budget.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        val window = budget.readAheadBlocks
        (0L until 30L).forEach { assertEquals(it in 10..10 + window, budget.contains(owner, it)) }
    }

    @Test fun releasingAStreamReturnsItsBlocksAndRefusesLateOnes() {
        val budget = budget(configuredBlocks = 8)
        val owner = owner(budget)
        (0L until 3L).forEach { fill(budget, owner, it) }
        val late = budget.acquire()
        budget.release(owner)
        budget.register(owner, 5, late, BLOCK)
        assertEquals(0L, budget.usage.value.usedBytes)
        assertFalse(budget.contains(owner, 5))
    }

    @Test fun releasedBuffersAreReused() {
        val budget = budget(configuredBlocks = 4)
        val owner = owner(budget).apply { cursorBlock = 100 }
        (0L until 12L).forEach { fill(budget, owner, it) }
        assertEquals(4, allocations.size)
    }

    private fun fill(budget: RemoteStreamCacheBudget, owner: RemoteStreamCache, index: Long) {
        budget.register(owner, index, budget.acquire(), BLOCK)
    }

    private fun owner(budget: RemoteStreamCacheBudget) = RemoteStreamCache(1L shl 40, budget, scope) { _, _ -> null }

    private fun budget(configuredBlocks: Int, ceilingBlocks: Int = 1_000) = RemoteStreamCacheBudget(
        configuredBytes = configuredBlocks.toLong() * BLOCK,
        ceilingBytes = ceilingBlocks.toLong() * BLOCK,
        blockBytes = BLOCK,
        allocate = { ByteBuffer.allocate(it).also(allocations::add) },
    )

    private companion object {
        const val BLOCK = 1024
    }
}
