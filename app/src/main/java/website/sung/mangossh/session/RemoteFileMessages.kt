package website.sung.mangossh.session

/**
 * Sanitized application-owned category for a remote file or transfer failure.
 *
 * Server-supplied error text never reaches this model. Presentation resolves
 * the category against the current locale only when it is displayed.
 */
sealed interface RemoteFileMessage {
    data class Failure(val reason: RemoteFileFailure) : RemoteFileMessage
    data object LocalDocumentFailure : RemoteFileMessage
    data object TreeTooLarge : RemoteFileMessage
    data object TransferSessionClosed : RemoteFileMessage
    data object ResumeRestarted : RemoteFileMessage
    data class LinksSkipped(val count: Int) : RemoteFileMessage
    data object IoFailure : RemoteFileMessage
}

/** Maps a throwable to a category without retaining library or server text. */
internal fun Throwable.toRemoteFileMessage(): RemoteFileMessage = when (this) {
    is RemoteFileException -> RemoteFileMessage.Failure(failure)
    is LocalDocumentException -> RemoteFileMessage.LocalDocumentFailure
    is TransferTreeTooLargeException -> RemoteFileMessage.TreeTooLarge
    else -> RemoteFileMessage.IoFailure
}

/** Signals that a directory transfer exceeded the entry or depth cap. */
class TransferTreeTooLargeException : Exception()
