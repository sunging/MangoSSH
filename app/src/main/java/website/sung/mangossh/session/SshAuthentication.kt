package website.sung.mangossh.session

import kotlinx.coroutines.CancellationException
import java.util.UUID
import website.sung.mangossh.session.ssh.SshConnection
import website.sung.mangossh.session.ssh.SshCredentials
import website.sung.mangossh.session.ssh.SshPromptField
import website.sung.mangossh.data.keys.SshKeyManager
import website.sung.mangossh.data.vault.StoredSshKey
import website.sung.mangossh.data.vault.StoredAuthentication
import website.sung.mangossh.session.ssh.SshAuthenticationFailure
import website.sung.mangossh.data.vault.VaultMutationResult
import website.sung.mangossh.data.vault.VaultSnapshot
import website.sung.mangossh.domain.AuthenticationMethod
import website.sung.mangossh.domain.ConnectionProfile

/**
 * One transport tries private key, ordinary password, then manual interactive authentication.
 * Cancelling a prompt cancels its authentication; local key errors never enable a fallback.
 * [savePassword] receives only an opted-in password after successful SSH authentication.
 * [savePassphrase] stores (or, with null, clears) a key's remembered passphrase after the user
 * chose so in the unlock prompt; it is only called with a passphrase that just decrypted the key.
 */
