@file:OptIn(ExperimentalMaterial3Api::class)

package website.sung.mangossh.presentation

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import website.sung.mangossh.data.vault.VaultStatus
import website.sung.mangossh.data.vault.CommandSnippet
import website.sung.mangossh.data.vault.PortForwardRule
import website.sung.mangossh.data.vault.PortForwardType
import website.sung.mangossh.data.vault.StoredSshKey
import website.sung.mangossh.data.vault.WebDavConfig
import website.sung.mangossh.domain.AuthenticationMethod
import website.sung.mangossh.domain.ConnectionProfile
import website.sung.mangossh.domain.ConnectionProfileDraft
import website.sung.mangossh.domain.ConnectionProtocol
import website.sung.mangossh.domain.ConnectionRoute
import website.sung.mangossh.session.SessionPrompt
import website.sung.mangossh.session.PortForwardRuntimePhase
import website.sung.mangossh.session.PortForwardRuntimeState
import website.sung.mangossh.session.ScpTransferDirection
import website.sung.mangossh.session.ScpTransferPhase
import website.sung.mangossh.session.ScpTransferState
import website.sung.mangossh.session.TerminalSessionPhase
import website.sung.mangossh.security.AppLockConfiguration

@Composable
fun MangoSshApp(
    viewModel: MangoSshViewModel,
    onRequestBiometricUnlock: (() -> Unit)? = null,
) {
    val appLocked by viewModel.appLocked.collectAsStateWithLifecycle()
    val appLockConfiguration by viewModel.appLockConfiguration.collectAsStateWithLifecycle()
    val userMessage by viewModel.userMessage.collectAsStateWithLifecycle()
    if (appLocked) {
        AppLockScreen(
            configuration = appLockConfiguration,
            message = userMessage,
            onUnlockWithPin = viewModel::unlockWithPin,
            onRequestBiometric = onRequestBiometricUnlock,
            onDismissMessage = viewModel::dismissUserMessage,
        )
        return
    }

    val hosts by viewModel.hosts.collectAsStateWithLifecycle()
    val keys by viewModel.keys.collectAsStateWithLifecycle()
    val snippets by viewModel.snippets.collectAsStateWithLifecycle()
    val selectedSection by viewModel.selectedSection.collectAsStateWithLifecycle()
    val vaultStatus by viewModel.vaultStatus.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val sessionPrompts by viewModel.sessionPrompts.collectAsStateWithLifecycle()
    val portForwardRules by viewModel.portForwardRules.collectAsStateWithLifecycle()
    val activePortForwards by viewModel.activePortForwards.collectAsStateWithLifecycle()
    val scpTransfers by viewModel.scpTransfers.collectAsStateWithLifecycle()
    val resourceSnapshots by viewModel.resourceSnapshots.collectAsStateWithLifecycle()
    val webDavConfig by viewModel.webDavConfig.collectAsStateWithLifecycle()
    val portableExport by viewModel.portableExport.collectAsStateWithLifecycle()
    var editingHost by remember { mutableStateOf<ConnectionProfile?>(null) }
    var showHostEditor by rememberSaveable { mutableStateOf(false) }
    var activeSessionId by rememberSaveable { mutableStateOf<String?>(null) }

    fun openHostEditor(host: ConnectionProfile? = null) {
        editingHost = host
        showHostEditor = true
    }

    val activeSession = activeSessionId?.let { id -> sessions.firstOrNull { it.id == id } }
    if (activeSession != null) {
        TerminalSessionScreen(
            session = activeSession,
            output = viewModel.terminalOutput,
            onSend = { bytes -> viewModel.sendTerminalInput(activeSession.id, bytes) },
            onResize = { columns, rows -> viewModel.resizeTerminal(activeSession.id, columns, rows) },
            resourceSnapshot = resourceSnapshots[activeSession.id],
            onRequestResources = { viewModel.requestServerResources(activeSession.id) },
            onMinimize = { activeSessionId = null },
            onClose = {
                viewModel.disconnect(activeSession.id)
                activeSessionId = null
            },
        )
        sessionPrompts.firstOrNull { it.sessionId == activeSession.id }?.let { prompt ->
            SessionPromptDialog(
                prompt = prompt,
                onRespond = { values -> viewModel.respondToSessionPrompt(prompt, values) },
            )
        }
        return
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("MangoSSH", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = sectionSubtitle(selectedSection),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (selectedSection == AppSection.HOSTS) {
                        IconButton(
                            onClick = { openHostEditor() },
                            enabled = vaultStatus !is VaultStatus.Failed,
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = "新建主机配置")
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                AppSection.entries.forEach { section ->
                    NavigationBarItem(
                        selected = section == selectedSection,
                        onClick = { viewModel.selectSection(section) },
                        icon = {
                            Icon(
                                imageVector = section.icon(),
                                contentDescription = section.label,
                            )
                        },
                        label = { Text(section.label) },
                    )
                }
            }
        },
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            when (selectedSection) {
                AppSection.HOSTS -> HostsScreen(
                    hosts = hosts,
                    sessions = sessions,
                    vaultStatus = vaultStatus,
                    onAddHost = { openHostEditor() },
                    onEditHost = { openHostEditor(it) },
                    onRemoveHost = viewModel::removeHost,
                    onConnectHost = { host -> activeSessionId = viewModel.connect(host) },
                    onOpenSession = { sessionId -> activeSessionId = sessionId },
                    onDisconnectSession = viewModel::disconnect,
                )

                AppSection.KEYS -> KeysScreen(
                    vaultStatus = vaultStatus,
                    keys = keys,
                    onGenerate = viewModel::generateEd25519Key,
                    onImport = viewModel::importPrivateKey,
                    onRemove = viewModel::removeKey,
                )
                AppSection.TRANSFERS -> TransfersScreen(
                    hosts = hosts,
                    rules = portForwardRules,
                    activeForwards = activePortForwards,
                    scpTransfers = scpTransfers,
                    sessions = sessions,
                    onSaveRule = viewModel::savePortForward,
                    onRemoveRule = viewModel::removePortForward,
                    onStartRule = viewModel::startPortForward,
                    onStopRule = viewModel::stopPortForward,
                    onUploadScp = viewModel::uploadScp,
                    onDownloadScp = viewModel::downloadScp,
                )
                AppSection.SETTINGS -> SettingsScreen(
                    vaultStatus = vaultStatus,
                    webDavConfig = webDavConfig,
                    snippets = snippets,
                    portableExport = portableExport,
                    onSaveWebDav = viewModel::saveWebDavConfig,
                    onClearWebDav = viewModel::clearWebDavConfig,
                    onPrepareExport = viewModel::preparePortableExport,
                    onConsumeExport = viewModel::consumePortableExport,
                    onImport = viewModel::importPortable,
                    onUpload = viewModel::uploadWebDav,
                    onDownloadAndImport = viewModel::downloadWebDavAndImport,
                    appLockConfiguration = appLockConfiguration,
                    onConfigureAppPin = viewModel::configureAppPin,
                    onClearAppLock = viewModel::clearAppLock,
                    onSetBiometricEnabled = viewModel::setBiometricUnlockEnabled,
                    onLockNow = viewModel::lockForBackground,
                    onSaveSnippet = viewModel::saveSnippet,
                    onRemoveSnippet = viewModel::removeSnippet,
                )
            }
        }
    }

    if (showHostEditor) {
        HostEditorSheet(
            initialHost = editingHost,
            keys = keys,
            snippets = snippets,
            onDismiss = {
                showHostEditor = false
                editingHost = null
            },
            onSave = { draft ->
                viewModel.saveHost(draft)
                showHostEditor = false
                editingHost = null
            },
        )
    }

    userMessage?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissUserMessage,
            title = { Text("MangoSSH") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissUserMessage) { Text("确定") }
            },
        )
    }
}

