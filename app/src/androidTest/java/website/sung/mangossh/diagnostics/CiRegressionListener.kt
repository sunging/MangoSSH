package website.sung.mangossh.diagnostics

import android.app.KeyguardManager
import android.os.PowerManager
import android.os.ParcelFileDescriptor
import android.os.Build
import android.system.ErrnoException
import android.view.accessibility.AccessibilityNodeInfo
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

    /** Records picker failure metadata on CI without node text, descriptions, document URIs or screenshots. */
    fun pickerState(phase: String) {
        if (InstrumentationRegistry.getArguments().getString("ciDiagnostics") != "true") return
        // Diagnostics must preserve the original assertion even if accessibility itself is unavailable.
        runCatching {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val automation = instrumentation.uiAutomation
            val service = automation.serviceInfo
            val fields = JSONObject().put("phase", safe(phase))
                .put("interactive", context.getSystemService(PowerManager::class.java).isInteractive)
                .put("keyguardLocked", context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
                .put("serviceFlags", service.flags).put("eventTypes", service.eventTypes)
                .put("packageFilter", JSONArray(service.packageNames.orEmpty().map(::safe)))
            val nodes = JSONArray()
            fun describe(node: AccessibilityNodeInfo, depth: Int) {
                if (depth > 20 || nodes.length() >= 200) return
                nodes.put(JSONObject().put("package", safe(node.packageName?.toString().orEmpty()))
                    .put("class", safe(node.className?.toString().orEmpty()))
                    .put("viewId", safe(node.viewIdResourceName.orEmpty()))
                    .put("enabled", node.isEnabled).put("clickable", node.isClickable)
                    .put("editable", node.isEditable).put("visible", node.isVisibleToUser))
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index) ?: continue
                    try { describe(child, depth + 1) } finally { child.recycle() }
                }
            }
            val root = automation.rootInActiveWindow
            fields.put("rootPresent", root != null)
            if (root != null) try { describe(root, 0) } finally { root.recycle() }
            fields.put("nodes", nodes)
            val windows = JSONArray()
            automation.windows.forEach { window ->
                try {
                    val item = JSONObject().put("type", window.type).put("active", window.isActive)
                        .put("focused", window.isFocused)
                    val windowRoot = window.root
                    if (windowRoot != null) try {
                        item.put("package", safe(windowRoot.packageName?.toString().orEmpty()))
                    } finally { windowRoot.recycle() }
                    windows.put(item)
                } finally { window.recycle() }
            }
            record("picker-state", fields.put("windows", windows))
        }
    }

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
        CiRegressionDiagnostics.pickerState("run-start")
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
