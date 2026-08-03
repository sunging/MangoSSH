package website.sung.mangossh.data.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey

class SshKeyManagerTest {
    private val manager = SshKeyManager()

    @Test
    fun everyGeneratedKeyTypeCanBeDecodedAgain() {
        val expectations = listOf(
            ExpectedKey(SshKeyGenerationType.ED25519, "EdDSA", "ssh-ed25519"),
            ExpectedKey(SshKeyGenerationType.ECDSA_P256, "EC", "ecdsa-sha2-nistp256", 256),
            ExpectedKey(SshKeyGenerationType.ECDSA_P384, "EC", "ecdsa-sha2-nistp384", 384),
            ExpectedKey(SshKeyGenerationType.ECDSA_P521, "EC", "ecdsa-sha2-nistp521", 521),
            ExpectedKey(SshKeyGenerationType.RSA_2048, "RSA", "ssh-rsa", 2048),
            ExpectedKey(SshKeyGenerationType.RSA_3072, "RSA", "ssh-rsa", 3072),
            ExpectedKey(SshKeyGenerationType.RSA_4096, "RSA", "ssh-rsa", 4096),
        )

        expectations.forEach { expected ->
            val key = manager.generateKey(expected.type, "test key")
            val decoded = manager.decodeKeyPair(key)

            assertEquals(expected.type.name, expected.javaAlgorithm, decoded.public.algorithm)
            assertTrue(expected.type.name, key.publicKey.startsWith("${expected.sshAlgorithm} "))
            assertTrue(expected.type.name, key.fingerprint.startsWith("SHA256:"))
            assertTrue(expected.type.name, key.privateKeyPem.contains("BEGIN OPENSSH PRIVATE KEY"))
            when (val publicKey = decoded.public) {
                is ECPublicKey -> assertEquals(expected.type.name, expected.strength, publicKey.params.curve.field.fieldSize)
                is RSAPublicKey -> assertEquals(expected.type.name, expected.strength, publicKey.modulus.bitLength())
            }
        }
    }

    private data class ExpectedKey(
        val type: SshKeyGenerationType,
        val javaAlgorithm: String,
        val sshAlgorithm: String,
        val strength: Int? = null,
    )
}