internal class SshAuthentication(private val keyManager: SshKeyManager,
    private val prompt: (String, SessionPromptText, SessionPromptText?, List<AuthenticationField>) -> List<String>?,
    private val savePassword: suspend (ConnectionProfile, String) -> Unit = { _, _ -> },
    private val savePassphrase: suspend (StoredSshKey, String?) -> Unit = { _, _ -> }) {
    /** Authenticates the live transport and saves only explicitly remembered, accepted passwords. */
    suspend fun authenticate(connection: SshConnection, sessionId: String, profile: ConnectionProfile, snapshot: VaultSnapshot): Boolean =
        authenticate(sessionId, profile, snapshot) { connection.authenticate(profile.username, it) }

    /** The exchange seam allows tests to exercise success, rejection, cancellation and save timing. */
    internal suspend fun authenticate(sessionId: String, profile: ConnectionProfile, snapshot: VaultSnapshot,
        exchange: suspend (SshCredentials) -> Boolean): Boolean {
        val authentication = snapshot.authentications.firstOrNull { it.id == profile.authenticationId }
        check(profile.authenticationId == null || authentication != null)
        val key = authentication?.keyId?.let { id -> requireNotNull(snapshot.keys.firstOrNull { it.id == id }) }
        if (profile.authentication != AuthenticationMethod.TAILSCALE_SSH && key?.algorithm == "ssh-dss")
            throw website.sung.mangossh.data.keys.UnsupportedDsaKeyException()
        if (profile.authentication != AuthenticationMethod.TAILSCALE_SSH && key != null) {
            keyManager.requireSupportedEncryption(key.privateKeyPem)
        }
        val methods = if (profile.authentication == AuthenticationMethod.TAILSCALE_SSH) listOf("keyboard-interactive")
            else listOfNotNull("publickey".takeIf { key != null }, "password", "keyboard-interactive")
        var rejection = false
        var unavailable: SshAuthenticationFailure? = null
        for (method in methods) {
            val offered = ProfileCredentials(sessionId, profile, snapshot, method)
            try {
                val success = try { exchange(offered) } catch (failure: SshAuthenticationFailure) {
                    if (failure.category != SshAuthenticationFailure.Category.NO_MATCHING_METHOD) throw failure
                    unavailable = failure
                    continue
                }
                if (success) {
                    // A successful interactive phase must never save a rejected password answer.
                    if (method == "password") offered.passwordToSave?.let { savePassword(profile, it) }
                    return true
                }
                rejection = true
            } finally {
                offered.forgetPassword()
            }
        }
        if (!rejection && unavailable != null) throw unavailable
        return false
    }

    /** The credentials offered for [profile]; each callback prompts only when the server asks for it. */
    internal fun credentials(sessionId: String, profile: ConnectionProfile, snapshot: VaultSnapshot): SshCredentials =
        ProfileCredentials(sessionId, profile, snapshot, if (snapshot.authentications.any { it.id == profile.authenticationId && it.keyId != null }) "publickey" else "password")

    /** Owns pending secrets for one exchange, so rejected or cancelled answers cannot be saved. */
    private inner class ProfileCredentials(private val sessionId: String, private val profile: ConnectionProfile,
        private val snapshot: VaultSnapshot, private val method: String) : SshCredentials {
        private val authentication = snapshot.authentications.firstOrNull { it.id == profile.authenticationId }
        override val supportedMethods: Set<String> = setOf(method)
        override val preferPasswordAuth: Boolean = true
        var passwordToSave: String? = null
            private set

        fun forgetPassword() { passwordToSave = null }

        override suspend fun password(): String? {
            if (method != "password") return null
            authentication?.password?.let { return it }
            val instruction = if (authentication?.keyId != null) SessionPromptTextKind.KEY_PASSWORD_FALLBACK_INSTRUCTION
                else SessionPromptTextKind.PASSWORD_INSTRUCTION
            val answers = ask(SessionPromptText.App(SessionPromptTextKind.PASSWORD_TITLE, profile.label),
                SessionPromptText.App(instruction),
                listOf(AuthenticationField(SessionPromptText.App(SessionPromptTextKind.PASSWORD_FIELD), false),
                    AuthenticationField(SessionPromptText.App(SessionPromptTextKind.REMEMBER_PASSWORD), true, toggle = true)))
            passwordToSave = answers[0].takeIf { answers[1] == AuthenticationField.TOGGLE_ON }
            return answers[0]
        }
        override suspend fun key(): java.security.KeyPair? {
            if (method != "publickey") return null
            val stored = snapshot.keys.firstOrNull { it.id == authentication?.keyId } ?: throw IllegalStateException()
            if (stored.algorithm == "ssh-dss") throw website.sung.mangossh.data.keys.UnsupportedDsaKeyException()
            // A remembered passphrase skips the prompt; if it no longer decrypts, ask instead.
            val stale = stored.savedPassphrase != null
            if (stale) {
                runCatching { keyManager.decodeKeyPair(stored) }.getOrNull()?.let { return it }
            }
            if (!stored.requiresPassphrase) return keyManager.decodeKeyPair(stored)
            val answers = ask(
                SessionPromptText.App(SessionPromptTextKind.UNLOCK_KEY_TITLE, stored.label),
                SessionPromptText.App(SessionPromptTextKind.KEY_PASSPHRASE_INSTRUCTION),
                listOf(
                    AuthenticationField(SessionPromptText.App(SessionPromptTextKind.KEY_PASSPHRASE_FIELD), false),
                    AuthenticationField(SessionPromptText.App(SessionPromptTextKind.REMEMBER_KEY_PASSPHRASE), true,
                        toggle = true, initiallyChecked = stale),
                ))
            val passphrase = answers[0]
            val keyPair = keyManager.decodeKeyPair(stored, passphrase)
            // Save only a passphrase proven above; unticking replaces a stale one with nothing.
            val remember = answers[1] == AuthenticationField.TOGGLE_ON
            if (remember || stale) savePassphrase(stored, passphrase.takeIf { remember })
            return keyPair
        }
        override suspend fun interactive(name: String, instruction: String, fields: List<SshPromptField>): List<String>? {
            if ("keyboard-interactive" !in supportedMethods) return null
            val serverInstruction = instruction.takeIf(String::isNotBlank)?.let(SessionPromptText::Verbatim)
            val instructions = if (profile.authentication != AuthenticationMethod.TAILSCALE_SSH)
                SessionPromptText.Joined(listOfNotNull(SessionPromptText.App(SessionPromptTextKind.INTERACTIVE_FALLBACK_INSTRUCTION), serverInstruction))
            else serverInstruction
            return ask(name.takeIf(String::isNotBlank)?.let(SessionPromptText::Verbatim) ?: SessionPromptText.App(
                if (profile.authentication == AuthenticationMethod.TAILSCALE_SSH) SessionPromptTextKind.TAILSCALE_LOGIN_TITLE
                else SessionPromptTextKind.INTERACTIVE_LOGIN_TITLE),
                instructions,
                fields.map { AuthenticationField(SessionPromptText.Verbatim(it.text), it.echo) })
        }
        private fun ask(title: SessionPromptText, instruction: SessionPromptText?, fields: List<AuthenticationField>): List<String> =
            prompt(sessionId, title, instruction, fields)?.takeIf { it.size == fields.size } ?: throw CancellationException("Authentication cancelled")
    }
}

/** The automatic policy is implemented as separate single-method exchanges on one transport. */
internal fun AuthenticationMethod.sshMethods(): Set<String> = when (this) {
    AuthenticationMethod.AUTOMATIC -> setOf("publickey", "password", "keyboard-interactive")
    AuthenticationMethod.TAILSCALE_SSH -> setOf("keyboard-interactive")
}

/** Sanitized authentication failure; never retains credentials or remote responses. */
internal class SshAuthenticationException : Exception()

/**
 * Persistence is a convenience after authentication, so a failed write reports a notice rather
 * than failing the live transport. Cancellation still belongs to the owning connection task.
 * A new id keeps accepted retry answers from overwriting an existing shared password.
 */
internal suspend fun saveAcceptedPassword(profile: ConnectionProfile, authentication: StoredAuthentication?, password: String,
    persist: suspend (ConnectionProfile, StoredAuthentication) -> VaultMutationResult, onFailure: () -> Unit) {
    val saved = try {
        persist(profile, StoredAuthentication(UUID.randomUUID().toString(), profile.label.ifBlank { profile.hostname }, password,
            authentication?.keyId)).isSuccess
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: Exception) { false }
    if (!saved) onFailure()
}
