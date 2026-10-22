package website.sung.mangossh.presentation

import website.sung.mangossh.R
import website.sung.mangossh.session.MAX_TRANSFER_TREE_DEPTH
import website.sung.mangossh.session.MAX_TRANSFER_TREE_ENTRIES
import website.sung.mangossh.session.RemoteFileFailure
import website.sung.mangossh.session.RemoteFileMessage

/** Maps sanitized remote-file state onto resource-backed presentation text. */
internal fun RemoteFileMessage.toUiText(): UiText = when (this) {
    is RemoteFileMessage.OperationFailure -> uiText(when (reason) {
        website.sung.mangossh.session.RemoteOperationFailure.INVALID_NAME -> R.string.fm_invalid_name
        website.sung.mangossh.session.RemoteOperationFailure.ROOT -> R.string.fm_root_protected
        website.sung.mangossh.session.RemoteOperationFailure.EXISTS -> R.string.fm_exists
        website.sung.mangossh.session.RemoteOperationFailure.PLATFORM_REQUIRED -> R.string.fm_platform_detail
        website.sung.mangossh.session.RemoteOperationFailure.WINDOWS_PERMISSIONS -> R.string.fm_windows_permissions
        website.sung.mangossh.session.RemoteOperationFailure.UNSUPPORTED -> R.string.fm_unsupported
        website.sung.mangossh.session.RemoteOperationFailure.MOVE_FAILED -> R.string.fm_move_failed
        website.sung.mangossh.session.RemoteOperationFailure.CASE_ONLY -> R.string.fm_case_only
        website.sung.mangossh.session.RemoteOperationFailure.INVALID_DESTINATION -> R.string.fm_invalid_destination
    })
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
    RemoteFileMessage.LocalDocumentWriteFailure -> uiText(R.string.remote_file_local_document_write_failure)
    RemoteFileMessage.TreeTooLarge -> uiText(
        R.string.remote_file_tree_too_large,
        MAX_TRANSFER_TREE_ENTRIES,
        MAX_TRANSFER_TREE_DEPTH,
    )
    RemoteFileMessage.TransferSessionClosed -> uiText(R.string.remote_file_transfer_session_closed)
    RemoteFileMessage.ResumeRestarted -> uiText(R.string.remote_file_resume_restarted)
    is RemoteFileMessage.LinksSkipped -> uiPluralText(R.plurals.remote_file_tree_links_skipped, count, count)
    RemoteFileMessage.SourceChanged -> uiText(R.string.remote_file_source_changed)
    RemoteFileMessage.MetadataFailure -> uiText(R.string.transfer_metadata_failed)
    RemoteFileMessage.CommitFailure -> uiText(R.string.transfer_commit_failed)
    is RemoteFileMessage.InsufficientSpace -> uiText(R.string.transfer_space_failed, requiredBytes)
    is RemoteFileMessage.OpenSpaceShortage -> uiText(R.string.remote_open_space_failed, requiredBytes)
    RemoteFileMessage.IoFailure -> uiText(R.string.remote_file_io_failure)
}
