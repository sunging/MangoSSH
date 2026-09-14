package website.sung.mangossh.session

import com.trilead.ssh2.Connection
import com.trilead.ssh2.InteractiveCallback
import website.sung.mangossh.data.keys.SshKeyManager
import website.sung.mangossh.data.vault.VaultSnapshot
import website.sung.mangossh.domain.AuthenticationMethod
import website.sung.mangossh.domain.ConnectionProfile

/** Blocking authentication runs on the connection dispatcher; prompts remain owned by its lifecycle. */
internal class SshAuthentication(private val keyManager: SshKeyManager,
    private val prompt: (String, SessionPromptText, SessionPromptText?, List<AuthenticationField>) -> List<String>?) {
    private fun requestAuthentication(sessionId: String, title: SessionPromptText,
        instruction: SessionPromptText?, fields: List<AuthenticationField>) = prompt(sessionId, title, instruction, fields)

    fun authenticate(
        connection: Connection,
        sessionId: String,
        profile: ConnectionProfile,
        snapshot: VaultSnapshot,
    ): Boolean {
        if (profile.authentication == AuthenticationMethod.TAILSCALE_SSH && connection.authenticateWithNone(profile.username)) {
            return true
        }
        return when (profile.authentication) {
        AuthenticationMethod.PASSWORD -> {
            val password = requestAuthentication(
                sessionId = sessionId,
                title = SessionPromptText.App(SessionPromptTextKind.PASSWORD_TITLE, profile.label),
                instruction = SessionPromptText.App(SessionPromptTextKind.PASSWORD_INSTRUCTION),
                fields = listOf(
                    AuthenticationField(
                        SessionPromptText.App(SessionPromptTextKind.PASSWORD_FIELD),
                        echo = false,
                    ),
                ),
            )?.firstOrNull() ?: return false
            connection.authenticateWithPassword(profile.username, password)
        }

        AuthenticationMethod.PRIVATE_KEY -> {
            val key = profile.keyId?.let { keyId -> snapshot.keys.firstOrNull { it.id == keyId } }
                ?: throw SshAuthenticationException()
            val passphrase = if (key.requiresPassphrase) {
                requestAuthentication(
                    sessionId = sessionId,
                    title = SessionPromptText.App(SessionPromptTextKind.UNLOCK_KEY_TITLE, key.label),
                    instruction = SessionPromptText.App(SessionPromptTextKind.KEY_PASSPHRASE_INSTRUCTION),
                    fields = listOf(
                        AuthenticationField(
                            SessionPromptText.App(SessionPromptTextKind.KEY_PASSPHRASE_FIELD),
                            echo = false,
                        ),
                    ),
                )?.firstOrNull() ?: return false
            } else {
                null
            }
            connection.authenticateWithPublicKey(
                profile.username,
                keyManager.decodeKeyPair(key, passphrase),
            )
        }

        AuthenticationMethod.KEYBOARD_INTERACTIVE,
        AuthenticationMethod.TAILSCALE_SSH,
        -> connection.authenticateWithKeyboardInteractive(
            profile.username,
            InteractiveCallback { name, instruction, numberOfPrompts, prompts, echo ->
                val fields = (0 until numberOfPrompts).map { index ->
                    AuthenticationField(SessionPromptText.Verbatim(prompts[index]), echo[index])
                }
                requestAuthentication(
                    sessionId = sessionId,
                    title = name.takeIf(String::isNotBlank)
                        ?.let(SessionPromptText::Verbatim)
                        ?: SessionPromptText.App(
                            if (profile.authentication == AuthenticationMethod.TAILSCALE_SSH) {
                                SessionPromptTextKind.TAILSCALE_LOGIN_TITLE
                            } else {
                                SessionPromptTextKind.INTERACTIVE_LOGIN_TITLE
                            },
                        ),
                    instruction = instruction.takeIf(String::isNotBlank)
                        ?.let(SessionPromptText::Verbatim),
                    fields = fields,
                )?.takeIf { it.size == numberOfPrompts }?.toTypedArray() ?: emptyArray()
            },
        )
        }
    }

}

/** Sanitized authentication failure; never retains credentials or remote responses. */
internal class SshAuthenticationException : Exception()
