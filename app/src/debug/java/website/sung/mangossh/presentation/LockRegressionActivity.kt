package website.sung.mangossh.presentation

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import website.sung.mangossh.BiometricGateKey
import website.sung.mangossh.BiometricUnlockPrompt
import website.sung.mangossh.data.keys.SshKeyManager
import website.sung.mangossh.data.vault.*
import website.sung.mangossh.presentation.settings.*
import website.sung.mangossh.security.AppAccessState
import website.sung.mangossh.security.AppLockStore

/** Debug-only owner for real lock, backup and biometric code with no production persistence. */
internal class LockRegressionModel(private val base: Context) : ViewModel() {
    companion object {
        /** Recovers only abandoned debug fixtures after a deliberately interrupted instrumentation run. */
        fun clearAbandoned(base: Context) {
            val names = base.cacheDir.listFiles().orEmpty().filter {
                it.isDirectory && it.name.matches(Regex("lock-regression-[0-9a-f-]{36}"))
            }
            if (names.isEmpty()) return
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            for (directory in names) {
                val prefix = directory.name
                listOf("vault.v1", "history.v1", "passwords.v1", "revisions.v1", "biometric").forEach { store.deleteEntry("$prefix.$it") }
                File(base.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty().filter {
                    it.name.startsWith("$prefix-") && it.name.endsWith(".xml")
                }.forEach { base.deleteSharedPreferences(it.name.removeSuffix(".xml")) }
                directory.deleteRecursively()
            }
        }
    }
    val prefix = "lock-regression-${UUID.randomUUID()}"
    val directoryName = prefix
    private val root = File(base.cacheDir, prefix).apply { mkdirs() }
    private val preferences = mutableSetOf<String>()
    val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int) =
            base.getSharedPreferences("$prefix-$name".also { preferences += it }, mode)
    }
    val access = AppAccessState(false)
    val vault = VaultRepository(context, AndroidKeystoreVault(context, "$prefix.vault.v1"))
    val backup = BackupCoordinator(context, vault, viewModelScope, { !access.locked.value },
        localStoreFactory = { BackupLocalStore(context, prefix) })
    val message = MutableStateFlow<UiText?>(null)
    val lock = AppLockCoordinator(AppLockStore(context), access, viewModelScope, backup::cancel, { message.value = it })
    val ready = MutableStateFlow(false)
    val pin = CharArray(6) { ('0'.code + SecureRandom().nextInt(10)).toChar() }.concatToString()
    val password = UUID.randomUUID().toString()
    val biometricKey = BiometricGateKey("$prefix.biometric")
    var lastUri: Uri? = null
    var lastResultWasCancelled = false
    var pendingRequestCode: Int? = null
    var platformPickerRequestCode: Int? = null
    var page by mutableStateOf("backup")
    var key: StoredSshKey? = null

    init {
        viewModelScope.launch {
            vault.open()
            key = withContext(Dispatchers.Default) { SshKeyManager().generateEd25519("Disposable key") }
            lock.configurePin(pin)
            lock.busy.first { !it }
            lock.setBiometricEnabled(true)
            ready.value = true
        }
    }

    fun saved(uri: Uri?) {
        lastUri = uri; lastResultWasCancelled = uri == null
        backup.writeExport(uri)
    }

    /** All namespaces and aliases are generated by this model; production stores are unreachable. */
    fun dispose() {
        backup.cancel()
        biometricKey.reset()
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        listOf("vault", "history", "passwords", "revisions").forEach { store.deleteEntry("$prefix.$it.v1") }
        preferences.forEach { name ->
            // Drain queued apply writes before removing this fixture's preference file.
            base.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            base.deleteSharedPreferences(name)
        }
        root.deleteRecursively()
    }
}

/**
 * Exercises production lifecycle and screens while redirecting only the picker location to a
 * test-owned directory. No exported activity or release build contains this fixture.
 */
