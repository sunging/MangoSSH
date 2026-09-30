package website.sung.mangossh.presentation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import website.sung.mangossh.R

/**
 * Asks the user which app should view [uri], granting that app read access
 * for the life of its activity only.
 */
internal fun launchViewIntent(context: Context, uri: Uri, mimeType: String) {
    val view = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(view, null))
    } catch (error: ActivityNotFoundException) {
        Toast.makeText(context, R.string.remote_file_no_app_to_open, Toast.LENGTH_SHORT).show()
    }
}
