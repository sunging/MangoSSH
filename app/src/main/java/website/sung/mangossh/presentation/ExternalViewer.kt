package website.sung.mangossh.presentation

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import website.sung.mangossh.R
import website.sung.mangossh.session.LocalDocumentException
import website.sung.mangossh.session.LocalDocumentTree

/**
 * Resolves the actual downloaded directory without interpreting provider IDs as paths.
 *
 * DocumentsUI needs a concrete document reference to rebuild its navigation stack.
 * Canonicalization lets the provider issue that narrower grant instead of stripping
 * the tree prefix ourselves, which would discard the caller's access permission.
 * Unsupported providers retain the original directory document URI.
 */
internal suspend fun prepareDirectoryViewUri(context: Context, directory: Uri): Uri = withContext(Dispatchers.IO) {
    if (directory.scheme != "content") throw LocalDocumentException()
    val documentId = DocumentsContract.getDocumentId(directory)
    val resolver = context.contentResolver
    LocalDocumentTree.queryDocument(resolver, directory, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE)) { cursor ->
        if (!cursor.moveToFirst() || cursor.getString(0) != DocumentsContract.Document.MIME_TYPE_DIR) {
            throw LocalDocumentException()
        }
    }
    val canonical = try {
        resolver.canonicalize(directory)
    } catch (_: UnsupportedOperationException) {
        null
    }
    // Accept only a reference to this same document, never a parent or a broader grant.
    canonical?.takeIf {
        it.scheme == "content" && it.authority == directory.authority &&
            runCatching { DocumentsContract.getDocumentId(it) }.getOrNull() == documentId
    } ?: directory
}

/**
 * Browses a downloaded directory with the system document picker.
 *
 * Some OEM file managers treat ACTION_VIEW directory documents as storage roots.
 * OPEN_DOCUMENT honors EXTRA_INITIAL_URI and also lets the user open a file from
 * that location. Its result is a temporary file grant; no new tree is selected
 * or persisted, and cancelling leaves the download unchanged.
 */
internal class BrowseDownloadedDirectoryContract : ActivityResultContract<Uri, Uri?>() {
    override fun createIntent(context: Context, input: Uri): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType("*/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .putExtra(DocumentsContract.EXTRA_INITIAL_URI, input)
        .putExtra("android.content.extra.SHOW_ADVANCED", true)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.data?.takeIf { resultCode == Activity.RESULT_OK && it.scheme == "content" }
}

/** Validates a folder off the UI thread before launching its lifecycle-owned system browser. */
internal suspend fun launchDirectoryViewIntent(context: Context, directory: Uri, launch: (Uri) -> Unit) {
    val uri = try {
        prepareDirectoryViewUri(context, directory)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        Toast.makeText(context, R.string.remote_file_local_document_failure, Toast.LENGTH_SHORT).show()
        return
    }
    try {
        launch(uri)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.remote_file_no_app_to_open_folder, Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(context, R.string.remote_file_local_document_failure, Toast.LENGTH_SHORT).show()
    }
}

/** Opens a downloaded or picker-selected file without retaining its temporary URI grant. */
internal suspend fun launchDocumentViewIntent(context: Context, uri: Uri) {
    val mimeType = try {
        withContext(Dispatchers.IO) { context.contentResolver.getType(uri) ?: "*/*" }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        Toast.makeText(context, R.string.remote_file_local_document_failure, Toast.LENGTH_SHORT).show()
        return
    }
    launchViewIntent(context, uri, mimeType)
}

/**
 * Asks the user which app should view [uri], granting that app read access
 * for the life of its activity only.
 */
internal fun launchViewIntent(
    context: Context,
    uri: Uri,
    mimeType: String,
) {
    val view = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(view, null))
    } catch (error: ActivityNotFoundException) {
        Toast.makeText(context, R.string.remote_file_no_app_to_open, Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(context, R.string.remote_file_local_document_failure, Toast.LENGTH_SHORT).show()
    }
}