@Composable
private fun AppLockScreen(
    configuration: AppLockConfiguration,
    message: String?,
    onUnlockWithPin: (String) -> Unit,
    onRequestBiometric: (() -> Unit)?,
    onDismissMessage: () -> Unit,
) {
    var pin by rememberSaveable { mutableStateOf("") }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Key,
                contentDescription = null,
                modifier = Modifier.size(52.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(20.dp))
            Text("MangoSSH 已锁定", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "使用应用 PIN 解锁。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.filter(Char::isDigit).take(12) },
                label = { Text("应用 PIN") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    onUnlockWithPin(pin)
                    pin = ""
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = pin.isNotEmpty(),
            ) {
                Text("使用 PIN 解锁")
            }
            if (configuration.biometricEnabled && onRequestBiometric != null) {
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = onRequestBiometric, modifier = Modifier.fillMaxWidth()) {
                    Text("使用生物识别")
                }
            }
            message?.let {
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onDismissMessage) { Text(it) }
            }
        }
    }
}

@Composable
private fun HostsScreen(
    hosts: List<ConnectionProfile>,
    sessions: List<website.sung.mangossh.session.TerminalSessionState>,
    vaultStatus: VaultStatus,
    onAddHost: () -> Unit,
    onEditHost: (ConnectionProfile) -> Unit,
    onRemoveHost: (String) -> Unit,
    onConnectHost: (ConnectionProfile) -> Unit,
    onOpenSession: (String) -> Unit,
    onDisconnectSession: (String) -> Unit,
) {
    if (hosts.isEmpty() && sessions.isEmpty()) {
        EmptyHosts(vaultStatus = vaultStatus, onAddHost = onAddHost)
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SecurityBanner(vaultStatus)
        }
        val visibleSessions = sessions.filter { it.phase != TerminalSessionPhase.CLOSED }
        if (visibleSessions.isNotEmpty()) {
            item { Text("活动会话", style = MaterialTheme.typography.titleMedium) }
            items(visibleSessions, key = { it.id }) { session ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(session.title, fontWeight = FontWeight.SemiBold)
                            Text(
                                session.endpoint + " · " + session.phase.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = { onOpenSession(session.id) }) { Text("打开") }
                        TextButton(onClick = { onDisconnectSession(session.id) }) { Text("关闭") }
                    }
                }
            }
        }
        items(items = hosts, key = { it.id }) { host ->
            HostCard(
                host = host,
                onEdit = { onEditHost(host) },
                onRemove = { onRemoveHost(host.id) },
                onConnect = { onConnectHost(host) },
            )
        }
    }
}

@Composable
private fun EmptyHosts(vaultStatus: VaultStatus, onAddHost: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Dns,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(18.dp))
        Text("还没有服务器", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "添加 SSH、Mosh 或 Tailnet 主机配置。密钥会在安全保险库启用后独立管理和共享。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        if (vaultStatus is VaultStatus.Failed) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = vaultStatus.userMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(
            onClick = onAddHost,
            enabled = vaultStatus !is VaultStatus.Failed,
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("新建主机配置")
        }
    }
}

@Composable
private fun SecurityBanner(vaultStatus: VaultStatus) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text("安全优先", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                text = vaultStatus.description(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun HostCard(
    host: ConnectionProfile,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onConnect: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Dns,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(host.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = "${host.username}@${host.endpoint}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "编辑 ${host.label}")
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = {},
                    label = { Text(host.protocol.label) },
                    colors = AssistChipDefaults.assistChipColors(
                        labelColor = MaterialTheme.colorScheme.primary,
                    ),
                )
                AssistChip(onClick = {}, label = { Text(host.route.label) })
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConnect) {
                    Text("连接")
                }
                OutlinedButton(onClick = onEdit) {
                    Text("编辑")
                }
                TextButton(onClick = onRemove) {
                    Text("移除")
                }
            }
        }
    }
}

