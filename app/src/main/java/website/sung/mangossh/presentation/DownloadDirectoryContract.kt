package website.sung.mangossh.presentation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Shows device storage when choosing a folder download's destination.
 *
 * Older DocumentsUI versions can hide internal storage and leave only Downloads
 * visible. SHOW_ADVANCED is a best-effort DocumentsUI compatibility hint, not a
 * permission grant. The initial location is also only a hint: the system picker
 * still controls which locations can be browsed and which tree URI it grants.
 */
internal class DownloadDirectoryContract : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent =
        super.createIntent(context, input ?: DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents", "primary:",
        )).apply {
            putExtra("android.content.extra.SHOW_ADVANCED", true)
        }
}
