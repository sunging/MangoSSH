package website.sung.mangossh.data.vault

import androidx.compose.runtime.Immutable
import website.sung.mangossh.domain.ConnectionProfile
import website.sung.mangossh.data.keys.canChangePassphrase

@Immutable
data class VaultSnapshot(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val profiles: List<ConnectionProfile> = emptyList(),
    val keys: List<StoredSshKey> = emptyList(),
    val knownHosts: List<TrustedHostKey> = emptyList(),
    val snippets: List<CommandSnippet> = emptyList(),
    val portForwards: List<PortForwardRule> = emptyList(),
    val webDavConfig: WebDavConfig? = null,
    val authentications: List<StoredAuthentication> = emptyList(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 8
    }
}

/** Reusable credentials protected by whole-vault encryption; keys retain their stable agent IDs. */
@Immutable
data class StoredAuthentication(
    val id: String,
    val label: String,
    val password: String? = null,
    val keyId: String? = null,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
) {
    override fun toString(): String = "StoredAuthentication(id=$id, credentials=<redacted>)"

    /** Only this non-secret projection may be supplied to credential pickers and editors. */
    fun summary(): AuthenticationSummary = AuthenticationSummary(id, label, keyId, password != null)
}

/** Credential metadata only: neither passwords, private keys nor their passphrases enter UI state. */
@Immutable
data class AuthenticationSummary(val id: String, val label: String, val keyId: String? = null, val hasPassword: Boolean = false) {
    override fun toString(): String = "AuthenticationSummary(id=$id, metadata=<redacted>)"
}

/** A private key record. The whole record is protected by the encrypted vault. */
@Immutable
data class StoredSshKey(
    val id: String,
    val label: String,
    val algorithm: String,
    val publicKey: String,
    val fingerprint: String,
    val privateKeyPem: String,
    val requiresPassphrase: Boolean = false,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    /**
     * The passphrase for [privateKeyPem], kept only when the user chose to remember it.
     * It is protected by the same vault encryption as the private key and is never shown or logged.
     */
    val savedPassphrase: String? = null,
) {
    override fun toString(): String =
        "StoredSshKey(id=$id, label=$label, algorithm=$algorithm, fingerprint=$fingerprint, privateKeyPem=<redacted>)"

    /** Supplies public metadata to key controls without handing private material to Compose. */
    fun summary(): SshKeySummary = SshKeySummary(id, label, algorithm, fingerprint, requiresPassphrase,
        savedPassphrase != null, canChangePassphrase())
}

/** Display-only key metadata, including capability flags calculated behind the vault boundary. */
@Immutable
data class SshKeySummary(val id: String, val label: String, val algorithm: String, val fingerprint: String,
    val requiresPassphrase: Boolean = false, val hasSavedPassphrase: Boolean = false, val canChangePassphrase: Boolean = true) {
    override fun toString(): String = "SshKeySummary(id=$id, metadata=<redacted>)"
}

/** Temporary backend-owned key edit; never place this object in UI state or a saved-state bundle. */
internal class StagedKeyChange(val key: StoredSshKey, val original: StoredSshKey? = null) {
    override fun toString(): String = "StagedKeyChange(<redacted>)"
}

@Immutable
data class TrustedHostKey(
    val hostname: String,
    val port: Int,
    val algorithm: String,
    val keyBlobBase64: String,
    val fingerprint: String,
    val trustedAtEpochMillis: Long = System.currentTimeMillis(),
)

@Immutable
data class CommandSnippet(
    val id: String,
    val label: String,
    val script: String,
    val appendNewline: Boolean = true,
)

enum class PortForwardType {
    LOCAL,
    REMOTE,
    DYNAMIC,
}

@Immutable
data class PortForwardRule(
    val id: String,
    val profileId: String,
    val type: PortForwardType,
    val bindHost: String = "127.0.0.1",
    val bindPort: Int,
    val destinationHost: String? = null,
    val destinationPort: Int? = null,
    val startOnConnect: Boolean = false,
)

/** Credentials are encrypted with the same local vault as host profiles and keys. */
@Immutable
data class WebDavConfig(
    val endpoint: String,
    val username: String,
    val password: String,
    val remoteFileName: String = "mangossh-vault.mssh",
) {
    override fun toString(): String =
        "WebDavConfig(endpoint=$endpoint, username=$username, password=<redacted>, remoteFileName=$remoteFileName)"
}

sealed interface VaultStatus {
    data object Loading : VaultStatus

    data object Ready : VaultStatus

    data class Failed(val reason: VaultFailureReason) : VaultStatus
}

/** Sanitized operation category for a vault failure exposed to presentation. */
enum class VaultFailureReason {
    OPEN,
    WRITE,
}
