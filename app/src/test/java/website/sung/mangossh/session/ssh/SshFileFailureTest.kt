package website.sung.mangossh.session.ssh

import java.io.IOException
import org.connectbot.sshlib.SftpResult
import org.connectbot.sshlib.SftpStatusCode
import org.junit.Assert.*
import org.junit.Test

class SshFileFailureTest {
    @Test fun openDiagnosticsKeepCategoriesButNeverPeerTextOrCauses() {
        val failures = listOf(
            SftpResult.IoError(IOException("secret transport details")) to "OPEN_IO",
            SftpResult.ProtocolError("secret peer details") to "OPEN_PROTOCOL",
            SftpResult.ServerError(SftpStatusCode.FAILURE, "secret path") to "OPEN_SERVER",
        )
        for ((result, category) in failures) {
            val error = runCatching { openedSftpValue(result) }.exceptionOrNull() as SshFileFailure
            assertEquals("SFTP operation failed ($category)", error.message)
            assertEquals(8, error.status)
            assertNull(error.cause)
        }
        assertEquals("value", openedSftpValue(SftpResult.Success("value")))
    }
}
