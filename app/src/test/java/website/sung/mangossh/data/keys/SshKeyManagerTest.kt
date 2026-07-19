package website.sung.mangossh.data.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SshKeyManagerTest {
    private val manager = SshKeyManager()

    @Test
    fun generatedEd25519KeyCanBeDecodedAgain() {
        val key = manager.generateEd25519("test key")

        val decoded = manager.decodeKeyPair(key)

        assertEquals("EdDSA", decoded.public.algorithm)
        assertTrue(key.publicKey.startsWith("ssh-ed25519 "))
        assertTrue(key.fingerprint.startsWith("SHA256:"))
        assertTrue(key.privateKeyPem.contains("BEGIN OPENSSH PRIVATE KEY"))
    }
}
