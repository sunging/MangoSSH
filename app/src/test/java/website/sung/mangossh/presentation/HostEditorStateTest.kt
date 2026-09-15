package website.sung.mangossh.presentation

import org.junit.Assert.*
import org.junit.Test
import website.sung.mangossh.domain.*

/** Pure draft checks prevent hidden invalid fields and metadata loss during the UI restructure. */
class HostEditorStateTest {
    private fun host() = ConnectionProfile(id = "test", label = "test", hostname = "synthetic.invalid", username = "test",
        authentication = AuthenticationMethod.PASSWORD, favorite = true, position = 8, connectionCount = 12, lastConnectedAtEpochMillis = 123)

    @Test fun editingRetainsEveryExistingProfileField() {
        val source = host().copy(agentForwarding = true, agentPolicy = HostAgentPolicy(listOf("key"), true, 60),
            requireReauthentication = true, overrides = HostConnectionOverrides(20, 60, 8, SshTerminalType.VT100),
            workspace = TmuxWorkspace(WorkspaceMode.CREATE_OR_ATTACH, "work"), jumpProfileIds = listOf("jump"))
        assertEquals(source.copy(label = "changed"), HostEditorDraft.from(source).copy(label = "changed").toProfileDraft().toProfile())
    }

    @Test fun hiddenMissingReferencesIdentifyTheirOwnPages() {
        val draft = HostEditorDraft.from(host()).copy(authentication = AuthenticationMethod.PRIVATE_KEY, keyId = "missing",
            jumpIds = listOf("missing"), startupSnippetId = "missing", agentPolicy = HostAgentPolicy(listOf("missing")), overrides = HostConnectionOverrides(1))
        assertEquals(setOf(HostEditorPage.CONNECTION, HostEditorPage.JUMPS, HostEditorPage.STARTUP, HostEditorPage.SECURITY, HostEditorPage.ADVANCED),
            draft.invalidPages(emptySet(), emptySet(), emptyList()))
    }

    @Test fun tailnetDoesNotRequireAnUnusedLoginKeyAndDefaultsRemainInherited() {
        val draft = HostEditorDraft.from(host()).copy(route = ConnectionRoute.TAILNET, authentication = AuthenticationMethod.PRIVATE_KEY)
        assertTrue(draft.invalidPages(emptySet(), emptySet(), emptyList()).isEmpty())
        assertEquals(HostConnectionOverrides(), draft.toProfileDraft().overrides)
        assertEquals(AuthenticationMethod.TAILSCALE_SSH, draft.toProfileDraft().toProfile().authentication)
    }

    @Test fun moshAndStartupConflictsCannotBeSaved() {
        val draft = HostEditorDraft.from(host()).copy(protocol = ConnectionProtocol.MOSH, jumpIds = listOf("jump"),
            workspace = TmuxWorkspace(WorkspaceMode.CREATE_OR_ATTACH, "work"), startupSnippetId = "snippet")
        assertEquals(setOf(HostEditorPage.JUMPS, HostEditorPage.STARTUP), draft.invalidPages(emptySet(), setOf("snippet"), listOf(host().copy(id = "jump"))))
    }

    @Test fun backFromDetailsRetainsDraftAndOnlyLeavingRequestsDiscard() {
        val controller = HostEditorController(HostEditorDraft.from(host()))
        controller.draft = controller.draft.copy(label = "changed")
        controller.page = HostEditorPage.SECURITY
        controller.back { fail("A detail must not close the editor") }
        assertEquals(HostEditorPage.MAIN, controller.page)
        assertFalse(controller.confirmDiscard)
        controller.back { fail("Unsaved changes must not close the editor") }
        assertTrue(controller.confirmDiscard)
        assertEquals("changed", controller.draft.label)
    }
}
