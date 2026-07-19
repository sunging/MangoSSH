package website.sung.mangossh

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MangoSshInstrumentedTest {
    @Test
    fun usesThePublicPackageName() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("website.sung.mangossh", appContext.packageName)
    }
}
