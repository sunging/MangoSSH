package website.sung.mangossh.session

import java.io.Closeable
import java.io.InputStream
import java.util.Base64
import java.util.Collections
import java.util.WeakHashMap
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import website.sung.mangossh.session.ssh.SshConnection

internal data class ResourceCommandResult(val output: String, val exitCode: Int?)

/** A query owns only its exec channel; closing it must never close the shared SSH carrier. */
internal interface ResourceExecChannel : Closeable {
    val stdout: InputStream
    val stderr: InputStream
    suspend fun execute(command: String)
    suspend fun exitCode(): Int?
}

/** Drains both streams concurrently and closes even when a stream ignores coroutine cancellation. */
internal suspend fun executeResourceCommand(channel: ResourceExecChannel, command: String): ResourceCommandResult = coroutineScope {
    val cancellation = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { channel.close() }
    }
    try {
        channel.execute(command)
        val output = async(Dispatchers.IO) { readResourceStream(channel.stdout, 128 * 1024).toString(Charsets.UTF_8) }
        val errors = async(Dispatchers.IO) { readResourceStream(channel.stderr, 16 * 1024) }
        val result = output.await()
        errors.await() // Intentionally discard all server error text, including PowerShell CLIXML.
        ResourceCommandResult(result, channel.exitCode())
    } finally {
        channel.close()
        cancellation.cancel()
    }
}

private suspend fun readResourceStream(stream: InputStream, limit: Int): ByteArray = try {
    BoundedProtocolReader.bytes(stream, limit)
} catch (error: Exception) {
    // Closing a cancelled channel can unblock read() with IOException; keep cancellation's identity.
    currentCoroutineContext().ensureActive()
    throw error
}

/** Detects once per transport. Scripts contain only application-owned text and read-only operations. */
internal class ServerResourceCollector {
    private val platforms = Collections.synchronizedMap(WeakHashMap<Any, ServerResourcePlatform>())

    suspend fun collect(connection: SshConnection): ServerResourceSample = collect(connection) { command ->
        val ssh = connection.openChannel()
        executeResourceCommand(object : ResourceExecChannel {
            override val stdout = ssh.stdout
            override val stderr = ssh.stderr
            override suspend fun execute(command: String) {
                if (!ssh.execute(command)) throw ResourceQueryException(ServerResourceFailure.QUERY)
                ssh.eof()
            }
            override suspend fun exitCode() = ssh.exitCode()
            override fun close() = ssh.close()
        }, command)
    }

    /** Injectable transport boundary also tests detection without manufacturing SSH credentials. */
    internal suspend fun collect(identity: Any, execute: suspend (String) -> ResourceCommandResult): ServerResourceSample = withTimeout(15_000) {
        val known = platforms[identity]
        if (known != null) return@withTimeout parse(known, checked(execute(command(known))), identity)
        for (platform in ServerResourcePlatform.entries) {
            val result = execute(command(platform))
            if (result.exitCode != 0) continue
            val sample = try { parse(platform, result.output, identity) } catch (_: ResourceQueryException) { continue }
            platforms[identity] = platform
            return@withTimeout sample
        }
        throw ResourceQueryException(ServerResourceFailure.UNSUPPORTED)
    }

    private fun checked(result: ResourceCommandResult): String {
        if (result.exitCode != 0) throw ResourceQueryException(ServerResourceFailure.QUERY)
        return result.output
    }
    private fun parse(platform: ServerResourcePlatform, output: String, identity: Any) = when (platform) {
        ServerResourcePlatform.LINUX -> ServerResourceParser.linux(output, identity)
        ServerResourcePlatform.WINDOWS -> ServerResourceParser.windows(output, identity)
    }
    private fun command(platform: ServerResourcePlatform) = when (platform) {
        ServerResourcePlatform.LINUX -> ServerResourceCommands.linux
        ServerResourcePlatform.WINDOWS -> ServerResourceCommands.windows
    }
}

/** Fixed wire formats avoid localized human-readable tools and never interpolate profile data. */
internal object ServerResourceCommands {
    // A POSIX shell is invoked explicitly, including when the account uses fish/csh.
    // Use kernel device-backed filesystem types (plus local union/ZFS types) to skip network
    // and pseudo mounts before df. Ignore inaccessible/stale mounts; use GNU/BusyBox df options.
    internal val linuxScript = """
        export LC_ALL=C
        [ "$(uname -s 2>/dev/null)" = Linux ] || exit 64
        printf '__MANGOSSH_RESOURCES_V1__\n@@platform\nLinux\n@@system\n'
        uname -sr
        printf '@@os\n'
        cat /etc/os-release 2>/dev/null
        printf '\n@@uptime\n'
        cat /proc/uptime 2>/dev/null
        printf '\n@@cpu\n'
        awk '/^cpu/ {print}' /proc/stat 2>/dev/null
        printf '@@load\n'
        cat /proc/loadavg 2>/dev/null
        printf '\n@@memory\n'
        cat /proc/meminfo 2>/dev/null
        printf '\n@@network\n'
        cat /proc/net/dev 2>/dev/null
        printf '@@network_status\n%s\n@@links\n' "$?"
        for p in /sys/class/net/*; do
            [ -d "${'$'}p" ] || continue
            name=${'$'}{p##*/}
            [ "${'$'}name" = lo ] && continue
            read -r state < "${'$'}p/operstate" || continue
            case "${'$'}state" in up|unknown) ;; *) continue ;; esac
            read -r index < "${'$'}p/ifindex" || continue
            virtual=unknown
            if [ -d "/sys/devices/virtual/net/${'$'}name" ]; then virtual=1
            elif [ -d "${'$'}p/device" ]; then virtual=0; fi
            printf '%s\t%s\t%s\n' "${'$'}name" "${'$'}index" "${'$'}virtual"
        done
        if [ -d /sys/class/net ]; then printf '@@links_status\n0\n'; else printf '@@links_status\n1\n'; fi
        printf '@@disk\n'
        targets=$(awk 'FILENAME == ARGV[1] {if (${'$'}1 != "nodev") localfs[${'$'}1]=1; next}
            (${'$'}3 in localfs || ${'$'}3 == "zfs" || ${'$'}3 == "overlay" || ${'$'}3 == "aufs") && ${'$'}3 != "squashfs" && !seen[${'$'}2]++ {print ${'$'}2}' /proc/filesystems /proc/self/mounts 2>/dev/null)
        disk_status=$?
        if [ "${'$'}disk_status" = 0 ]; then
            set --
            while IFS= read -r target; do
                [ -n "${'$'}target" ] || continue
                target=$(printf '%b.' "${'$'}target")
                target=${'$'}{target%.}
                [ -d "${'$'}target" ] || continue
                case "${'$'}target" in /*) set -- "${'$'}@" "${'$'}target" ;; esac
            done <<MANGOSSH_MOUNTS
        ${'$'}targets
        MANGOSSH_MOUNTS
            if [ "$#" -gt 0 ]; then
                df -P -k "${'$'}@" 2>/dev/null
                disk_status=$?
            else
                printf 'Filesystem 1024-blocks Used Available Capacity Mounted on\n'
            fi
        fi
        printf '@@disk_status\n%s\n__MANGOSSH_RESOURCES_END__\n' "${'$'}disk_status"
    """.trimIndent()
    val linux = "sh -c '" + linuxScript.replace("'", "'\\''") + "'"

