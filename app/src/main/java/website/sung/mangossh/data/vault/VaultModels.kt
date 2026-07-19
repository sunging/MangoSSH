package website.sung.mangossh.data.vault

import androidx.compose.runtime.Immutable
import website.sung.mangossh.domain.ConnectionProfile

@Immutable
data class VaultSnapshot(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val profiles: List<ConnectionProfile> = emptyList(),
    val keys: List<StoredSshKey> = emptyList(),
    val knownHosts: List<TrustedHostKey> = emptyList(),
    val snippets: List<CommandSnippet> = emptyList(),
    val portForwards: List<PortForwardRule> = emptyList(),
    val webDavConfig: WebDavConfig? = null,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 3
    }
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
) {
    override fun toString(): String =
        "StoredSshKey(id=$id, label=$label, algorithm=$algorithm, fingerprint=$fingerprint, privateKeyPem=<redacted>)"
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

enum class PortForwardType(val label: String) {
    LOCAL("本地"),
    REMOTE("远程"),
    DYNAMIC("SOCKS5"),
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

    data class Failed(val userMessage: String) : VaultStatus
}
