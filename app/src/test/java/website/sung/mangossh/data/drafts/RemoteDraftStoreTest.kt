package website.sung.mangossh.data.drafts

import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteDraftStoreTest {
    private val directory: File = Files.createTempDirectory("drafts").toFile()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val store = RemoteDraftStore(directory) { key }

    @After fun cleanUp() { directory.deleteRecursively() }

    private fun draft(path: String = "/etc/app.conf", text: String = "port = 22\n") =
        RemoteDraft("profile", path, text, ByteArray(32) { 1 }, 1_000)

    @Test fun aSavedDraftRoundTripsAndIsNotStoredInPlainText() {
        store.save(draft())
        val loaded = store.load("profile", "/etc/app.conf")!!
        assertEquals("port = 22\n", loaded.text)
        assertArrayEquals(ByteArray(32) { 1 }, loaded.baseDigest)
        val raw = directory.listFiles()!!.single()
        assertFalse(raw.name.contains("app.conf"))
        assertFalse(String(raw.readBytes(), Charsets.ISO_8859_1).contains("port = 22"))
    }

    @Test fun aDraftMovedOntoAnotherSlotIsRejected() {
        store.save(draft(path = "/a"))
        store.save(draft(path = "/b", text = "other"))
        val (first, second) = directory.listFiles()!!.sortedBy { it.name }
        first.copyTo(second, overwrite = true)
        // Associated data binds each ciphertext to its own file name.
        val loadedA = store.load("profile", "/a")
        val loadedB = store.load("profile", "/b")
        assertEquals(1, listOfNotNull(loadedA, loadedB).size)
    }

    @Test fun deleteAndClearRemoveDrafts() {
        store.save(draft(path = "/a"))
        store.save(draft(path = "/b"))
        store.delete("profile", "/a")
        assertNull(store.load("profile", "/a"))
        assertEquals(1, store.count())
        store.clear()
        assertEquals(0, store.count())
    }

    @Test fun deletingAProfileRemovesOnlyItsDrafts() {
        store.save(draft(path = "/a"))
        store.save(RemoteDraft("other", "/a", "kept", ByteArray(32), 1_000))
        store.deleteProfile("profile")
        assertNull(store.load("profile", "/a"))
        assertEquals("kept", store.load("other", "/a")!!.text)
    }

    @Test fun onlyTheNewestDraftsAreKept() {
        repeat(RemoteDraftStore.MAX_DRAFTS + 3) { index -> store.save(draft(path = "/file$index")) }
        assertEquals(RemoteDraftStore.MAX_DRAFTS, store.count())
    }

    @Test fun aCorruptDraftIsDiscarded() {
        store.save(draft())
        directory.listFiles()!!.single().writeBytes(ByteArray(64))
        assertNull(store.load("profile", "/etc/app.conf"))
        assertEquals(0, store.count())
    }
}
