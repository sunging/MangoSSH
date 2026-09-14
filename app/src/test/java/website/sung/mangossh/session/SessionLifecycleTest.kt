package website.sung.mangossh.session

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class SessionLifecycleTest {
    @Test fun resourceCreatedBeforeCloseButAdoptedAfterwardsIsReleased() {
        val lifecycle = SessionLifecycle()
        val created = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val releases = AtomicInteger()
        val worker = Thread {
            val resource = Any()
            created.countDown(); resume.await()
            assertFalse(lifecycle.adopt(resource) { releases.incrementAndGet() })
        }
        worker.start(); created.await()
        lifecycle.close()!!.forEach { it() }
        resume.countDown(); worker.join(2_000)
        assertFalse(worker.isAlive)
        assertEquals(1, releases.get())
        assertNull(lifecycle.close())
    }

    @Test fun closeDetachesExactlyOnceAndPreventsPromptPublication() {
        val lifecycle = SessionLifecycle()
        var releases = 0
        lifecycle.adopt(Any()) { releases++ }
        val cleanup = lifecycle.close()!!
        assertEquals(0, releases)
        assertNull(lifecycle.whileOpen { error("Cannot publish a prompt") })
        cleanup.forEach { it() }
        assertEquals(1, releases)
        assertNull(lifecycle.close())
    }
}
