package website.sung.mangossh.presentation.settings

import website.sung.mangossh.domain.AuthenticationMethod
import website.sung.mangossh.domain.ConnectionProfile
import website.sung.mangossh.domain.ConnectionProfileDraft
import website.sung.mangossh.domain.ConnectionProtocol
import website.sung.mangossh.domain.ConnectionRoute
import website.sung.mangossh.session.tsnet.TsnetDevice

/** Saved Embedded Tailscale profiles that already target [device]. */
internal fun savedTsnetHostsFor(device: TsnetDevice, hosts: List<ConnectionProfile>): List<ConnectionProfile> =
    hosts.filter { it.route == ConnectionRoute.TSNET && device.isNamedBy(it.hostname) }

/** Username of any saved profile naming [device], whatever its route, to prefill quick connect. */
internal fun suggestedTsnetUsername(device: TsnetDevice, hosts: List<ConnectionProfile>): String =
    hosts.firstOrNull { device.isNamedBy(it.hostname) }?.username.orEmpty()

/** Tailscale SSH needs no local credential, so it is the natural default wherever the device offers it. */
internal fun defaultTsnetAuthentication(device: TsnetDevice): AuthenticationMethod =
    if (device.tailscaleSshEnabled) AuthenticationMethod.TAILSCALE_SSH else AuthenticationMethod.PASSWORD

/**
 * Builds the unsaved Embedded Tailscale profile used by quick connect and as
 * the prefill for "save as host". It reaches the device by MagicDNS name
 * through the app node, never through the system network.
 */
internal fun tsnetQuickConnectProfile(
    device: TsnetDevice,
    username: String,
    protocol: ConnectionProtocol,
    authentication: AuthenticationMethod,
    keyId: String?,
): ConnectionProfile = ConnectionProfileDraft(
    label = device.displayName,
    hostname = device.connectHost,
    port = 22,
    username = username,
    protocol = protocol,
    route = ConnectionRoute.TSNET,
    authentication = authentication,
    keyId = keyId.takeIf { authentication == AuthenticationMethod.PRIVATE_KEY },
).toProfile()
