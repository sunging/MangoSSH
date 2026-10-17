package website.sung.mangossh.presentation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import website.sung.mangossh.R
import website.sung.mangossh.data.keys.KeyEditRequest
import website.sung.mangossh.data.keys.SshKeyGenerationType
import website.sung.mangossh.data.vault.AuthenticationSummary
import website.sung.mangossh.data.vault.SshKeySummary
import website.sung.mangossh.data.vault.VaultStatus
import website.sung.mangossh.domain.ConnectionProfile
import website.sung.mangossh.session.BoundedProtocolReader

/** Null replacement keeps a password; removing it is always an explicit editor action. */
internal class AuthenticationEditRequest(val token: String, val id: String?, val label: String,
    val password: String?, val clearPassword: Boolean, val keyId: String?) {
    override fun toString(): String = "AuthenticationEditRequest(<redacted>)"
}

/** Temporary commands never return private key material to Compose. */
internal sealed class AuthenticationKeyRequest {
    class Generate(val type: SshKeyGenerationType, val label: String, val passphrase: String?, val remember: Boolean) : AuthenticationKeyRequest()
    class Import(val label: String, val contents: String, val passphrase: String?, val remember: Boolean) : AuthenticationKeyRequest()
    class Edit(val id: String, val request: KeyEditRequest) : AuthenticationKeyRequest()
    final override fun toString(): String = "AuthenticationKeyRequest(<redacted>)"
}

/** Backend ownership of staged keys ends when the editor is disposed. */
internal class AuthenticationEditorActions(
    val begin: (String) -> Unit = {},
    val discard: (String) -> Unit = {},
    val prepare: (String, AuthenticationKeyRequest, (SshKeySummary?, UiText?) -> Unit) -> Unit = { _, _, done -> done(null, null) },
    val save: (AuthenticationEditRequest, EditorSaveOperation) -> Unit = { _, _ -> },
)

/** A unified list of reusable credentials with no secret values in its inputs. */
@Composable
internal fun AuthenticationScreen(vaultStatus: VaultStatus, authentications: List<AuthenticationSummary>,
    keys: List<SshKeySummary>, hosts: List<ConnectionProfile>, actions: AuthenticationEditorActions,
    onRemove: (String) -> Unit, onExport: (String, KeyExportPart, KeyExportTarget) -> Unit, busy: Boolean) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var exportingId by remember { mutableStateOf<String?>(null) }
    var exportFileId by remember { mutableStateOf<String?>(null) }
    val publicFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        exportFileId?.let { if (uri != null) onExport(it, KeyExportPart.PUBLIC, KeyExportTarget.File(uri)) }; exportFileId = null
    }
    val privateFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-pem-file")) { uri ->
        exportFileId?.let { if (uri != null) onExport(it, KeyExportPart.PRIVATE, KeyExportTarget.File(uri)) }; exportFileId = null
    }
    LazyColumn(Modifier.fillMaxSize().testTag("authentication_list"), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(stringResource(R.string.authentication_vault_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.authentication_summary), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { Button(onClick = { adding = true }, enabled = !busy && vaultStatus is VaultStatus.Ready,
            modifier = Modifier.testTag("authentication_add")) { Text(stringResource(R.string.authentication_add)) } }
        if (vaultStatus !is VaultStatus.Ready) item {
            Text(stringResource(if (vaultStatus is VaultStatus.Loading) R.string.ui_secure_vault_opening else R.string.ui_secure_vault_unavailable))
        }
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (authentications.isEmpty()) item { Text(stringResource(R.string.authentication_empty)) }
        items(authentications, key = { it.id }) { authentication ->
            val references = hosts.count { it.authenticationId == authentication.id }
            Card(Modifier.fillMaxWidth().testTag("authentication_${authentication.id}")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(authentication.label, style = MaterialTheme.typography.titleMedium)
                    Text(authentication.configurationLabel(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(pluralStringResource(R.plurals.authentication_password_host_count, references, references), style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { editingId = authentication.id }, enabled = !busy,
                            modifier = Modifier.testTag("authentication_edit_${authentication.id}")) { Text(stringResource(R.string.common_edit)) }
                        if (authentication.keyId != null) TextButton(onClick = { exportingId = authentication.keyId }, enabled = !busy) { Text(stringResource(R.string.ui_export)) }
                        TextButton(onClick = { onRemove(authentication.id) }, enabled = !busy,
                            modifier = Modifier.testTag("authentication_remove_${authentication.id}"),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.common_remove)) }
                    }
                }
            }
        }
    }
    if (adding) AuthenticationEditorDialog(null, keys, actions) { adding = false }
    authentications.firstOrNull { it.id == editingId }?.let { AuthenticationEditorDialog(it, keys, actions) { editingId = null } }
    keys.firstOrNull { it.id == exportingId }?.let { key ->
        ExportKeyDialog(key, { exportingId = null }) { part, target ->
            exportingId = null
            when (target) {
                KeyExportDestination.CLIPBOARD -> onExport(key.id, part, KeyExportTarget.Clipboard)
                KeyExportDestination.SHARE -> onExport(key.id, part, KeyExportTarget.Share)
                KeyExportDestination.FILE -> {
                    exportFileId = key.id
                    if (part == KeyExportPart.PUBLIC) publicFile.launch("ssh_key.pub") else privateFile.launch("ssh_key.pem")
                }
            }
        }
    }
}

