package website.sung.mangossh.data.vault

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import website.sung.mangossh.domain.*

class HostPolicyCompatibilityTest {
    private fun host(id: String) = ConnectionProfile(id = id, label = id, hostname = "synthetic.invalid", username = "test", authentication = AuthenticationMethod.PASSWORD)

    @Test fun newFieldsRoundTripAndOldFieldsInherit() {
        val jump = host("jump")
        val profile = host("target").copy(
            overrides = HostConnectionOverrides(20, 60, 8, SshTerminalType.VT100),
            agentPolicy = HostAgentPolicy(emptyList(), true, 30),
            requireReauthentication = true,
            workspace = TmuxWorkspace(WorkspaceMode.CREATE, "synthetic"),
            jumpProfileIds = listOf(jump.id),
        )
        val snapshot = VaultSnapshot(profiles = listOf(jump, profile))
        assertEquals(snapshot, VaultPayloadCodec.decode(VaultPayloadCodec.encode(snapshot)))
        val old = JSONObject(VaultPayloadCodec.encode(VaultSnapshot(profiles = listOf(host("old")))).decodeToString())
        old.put("schemaVersion", 5)
        val record = old.getJSONArray("profiles").getJSONObject(0)
        listOf("overrides", "agentPolicy", "requireReauthentication", "workspace", "jumpProfileIds").forEach(record::remove)
        val migrated = VaultPayloadCodec.decode(old.toString().encodeToByteArray()).profiles.single()
        assertEquals(HostConnectionOverrides(), migrated.overrides)
        assertNull(migrated.agentPolicy.allowedKeyIds)
        assertEquals(WorkspaceMode.DISABLED, migrated.workspace.mode)
    }

    @Test fun createOrAttachRoundTripsWithoutChangingExistingModes() {
        WorkspaceMode.entries.forEach { mode ->
            val workspace = TmuxWorkspace(mode, name = "synthetic", sessionId = "\$1")
            val snapshot = VaultSnapshot(profiles = listOf(host("test").copy(workspace = workspace)))
            assertEquals(workspace, VaultPayloadCodec.decode(VaultPayloadCodec.encode(snapshot)).profiles.single().workspace)
        }
        assertFalse(TmuxWorkspace(WorkspaceMode.CREATE_OR_ATTACH, name = "unsafe;name").isValid())
    }

    @Test fun nestedMissingAndCyclicJumpsAreRejected() {
        val a = host("a")
        val b = host("b")
        assertThrows(BackupException::class.java) { BackupValidator.validate(VaultSnapshot(profiles = listOf(a.copy(jumpProfileIds = listOf("missing"))))) }
        assertThrows(BackupException::class.java) { BackupValidator.validate(VaultSnapshot(profiles = listOf(a.copy(jumpProfileIds = listOf("b")), b.copy(jumpProfileIds = listOf("a"))))) }
        assertThrows(BackupException::class.java) { BackupValidator.validate(VaultSnapshot(profiles = listOf(a.copy(protocol = ConnectionProtocol.MOSH, jumpProfileIds = listOf("b")), b))) }
    }
}
