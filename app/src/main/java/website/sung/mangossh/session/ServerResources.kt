package website.sung.mangossh.session

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/** The operating system of this authenticated transport, independent of SFTP path policy. */
enum class ServerResourcePlatform { LINUX, WINDOWS }

/** Capacity in bytes. Available space can exclude filesystem reservations. */
@Immutable
data class ResourceCapacity(val totalBytes: Long, val usedBytes: Long, val availableBytes: Long) {
    val fraction: Float get() = if (totalBytes == 0L) 0f else (usedBytes.toDouble() / totalBytes).toFloat()
}

/** One local filesystem or Windows drive; names are server data, never diagnostic text. */
@Immutable
data class ResourceDisk(val name: String, val capacity: ResourceCapacity)

/** Rates need two samples. Unknown interface kinds stay visible rather than guessing from names. */
@Immutable
data class ResourceNetwork(val name: String, val receivedBytesPerSecond: Double?, val sentBytesPerSecond: Double?, val isVirtual: Boolean? = null)

/** A partial snapshot: null means unavailable; an empty list means a successful empty query. */
@Immutable
data class ServerResourceSnapshot(
    val platform: ServerResourcePlatform,
    val system: String?,
    val uptimeSeconds: Long?,
    val logicalCpuCount: Int?,
    val cpuPercent: Double?,
    val cpuSampling: Boolean,
    val loadAverage: List<Double>?,
    val memory: ResourceCapacity?,
    val swap: ResourceCapacity?,
    val disks: List<ResourceDisk>?,
    val networks: List<ResourceNetwork>?,
    val collectedAtEpochMillis: Long,
)

/** Application-owned categories only; remote errors must not enter UI state or logs. */
enum class ServerResourceFailure { QUERY, TIMEOUT, CONNECTION, UNSUPPORTED }

/** Refresh and failure retain the last successful snapshot and its original timestamp. */
@Immutable
data class ServerResourceState(
    val snapshot: ServerResourceSnapshot? = null,
    val isRefreshing: Boolean = false,
    val failure: ServerResourceFailure? = null,
)

internal class ResourceQueryException(val failure: ServerResourceFailure) : Exception()
internal data class ResourceCpuCounter(val total: Long, val idle: Long)
internal data class ResourceNetworkCounter(val id: String, val name: String, val received: Long, val sent: Long, val isVirtual: Boolean? = null)

/** Raw counters belong to one transport generation, not merely a host or session name. */
internal data class ServerResourceSample(
    val connectionIdentity: Any,
    val platform: ServerResourcePlatform,
    val system: String? = null,
    val uptimeSeconds: Double? = null,
    val logicalCpuCount: Int? = null,
    val cpu: ResourceCpuCounter? = null,
    val loadAverage: List<Double>? = null,
    val memory: ResourceCapacity? = null,
    val swap: ResourceCapacity? = null,
    val disks: List<ResourceDisk>? = null,
    val networkTimeSeconds: Double? = null,
    val networks: List<ResourceNetworkCounter>? = null,
) {
    /** Never carry a rate across transport replacement, reboot, or counter rollback. */
    fun snapshot(previous: ServerResourceSample?, now: Long): ServerResourceSnapshot {
        val baseline = previous?.takeIf {
            it.connectionIdentity === connectionIdentity && it.platform == platform &&
                (uptimeSeconds == null || it.uptimeSeconds == null || uptimeSeconds >= it.uptimeSeconds)
        }
        val cpuPercent = cpu?.let { current -> baseline?.cpu?.let { old ->
            val total = current.total - old.total
            val idle = current.idle - old.idle
            if (total > 0 && idle in 0..total) (1.0 - idle.toDouble() / total) * 100.0 else null
        } }
        val elapsed = networkTimeSeconds?.let { time -> baseline?.networkTimeSeconds?.let { time - it } }
            ?.takeIf { it.isFinite() && it > 0.0 }
        val oldNetworks = baseline?.networks?.associateBy { it.id }.orEmpty()
        val rates = networks?.map { current ->
            val old = oldNetworks[current.id]
            val valid = elapsed != null && old != null && current.received >= old.received && current.sent >= old.sent
            ResourceNetwork(current.name,
                if (valid) (current.received - old.received) / elapsed else null,
                if (valid) (current.sent - old.sent) / elapsed else null, current.isVirtual)
        }
        return ServerResourceSnapshot(platform, system, uptimeSeconds?.toLong(), logicalCpuCount,
            cpuPercent, cpu != null && cpuPercent == null, loadAverage, memory, swap, disks, rates, now)
    }
}

