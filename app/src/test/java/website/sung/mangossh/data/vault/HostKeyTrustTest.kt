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

    @Test fun classificationSeparatesFirstUseChangedKeyAndNewKeyType() {
        val rsa = key("ssh-rsa").copy(fingerprint = "SHA256:rsa")
        val known = listOf(rsa, key("ssh-ed25519").copy(port = 23))

        assertEquals(HostKeyCheck.Trusted, classifyHostKey(known, rsa.hostname, 22, "rsa-sha2-512", rsa.keyBlobBase64))
        assertEquals(HostKeyCheck.FirstUse, classifyHostKey(known, "other.invalid", 22, "ssh-rsa", rsa.keyBlobBase64))
        assertEquals(
            HostKeyCheck.Changed("SHA256:rsa"),
            classifyHostKey(known, rsa.hostname, 22, "ssh-rsa", key().keyBlobBase64),
        )
        // The ed25519 key trusted on port 23 says nothing about port 22.
        assertEquals(
            HostKeyCheck.NewKeyType(listOf(rsa)),
            classifyHostKey(known, rsa.hostname, 22, "ssh-ed25519", key().keyBlobBase64),
        )
    }

    @Test fun approvingANewKeyTypeKeepsTheOtherTrustedTypes() {
        val rsa = key("ssh-rsa")
        val ed25519 = key("ssh-ed25519")
        val trusted = replaceTrustedHostKey(listOf(rsa), ed25519)
        assertEquals(listOf(rsa, ed25519), trusted)
        assertEquals(setOf("ssh-rsa", "ssh-ed25519"), trustedHostKeyFamilies(trusted, rsa.hostname, 22))
        assertEquals(emptySet<String>(), trustedHostKeyFamilies(trusted, rsa.hostname, 23))
    }

    @Test fun trustedFamiliesMoveForwardWithoutAddingOrReorderingWithinGroups() {
        val offered = "ssh-ed25519,ecdsa-sha2-nistp256,rsa-sha2-256,rsa-sha2-512"
        assertEquals(offered, preferTrustedHostKeyAlgorithms(offered, emptySet()))
        assertEquals(
            "rsa-sha2-256,rsa-sha2-512,ssh-ed25519,ecdsa-sha2-nistp256",
            preferTrustedHostKeyAlgorithms(offered, setOf("ssh-rsa")),
        )
        // Several trusted families keep the offered order relative to each other.
        assertEquals(
            "ecdsa-sha2-nistp256,rsa-sha2-256,rsa-sha2-512,ssh-ed25519",
            preferTrustedHostKeyAlgorithms(offered, setOf("ssh-rsa", "ecdsa-sha2-nistp256")),
        )
        assertEquals(offered, preferTrustedHostKeyAlgorithms(offered, setOf("ssh-dss")))
    }
}
