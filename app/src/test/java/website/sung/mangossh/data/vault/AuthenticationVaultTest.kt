package website.sung.mangossh.data.vault

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import website.sung.mangossh.domain.*

/** Synthetic secrets are generated at runtime; no test contains reusable production credentials. */
class AuthenticationVaultTest {
    private fun authentication() = StoredAuthentication(UUID.randomUUID().toString(), "Example", " ${UUID.randomUUID()}\t ", createdAtEpochMillis = 123)
    private fun host(id: String? = null) = ConnectionProfile(id = "host", label = "Example", hostname = "synthetic.invalid", username = "test", authenticationId = id)
    private fun key(id: String = "key") = StoredSshKey(id, id, "ssh-ed25519", UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), createdAtEpochMillis = 123)

    @Test fun schemaEightRoundTripsAllCredentialCombinationsAndRedactsSecrets() {
        val key = key()
        val original = authentication()
        val records = listOf(original, original.copy(id = "both", keyId = key.id), original.copy(id = "key", password = null, keyId = key.id), original.copy(id = "empty", password = null))
        val snapshot = VaultSnapshot(keys = listOf(key), authentications = records, profiles = records.mapIndexed { index, record -> host(record.id).copy(id = "host$index") })
        assertEquals(snapshot, VaultPayloadCodec.decode(VaultPayloadCodec.encode(snapshot)))
        assertEquals(8, snapshot.schemaVersion)
        val encoded = JSONObject(VaultPayloadCodec.encode(snapshot).decodeToString())
        assertTrue(encoded.has("authentications")); assertFalse(encoded.has("passwords"))
        assertFalse(encoded.getJSONArray("profiles").getJSONObject(0).has("keyId"))
        assertFalse(original.toString().contains(original.password!!))
        assertEquals(AuthenticationSummary(original.id, original.label, hasPassword = true), original.summary())
    }

    @Test fun everyReleasedLegacySchemaMigratesToEightWithStableKeyReferences() {
        for (version in 1..7) {
            val key = key()
            val root = JSONObject(VaultPayloadCodec.encode(VaultSnapshot(keys = listOf(key), profiles = listOf(host(), host().copy(id = "other")))).decodeToString())
            root.put("schemaVersion", version).remove("authentications")
            val profiles = root.getJSONArray("profiles")
            repeat(profiles.length()) { profiles.getJSONObject(it).put("authentication", "PASSWORD").put("keyId", key.id).remove("authenticationId") }
            val first = VaultPayloadCodec.decode(root.toString().encodeToByteArray())
            val second = VaultPayloadCodec.decode(root.toString().encodeToByteArray())
            assertEquals(first, second)
            assertEquals(8, first.schemaVersion)
            assertEquals(key.id, first.authentications.single().keyId)
            assertEquals(listOf(first.authentications.single().id, first.authentications.single().id), first.profiles.map { it.authenticationId })
            assertTrue(first.profiles.all { it.authentication == AuthenticationMethod.AUTOMATIC })
            assertNull(first.authentications.single().password)
            assertEquals(first, VaultPayloadCodec.decode(VaultPayloadCodec.encode(first)))
        }
    }

    @Test fun legacyDisplaySortingDoesNotCrossCredentialReferencesAndUnusedKeysRemain() {
        val keys = listOf(key("one"), key("two"), key("unused"))
        val profiles = listOf(host().copy(id = "one", label = "Z"), host().copy(id = "two", label = "A"))
        val root = JSONObject(VaultPayloadCodec.encode(VaultSnapshot(keys = keys, profiles = profiles)).decodeToString()).put("schemaVersion", 4)
        root.remove("authentications")
        repeat(2) { root.getJSONArray("profiles").getJSONObject(it).put("keyId", keys[it].id).put("authentication", "PRIVATE_KEY") }
        val result = VaultPayloadCodec.decode(root.toString().encodeToByteArray())
        assertEquals(listOf("two", "one"), result.profiles.map { it.id })
        result.profiles.forEach { host -> assertEquals(host.id, result.authentications.single { it.id == host.authenticationId }.keyId) }
        assertEquals(3, result.authentications.size)
    }

    @Test fun unpublishedSplitRecordsPreserveBothReferencesWithoutAnotherSchemaVersion() {
        val key = key()
        val password = authentication()
        val root = JSONObject(VaultPayloadCodec.encode(VaultSnapshot(keys = listOf(key), profiles = listOf(host()))).decodeToString())
        root.remove("authentications")
        root.put("passwords", JSONArray().put(JSONObject().put("id", password.id).put("label", password.label).put("password", password.password).put("createdAtEpochMillis", 123)))
        root.getJSONArray("profiles").getJSONObject(0).put("keyId", key.id).put("passwordId", password.id)
        val result = VaultPayloadCodec.decode(root.toString().encodeToByteArray())
        assertEquals(8, result.schemaVersion)
        assertEquals(key.id, result.authentications.single().keyId)
        assertEquals(password.password, result.authentications.single().password)
    }

    @Test fun blankReplacementPreservesLatestPasswordAndExplicitRemovalClearsIt() {
        val original = authentication()
        val snapshot = VaultSnapshot(authentications = listOf(original))
        val replaced = snapshot.withEditedAuthentication(original.id, "Renamed", UUID.randomUUID().toString(), false, null)!!
        val renamed = replaced.withEditedAuthentication(original.id, "Again", null, false, null)!!
        assertEquals(replaced.authentications.single().password, renamed.authentications.single().password)
        val cleared = renamed.withEditedAuthentication(original.id, "Again", null, true, null)!!
        assertNull(cleared.authentications.single().password)
        assertNull(cleared.withRemovedAuthentication(original.id).withEditedAuthentication(original.id, "Gone", null, false, null))
    }

    @Test fun sharedKeyDeletionRetainsOtherOwnersThenClearsAgentReferencesAtLastOwner() {
        val key = key()
        val one = authentication().copy(keyId = key.id)
        val two = authentication().copy(keyId = key.id)
        val host = host(one.id).copy(agentPolicy = HostAgentPolicy(allowedKeyIds = listOf(key.id)))
        val snapshot = VaultSnapshot(keys = listOf(key), authentications = listOf(one, two), profiles = listOf(host))
        val retained = snapshot.withRemovedAuthentication(one.id)
        assertNull(retained.profiles.single().authenticationId)
        assertEquals(listOf(key), retained.keys)
        assertEquals(listOf(key.id), retained.profiles.single().agentPolicy.allowedKeyIds)
        val removed = retained.withRemovedAuthentication(two.id)
        assertTrue(removed.keys.isEmpty()); assertTrue(removed.profiles.single().agentPolicy.allowedKeyIds!!.isEmpty())
        BackupValidator.validate(removed)
    }

    @Test fun stagedKeyCommitIsAtomicAndRejectsChangedOrDeletedKeys() {
        val key = key()
        val record = authentication().copy(keyId = key.id)
        val snapshot = VaultSnapshot(keys = listOf(key), authentications = listOf(record))
        val edited = key.copy(label = "edited")
        val change = StagedKeyChange(edited, key)
        assertEquals(edited, snapshot.withEditedAuthentication(record.id, "Renamed", null, false, key.id, change)!!.keys.single())
        assertNull(snapshot.copy(keys = emptyList()).withEditedAuthentication(record.id, "Renamed", null, false, key.id, change))
        assertNull(snapshot.copy(keys = listOf(key.copy(label = "concurrent"))).withStagedKey(key.id, change))
        val generated = key("generated")
        assertEquals(generated, snapshot.withStagedKey(generated.id, StagedKeyChange(generated))!!.keys.last())
        assertNull(snapshot.withStagedKey(key.id, StagedKeyChange(key)))
    }

    @Test fun rememberedPasswordCreatesACopyPreservingKeyAndSharedEntry() {
        val key = key()
        val original = authentication().copy(keyId = key.id)
        val new = authentication().copy(keyId = key.id)
        val host = host(original.id)
        val other = host.copy(id = "other")
        val snapshot = VaultSnapshot(keys = listOf(key), authentications = listOf(original), profiles = listOf(host.copy(connectionCount = 4, favorite = true), other))
        val result = snapshot.withRememberedAuthentication(host, host, original, new, key)!!
        assertEquals(listOf(original, new), result.authentications)
        assertEquals(new.id, result.profiles.first().authenticationId)
        assertEquals(4, result.profiles.first().connectionCount)
        assertEquals(other, result.profiles.last())
        BackupValidator.validate(result)
    }

    @Test fun concurrentHostCredentialOrKeyEditsAndDeletionRejectLateSavesEntirely() {
        val key = key()
        val original = authentication().copy(keyId = key.id)
        val host = host(original.id)
        val new = authentication().copy(keyId = key.id)
        val snapshot = VaultSnapshot(keys = listOf(key), authentications = listOf(original), profiles = listOf(host))
        val changed = listOf(snapshot.copy(profiles = emptyList()), snapshot.copy(profiles = listOf(host.copy(label = "changed"))),
            snapshot.copy(profiles = listOf(host.copy(authenticationId = null))), snapshot.copy(authentications = emptyList()),
            snapshot.copy(authentications = listOf(original.copy(password = UUID.randomUUID().toString()))),
            snapshot.copy(keys = listOf(key.copy(privateKeyPem = UUID.randomUUID().toString()))), snapshot.copy(keys = emptyList()))
        changed.forEach { assertNull(it.withRememberedAuthentication(host, host, original, new, key)) }
    }

    @Test fun quickConnectCreatesOnlyAnEntryAndCannotAttachToANewlyCreatedHost() {
        val record = authentication()
        val host = host()
        val result = VaultSnapshot().withRememberedAuthentication(host, null, null, record)!!
        assertTrue(result.profiles.isEmpty()); assertEquals(listOf(record), result.authentications)
        assertNull(VaultSnapshot(profiles = listOf(host)).withRememberedAuthentication(host, null, null, record))
    }

    @Test fun malformedRecordsAndDanglingReferencesAreRejected() {
        val record = authentication()
        listOf(VaultSnapshot(authentications = listOf(record, record)), VaultSnapshot(authentications = listOf(record.copy(label = ""))),
            VaultSnapshot(authentications = listOf(record.copy(password = ""))), VaultSnapshot(authentications = listOf(record.copy(keyId = "missing"))),
            VaultSnapshot(authentications = listOf(record.copy(createdAtEpochMillis = -1))), VaultSnapshot(profiles = listOf(host(record.id)))).forEach {
            assertThrows(BackupException::class.java) { BackupValidator.validate(it) }
        }
        val root = JSONObject(VaultPayloadCodec.encode(VaultSnapshot(authentications = listOf(record))).decodeToString())
        root.getJSONArray("authentications").getJSONObject(0).put("password", true)
        assertThrows(IllegalArgumentException::class.java) { VaultPayloadCodec.decode(root.toString().encodeToByteArray()) }
    }

    @Test fun encryptedBackupsAndConflictPreviewsPreserveCredentialsWithoutExposingSecrets() {
        val key = key()
        val record = authentication().copy(keyId = key.id)
        val local = VaultSnapshot(keys = listOf(key), authentications = listOf(record), profiles = listOf(host(record.id)))
        val secret = UUID.randomUUID().toString().toCharArray()
        val encrypted = PortableVaultCodec.encrypt(local, secret)
        try { assertEquals(local, PortableVaultCodec.decrypt(encrypted, secret)); assertFalse(encrypted.toString(Charsets.ISO_8859_1).contains(record.password!!))
        } finally { secret.fill('\u0000'); encrypted.fill(0) }
        val replacement = record.copy(password = UUID.randomUUID().toString())
        val remote = local.copy(authentications = listOf(replacement))
        val preview = BackupMerger.preview(local, BackupArchive(remote, null), 1)
        assertEquals("authentication", preview.conflicts.single().kind)
        assertFalse(preview.toString().contains(record.password))
        assertEquals(local, BackupMerger.merge(local, remote, ImportDecision()))
        assertEquals(remote, BackupMerger.merge(local, remote, ImportDecision(useIncoming = setOf(preview.conflicts.single().token))))
        assertEquals(remote, BackupMerger.merge(local, remote, ImportDecision(replace = true)))
    }
}