/** Bounded, locale-independent parsing. Malformed optional sections do not erase other metrics. */
internal object ServerResourceParser {
    const val START = "__MANGOSSH_RESOURCES_V1__"
    const val END = "__MANGOSSH_RESOURCES_END__"
    private val whitespace = Regex("\\s+")

    fun linux(output: String, identity: Any): ServerResourceSample {
        val body = frame(output)
        val sections = linkedMapOf<String, MutableList<String>>()
        var current: MutableList<String>? = null
        body.lineSequence().forEach { line ->
            if (line.startsWith("@@")) {
                current = mutableListOf<String>().also { sections[line.drop(2)] = it }
            } else current?.add(line)
        }
        fun section(name: String) = sections[name].orEmpty().filter { it.isNotBlank() }
        if (section("platform").singleOrNull() != "Linux") throw ResourceQueryException(ServerResourceFailure.UNSUPPORTED)
        val uptime = section("uptime").firstOrNull()?.trim()?.split(whitespace)?.firstOrNull()?.positiveDouble()
        val cpuLines = section("cpu")
        val cpu = cpuLines.firstOrNull { it.startsWith("cpu ") }?.trim()?.split(whitespace)?.drop(1)?.take(8)?.let { values ->
            val numbers = values.map { it.toLongOrNull()?.takeIf { number -> number >= 0 } }
            if (numbers.size < 4 || numbers.any { it == null }) null else runCatching {
                ResourceCpuCounter(numbers.fold(0L) { sum, number -> Math.addExact(sum, number!!) },
                    Math.addExact(numbers[3]!!, numbers.getOrNull(4) ?: 0L))
            }.getOrNull()
        }
        val memory = section("memory").mapNotNull { line ->
            val parts = line.split(':', limit = 2)
            val value = parts.getOrNull(1)?.trim()?.substringBefore(' ')?.toLongOrNull()?.scaled(1024)
            value?.let { parts[0] to it }
        }.toMap()
        val links = section("links").mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size in 2..3 && parts[1].toLongOrNull() != null) {
                parts[0] to (parts[1] to when (parts.getOrNull(2)) { "1" -> true; "0" -> false; else -> null })
            } else null
        }.toMap()
        val networks = if (section("network_status").singleOrNull() == "0" && section("links_status").singleOrNull() == "0") {
            section("network").mapNotNull { line ->
                val parts = line.trim().split(':', limit = 2)
                val name = parts[0].trim()
                val link = links[name] ?: return@mapNotNull null
                val values = parts.getOrNull(1)?.trim()?.split(whitespace) ?: return@mapNotNull null
                val received = values.getOrNull(0)?.toLongOrNull()?.takeIf { it >= 0 }
                val sent = values.getOrNull(8)?.toLongOrNull()?.takeIf { it >= 0 }
                if (received == null || sent == null) null else ResourceNetworkCounter("$name:${link.first}", name, received, sent, link.second)
            }.takeUnless { it.isEmpty() && links.isNotEmpty() }
        } else null
        val diskRows = section("disk").drop(1)
        val disks = if (section("disk_status").singleOrNull() == "0" && section("disk").isNotEmpty()) {
            diskRows.mapNotNull(::disk).takeUnless { it.isEmpty() && diskRows.isNotEmpty() }
        } else null
        val prettyName = section("os").firstOrNull { it.startsWith("PRETTY_NAME=") }
            ?.substringAfter('=')?.removeSurrounding("\"")?.removeSurrounding("'")?.takeIf { it.isNotBlank() }
        val load = section("load").firstOrNull()?.trim()?.split(whitespace)?.take(3)
            ?.mapNotNull { it.positiveDouble() }?.takeIf { it.size == 3 }
        return ServerResourceSample(identity, ServerResourcePlatform.LINUX,
            prettyName ?: section("system").firstOrNull(), uptime,
            cpuLines.count { it.matches(Regex("cpu[0-9]+\\s+.*")) }.takeIf { it > 0 }, cpu, load,
            capacity(memory["MemTotal"], memory["MemAvailable"])?.takeIf { it.totalBytes > 0 }, capacity(memory["SwapTotal"], memory["SwapFree"]),
            disks, uptime, networks)
    }

    fun windows(output: String, identity: Any): ServerResourceSample {
        val json = try { JSONObject(frame(output)) } catch (_: Exception) { throw ResourceQueryException(ServerResourceFailure.QUERY) }
        if (json.long("v") != 1L || json.optString("platform") != "windows") throw ResourceQueryException(ServerResourceFailure.UNSUPPORTED)
        fun capacityObject(name: String): ResourceCapacity? = json.optJSONObject(name)?.let { capacity(it.long("total"), it.long("available")) }
        val cpu = json.optJSONObject("cpu")?.let { value ->
            val total = value.long("total"); val idle = value.long("idle")
            if (total != null && idle != null) ResourceCpuCounter(total, idle) else null
        }
        val disks = records(json.optJSONArray("disks")) { value ->
            val name = value.name("name") ?: return@records null
            capacity(value.long("total"), value.long("available"))?.let { ResourceDisk(name, it) }
        }
        val networks = records(json.optJSONArray("net")) { value ->
            val id = value.name("id"); val name = value.name("name")
            val received = value.long("received"); val sent = value.long("sent")
            if (id != null && name != null && received != null && sent != null) {
                ResourceNetworkCounter(id, name, received, sent, value.opt("virtual") as? Boolean)
            } else null
        }
        return ServerResourceSample(identity, ServerResourcePlatform.WINDOWS, json.name("os"), json.double("uptime"),
            json.long("cores")?.takeIf { it in 1..Int.MAX_VALUE }?.toInt(), cpu, memory = capacityObject("memory")?.takeIf { it.totalBytes > 0 },
            swap = capacityObject("swap"), disks = disks, networkTimeSeconds = json.double("netTime"), networks = networks)
    }

    private fun frame(output: String): String {
        val lines = output.trimStart('\uFEFF').lineSequence().map { it.trimEnd('\r') }.toList()
        val start = lines.indexOf(START)
        val end = lines.indexOf(END)
        if (start < 0 || end <= start) throw ResourceQueryException(ServerResourceFailure.QUERY)
        return lines.subList(start + 1, end).joinToString("\n")
    }

    private fun disk(line: String): ResourceDisk? {
        // The filesystem and mountpoint may contain spaces; numeric columns delimit them.
        val match = Regex("^.*?\\s+(\\d+)\\s+(\\d+)\\s+(-?\\d+)\\s+\\d+%\\s+(.+)$").matchEntire(line) ?: return null
        val total = match.groupValues[1].toLongOrNull()?.scaled(1024) ?: return null
        val used = match.groupValues[2].toLongOrNull()?.scaled(1024) ?: return null
        val available = match.groupValues[3].toLongOrNull()?.coerceAtLeast(0)?.scaled(1024) ?: return null
        if (used > total || available > total) return null
        return ResourceDisk(match.groupValues[4], ResourceCapacity(total, used, available))
    }

    private fun capacity(total: Long?, available: Long?): ResourceCapacity? =
        if (total != null && available != null && available in 0..total) ResourceCapacity(total, total - available, available) else null
    private fun Long.scaled(factor: Long): Long? = if (this < 0) null else runCatching { Math.multiplyExact(this, factor) }.getOrNull()
    private fun String.positiveDouble() = toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
    private fun JSONObject.long(key: String) = opt(key)?.toString()?.toLongOrNull()?.takeIf { it >= 0 }
    private fun JSONObject.double(key: String) = opt(key)?.toString()?.positiveDouble()
    private fun JSONObject.name(key: String) = (opt(key) as? String)?.takeIf { it.isNotBlank() }
    /** An unreadable nonempty response is unavailable, never a successful empty query. */
    private fun <T> records(array: JSONArray?, parse: (JSONObject) -> T?): List<T>? = array?.let {
        (0 until it.length()).mapNotNull { index -> it.optJSONObject(index)?.let(parse) }
            .takeUnless { values -> values.isEmpty() && it.length() > 0 }
    }
}
