package website.sung.mangossh.session

import android.webkit.MimeTypeMap
import java.util.Locale

/** MIME rules for creating downloads without asking the provider to change their suffixes. */
internal object DownloadMimeTypes {
    const val UNSPECIFIED = "*/*"

    /**
     * Uses the device's extension map so its picker can match the original suffix to the type.
     * A wildcard deliberately disables automatic extension completion for unknown and binary
     * types; declaring every file as octet-stream makes some pickers append `.bin` instead.
     * The supplied name is never rewritten, and this policy is not used for opening files.
     */
    fun forName(name: String, lookup: (String) -> String?): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.lastIndex) return UNSPECIFIED
        val extension = name.substring(dot + 1).lowercase(Locale.ROOT)
        return lookup(extension)?.takeUnless { it.isBlank() || it == "application/octet-stream" }
            ?: UNSPECIFIED
    }
}

/** Returns the type to send to a document-creation picker or provider for [name]. */
internal fun downloadDocumentMimeType(name: String): String = DownloadMimeTypes.forName(name) {
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(it)
}
