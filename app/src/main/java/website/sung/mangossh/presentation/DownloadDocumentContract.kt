package website.sung.mangossh.presentation

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Starts downloads in the storage provider's Download folder. Some OEM Downloads
 * providers advertise writable documents but reject every write mode after creation.
 * This is only a picker hint: all access still uses the URI the user grants, and
 * DocumentsUI can fall back when the suggested location is unavailable.
 */
internal class DownloadDocumentContract(mimeType: String = "application/octet-stream") :
    ActivityResultContracts.CreateDocument(mimeType) {
    override fun createIntent(context: Context, input: String): Intent =
        super.createIntent(context, input).apply {
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", "primary:${Environment.DIRECTORY_DOWNLOADS}",
            ))
        }
}
