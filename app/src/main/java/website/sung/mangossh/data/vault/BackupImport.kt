package website.sung.mangossh.data.vault

import java.util.UUID

/** Shared pre-allocation bounds for file, HTTP and local encrypted backup reads. */
internal object BackupLimits {
    const val MAX_FILE_BYTES = 16 * 1024 * 1024

    fun readLimited(input: java.io.InputStream, limit: Int = MAX_FILE_BYTES): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > limit) throw BackupException(BackupFailure.TOO_LARGE)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}

/** Sanitized backup failures; never carry payload contents into presentation or logs. */
enum class BackupFailure { VERSION, INVALID, TOO_LARGE, AUTHENTICATION, STORAGE, CHANGED, LOCKED, NETWORK, UNSAFE_SERVER, PARTIAL }

/** Boundary exception containing only a fixed category. */
class BackupException(val reason: BackupFailure) : Exception(reason.name)

/** Authenticated metadata; legacy archives have no metadata. */
data class BackupMetadata(val id: String, val createdAt: Long, val appVersion: String)

/** Decrypted data stays inside the backup coordinator. */
internal class BackupArchive(val snapshot: VaultSnapshot, val metadata: BackupMetadata?)

/** An opaque conflict handle. No host identifiers, scripts or key material are exposed. */
data class ImportConflict(val token: String, val kind: String, val ordinal: Int, val trust: Boolean = false, val label: String = "") {
    override fun toString(): String = "ImportConflict(kind=$kind, ordinal=$ordinal, trust=$trust)"
}

/** Safe summary of a prepared operation bound to a repository revision. */
data class ImportPreview(
    val operationId: String,
    val revision: Long,
    val added: Int,
    val unchanged: Int,
    val removedByReplacement: Int,
    val conflicts: List<ImportConflict>,
    val hasWebDavConfig: Boolean,
    val metadata: BackupMetadata?,
)

/** Trust conflicts require individual decisions, including in replacement mode. */
data class ImportDecision(
    val replace: Boolean = false,
    val useIncoming: Set<String> = emptySet(),
    val restoreWebDav: Boolean = false,
    val operationId: String = "",
)

/** A changed repository must be previewed again before writing. */
sealed interface ImportResult {
    data object Saved : ImportResult
    data class Changed(val preview: ImportPreview) : ImportResult
}

/** Pure, ID-based merge. Ordering and usage history belong to the receiving device. */
internal object BackupMerger {
    private fun hostId(host: TrustedHostKey) = listOf(host.hostname, host.port.toString(), host.algorithm)

    /** Builds opaque conflict handles in incoming order, ignoring device-owned usage differences. */
    fun preview(local: VaultSnapshot, archive: BackupArchive, revision: Long, operationId: String = UUID.randomUUID().toString()): ImportPreview {
        val conflicts = mutableListOf<ImportConflict>()
        var added = 0
        var unchanged = 0
        var removed = 0
        val profileLabels = archive.snapshot.profiles.associate { it.id to it.label }
        fun <T, K> scan(kind: String, old: List<T>, incoming: List<T>, id: (T) -> K, equivalent: (T, T) -> Boolean = { a, b -> a == b }) {
            val existing = old.associateBy(id)
            incoming.forEachIndexed { index, item ->
                val previous = existing[id(item)]
                when {
                    previous == null -> added++
                    equivalent(previous, item) -> unchanged++
                    else -> conflicts += ImportConflict("$kind:$index", kind, index + 1, kind == "trust", when (item) {
                        is website.sung.mangossh.domain.ConnectionProfile -> item.label
                        is StoredSshKey -> item.label
                        is CommandSnippet -> item.label
                        is PortForwardRule -> profileLabels[item.profileId].orEmpty()
                        is TrustedHostKey -> "${item.hostname}:${item.port}"
                        else -> ""
                    })
                }
            }
            val ids = incoming.map(id).toSet()
            removed += old.count { id(it) !in ids }
        }
        val remote = archive.snapshot
        scan("profile", local.profiles, remote.profiles, { it.id }) { a, b ->
            a == b.copy(position = a.position, connectionCount = a.connectionCount, lastConnectedAtEpochMillis = a.lastConnectedAtEpochMillis)
        }
        scan("key", local.keys, remote.keys, { it.id })
        scan("snippet", local.snippets, remote.snippets, { it.id })
        scan("forward", local.portForwards, remote.portForwards, { it.id })
        scan("trust", local.knownHosts, remote.knownHosts, ::hostId) { a, b -> a.keyBlobBase64 == b.keyBlobBase64 }
        return ImportPreview(operationId, revision, added, unchanged, removed, conflicts, remote.webDavConfig != null, archive.metadata)
    }

