package website.sung.mangossh.session

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import website.sung.mangossh.data.keys.SshKeyGenerationType
import website.sung.mangossh.data.keys.SshKeyManager
import website.sung.mangossh.data.vault.*
import website.sung.mangossh.domain.AuthenticationMethod
import website.sung.mangossh.domain.ConnectionProfile
import website.sung.mangossh.session.ssh.*

/** Runtime-only credentials exercise the staged application policy without production fixtures. */
class SshAuthenticationPasswordTest {
    private val manager = SshKeyManager()
    private val key by lazy { manager.generateKey(SshKeyGenerationType.ED25519, "Ephemeral") }
    private val entered = " ${UUID.randomUUID()}\t "
    private fun record(withKey: Boolean = false, withPassword: Boolean = false) = StoredAuthentication("auth", "Example",
        UUID.randomUUID().toString().takeIf { withPassword }, key.id.takeIf { withKey })
    private fun profile() = ConnectionProfile(id = "host", label = "Example", hostname = "synthetic.invalid", username = "test", authenticationId = "auth")
    private fun snapshot(record: StoredAuthentication) = VaultSnapshot(authentications = listOf(record), keys = listOfNotNull(key.takeIf { record.keyId != null }))
    private fun missing() = SshAuthenticationFailure(SshAuthenticationFailure.Category.NO_MATCHING_METHOD, SshAuthenticationStage.DISCOVERY)

    @Test fun allFourConfigurationsUseKeyThenPasswordThenInteractive() = runBlocking {
        for (withKey in listOf(false, true)) for (withPassword in listOf(false, true)) {
            val record = record(withKey, withPassword)
            val calls = mutableListOf<String>()
            var prompts = 0
            val auth = SshAuthentication(manager, { _, _, _, fields -> prompts++; assertFalse(fields.last().initiallyChecked); listOf(entered, "") })
            assertTrue(auth.authenticate("session", profile(), snapshot(record)) { credentials ->
                val method = credentials.supportedMethods.single(); calls += method
                when (method) {
                    "publickey" -> { assertNotNull(credentials.key()); false }
                    "password" -> { assertEquals(record.password ?: entered, credentials.password()); true }
                    else -> error("Successful password must end authentication")
                }
            })
            assertEquals(if (withKey) listOf("publickey", "password") else listOf("password"), calls)
            assertEquals(if (withPassword) 0 else 1, prompts)
        }
    }

    @Test fun successfulKeyNeverAsksForOrSavesAPassword() = runBlocking {
        val auth = SshAuthentication(manager, { _, _, _, _ -> error("Unexpected prompt") }, savePassword = { _, _ -> error("Unexpected save") })
        assertTrue(auth.authenticate("session", profile(), snapshot(record(true, true))) {
            assertEquals(setOf("publickey"), it.supportedMethods); assertNotNull(it.key()); true
        })
    }

    @Test fun unsupportedPublicKeySkipsToSavedPasswordOnSameExchange() = runBlocking {
        val record = record(true, true)
        val calls = mutableListOf<String>()
        val auth = SshAuthentication(manager, { _, _, _, _ -> error("Saved password must not prompt") })
        assertTrue(auth.authenticate("session", profile(), snapshot(record)) {
            calls += it.supportedMethods.single()
            if (calls.size == 1) throw missing()
            assertEquals(record.password, it.password()); true
        })
        assertEquals(listOf("publickey", "password"), calls)
    }

    @Test fun keyFallbackPromptsWithUncheckedSaveAndPreservesWhitespace() = runBlocking {
        var prompts = 0
        val auth = SshAuthentication(manager, { _, _, instruction, fields ->
            prompts++
            assertEquals(SessionPromptText.App(SessionPromptTextKind.KEY_PASSWORD_FALLBACK_INSTRUCTION), instruction)
            assertTrue(fields[1].toggle); assertFalse(fields[1].initiallyChecked)
            listOf(entered, "")
        }, savePassword = { _, _ -> error("Unchecked input cannot be saved") })
        assertTrue(auth.authenticate("session", profile(), snapshot(record(true))) {
            if ("publickey" in it.supportedMethods) false else { assertEquals(entered, it.password()); true }
        })
        assertEquals(1, prompts)
    }

