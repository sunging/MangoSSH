package website.sung.mangossh.session

import android.content.Context
import androidx.core.content.FileProvider
import website.sung.mangossh.R

/**
 * Grants other apps read access to files in the private open cache.
 *
 * A subclass rather than a second `androidx.core.content.FileProvider` entry,
 * because the sideload distribution already declares that class for updates
 * and the manifest merger would collapse the two. Only `cacheDir/remote-open`
 * is exposed; see `res/xml/remote_open_paths.xml`.
 */
class RemoteOpenFileProvider : FileProvider(R.xml.remote_open_paths) {
    internal companion object {
        fun authority(context: Context) = "${context.packageName}.remoteopen"
    }
}
