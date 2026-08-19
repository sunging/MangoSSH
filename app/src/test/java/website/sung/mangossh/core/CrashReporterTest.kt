package website.sung.mangossh.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crash record is meant to be handed to someone else, so the property that
 * matters is what it leaves out.
 */
class CrashReporterTest {
    @Test
    fun omitsExceptionMessagesFromEveryLevelOfTheChain() {
        val cause = IllegalStateException("connect to secret.internal.example as admin")
        val error = RuntimeException("session 4f3c failed for /home/user/private", cause)

        val report = CrashReporter.format(error, epochMillis = 1_700_000_000_000L)

        assertFalse(report.contains("secret.internal.example"))
        assertFalse(report.contains("admin"))
        assertFalse(report.contains("/home/user/private"))
        assertFalse(report.contains("4f3c"))
    }

    @Test
    fun namesEveryThrowableInTheChain() {
        val error = RuntimeException("outer", IllegalStateException("inner"))

        val report = CrashReporter.format(error, epochMillis = 0L)

        assertTrue(report.contains("java.lang.RuntimeException"))
        assertTrue(report.contains("java.lang.IllegalStateException"))
        assertTrue(report.contains("caused by:"))
    }

    @Test
    fun recordsStackFrameLocations() {
        val error = runCatching { error("boom") }.exceptionOrNull()!!

        val report = CrashReporter.format(error, epochMillis = 0L)

        assertTrue(report.contains("CrashReporterTest"))
        assertTrue(report.contains("    at "))
    }

    @Test
    fun terminatesOnASelfReferencingCause() {
        // A throwable whose cause is itself must not spin the formatter.
        val error = object : RuntimeException("self") {
            override val cause: Throwable get() = this
        }

        val report = CrashReporter.format(error, epochMillis = 0L)

        assertTrue(report.contains("thrown: "))
    }
}
