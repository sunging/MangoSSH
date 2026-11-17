package website.sung.mangossh.data.vault

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import website.sung.mangossh.data.sync.BackupRemoteTransport
import website.sung.mangossh.data.sync.RemoteBackup
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** Exercises the confirmation protocol using real local encryption and a deterministic remote. */
class BackupCoordinatorInstrumentedTest {
    private class Remote : BackupRemoteTransport {
        var head: RemoteBackup? = null
        var old: RemoteBackup? = null
        var archiveFails = false
        var cleanupFails = false
        var timeoutAfterWrite = false
        var writes = 0
        var archives = 0
        var reads = 0
        var probes = 0
        override fun download(config: WebDavConfig): RemoteBackup? {
            reads++
            return head?.let { RemoteBackup(it.bytes.copyOf(), it.etag) }
        }
        override fun history(config: WebDavConfig) = emptyList<BackupHistoryEntry>()
        override fun readHistory(config: WebDavConfig, id: String) = requireNotNull(old).let { RemoteBackup(it.bytes.copyOf(), it.etag) }
        override fun archive(config: WebDavConfig, backup: RemoteBackup) {
            if (archiveFails) throw BackupException(BackupFailure.STORAGE)
            archives++
        }
        override fun publish(config: WebDavConfig, bytes: ByteArray, etag: String?) {
            if (etag != head?.etag) throw BackupException(BackupFailure.CHANGED)
            writes++
            head = RemoteBackup(bytes.copyOf(), "\"$writes\"")
            if (timeoutAfterWrite) throw java.net.SocketTimeoutException()
        }
        override fun verifyConditionalWrites(config: WebDavConfig) { probes++ }
        override fun prune(config: WebDavConfig) { if (cleanupFails) throw BackupException(BackupFailure.PARTIAL) }
    }

    private class Authorization : BackupReauthentication {
        var hold = false
        var epoch = 0L
        var now = 0L
        var approved: (() -> Unit)? = null

        override fun request(onCancelled: () -> Unit, onApproved: () -> Unit): () -> Unit {
            if (!hold) onApproved()
            else approved = onApproved
            return onCancelled
        }
    }

    private class Fixture(
        val context: Context,
        val repository: VaultRepository,
        val local: BackupLocalStore,
        val remote: Remote,
        val coordinator: BackupCoordinator,
        val authorization: Authorization,
    ) {
        suspend fun idle() { withTimeout(10_000) { coordinator.state.first { it.phase == BackupPhase.IDLE } }; delay(30) }
        val state get() = coordinator.state.value
        val password = UUID.randomUUID().toString()
    }