class LockRegressionActivity : AppCompatActivity() {
    internal lateinit var model: LockRegressionModel
    private lateinit var biometric: BiometricUnlockPrompt
    private lateinit var pickerRegistry: ActivityResultRegistry
    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        model.pendingRequestCode?.let { code ->
            model.pendingRequestCode = null
            model.platformPickerRequestCode = null
            pickerRegistry.dispatchResult(code, result.resultCode, result.data)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                LockRegressionModel.clearAbandoned(applicationContext)
                return LockRegressionModel(applicationContext) as T
            }
        })[LockRegressionModel::class.java]
        biometric = BiometricUnlockPrompt(this, model.lock, { model.message.value = uiText(website.sung.mangossh.R.string.biometric_unavailable) }, model.biometricKey)
        lifecycle.addObserver(AppLockActivityObserver(this, model.lock, biometric::cancel))
        pickerRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                model.pendingRequestCode = requestCode
                picker.launch(contract.createIntent(this@LockRegressionActivity, input).apply {
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(
                        "com.android.externalstorage.documents", "primary:Download/${model.directoryName}"))
                })
            }
        }
        // Keep the Compose registry's opaque pending result registrations through rotation as well.
        pickerRegistry.onRestoreInstanceState(savedInstanceState?.getBundle("fixture-picker"))
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry get() = pickerRegistry }
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDocumentPickerRequests provides model.lock.documents,
                    LocalActivityResultRegistryOwner provides owner) {
                    val ready by model.ready.collectAsState()
                    val locked by model.lock.locked.collectAsState()
                    val configuration by model.lock.configuration.collectAsState()
                    val busy by model.lock.busy.collectAsState()
                    val message by model.message.collectAsState()
                    if (!ready) Text("Preparing isolated fixture", Modifier.testTag("fixture_preparing"))
                    else if (locked) AppLockScreen(busy, configuration, message, model.lock::unlockWithPin,
                        biometric::authenticate, { model.message.value = null })
                    else Column {
                        Button(onClick = { model.page = "backup" }) { Text("Backup fixture") }
                        Button(onClick = { model.page = "security" }) { Text("Security fixture") }
                        Button(onClick = { model.page = "keys" }) { Text("Keys fixture") }
                        Button(onClick = model.lock::lock) { Text("Lock fixture") }
                        when (model.page) {
                            "keys" -> KeyExportButtons()
                            "security" -> SecuritySettingsPage(SecuritySettingsState(configuration), SecuritySettingsCallbacks(
                                model.lock::configurePin, model.lock::clear, model.lock::setBiometricEnabled,
                                model.lock::lock, model.lock::setAutoLockDelay, model.lock::setReauthentication))
                            else -> {
                                val operation by model.backup.state.collectAsState()
                                BackupSettingsPage(BackupSettingsState(VaultStatus.Ready, null, operation), BackupSettingsCallbacks(
                                    { _, _, _, _ -> }, {}, { password, remember, config -> model.backup.prepareExport(password, remember, config) },
                                    model::saved, { uri, password, remember -> model.backup.importFile(uri, password, remember) },
                                    { _, _ -> }, { _, _ -> }, model.backup::commit, model.backup::cancel, {}, {}, { _, _ -> }, {}, {}))
                            }
                        }
                    }
                }
            }
        }
    }

    /** Records only document launches so cancellation cannot send a late Back key to the host. */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        if (::model.isInitialized && intent.action in setOf(Intent.ACTION_CREATE_DOCUMENT,
                Intent.ACTION_OPEN_DOCUMENT, Intent.ACTION_OPEN_DOCUMENT_TREE)) {
            model.platformPickerRequestCode = requestCode
        }
        super.startActivityForResult(intent, requestCode, options)
    }

    @Composable private fun KeyExportButtons() {
        val scope = rememberCoroutineScope()
        val publicKey = rememberAppDocumentLauncher(SystemDocumentContract("*/*")) { uri ->
            model.lastUri = uri
            if (uri != null) scope.launch(Dispatchers.IO) {
                contentResolver.openOutputStream(uri, "wt")!!.use { it.write(checkNotNull(model.key).publicKey.encodeToByteArray()) }
            }
        }
        val privateKey = rememberAppDocumentLauncher(SystemDocumentContract("application/x-pem-file")) { uri ->
            model.lastUri = uri
            if (uri != null) scope.launch(Dispatchers.IO) {
                contentResolver.openOutputStream(uri, "wt")!!.use { it.write(checkNotNull(model.key).privateKeyPem.encodeToByteArray()) }
            }
        }
        Button(onClick = { publicKey.launch("ssh_key.pub") }) { Text("Export public fixture") }
        Button(onClick = { privateKey.launch("ssh_key.pem") }) { Text("Export private fixture") }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBundle("fixture-picker", Bundle().also(pickerRegistry::onSaveInstanceState))
        super.onSaveInstanceState(outState)
    }
}
