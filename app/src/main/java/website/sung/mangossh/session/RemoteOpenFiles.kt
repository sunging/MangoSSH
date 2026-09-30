package website.sung.mangossh.session

import android.net.Uri
import java.io.File
import java.util.Locale
import java.util.UUID

/** A remote file ready to hand to another app with `ACTION_VIEW`. */
data class RemoteLaunchRequest(val uri: Uri, val mimeType: String)

/**
 * Naming and typing rules for remote files handed to other apps.
 *
 * Remote names are untrusted: they only ever become the last component of a
 * private cache path or a cosmetic content URI segment, never a lookup key.
 */
internal object RemoteOpenNames {
    const val FALLBACK_MIME_TYPE = "application/octet-stream"
    private const val FALLBACK_NAME = "file"
    private const val MAX_NAME_CHARS = 120

    /** Guesses a MIME type from the extension; [lookup] is usually `MimeTypeMap`. */
    fun mimeType(name: String, lookup: (String) -> String?): String {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension.isEmpty()) return FALLBACK_MIME_TYPE
        return lookup(extension) ?: COMMON_TYPES[extension] ?: FALLBACK_MIME_TYPE
    }

    /** True for types players stream, where tapping the file should stream it. */
    fun isStreamable(mimeType: String): Boolean = mimeType.startsWith("video/") || mimeType.startsWith("audio/")

    /**
     * Reduces [name] to one safe local path component: no separators, control
     * characters, or dot-only names, and short enough for any file system.
     */
    fun localName(name: String): String {
        val cleaned = name.substringAfterLast('/').substringAfterLast('\\')
            .filterNot { it.isISOControl() || it in "<>:\"|?*" }
            .trim()
        val shortened = if (cleaned.length <= MAX_NAME_CHARS) cleaned else {
            val extension = cleaned.substringAfterLast('.', "").take(16)
            val keep = MAX_NAME_CHARS - extension.length - 1
            if (extension.isEmpty() || keep <= 0) cleaned.take(MAX_NAME_CHARS) else cleaned.take(keep) + "." + extension
        }
        return shortened.takeUnless { it.isEmpty() || it.all { char -> char == '.' } } ?: FALLBACK_NAME
    }

    /** Covers formats some devices' `MimeTypeMap` misses. */
    private val COMMON_TYPES = mapOf(
        "mkv" to "video/x-matroska",
        "webm" to "video/webm",
        "ts" to "video/mp2t",
        "m2ts" to "video/mp2t",
        "flac" to "audio/flac",
        "opus" to "audio/ogg",
        "m4a" to "audio/mp4",
        "mp4" to "video/mp4",
        "mp3" to "audio/mpeg",
        "pdf" to "application/pdf",
    )
}

/** The MIME type other apps are told for a remote file called [name]. */
internal fun remoteMimeType(name: String): String = RemoteOpenNames.mimeType(name) { extension ->
    android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
}

/**
 * Private cache of whole files downloaded for another app to open.
 *
 * Each download gets its own random directory, so two remote files with the
 * same name never collide and a URI cannot be guessed. Entries are pruned by
 * age: the receiving app may still be reading an entry, and a file it already
 * opened stays readable after deletion anyway.
 */
internal class RemoteOpenCache(
    private val root: File,
    private val availableBytes: () -> Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Creates the destination for a [size]-byte download, or fails when storage is short. */
    fun newFile(name: String, size: Long?): File {
        val needed = (size ?: 0L) + RESERVE_BYTES
        val available = availableBytes()
        if (needed > available) throw OpenCacheSpaceException(needed - available)
        val directory = File(root, UUID.randomUUID().toString())
        if (!directory.mkdirs()) throw java.io.IOException()
        return File(directory, RemoteOpenNames.localName(name))
    }

    /** Deletes a download that failed or was cancelled. */
    fun discard(file: File) {
        file.parentFile?.takeIf { it.parentFile == root }?.deleteRecursively()
    }

    /** Removes entries older than [maxAgeMillis]. */
    fun prune(maxAgeMillis: Long) {
        val cutoff = now() - maxAgeMillis
        root.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
    }

    companion object {
        /** Path of the cache under `cacheDir`; must match `res/xml/remote_open_paths.xml`. */
        const val DIRECTORY = "remote-open"

        /** Free space left for the rest of the system after a download. */
        const val RESERVE_BYTES = 64L * 1024 * 1024
    }
}

/** Too little storage to download a file for opening; carries only a byte count. */
internal class OpenCacheSpaceException(val requiredBytes: Long) : java.io.IOException()
