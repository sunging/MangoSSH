package website.sung.mangossh.data.vault

import website.sung.mangossh.domain.ConnectionProfile

/** A secret editor uses explicit removal; null replacement means keep the latest stored value. */
internal fun VaultSnapshot.withEditedAuthentication(id: String, label: String, password: String?,
    clearPassword: Boolean, keyId: String?, stagedKey: StagedKeyChange? = null): VaultSnapshot? {
    val current = authentications.firstOrNull { it.id == id } ?: return null
    val withKey = withStagedKey(keyId, stagedKey) ?: return null
    val edited = current.copy(label = label, password = if (clearPassword) null else password ?: current.password, keyId = keyId)
    return withKey.copy(authentications = authentications.map { if (it.id == id) edited else it }).releaseUnusedKey(current.keyId)
}

/** A staged edit is committed only against the key version read when the editor started it. */
internal fun VaultSnapshot.withStagedKey(keyId: String?, change: StagedKeyChange?): VaultSnapshot? {
    if (change == null) return this
    if (change.key.id != keyId || keys.firstOrNull { it.id == keyId } != change.original) return null
    return copy(keys = keys.filterNot { it.id == keyId } + change.key)
}

/** Deletes an entry and its host references atomically; shared private keys remain available. */
internal fun VaultSnapshot.withRemovedAuthentication(id: String): VaultSnapshot {
    val previous = authentications.firstOrNull { it.id == id }
    return copy(authentications = authentications.filterNot { it.id == id },
        profiles = profiles.map { if (it.authenticationId == id) it.copy(authenticationId = null) else it })
        .releaseUnusedKey(previous?.keyId)
}

/** Removes a key only after its last credential owner leaves, clearing explicit agent references. */
private fun VaultSnapshot.releaseUnusedKey(id: String?): VaultSnapshot {
    if (id == null || authentications.any { it.keyId == id }) return this
    return copy(keys = keys.filterNot { it.id == id }, profiles = profiles.map { profile ->
        profile.copy(agentPolicy = profile.agentPolicy.copy(allowedKeyIds = profile.agentPolicy.allowedKeyIds?.filterNot { it == id }))
    })
}

/**
 * An accepted password creates a copy, never edits a shared credential. Both host configuration
 * and credential contents must still match the connection snapshot before anything is saved.
 * A missing initial host denotes quick connect; a host deleted during authentication is rejected.
 */
internal fun VaultSnapshot.withRememberedAuthentication(expected: ConnectionProfile, expectedSavedHost: ConnectionProfile?,
    expectedAuthentication: StoredAuthentication?, authentication: StoredAuthentication, expectedKey: StoredSshKey? = null): VaultSnapshot? {
    if (authentications.any { it.id == authentication.id }) return null
    if (expected.authenticationId != expectedAuthentication?.id) return null
    if (expectedAuthentication != null && authentications.firstOrNull { it.id == expectedAuthentication.id } != expectedAuthentication) return null
    if (expectedAuthentication?.keyId != expectedKey?.id || expectedKey != null &&
        keys.firstOrNull { it.id == expectedKey.id }?.copy(savedPassphrase = expectedKey.savedPassphrase) != expectedKey) return null
    if (authentication.keyId != expectedAuthentication?.keyId || authentication.keyId != null && keys.none { it.id == authentication.keyId }) return null
    if (expectedSavedHost != null) {
        val current = profiles.firstOrNull { it.id == expectedSavedHost.id } ?: return null
        if (current.copy(position = expectedSavedHost.position, favorite = expectedSavedHost.favorite,
                connectionCount = expectedSavedHost.connectionCount,
                lastConnectedAtEpochMillis = expectedSavedHost.lastConnectedAtEpochMillis) != expectedSavedHost) return null
    } else if (profiles.any { it.id == expected.id }) return null
    return copy(authentications = authentications + authentication, profiles = profiles.map {
        if (it.id == expectedSavedHost?.id) it.copy(authenticationId = authentication.id) else it
    })
}