    @Test fun rejectedSavedPasswordGoesDirectlyToManualMultiRoundInteractive() = runBlocking {
        val record = record(true, true)
        val calls = mutableListOf<String>()
        val serverText = UUID.randomUUID().toString()
        var rounds = 0
        val auth = SshAuthentication(manager, { _, title, instruction, fields ->
            rounds++
            assertEquals(SessionPromptText.Verbatim("Server title"), title)
            assertEquals(SessionPromptText.Verbatim(serverText), (instruction as SessionPromptText.Joined).parts.last())
            assertFalse(fields.single().toggle)
            listOf(entered)
        }, savePassword = { _, _ -> error("Interactive answers cannot be saved") })
        assertTrue(auth.authenticate("session", profile(), snapshot(record)) {
            val method = it.supportedMethods.single(); calls += method
            when (method) {
                "publickey" -> false
                "password" -> { assertEquals(record.password, it.password()); false }
                else -> { repeat(2) { _ -> assertEquals(listOf(entered), it.interactive("Server title", serverText, listOf(SshPromptField("Response", false)))) }; true }
            }
        })
        assertEquals(listOf("publickey", "password", "keyboard-interactive"), calls)
        assertEquals(2, rounds)
    }

    @Test fun interactiveSuccessNeverSavesTheRejectedOptedInPassword() = runBlocking {
        var prompts = 0
        val auth = SshAuthentication(manager, { _, _, _, fields ->
            prompts++; if (fields.size == 2) listOf(entered, AuthenticationField.TOGGLE_ON) else listOf(UUID.randomUUID().toString())
        }, savePassword = { _, _ -> error("Rejected password must be discarded") })
        assertTrue(auth.authenticate("session", profile(), snapshot(record())) {
            if ("password" in it.supportedMethods) { it.password(); false }
            else { it.interactive("", "", listOf(SshPromptField("OTP", false))); true }
        })
        assertEquals(2, prompts)
    }

    @Test fun optedInPasswordIsSavedOnlyAfterItsOwnPhaseSucceeded() = runBlocking {
        var accepted = false
        var saved: String? = null
        val auth = SshAuthentication(manager, { _, _, _, _ -> listOf(entered, AuthenticationField.TOGGLE_ON) }, savePassword = { host, password ->
            assertTrue(accepted); assertEquals(profile(), host); saved = password
        })
        assertTrue(auth.authenticate("session", profile(), snapshot(record())) { assertEquals(entered, it.password()); accepted = true; true })
        assertEquals(entered, saved)
    }

    @Test fun noConfiguredAuthenticationAsksAtConnection() = runBlocking {
        val auth = SshAuthentication(manager, { _, _, _, _ -> listOf(entered, "") })
        assertTrue(auth.authenticate("session", profile().copy(authenticationId = null), VaultSnapshot()) { assertEquals(entered, it.password()); true })
    }

    @Test fun noAvailableMethodReturnsSanitizedFailureWithoutPrompting() = runBlocking {
        var calls = 0
        val auth = SshAuthentication(manager, { _, _, _, _ -> error("No available method may prompt") })
        val failure = runCatching { auth.authenticate("session", profile(), snapshot(record(true))) { calls++; throw missing() } }.exceptionOrNull()
        assertEquals(3, calls)
        assertEquals(SshAuthenticationFailure.Category.NO_MATCHING_METHOD, (failure as SshAuthenticationFailure).category)
    }

    @Test fun passwordUnavailableStillAllowsInteractiveAndNeverReadsSavedPassword() = runBlocking {
        val auth = SshAuthentication(manager, { _, _, _, _ -> listOf(entered) })
        assertTrue(auth.authenticate("session", profile(), snapshot(record(false, true))) {
            if ("password" in it.supportedMethods) throw missing()
            assertEquals(listOf(entered), it.interactive("", "", listOf(SshPromptField("Response", false)))); true
        })
    }

    @Test fun cancellationAtAnyPromptEndsTheWholeAttempt() = runBlocking {
        for (cancelMethod in listOf("password", "keyboard-interactive")) {
            var calls = 0
            val auth = SshAuthentication(manager, { _, _, _, _ -> null })
            val result = runCatching { auth.authenticate("session", profile(), snapshot(record())) {
                calls++
                if (it.supportedMethods.single() != cancelMethod) throw missing()
                if (cancelMethod == "password") it.password() else it.interactive("", "", listOf(SshPromptField("OTP", false)))
                false
            } }
            assertTrue(result.exceptionOrNull() is CancellationException)
            assertEquals(if (cancelMethod == "password") 1 else 2, calls)
        }
    }

    @Test fun rejectionAfterPasswordAndInteractiveEndsWithoutPasswordRetry() = runBlocking {
        val calls = mutableListOf<String>()
        var prompts = 0
        val auth = SshAuthentication(manager, { _, _, _, _ -> prompts++; listOf(entered, "") })
        assertFalse(auth.authenticate("session", profile(), snapshot(record(false, true))) {
            calls += it.supportedMethods.single()
            if ("password" in it.supportedMethods) it.password()
            false
        })
        assertEquals(listOf("password", "keyboard-interactive"), calls)
        assertEquals(0, prompts)
    }

