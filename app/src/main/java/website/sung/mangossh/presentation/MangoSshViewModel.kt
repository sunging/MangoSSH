package website.sung.mangossh.presentation

import android.app.Application
import android.net.Uri
import java.util.UUID
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import website.sung.mangossh.data.keys.KeyPassphraseRequiredException
import website.sung.mangossh.data.keys.SshKeyManager
import website.sung.mangossh.data.sync.WebDavClient
import website.sung.mangossh.data.sync.WebDavDownloadResult
import website.sung.mangossh.data.sync.WebDavResult
import website.sung.mangossh.data.vault.VaultRepository
import website.sung.mangossh.data.vault.WebDavConfig
import website.sung.mangossh.data.vault.PortForwardRule
import website.sung.mangossh.data.vault.CommandSnippet
import website.sung.mangossh.domain.ConnectionProfile
import website.sung.mangossh.domain.ConnectionProfileDraft
import website.sung.mangossh.security.AppLockConfiguration
import website.sung.mangossh.security.AppLockStore
import website.sung.mangossh.session.SessionPrompt
import website.sung.mangossh.session.SshSessionController

enum class AppSection(val label: String) {
    HOSTS("主机"),
    KEYS("密钥"),
    TRANSFERS("传输"),
    SETTINGS("设置"),
}

class MangoSshViewModel(application: Application) : AndroidViewModel(application) {
    private val vault = VaultRepository(application)
    private val keyManager = SshKeyManager()
    private val sessionController = SshSessionController(application, vault, keyManager)
    private val webDavClient = WebDavClient()
    private val appLockStore = AppLockStore(application)

