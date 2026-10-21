package website.sung.mangossh.diagnostics

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Exercises shell-owned journal transport on the disposable CI device, including pipe-sized data. */
class CiRegressionListenerInstrumentedTest {
    @Test fun journalAppendsLiteralJsonAndPreservesExistingEvents() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("ciDiagnostics") == "true")
        val literal = "quotes ' \" ; dollar ${'$'}(printf unsafe) newline\n" + "x".repeat(80_000)
        CiRegressionDiagnostics.record("journal_probe", JSONObject().put("sequence", 1).put("literal", literal))
        CiRegressionDiagnostics.record("journal_probe", JSONObject().put("sequence", 2).put("literal", "second"))
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "cat /sdcard/mangossh-ci-diagnostics/run-events.jsonl"
        )
        val events = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            input.readBytes().toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.map(::JSONObject).toList()
        }
        assertTrue("Earlier runner plan must remain readable", events.any { it.getString("event") == "plan" })
        val probes = events.filter { it.getString("event") == "journal_probe" }
        assertEquals(listOf(1, 2), probes.map { it.getInt("sequence") })
        assertEquals(literal, probes[0].getString("literal"))
        assertEquals("second", probes[1].getString("literal"))
    }
}
