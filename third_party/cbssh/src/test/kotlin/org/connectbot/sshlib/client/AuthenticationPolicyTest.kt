package org.connectbot.sshlib.client

import java.security.KeyPairGenerator
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.connectbot.sshlib.AuthHandler
import org.connectbot.sshlib.AuthPublicKey
import org.connectbot.sshlib.AuthResult as PublicAuthResult
import org.connectbot.sshlib.ConnectResult
import org.connectbot.sshlib.crypto.SignatureVerifier
import org.connectbot.sshlib.HostKeyVerifier
import org.connectbot.sshlib.KeyboardInteractiveCallback
import org.connectbot.sshlib.PublicKey
import org.connectbot.sshlib.SshSigning
import org.connectbot.sshlib.protocol.UserauthRequestPublickey
import org.connectbot.sshlib.transport.PipedTransport
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Exercises real protocol packets with ephemeral credentials, including mixed method offers. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AuthenticationPolicyTest {
    private open class Handler(
        override val supportedMethods: Set<String> = setOf("publickey", "keyboard-interactive", "password"),
        override val preferPasswordAuth: Boolean = false,
    ) : AuthHandler {
        override suspend fun onPublicKeysNeeded(): List<AuthPublicKey> = error("Unexpected key request")
        override suspend fun onSignatureRequest(key: AuthPublicKey, dataToSign: ByteArray): ByteArray? = error("Unexpected signature request")
        override suspend fun onPasswordNeeded(): String? = error("Unexpected password request")
        override suspend fun onKeyboardInteractivePrompt(name: String, instruction: String, prompts: List<KeyboardInteractiveCallback.Prompt>): List<String>? = error("Unexpected interactive request")
    }

    private suspend fun TestScope.connected(block: suspend (SshConnection, FakeSshServer) -> Unit) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val (transport, peer) = PipedTransport.create()
        val server = FakeSshServer(peer, backgroundScope, dispatcher).apply { advertiseExtInfo = true; start() }
        val connection = SshConnection(transport, hostKeyVerifier = object : HostKeyVerifier {
            override suspend fun verify(key: PublicKey) = true
        }, coroutineDispatcher = dispatcher)
        try {
            assertIs<ConnectResult.Success>(backgroundScope.async(dispatcher) { connection.connect() }.await())
            block(connection, server)
        } finally { connection.close() }
    }

    @Test fun `password is requested for single and mixed offers without trying other credentials`() = runTest {
        for (offer in listOf(setOf("password"), setOf("publickey", "keyboard-interactive", "password"))) {
            connected { connection, server ->
                var asked = false
                val handler = object : Handler(setOf("password", "keyboard-interactive"), true) {
                    override suspend fun onPasswordNeeded(): String { asked = true; return UUID.randomUUID().toString() }
                }
                val auth = backgroundScope.async { connection.authenticate("ephemeral", handler) }
                assertEquals("none", server.awaitUserauthRequest().methodName().value())
                server.sendUserauthFailure(offer, false)
                assertEquals("password", server.awaitUserauthRequest().methodName().value())
                server.sendUserauthSuccess()
                assertIs<PublicAuthResult.Success>(auth.await())
                assertTrue(asked)
            }
        }
    }

    @Test fun `rejected password does not fall back to interactive`() = runTest {
        connected { connection, server ->
            val handler = object : Handler(setOf("password", "keyboard-interactive"), true) {
                override suspend fun onPasswordNeeded() = UUID.randomUUID().toString()
            }
            val auth = backgroundScope.async { connection.authenticate("ephemeral", handler) }
            server.awaitUserauthRequest()
            server.sendUserauthFailure(setOf("password", "keyboard-interactive"), false)
            assertEquals("password", server.awaitUserauthRequest().methodName().value())
            server.sendUserauthFailure(setOf("keyboard-interactive"), false)
            assertIs<PublicAuthResult.Failure>(auth.await())
            runCurrent()
            assertNull(server.pollUserauthRequest())
        }
    }

    @Test fun `unsupported methods are filtered before requesting credentials or sending auth`() = runTest {
        for ((supported, offered) in listOf(setOf("publickey") to setOf("password", "keyboard-interactive"), setOf("password", "keyboard-interactive") to setOf("publickey"))) {
            connected { connection, server ->
                val auth = backgroundScope.async { connection.authenticate("ephemeral", Handler(supported)) }
                assertEquals("none", server.awaitUserauthRequest().methodName().value())
                server.sendUserauthFailure(offered, false)
                assertIs<PublicAuthResult.Failure>(auth.await())
                runCurrent()
                assertNull(server.pollUserauthRequest())
            }
        }
    }

    @Test fun `default and explicit interactive handlers retain interactive preference`() = runTest {
        for (handler in listOf(Handler(), Handler(setOf("keyboard-interactive")))) {
            connected { connection, server ->
                val auth = backgroundScope.async { connection.authenticate("ephemeral", handler) }
                server.awaitUserauthRequest()
                server.sendUserauthFailure(setOf("password", "keyboard-interactive"), false)
                assertEquals("keyboard-interactive", server.awaitUserauthRequest().methodName().value())
                server.sendUserauthSuccess()
                assertIs<PublicAuthResult.Success>(auth.await())
            }
        }
    }

    @Test fun `password mode accepts multi-round interactive only when password is absent`() = runTest {
        connected { connection, server ->
            var rounds = 0
            val handler = object : Handler(setOf("password", "keyboard-interactive"), true) {
                override suspend fun onKeyboardInteractivePrompt(name: String, instruction: String, prompts: List<KeyboardInteractiveCallback.Prompt>): List<String> {
                    assertEquals("Fixture", name)
                    assertEquals("Enter response", instruction)
                    assertEquals(1, prompts.size)
                    assertFalse(prompts.single().echo)
                    rounds++
                    return listOf(UUID.randomUUID().toString())
                }
            }
            val auth = backgroundScope.async { connection.authenticate("ephemeral", handler) }
            server.awaitUserauthRequest()
            server.sendUserauthFailure(setOf("keyboard-interactive"), false)
            assertEquals("keyboard-interactive", server.awaitUserauthRequest().methodName().value())
            repeat(2) {
                server.sendUserauthInfoRequest("Fixture", "Enter response", listOf("Response" to false))
                assertEquals(1L, server.awaitUserauthInfoResponse().numResponses())
            }
            server.sendUserauthSuccess()
            assertIs<PublicAuthResult.Success>(auth.await())
            assertEquals(2, rounds)
        }
    }

    @Test fun `none success needs no credentials and cancellation stays cancellation`() = runTest {
        connected { connection, server ->
            val auth = backgroundScope.async { connection.authenticate("ephemeral", Handler(emptySet())) }
            assertEquals("none", server.awaitUserauthRequest().methodName().value())
            server.sendUserauthSuccess()
            assertIs<PublicAuthResult.Success>(auth.await())
        }
        connected { connection, server ->
            val handler = object : Handler(setOf("keyboard-interactive")) {
                override suspend fun onKeyboardInteractivePrompt(name: String, instruction: String, prompts: List<KeyboardInteractiveCallback.Prompt>): List<String>? = throw CancellationException()
            }
            val auth = backgroundScope.async { connection.authenticate("ephemeral", handler) }
            server.awaitUserauthRequest()
            server.sendUserauthFailure(setOf("keyboard-interactive"), false)
            server.awaitUserauthRequest()
            server.sendUserauthInfoRequest("", "", listOf("Response" to false))
            assertFailsWith<CancellationException> { auth.await() }
        }
    }

    @Test fun `RSA Ed25519 and ECDSA probe and sign without falling back after rejection`() = runTest {
        for (algorithm in listOf("RSA", "Ed25519", "EC")) {
            val pair = KeyPairGenerator.getInstance(algorithm).apply { if (algorithm == "RSA") initialize(2048); if (algorithm == "EC") initialize(256) }.generateKeyPair()
            val key = SshSigning.encodePublicKey(pair)
            val secondKey = SshSigning.encodePublicKey(KeyPairGenerator.getInstance(algorithm).apply {
                if (algorithm == "RSA") initialize(2048)
                if (algorithm == "EC") initialize(256)
            }.generateKeyPair())
            for (outcome in listOf("probe-rejected", "signature-rejected", "success")) {
                connected { connection, server ->
                    server.sendCustomExtInfo(mapOf("server-sig-algs" to "rsa-sha2-512,rsa-sha2-256,ssh-ed25519,ecdsa-sha2-nistp256".toByteArray()))
                    var signed = false
                    var signingData = ByteArray(0)
                    val handler = object : Handler(setOf("publickey")) {
                        override suspend fun onPublicKeysNeeded() = listOf(key, secondKey)
                        override suspend fun onSignatureRequest(key: AuthPublicKey, dataToSign: ByteArray): ByteArray {
                            assertNotEquals("ssh-rsa", key.algorithmName)
                            signed = true
                            signingData = dataToSign.copyOf()
                            return SshSigning.signWithKeyPair(key.algorithmName, pair, dataToSign)
                        }
                    }
                    val auth = backgroundScope.async { connection.authenticate("ephemeral", handler) }
                    server.awaitUserauthRequest()
                    server.sendUserauthFailure(setOf("publickey", "password", "keyboard-interactive"), false)
                    val probe = server.awaitUserauthRequest().methodSpecificFields() as UserauthRequestPublickey
                    assertEquals(0, probe.hasSignature())
                    if (outcome == "probe-rejected") {
                        server.sendUserauthFailure(setOf("password", "keyboard-interactive"), false)
                        assertIs<PublicAuthResult.Failure>(auth.await())
                        assertFalse(signed)
                        runCurrent()
                        assertNull(server.pollUserauthRequest())
                        return@connected
                    }
                    server.sendUserauthPkOk(probe.publicKeyAlgorithmName().value(), probe.publicKeyBlob().data())
                    val request = server.awaitUserauthRequest().methodSpecificFields() as UserauthRequestPublickey
                    assertEquals(1, request.hasSignature())
                    assertTrue(SignatureVerifier.verify(key.publicKeyBlob, request.signature().data(), signingData, request.publicKeyAlgorithmName().value()))
                    val accept = outcome == "success"
                    if (accept) server.sendUserauthSuccess() else server.sendUserauthFailure(setOf("password", "keyboard-interactive"), false)
                    if (accept) assertIs<PublicAuthResult.Success>(auth.await()) else assertIs<PublicAuthResult.Failure>(auth.await())
                    assertTrue(signed)
                    runCurrent()
                    assertNull(server.pollUserauthRequest())
                }
            }
        }
    }
}
