package website.sung.mangossh.session

import kotlinx.coroutines.CancellationException
import website.sung.mangossh.session.ssh.SshConnection
import website.sung.mangossh.session.ssh.SshCredentials
import website.sung.mangossh.session.ssh.SshPromptField
import website.sung.mangossh.data.keys.SshKeyManager
import website.sung.mangossh.data.vault.VaultSnapshot
import website.sung.mangossh.domain.AuthenticationMethod
import website.sung.mangossh.domain.ConnectionProfile

/** Only configured credentials may be offered; cancelling a prompt cancels its authentication. */
internal class SshAuthentication(private val keyManager: SshKeyManager,
    private val prompt: (String, SessionPromptText, SessionPromptText?, List<AuthenticationField>) -> List<String>?) {
    suspend fun authenticate(connection: SshConnection, sessionId: String, profile: ConnectionProfile, snapshot: VaultSnapshot): Boolean {
        if (profile.authentication == AuthenticationMethod.PRIVATE_KEY &&
            snapshot.keys.any { it.id == profile.keyId && it.algorithm == "ssh-dss" })
            throw website.sung.mangossh.data.keys.UnsupportedDsaKeyException()
        if (profile.authentication == AuthenticationMethod.PRIVATE_KEY) {
            snapshot.keys.firstOrNull { it.id == profile.keyId }?.let { keyManager.requireSupportedEncryption(it.privateKeyPem) }
        }
        return connection.authenticate(profile.username, object : SshCredentials {
            override val supportedMethods: Set<String> = profile.authentication.sshMethods()
            override val preferPasswordAuth: Boolean = profile.authentication == AuthenticationMethod.PASSWORD
            override suspend fun password(): String? {
                if (profile.authentication != AuthenticationMethod.PASSWORD) return null
                return ask(SessionPromptText.App(SessionPromptTextKind.PASSWORD_TITLE, profile.label),
                    SessionPromptText.App(SessionPromptTextKind.PASSWORD_INSTRUCTION),
                    listOf(AuthenticationField(SessionPromptText.App(SessionPromptTextKind.PASSWORD_FIELD), false))).single()
            }
            override suspend fun key(): java.security.KeyPair? {
                if (profile.authentication != AuthenticationMethod.PRIVATE_KEY) return null
                val stored = snapshot.keys.firstOrNull { it.id == profile.keyId } ?: throw IllegalStateException()
                if (stored.algorithm == "ssh-dss") throw website.sung.mangossh.data.keys.UnsupportedDsaKeyException()
                // A remembered passphrase skips the prompt; if it no longer decrypts, ask instead.
                if (stored.savedPassphrase != null) {
                    runCatching { keyManager.decodeKeyPair(stored) }.getOrNull()?.let { return it }
                }
                val passphrase = if (stored.requiresPassphrase) ask(
                    SessionPromptText.App(SessionPromptTextKind.UNLOCK_KEY_TITLE, stored.label),
                    SessionPromptText.App(SessionPromptTextKind.KEY_PASSPHRASE_INSTRUCTION),
                    listOf(AuthenticationField(SessionPromptText.App(SessionPromptTextKind.KEY_PASSPHRASE_FIELD), false))).single() else null
                return keyManager.decodeKeyPair(stored, passphrase)
            }
            override suspend fun interactive(name: String, instruction: String, fields: List<SshPromptField>): List<String>? {
                if ("keyboard-interactive" !in supportedMethods) return null
                return ask(name.takeIf(String::isNotBlank)?.let(SessionPromptText::Verbatim) ?: SessionPromptText.App(
                    if (profile.authentication == AuthenticationMethod.TAILSCALE_SSH) SessionPromptTextKind.TAILSCALE_LOGIN_TITLE
                    else SessionPromptTextKind.INTERACTIVE_LOGIN_TITLE),
                    instruction.takeIf(String::isNotBlank)?.let(SessionPromptText::Verbatim),
                    fields.map { AuthenticationField(SessionPromptText.Verbatim(it.text), it.echo) })
            }
            private fun ask(title: SessionPromptText, instruction: SessionPromptText?, fields: List<AuthenticationField>): List<String> =
                prompt(sessionId, title, instruction, fields)?.takeIf { it.size == fields.size } ?: throw CancellationException("Authentication cancelled")
        })
    }
}

/** Password fallback is explicit user input; key profiles never offer other credentials. */
internal fun AuthenticationMethod.sshMethods(): Set<String> = when (this) {
    AuthenticationMethod.PRIVATE_KEY -> setOf("publickey")
    AuthenticationMethod.PASSWORD -> setOf("password", "keyboard-interactive")
    AuthenticationMethod.KEYBOARD_INTERACTIVE, AuthenticationMethod.TAILSCALE_SSH -> setOf("keyboard-interactive")
}

/** Sanitized authentication failure; never retains credentials or remote responses. */
internal class SshAuthenticationException : Exception()
