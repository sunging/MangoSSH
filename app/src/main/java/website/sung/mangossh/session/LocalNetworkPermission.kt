package website.sung.mangossh.session

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.UnknownHostException

/** A fixed failure category; it must never retain the requested host or a resolver error. */
internal class LocalNetworkPermissionDeniedException : Exception()

/** An interface prefix used to recognize directly connected global IPv6 destinations. */
internal class LocalNetworkPrefix(val address: InetAddress, val bits: Int) {
    /** Compares prefix bits without resolving names or retaining a destination. */
    fun contains(candidate: InetAddress): Boolean {
        val network = address.address
        val target = candidate.address
        if (network.size != target.size || bits !in 1..network.size * 8) return false
        return (0 until bits).all { bit ->
            val mask = 1 shl (7 - bit % 8)
            (network[bit / 8].toInt() and mask) == (target[bit / 8].toInt() and mask)
        }
    }
}

/**
 * Guards physical-network connects before a socket can silently time out on Android 17.
 *
 * Call only on an I/O dispatcher: ordinary DNS names must be resolved to distinguish
 * LAN servers from public servers. mDNS names require permission before resolution.
 * Loopback and public destinations remain usable when permission is denied. Callers
 * bypass this guard for embedded Tailscale, whose destinations are reached in userspace.
 */
internal class LocalNetworkPermissionGate(
    private val sdkInt: Int,
    private val hasPermission: () -> Boolean,
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    private val localPrefixes: () -> List<LocalNetworkPrefix> = ::interfacePrefixes,
) {
    /** UI answers alone never authorize access: recheck the actual Android grant after prompting. */
    suspend fun requireAccess(hostname: String, requestPermission: suspend () -> Boolean) {
        if (sdkInt < 37 || hasPermission()) return
        val name = hostname.trimEnd('.')
        val local = name.endsWith(".local", ignoreCase = true) || try {
            val addresses = resolve(hostname)
            val prefixes = if (addresses.any { it is Inet6Address }) localPrefixes() else emptyList()
            addresses.any { isLocalNetworkAddress(it, prefixes) }
        } catch (_: UnknownHostException) {
            // Let the transport report its usual DNS failure; no permission can fix NXDOMAIN.
            false
        }
        if (local && (!requestPermission() || !hasPermission())) throw LocalNetworkPermissionDeniedException()
    }
}

/** Android's local ranges include CGNAT, link-local, multicast and LAN IPv6 prefixes. */
internal fun isLocalNetworkAddress(address: InetAddress, prefixes: List<LocalNetworkPrefix> = emptyList()): Boolean {
    if (address.isLoopbackAddress) return false
    if (address.isAnyLocalAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return true
    val bytes = address.address
    return when (address) {
        is Inet4Address -> ((bytes[0].toInt() and 0xff) == 100 && (bytes[1].toInt() and 0xc0) == 64) ||
            bytes.all { (it.toInt() and 0xff) == 255 }
        is Inet6Address -> (bytes[0].toInt() and 0xfe) == 0xfc || prefixes.any { it.contains(address) }
        else -> false
    }
}

/** Interface enumeration never exposes addresses through UI state, logs or exception causes. */
private fun interfacePrefixes(): List<LocalNetworkPrefix> = try {
    NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().flatMap { network ->
        network.interfaceAddresses.map { LocalNetworkPrefix(it.address, it.networkPrefixLength.toInt()) }
    }
} catch (_: SocketException) {
    emptyList()
}