/** Names may survive recreation; passwords, imported material and staged key IDs never do. */
@Composable
internal fun AuthenticationEditorDialog(authentication: AuthenticationSummary?, keys: List<SshKeySummary>,
    actions: AuthenticationEditorActions, onDismiss: () -> Unit) {
    val token = remember { UUID.randomUUID().toString() }
    val save = remember { EditorSaveOperation(onDismiss) }
    DisposableEffect(token) { actions.begin(token); onDispose { save.dispose(); actions.discard(token) } }
    var label by rememberSaveable(authentication?.id) { mutableStateOf(authentication?.label.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var clearPassword by remember { mutableStateOf(false) }
    var keyId by remember { mutableStateOf(authentication?.keyId) }
    var staged by remember { mutableStateOf<SshKeySummary?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<UiText?>(null) }
    var generate by remember { mutableStateOf(false) }
    var editKey by remember { mutableStateOf(false) }
    var importContents by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    fun prepare(request: AuthenticationKeyRequest) {
        preparing = true; error = null
        actions.prepare(token, request) { key, failure ->
            preparing = false; error = failure
            if (key != null) { staged = key; keyId = key.id }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            preparing = true
            try { importContents = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use {
                BoundedProtocolReader.bytes(it, 1024 * 1024).decodeToString()
            } }; if (importContents == null) error = uiText(R.string.message_key_import_failed)
            } catch (_: Exception) { error = uiText(R.string.message_key_import_failed) }
            finally { preparing = false }
        }
    }
    val choices = keys.filterNot { it.id == staged?.id } + listOfNotNull(staged)
    val selectedKey = choices.firstOrNull { it.id == keyId }
    val density = LocalDensity.current
    AlertDialog(onDismissRequest = { if (!save.busy) onDismiss() }, modifier = Modifier.testTag("authentication_editor"),
        title = { Text(stringResource(if (authentication == null) R.string.authentication_add else R.string.authentication_edit)) },
        text = { CompositionLocalProvider(LocalDensity provides density) {
            Column(Modifier.imePadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(label, { label = it }, singleLine = true, enabled = !save.busy && !preparing,
                    label = { Text(stringResource(R.string.authentication_name)) }, modifier = Modifier.fillMaxWidth().testTag("authentication_name"))
                OutlinedTextField(password, { password = it }, singleLine = true, enabled = !save.busy && !preparing && !clearPassword,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    label = { Text(stringResource(R.string.authentication_password_field)) },
                    supportingText = { Text(stringResource(if (authentication?.hasPassword == true) R.string.authentication_password_keep_empty else R.string.authentication_password_optional)) },
                    modifier = Modifier.fillMaxWidth().testTag("authentication_password_value"))
                if (authentication?.hasPassword == true) Row(Modifier.fillMaxWidth()) {
                    Checkbox(clearPassword, { clearPassword = it; if (it) password = "" }, enabled = !save.busy && !preparing,
                        modifier = Modifier.testTag("authentication_clear_password"))
                    Text(stringResource(R.string.authentication_clear_password), Modifier.weight(1f).padding(top = 12.dp))
                }
                val none = stringResource(R.string.authentication_no_key)
                HostEditorChoice(stringResource(R.string.ui_shared_private_key), selectedKey?.label ?: none, keyId,
                    listOf(null) + choices.map { it.id }, { id -> choices.firstOrNull { it.id == id }?.label ?: none },
                    { if (!preparing && !save.busy) keyId = it }, "authentication_key", searchable = true,
                    error = keyId != null && selectedKey == null)
                selectedKey?.let { key ->
                    Text(key.algorithm, style = MaterialTheme.typography.bodySmall)
                    if (key.fingerprint.isNotBlank()) Text(key.fingerprint, style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    if (key.requiresPassphrase) Text(stringResource(if (key.hasSavedPassphrase) R.string.ui_passphrase_saved_in_vault
                        else R.string.ui_this_private_key_also_requires_its_passphrase), style = MaterialTheme.typography.bodySmall)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ generate = true }, enabled = !save.busy && !preparing) { Text(stringResource(R.string.ui_generate_key)) }
                    TextButton({ picker.launch(arrayOf("text/*", "application/octet-stream", "application/x-pem-file")) }, enabled = !save.busy && !preparing) { Text(stringResource(R.string.ui_import_private_key)) }
                    if (selectedKey != null) TextButton({ editKey = true }, enabled = !save.busy && !preparing) { Text(stringResource(R.string.ui_edit_key)) }
                }
                if (staged != null) Text(stringResource(R.string.authentication_key_staged), style = MaterialTheme.typography.bodySmall)
                if (preparing) LinearProgressIndicator(Modifier.fillMaxWidth())
                (save.error ?: error)?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
                Text(stringResource(R.string.authentication_summary), style = MaterialTheme.typography.bodySmall)
            }
        } },
        confirmButton = { TextButton(onClick = { actions.save(AuthenticationEditRequest(token, authentication?.id, label,
            password.takeIf { it.isNotEmpty() }, clearPassword, keyId), save) },
            enabled = !save.busy && !preparing && label.isNotBlank() && (keyId == null || selectedKey != null),
            modifier = Modifier.testTag("authentication_save")) { Text(stringResource(R.string.common_save)) } },
        dismissButton = { TextButton(onDismiss, enabled = !save.busy, modifier = Modifier.testTag("authentication_cancel")) { Text(stringResource(R.string.common_cancel)) } })
    if (generate) GenerateKeyDialog({ generate = false }) { type, name, passphrase, remember ->
        generate = false; prepare(AuthenticationKeyRequest.Generate(type, name, passphrase.takeIf { it.isNotEmpty() }, remember))
    }
    importContents?.let { contents -> ImportKeyDialog({ importContents = null }) { name, passphrase, remember ->
        importContents = null; prepare(AuthenticationKeyRequest.Import(name, contents, passphrase.takeIf { it.isNotEmpty() }, remember))
    } }
    if (editKey && selectedKey != null) EditKeyDialog(selectedKey, { editKey = false }) { request ->
        editKey = false; prepare(AuthenticationKeyRequest.Edit(selectedKey.id, request))
    }
}

/** Searchable credential reference with an explicit prompt-at-connect choice. */
@Composable
internal fun AuthenticationChoice(selected: String?, authentications: List<AuthenticationSummary>, onSelect: (String?) -> Unit, tag: String) {
    val ask = stringResource(R.string.authentication_ask_at_connection)
    val missing = stringResource(R.string.host_editor_needs_attention)
    fun label(id: String?) = if (id == null) ask else authentications.firstOrNull { it.id == id }?.label ?: missing
    HostEditorChoice(stringResource(R.string.ui_authentication), label(selected), selected, listOf(null) + authentications.map { it.id },
        { label(it) }, onSelect, tag, searchable = true, error = selected != null && authentications.none { it.id == selected })
}

/** Reports configured kinds without revealing a value or password length. */
@Composable
private fun AuthenticationSummary.configurationLabel(): String = stringResource(when {
    keyId != null && hasPassword -> R.string.authentication_has_both
    keyId != null -> R.string.authentication_has_key
    hasPassword -> R.string.authentication_has_password
    else -> R.string.authentication_has_neither
})
