package org.connectbot.sshlib.crypto

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.connectbot.sshlib.SshException

/** Weak ciphers cannot be re-enabled by passing their wire names to the library. */
class DisabledCiphersTest {
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
}
