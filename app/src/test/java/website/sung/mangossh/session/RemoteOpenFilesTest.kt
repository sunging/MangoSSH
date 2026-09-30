package website.sung.mangossh.session

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import website.sung.mangossh.data.settings.StreamingPreferencesStore

class RemoteOpenFilesTest {
    private val root: File = Files.createTempDirectory("remote-open").toFile()

    @After fun tearDown() {
        root.deleteRecursively()
    }

    @Test fun mimeTypesPreferThePlatformThenKnownMediaThenOctetStream() {
        assertEquals("video/x-matroska", RemoteOpenNames.mimeType("Movie.MKV") { null })
        assertEquals("video/custom", RemoteOpenNames.mimeType("movie.mkv") { if (it == "mkv") "video/custom" else null })
        assertEquals(RemoteOpenNames.FALLBACK_MIME_TYPE, RemoteOpenNames.mimeType("README") { "text/plain" })
        assertEquals(RemoteOpenNames.FALLBACK_MIME_TYPE, RemoteOpenNames.mimeType("archive.xyz") { null })
    }

    @Test fun onlyAudioAndVideoStreamOnTap() {
        assertTrue(RemoteOpenNames.isStreamable("video/mp4"))
        assertTrue(RemoteOpenNames.isStreamable("audio/flac"))
        assertFalse(RemoteOpenNames.isStreamable("application/pdf"))
    }

    @Test fun localNamesCannotEscapeTheirDirectory() {
        assertEquals("passwd", RemoteOpenNames.localName("../../etc/passwd"))
        assertEquals("b.txt", RemoteOpenNames.localName("a\\b.txt"))
        assertEquals("bad.txt", RemoteOpenNames.localName("b\u0000a\nd.txt"))
        assertEquals("file", RemoteOpenNames.localName(".."))
        assertEquals("file", RemoteOpenNames.localName("  "))
        val long = RemoteOpenNames.localName("x".repeat(300) + ".mp4")
        assertTrue(long.length <= 120)
        assertTrue(long.endsWith(".mp4"))
    }

    @Test fun eachDownloadGetsItsOwnDirectory() {
        val cache = RemoteOpenCache(root, availableBytes = { Long.MAX_VALUE })
        val first = cache.newFile("a.pdf", 10)
        val second = cache.newFile("a.pdf", 10)
        assertEquals("a.pdf", first.name)
        assertNotEquals(first.parentFile, second.parentFile)
        assertEquals(root, first.parentFile?.parentFile)
    }

    @Test fun aDownloadThatDoesNotFitFailsWithTheShortfall() {
        val cache = RemoteOpenCache(root, availableBytes = { RemoteOpenCache.RESERVE_BYTES + 100 })
        try {
            cache.newFile("big.iso", 1_000)
            fail("expected a space failure")
        } catch (error: OpenCacheSpaceException) {
            assertEquals(900L, error.requiredBytes)
        }
    }

    @Test fun pruningRemovesOnlyOldEntriesAndDiscardStaysInsideTheCache() {
        var now = 10_000_000L
        val cache = RemoteOpenCache(root, availableBytes = { Long.MAX_VALUE }, now = { now })
        val old = cache.newFile("old", 1).apply { writeText("x") }
        old.parentFile!!.setLastModified(now - 5_000)
        val fresh = cache.newFile("fresh", 1).apply { writeText("x") }
        fresh.parentFile!!.setLastModified(now)
        cache.prune(maxAgeMillis = 1_000)
        assertFalse(old.exists())
        assertTrue(fresh.exists())

        val outside = File(root.parentFile, "outside-${System.nanoTime()}").apply { mkdirs() }
        cache.discard(File(outside, "x"))
        assertTrue(outside.exists())
        outside.deleteRecursively()
        cache.discard(fresh)
        assertFalse(fresh.parentFile!!.exists())
    }

    @Test fun streamingCacheChoicesKeepAnOffGridStoredValue() {
        assertEquals(StreamingPreferencesStore.CACHE_LIMIT_CHOICES_MIB, StreamingPreferencesStore.cacheLimitChoices(64))
        assertTrue(48 in StreamingPreferencesStore.cacheLimitChoices(48))
        assertEquals(StreamingPreferencesStore.DEFAULT_CACHE_LIMIT_MIB, StreamingPreferencesStore.normalizeCacheLimit(8))
        assertEquals(StreamingPreferencesStore.DEFAULT_CACHE_LIMIT_MIB, StreamingPreferencesStore.normalizeCacheLimit(4096))
        assertEquals(512, StreamingPreferencesStore.normalizeCacheLimit(512))
    }
}
