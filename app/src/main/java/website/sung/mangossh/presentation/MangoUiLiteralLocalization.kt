package website.sung.mangossh.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * English fallbacks for the established Compose wording.
 *
 * These entries are deliberately limited to application-owned fixed UI text.
 * User-created labels, hostnames, fingerprints, commands, server output, and
 * authentication prompts bypass the table unchanged. Android resources cover
 * platform-facing strings and expose `en` / `zh-CN` as supported app locales.
 */
internal object MangoUiLiteralLocalization {
    fun resolve(value: String, language: String?): String {
        if (language.equals("zh", ignoreCase = true)) return value
        return english[value] ?: resolveTemplate(value) ?: value
    }

    /**
     * Resolves the small set of application-owned messages that include a
     * runtime value. The variable portion is carried through verbatim: it can
     * be a user-created label, a key algorithm, or a sanitized error detail.
     */
    private fun resolveTemplate(value: String): String? {
        pinLengthPattern.matchEntire(value)?.let { match ->
            return "PIN must contain ${match.groupValues[1]} to ${match.groupValues[2]} digits."
        }
        return when {
            value.startsWith("已生成 ") && value.endsWith(" 密钥") ->
                "Generated ${value.removePrefix("已生成 ").removeSuffix(" 密钥")} key."

            value.startsWith("已生成 ") && value.endsWith("。") ->
                "Generated ${value.removePrefix("已生成 ").removeSuffix("。")}."

            value.startsWith("已导入 ") && value.endsWith("。") ->
                "Imported ${value.removePrefix("已导入 ").removeSuffix("。")}."

            value.startsWith("\r\n[MangoSSH] 写入失败：") && value.endsWith("\r\n") ->
                "\r\n[MangoSSH] Write failed: ${value.removePrefix("\r\n[MangoSSH] 写入失败：").removeSuffix("\r\n")}\r\n"

            value.startsWith("\r\n[MangoSSH] 资源查询失败：") && value.endsWith("\r\n") ->
                "\r\n[MangoSSH] Resource query failed: ${value.removePrefix("\r\n[MangoSSH] 资源查询失败：").removeSuffix("\r\n")}\r\n"

            value.endsWith(" 的密码") -> "Password for ${value.removeSuffix(" 的密码")}"
            value.startsWith("解锁 ") -> "Unlock ${value.removePrefix("解锁 ")}"
            else -> null
        }
    }

    private val pinLengthPattern = Regex("^PIN 必须为 (\\d+) 到 (\\d+) 位数字。\\z")

