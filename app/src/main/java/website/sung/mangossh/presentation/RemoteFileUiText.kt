package website.sung.mangossh.presentation

import website.sung.mangossh.R
import website.sung.mangossh.session.MAX_TRANSFER_TREE_DEPTH
import website.sung.mangossh.session.MAX_TRANSFER_TREE_ENTRIES
import website.sung.mangossh.session.RemoteFileFailure
import website.sung.mangossh.session.RemoteFileMessage

/** Maps sanitized remote-file state onto resource-backed presentation text. */
internal fun RemoteFileMessage.toUiText(): UiText = when (this) {
    is RemoteFileMessage.Failure -> uiText(
        when (reason) {
            RemoteFileFailure.SUBSYSTEM_UNAVAILABLE -> R.string.remote_file_subsystem_unavailable
            RemoteFileFailure.NOT_FOUND -> R.string.remote_file_not_found
            RemoteFileFailure.ACCESS_DENIED -> R.string.remote_file_access_denied
            RemoteFileFailure.NOT_A_FILE -> R.string.remote_file_not_a_file
            RemoteFileFailure.TOO_LARGE -> R.string.remote_file_too_large
            RemoteFileFailure.IO_FAILURE -> R.string.remote_file_io_failure
        },
    )

    RemoteFileMessage.LocalDocumentFailure -> uiText(R.string.remote_file_local_document_failure)
    RemoteFileMessage.TreeTooLarge -> uiText(
        R.string.remote_file_tree_too_large,
        MAX_TRANSFER_TREE_ENTRIES,
        MAX_TRANSFER_TREE_DEPTH,
    )
    RemoteFileMessage.TransferSessionClosed -> uiText(R.string.remote_file_transfer_session_closed)
    RemoteFileMessage.ResumeRestarted -> uiText(R.string.remote_file_resume_restarted)
    is RemoteFileMessage.LinksSkipped -> uiText(R.string.remote_file_tree_links_skipped, count)
    RemoteFileMessage.IoFailure -> uiText(R.string.remote_file_io_failure)
}
