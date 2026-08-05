package website.sung.mangossh.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import website.sung.mangossh.R
import website.sung.mangossh.session.SessionEndReason
import website.sung.mangossh.session.SessionEndMessageKind
import website.sung.mangossh.session.SessionEndedEvent

class SessionEndMessageTest {
    @Test
    fun keepsOrderlySessionEndsSilent() {
        assertNull(
            resolveSessionEndMessage(
                SessionEndedEvent("user-request", SessionEndReason.USER_REQUEST),
            ),
        )
        assertNull(
            resolveSessionEndMessage(
                SessionEndedEvent("remote-exit", SessionEndReason.REMOTE_EXIT),
            ),
        )
    }

    @Test
    fun retainsFailureMessages() {
        assertEquals(
            uiText(R.string.session_ended_connection_lost),
            resolveSessionEndMessage(
                SessionEndedEvent("lost", SessionEndReason.CONNECTION_LOST),
            ),
        )
        assertEquals(
            uiText(R.string.session_ended_connection_failed),
            resolveSessionEndMessage(
                SessionEndedEvent("failed", SessionEndReason.CONNECTION_FAILED),
            ),
        )
    }

    @Test
    fun preservesSpecificSanitizedFailureMessage() {
        assertEquals(
            uiText(R.string.session_ended_authentication_failed),
            resolveSessionEndMessage(
                SessionEndedEvent(
                    sessionId = "authentication",
                    reason = SessionEndReason.CONNECTION_FAILED,
                    messageKind = SessionEndMessageKind.AUTHENTICATION_FAILED,
                ),
            ),
        )
    }
}