    private val english = mapOf(
        "确定" to "OK",
        "取消" to "Cancel",
        "保存" to "Save",
        "继续" to "Continue",
        "提交" to "Submit",
        "输入" to "Enter value",
        "打开" to "Open",
        "关闭" to "Close",
        "编辑" to "Edit",
        "移除" to "Remove",
        "连接" to "Connect",
        "上传" to "Upload",
        "下载" to "Download",
        "刷新" to "Refresh",
        "粘贴" to "Paste",
        "返回主机列表" to "Back to hosts",
        "服务器资源" to "Server resources",
        "关闭会话" to "Close session",
        "正在从服务器读取资源信息…" to "Reading resource information from the server…",
        "启动" to "Start",
        "停止" to "Stop",
        "配置" to "Configure",
        "导入" to "Import",
        "导出" to "Export",
        "生成" to "Generate",
        "主机" to "Hosts",
        "密钥" to "Keys",
        "传输" to "Transfers",
        "设置" to "Settings",
        "SSH" to "SSH",
        "Mosh" to "Mosh",
        "直接连接" to "Direct",
        "私钥" to "Private key",
        "密码" to "Password",
        "交互式 / OTP" to "Interactive / OTP",
        "本地" to "Local",
        "远程" to "Remote",
        "MangoSSH 已锁定" to "MangoSSH is locked",
        "使用应用 PIN 解锁。" to "Unlock with your app PIN.",
        "应用 PIN" to "App PIN",
        "使用 PIN 解锁" to "Unlock with PIN",
        "使用生物识别" to "Use biometrics",
        "活动会话" to "Active sessions",
        "连接中" to "Connecting",
        "验证指纹" to "Verifying fingerprint",
        "认证中" to "Authenticating",
        "已连接" to "Connected",
        "已关闭" to "Closed",
        "还没有服务器" to "No servers yet",
        "新建主机配置" to "New host profile",
        "安全优先" to "Security first",
        "密钥保险库" to "Key vault",
        "生成 Ed25519" to "Generate Ed25519",
        "导入私钥" to "Import private key",
        "复制公钥" to "Copy public key",
        "导出私钥" to "Export private key",
        "密钥名称" to "Key name",
        "生成 Ed25519 密钥" to "Generate Ed25519 key",
        "导入的私钥" to "Imported private key",
        "私钥口令（可选）" to "Private-key passphrase (optional)",
        "端口转发" to "Port forwarding",
        "新建转发" to "New forward",
        "SCP 上传 / 下载" to "SCP upload / download",
        "SCP 传输" to "SCP transfers",
        "排队中" to "Queued",
        "传输中" to "Transferring",
        "已完成" to "Completed",
        "失败" to "Failed",
        "已删除的主机" to "Deleted host",
        "新建端口转发" to "New port forward",
        "编辑端口转发" to "Edit port forward",
        "所属主机" to "Host",
        "类型" to "Type",
        "远端绑定地址" to "Remote bind address",
        "本地绑定地址" to "Local bind address",
        "远端监听端口" to "Remote listen port",
        "本地监听端口" to "Local listen port",
        "目标主机" to "Destination host",
        "目标端口" to "Destination port",
        "连接后自动启动" to "Start on connection",
        "SCP 文件传输" to "SCP file transfer",
        "会话" to "Session",
        "远端目录" to "Remote directory",
        "远端文件路径" to "Remote file path",
        "选择本地文件" to "Choose local file",
        "选择保存位置" to "Choose save location",
        "安全与同步" to "Security and sync",
        "加密备份" to "Encrypted backup",
        "自定义 WebDAV" to "Custom WebDAV",
        "下载并导入" to "Download and import",
        "应用保护" to "App protection",
        "修改 PIN" to "Change PIN",
        "设置 PIN" to "Set PIN",
        "立即锁定" to "Lock now",
        "允许生物识别解锁" to "Allow biometric unlock",
        "连接后代码片段" to "Post-connect snippets",
        "新建片段" to "New snippet",
        "还没有代码片段。" to "No snippets yet.",
        "配置 WebDAV" to "Configure WebDAV",
        "WebDAV 目录 URL" to "WebDAV directory URL",
        "用户名" to "Username",
        "密码 / 应用专用密码" to "Password / app password",
        "远端文件名" to "Remote file name",
        "同步口令" to "Sync passphrase",
        "新建代码片段" to "New snippet",
        "编辑代码片段" to "Edit snippet",
        "名称" to "Name",
        "Shell 命令或脚本" to "Shell command or script",
        "自动附加换行" to "Append newline automatically",
        "设置应用 PIN" to "Set app PIN",
        "4–12 位 PIN" to "4–12 digit PIN",
        "再次输入 PIN" to "Confirm PIN",
        "新建服务器" to "New server",
        "编辑服务器" to "Edit server",
        "名称（可选）" to "Name (optional)",
        "主机名或 IP 地址" to "Hostname or IP address",
        "端口" to "Port",
        "协议" to "Protocol",
        "网络路由" to "Network route",
        "认证方式" to "Authentication",
        "共享私钥" to "Shared private key",
        "连接后自动执行" to "Run after connection",
        "不执行" to "Do not run",
        "启用 SSH 代理转发" to "Enable SSH agent forwarding",
        "保存配置" to "Save profile",
        "服务器指纹已变更" to "Server fingerprint changed",
        "验证服务器指纹" to "Verify server fingerprint",
        "替换并信任" to "Replace and trust",
        "信任并连接" to "Trust and connect",
        "连接工作区" to "Connection workspace",
        "共享密钥与代理" to "Shared keys and agent",
        "SFTP、SCP 与端口转发" to "SFTP, SCP, and port forwarding",
        "应用锁、同步与 Tailnet" to "App lock, sync, and Tailnet",
        "安全保险库：正在打开" to "Secure vault: opening",
        "安全保险库：已由 Android Keystore 加密" to "Secure vault: encrypted by Android Keystore",
        "安全保险库：不可用" to "Secure vault: unavailable",
        "还没有密钥。建议新建 Ed25519 密钥，或导入 OpenSSH/PEM 私钥。" to
            "No keys yet. Create an Ed25519 key or import an OpenSSH/PEM private key.",
        "此私钥还需要口令。" to "This private key also requires its passphrase.",
        "支持 OpenSSH 和 PEM 私钥。若密钥未加密，口令留空即可。" to
            "OpenSSH and PEM private keys are supported. Leave the passphrase blank for an unencrypted key.",
        "文件与转发" to "Files and forwarding",
        "活动传输会显示在这里。" to "Active transfers appear here.",
        "活动端口转发会显示在这里。" to "Active port forwards appear here.",
        "仅在用户启动会话后运行前台服务。" to "The foreground service runs only after you start a session.",
        "本地、远程和 SOCKS5 转发均绑定到已打开的 SSH 会话；会话关闭后转发会自动停止。" to
            "Local, remote, and SOCKS5 forwards attach to an open SSH session and stop when it closes.",
        "请先创建主机配置，再为其添加端口转发。" to "Create a host profile before adding a port forward.",
        "还没有端口转发规则。可以设置连接后自动启动。" to
            "No port-forwarding rules yet. A rule can start automatically after connection.",
        "SOCKS5 代理不需要目标主机和端口。" to "A SOCKS5 proxy does not need a destination host or port.",
        "选择本地文件后将上传到此远端目录。路径不能含空格或 shell 特殊字符。" to
            "The selected local file will upload to this directory. Paths cannot contain spaces or shell-special characters.",
        "选择保存位置后将下载此远端文件。路径不能含空格或 shell 特殊字符。" to
            "The remote file will download to the selected location. Paths cannot contain spaces or shell-special characters.",
        "只接受 HTTPS WebDAV 地址。地址应指向要保存备份的目录。" to
            "Only HTTPS WebDAV URLs are accepted. The URL should point to the directory that stores backups.",
        "PIN 只在本机保存为加盐验证值，不能恢复。" to
            "The PIN is stored only on this device as a salted verifier and cannot be recovered.",
        "Tailnet 路由通过设备已启用的 Tailscale VPN 访问目标；认证方式会设为 Tailscale SSH。" to
            "Tailnet routing reaches the target through the device's enabled Tailscale VPN; authentication uses Tailscale SSH.",
        "Mosh 使用 GPL-3.0-or-later 原生客户端；源代码和许可证见项目随附材料。它需要远端 mosh-server 和可达的 UDP 端口（默认 60000–61000），且不支持 SSH 的 SCP、端口转发、代理转发或资源查询。" to
            "Mosh uses a GPL-3.0-or-later native client; its source and license are included with the project. It requires mosh-server on the remote host and reachable UDP (default ports 60000–61000), and does not support SSH SCP, port forwarding, agent forwarding, or resource queries.",
        "请先在“密钥”页生成或导入一把私钥。" to "Generate or import a private key on the Keys page first.",
        "保存的服务器密钥与本次连接不一致。仅在确认服务器确实重装或更换密钥后才继续。" to
            "The saved server key differs from this connection. Continue only after confirming the server was rebuilt or its key was replaced.",
        "这是首次连接到此服务器。请与管理员提供的指纹核对后再信任。" to
            "This is the first connection to this server. Verify the fingerprint with the administrator before trusting it.",
        "添加 SSH、Mosh 或 Tailnet 主机配置。密钥会在安全保险库启用后独立管理和共享。" to
            "Add an SSH, Mosh, or Tailnet host profile. Keys are managed and shared separately once the secure vault is available.",
        "生成或导入的私钥仅保存在加密保险库内，可复用到多个主机。" to
            "Generated or imported private keys stay only in the encrypted vault and can be reused by multiple hosts.",
        "此私钥还需要口令" to "This private key also requires its passphrase.",
        "SFTP、SCP、本地/远程/SOCKS 转发和传输进度会集中显示在这里。" to
            "SFTP, SCP, local/remote/SOCKS forwarding, and transfer progress are shown here.",
        "活动传输：0" to "Active transfers: 0",
        "活动端口转发：0" to "Active port forwards: 0",
        "仅在用户启动会话后运行前台服务" to "The foreground service runs only after you start a session.",
        "运行中" to "Running",
        "正在启动" to "Starting",
        "未启动" to "Not started",
        "连接对应主机后即可手动启动。" to "You can start it manually after connecting to its host.",
        "导出和 WebDAV 上传均使用独立同步口令再次加密；不会上传设备绑定的本地保险库密钥。" to
            "Exports and WebDAV uploads are encrypted again with a separate sync passphrase; the device-bound local vault key is never uploaded.",
        "尚未配置" to "Not configured",
        "应用 PIN 已启用。返回前台时需要解锁；本地保险库仍由 Android Keystore 加密。" to
            "App PIN is enabled. Unlocking is required when returning to the foreground; the local vault remains encrypted by Android Keystore.",
        "尚未设置应用 PIN。启用后，应用回到前台需要 PIN 或生物识别解锁。" to
            "No app PIN is set. Once enabled, returning to the foreground requires PIN or biometric unlock.",
        "在主机编辑页选择片段后，会在 shell 打开时自动发送。" to
            "A snippet selected in the host editor is sent automatically when the shell opens.",
        "导出加密备份" to "Export encrypted backup",
        "该口令用于保护导出的备份文件。" to "This passphrase protects the exported backup file.",
        "导入加密备份" to "Import encrypted backup",
        "导入会替换当前主机、密钥、片段和同步配置。" to
            "Importing replaces the current hosts, keys, snippets, and sync configuration.",
        "上传到 WebDAV" to "Upload to WebDAV",
        "将使用此口令加密后上传。" to "The backup will be encrypted with this passphrase before upload.",
        "从 WebDAV 导入" to "Import from WebDAV",
        "下载后会使用口令解密，并替换当前保险库。" to
            "After download, the backup will be decrypted with this passphrase and replace the current vault.",
        "原指纹：" to "Previous fingerprint: ",
        "正在打开本地加密保险库。首次连接仍会验证主机指纹。" to
            "Opening the local encrypted vault. Server fingerprints are still verified on first connection.",
        "本地配置已使用 Android Keystore 和 AES-GCM 加密保存；首次连接仍会验证主机指纹。" to
            "Local configuration is encrypted with Android Keystore and AES-GCM; server fingerprints are still verified on first connection.",
        "端口转发配置不完整" to "Port-forward configuration is incomplete.",
        "已保存端口转发" to "Port forwarding saved.",
        "代码片段名称和内容不能为空" to "Snippet name and contents cannot be empty.",
        "已保存代码片段" to "Snippet saved.",
        "请输入远端目录" to "Enter a remote directory.",
        "远端目录包含不支持的字符" to "The remote directory contains unsupported characters.",
        "请输入远端文件路径" to "Enter a remote file path.",
        "远端文件路径包含不支持的字符" to "The remote file path contains unsupported characters.",
        "无法保存加密保险库。数据未被覆盖。" to
            "Unable to save the encrypted vault. Existing data was not overwritten.",
        "无法生成密钥。" to "Unable to generate the key.",
        "此密钥需要口令。" to "This key requires a passphrase.",
        "无法导入私钥。请检查格式和口令。" to "Unable to import the private key. Check its format and passphrase.",
        "请填写 HTTPS WebDAV 地址、用户名和远端文件名。" to
            "Enter an HTTPS WebDAV URL, username, and remote filename.",
        "已保存 WebDAV 配置。" to "WebDAV configuration saved.",
        "请选择备份文件的保存位置。" to "Choose where to save the backup file.",
        "无法创建加密备份。请确认已设置同步口令。" to
            "Unable to create the encrypted backup. Ensure a sync passphrase is set.",
        "已导入加密备份，主机与密钥已替换。" to "Encrypted backup imported. Hosts and keys were replaced.",
        "无法导入备份：口令错误或文件已损坏。" to
            "Unable to import the backup: incorrect passphrase or corrupted file.",
        "请先配置 WebDAV。" to "Configure WebDAV first.",
        "无法创建加密备份。" to "Unable to create the encrypted backup.",
        "已上传加密备份到 WebDAV。" to "Encrypted backup uploaded to WebDAV.",
        "已从 WebDAV 导入加密备份。" to "Encrypted backup imported from WebDAV.",
        "无法导入 WebDAV 备份：口令错误或文件已损坏。" to
            "Unable to import the WebDAV backup: incorrect passphrase or corrupted file.",
        "已启用应用 PIN 解锁。" to "App PIN unlock enabled.",
        "PIN 必须为 4 到 12 位数字。" to "PIN must contain 4 to 12 digits.",
        "已关闭应用锁。" to "App lock disabled.",
        "请先设置应用 PIN。" to "Set an app PIN first.",
        "PIN 不正确。" to "Incorrect PIN.",
        "请先设置 PIN。" to "Set a PIN first.",
        "请选择私钥文件，而不是公钥文件。" to
            "Select a private-key file rather than a public-key file.",
        "此私钥受口令保护。" to "This private key is protected by a passphrase.",
        "同步口令不能为空。" to "The sync passphrase cannot be empty.",
        "接收长度无法接受。" to "The received length is not acceptable.",
        "保险库过大，无法导出。" to "The vault is too large to export.",
        "同步口令错误或备份文件已损坏。" to
            "The sync passphrase is incorrect or the backup is corrupted.",
        "不是有效的 MangoSSH 加密备份。" to "This is not a valid MangoSSH encrypted backup.",
        "备份文件头无效。" to "The backup header is invalid.",
        "备份文件长度无效。" to "The backup length is invalid.",
        "无法打开本地加密保险库。请检查设备安全设置。" to
            "Unable to open the local encrypted vault. Check device security settings.",
        "正在建立安全连接…" to "Establishing a secure connection…",
        "连接已关闭" to "Connection closed",
        "正在验证服务器指纹…" to "Verifying server fingerprint…",
        "正在验证身份…" to "Authenticating…",
        "服务器拒绝了此身份验证方式。" to "The server rejected this authentication method.",
        "\r\n[MangoSSH] 服务器未接受 SSH 代理转发。\r\n" to
            "\r\n[MangoSSH] The server did not accept SSH agent forwarding.\r\n",
        "密码仅用于本次连接，不会保存。" to "The password is used only for this connection and is not saved.",
        "此主机尚未选择私钥。" to "No private key is selected for this host.",
        "私钥口令仅用于本次连接。" to "The private-key passphrase is used only for this connection.",
        "私钥口令" to "Private-key passphrase",
        "Tailscale SSH 登录" to "Tailscale SSH sign-in",
        "交互式 SSH 登录" to "Interactive SSH sign-in",
        "身份验证失败。" to "Authentication failed.",
        "此私钥需要口令。" to "This private key requires a passphrase.",
        "连接已中断。请检查网络、主机地址和服务器日志。" to
            "The connection was interrupted. Check the network, host address, and server logs.",
    )
}

/** Resolves fixed MangoSSH UI wording for the current app/device locale. */
@Composable
internal fun localizedUiLiteral(value: String): String =
    MangoUiLiteralLocalization.resolve(value, LocalConfiguration.current.locales[0]?.language)
