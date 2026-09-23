package website.sung.mangossh.presentation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import website.sung.mangossh.R
import website.sung.mangossh.data.vault.StoredSshKey
import website.sung.mangossh.domain.*
import website.sung.mangossh.session.BoundedProtocolReader
import java.util.UUID

/** Import has no side effects until all identities, jumps, and incompatibilities have been reviewed. */
@Composable
internal fun SshConfigImportDialog(keys: List<StoredSshKey>, hosts: List<ConnectionProfile>, onSave: (List<ConnectionProfile>) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var contents by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<SshConfigPreview?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var acceptedIssues by remember { mutableStateOf(false) }
    val usernames = remember(preview) { mutableStateMapOf<String, String>() }
    val keyIds = remember(preview) { mutableStateMapOf<String, String>() }
    val jumpMappings = remember(preview) { mutableStateMapOf<String, String>() }
    val ids = remember(preview) { preview?.candidates.orEmpty().associate { it.alias to UUID.randomUUID().toString() } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            runCatching { withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { BoundedProtocolReader.bytes(it, 256 * 1024).toString(Charsets.UTF_8) }
                    ?: error("Unreadable configuration")
            } }.onSuccess { contents = it; preview = null; failed = false }.onFailure { failed = true }
            busy = false
        }
    }
    fun mappedJump(raw: String): String? = jumpMappings[raw] ?: ids[raw] ?: hosts.singleOrNull { it.label == raw || it.hostname == raw }?.id
    val candidates = preview?.candidates.orEmpty()
    val profiles = candidates.map { candidate ->
        ConnectionProfile(id = ids.getValue(candidate.alias), label = candidate.alias, hostname = candidate.hostname,
            port = candidate.port, username = usernames[candidate.alias] ?: candidate.username.orEmpty(),
            keyId = keyIds[candidate.alias], authentication = if (keyIds[candidate.alias] == null) AuthenticationMethod.KEYBOARD_INTERACTIVE else AuthenticationMethod.PRIVATE_KEY,
            agentForwarding = candidate.forwardAgent,
            overrides = HostConnectionOverrides(candidate.connectTimeoutSeconds, candidate.keepaliveSeconds),
            jumpProfileIds = candidate.proxyJumps.mapNotNull(::mappedJump))
    }
    val ready = candidates.isNotEmpty() && profiles.all { it.username.isNotBlank() && it.overrides.isValid() && it.jumpProfileIds.size <= 4 && it.id !in it.jumpProfileIds && it.jumpProfileIds.distinct().size == it.jumpProfileIds.size } &&
        candidates.all { candidate ->
            (!(candidate.identitiesOnly || candidate.identityFiles.isNotEmpty()) || keyIds[candidate.alias] != null) &&
                candidate.proxyJumps.all { raw -> mappedJump(raw)?.let { id -> (profiles + hosts).any { it.id == id && it.jumpProfileIds.isEmpty() && it.protocol == ConnectionProtocol.SSH } } == true }
        } && (preview?.issues.isNullOrEmpty() || acceptedIssues)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.ssh_config_import)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ssh_config_no_commands))
            if (preview == null) {
                TextButton(enabled = !busy, onClick = { picker.launch(arrayOf("text/*", "application/octet-stream")) }) { Text(stringResource(R.string.ssh_config_pick)) }
                OutlinedTextField(contents, { if (it.length <= 256 * 1024) contents = it }, label = { Text(stringResource(R.string.ssh_config_contents)) }, maxLines = 8)
                TextButton(enabled = !busy && contents.isNotBlank(), onClick = { scope.launch {
                    busy = true
                    runCatching { withContext(Dispatchers.Default) { SshConfigImport.parse(contents) } }
                        .onSuccess { preview = it; acceptedIssues = false; failed = false }.onFailure { failed = true }
                    busy = false
                } }) { Text(stringResource(R.string.ssh_config_preview)) }
            } else {
                candidates.forEach { candidate ->
                    Text(candidate.alias, style = MaterialTheme.typography.titleSmall)
                    Text("${candidate.hostname}:${candidate.port}")
                    OutlinedTextField(usernames[candidate.alias] ?: candidate.username.orEmpty(), { usernames[candidate.alias] = it },
                        label = { Text(stringResource(R.string.ui_username)) }, singleLine = true)
                    if (candidate.identityFiles.isNotEmpty()) Text(candidate.identityFiles.joinToString("\n"))
                    if (candidate.identitiesOnly || candidate.identityFiles.isNotEmpty()) Text(stringResource(R.string.ssh_config_map_key))
                    keys.forEach { key -> FilterChip(selected = keyIds[candidate.alias] == key.id, onClick = { keyIds[candidate.alias] = key.id }, label = { Text(key.label) }) }
                    candidate.proxyJumps.forEach { raw ->
                        Text(stringResource(R.string.ssh_config_jump, raw))
                        val options = hosts.filter { it.protocol == ConnectionProtocol.SSH && it.jumpProfileIds.isEmpty() }.map { it.id to it.label } +
                            candidates.filter { it.alias != candidate.alias && it.proxyJumps.isEmpty() }.map { ids.getValue(it.alias) to it.alias }
                        options.forEach { (id, label) -> FilterChip(selected = mappedJump(raw) == id, onClick = { jumpMappings[raw] = id }, label = { Text(label) }) }
                    }
                }
                if (!preview?.issues.isNullOrEmpty()) {
                    Text(stringResource(R.string.ssh_config_unsupported))
                    preview?.issues.orEmpty().forEach { Text("${it.line}: ${it.directive}") }
                    Row { Checkbox(acceptedIssues, { acceptedIssues = it }); Text(stringResource(R.string.ssh_config_accept_unsupported)) }
                }
                if (!ready) Text(stringResource(R.string.ssh_config_resolve))
            }
            if (failed) Text(stringResource(R.string.ssh_config_invalid))
            if (busy) CircularProgressIndicator()
        }
    }, confirmButton = { TextButton(enabled = ready && !busy, onClick = { onSave(profiles) }) { Text(stringResource(R.string.ssh_config_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } })
}
