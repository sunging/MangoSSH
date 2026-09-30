package website.sung.mangossh.core

import java.security.NoSuchAlgorithmException
import org.connectbot.sshlib.ConnectResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import website.sung.mangossh.session.ssh.SshFailure
import website.sung.mangossh.session.ssh.failureDetail

class MangoLogTest {
    @Test
    fun namesTheCauseChainWithoutMessages() {
        val error = RuntimeException("session for admin@secret.example", IllegalStateException("/home/user/private"))

        val described = MangoLog.describe(error)

        assertEquals("RuntimeException <- IllegalStateException", described)
        assertFalse(described.contains("secret.example"))
        assertFalse(described.contains("/home/user/private"))
    }

    @Test
    fun connectFailureNamesStageAndRootCause() {
        val result = ConnectResult.TransportError(NoSuchAlgorithmException("No provider found for ChaCha20/None/NoPadding"))

        val described = MangoLog.describe(SshFailure(SshFailure.Category.CONNECT, result.failureDetail()))

        assertEquals("SshFailure[CONNECT/TransportError/NoSuchAlgorithmException]", described)
    }

    @Test
    fun protocolErrorOmitsItsRemoteMessage() {
        val result = ConnectResult.ProtocolError("server said: hello from secret.example")

        val described = MangoLog.describe(SshFailure(SshFailure.Category.CONNECT, result.failureDetail()))

        assertEquals("SshFailure[CONNECT/ProtocolError]", described)
    }

    @Test
    fun categoryAloneWhenNoDetail() {
        assertEquals("SshFailure[CLOSED]", MangoLog.describe(SshFailure(SshFailure.Category.CLOSED)))
    }
}