    @Test fun wrongKeyPassphraseStopsBeforeAnyFallback() = runBlocking {
        val encrypted = manager.generateKey(SshKeyGenerationType.ED25519, "Ephemeral", UUID.randomUUID().toString())
        val record = record().copy(keyId = encrypted.id)
        var calls = 0
        val auth = SshAuthentication(manager, { _, title, _, _ ->
            assertEquals(SessionPromptText.App(SessionPromptTextKind.UNLOCK_KEY_TITLE, encrypted.label), title)
            listOf(entered, "")
        })
        val error = runCatching { auth.authenticate("session", profile(), VaultSnapshot(keys = listOf(encrypted), authentications = listOf(record))) {
            calls++; SshAuthenticationHandler(it, false) {}.onPublicKeysNeeded(); false
        } }.exceptionOrNull() as SshAuthenticationFailure
        assertEquals(SshAuthenticationFailure.Category.LOCAL_KEY, error.category)
        assertEquals(1, calls)
    }

    @Test fun protocolAndSignatureFailuresNeverFallBack() = runBlocking {
        for (category in listOf(SshAuthenticationFailure.Category.LOCAL_KEY, SshAuthenticationFailure.Category.PROTOCOL)) {
            var calls = 0
            val auth = SshAuthentication(manager, { _, _, _, _ -> error("Unexpected prompt") })
            val error = runCatching { auth.authenticate("session", profile(), snapshot(record(true, true))) {
                calls++; throw SshAuthenticationFailure(category, SshAuthenticationStage.KEY_SIGNING)
            } }.exceptionOrNull() as SshAuthenticationFailure
            assertEquals(category, error.category); assertEquals(1, calls)
        }
    }

    @Test fun brokenPrivateKeysAndCancelledUnlocksNeverReachPasswordAuthentication() = runBlocking {
        val encrypted = manager.generateKey(SshKeyGenerationType.ED25519, "Ephemeral", UUID.randomUUID().toString())
        for (cancel in listOf(false, true)) {
            val configured = if (cancel) encrypted else encrypted.copy(privateKeyPem = UUID.randomUUID().toString(), requiresPassphrase = false)
            val record = record().copy(keyId = configured.id)
            var calls = 0
            var prompts = 0
            val auth = SshAuthentication(manager, { _, _, _, _ -> prompts++; null })
            val failure = runCatching { auth.authenticate("session", profile(), VaultSnapshot(keys = listOf(configured), authentications = listOf(record))) {
                calls++; assertEquals(setOf("publickey"), it.supportedMethods)
                SshAuthenticationHandler(it, false) {}.onPublicKeysNeeded(); false
            } }.exceptionOrNull()
            assertNotNull(failure)
            if (cancel) assertTrue(failure is CancellationException)
            else assertTrue(failure is SshAuthenticationFailure || failure is IllegalArgumentException)
            assertTrue(calls <= 1)
            assertEquals(if (cancel) 1 else 0, prompts)
        }
    }

    @Test fun tailscaleSshUsesOnlyManualInteractive() = runBlocking {
        val auth = SshAuthentication(manager, { _, _, _, _ -> listOf(entered) })
        assertTrue(auth.authenticate("session", profile().copy(authentication = AuthenticationMethod.TAILSCALE_SSH), snapshot(record(true, true))) {
            assertEquals(setOf("keyboard-interactive"), it.supportedMethods)
            assertEquals(listOf(entered), it.interactive("", "", listOf(SshPromptField("Response", false)))); true
        })
    }

    @Test fun savingKeepsThePrivateKeyAndStorageFailuresKeepTheConnection() = runBlocking {
        val original = record(true)
        for (throwError in listOf(false, true)) {
            var notices = 0
            val auth = SshAuthentication(manager, { _, _, _, _ -> listOf(entered, AuthenticationField.TOGGLE_ON) }, savePassword = { host, password ->
                saveAcceptedPassword(host, original, password, persist = { _, copy ->
                    assertNotEquals(original.id, copy.id); assertEquals(original.keyId, copy.keyId)
                    assertEquals(entered, copy.password); assertEquals(host.label, copy.label)
                    if (throwError) throw java.io.IOException()
                    VaultMutationResult.StorageFailure
                }, onFailure = { notices++ })
            })
            assertTrue(auth.authenticate("session", profile(), snapshot(original)) {
                if ("publickey" in it.supportedMethods) false else { it.password(); true }
            })
            assertEquals(1, notices)
        }
    }

    @Test fun cancellationWhileSavingRemainsCancellation() = runBlocking {
        val result = runCatching { saveAcceptedPassword(profile(), null, entered, persist = { _, _ -> throw CancellationException() },
            onFailure = { error("Cancellation is not a storage error") }) }
        assertTrue(result.exceptionOrNull() is CancellationException)
    }
}
