package website.sung.mangossh.session

import android.content.Context
import website.sung.mangossh.R

/**
 * Fixed application wording for a file-operation failure, or null when the
 * failure is not one this mapping recognizes.
 *
 * Server-supplied error text never reaches the UI: [RemoteFileException] only
 * carries a category, and every category maps to a string resource here.
 */
internal fun Context.remoteFileMessageOrNull(error: Throwable): String? = when (error) {
    is RemoteFileException -> getString(
        when (error.failure) {
            RemoteFileFailure.SUBSYSTEM_UNAVAILABLE -> R.string.remote_file_subsystem_unavailable
            RemoteFileFailure.NOT_FOUND -> R.string.remote_file_not_found
            RemoteFileFailure.ACCESS_DENIED -> R.string.remote_file_access_denied
            RemoteFileFailure.NOT_A_FILE -> R.string.remote_file_not_a_file
            RemoteFileFailure.TOO_LARGE -> R.string.remote_file_too_large
            RemoteFileFailure.IO_FAILURE -> R.string.remote_file_io_failure
        },
    )

    is LocalDocumentException -> getString(R.string.remote_file_local_document_failure)
    is TransferTreeTooLargeException -> getString(
        R.string.remote_file_tree_too_large,
        MAX_TRANSFER_TREE_ENTRIES,
        MAX_TRANSFER_TREE_DEPTH,
    )

    else -> null
}

/** Fixed application wording for a failure raised by a file transfer. */
internal fun Context.remoteFileMessage(error: Throwable): String =
    remoteFileMessageOrNull(error) ?: getString(R.string.remote_file_io_failure)

/** Signals that a directory transfer exceeded the entry or depth cap. */
class TransferTreeTooLargeException : Exception()
