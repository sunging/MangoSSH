package website.sung.mangossh.session

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import java.io.FileNotFoundException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import website.sung.mangossh.MangoSshApplication
import website.sung.mangossh.core.MangoLog
import website.sung.mangossh.core.MangoLogEvent

/**
 * Serves streamed remote files to other apps as seekable read-only descriptors.
 *
 * Each `openFile` returns a proxy descriptor from
 * [StorageManager.openProxyFileDescriptor]: the platform turns the receiving
 * app's reads and seeks into [ProxyFileDescriptorCallback.onRead] calls, which
 * are answered from [RemoteStreamCache] and ultimately from SFTP reads. Nothing
 * is written to storage.
 *
 * The provider is not exported. Only an app granted a URI through an intent can
 * read it, and the random token in the URI is its only lookup key; the file
 * name after it is cosmetic. Callbacks run on a thread per descriptor because
 * they block on the network.
 */
class RemoteStreamProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException()
        val registry = registry() ?: throw FileNotFoundException()
        val token = tokenOf(uri) ?: throw FileNotFoundException()
        return openDescriptor(requireNotNull(context), registry, token)
    }

    override fun getType(uri: Uri): String? = target(uri)?.mimeType

    /** Answers the display name and size queries apps make before reading. */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val target = target(uri) ?: return null
        val columns = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }
            ?: listOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns.toTypedArray(), 1).apply {
            addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) target.displayName else target.size })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    private fun target(uri: Uri): RemoteStreamTarget? = tokenOf(uri)?.let { registry()?.find(it) }

    private fun registry(): RemoteStreamRegistry? =
        (context?.applicationContext as? MangoSshApplication)?.existingSessionRuntime?.sessionController?.remoteStreams

    internal companion object {
        private const val READ_TIMEOUT_MILLIS = 30_000L

        /**
         * Opens a proxy descriptor on [token]. Every read blocks this
         * descriptor's own thread; a failure reaches the reading app as `EIO`
         * and is logged by category only.
         */
        fun openDescriptor(context: Context, registry: RemoteStreamRegistry, token: String): ParcelFileDescriptor {
            val stream = registry.acquire(token) ?: throw FileNotFoundException()
            val thread = HandlerThread("remote-stream").apply { start() }
            val callback = object : ProxyFileDescriptorCallback() {
                override fun onGetSize(): Long = stream.target.size

                override fun onRead(offset: Long, size: Int, data: ByteArray): Int = try {
                    runBlocking { withTimeout(READ_TIMEOUT_MILLIS) { stream.read(offset, data, size) } }
                } catch (error: Exception) {
                    MangoLog.warn(MangoLogEvent.REMOTE_STREAM_READ_FAILED, error)
                    throw ErrnoException("read", OsConstants.EIO)
                }

                override fun onRelease() {
                    registry.release(stream)
                    thread.quitSafely()
                    MangoLog.info(MangoLogEvent.REMOTE_STREAM_CLOSED)
                }
            }
            return try {
                context.getSystemService(StorageManager::class.java)
                    .openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, Handler(thread.looper))
                    .also { MangoLog.info(MangoLogEvent.REMOTE_STREAM_OPENED) }
            } catch (error: Exception) {
                registry.release(stream)
                thread.quitSafely()
                MangoLog.warn(MangoLogEvent.REMOTE_STREAM_OPEN_FAILED, error)
                throw FileNotFoundException()
            }
        }

        fun authority(context: Context) = "${context.packageName}.remotestream"

        /** `content://<authority>/<token>/<name>`; the name only helps apps label the file. */
        fun uriFor(context: Context, target: RemoteStreamTarget): Uri = Uri.Builder()
            .scheme("content")
            .authority(authority(context))
            .appendPath(target.token)
            .appendPath(RemoteOpenNames.localName(target.displayName))
            .build()

        /** The URI prefix covering every name under [target]'s token, for revocation. */
        fun tokenUri(context: Context, target: RemoteStreamTarget): Uri = Uri.Builder()
            .scheme("content")
            .authority(authority(context))
            .appendPath(target.token)
            .build()

        private fun tokenOf(uri: Uri): String? = uri.pathSegments.firstOrNull()
    }
}
