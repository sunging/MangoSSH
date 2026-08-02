package website.sung.mangossh.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import website.sung.mangossh.R
import website.sung.mangossh.session.SessionEndReason
import website.sung.mangossh.session.SessionEndedEvent

class SessionEndMessageTest {
    @Test
    fun keepsOrderlySessionEndsSilent() {
        val getString: (Int) -> String = { "unexpected message" }

        assertNull(
            resolveSessionEndMessage(
                SessionEndedEvent("user-request", SessionEndReason.USER_REQUEST),
                getString,
            ),
        )
        assertNull(
            resolveSessionEndMessage(
                SessionEndedEvent("remote-exit", SessionEndReason.REMOTE_EXIT),
                getString,
            ),
        )
    }

    @Test
    fun retainsFailureMessages() {
        val messages = mapOf(
            R.string.session_ended_connection_lost to "connection lost",
            R.string.session_ended_connection_failed to "connection failed",
        )

        assertEquals(
            "connection lost",
            resolveSessionEndMessage(
                SessionEndedEvent("lost", SessionEndReason.CONNECTION_LOST),
                messages::getValue,
            ),
        )
        assertEquals(
            "connection failed",
            resolveSessionEndMessage(
                SessionEndedEvent("failed", SessionEndReason.CONNECTION_FAILED),
                messages::getValue,
            ),
        )
    }

    @Test
    fun preservesSpecificSanitizedFailureMessage() {
        assertEquals(
            "Authentication failed.",
            resolveSessionEndMessage(
                SessionEndedEvent(
                    sessionId = "authentication",
                    reason = SessionEndReason.CONNECTION_FAILED,
                    userMessage = "Authentication failed.",
                ),
                getString = { "generic failure" },
            ),
        )
    }
}
