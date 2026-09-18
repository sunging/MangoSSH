package website.sung.mangossh.data.vault

import java.security.SecureRandom
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class HostKeyTrustTest {
    private fun key(algorithm: String = "rsa-sha2-512") = TrustedHostKey("fixture.invalid", 22, algorithm,
        Base64.getEncoder().encodeToString(ByteArray(64).also(SecureRandom()::nextBytes)), "fixture")

    @Test fun rsaSignatureAliasesRetainTrustOnlyForTheExactKeyAndEndpoint() {
        val trusted = key()
        for (algorithm in listOf("ssh-rsa", "rsa-sha2-256", "rsa-sha2-512")) {
            assertTrue(isTrustedHostKey(listOf(trusted), trusted.hostname, 22, algorithm, trusted.keyBlobBase64))
        }
        assertFalse(isTrustedHostKey(listOf(trusted), trusted.hostname, 23, "ssh-rsa", trusted.keyBlobBase64))
        assertFalse(isTrustedHostKey(listOf(trusted), "other.invalid", 22, "ssh-rsa", trusted.keyBlobBase64))
        assertFalse(isTrustedHostKey(listOf(trusted), trusted.hostname, 22, "ssh-ed25519", trusted.keyBlobBase64))
        assertFalse(isTrustedHostKey(listOf(trusted), trusted.hostname, 22, "ssh-rsa", key().keyBlobBase64))
    }

    @Test fun conflictingAliasesRequireConfirmationAndApprovalRevokesAllOldAliases() {
        val first = key()
        val second = key("rsa-sha2-256")
        val otherAlgorithm = key("ssh-ed25519")
        val otherPort = first.copy(port = 23)
        val known = listOf(first, second, otherAlgorithm, otherPort)
        assertFalse(isTrustedHostKey(known, first.hostname, 22, "ssh-rsa", first.keyBlobBase64))
        val approved = key("ssh-rsa")
        val replaced = replaceTrustedHostKey(known, approved)
        assertEquals(listOf(otherAlgorithm, otherPort, approved), replaced)
        assertTrue(isTrustedHostKey(replaced, approved.hostname, 22, "rsa-sha2-512", approved.keyBlobBase64))
        assertFalse(isTrustedHostKey(replaced, first.hostname, 22, "ssh-rsa", first.keyBlobBase64))
    }
}
