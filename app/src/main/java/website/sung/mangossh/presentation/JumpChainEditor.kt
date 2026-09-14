package website.sung.mangossh.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import website.sung.mangossh.R
import website.sung.mangossh.domain.ConnectionProfile
import website.sung.mangossh.domain.ConnectionProtocol

/** The form shows selected hops only; eligible saved hosts are loaded into a bounded picker on demand. */
@Composable
internal fun JumpChainEditor(jumps: List<String>, onJumps: (List<String>) -> Unit,
    hosts: List<ConnectionProfile>, profileId: String?, protocol: ConnectionProtocol) {
    var picking by remember { mutableStateOf(false) }
    val candidates = hosts.filter { it.id != profileId && it.id !in jumps &&
        it.protocol == ConnectionProtocol.SSH && it.jumpProfileIds.isEmpty() }
    val canAdd = protocol == ConnectionProtocol.SSH && jumps.size < 4 && candidates.isNotEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.host_policy_jumps), style = MaterialTheme.typography.titleSmall)
        if (protocol == ConnectionProtocol.MOSH) Text(stringResource(R.string.host_policy_mosh_jumps))
        jumps.forEachIndexed { index, id ->
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("${index + 1}. ${hosts.firstOrNull { it.id == id }?.label.orEmpty()}",
                    Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { onJumps(jumps - id) }) { Text(stringResource(R.string.common_remove)) }
            }
        }
        if (protocol == ConnectionProtocol.SSH) {
            OutlinedButton(enabled = canAdd, onClick = { picking = true }) { Text(stringResource(R.string.jump_add)) }
        }
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
                        TextButton(modifier = Modifier.fillMaxWidth(), onClick = {
                            onJumps(jumps + host.id)
                            picking = false
                        }) { Text(host.label, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.common_cancel)) } })
    }
}
