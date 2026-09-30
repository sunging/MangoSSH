package website.sung.mangossh.session.ssh

import java.security.KeyPairGenerator
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.connectbot.sshlib.AuthResult
import org.connectbot.sshlib.KeyboardInteractiveCallback
import org.junit.Assert.*
import org.junit.Test
import website.sung.mangossh.core.MangoLog
import website.sung.mangossh.domain.AuthenticationMethod
import website.sung.mangossh.session.sshMethods

/** Regression checks keep credential errors and protocol text out of diagnostics. */
class SshAuthenticationHandlerTest {
    private fun handler(credentials: SshCredentials) = SshAuthenticationHandler(credentials, false) {}

    @Test fun profilePoliciesDoNotOfferUnselectedCredentials() {
        assertEquals(setOf("publickey"), AuthenticationMethod.PRIVATE_KEY.sshMethods())
        assertEquals(setOf("password", "keyboard-interactive"), AuthenticationMethod.PASSWORD.sshMethods())
        for (method in listOf(AuthenticationMethod.KEYBOARD_INTERACTIVE, AuthenticationMethod.TAILSCALE_SSH)) {
            assertEquals(setOf("keyboard-interactive"), method.sshMethods())
        }
    }

    @Test fun noMatchRejectionAndProtocolErrorsRemainDistinctAndSanitized() = runBlocking {
        val secret = UUID.randomUUID().toString()
        val handler = handler(object : SshCredentials { override val supportedMethods = setOf("password") })
        assertTrue(handler.resolve(AuthResult.Success))
        handler.onAuthMethodsAvailable(setOf(secret))
        val mismatch = runCatching { handler.resolve(AuthResult.Failure(setOf(secret))) }.exceptionOrNull() as SshAuthenticationFailure
        assertEquals(SshAuthenticationFailure.Category.NO_MATCHING_METHOD, mismatch.category)
        handler.onAuthMethodsAvailable(setOf("password"))
        assertFalse(handler.resolve(AuthResult.Failure(setOf("password"))))
        val protocol = runCatching { handler.resolve(AuthResult.Error(secret, IllegalStateException(secret))) }.exceptionOrNull() as SshAuthenticationFailure
        assertEquals(SshAuthenticationFailure.Category.PROTOCOL, protocol.category)
        for (failure in listOf(mismatch, protocol)) {
            assertNull(failure.message)
            assertNull(failure.cause)
            assertFalse(MangoLog.describe(failure).contains(secret))
        }
    }

    @Test fun keyLoadingFailureIsLocalAndCancellationIsPreserved() = runBlocking {
        val secret = UUID.randomUUID().toString()
        val handler = handler(object : SshCredentials {
            override suspend fun key(): java.security.KeyPair = throw IllegalArgumentException(secret)
        })
        val failure = runCatching { handler.onPublicKeysNeeded() }.exceptionOrNull() as SshAuthenticationFailure
        assertEquals(SshAuthenticationFailure.Category.LOCAL_KEY, failure.category)
        assertEquals(SshAuthenticationStage.KEY_LOADING, failure.stage)
        assertNull(failure.cause)
        assertNull(failure.message)
        assertSame(failure, runCatching { handler.resolve(AuthResult.Error(secret, failure)) }.exceptionOrNull())
        assertFalse(MangoLog.describe(failure).contains(secret))
        val cancelled = handler(object : SshCredentials {
            override suspend fun key(): java.security.KeyPair = throw CancellationException()
        })
        assertTrue(runCatching { cancelled.onPublicKeysNeeded() }.exceptionOrNull() is CancellationException)
    }

    @Test fun generatedKeysSignAndSigningErrorsRemainLocal() = runBlocking {
        for ((algorithm, signature) in listOf("RSA" to "rsa-sha2-512", "Ed25519" to "ssh-ed25519", "EC" to "ecdsa-sha2-nistp256")) {
            val pair = KeyPairGenerator.getInstance(algorithm).apply { if (algorithm == "RSA") initialize(2048); if (algorithm == "EC") initialize(256) }.generateKeyPair()
            val handler = handler(object : SshCredentials { override suspend fun key() = pair })
            val key = handler.onPublicKeysNeeded().single()
            val data = UUID.randomUUID().toString().toByteArray()
            assertTrue(requireNotNull(handler.onSignatureRequest(key.copy(algorithmName = signature), data)).isNotEmpty())
            if (algorithm == "RSA") assertNull(handler.onSignatureRequest(key.copy(algorithmName = "ssh-rsa"), data))
            val failure = runCatching { handler.onSignatureRequest(key.copy(algorithmName = "unsupported"), data) }.exceptionOrNull() as SshAuthenticationFailure
            assertEquals(SshAuthenticationFailure.Category.LOCAL_KEY, failure.category)
            assertEquals(SshAuthenticationStage.KEY_SIGNING, failure.stage)
            assertNull(failure.cause)
            assertNull(failure.message)
        }
    }

    @Test fun interactivePromptsAreForwardedVerbatimAndNeverReusePassword() = runBlocking {
        val answer = UUID.randomUUID().toString()
        var rounds = 0
        val handler = handler(object : SshCredentials {
            override val supportedMethods = AuthenticationMethod.PASSWORD.sshMethods()
            override val preferPasswordAuth = true
            override suspend fun password(): String = error("Must not request a password for interactive prompts")
            override suspend fun interactive(name: String, instruction: String, fields: List<SshPromptField>): List<String> {
                assertEquals("Remote title", name)
                assertEquals("Remote instruction", instruction)
                assertEquals("Remote prompt", fields.single().text)
                assertFalse(fields.single().echo)
                rounds++
                return listOf(answer)
            }
        })
        assertTrue(handler.preferPasswordAuth)
        repeat(2) {
            val responses = handler.onKeyboardInteractivePrompt("Remote title", "Remote instruction", listOf(KeyboardInteractiveCallback.Prompt("Remote prompt", false)))
            assertTrue(responses == listOf(answer))
        }
        assertEquals(2, rounds)
    }
}
