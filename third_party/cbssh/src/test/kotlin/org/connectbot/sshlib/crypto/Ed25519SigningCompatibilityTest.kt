package org.connectbot.sshlib.crypto

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import kotlin.test.Test
import kotlin.test.assertTrue
import org.connectbot.sshlib.crypto.ed25519.Ed25519PrivateKey

/** Android fallback keys must sign without globally installing or relying on a JCA provider. */
class Ed25519SigningCompatibilityTest {
    @Test fun platformAndFallbackKeysProduceSignaturesVerifiedByJdk() {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val message = java.util.UUID.randomUUID().toString().toByteArray()
        for (key in listOf(pair.private, Ed25519PrivateKey(PKCS8EncodedKeySpec(pair.private.encoded)))) {
            val encoded = Ed25519SignatureAlgorithm.sign("ssh-ed25519", key, message)
            val input = DataInputStream(ByteArrayInputStream(encoded))
            input.skipBytes(input.readInt())
            val signature = ByteArray(input.readInt()).also(input::readFully)
            assertTrue(Signature.getInstance("Ed25519").run {
                initVerify(pair.public); update(message); verify(signature)
            })
        }
    }
}
