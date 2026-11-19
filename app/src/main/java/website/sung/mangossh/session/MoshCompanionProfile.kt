package website.sung.mangossh.session

import website.sung.mangossh.data.vault.VaultSnapshot
import website.sung.mangossh.domain.ConnectionProfile

/**
 * Refreshes the credential reference from the live vault only for the account and route
 * used by the running Mosh terminal. The original transport and security options stay fixed.
 */
internal fun moshCompanionProfile(original: ConnectionProfile, snapshot: VaultSnapshot): ConnectionProfile {
    val sameIdentity = snapshot.profiles.firstOrNull(original::matchesMoshCompanionIdentity)
    val reference = if (sameIdentity != null) sameIdentity.authenticationId else original.authenticationId
    return original.copy(authenticationId = reference?.takeIf { id -> snapshot.authentications.any { it.id == id } })
}

/** Identifies the saved profile to which this running session may attach a remembered password. */
internal fun ConnectionProfile.matchesMoshCompanionIdentity(other: ConnectionProfile): Boolean =
    id == other.id && hostname == other.hostname && port == other.port && username == other.username &&
        route == other.route && protocol == other.protocol && jumpProfileIds == other.jumpProfileIds
