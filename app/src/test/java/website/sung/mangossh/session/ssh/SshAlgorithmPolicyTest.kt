package website.sung.mangossh.session.ssh

import org.connectbot.sshlib.SshClientConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshAlgorithmPolicyTest {
    @Test fun modernPolicyPreservesDefaultsAndConnectionOwnership() {
        val upstream = SshClientConfig.Builder()
        val actual = SshClientConfig.Builder().apply { applyMangoAlgorithmPolicy(false) }
        assertEquals(upstream.hostKeyAlgorithms, actual.hostKeyAlgorithms)
        assertEquals(upstream.kexAlgorithms, actual.kexAlgorithms)
        assertEquals(upstream.encryptionAlgorithms, actual.encryptionAlgorithms)
        assertEquals(upstream.macAlgorithms, actual.macAlgorithms)
        assertFalse(actual.autoDisconnectOnLastChannelClose)
    }

    @Test fun compatibilityIsAppendedWithoutForbiddenAlgorithms() {
        val modern = SshClientConfig.Builder()
        val legacy = SshClientConfig.Builder().apply { applyMangoAlgorithmPolicy(true) }
        assertTrue(legacy.hostKeyAlgorithms.startsWith(modern.hostKeyAlgorithms))
        assertTrue(legacy.kexAlgorithms.startsWith(modern.kexAlgorithms))
        assertTrue(legacy.encryptionAlgorithms.startsWith(modern.encryptionAlgorithms))
        assertTrue(legacy.macAlgorithms.startsWith(modern.macAlgorithms))
        val names = listOf(legacy.hostKeyAlgorithms, legacy.kexAlgorithms, legacy.encryptionAlgorithms, legacy.macAlgorithms)
            .flatMap { it.split(',') }.toSet()
        assertTrue(names.containsAll(setOf("ssh-rsa", "rsa-sha2-256", "rsa-sha2-512", "diffie-hellman-group14-sha1",
            "diffie-hellman-group-exchange-sha1", "aes128-cbc", "aes256-cbc", "hmac-sha1")))
        assertFalse(names.any { it in setOf("ssh-dss", "diffie-hellman-group1-sha1", "3des-cbc", "blowfish-cbc") })
    }

    @Test fun trustedHostKeyFamiliesAreOfferedFirstWithoutChangingTheSet() {
        val modern = SshClientConfig.Builder()
        val preferred = SshClientConfig.Builder().apply { applyMangoAlgorithmPolicy(false, setOf("ssh-rsa")) }
        val names = preferred.hostKeyAlgorithms.split(',')
        assertEquals(modern.hostKeyAlgorithms.split(',').toSet(), names.toSet())
        assertTrue(names.first().startsWith("rsa-sha2-"))
        assertFalse("ssh-rsa" in names)

        val legacy = SshClientConfig.Builder().apply { applyMangoAlgorithmPolicy(true, setOf("ssh-rsa")) }
        val legacyNames = legacy.hostKeyAlgorithms.split(',')
        assertTrue(legacyNames.indexOf("ssh-rsa") < legacyNames.indexOf("ssh-ed25519"))
    }
}
