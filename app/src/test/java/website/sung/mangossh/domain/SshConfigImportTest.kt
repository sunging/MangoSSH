package website.sung.mangossh.domain

import org.junit.Assert.*
import org.junit.Test

class SshConfigImportTest {
    @Test fun wildcardDefaultsUseFirstValueAndNegation() {
        val result = SshConfigImport.parse("""
            Host * !private
              User default-user
              ServerAliveInterval 60
            Host public private
              HostName %h.invalid
              User later-user
              Port=2222
        """.trimIndent())
        assertEquals("default-user", result.candidates.first().username)
        assertEquals("later-user", result.candidates.last().username)
        assertEquals("public.invalid", result.candidates.first().hostname)
        assertEquals(2222, result.candidates.first().port)
    }

    @Test fun matchContentsNeverLeakIntoHostDefaultsAndCommandsNeverExecute() {
        val result = SshConfigImport.parse("""
            Include untrusted-config
            Match exec "untrusted-command"
              User hidden
              ProxyCommand untrusted-command
            Host synthetic
              IdentityFile "untrusted path"
              IdentitiesOnly yes
              ProxyJump jump
        """.trimIndent())
        assertNull(result.candidates.single().username)
        assertEquals(listOf("untrusted path"), result.candidates.single().identityFiles)
        assertTrue(result.candidates.single().identitiesOnly)
        assertEquals(4, result.issues.size)
    }
}
