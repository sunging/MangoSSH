package website.sung.mangossh.session.ssh

import java.security.KeyPair
import kotlinx.coroutines.CancellationException
import org.connectbot.sshlib.AuthHandler
import org.connectbot.sshlib.AuthPublicKey
import org.connectbot.sshlib.AuthResult
import org.connectbot.sshlib.KeyboardInteractiveCallback
import org.connectbot.sshlib.SshSigning
import website.sung.mangossh.core.MangoLogDetail

/** Fixed stages describe authentication without retaining remote text or credential material. */
internal enum class SshAuthenticationStage { DISCOVERY, KEY_LOADING, KEY_PROBE, KEY_SIGNING, PASSWORD, INTERACTIVE }

/** Never attaches the original exception: key parsers and protocol errors can contain secrets. */
internal class SshAuthenticationFailure(
    val category: Category,
    val stage: SshAuthenticationStage,
) : Exception(), MangoLogDetail {
    enum class Category { REJECTED, NO_MATCHING_METHOD, LOCAL_KEY, PROTOCOL }
    override val logDetail: String get() = "${category.name}/${stage.name}"
}

/** Adapts credentials and preserves failure categories across the library's result-based API. */
internal class SshAuthenticationHandler(
    private val credentials: SshCredentials,
    private val legacyAlgorithms: Boolean,
    private val banner: suspend (String) -> Unit,
) : AuthHandler {
    override val supportedMethods: Set<String> get() = credentials.supportedMethods
    override val preferPasswordAuth: Boolean get() = credentials.preferPasswordAuth
    var stage: SshAuthenticationStage = SshAuthenticationStage.DISCOVERY
        private set
    private var matchingMethod = false
    private var pair: KeyPair? = null

    override suspend fun onAuthMethodsAvailable(methods: Set<String>) {
        matchingMethod = methods.any { it in supportedMethods }
    }

    override suspend fun onPublicKeysNeeded(): List<AuthPublicKey> {
        stage = SshAuthenticationStage.KEY_LOADING
        return localKey {
            pair = credentials.key()
            pair?.let { listOf(SshSigning.encodePublicKey(it)) }.orEmpty()
        }.also { stage = SshAuthenticationStage.KEY_PROBE }
    }

    override suspend fun onSignatureRequest(key: AuthPublicKey, dataToSign: ByteArray): ByteArray? {
        stage = SshAuthenticationStage.KEY_SIGNING
        if (key.algorithmName == "ssh-rsa" && !legacyAlgorithms) return null
        return localKey { pair?.let { SshSigning.signWithKeyPair(key.algorithmName, it, dataToSign) } }
    }

    override suspend fun onPasswordNeeded(): String? {
        stage = SshAuthenticationStage.PASSWORD
        return credentials.password()
    }

    override suspend fun onKeyboardInteractivePrompt(
        name: String,
        instruction: String,
        prompts: List<KeyboardInteractiveCallback.Prompt>,
    ): List<String>? {
        stage = SshAuthenticationStage.INTERACTIVE
        return credentials.interactive(name, instruction, prompts.map { SshPromptField(it.text, it.echo) })
    }

    override suspend fun onBanner(message: String) {
        banner(message)
        credentials.banner(message)
    }

    /** Preserves library rejection results without collapsing local or protocol errors into rejection. */
    fun resolve(result: AuthResult): Boolean = when (result) {
        AuthResult.Success -> true
        is AuthResult.Failure -> if (matchingMethod) false else
            throw SshAuthenticationFailure(SshAuthenticationFailure.Category.NO_MATCHING_METHOD, stage)
        is AuthResult.Error -> throw (result.cause as? SshAuthenticationFailure
            ?: SshAuthenticationFailure(SshAuthenticationFailure.Category.PROTOCOL, stage))
    }

    private suspend fun <T> localKey(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw SshAuthenticationFailure(SshAuthenticationFailure.Category.LOCAL_KEY, stage)
    }
}
