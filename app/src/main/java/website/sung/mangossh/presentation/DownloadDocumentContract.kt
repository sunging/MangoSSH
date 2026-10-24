package website.sung.mangossh.presentation

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import website.sung.mangossh.session.DownloadMimeTypes
import website.sung.mangossh.session.downloadDocumentMimeType

/**
 * Starts downloads in the storage provider's Download folder. Some OEM Downloads
 * providers advertise writable documents but reject every write mode after creation.
 * This is only a picker hint: all access still uses the URI the user grants, and
 * DocumentsUI can fall back when the suggested location is unavailable.
 */
internal class DownloadDocumentContract :
    ActivityResultContracts.CreateDocument(DownloadMimeTypes.UNSPECIFIED) {
    override fun createIntent(context: Context, input: String): Intent =
        super.createIntent(context, input).apply {
            // Resolve each launch separately: one launcher can save different file types.
            type = downloadDocumentMimeType(input)
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", "primary:${Environment.DIRECTORY_DOWNLOADS}",
            ))
        }.preferPlatformDocumentPicker(context)
}
