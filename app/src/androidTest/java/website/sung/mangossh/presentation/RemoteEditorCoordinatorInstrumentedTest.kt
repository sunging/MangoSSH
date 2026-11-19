package website.sung.mangossh.presentation

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import website.sung.mangossh.data.drafts.RemoteDraft
import website.sung.mangossh.data.drafts.RemoteDraftStore
import website.sung.mangossh.session.EditableRemoteText
import website.sung.mangossh.session.MAX_REMOTE_PREVIEW_BYTES
import website.sung.mangossh.session.RemoteTarget
import website.sung.mangossh.session.SourceIdentity

/** Uses an isolated cache directory and an in-memory key, with no app data or session changes. */
class RemoteEditorCoordinatorInstrumentedTest {
    private fun source(path: String, text: String = "fixture"): EditableRemoteText {
        val identity = SourceIdentity(path, text.length.toLong(), 1000)
        return EditableRemoteText(path, text, false, "\n", identity,
            RemoteTarget(identity, 0x8180, true), ByteArray(32))
    }

    private fun fixture(block: suspend CoroutineScope.(File, RemoteDraftStore, AtomicBoolean) -> Unit) = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val directory = File(base, "draft-editor-test-${UUID.randomUUID()}").apply { check(mkdir()) }
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val fail = AtomicBoolean()
        val store = RemoteDraftStore(directory) { if (fail.get()) throw IOException() else key }
        try {
            withTimeout(10_000) { block(directory, store, fail) }
        } finally { directory.deleteRecursively() }
    }

    private fun CoroutineScope.editor(store: RemoteDraftStore, read: suspend (String, String) -> EditableRemoteText) =
        RemoteEditorCoordinator(this, store, read, { _, _, _, _, _ -> error("Unexpected save") },
            canOpen = { true }, onOpenFailed = { error("Unexpected open failure") }, onSaved = {})

    @Test fun lateReadCannotReplaceTheNewSessionAndPath() = fixture { _, store, _ ->
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        val editor = editor(store) { _, path ->
            if (path == "/old") withContext(NonCancellable) {
                started.complete(Unit)
                finish.await()
                returned.complete(Unit)
            }
            source(path)
        }
        editor.open("old", "/old", "profile")
        try {
            started.await()
            editor.open("new", "/new", "profile")
            val opened = editor.state.first { it?.source?.path == "/new" }!!
            finish.complete(Unit)
            returned.await()
            assertEquals("new", opened.identity.sessionId)
            assertEquals(opened.identity, editor.state.value?.identity)
        } finally { finish.complete(Unit) }
    }

    @Test fun failedReloadLeavesCurrentTextAndCiphertextUntilSuccessfulReload() = fixture { _, store, _ ->
        val reloading = CompletableDeferred<Unit>()
        val failRead = CompletableDeferred<Unit>()
        var reads = 0
        val editor = editor(store) { _, path ->
            when (reads++) {
                0 -> source(path)
                1 -> { reloading.complete(Unit); failRead.await(); throw IOException() }
                else -> source(path, "new remote")
            }
        }
        editor.open("session", "/file", "profile")
        val initial = editor.state.first { it != null }!!
        editor.change("pending content")
        editor.flushDraft()
        editor.state.first { it?.draftPersistence == DraftPersistence.STORED }
        editor.reload()
        reloading.await()
        assertTrue(editor.state.value!!.busy)
        assertEquals("pending content", editor.state.value!!.draft)
        assertEquals("pending content", store.load("profile", "/file")!!.text)
        failRead.complete(Unit)
        val failed = editor.state.first { it?.failed == true }!!
        assertEquals(initial.identity, failed.identity)
        assertEquals("pending content", failed.draft)
        editor.reload()
        val refreshed = editor.state.first { it?.source?.text == "new remote" }!!
        assertNotEquals(initial.identity, refreshed.identity)
        assertEquals("new remote", refreshed.draft)
        assertNull(store.load("profile", "/file"))
    }

    @Test fun failedDraftWriteKeepsPriorTimestampAndCiphertextUntilRetry() = fixture { directory, store, failKey ->
        val editor = editor(store) { _, path -> source(path) }
        editor.open("session", "/file", "profile")
        editor.state.first { it != null }
        editor.change("first draft")
        editor.flushDraft()
        val saved = editor.state.first { it?.draftPersistence == DraftPersistence.STORED }!!
        val ciphertext = directory.listFiles()!!.single()
        val previous = ciphertext.readBytes()
        failKey.set(true)
        editor.change("second draft")
        assertEquals(DraftPersistence.PENDING, editor.state.value!!.draftPersistence)
        editor.onLocked()
        val failed = editor.state.first { it?.draftPersistence == DraftPersistence.FAILED }!!
        assertEquals("second draft", failed.draft)
        assertEquals(saved.lastDraftSavedAt, failed.lastDraftSavedAt)
        assertArrayEquals(previous, ciphertext.readBytes())
        editor.close(discardConfirmed = false, keepSavedDraft = true)
        assertEquals(failed, editor.state.value)
        failKey.set(false)
        editor.flushDraft()
        editor.state.first { it?.draftPersistence == DraftPersistence.STORED }
        assertEquals("second draft", store.load("profile", "/file")!!.text)
        editor.close(discardConfirmed = false, keepSavedDraft = true)
        assertNull(editor.state.value)
    }

    @Test fun oversizeLegacyDraftCanBeRecoveredAndShortenedWithoutTruncation() = fixture { _, store, _ ->
        val legacy = "a".repeat(256 * 1024)
        store.save(RemoteDraft("profile", "/file", legacy, ByteArray(32), 1_000))
        val editor = editor(store) { _, path -> source(path) }
        editor.open("session", "/file", "profile")
        editor.state.first { it?.recovered != null }
        editor.restore()
        assertEquals(legacy, editor.state.value!!.draft)
        assertFalse(editor.state.value!!.canSave)
        editor.change(legacy + "b")
        assertTrue(editor.state.value!!.inputRejected)
        assertEquals(legacy, editor.state.value!!.draft)
        editor.change(legacy.dropLast(1))
        assertFalse(editor.state.value!!.inputRejected)
        assertEquals(legacy.length - 1, editor.state.value!!.draft.length)
        assertFalse(editor.state.value!!.canSave)
        editor.change(legacy.take(MAX_REMOTE_PREVIEW_BYTES))
        assertTrue(editor.state.value!!.canSave)
        editor.onDisconnect("session")
        val stored = editor.state.first { it?.draftPersistence == DraftPersistence.STORED }!!
        assertTrue(stored.disconnected)
        assertEquals(MAX_REMOTE_PREVIEW_BYTES, store.load("profile", "/file")!!.text.length)
    }
}