@Composable
private fun KeysScreen(
    vaultStatus: VaultStatus,
    keys: List<StoredSshKey>,
    onGenerate: (String) -> Unit,
    onImport: (label: String, contents: String, passphrase: String?) -> Unit,
    onRemove: (String) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var showGenerator by rememberSaveable { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<String?>(null) }
    var pendingExport by remember { mutableStateOf<StoredSshKey?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            pendingImport = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.readBytes().decodeToString()
                }
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-pem-file")) { uri ->
        val key = pendingExport
        pendingExport = null
        if (uri == null || key == null) return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(key.privateKeyPem.encodeToByteArray())
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SecurityBanner(vaultStatus) }
        item {
            Text("密钥保险库", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "生成或导入的私钥仅保存在加密保险库内，可复用到多个主机。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showGenerator = true }, enabled = vaultStatus !is VaultStatus.Failed) {
                    Text("生成 Ed25519")
                }
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/x-pem-file", "text/plain", "application/octet-stream")) },
                    enabled = vaultStatus !is VaultStatus.Failed,
                ) {
                    Text("导入私钥")
                }
            }
        }
        if (keys.isEmpty()) {
            item {
                Text(
                    "还没有密钥。建议新建 Ed25519 密钥，或导入 OpenSSH/PEM 私钥。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(keys, key = { it.id }) { key ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(key.label, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${key.algorithm} · ${key.fingerprint}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (key.requiresPassphrase) {
                        Text(
                            "此私钥还需要口令",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { clipboard.setText(AnnotatedString(key.publicKey)) }) {
                            Text("复制公钥")
                        }
                        TextButton(
                            onClick = {
                                pendingExport = key
                                exportLauncher.launch("${key.label.replace(' ', '_')}.pem")
                            },
                        ) {
                            Text("导出私钥")
                        }
                        TextButton(onClick = { onRemove(key.id) }) {
                            Text("移除")
                        }
                    }
                }
            }
        }
    }

    if (showGenerator) {
        KeyLabelDialog(
            title = "生成 Ed25519 密钥",
            confirmLabel = "生成",
            onDismiss = { showGenerator = false },
            onConfirm = { label ->
                onGenerate(label)
                showGenerator = false
            },
        )
    }
    pendingImport?.let { contents ->
        ImportKeyDialog(
            onDismiss = { pendingImport = null },
            onConfirm = { label, passphrase ->
                onImport(label, contents, passphrase.takeIf(String::isNotEmpty))
                pendingImport = null
            },
        )
    }
}

