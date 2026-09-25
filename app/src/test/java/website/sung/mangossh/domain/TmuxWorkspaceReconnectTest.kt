package website.sung.mangossh.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TmuxWorkspaceReconnectTest {
    @Test fun noWorkspaceMeansAFreshShell() {
        assertNull(TmuxWorkspace().reconnectTarget("\$3"))
    }

    @Test fun aCreatedWorkspaceIsReattachedById() {
        assertEquals(
            TmuxWorkspace(WorkspaceMode.ATTACH, sessionId = "\$3"),
            TmuxWorkspace(WorkspaceMode.CREATE, name = "work").reconnectTarget("\$3"),
        )
    }

    @Test fun aCreateThatNeverResolvedIsRetriedAsConfigured() {
        val create = TmuxWorkspace(WorkspaceMode.CREATE, name = "work")
        assertEquals(create, create.reconnectTarget(null))
        assertEquals(create, create.reconnectTarget("not-an-id"))
    }

    @Test fun namedReuseAndAttachAreKept() {
        val named = TmuxWorkspace(WorkspaceMode.CREATE_OR_ATTACH, name = "work")
        val attach = TmuxWorkspace(WorkspaceMode.ATTACH, sessionId = "\$1")
        assertEquals(named, named.reconnectTarget("\$9"))
        assertEquals(attach, attach.reconnectTarget("\$1"))
    }
}
