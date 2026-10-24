package website.sung.mangossh.session

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadMimeTypesTest {
    private val types = mapOf(
        "txt" to "text/plain", "pdf" to "application/pdf", "jpg" to "image/jpeg",
        "zip" to "application/zip", "gz" to "application/gzip", "ini" to "text/plain",
        "bin" to "application/octet-stream",
    )

    @Test fun recognizedExtensionsUseTheDevicesType() {
        for ((name, type) in mapOf(
            "notes.txt" to "text/plain", "report.PDF" to "application/pdf",
            "photo.jpg" to "image/jpeg", "archive.zip" to "application/zip",
            "archive.tar.gz" to "application/gzip", "two words.txt" to "text/plain",
            ".config.txt" to "text/plain", "\u4e2d\u6587.pdf" to "application/pdf",
        )) assertEquals(name, type, DownloadMimeTypes.forName(name, types::get))
    }

    @Test fun unknownBinaryAndExtensionlessNamesDisableExtensionCompletion() {
        for (name in listOf("README", ".profile", ".z", ".Z", "unknown.mangossh-unknown", "payload.bin", "trailing.", "")) {
            assertEquals(name, "*/*", DownloadMimeTypes.forName(name, types::get))
        }
        assertEquals("*/*", DownloadMimeTypes.forName("unknown.txt") { "" })
        assertEquals("*/*", DownloadMimeTypes.forName("unknown.mkv") { null })
    }

    @Test fun dotfilesAndNamesWithoutExtensionsDoNotConsultTheMap() {
        for (name in listOf("README", ".pdf", ".z", ".Z", "trailing.")) {
            assertEquals("*/*", DownloadMimeTypes.forName(name) { error("No extension to look up") })
        }
    }

    @Test fun lookupIsIndependentOfTheUsersLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("text/plain", DownloadMimeTypes.forName("SETTINGS.INI", types::get))
        } finally { Locale.setDefault(original) }
    }
}
