package org.connectbot.sshlib.crypto

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import org.connectbot.sshlib.SshKeys

/**
 * Android's Conscrypt decodes Ed25519 keys into a class that is neither ours nor EdECPrivateKey.
 * An opaque wrapper stands in for it; the key and passphrase are generated per run.
 */
class OpenSshKeyWriterProviderKeyTest {
    private class OpaqueProviderKey(private val encoded: ByteArray) : PrivateKey {
        override fun getAlgorithm() = "Ed25519"
        override fun getFormat() = "PKCS#8"
        override fun getEncoded() = encoded.clone()
    }

    @Test fun providerKeysThatExposeOnlyPkcs8AreWrittenAndReadBack() {
        val platform = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val pair = KeyPair(platform.public, OpaqueProviderKey(platform.private.encoded))
        val passphrase = java.util.UUID.randomUUID().toString()
        for (password in listOf(null, passphrase)) {
            val decoded = SshKeys.decodePemPrivateKey(SshKeys.encodeOpenSshPrivateKey(pair, password), password)
            assertContentEquals(platform.public.encoded.takeLast(32), decoded.public.encoded.takeLast(32))
        }
    }
}
