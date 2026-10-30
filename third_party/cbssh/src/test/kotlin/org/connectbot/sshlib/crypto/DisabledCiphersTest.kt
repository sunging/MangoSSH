package org.connectbot.sshlib.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.connectbot.sshlib.AlgorithmCategory
import org.connectbot.sshlib.AlgorithmPolicyWarningReason
import org.connectbot.sshlib.CryptoPolicyPreset
import org.connectbot.sshlib.SshCryptoPolicy
import org.connectbot.sshlib.SshException

/** Weak ciphers cannot be re-enabled by passing their wire names to the library. */
class DisabledCiphersTest {
    private val weakCiphers = listOf("3des-cbc", "des-cbc", "des")

    @Test fun rejectsTripleDesTransportAndWeakPemEncryption() {
        assertNull(CipherEntry.fromSshName("3des-cbc"))
        for (name in listOf("DES-CBC", "DES-EDE3-CBC")) {
            assertFailsWith<SshException> {
                KeyEncryption.encryptPem(ByteArray(16), ByteArray(0), ByteArray(8), name)
            }
            assertFailsWith<SshException> {
                KeyDecryption.decryptPem(ByteArray(16), ByteArray(0), ByteArray(8), name)
            }
        }
    }

    @Test fun noPolicyPresetOffersWeakCiphers() {
        assertTrue(SshCryptoPolicy.catalog().getValue(AlgorithmCategory.CIPHER).none { it.name in weakCiphers })
        for (preset in CryptoPolicyPreset.entries) {
            val ciphers = SshCryptoPolicy.resolve(preset).algorithms.getValue(AlgorithmCategory.CIPHER)
            assertTrue(ciphers.isNotEmpty(), "$preset offers no cipher")
            assertTrue(ciphers.none { it in weakCiphers }, "$preset offers $ciphers")
        }
    }

    @Test fun customOverridesCannotAddWeakCiphers() {
        for (expression in listOf("+3des-cbc", "^3des-cbc", "=3des-cbc,aes128-ctr")) {
            val policy = SshCryptoPolicy.resolve(overrides = mapOf(AlgorithmCategory.CIPHER to expression))
            assertTrue(policy.algorithms.getValue(AlgorithmCategory.CIPHER).none { it in weakCiphers }, expression)
            val warning = policy.warnings.single()
            assertEquals(AlgorithmCategory.CIPHER, warning.category)
            assertEquals(AlgorithmPolicyWarningReason.UNSUPPORTED, warning.reason)
            assertTrue(warning.algorithm.endsWith("3des-cbc"), warning.algorithm)
        }
    }
}
