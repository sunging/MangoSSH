package website.sung.mangossh

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

/** Sensor provisioning is opt-in and restricted to the task-owned AVD, never a shared device. */
internal object BiometricTestEnvironment {
    fun requireDisposableAvd() {
        assumeTrue("Virtual-sensor tests require explicit opt-in",
            InstrumentationRegistry.getArguments().getString("runBiometricSensor") == "true")
        assertEquals("1", property("ro.kernel.qemu"))
        assertEquals("mangossh-lock-regression", property("ro.boot.qemu.avd_name"))
    }

    /** Wraps Activity creation so ordinary CI skips these tests before creating a fixture. */
    fun rule(): TestRule = TestRule { base, _ -> object : Statement() {
        override fun evaluate() { requireDisposableAvd(); base.evaluate() }
    } }

    private fun property(name: String): String = InstrumentationRegistry.getInstrumentation().uiAutomation
        .executeShellCommand("getprop $name").use { fd ->
            ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.bufferedReader().readText().trim() }
        }
}