    val hosts = vault.snapshot
        .map { snapshot ->
            snapshot.profiles.sortedWith(
                compareByDescending<ConnectionProfile> { it.favorite }.thenBy { it.label.lowercase() },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val vaultStatus = vault.status

    val keys = vault.snapshot
        .map { it.keys.sortedBy { key -> key.label.lowercase() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val snippets = vault.snapshot
        .map { it.snippets.sortedBy { snippet -> snippet.label.lowercase() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val webDavConfig = vault.snapshot
        .map { it.webDavConfig }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val portForwardRules = vault.snapshot
        .map { snapshot -> snapshot.portForwards.sortedBy { rule -> rule.bindPort } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val sessions = sessionController.sessions
    val activePortForwards = sessionController.portForwards
    val scpTransfers = sessionController.scpTransfers
    val resourceSnapshots = sessionController.resourceSnapshots
    val sessionPrompts = sessionController.prompts
    val terminalOutput = sessionController.output

    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage = _userMessage.asStateFlow()

    private val _portableExport = MutableStateFlow<ByteArray?>(null)
    val portableExport = _portableExport.asStateFlow()

    private val _appLockConfiguration = MutableStateFlow(appLockStore.configuration())
    val appLockConfiguration = _appLockConfiguration.asStateFlow()
    private val _appLocked = MutableStateFlow(_appLockConfiguration.value.pinConfigured)
    val appLocked = _appLocked.asStateFlow()

    private val _selectedSection = kotlinx.coroutines.flow.MutableStateFlow(AppSection.HOSTS)
    val selectedSection = _selectedSection.asStateFlow()

    init {
        viewModelScope.launch { vault.open() }
    }

    fun selectSection(section: AppSection) {
        _selectedSection.value = section
    }

    fun saveHost(draft: ConnectionProfileDraft) {
        if (!draft.isValid()) return
        viewModelScope.launch { vault.upsertProfile(draft.toProfile()) }
    }

    fun removeHost(id: String) {
        viewModelScope.launch { vault.removeProfile(id) }
    }

    fun retryVault() {
        viewModelScope.launch { vault.open() }
    }

    fun connect(profile: ConnectionProfile): String = sessionController.connect(profile)

    fun disconnect(sessionId: String) {
        sessionController.close(sessionId)
    }

    fun sendTerminalInput(sessionId: String, bytes: ByteArray) {
        sessionController.send(sessionId, bytes)
    }

    fun resizeTerminal(sessionId: String, columns: Int, rows: Int) {
        sessionController.resize(sessionId, columns, rows)
    }

    fun savePortForward(rule: PortForwardRule) {
        val isDestinationValid = rule.type == website.sung.mangossh.data.vault.PortForwardType.DYNAMIC ||
            (!rule.destinationHost.isNullOrBlank() && rule.destinationPort in 1..65535)
        if (rule.bindPort !in 1..65535 || !isDestinationValid) {
            _userMessage.value = "端口转发配置不完整"
            return
        }
        viewModelScope.launch {
            vault.upsertPortForward(rule)
            _userMessage.value = "已保存端口转发"
        }
    }

    fun removePortForward(ruleId: String) {
        viewModelScope.launch { vault.removePortForward(ruleId) }
    }

    fun saveSnippet(id: String?, label: String, script: String, appendNewline: Boolean) {
        if (label.isBlank() || script.isBlank()) {
            _userMessage.value = "代码片段名称和内容不能为空"
            return
        }
        viewModelScope.launch {
            vault.upsertSnippet(
                CommandSnippet(
                    id = id ?: UUID.randomUUID().toString(),
                    label = label.trim(),
                    script = script,
                    appendNewline = appendNewline,
                ),
            )
            _userMessage.value = "已保存代码片段"
        }
    }

    fun removeSnippet(id: String) {
        viewModelScope.launch { vault.removeSnippet(id) }
    }

    fun startPortForward(sessionId: String, rule: PortForwardRule) {
        sessionController.startPortForward(sessionId, rule)
    }

    fun stopPortForward(sessionId: String, ruleId: String) {
        sessionController.stopPortForward(sessionId, ruleId)
    }

    fun uploadScp(sessionId: String, sourceUri: Uri, displayName: String, remoteDirectory: String) {
        if (remoteDirectory.isBlank()) {
            _userMessage.value = "请输入远端目录"
            return
        }
        runCatching { sessionController.uploadScp(sessionId, sourceUri, displayName, remoteDirectory) }
            .onFailure { _userMessage.value = "远端目录包含不支持的字符" }
    }

    fun downloadScp(sessionId: String, remotePath: String, destinationUri: Uri) {
        if (remotePath.isBlank()) {
            _userMessage.value = "请输入远端文件路径"
            return
        }
        runCatching { sessionController.downloadScp(sessionId, remotePath, destinationUri) }
            .onFailure { _userMessage.value = "远端文件路径包含不支持的字符" }
    }

    fun requestServerResources(sessionId: String) {
        sessionController.requestServerResources(sessionId)
    }

    fun respondToSessionPrompt(prompt: SessionPrompt, values: List<String>?) {
        sessionController.respondToPrompt(prompt.requestId, values)
    }

    fun generateEd25519Key(label: String) {
        viewModelScope.launch {
            runCatching { keyManager.generateEd25519(label) }
                .onSuccess {
                    vault.upsertKey(it)
                    _userMessage.value = "已生成 ${it.label}。"
                }
                .onFailure {
                    _userMessage.value = "无法生成密钥。"
                }
        }
    }

    fun importPrivateKey(label: String, contents: String, passphrase: String?) {
        viewModelScope.launch {
            runCatching { keyManager.importPrivateKey(label, contents, passphrase) }
                .onSuccess {
                    vault.upsertKey(it)
                    _userMessage.value = "已导入 ${it.label}。"
                }
                .onFailure { error ->
                    _userMessage.value = when (error) {
                        is KeyPassphraseRequiredException -> "此密钥需要口令。"
                        else -> "无法导入私钥。请检查格式和口令。"
                    }
                }
        }
    }

    fun removeKey(id: String) {
        viewModelScope.launch { vault.removeKey(id) }
    }

    fun dismissUserMessage() {
        _userMessage.value = null
    }

    fun reportUserMessage(message: String) {
        _userMessage.value = message
    }

    fun saveWebDavConfig(
        endpoint: String,
        username: String,
        password: String,
        remoteFileName: String,
    ) {
        val normalizedEndpoint = endpoint.trim().trimEnd('/')
        val normalizedFileName = remoteFileName.trim().trimStart('/')
        if (!normalizedEndpoint.startsWith("https://") || username.isBlank() || normalizedFileName.isBlank()) {
            _userMessage.value = "请填写 HTTPS WebDAV 地址、用户名和远端文件名。"
            return
        }
        viewModelScope.launch {
            vault.saveWebDavConfig(
                WebDavConfig(
                    endpoint = normalizedEndpoint,
                    username = username.trim(),
                    password = password,
                    remoteFileName = normalizedFileName,
                ),
            )
            _userMessage.value = "已保存 WebDAV 配置。"
        }
    }

    fun clearWebDavConfig() {
        viewModelScope.launch { vault.saveWebDavConfig(null) }
    }

    fun preparePortableExport(passphrase: String) {
        viewModelScope.launch {
            runCatching { vault.exportPortable(passphrase.toCharArray()) }
                .onSuccess { blob ->
                    _portableExport.value = blob
                    _userMessage.value = "请选择备份文件的保存位置。"
                }
                .onFailure {
                    _userMessage.value = "无法创建加密备份。请确认已设置同步口令。"
                }
        }
    }

    fun consumePortableExport() {
        _portableExport.value = null
    }

    fun importPortable(bytes: ByteArray, passphrase: String) {
        viewModelScope.launch {
            runCatching { vault.importPortable(bytes, passphrase.toCharArray()) }
                .onSuccess { _userMessage.value = "已导入加密备份，主机与密钥已替换。" }
                .onFailure { _userMessage.value = "无法导入备份：口令错误或文件已损坏。" }
        }
    }

    fun uploadWebDav(passphrase: String) {
        viewModelScope.launch {
            val config = vault.snapshot.value.webDavConfig
            if (config == null) {
                _userMessage.value = "请先配置 WebDAV。"
                return@launch
            }
            val blob = runCatching { vault.exportPortable(passphrase.toCharArray()) }.getOrElse {
                _userMessage.value = "无法创建加密备份。"
                return@launch
            }
            when (val result = webDavClient.upload(config, blob)) {
                WebDavResult.Success -> _userMessage.value = "已上传加密备份到 WebDAV。"
                is WebDavResult.Failure -> _userMessage.value = result.message
            }
        }
    }

    fun downloadWebDavAndImport(passphrase: String) {
        viewModelScope.launch {
            val config = vault.snapshot.value.webDavConfig
            if (config == null) {
                _userMessage.value = "请先配置 WebDAV。"
                return@launch
            }
            when (val result = webDavClient.download(config)) {
                is WebDavDownloadResult.Failure -> _userMessage.value = result.message
                is WebDavDownloadResult.Success -> {
                    runCatching { vault.importPortable(result.encryptedBlob, passphrase.toCharArray()) }
                        .onSuccess { _userMessage.value = "已从 WebDAV 导入加密备份。" }
                        .onFailure { _userMessage.value = "无法导入 WebDAV 备份：口令错误或文件已损坏。" }
                }
            }
        }
    }

    fun configureAppPin(pin: String) {
        val chars = pin.toCharArray()
        try {
            appLockStore.setPin(chars)
            _appLockConfiguration.value = appLockStore.configuration()
            _appLocked.value = false
            _userMessage.value = "已启用应用 PIN 解锁。"
        } catch (_: IllegalArgumentException) {
            _userMessage.value = "PIN 必须为 4 到 12 位数字。"
        } finally {
            chars.fill('\u0000')
        }
    }

    fun clearAppLock() {
        appLockStore.clear()
        _appLockConfiguration.value = appLockStore.configuration()
        _appLocked.value = false
        _userMessage.value = "已关闭应用锁。"
    }

    fun setBiometricUnlockEnabled(enabled: Boolean) {
        runCatching { appLockStore.setBiometricEnabled(enabled) }
            .onSuccess { _appLockConfiguration.value = appLockStore.configuration() }
            .onFailure { _userMessage.value = "请先设置应用 PIN。" }
    }

    fun lockForBackground() {
        if (_appLockConfiguration.value.pinConfigured) _appLocked.value = true
    }

    fun unlockWithPin(pin: String) {
        val chars = pin.toCharArray()
        try {
            if (appLockStore.verifyPin(chars)) {
                _appLocked.value = false
            } else {
                _userMessage.value = "PIN 不正确。"
            }
        } finally {
            chars.fill('\u0000')
        }
    }

    fun unlockWithBiometrics() {
        if (_appLockConfiguration.value.biometricEnabled) _appLocked.value = false
    }

    override fun onCleared() {
        sessionController.clear()
        super.onCleared()
    }
}
