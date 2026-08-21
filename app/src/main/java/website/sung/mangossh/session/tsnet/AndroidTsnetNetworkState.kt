package website.sung.mangossh.session.tsnet

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import java.io.Closeable
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import tsnetbridge.NetworkStateSource

/**
 * Supplies the embedded Go node with Android's permission-safe interface view.
 *
 * Android 30+ blocks the netlink operations used by Go's net.Interfaces. This
 * source contains only local interface metadata and never persists or logs it.
 */
internal class AndroidTsnetNetworkStateSource(context: Context) : NetworkStateSource {
    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun snapshotJson(): String {
        val interfaces = JSONArray()
        val enumeration = NetworkInterface.getNetworkInterfaces()
        while (enumeration != null && enumeration.hasMoreElements()) {
            val networkInterface = enumeration.nextElement()
            runCatching { networkInterface.toJson() }
                .getOrNull()
                ?.let(interfaces::put)
        }
        val linkProperties = connectivityManager.activeNetwork
            ?.let(connectivityManager::getLinkProperties)
        return JSONObject()
            .put("defaultRoute", linkProperties?.interfaceName.orEmpty())
            .put("defaultGateway", linkProperties.defaultGateway())
            .put("interfaces", interfaces)
            .toString()
    }

    /**
     * Reports the IPv4 default gateway so tsnet can attempt local port mapping.
     *
     * Android hides the routing table from apps, so the upstream monitor cannot
     * discover the home router on its own. Without this value UPnP/PMP/PCP
     * discovery is skipped and direct connections degrade to relaying.
     */
    private fun LinkProperties?.defaultGateway(): String {
        val gateway = this?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway
            ?: return ""
        return if (gateway.isAnyLocalAddress) "" else gateway.hostAddress.orEmpty()
    }

    private fun NetworkInterface.toJson(): JSONObject {
        val addresses = JSONArray()
        interfaceAddresses.forEach { interfaceAddress ->
            val address = interfaceAddress.address ?: return@forEach
            val hostAddress = address.hostAddress ?: return@forEach
            addresses.put(
                JSONObject()
                    .put("ip", hostAddress)
                    .put("prefixLen", interfaceAddress.networkPrefixLength.toInt()),
            )
        }
        return JSONObject()
            .put("name", name)
            .put("index", index)
            .put("mtu", mtu)
            .put("up", isUp)
            .put("broadcast", false)
            .put("loopback", isLoopback)
            .put("pointToPoint", isPointToPoint)
            .put("multicast", supportsMulticast())
            .put("addrs", addresses)
    }
}

/**
 * Converts Android default-network callbacks into tsnet monitor refreshes.
 *
 * Registration exists only while the process-level node is starting or in use.
 */
internal class AndroidTsnetNetworkMonitor(
    context: Context,
    private val onNetworkChanged: () -> Unit,
) : Closeable {
    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val registered = AtomicBoolean(false)
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = notifyChanged()

        override fun onLost(network: Network) = notifyChanged()

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
            notifyChanged()

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) = notifyChanged()
    }

    fun start() {
        if (registered.compareAndSet(false, true)) {
            try {
                connectivityManager.registerDefaultNetworkCallback(callback)
            } catch (error: RuntimeException) {
                registered.set(false)
                throw error
            }
        }
    }

    override fun close() {
        if (!registered.compareAndSet(true, false)) return
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
    }

    private fun notifyChanged() {
        if (registered.get()) onNetworkChanged()
    }
}
