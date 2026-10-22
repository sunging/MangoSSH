package website.sung.mangossh.diagnostics

import android.os.ParcelFileDescriptor
import android.os.Build
import android.system.ErrnoException
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.runner.Description
import org.junit.runner.Result
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener
import website.sung.mangossh.session.SourceChangedException
import website.sung.mangossh.session.ssh.SshFailure
import website.sung.mangossh.session.ssh.SshFileFailure

/** CI-only journal survives runner termination and contains no peer text or file contents. */
object CiRegressionDiagnostics {
    private fun safe(value: String) = value.replace(Regex("[^A-Za-z0-9_.$#:-]"), "_")
    fun id(description: Description) = safe("${description.className}#${description.methodName}")

    @Synchronized fun record(event: String, fields: JSONObject = JSONObject()) {
        if (InstrumentationRegistry.getArguments().getString("ciDiagnostics") != "true") return
        fields.put("event", event).put("elapsedMs", android.os.SystemClock.elapsedRealtime())
        if (Build.VERSION.SDK_INT < 31) throw IllegalStateException("CI journal requires API 31 or newer")
        // UiAutomation executes argv, not shell syntax. Feed the script to an explicit sh
        // through stdin: semicolons/redirections must not become mkdir path arguments.
        val json = fields.toString().replace("'", "'\\''")
        val script = "mkdir -p /sdcard/mangossh-ci-diagnostics && " +
            "printf '%s\\n' '$json' >> /sdcard/mangossh-ci-diagnostics/run-events.jsonl && " +
            "printf journal-written\n"
        val pipes = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommandRw("sh")
        ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { output ->
            ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { input ->
                input.write(script.toByteArray(Charsets.UTF_8))
            }
            check(output.readBytes().toString(Charsets.UTF_8) == "journal-written") {
                "CI journal append failed"
            }
        }
    }

    fun phase(name: String, index: Int = -1, offset: Long = -1, count: Int = -1) =
        record("phase", JSONObject().put("name", safe(name)).put("index", index).put("offset", offset).put("count", count))

    fun failure(error: Throwable, test: String = "operation") {
        val causes = JSONArray()
        generateSequence(error) { it.cause }.take(8).forEach { cause ->
            val item = JSONObject().put("type", safe(cause.javaClass.name))
            if (cause is ErrnoException) item.put("errno", cause.errno)
            if (cause is SshFailure) item.put("category", cause.category.name)
            if (cause is SshFileFailure) item.put("status", cause.status).put("stage", cause.stage.name)
            if (cause is SourceChangedException) item.put("reason", cause.reason.name)
            item.put("frames", JSONArray(cause.stackTrace.take(30).map {
                safe("${it.className}.${it.methodName}:${it.lineNumber}")
            }))
            causes.put(item)
        }
        record("failure", JSONObject().put("test", safe(test)).put("causes", causes))
    }
}

/** Registered through AndroidJUnitRunner's listener argument only for disposable CI app suites. */
class CiRegressionListener : RunListener() {
    override fun testRunStarted(description: Description) {
        fun leaves(node: Description): List<String> = if (node.isTest) listOf(CiRegressionDiagnostics.id(node))
            else node.children.flatMap(::leaves)
        CiRegressionDiagnostics.record("plan", JSONObject().put("tests", JSONArray(leaves(description))))
    }
    override fun testStarted(description: Description) = CiRegressionDiagnostics.record("started",
        JSONObject().put("test", CiRegressionDiagnostics.id(description)))
    override fun testFinished(description: Description) = CiRegressionDiagnostics.record("finished",
        JSONObject().put("test", CiRegressionDiagnostics.id(description)))
    override fun testFailure(failure: Failure) = CiRegressionDiagnostics.failure(failure.exception,
        CiRegressionDiagnostics.id(failure.description))
    override fun testIgnored(description: Description) = CiRegressionDiagnostics.record("ignored",
        JSONObject().put("test", CiRegressionDiagnostics.id(description)))
    override fun testRunFinished(result: Result) = CiRegressionDiagnostics.record("run_finished",
        JSONObject().put("count", result.runCount).put("failures", result.failureCount).put("ignored", result.ignoreCount))
}
