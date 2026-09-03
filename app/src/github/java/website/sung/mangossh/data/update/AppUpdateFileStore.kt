package website.sung.mangossh.data.update

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Owns the app-private cache location used to stage a downloaded update APK.
 *
 * The APK lives in `cacheDir/updates/`, never external storage: this needs no
 * permissions, is disposable under storage pressure, and — critically — no
 * other app can tamper with the file between digest verification and the
 * install handoff, which closes what would otherwise be a TOCTOU hole.
 *
 * A download is written to a `.part` file and only [promote]d to its final
 * name after its digest is verified, so an unverified download is never
 * reachable through [contentUri].
 */
internal class AppUpdateFileStore(context: Context) {
    private val appContext = context.applicationContext
    private val updatesDir = File(appContext.cacheDir, "updates")

    fun partFile(tag: String): File {
        updatesDir.mkdirs()
        return File(updatesDir, "MangoSSH-$tag.apk.part")
    }

    fun verifiedFile(tag: String): File = File(updatesDir, "MangoSSH-$tag.apk")

    /** Renames a completed, digest-verified [part] to its final name, or `null` on failure. */
    fun promote(part: File, tag: String): File? {
        val target = verifiedFile(tag)
        target.delete()
        return if (part.renameTo(target)) target else null
    }

    /** Deletes every file in the updates directory except the named verified one. */
    fun purgeExcept(tag: String?) {
        val keep = tag?.let { verifiedFile(it) }
        updatesDir.listFiles()?.forEach { file ->
            if (file != keep) file.delete()
        }
    }

    /** A short-lived, read-only content URI for [file], scoped to the updates subdirectory. */
    fun contentUri(file: File): Uri =
        FileProvider.getUriForFile(appContext, "${appContext.packageName}.updates", file)
}
