package website.sung.mangossh.core

import android.content.Context
import java.io.File

/**
 * Persists a sanitized record of the throwable that ended the process.
 *
 * A terminal app crash is almost always a lifecycle race that the user cannot
 * reproduce on demand, and Logcat is gone by the time they notice, so the last
 * crash is written to app-private storage instead.
 *
 * The record deliberately excludes every [Throwable.message]. Protocol and
 * crypto libraries routinely put the hostname, the remote banner, a selected
 * path, or credential-adjacent text into their messages, and this file is meant
 * to be handed to someone else. Type names and stack frames identify the defect
 * without carrying any of that. This mirrors what [MangoLog.warn] already does
 * for the running app.
 */
object CrashReporter {
    private const val DIRECTORY_NAME = "crash"
    private const val FILE_NAME = "last-crash.txt"
    private const val MAX_FRAMES_PER_THROWABLE = 40
    private const val MAX_CAUSE_DEPTH = 8

    /**
     * Installs the process-wide handler.
     *
     * The previous handler is always invoked afterwards: this records the crash,
     * it does not suppress it. Swallowing the throwable would leave the process
     * alive in an undefined state, which is worse than crashing.
     */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(appContext, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Returns the stored report, or null when this install has not crashed yet. */
    fun lastReport(context: Context): String? {
        val file = reportFile(context)
        return if (file.isFile) runCatching { file.readText() }.getOrNull() else null
    }

    /** Discards the stored report once the user has exported it. */
    fun clear(context: Context) {
        runCatching { reportFile(context).delete() }
    }

    private fun write(context: Context, error: Throwable) {
        val file = reportFile(context)
        val created = file.parentFile?.let { it.isDirectory || it.mkdirs() } == true
        if (!created) {
            MangoLog.warn(MangoLogEvent.CRASH_REPORT_FAILED)
            return
        }
        runCatching { file.writeText(format(error, System.currentTimeMillis())) }
            .onSuccess { MangoLog.info(MangoLogEvent.CRASH_REPORT_WRITTEN) }
            .onFailure { failure -> MangoLog.warn(MangoLogEvent.CRASH_REPORT_FAILED, failure) }
    }

    private fun reportFile(context: Context): File =
        File(File(context.applicationContext.noBackupFilesDir, DIRECTORY_NAME), FILE_NAME)

    /**
     * Renders the throwable chain as type names and frames only.
     *
     * Kept internal and pure so a unit test can assert that no message text
     * survives, which is the property that makes the file safe to share.
     */
    internal fun format(error: Throwable, epochMillis: Long): String = buildString {
        append("MangoSSH crash report\n")
        append("at=").append(epochMillis).append('\n')
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            append(if (depth == 0) "\nthrown: " else "\ncaused by: ")
            append(current.javaClass.name).append('\n')
            current.stackTrace.take(MAX_FRAMES_PER_THROWABLE).forEach { frame ->
                append("    at ")
                    .append(frame.className)
                    .append('.')
                    .append(frame.methodName)
                    .append('(')
                    .append(frame.fileName ?: "unknown")
                    .append(':')
                    .append(frame.lineNumber)
                    .append(")\n")
            }
            if (current.stackTrace.size > MAX_FRAMES_PER_THROWABLE) {
                append("    ... ").append(current.stackTrace.size - MAX_FRAMES_PER_THROWABLE)
                    .append(" more\n")
            }
            val next = current.cause
            current = if (next === current) null else next
            depth++
        }
    }
}