    private fun isolated(block: suspend Fixture.() -> Unit) = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "coordinator-test-${UUID.randomUUID()}").apply { mkdirs() }
        val namespace = "coordinator-test-${UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(directory, "private").apply { mkdirs() }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val local = BackupLocalStore(context, namespace)
        val remote = Remote()
        val repository = VaultRepository(context, AndroidKeystoreVault(context, "$namespace.vault.v1"))
        repository.open()
        repository.changeWebDavConfig(repository.backupSnapshot().first,
            WebDavConfig("https://${UUID.randomUUID()}.invalid", UUID.randomUUID().toString(), UUID.randomUUID().toString())) { true }
        val authorization = Authorization()
        val coordinator = BackupCoordinator(context, repository, scope, { true }, { authorization.epoch }, authorization,
            { "test" }, remote, { local }, { authorization.now })
        try { Fixture(context, repository, local, remote, coordinator, authorization).block() } finally {
            coordinator.cancel()
            scope.coroutineContext[Job]!!.cancelAndJoin()
            directory.deleteRecursively()
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            listOf("history", "passwords", "revisions", "vault").forEach { store.deleteEntry("$namespace.$it.v1") }
        }
    }

    @Test fun unknownRemoteRequiresConfirmationAndArchiveFailurePreservesHead() = isolated {
        val original = PortableVaultCodec.encrypt(VaultSnapshot(), password.toCharArray())
        remote.head = RemoteBackup(original, "\"original\"")
        coordinator.upload(password, false); idle()
        assertTrue(state.remoteConflict)
        assertEquals(0, remote.writes)
        remote.archiveFails = true
        coordinator.confirmUpload(requireNotNull(state.remoteConflictId)); idle()
        assertEquals(0, remote.writes)
        assertArrayEquals(original, remote.head!!.bytes)
        assertEquals(BackupFailure.STORAGE, state.failure)
    }

    @Test fun changedRemoteValidatorIsNotOverwrittenEvenWhenTheCiphertextMatches() = isolated {
        val original = PortableVaultCodec.encrypt(VaultSnapshot(), password.toCharArray())
        remote.head = RemoteBackup(original, "\"a\"")
        coordinator.upload(password, false); idle()
        remote.head = RemoteBackup(original, "\"b\"")
        coordinator.confirmUpload(requireNotNull(state.remoteConflictId)); idle()
        assertEquals(BackupFailure.CHANGED, state.failure)
        assertEquals(0, remote.writes)
        assertEquals(0, remote.archives)
    }

    @Test fun acceptedTimedOutWriteIsVerifiedAndCleanupFailureIsPartialSuccess() = isolated {
        remote.timeoutAfterWrite = true
        remote.cleanupFails = true
        coordinator.upload(password, false); idle()
        assertTrue(state.completed)
        assertEquals(BackupFailure.PARTIAL, state.failure)
        assertEquals(1, remote.writes)
        assertEquals(BackupLocalStore.digest(remote.head!!.bytes), local.revision(BackupLocalStore.target(repository.snapshot.value.webDavConfig)))
    }

    @Test fun oldHistoryPasswordDoesNotReplaceSavedCurrentPassword() = isolated {
        val target = BackupLocalStore.target(repository.snapshot.value.webDavConfig)
        val current = UUID.randomUUID().toString().toCharArray()
        local.remember(target, current)
        remote.old = RemoteBackup(PortableVaultCodec.encrypt(VaultSnapshot(), password.toCharArray()), null)
        coordinator.restore(BackupHistoryEntry(UUID.randomUUID().toString(), 0, true), password); idle()
        assertNotNull(state.preview)
        assertArrayEquals(current, local.password(target))
        assertNull(local.revision(target))
        current.fill('\u0000')
    }

    @Test fun fileExportOnlySucceedsAfterWritingAndDefaultOmitsWebDavCredentials() = isolated {
        coordinator.prepareExport(password, false, false); idle()
        assertTrue(state.exportReady)
        assertFalse(state.completed)
        val file = File(context.filesDir, "backup-test.mssh")
        coordinator.writeExport(Uri.fromFile(file)); idle()
        assertTrue(state.completed)
        assertFalse(state.exportReady)
        assertNull(PortableVaultCodec.decrypt(file.readBytes(), password.toCharArray()).webDavConfig)
        coordinator.prepareExport(password, false, false); idle()
        coordinator.writeExport(Uri.fromFile(context.filesDir)); idle()
        assertEquals(BackupFailure.STORAGE, state.failure)
        assertFalse(state.completed)
        assertFalse(state.exportReady)
    }

    @Test fun staleImportRefreshesPreviewAndCancellationCreatesNoCheckpoint() = isolated {
        val file = File(context.filesDir, "backup-test.mssh").apply { writeBytes(PortableVaultCodec.encrypt(VaultSnapshot(), password.toCharArray())) }
        coordinator.importFile(Uri.fromFile(file), password, false); idle()
        val oldPreview = state.preview!!
        repository.upsertSnippet(CommandSnippet("new", "new", ""))
        coordinator.commit(ImportDecision(operationId = oldPreview.operationId)); idle()
        assertNotEquals(oldPreview.operationId, state.preview!!.operationId)
        assertEquals(BackupFailure.CHANGED, state.failure)
        assertTrue(local.list().isEmpty())
        val refreshed = state.preview!!.operationId
        coordinator.commit(ImportDecision(operationId = oldPreview.operationId)); idle()
        assertEquals(refreshed, state.preview!!.operationId)
        assertTrue(local.list().isEmpty())
        coordinator.cancel()
        assertNull(state.preview)
        assertTrue(local.list().isEmpty())
    }

    @Test fun missingWebDavTargetCannotExposeOrForgetManualPassword() = isolated {
        repository.changeWebDavConfig(repository.backupSnapshot().first, null) { true }
        local.remember("manual", password.toCharArray())
        coordinator.refreshPasswords(); idle()
        assertTrue(state.rememberedManual)
        assertFalse(state.rememberedRemote)
        coordinator.forget(true); idle()
        assertEquals(BackupFailure.INVALID, state.failure)
        assertTrue(local.hasPassword("manual"))
    }

    @Test fun uploadDoesNotReadRememberOrPublishUntilApprovedAndCannotReplay() = isolated {
        val target = BackupLocalStore.target(repository.snapshot.value.webDavConfig)
        authorization.hold = true
        coordinator.upload(password, true)
        val approve = requireNotNull(authorization.approved)
        assertEquals(0, remote.reads)
        assertFalse(local.hasPassword(target))
        approve(); idle()
        assertEquals(1, remote.writes)
        assertTrue(local.hasPassword(target))
        approve(); idle()
        assertEquals(1, remote.writes)
    }

    @Test fun cancelledOrExpiredApprovalCannotChangeDestination() = isolated {
        val original = repository.snapshot.value.webDavConfig
        authorization.hold = true
        coordinator.changeWebDavConfig(null)
        val old = requireNotNull(authorization.approved)
        coordinator.cancel()
        old(); idle()
        assertEquals(original, repository.snapshot.value.webDavConfig)

        coordinator.changeWebDavConfig(null)
        authorization.now += 5 * 60_000L
        requireNotNull(authorization.approved)(); idle()
        assertEquals(BackupFailure.LOCKED, state.failure)
        assertEquals(original, repository.snapshot.value.webDavConfig)
    }

    @Test fun unlockEpochChangeRejectsUploadEvenWhenCurrentlyUnlocked() = isolated {
        authorization.hold = true
        coordinator.upload(password, false)
        authorization.epoch++
        requireNotNull(authorization.approved)(); idle()
        assertEquals(BackupFailure.LOCKED, state.failure)
        assertEquals(0, remote.reads)
        assertEquals(0, remote.probes)
    }

    @Test fun changedVaultInvalidatesApprovedOverwriteAndDiscardsConflict() = isolated {
        remote.head = RemoteBackup(PortableVaultCodec.encrypt(VaultSnapshot(), password.toCharArray()), "\"initial\"")
        coordinator.upload(password, false); idle()
        val conflict = requireNotNull(state.remoteConflictId)
        authorization.hold = true
        coordinator.confirmUpload(conflict)
        repository.upsertSnippet(CommandSnippet("revision", "revision", ""))
        requireNotNull(authorization.approved)(); idle()
        assertEquals(BackupFailure.CHANGED, state.failure)
        assertFalse(state.remoteConflict)
        assertEquals(0, remote.probes)
        assertEquals(0, remote.writes)
    }

    @Test fun restoringDestinationRequiresApprovalBeforeCheckpointOrVaultMutation() = isolated {
        val original = requireNotNull(repository.snapshot.value.webDavConfig)
        val incoming = original.copy(endpoint = "https://${UUID.randomUUID()}.invalid")
        val file = File(context.filesDir, "backup-test.mssh").apply {
            writeBytes(PortableVaultCodec.encrypt(VaultSnapshot(webDavConfig = incoming), password.toCharArray()))
        }
        coordinator.importFile(Uri.fromFile(file), password, false); idle()
        authorization.hold = true
        coordinator.commit(ImportDecision(restoreWebDav = true, operationId = requireNotNull(state.preview).operationId))
        assertTrue(local.list().isEmpty())
        assertEquals(original, repository.snapshot.value.webDavConfig)
        requireNotNull(authorization.approved)(); idle()
        assertEquals(incoming, repository.snapshot.value.webDavConfig)
    }
}
