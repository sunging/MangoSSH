package website.sung.mangossh.data.vault

import org.junit.Assert.*
import org.junit.Test
import website.sung.mangossh.domain.ConnectionProfile
import java.util.UUID

class BackupMergerTest {
    private fun profile() = UUID.randomUUID().toString().let { ConnectionProfile(id = it, label = it, hostname = "$it.invalid", username = it) }

    @Test fun repeatedImportIsIdempotentAndDifferentIdsAreNotFolded() {
        val original = profile()
        val incoming = VaultSnapshot(profiles = listOf(original, original.copy(id = UUID.randomUUID().toString())))
        val first = BackupMerger.merge(VaultSnapshot(), incoming, ImportDecision())
        assertEquals(2, first.profiles.size)
        assertEquals(first, BackupMerger.merge(first, incoming, ImportDecision()))
        assertEquals(2, BackupMerger.preview(first, BackupArchive(incoming, null), 1).unchanged)
    }

    @Test fun conflictChoicePreservesOrderAndUsage() {
        val first = profile().copy(position = 1, connectionCount = 10, lastConnectedAtEpochMillis = 9)
        val second = profile().copy(position = 0)
        val appended = profile().copy(position = 0)
        val local = VaultSnapshot(profiles = listOf(first, second))
        val remote = VaultSnapshot(profiles = listOf(first.copy(label = UUID.randomUUID().toString(), position = 0, connectionCount = 50), appended))
        val preview = BackupMerger.preview(local, BackupArchive(remote, null), 4)
        assertEquals(1, preview.conflicts.size)
        val keep = BackupMerger.merge(local, remote, ImportDecision())
        assertEquals(first.label, keep.profiles.single { it.id == first.id }.label)
        val use = BackupMerger.merge(local, remote, ImportDecision(useIncoming = setOf(preview.conflicts.single().token)))
        assertEquals(listOf(second.id, first.id, appended.id), use.profiles.map { it.id })
        assertEquals(remote.profiles.first().label, use.profiles[1].label)
        assertEquals(10, use.profiles[1].connectionCount)
        assertEquals(9L, use.profiles[1].lastConnectedAtEpochMillis)
    }

    @Test fun replacementDeletesLocalOnlyRecordsAndRestoresUsage() {
        val local = VaultSnapshot(profiles = listOf(profile()))
        val remote = VaultSnapshot(profiles = listOf(profile().copy(connectionCount = 7)))
        assertEquals(1, BackupMerger.preview(local, BackupArchive(remote, null), 0).removedByReplacement)
        assertEquals(remote, BackupMerger.merge(local, remote, ImportDecision(replace = true)))
    }

    @Test fun trustReplacementStillRequiresIndividualChoice() {
        val bytes = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
        val trust = TrustedHostKey(UUID.randomUUID().toString(), 22, UUID.randomUUID().toString(), java.util.Base64.getEncoder().encodeToString(bytes), "")
        val other = trust.copy(keyBlobBase64 = java.util.Base64.getEncoder().encodeToString(bytes.reversedArray()))
        val local = VaultSnapshot(knownHosts = listOf(trust))
        val incoming = VaultSnapshot(knownHosts = listOf(other))
        val preview = BackupMerger.preview(local, BackupArchive(incoming, null), 0)
        assertTrue(preview.conflicts.single().trust)
        assertEquals(trust, BackupMerger.merge(local, incoming, ImportDecision(replace = true)).knownHosts.single())
        assertEquals(other, BackupMerger.merge(local, incoming, ImportDecision(useIncoming = setOf("trust:0"))).knownHosts.single())
        assertFalse(preview.toString().contains(trust.keyBlobBase64))
    }

    @Test fun duplicatesDanglingReferencesAndInvalidPortsAreRejected() {
        val profile = profile()
        listOf(
            VaultSnapshot(profiles = listOf(profile, profile)),
            VaultSnapshot(profiles = listOf(profile.copy(keyId = UUID.randomUUID().toString()))),
            VaultSnapshot(profiles = listOf(profile.copy(startupSnippetId = UUID.randomUUID().toString()))),
            VaultSnapshot(profiles = listOf(profile.copy(port = 0))),
            VaultSnapshot(portForwards = listOf(PortForwardRule(UUID.randomUUID().toString(), profile.id, PortForwardType.DYNAMIC, bindPort = 1000))),
        ).forEach { assertThrows(BackupException::class.java) { BackupValidator.validate(it) } }
    }

    @Test fun configRequiresExplicitChoiceEvenForReplacement() {
        fun config() = WebDavConfig("https://${UUID.randomUUID()}.invalid", UUID.randomUUID().toString(), UUID.randomUUID().toString())
        val local = VaultSnapshot(webDavConfig = config())
        val remote = VaultSnapshot(webDavConfig = config())
        assertEquals(local.webDavConfig, BackupMerger.merge(local, remote, ImportDecision(replace = true)).webDavConfig)
        assertEquals(remote.webDavConfig, BackupMerger.merge(local, remote, ImportDecision(restoreWebDav = true)).webDavConfig)
    }
}
