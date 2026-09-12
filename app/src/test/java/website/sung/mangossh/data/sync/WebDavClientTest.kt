package website.sung.mangossh.data.sync

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import website.sung.mangossh.data.vault.*
import java.util.UUID

class WebDavClientTest {
    private fun config(server: MockWebServer) = WebDavConfig(server.url("/vault").toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString())
    private fun bytes() = ByteArray(64).also(java.security.SecureRandom()::nextBytes)

    @Test fun getPreservesEtagAndCreateUsesIfNoneMatch() {
        MockWebServer().use { server ->
            val data = bytes()
            server.enqueue(MockResponse().setBody(Buffer().write(data)).setHeader("ETag", "\"one\""))
            server.enqueue(MockResponse().setResponseCode(201))
            val client = WebDavClient(allowHttpForTests = true)
            val config = config(server)
            val downloaded = client.download(config)!!
            assertArrayEquals(data, downloaded.bytes)
            assertEquals("\"one\"", downloaded.etag)
            client.publish(config, data, null)
            assertEquals("GET", server.takeRequest().method)
            val put = server.takeRequest()
            assertEquals("*", put.getHeader("If-None-Match"))
            assertArrayEquals(data, put.body.readByteArray())
        }
    }

    @Test fun archiveIsImmutableAndConditionalUpdatePropagates412() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(201))
            server.enqueue(MockResponse().setResponseCode(201))
            server.enqueue(MockResponse().setResponseCode(412))
            val client = WebDavClient(allowHttpForTests = true)
            val config = config(server)
            val data = bytes()
            client.archive(config, RemoteBackup(data, "\"old\""))
            assertEquals("MKCOL", server.takeRequest().method)
            val archive = server.takeRequest()
            assertTrue(archive.path!!.contains(".mangossh-vault.mssh.history/mssh-"))
            assertEquals("*", archive.getHeader("If-None-Match"))
            assertArrayEquals(data, archive.body.readByteArray())
            assertEquals(BackupFailure.CHANGED, assertThrows(BackupException::class.java) { client.publish(config, bytes(), "\"old\"") }.reason)
            assertEquals("\"old\"", server.takeRequest().getHeader("If-Match"))
        }
    }

    @Test fun weakValidatorsAndUnsafePathsAreRejectedWithoutRequests() {
        MockWebServer().use { server ->
            val client = WebDavClient(allowHttpForTests = true)
            assertEquals(BackupFailure.UNSAFE_SERVER, assertThrows(BackupException::class.java) { client.publish(config(server), bytes(), "W/\"one\"") }.reason)
            for (path in listOf("../other", "%2e%2e/other", "file?x", "file\\x")) assertThrows(IllegalArgumentException::class.java) { client.validate(config(server).copy(remoteFileName = path)) }
            assertThrows(IllegalArgumentException::class.java) { WebDavClient().validate(config(server)) }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun malformedAndExternalHistoryEntriesCannotEscapeDirectory() {
        MockWebServer().use { server ->
            val directory = server.url("/history/")
            val valid = "mssh-123-${UUID.randomUUID()}.mssh"
            val hrefs = listOf("/history/$valid", "https://outside.invalid/history/$valid", "/other/$valid", "/history/nested/$valid", "/history/unrelated")
            val xml = "<d:multistatus xmlns:d=\"DAV:\">" + hrefs.joinToString("") { "<d:response><d:href>$it</d:href></d:response>" } + "</d:multistatus>"
            val client = WebDavClient(allowHttpForTests = true)
            assertEquals(listOf(valid), client.parseHistory(xml.encodeToByteArray(), directory).map { it.id })
            assertThrows(IllegalArgumentException::class.java) { client.parseHistory("<!DOCTYPE x [<!ENTITY y SYSTEM 'file:///invalid'>]><x/>".encodeToByteArray(), directory) }
            assertThrows(IllegalArgumentException::class.java) { client.parseHistory(ByteArray(1024 * 1024 + 1), directory) }
        }
    }

    @Test fun archiveFailureDoesNotPublishAnything() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            assertThrows(BackupException::class.java) { WebDavClient(allowHttpForTests = true).archive(config(server), RemoteBackup(bytes(), "\"one\"")) }
            assertEquals(1, server.requestCount)
            assertEquals("MKCOL", server.takeRequest().method)
        }
    }

    @Test fun retentionDeletesOnlyTheOldestRecognizedVersions() {
        MockWebServer().use { server ->
            val entries = (1..12).map { "mssh-$it-${UUID.randomUUID()}.mssh" }
            val xml = "<d:multistatus xmlns:d=\"DAV:\">" + entries.joinToString("") { "<d:response><d:href>/vault/.mangossh-vault.mssh.history/$it</d:href></d:response>" } + "</d:multistatus>"
            server.enqueue(MockResponse().setResponseCode(207).setBody(xml))
            repeat(2) { server.enqueue(MockResponse().setResponseCode(204)) }
            WebDavClient(allowHttpForTests = true).prune(config(server))
            val listing = server.takeRequest()
            assertEquals("1", listing.getHeader("Depth"))
            assertEquals("application/xml; charset=utf-8", listing.getHeader("Content-Type"))
            assertTrue(server.takeRequest().path!!.endsWith(entries[1]))
            assertTrue(server.takeRequest().path!!.endsWith(entries[0]))
        }
    }

    @Test fun boundedReadRejectsUnknownLengthOverflow() {
        assertThrows(BackupException::class.java) { WebDavClient.readLimited(ByteArray(9).inputStream(), 8) }
        assertEquals(8, WebDavClient.readLimited(ByteArray(8).inputStream(), 8).size)
    }

    @Test fun serverIgnoringConditionsIsRejectedUsingOnlyDisposableProbe() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(201))
            server.enqueue(MockResponse().setResponseCode(201))
            server.enqueue(MockResponse().setResponseCode(200))
            server.enqueue(MockResponse().setResponseCode(204))
            val error = assertThrows(BackupException::class.java) { WebDavClient(allowHttpForTests = true).verifyConditionalWrites(config(server)) }
            assertEquals(BackupFailure.UNSAFE_SERVER, error.reason)
            server.takeRequest()
            repeat(3) { assertTrue(server.takeRequest().path!!.contains("/.probe-")) }
        }
    }
}
