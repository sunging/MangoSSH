package website.sung.mangossh

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import website.sung.mangossh.session.SessionForegroundService

@RunWith(AndroidJUnit4::class)
class MangoSshInstrumentedTest {
    @Test
    fun usesThePublicPackageName() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("website.sung.mangossh", appContext.packageName)
    }

    @Test
    fun emptyForegroundServiceStartDoesNotCrashTheProcess() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext

        SessionForegroundService.start(appContext)
        instrumentation.waitForIdleSync()
        // Android raises ForegroundServiceDidNotStartInTimeException after
        // the service deadline. Reaching this assertion proves that the
        // empty-session fast-failure path acknowledged the foreground start
        // and then shut itself down from the observed empty session state.
        SystemClock.sleep(6_000)
        assertTrue("Instrumentation process survived the foreground-service deadline", true)
    }
}
