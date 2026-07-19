package website.sung.mangossh.data.vault

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import website.sung.mangossh.domain.ConnectionProfile
import website.sung.mangossh.core.MangoLog
import website.sung.mangossh.core.MangoLogEvent

/** Coordinates encrypted vault reads and serialized mutations on the I/O dispatcher. */
class VaultRepository(context: Context) {
    private val storage = AndroidKeystoreVault(context)
    private val mutationMutex = Mutex()

    private val _snapshot = MutableStateFlow(VaultSnapshot())
    val snapshot = _snapshot.asStateFlow()

    private val _status = MutableStateFlow<VaultStatus>(VaultStatus.Loading)
    val status = _status.asStateFlow()

    /** Opens the Android Keystore-protected vault without exposing plaintext outside this repository. */
    suspend fun open() = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            _status.value = VaultStatus.Loading
            MangoLog.info(MangoLogEvent.VAULT_OPEN_STARTED)
            try {
                _snapshot.value = storage.read() ?: VaultSnapshot()
                _status.value = VaultStatus.Ready
                MangoLog.info(MangoLogEvent.VAULT_OPEN_SUCCEEDED)
            } catch (error: Exception) {
                _status.value = VaultStatus.Failed("无法打开本地加密保险库。请检查设备安全设置。")
                MangoLog.warn(MangoLogEvent.VAULT_OPEN_FAILED, error)
            }
        }
    }

    suspend fun upsertProfile(profile: ConnectionProfile) = mutate { snapshot ->
        val profiles = snapshot.profiles.filterNot { it.id == profile.id }.plus(profile)
        snapshot.copy(profiles = profiles)
    }

    suspend fun removeProfile(id: String) = mutate { snapshot ->
        snapshot.copy(
            profiles = snapshot.profiles.filterNot { it.id == id },
            portForwards = snapshot.portForwards.filterNot { it.profileId == id },
        )
    }

    suspend fun upsertKey(key: StoredSshKey) = mutate { snapshot ->
        snapshot.copy(keys = snapshot.keys.filterNot { it.id == key.id }.plus(key))
    }

    suspend fun removeKey(id: String) = mutate { snapshot ->
        snapshot.copy(
            keys = snapshot.keys.filterNot { it.id == id },
            profiles = snapshot.profiles.map { profile ->
                if (profile.keyId == id) profile.copy(keyId = null) else profile
            },
        )
    }

    suspend fun trustHostKey(hostKey: TrustedHostKey) = mutate { snapshot ->
        snapshot.copy(
            knownHosts = snapshot.knownHosts
                .filterNot {
                    it.hostname == hostKey.hostname &&
                        it.port == hostKey.port &&
                        it.algorithm == hostKey.algorithm
                }
                .plus(hostKey),
        )
    }

    suspend fun upsertSnippet(snippet: CommandSnippet) = mutate { snapshot ->
        snapshot.copy(snippets = snapshot.snippets.filterNot { it.id == snippet.id }.plus(snippet))
    }

    suspend fun removeSnippet(id: String) = mutate { snapshot ->
        snapshot.copy(
            snippets = snapshot.snippets.filterNot { it.id == id },
            profiles = snapshot.profiles.map { profile ->
                if (profile.startupSnippetId == id) profile.copy(startupSnippetId = null) else profile
            },
        )
    }

    suspend fun upsertPortForward(rule: PortForwardRule) = mutate { snapshot ->
        snapshot.copy(portForwards = snapshot.portForwards.filterNot { it.id == rule.id }.plus(rule))
    }

    suspend fun removePortForward(id: String) = mutate { snapshot ->
        snapshot.copy(portForwards = snapshot.portForwards.filterNot { it.id == id })
    }

    suspend fun saveWebDavConfig(config: WebDavConfig?) = mutate { snapshot ->
        snapshot.copy(webDavConfig = config)
    }

    suspend fun exportPortable(passphrase: CharArray): ByteArray = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            check(_status.value is VaultStatus.Ready) { "Vault is not ready" }
            PortableVaultCodec.encrypt(_snapshot.value, passphrase)
        }
    }

    suspend fun importPortable(bytes: ByteArray, passphrase: CharArray) = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            check(_status.value is VaultStatus.Ready) { "Vault is not ready" }
            val imported = PortableVaultCodec.decrypt(bytes, passphrase)
            storage.write(imported)
            _snapshot.value = imported
        }
    }

    /**
     * Applies one mutation atomically and reports whether the encrypted write
     * committed. The caller must not claim success when persistence failed:
     * generated private keys are intentionally retained only after this method
     * returns `true`.
     */
    private suspend fun mutate(transform: (VaultSnapshot) -> VaultSnapshot): Boolean = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            if (_status.value !is VaultStatus.Ready) return@withLock false
            val updated = transform(_snapshot.value)
            try {
                storage.write(updated)
                _snapshot.value = updated
                MangoLog.info(MangoLogEvent.VAULT_WRITE_SUCCEEDED)
                true
            } catch (error: Exception) {
                _status.value = VaultStatus.Failed("无法保存加密保险库。数据未被覆盖。")
                MangoLog.warn(MangoLogEvent.VAULT_WRITE_FAILED, error)
                false
            }
        }
    }
}
