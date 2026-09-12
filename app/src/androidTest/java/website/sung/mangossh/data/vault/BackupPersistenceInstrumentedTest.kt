package website.sung.mangossh.data.vault

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** Real Keystore and AtomicFile checks use isolated directories and test-owned key aliases. */
class BackupPersistenceInstrumentedTest {
    private fun isolated(block: (Context, String) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "backup-test-${UUID.randomUUID()}").apply { mkdirs() }
        val namespace = "backup-test-${UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(directory, "private").apply { mkdirs() }
        }
        try { block(context, namespace) } finally {
            directory.deleteRecursively()
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            listOf("history", "passwords", "revisions").forEach { keys.deleteEntry("$namespace.$it.v1") }
        }
    }

    @Test fun recoveryDoesNotNeedPasswordAndSurvivesRestartWithTenVersionLimit() = isolated { context, namespace ->
        val store = BackupLocalStore(context, namespace)
        val ids = mutableListOf<String>()
        repeat(12) { index ->
            val snapshot = VaultSnapshot(snippets = listOf(CommandSnippet(index.toString(), index.toString(), "")))
            ids += store.checkpoint(snapshot).id
            store.prune()
        }
        val reopened = BackupLocalStore(context, namespace)
        assertEquals(ids.takeLast(10).reversed(), reopened.list().map { it.id })
        assertEquals("11", reopened.readHistory(ids.last()).snapshot.snippets.single().id)
    }

    @Test fun passwordsAreTargetScopedExcludedFromHistoryAndCanBeForgotten() = isolated { context, namespace ->
        val store = BackupLocalStore(context, namespace)
        val password = UUID.randomUUID().toString().toCharArray()
        try {
            store.remember("manual", password)
            assertNull(store.password("other"))
            assertArrayEquals(password, BackupLocalStore(context, namespace).password("manual"))
            val entry = store.checkpoint(VaultSnapshot())
            assertEquals(VaultSnapshot(), store.readHistory(entry.id).snapshot)
            val rawPassword = String(password).encodeToByteArray()
            val files = File(context.noBackupFilesDir, "backup-state").walkTopDown().filter { it.isFile }.toList()
            assertTrue(files.none { it.readBytes().toList().windowed(rawPassword.size).any { window -> window == rawPassword.toList() } })
            store.forget("manual")
            assertNull(store.password("manual"))
        } finally { password.fill('\u0000') }
    }

    @Test fun invalidatedPasswordKeyDoesNotPreventHistoryRecovery() = isolated { context, namespace ->
        val store = BackupLocalStore(context, namespace)
        store.remember("manual", UUID.randomUUID().toString().toCharArray())
        val entry = store.checkpoint(VaultSnapshot())
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("$namespace.passwords.v1") }
        assertThrows(Exception::class.java) { store.password("manual") }
        assertEquals(VaultSnapshot(), store.readHistory(entry.id).snapshot)
        assertTrue(store.hasPassword("manual"))
    }

    @Test fun stalePreviewAndLockedCommitDoNotWriteRecoveryOrVault() = isolated { context, namespace -> runBlocking {
        val repository = VaultRepository(context)
        repository.open()
        val revision = repository.backupSnapshot().first
        repository.upsertSnippet(CommandSnippet("one", "one", ""))
        val local = BackupLocalStore(context, namespace)
        assertFalse(repository.commitImport(revision, VaultSnapshot(), ImportDecision(replace = true), local) { true })
        assertTrue(local.list().isEmpty())
        try {
            repository.commitImport(repository.backupSnapshot().first, VaultSnapshot(), ImportDecision(), local) { false }
            fail("Locked commit must fail")
        } catch (error: BackupException) { assertEquals(BackupFailure.LOCKED, error.reason) }
        assertEquals(1, repository.snapshot.value.snippets.size)
    } }

    @Test fun recoveryFailurePreservesOriginalVault() = isolated { context, namespace -> runBlocking {
        val repository = VaultRepository(context)
        repository.open()
        repository.upsertSnippet(CommandSnippet("original", "original", ""))
        File(context.noBackupFilesDir, "backup-state").writeText("")
        try {
            repository.commitImport(repository.backupSnapshot().first, VaultSnapshot(), ImportDecision(replace = true), BackupLocalStore(context, namespace)) { true }
            fail("Recovery write must fail")
        } catch (_: Exception) { }
        assertEquals("original", repository.snapshot.value.snippets.single().id)
        assertEquals("original", AndroidKeystoreVault(context).read()!!.snippets.single().id)
    } }

    @Test fun successfulImportCreatesRestorablePreviousSnapshot() = isolated { context, namespace -> runBlocking {
        val repository = VaultRepository(context)
        repository.open()
        repository.upsertSnippet(CommandSnippet("original", "original", ""))
        val local = BackupLocalStore(context, namespace)
        assertTrue(repository.commitImport(repository.backupSnapshot().first, VaultSnapshot(), ImportDecision(replace = true), local) { true })
        assertTrue(repository.snapshot.value.snippets.isEmpty())
        val previous = local.readHistory(local.list().single().id).snapshot
        assertTrue(repository.commitImport(repository.backupSnapshot().first, previous, ImportDecision(replace = true), local) { true })
        assertEquals("original", repository.snapshot.value.snippets.single().id)
        assertEquals(2, local.list().size)
    } }

    @Test fun mainVaultWriteFailureKeepsOriginalAndRecoveryPoint() = isolated { context, namespace -> runBlocking {
        val repository = VaultRepository(context)
        repository.open()
        repository.upsertSnippet(CommandSnippet("original", "original", ""))
        val local = BackupLocalStore(context, namespace)
        File(context.filesDir, "mangossh-vault.bin.new").mkdir()
        try {
            repository.commitImport(repository.backupSnapshot().first, VaultSnapshot(), ImportDecision(replace = true), local) { true }
            fail("Atomic write must fail")
        } catch (_: Exception) { }
        assertEquals("original", repository.snapshot.value.snippets.single().id)
        assertEquals("original", AndroidKeystoreVault(context).read()!!.snippets.single().id)
        assertEquals("original", local.readHistory(local.list().single().id).snapshot.snippets.single().id)
    } }
}
