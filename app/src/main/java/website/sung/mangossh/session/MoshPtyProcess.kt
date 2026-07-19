package website.sung.mangossh.session

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import website.sung.mangossh.R

/**
 * A direct child process for the GPL Mosh client, connected to a pseudo
 * terminal rather than to a pipe.
 *
 * Mosh is a terminal program: a PTY is required for its terminal modes,
 * resize notifications, and escape sequences. The JNI bridge passes the host,
 * UDP port, and `MOSH_KEY` directly to `execve`; it never builds a shell
 * command from profile data.
 */
internal class MoshPtyProcess private constructor(
    private val master: ParcelFileDescriptor,
    private val pid: Int,
    val input: InputStream,
    val output: OutputStream,
) : Closeable {
    private val closed = AtomicBoolean(false)

    /** Propagates the current terminal geometry to the slave PTY. */
    fun resize(columns: Int, rows: Int) {
        require(columns > 0 && rows > 0) { "Terminal dimensions must be positive" }
        if (!closed.get()) {
            MoshPtyNative.resize(master.fd, columns, rows)
        }
    }

    /**
     * Waits for the child after its PTY stream reaches EOF. Call this only on
     * a background dispatcher; it reaps the direct child to avoid zombies.
     */
    fun awaitExit() {
        MoshPtyNative.waitForExit(pid)
    }

    /**
     * Requests graceful child termination and closes every copy of the master
     * descriptor. Waiting is intentionally performed by the reader coroutine,
     * so disconnecting from the Compose UI never blocks the main thread.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        MoshPtyNative.requestStop(pid)
        runCatching { output.close() }
        runCatching { input.close() }
        runCatching { master.close() }
    }

    internal companion object {
        private const val CLIENT_FILE_NAME = "libmosh_client.so"
        private const val DEFAULT_TERM = "xterm-256color"

        /**
         * Starts the packaged Mosh client and returns a PTY-backed process.
         *
         * The key is transient bootstrap material. It is not stored in a
         * field, logged, or propagated through an exception message.
         */
        fun start(
            context: Context,
            host: String,
            port: Int,
            key: CharArray,
            terminalColumns: Int,
            terminalRows: Int,
        ): MoshPtyProcess {
            require(port in 1..65535) { "Mosh UDP port is invalid" }
            val executable = File(context.applicationInfo.nativeLibraryDir, CLIENT_FILE_NAME)
            check(executable.isFile) { context.getString(R.string.mosh_client_missing) }
            val terminfoDir = MoshRuntimeInstaller(context.applicationContext).install()
            val keyText = key.concatToString()
            val handle = try {
                MoshPtyNative.start(
                    executable = executable.absolutePath,
                    host = host,
                    port = port,
                    key = keyText,
                    terminal = DEFAULT_TERM,
                    terminfoDirectory = terminfoDir.absolutePath,
                    columns = terminalColumns,
                    rows = terminalRows,
                )
            } finally {
                key.fill('\u0000')
            }
            check(handle.size == 2) { "Native Mosh PTY returned an invalid handle" }
            val descriptor = ParcelFileDescriptor.adoptFd(handle[0].toInt())
            return MoshPtyProcess(
                master = descriptor,
                pid = handle[1].toInt(),
                input = ParcelFileDescriptor.AutoCloseInputStream(descriptor.dup()),
                output = ParcelFileDescriptor.AutoCloseOutputStream(descriptor.dup()),
            )
        }

    }
}

/** JNI entry points for the PTY bridge. This object holds no session state. */
private object MoshPtyNative {
    init {
        System.loadLibrary("mangossh_pty")
    }

    external fun start(
        executable: String,
        host: String,
        port: Int,
        key: String,
        terminal: String,
        terminfoDirectory: String,
        columns: Int,
        rows: Int,
    ): LongArray

    external fun resize(masterFd: Int, columns: Int, rows: Int)

    external fun requestStop(pid: Int)

    external fun waitForExit(pid: Int)
}

/**
 * Installs the packaged terminfo database once in the app's no-backup storage.
 *
 * `mosh-client` is a native executable rather than an Android shared library,
 * so terminfo cannot be discovered through the normal Android resource stack.
 * Zip entries are validated against the extraction root to prevent Zip Slip.
 */
private class MoshRuntimeInstaller(private val context: Context) {
    fun install(): File {
        val destination = File(context.noBackupFilesDir, DIRECTORY_NAME)
        val marker = File(destination, MARKER_NAME)
        if (marker.isFile && marker.readText() == RUNTIME_REVISION) return terminfoDirectory(destination)

        val staging = File(context.noBackupFilesDir, "$DIRECTORY_NAME.staging")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Cannot create Mosh runtime directory" }
        val stagingPath = staging.canonicalFile
        try {
            context.assets.open(TERMINF0_ASSET).use { asset ->
                java.util.zip.ZipInputStream(asset).use { zip ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val output = File(staging, entry.name)
                        val canonicalOutput = output.canonicalFile
                        check(
                            canonicalOutput.path == stagingPath.path ||
                                canonicalOutput.path.startsWith(stagingPath.path + File.separator),
                        ) { "Invalid Mosh terminfo archive" }
                        if (entry.isDirectory) {
                            check(canonicalOutput.mkdirs() || canonicalOutput.isDirectory) {
                                "Cannot create Mosh terminfo directory"
                            }
                        } else {
                            canonicalOutput.parentFile?.mkdirs()
                            canonicalOutput.outputStream().use { stream ->
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count < 0) break
                                    stream.write(buffer, 0, count)
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
            File(staging, MARKER_NAME).writeText(RUNTIME_REVISION)
            destination.deleteRecursively()
            check(staging.renameTo(destination)) { "Cannot activate Mosh runtime" }
            return terminfoDirectory(destination)
        } catch (error: IOException) {
            staging.deleteRecursively()
            throw error
        } catch (error: IllegalStateException) {
            staging.deleteRecursively()
            throw error
        }
    }

    /** The upstream archive stores terminfo below `share/terminfo`. */
    private fun terminfoDirectory(destination: File): File {
        val terminfo = File(destination, "share/terminfo")
        check(terminfo.isDirectory) { context.getString(R.string.mosh_runtime_missing) }
        return terminfo
    }

    private companion object {
        const val DIRECTORY_NAME = "mosh-terminfo"
        const val MARKER_NAME = ".mangossh-runtime-revision"
        const val RUNTIME_REVISION = "mosh4android-2de58be"
        const val TERMINF0_ASSET = "mosh/terminfo.zip"
    }
}
