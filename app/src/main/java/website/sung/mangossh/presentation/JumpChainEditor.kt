package website.sung.mangossh.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import website.sung.mangossh.R
import website.sung.mangossh.domain.ConnectionProfile
import website.sung.mangossh.domain.ConnectionProtocol
import website.sung.mangossh.ui.components.MangoPreferenceGroup

/** The form shows selected hops only; eligible saved hosts are loaded into a bounded picker on demand. */
@Composable
internal fun JumpChainEditor(jumps: List<String>, onJumps: (List<String>) -> Unit,
    hosts: List<ConnectionProfile>, profileId: String?, protocol: ConnectionProtocol) {
    var picking by remember { mutableStateOf(false) }
    val candidates = hosts.filter { it.id != profileId && it.id !in jumps &&
        it.protocol == ConnectionProtocol.SSH && it.jumpProfileIds.isEmpty() }
    val canAdd = protocol == ConnectionProtocol.SSH && jumps.size < 4 && candidates.isNotEmpty()
    MangoPreferenceGroup {
        if (jumps.isEmpty()) {
            Text(stringResource(R.string.host_editor_not_configured), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        }
        jumps.forEachIndexed { index, id ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}. ${hosts.firstOrNull { it.id == id }?.label.orEmpty()}",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { onJumps(jumps - id) }) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.common_remove))
                }
            }
        }
    }
    if (protocol == ConnectionProtocol.SSH) {
        OutlinedButton(enabled = canAdd, onClick = { picking = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(Icons.Outlined.Add, null, Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.jump_add))
        }
    } else {
        HostEditorFootnote(stringResource(R.string.host_policy_mosh_jumps))
    }
    if (picking && canAdd) {
        var search by remember { mutableStateOf("") }
        val visible = candidates.filter { it.label.contains(search, ignoreCase = true) || it.hostname.contains(search, ignoreCase = true) }
        AlertDialog(onDismissRequest = { picking = false }, title = { Text(stringResource(R.string.jump_pick)) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(search, { search = it }, label = { Text(stringResource(R.string.jump_search)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                if (visible.isEmpty()) Text(stringResource(R.string.jump_no_matches))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp).testTag("jump-picker-list")) {
                    items(visible, key = { it.id }) { host ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .clickable(role = Role.Button) {
                                onJumps(jumps + host.id)
                                picking = false
                            }.padding(horizontal = 4.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(host.label, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }, confirmButton = {}, dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.common_cancel)) } })
    }
}