    // Keep the encoded command below cmd.exe's 8191-character limit. Individual CIM failures
    // omit only that section. JSON numeric counters are strings to retain all 64 bits.
    internal val windowsScript = """
        ${'$'}ProgressPreference='SilentlyContinue';${'$'}ErrorActionPreference='Stop';[Console]::OutputEncoding=New-Object Text.UTF8Encoding(${'$'}false);
        ${'$'}r=@{v=1;platform='windows';os=[Environment]::OSVersion.VersionString};
        try{${'$'}o=Get-CimInstance Win32_OperatingSystem;${'$'}r.os=${'$'}o.Caption+' ('+${'$'}o.Version+')';${'$'}r.uptime=([DateTime]::UtcNow-${'$'}o.LastBootUpTime.ToUniversalTime()).TotalSeconds;if(${'$'}null -ne ${'$'}o.TotalVisibleMemorySize -and ${'$'}null -ne ${'$'}o.FreePhysicalMemory){${'$'}r.memory=@{total=([long]${'$'}o.TotalVisibleMemorySize*1024);available=([long]${'$'}o.FreePhysicalMemory*1024)}}}catch{};
        try{${'$'}r.cores=(Get-CimInstance Win32_ComputerSystem).NumberOfLogicalProcessors}catch{};
        try{${'$'}c=Get-CimInstance Win32_PerfRawData_PerfOS_Processor -Filter "Name='_Total'";${'$'}r.cpu=@{total=[string]${'$'}c.Timestamp_Sys100NS;idle=[string]${'$'}c.PercentProcessorTime}}catch{};
        try{${'$'}t=0L;${'$'}u=0L;Get-CimInstance Win32_PageFileUsage|ForEach-Object{if(${'$'}null -eq ${'$'}_.AllocatedBaseSize -or ${'$'}null -eq ${'$'}_.CurrentUsage){throw 1};${'$'}t+=[long]${'$'}_.AllocatedBaseSize;${'$'}u+=[long]${'$'}_.CurrentUsage};${'$'}r.swap=@{total=(${'$'}t*1048576);available=((${'$'}t-${'$'}u)*1048576)}}catch{};
        try{${'$'}r.disks=@(Get-CimInstance Win32_LogicalDisk -Filter 'DriveType=3'|ForEach-Object{@{name=${'$'}_.DeviceID;total=${'$'}_.Size;available=${'$'}_.FreeSpace}})}catch{};
        ${'$'}v=@{};try{Get-CimInstance -Namespace root/StandardCimv2 -ClassName MSFT_NetAdapter|ForEach-Object{if(${'$'}_.InterfaceGuid){${'$'}v[${'$'}_.InterfaceGuid.Trim('{}')]=if(${'$'}_.Virtual -eq ${'$'}true -or ${'$'}_.HardwareInterface -eq ${'$'}false){${'$'}true}elseif(${'$'}_.HardwareInterface -eq ${'$'}true){${'$'}false}else{${'$'}null}}}}catch{};
        try{${'$'}r.netTime=[Diagnostics.Stopwatch]::GetTimestamp()/[double][Diagnostics.Stopwatch]::Frequency;${'$'}r.net=@([Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces()|Where-Object{${'$'}_.OperationalStatus -eq 'Up' -and ${'$'}_.NetworkInterfaceType -ne 'Loopback'}|ForEach-Object{${'$'}s=${'$'}_.GetIPStatistics();@{id=${'$'}_.Id;name=${'$'}_.Name;virtual=${'$'}v[${'$'}_.Id.Trim('{}')];received=[string]${'$'}s.BytesReceived;sent=[string]${'$'}s.BytesSent}})}catch{${'$'}r.Remove('net')};
        '__MANGOSSH_RESOURCES_V1__';ConvertTo-Json -InputObject ${'$'}r -Depth 4 -Compress;'__MANGOSSH_RESOURCES_END__';
    """.trimIndent()
    val windows = "powershell.exe -NoLogo -NoProfile -NonInteractive -OutputFormat Text -EncodedCommand " +
        Base64.getEncoder().encodeToString(windowsScript.toByteArray(Charsets.UTF_16LE))
}