@Composable
private fun KeyLabelDialog(
    title: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var label by rememberSaveable { mutableStateOf("MangoSSH Key") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("密钥名称") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(label.trim()) }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ImportKeyDialog(
    onDismiss: () -> Unit,
    onConfirm: (label: String, passphrase: String) -> Unit,
) {
    var label by rememberSaveable { mutableStateOf("导入的私钥") }
    var passphrase by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入私钥") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "支持 OpenSSH 和 PEM 私钥。若密钥未加密，口令留空即可。",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("密钥名称") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("私钥口令（可选）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(label.trim(), passphrase) }) { Text("导入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun TransfersScreen() {
    FeatureScreen(
        icon = Icons.Outlined.FolderOpen,
        title = "文件与转发",
        description = "SFTP、SCP、本地/远程/SOCKS 转发和传输进度会集中显示在这里。",
        states = listOf(
            "活动传输：0",
            "活动端口转发：0",
            "仅在用户启动会话后运行前台服务",
        ),
    )
}

@Composable
private fun TransfersScreen(
    hosts: List<ConnectionProfile>,
    rules: List<PortForwardRule>,
    activeForwards: List<PortForwardRuntimeState>,
    scpTransfers: List<ScpTransferState>,
    sessions: List<website.sung.mangossh.session.TerminalSessionState>,
    onSaveRule: (PortForwardRule) -> Unit,
    onRemoveRule: (String) -> Unit,
    onStartRule: (String, PortForwardRule) -> Unit,
    onStopRule: (String, String) -> Unit,
    onUploadScp: (String, android.net.Uri, String, String) -> Unit,
    onDownloadScp: (String, String, android.net.Uri) -> Unit,
) {
    val context = LocalContext.current
    var editingRule by remember { mutableStateOf<PortForwardRule?>(null) }
    var showRuleEditor by rememberSaveable { mutableStateOf(false) }
    var showScpDialog by rememberSaveable { mutableStateOf(false) }
    var pendingUpload by remember { mutableStateOf<ScpUploadRequest?>(null) }
    var pendingDownload by remember { mutableStateOf<ScpDownloadRequest?>(null) }
    val openSessions = sessions.filter { it.phase == TerminalSessionPhase.OPEN }
    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val request = pendingUpload
        pendingUpload = null
        if (uri != null && request != null) {
            onUploadScp(request.sessionId, uri, uri.displayName(context), request.remoteDirectory)
        }
    }
    val downloadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val request = pendingDownload
        pendingDownload = null
        if (uri != null && request != null) {
            onDownloadScp(request.sessionId, request.remotePath, uri)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("端口转发", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "本地、远程和 SOCKS5 转发均绑定到已打开的 SSH 会话；会话关闭后转发会自动停止。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Button(
                onClick = {
                    editingRule = null
                    showRuleEditor = true
                },
                enabled = hosts.isNotEmpty(),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("新建转发")
            }
        }
        item {
            OutlinedButton(
                onClick = { showScpDialog = true },
                enabled = openSessions.isNotEmpty(),
            ) { Text("SCP 上传 / 下载") }
        }
        if (scpTransfers.isNotEmpty()) {
            item { Text("SCP 传输", style = MaterialTheme.typography.titleMedium) }
            items(scpTransfers.reversed(), key = { it.id }) { transfer ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        val direction = if (transfer.direction == ScpTransferDirection.UPLOAD) "上传" else "下载"
                        Text("$direction · ${transfer.displayName}", fontWeight = FontWeight.SemiBold)
                        Text(
                            transfer.remotePath,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        val phase = when (transfer.phase) {
                            ScpTransferPhase.QUEUED -> "排队中"
                            ScpTransferPhase.RUNNING -> "传输中"
                            ScpTransferPhase.COMPLETED -> "已完成"
                            ScpTransferPhase.FAILED -> "失败"
                        }
                        Text(
                            listOfNotNull(phase, transfer.detail).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (transfer.phase == ScpTransferPhase.FAILED) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                }
            }
        }
        if (hosts.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "请先创建主机配置，再为其添加端口转发。",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (rules.isEmpty() && hosts.isNotEmpty()) {
            item {
                Text(
                    "还没有端口转发规则。可以设置连接后自动启动。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(rules, key = { it.id }) { rule ->
            val profile = hosts.firstOrNull { it.id == rule.profileId }
            val active = activeForwards.firstOrNull {
                it.rule.id == rule.id &&
                    (it.phase == PortForwardRuntimePhase.ACTIVE || it.phase == PortForwardRuntimePhase.STARTING)
            }
            val eligibleSession = openSessions.firstOrNull { it.profileId == rule.profileId }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(rule.type.label + " · " + (profile?.label ?: "已删除的主机"), fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        rule.displayDescription(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val status = active?.let { runtime ->
                        when (runtime.phase) {
                            PortForwardRuntimePhase.ACTIVE -> "运行中"
                            PortForwardRuntimePhase.STARTING -> "正在启动"
                            else -> ""
                        }
                    } ?: if (rule.startOnConnect) "连接后自动启动" else "未启动"
                    Spacer(Modifier.height(4.dp))
                    Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (active != null) {
                            Button(onClick = { onStopRule(active.sessionId, rule.id) }) { Text("停止") }
                        } else {
                            Button(
                                onClick = { eligibleSession?.let { onStartRule(it.id, rule) } },
                                enabled = eligibleSession != null,
                            ) { Text("启动") }
                        }
                        OutlinedButton(
                            onClick = {
                                editingRule = rule
                                showRuleEditor = true
                            },
                        ) { Text("编辑") }
                        TextButton(onClick = { onRemoveRule(rule.id) }) { Text("移除") }
                    }
                    if (active == null && eligibleSession == null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "连接对应主机后即可手动启动。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (showRuleEditor) {
        PortForwardRuleDialog(
            initial = editingRule,
            hosts = hosts,
            onDismiss = {
                showRuleEditor = false
                editingRule = null
            },
            onSave = { rule ->
                onSaveRule(rule)
                showRuleEditor = false
                editingRule = null
            },
        )
    }
    if (showScpDialog) {
        ScpTransferDialog(
            sessions = openSessions,
            onDismiss = { showScpDialog = false },
            onUpload = { request ->
                pendingUpload = request
                uploadLauncher.launch(arrayOf("*/*"))
                showScpDialog = false
            },
            onDownload = { request ->
                pendingDownload = request
                downloadLauncher.launch(request.suggestedName)
                showScpDialog = false
            },
        )
    }
}

@Composable
private fun PortForwardRuleDialog(
    initial: PortForwardRule?,
    hosts: List<ConnectionProfile>,
    onDismiss: () -> Unit,
    onSave: (PortForwardRule) -> Unit,
) {
    var profileId by rememberSaveable(initial?.id) { mutableStateOf(initial?.profileId ?: hosts.firstOrNull()?.id.orEmpty()) }
    var type by rememberSaveable(initial?.id) { mutableStateOf(initial?.type ?: PortForwardType.LOCAL) }
    var bindHost by rememberSaveable(initial?.id) { mutableStateOf(initial?.bindHost ?: "127.0.0.1") }
    var bindPort by rememberSaveable(initial?.id) { mutableStateOf(initial?.bindPort?.toString().orEmpty()) }
    var destinationHost by rememberSaveable(initial?.id) { mutableStateOf(initial?.destinationHost.orEmpty()) }
    var destinationPort by rememberSaveable(initial?.id) { mutableStateOf(initial?.destinationPort?.toString().orEmpty()) }
    var startOnConnect by rememberSaveable(initial?.id) { mutableStateOf(initial?.startOnConnect ?: false) }
    val bindPortValue = bindPort.toIntOrNull()
    val destinationPortValue = destinationPort.toIntOrNull()
    val validDestination = type == PortForwardType.DYNAMIC ||
        (destinationHost.isNotBlank() && destinationPortValue in 1..65535)
    val canSave = profileId.isNotBlank() && bindPortValue in 1..65535 && validDestination

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新建端口转发" else "编辑端口转发") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("所属主机", style = MaterialTheme.typography.titleSmall)
                hosts.forEach { host ->
                    FilterChip(
                        selected = profileId == host.id,
                        onClick = { profileId = host.id },
                        label = { Text(host.label) },
                    )
                }
                Text("类型", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PortForwardType.entries.forEach { option ->
                        FilterChip(
                            selected = type == option,
                            onClick = { type = option },
                            label = { Text(option.label) },
                        )
                    }
                }
                OutlinedTextField(
                    value = bindHost,
                    onValueChange = { bindHost = it },
                    label = { Text(if (type == PortForwardType.REMOTE) "远端绑定地址" else "本地绑定地址") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = bindPort,
                    onValueChange = { bindPort = it.filter(Char::isDigit).take(5) },
                    label = { Text(if (type == PortForwardType.REMOTE) "远端监听端口" else "本地监听端口") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = bindPort.isNotEmpty() && bindPortValue !in 1..65535,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (type != PortForwardType.DYNAMIC) {
                    OutlinedTextField(
                        value = destinationHost,
                        onValueChange = { destinationHost = it },
                        label = { Text("目标主机") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = destinationPort,
                        onValueChange = { destinationPort = it.filter(Char::isDigit).take(5) },
                        label = { Text("目标端口") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = destinationPort.isNotEmpty() && destinationPortValue !in 1..65535,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text("SOCKS5 代理不需要目标主机和端口。", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = startOnConnect, onCheckedChange = { startOnConnect = it })
                    Text("连接后自动启动")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        PortForwardRule(
                            id = initial?.id ?: java.util.UUID.randomUUID().toString(),
                            profileId = profileId,
                            type = type,
                            bindHost = bindHost.trim().ifEmpty { "127.0.0.1" },
                            bindPort = requireNotNull(bindPortValue),
                            destinationHost = destinationHost.trim().takeIf { type != PortForwardType.DYNAMIC && it.isNotEmpty() },
                            destinationPort = destinationPortValue.takeIf { type != PortForwardType.DYNAMIC },
                            startOnConnect = startOnConnect,
                        ),
                    )
                },
                enabled = canSave,
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private data class ScpUploadRequest(
    val sessionId: String,
    val remoteDirectory: String,
)

private data class ScpDownloadRequest(
    val sessionId: String,
    val remotePath: String,
) {
    val suggestedName: String = remotePath.substringAfterLast('/').ifBlank { "download" }
}

@Composable
private fun ScpTransferDialog(
    sessions: List<website.sung.mangossh.session.TerminalSessionState>,
    onDismiss: () -> Unit,
    onUpload: (ScpUploadRequest) -> Unit,
    onDownload: (ScpDownloadRequest) -> Unit,
) {
    var sessionId by rememberSaveable { mutableStateOf(sessions.firstOrNull()?.id.orEmpty()) }
    var upload by rememberSaveable { mutableStateOf(true) }
    var remotePath by rememberSaveable { mutableStateOf("") }
    val canContinue = sessionId.isNotBlank() && remotePath.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("SCP 文件传输") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("会话", style = MaterialTheme.typography.titleSmall)
                sessions.forEach { session ->
                    FilterChip(
                        selected = sessionId == session.id,
                        onClick = { sessionId = session.id },
                        label = { Text(session.title + " · " + session.endpoint) },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = upload, onClick = { upload = true }, label = { Text("上传") })
                    FilterChip(selected = !upload, onClick = { upload = false }, label = { Text("下载") })
                }
                OutlinedTextField(
                    value = remotePath,
                    onValueChange = { remotePath = it },
                    label = { Text(if (upload) "远端目录" else "远端文件路径") },
                    supportingText = {
                        Text(if (upload) "选择本地文件后将上传到此远端目录。路径不能含空格或 shell 特殊字符。" else "选择保存位置后将下载此远端文件。路径不能含空格或 shell 特殊字符。")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (upload) {
                        onUpload(ScpUploadRequest(sessionId, remotePath.trim()))
                    } else {
                        onDownload(ScpDownloadRequest(sessionId, remotePath.trim()))
                    }
                },
                enabled = canContinue,
            ) { Text(if (upload) "选择本地文件" else "选择保存位置") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun android.net.Uri.displayName(context: android.content.Context): String {
    val fromProvider = context.contentResolver
        .query(this, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    return fromProvider?.takeIf(String::isNotBlank)
        ?: lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank)
        ?: "upload"
}

private fun PortForwardRule.displayDescription(): String = when (type) {
    PortForwardType.LOCAL -> "$bindHost:$bindPort → ${destinationHost.orEmpty()}:${destinationPort ?: "?"}"
    PortForwardType.REMOTE -> "$bindHost:$bindPort ← ${destinationHost.orEmpty()}:${destinationPort ?: "?"}"
    PortForwardType.DYNAMIC -> "$bindHost:$bindPort · SOCKS5"
}

@Composable
private fun SettingsScreen(
    vaultStatus: VaultStatus,
    webDavConfig: WebDavConfig?,
    snippets: List<CommandSnippet>,
    portableExport: ByteArray?,
    onSaveWebDav: (endpoint: String, username: String, password: String, remoteFileName: String) -> Unit,
    onClearWebDav: () -> Unit,
    onPrepareExport: (String) -> Unit,
    onConsumeExport: () -> Unit,
    onImport: (ByteArray, String) -> Unit,
    onUpload: (String) -> Unit,
    onDownloadAndImport: (String) -> Unit,
    appLockConfiguration: AppLockConfiguration,
    onConfigureAppPin: (String) -> Unit,
    onClearAppLock: () -> Unit,
    onSetBiometricEnabled: (Boolean) -> Unit,
    onLockNow: () -> Unit,
    onSaveSnippet: (String?, String, String, Boolean) -> Unit,
    onRemoveSnippet: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showWebDavEditor by rememberSaveable { mutableStateOf(false) }
    var showPinEditor by rememberSaveable { mutableStateOf(false) }
    var editingSnippet by remember { mutableStateOf<CommandSnippet?>(null) }
    var showSnippetEditor by rememberSaveable { mutableStateOf(false) }
    var syncAction by remember { mutableStateOf<SyncAction?>(null) }
    var pendingImport by remember { mutableStateOf<ByteArray?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                pendingImport = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream -> stream.readBytes() }
                }
                if (pendingImport != null) syncAction = SyncAction.MANUAL_IMPORT
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val data = portableExport
        onConsumeExport()
        if (uri != null && data != null) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { stream -> stream.write(data) }
                }
            }
        }
    }
    LaunchedEffect(portableExport) {
        if (portableExport != null) exportLauncher.launch("mangossh-vault.mssh")
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SecurityBanner(vaultStatus) }
        item {
            Text("安全与同步", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "导出和 WebDAV 上传均使用独立同步口令再次加密；不会上传设备绑定的本地保险库密钥。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("加密备份", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { syncAction = SyncAction.MANUAL_EXPORT },
                            enabled = vaultStatus !is VaultStatus.Failed,
                        ) { Text("导出") }
                        OutlinedButton(
                            onClick = { importLauncher.launch(arrayOf("application/octet-stream", "application/json", "text/plain")) },
                            enabled = vaultStatus !is VaultStatus.Failed,
                        ) { Text("导入") }
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("自定义 WebDAV", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        webDavConfig?.let { "${it.endpoint}/${it.remoteFileName}" } ?: "尚未配置",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showWebDavEditor = true }) {
                            Text(if (webDavConfig == null) "配置" else "编辑")
                        }
                        if (webDavConfig != null) {
                            Button(onClick = { syncAction = SyncAction.WEBDAV_UPLOAD }) { Text("上传") }
                            Button(onClick = { syncAction = SyncAction.WEBDAV_DOWNLOAD }) { Text("下载并导入") }
                            TextButton(onClick = onClearWebDav) { Text("移除") }
                        }
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("应用保护", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (appLockConfiguration.pinConfigured) {
                            "应用 PIN 已启用。返回前台时需要解锁；本地保险库仍由 Android Keystore 加密。"
                        } else {
                            "尚未设置应用 PIN。启用后，应用回到前台需要 PIN 或生物识别解锁。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showPinEditor = true }) {
                            Text(if (appLockConfiguration.pinConfigured) "修改 PIN" else "设置 PIN")
                        }
                        if (appLockConfiguration.pinConfigured) {
                            Button(onClick = onLockNow) { Text("立即锁定") }
                            TextButton(onClick = onClearAppLock) { Text("关闭") }
                        }
                    }
                    if (appLockConfiguration.pinConfigured) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = appLockConfiguration.biometricEnabled,
                                onCheckedChange = onSetBiometricEnabled,
                            )
                            Text("允许生物识别解锁", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("连接后代码片段", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "在主机编辑页选择片段后，会在 shell 打开时自动发送。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            editingSnippet = null
                            showSnippetEditor = true
                        },
                    ) { Text("新建片段") }
                }
            }
        }
        if (snippets.isEmpty()) {
            item {
                Text("还没有代码片段。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            items(snippets, key = { it.id }) { snippet ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(snippet.label, fontWeight = FontWeight.SemiBold)
                        Text(
                            snippet.script,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    editingSnippet = snippet
                                    showSnippetEditor = true
                                },
                            ) { Text("编辑") }
                            TextButton(onClick = { onRemoveSnippet(snippet.id) }) { Text("移除") }
                        }
                    }
                }
            }
        }
    }

    if (showWebDavEditor) {
        WebDavConfigDialog(
            initial = webDavConfig,
            onDismiss = { showWebDavEditor = false },
            onSave = { endpoint, username, password, remoteFileName ->
                onSaveWebDav(endpoint, username, password, remoteFileName)
                showWebDavEditor = false
            },
        )
    }
    if (showPinEditor) {
        AppPinDialog(
            onDismiss = { showPinEditor = false },
            onConfirm = { pin ->
                onConfigureAppPin(pin)
                showPinEditor = false
            },
        )
    }
    if (showSnippetEditor) {
        CommandSnippetDialog(
            initial = editingSnippet,
            onDismiss = {
                showSnippetEditor = false
                editingSnippet = null
            },
            onSave = { label, script, appendNewline ->
                onSaveSnippet(editingSnippet?.id, label, script, appendNewline)
                showSnippetEditor = false
                editingSnippet = null
            },
        )
    }
    syncAction?.let { action ->
        SyncPassphraseDialog(
            action = action,
            onDismiss = { syncAction = null },
            onConfirm = { passphrase ->
                when (action) {
                    SyncAction.MANUAL_EXPORT -> onPrepareExport(passphrase)
                    SyncAction.MANUAL_IMPORT -> pendingImport?.let { bytes -> onImport(bytes, passphrase) }
                    SyncAction.WEBDAV_UPLOAD -> onUpload(passphrase)
                    SyncAction.WEBDAV_DOWNLOAD -> onDownloadAndImport(passphrase)
                }
                pendingImport = null
                syncAction = null
            },
        )
    }
}

private enum class SyncAction(val title: String, val warning: String) {
    MANUAL_EXPORT("导出加密备份", "该口令用于保护导出的备份文件。"),
    MANUAL_IMPORT("导入加密备份", "导入会替换当前主机、密钥、片段和同步配置。"),
    WEBDAV_UPLOAD("上传到 WebDAV", "将使用此口令加密后上传。"),
    WEBDAV_DOWNLOAD("从 WebDAV 导入", "下载后会使用口令解密，并替换当前保险库。"),
}

@Composable
private fun WebDavConfigDialog(
    initial: WebDavConfig?,
    onDismiss: () -> Unit,
    onSave: (endpoint: String, username: String, password: String, remoteFileName: String) -> Unit,
) {
    var endpoint by rememberSaveable(initial?.endpoint) { mutableStateOf(initial?.endpoint.orEmpty()) }
    var username by rememberSaveable(initial?.endpoint) { mutableStateOf(initial?.username.orEmpty()) }
    var password by rememberSaveable(initial?.endpoint) { mutableStateOf(initial?.password.orEmpty()) }
    var remoteFileName by rememberSaveable(initial?.endpoint) {
        mutableStateOf(initial?.remoteFileName ?: "mangossh-vault.mssh")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("配置 WebDAV") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("只接受 HTTPS WebDAV 地址。地址应指向要保存备份的目录。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = { Text("WebDAV 目录 URL") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("用户名") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码 / 应用专用密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    value = remoteFileName,
                    onValueChange = { remoteFileName = it },
                    label = { Text("远端文件名") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(endpoint, username, password, remoteFileName) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SyncPassphraseDialog(
    action: SyncAction,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var passphrase by rememberSaveable(action) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(action.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(action.warning, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("同步口令") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(passphrase) }, enabled = passphrase.isNotEmpty()) { Text("继续") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun CommandSnippetDialog(
    initial: CommandSnippet?,
    onDismiss: () -> Unit,
    onSave: (label: String, script: String, appendNewline: Boolean) -> Unit,
) {
    var label by rememberSaveable(initial?.id) { mutableStateOf(initial?.label.orEmpty()) }
    var script by rememberSaveable(initial?.id) { mutableStateOf(initial?.script.orEmpty()) }
    var appendNewline by rememberSaveable(initial?.id) { mutableStateOf(initial?.appendNewline ?: true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新建代码片段" else "编辑代码片段") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = script,
                    onValueChange = { script = it },
                    label = { Text("Shell 命令或脚本") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = appendNewline, onCheckedChange = { appendNewline = it })
                    Text("自动附加换行")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(label.trim(), script, appendNewline) },
                enabled = label.isNotBlank() && script.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun AppPinDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var pin by rememberSaveable { mutableStateOf("") }
    var confirmation by rememberSaveable { mutableStateOf("") }
    val valid = pin.length in 4..12 && pin.all(Char::isDigit) && pin == confirmation
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置应用 PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("PIN 只在本机保存为加盐验证值，不能恢复。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(12) },
                    label = { Text("4–12 位 PIN") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it.filter(Char::isDigit).take(12) },
                    label = { Text("再次输入 PIN") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    visualTransformation = PasswordVisualTransformation(),
                    isError = confirmation.isNotEmpty() && confirmation != pin,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(pin) }, enabled = valid) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun FeatureScreen(
    icon: ImageVector,
    title: String,
    description: String,
    states: List<String>,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                states.forEachIndexed { index, state ->
                    Text(state, style = MaterialTheme.typography.bodyMedium)
                    if (index != states.lastIndex) {
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun HostEditorSheet(
    initialHost: ConnectionProfile?,
    keys: List<StoredSshKey>,
    snippets: List<CommandSnippet>,
    onDismiss: () -> Unit,
    onSave: (ConnectionProfileDraft) -> Unit,
) {
    var label by rememberSaveable(initialHost?.id) { mutableStateOf(initialHost?.label.orEmpty()) }
    var hostname by rememberSaveable(initialHost?.id) { mutableStateOf(initialHost?.hostname.orEmpty()) }
    var portText by rememberSaveable(initialHost?.id) { mutableStateOf((initialHost?.port ?: 22).toString()) }
    var username by rememberSaveable(initialHost?.id) { mutableStateOf(initialHost?.username.orEmpty()) }
    var protocol by rememberSaveable(initialHost?.id) { mutableStateOf(initialHost?.protocol ?: ConnectionProtocol.SSH) }
    var route by rememberSaveable(initialHost?.id) { mutableStateOf(initialHost?.route ?: ConnectionRoute.DIRECT) }
    var authentication by rememberSaveable(initialHost?.id) {
        mutableStateOf(initialHost?.authentication ?: AuthenticationMethod.PRIVATE_KEY)
    }
    var keyId by rememberSaveable(initialHost?.id) { mutableStateOf(initialHost?.keyId) }
    var startupSnippetId by rememberSaveable(initialHost?.id) { mutableStateOf(initialHost?.startupSnippetId) }
    var agentForwarding by rememberSaveable(initialHost?.id) { mutableStateOf(initialHost?.agentForwarding ?: false) }
    val port = portText.toIntOrNull()
    val authenticationIsConfigured = route == ConnectionRoute.TAILNET ||
        authentication != AuthenticationMethod.PRIVATE_KEY ||
        keys.any { it.id == keyId }
    val canSave = hostname.isNotBlank() &&
        username.isNotBlank() &&
        port != null &&
        port in 1..65535 &&
        authenticationIsConfigured

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        ) {
            Text(
                text = if (initialHost == null) "新建服务器" else "编辑服务器",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("名称（可选）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = hostname,
                onValueChange = { hostname = it },
                label = { Text("主机名或 IP 地址") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = hostname.isBlank(),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("用户名") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    isError = username.isBlank(),
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter(Char::isDigit) },
                    label = { Text("端口") },
                    modifier = Modifier.width(112.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = port == null || port !in 1..65535,
                )
            }
            Spacer(Modifier.height(20.dp))
            Text("协议", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConnectionProtocol.entries.forEach { option ->
                    FilterChip(
                        selected = protocol == option,
                        onClick = { protocol = option },
                        label = { Text(option.label) },
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("网络路由", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConnectionRoute.entries.forEach { option ->
                    FilterChip(
                        selected = route == option,
                        onClick = {
                            route = option
                            if (option == ConnectionRoute.DIRECT && authentication == AuthenticationMethod.TAILSCALE_SSH) {
                                authentication = AuthenticationMethod.PRIVATE_KEY
                            }
                        },
                        label = { Text(option.label) },
                    )
                }
            }
            if (route == ConnectionRoute.TAILNET) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Tailnet 路由通过设备已启用的 Tailscale VPN 访问目标；认证方式会设为 Tailscale SSH。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Spacer(Modifier.height(20.dp))
                Text("认证方式", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AuthenticationMethod.entries
                        .filterNot { it == AuthenticationMethod.TAILSCALE_SSH }
                        .forEach { option ->
                            FilterChip(
                                selected = authentication == option,
                                onClick = { authentication = option },
                                label = { Text(option.label) },
                            )
                        }
                }
                if (authentication == AuthenticationMethod.PRIVATE_KEY) {
                    Spacer(Modifier.height(16.dp))
                    Text("共享私钥", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    if (keys.isEmpty()) {
                        Text(
                            "请先在“密钥”页生成或导入一把私钥。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            keys.forEach { key ->
                                FilterChip(
                                    selected = keyId == key.id,
                                    onClick = { keyId = key.id },
                                    label = { Text(key.label) },
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("连接后自动执行", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = startupSnippetId == null,
                    onClick = { startupSnippetId = null },
                    label = { Text("不执行") },
                )
                snippets.forEach { snippet ->
                    FilterChip(
                        selected = startupSnippetId == snippet.id,
                        onClick = { startupSnippetId = snippet.id },
                        label = { Text(snippet.label) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = agentForwarding, onCheckedChange = { agentForwarding = it })
                Text("启用 SSH 代理转发", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = {
                    onSave(
                        ConnectionProfileDraft(
                            id = initialHost?.id,
                            label = label,
                            hostname = hostname,
                            port = requireNotNull(port),
                            username = username,
                            protocol = protocol,
                            route = route,
                            authentication = authentication,
                            keyId = keyId,
                            startupSnippetId = startupSnippetId,
                            agentForwarding = agentForwarding,
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = canSave,
            ) {
                Text("保存配置")
            }
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("取消")
            }
        }
    }
}

@Composable
private fun SessionPromptDialog(
    prompt: SessionPrompt,
    onRespond: (List<String>?) -> Unit,
) {
    when (prompt) {
        is SessionPrompt.HostKeyVerification -> {
            AlertDialog(
                onDismissRequest = { onRespond(null) },
                title = {
                    Text(if (prompt.isChanged) "服务器指纹已变更" else "验证服务器指纹")
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            if (prompt.isChanged) {
                                "保存的服务器密钥与本次连接不一致。仅在确认服务器确实重装或更换密钥后才继续。"
                            } else {
                                "这是首次连接到此服务器。请与管理员提供的指纹核对后再信任。"
                            },
                        )
                        Text("${prompt.hostname}:${prompt.port} · ${prompt.algorithm}")
                        SelectionContainer {
                            Text(prompt.fingerprint, style = MaterialTheme.typography.bodySmall)
                        }
                        prompt.previousFingerprint?.let { previous ->
                            Text("原指纹：$previous", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { onRespond(listOf("trust")) }) {
                        Text(if (prompt.isChanged) "替换并信任" else "信任并连接")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onRespond(null) }) { Text("取消") }
                },
            )
        }

        is SessionPrompt.Authentication -> {
            val answers = remember(prompt.requestId) {
                mutableStateListOf<String>().apply { repeat(prompt.fields.size) { add("") } }
            }
            AlertDialog(
                onDismissRequest = { onRespond(null) },
                title = { Text(prompt.title) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        prompt.instruction?.let { instruction ->
                            SelectionContainer { Text(instruction) }
                        }
                        prompt.fields.forEachIndexed { index, field ->
                            OutlinedTextField(
                                value = answers[index],
                                onValueChange = { answers[index] = it },
                                label = { Text(field.label.ifBlank { "输入" }) },
                                singleLine = true,
                                visualTransformation = if (field.echo) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { onRespond(answers.toList()) }) {
                        Text(if (prompt.fields.isEmpty()) "继续" else "提交")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onRespond(null) }) { Text("取消") }
                },
            )
        }
    }
}

private fun AppSection.icon(): ImageVector = when (this) {
    AppSection.HOSTS -> Icons.Outlined.Dns
    AppSection.KEYS -> Icons.Outlined.Key
    AppSection.TRANSFERS -> Icons.Outlined.FolderOpen
    AppSection.SETTINGS -> Icons.Outlined.Settings
}

private fun sectionSubtitle(section: AppSection): String = when (section) {
    AppSection.HOSTS -> "连接工作区"
    AppSection.KEYS -> "共享密钥与代理"
    AppSection.TRANSFERS -> "SFTP、SCP 与端口转发"
    AppSection.SETTINGS -> "应用锁、同步与 Tailnet"
}

private fun VaultStatus.summary(): String = when (this) {
    VaultStatus.Loading -> "安全保险库：正在打开"
    VaultStatus.Ready -> "安全保险库：已由 Android Keystore 加密"
    is VaultStatus.Failed -> "安全保险库：不可用"
}

private fun VaultStatus.description(): String = when (this) {
    VaultStatus.Loading -> "正在打开本地加密保险库。首次连接仍会验证主机指纹。"
    VaultStatus.Ready -> "本地配置已使用 Android Keystore 和 AES-GCM 加密保存；首次连接仍会验证主机指纹。"
    is VaultStatus.Failed -> userMessage
}
