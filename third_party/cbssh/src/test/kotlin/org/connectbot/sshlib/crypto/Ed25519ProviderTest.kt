package org.connectbot.sshlib.crypto

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Security
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.connectbot.sshlib.SshKeys
import org.connectbot.sshlib.crypto.ed25519.Ed25519KeyFactory
import org.connectbot.sshlib.crypto.ed25519.Ed25519KeyPairGenerator
import org.connectbot.sshlib.crypto.ed25519.Ed25519PrivateKey
import org.connectbot.sshlib.crypto.ed25519.Ed25519Provider
import org.connectbot.sshlib.crypto.ed25519.Ed25519PublicKey

/** Exercises the reflective JCA services explicitly, without the JDK's Ed25519 fallback. */
class Ed25519ProviderTest {
    /** JCA must instantiate both services by their registered names, including all aliases. */
    @Test fun servicesAndAliasesInstantiateWithoutGlobalRegistration() {
        val installed = Security.getProviders().toList()
        val provider = Ed25519Provider()
        assertIs<Ed25519KeyFactory>(provider.getService("KeyFactory", "Ed25519").newInstance(null))
        assertIs<Ed25519KeyPairGenerator>(provider.getService("KeyPairGenerator", "Ed25519").newInstance(null))
        for (algorithm in listOf("Ed25519", "EdDSA", "1.3.101.112", "OID.1.3.101.112")) {
            val generator = KeyPairGenerator.getInstance(algorithm, provider)
            val factory = KeyFactory.getInstance(algorithm, provider)
            assertSame(provider, generator.provider)
            assertSame(provider, factory.provider)
            val pair = generator.generateKeyPair()
            val decoded = KeyPair(
                factory.generatePublic(X509EncodedKeySpec(pair.public.encoded)),
                factory.generatePrivate(PKCS8EncodedKeySpec(pair.private.encoded)),
            )
            assertTrue(pair.public.encoded.contentEquals(decoded.public.encoded))
            assertTrue(pair.private.encoded.contentEquals(decoded.private.encoded))
            verifySignature(decoded)
        }
        assertTrue(installed == Security.getProviders().toList())
    }

    /** Raw conversion must work when the compatibility provider is the only available choice. */
    @Test fun rawKeyFactoryUsesExplicitCompatibilityProvider() {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val providers = listOf(Ed25519Provider())
        val publicSpec = X509EncodedKeySpec(pair.public.encoded)
        val privateSpec = PKCS8EncodedKeySpec(pair.private.encoded)
        assertIs<Ed25519PublicKey>(RawKeyFactory.generatePublic("Ed25519", publicSpec, providers))
        assertIs<Ed25519PrivateKey>(RawKeyFactory.generatePrivate("Ed25519", privateSpec, providers))
        val decoded = RawKeyFactory.generateKeyPair("Ed25519", publicSpec, privateSpec, providers)
        assertIs<Ed25519PublicKey>(decoded.public)
        assertIs<Ed25519PrivateKey>(decoded.private)
        assertTrue(pair.public.encoded.contentEquals(decoded.public.encoded))
        verifySignature(decoded)
    }

    /** Plain and encrypted OpenSSH records retain identity and can sign with fallback keys. */
    @Test fun openSshRoundTripsRetainIdentityAndRejectWrongPassphrases() {
        val provider = Ed25519Provider()
        val pair = KeyPairGenerator.getInstance("Ed25519", provider).generateKeyPair()
        for (passphrase in listOf(null, UUID.randomUUID().toString())) {
            val encoded = SshKeys.encodeOpenSshPrivateKey(pair, passphrase)
            val decoded = SshKeys.decodePemPrivateKey(encoded, passphrase)
            assertTrue(pair.public.encoded.contentEquals(decoded.public.encoded))
            val fallback = RawKeyFactory.generateKeyPair(
                "Ed25519", X509EncodedKeySpec(decoded.public.encoded),
                PKCS8EncodedKeySpec(decoded.private.encoded), listOf(provider),
            )
            verifySignature(fallback)
            if (passphrase != null) {
                assertFailsWith<Exception> { SshKeys.decodePemPrivateKey(encoded, UUID.randomUUID().toString()) }
            }
        }
    }

    private fun verifySignature(pair: KeyPair) {
        val message = UUID.randomUUID().toString().toByteArray()
        val encoded = Ed25519SignatureAlgorithm.sign("ssh-ed25519", pair.private, message)
        val input = DataInputStream(ByteArrayInputStream(encoded))
        input.skipBytes(input.readInt())
        val signature = ByteArray(input.readInt()).also(input::readFully)
        val verifierKey = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(pair.public.encoded))
        assertTrue(Signature.getInstance("Ed25519").run {
            initVerify(verifierKey)
            update(message)
            verify(signature)
        })
    }
}