    /** Applies explicit choices and rejects any resulting dangling references. */
    fun merge(local: VaultSnapshot, remote: VaultSnapshot, decision: ImportDecision): VaultSnapshot {
        fun <T, K> combine(kind: String, old: List<T>, incoming: List<T>, id: (T) -> K): List<T> {
            val incomingIds = incoming.map(id).toSet()
            val result = old.filter { !decision.replace || id(it) in incomingIds }.associateByTo(linkedMapOf(), id)
            incoming.forEachIndexed { index, item ->
                val key = id(item)
                if (key !in result || (decision.replace && kind != "trust") || "$kind:$index" in decision.useIncoming) result[key] = item
            }
            return result.values.toList()
        }
        val localProfileIds = local.profiles.mapTo(hashSetOf()) { it.id }
        val mergedProfiles = combine("profile", local.profiles, remote.profiles, { it.id })
        val profiles = if (decision.replace) remote.profiles.sortedBy { it.position } else {
            val existing = mergedProfiles.associateBy { it.id }
            local.profiles.sortedBy { it.position }.map { original ->
                existing.getValue(original.id).copy(connectionCount = original.connectionCount, lastConnectedAtEpochMillis = original.lastConnectedAtEpochMillis)
            } + remote.profiles.sortedBy { it.position }.filter { it.id !in localProfileIds }
        }
        return VaultSnapshot(
            profiles = profiles.mapIndexed { index, profile -> profile.copy(position = index) },
            keys = combine("key", local.keys, remote.keys, { it.id }),
            snippets = combine("snippet", local.snippets, remote.snippets, { it.id }),
            portForwards = combine("forward", local.portForwards, remote.portForwards, { it.id }),
            knownHosts = combine("trust", local.knownHosts, remote.knownHosts, ::hostId),
            webDavConfig = if (decision.restoreWebDav && remote.webDavConfig != null) remote.webDavConfig else local.webDavConfig,
        ).also(BackupValidator::validate)
    }
}

/** Rejects ambiguous records and dangling references before any persistent mutation. */
internal object BackupValidator {
    /** Validates the complete graph before it may replace or augment the current vault. */
    fun validate(snapshot: VaultSnapshot) {
        fun valid(condition: Boolean) { if (!condition) throw BackupException(BackupFailure.INVALID) }
        fun <T> unique(items: List<T>, id: (T) -> String) {
            val ids = items.map(id)
            valid(ids.all { it.isNotBlank() } && ids.distinct().size == ids.size)
        }
        unique(snapshot.profiles) { it.id }
        unique(snapshot.keys) { it.id }
        unique(snapshot.snippets) { it.id }
        unique(snapshot.portForwards) { it.id }
        valid(snapshot.knownHosts.map { Triple(it.hostname, it.port, it.algorithm) }.distinct().size == snapshot.knownHosts.size)
        val keys = snapshot.keys.map { it.id }.toSet()
        val snippets = snapshot.snippets.map { it.id }.toSet()
        val profiles = snapshot.profiles.map { it.id }.toSet()
        snapshot.profiles.forEach {
            valid(it.hostname.isNotBlank() && it.username.isNotBlank() && it.port in 1..65535)
            valid(it.keyId == null || it.keyId in keys)
            valid(it.startupSnippetId == null || it.startupSnippetId in snippets)
            valid(it.position >= 0 && it.connectionCount >= 0 && it.lastConnectedAtEpochMillis >= 0)
        }
        snapshot.keys.forEach { valid(it.privateKeyPem.isNotBlank() && it.publicKey.isNotBlank() && it.algorithm.isNotBlank() && it.createdAtEpochMillis >= 0) }
        snapshot.knownHosts.forEach { valid(it.hostname.isNotBlank() && it.port in 1..65535 && it.algorithm.isNotBlank() && it.keyBlobBase64.isNotBlank() && it.trustedAtEpochMillis >= 0) }
        snapshot.portForwards.forEach {
            valid(it.profileId in profiles && it.bindPort in 1..65535 && it.bindHost.isNotBlank())
            valid(it.destinationPort == null || it.destinationPort in 1..65535)
            if (it.type != PortForwardType.DYNAMIC) valid(!it.destinationHost.isNullOrBlank() && it.destinationPort in 1..65535)
        }
    }
}
