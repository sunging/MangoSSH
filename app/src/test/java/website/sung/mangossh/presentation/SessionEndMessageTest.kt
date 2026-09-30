package website.sung.mangossh.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun authenticationFailuresUseDistinctLocalizedMessages() {
        for ((kind, resource) in mapOf(
            SessionEndMessageKind.AUTHENTICATION_METHOD_UNAVAILABLE to R.string.session_ended_authentication_method_unavailable,
            SessionEndMessageKind.AUTHENTICATION_KEY_FAILED to R.string.session_ended_authentication_key_failed,
            SessionEndMessageKind.AUTHENTICATION_PROTOCOL_FAILED to R.string.session_ended_authentication_protocol_failed,
        )) {
            assertEquals(uiText(resource), resolveSessionEndMessage(
                SessionEndedEvent("authentication", SessionEndReason.CONNECTION_FAILED, messageKind = kind)))
        }
    }

    @Test
    fun onlyOrderlySessionEndsLeaveTheTerminal() {
        assertTrue(SessionEndedEvent("exit", SessionEndReason.REMOTE_EXIT).leavesTerminal())
        assertTrue(SessionEndedEvent("user", SessionEndReason.USER_REQUEST).leavesTerminal())
        assertFalse(SessionEndedEvent("lost", SessionEndReason.CONNECTION_LOST).leavesTerminal())
        assertFalse(SessionEndedEvent("failed", SessionEndReason.CONNECTION_FAILED).leavesTerminal())
        assertFalse(
            SessionEndedEvent(
                "mosh",
                SessionEndReason.REMOTE_EXIT,
                messageKind = SessionEndMessageKind.MOSH_BOOTSTRAP_FAILED,
            ).leavesTerminal(),
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
