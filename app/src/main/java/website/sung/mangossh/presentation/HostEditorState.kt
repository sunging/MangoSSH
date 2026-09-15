package website.sung.mangossh.presentation

import java.io.Serializable
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import website.sung.mangossh.domain.*

/** Editor navigation is independent of the shared draft; visiting a detail never persists it. */
internal enum class HostEditorPage { MAIN, CONNECTION, JUMPS, STARTUP, SECURITY, ADVANCED }

/** A single state owner makes back navigation and confirmation independent of the visible page. */
@Stable
internal class HostEditorController(val original: HostEditorDraft) {
    var draft by mutableStateOf(original)
    var page by mutableStateOf(HostEditorPage.MAIN)
    var confirmDiscard by mutableStateOf(false)
    var pendingDraft by mutableStateOf<HostEditorDraft?>(null)
    var confirmingMosh by mutableStateOf(false)

    fun back(onDismiss: () -> Unit) {
        when {
            page != HostEditorPage.MAIN -> page = HostEditorPage.MAIN
            draft != original -> confirmDiscard = true
            else -> onDismiss()
        }
    }

    /** Destructive changes to the unsaved configuration require an explicit, cancelable choice. */
    fun change(next: HostEditorDraft, confirm: Boolean = false, mosh: Boolean = false) {
        if (confirm) { pendingDraft = next; confirmingMosh = mosh } else draft = next
    }

    companion object {
        val StateSaver = Saver<HostEditorController, List<Serializable>>(
            save = { listOf(it.original, it.draft, it.page, it.confirmDiscard, it.pendingDraft ?: "", it.confirmingMosh) },
            restore = { saved -> HostEditorController(saved[0] as HostEditorDraft).apply {
                draft = saved[1] as HostEditorDraft; page = saved[2] as HostEditorPage
                confirmDiscard = saved[3] as Boolean; pendingDraft = saved[4] as? HostEditorDraft
                confirmingMosh = saved[5] as Boolean
            } },
        )
    }
}

@Composable
internal fun rememberHostEditorController(host: ConnectionProfile?) = rememberSaveable(host?.id, saver = HostEditorController.StateSaver) {
    HostEditorController(HostEditorDraft.from(host))
}

/** Saveable form values contain references to keys and snippets, never their secret contents. */
internal data class HostEditorDraft(
    val id: String? = null,
    val label: String = "",
    val hostname: String = "",
    val username: String = "",
    val portText: String = "22",
    val protocol: ConnectionProtocol = ConnectionProtocol.SSH,
    val route: ConnectionRoute = ConnectionRoute.DIRECT,
    val authentication: AuthenticationMethod = AuthenticationMethod.PRIVATE_KEY,
    val keyId: String? = null,
    val startupSnippetId: String? = null,
    val agentForwarding: Boolean = false,
    val overrides: HostConnectionOverrides = HostConnectionOverrides(),
    val agentPolicy: HostAgentPolicy = HostAgentPolicy(),
    val reauthenticate: Boolean? = null,
    val workspace: TmuxWorkspace = TmuxWorkspace(),
    val jumpIds: List<String> = emptyList(),
    val favorite: Boolean = false,
    val position: Int = 0,
    val lastConnectedAtEpochMillis: Long = 0,
    val connectionCount: Int = 0,
) : Serializable {
    /** Summaries identify invalid hidden fields as well as errors on the basic form. */
    fun invalidPages(keyIds: Set<String>, snippetIds: Set<String>, hosts: List<ConnectionProfile>): Set<HostEditorPage> = buildSet {
        if (hostname.isBlank() || username.isBlank() || portText.toIntOrNull() !in 1..65535) add(HostEditorPage.MAIN)
        if (route != ConnectionRoute.TAILNET && authentication == AuthenticationMethod.PRIVATE_KEY && keyId !in keyIds) add(HostEditorPage.CONNECTION)
        if (!workspace.isValid() || startupSnippetId != null && (startupSnippetId !in snippetIds || workspace.mode != WorkspaceMode.DISABLED)) add(HostEditorPage.STARTUP)
        if (jumpIds.size > 4 || jumpIds.distinct().size != jumpIds.size || protocol == ConnectionProtocol.MOSH && jumpIds.isNotEmpty() ||
            jumpIds.any { hop -> hosts.none { it.id == hop && it.id != id && it.protocol == ConnectionProtocol.SSH && it.jumpProfileIds.isEmpty() } }) add(HostEditorPage.JUMPS)
        if (agentPolicy.allowedKeyIds?.any { it !in keyIds } == true) add(HostEditorPage.SECURITY)
        if (!overrides.isValid()) add(HostEditorPage.ADVANCED)
    }

    /** Retains existing host metadata instead of resetting favorites or usage when editing. */
    fun toProfileDraft() = ConnectionProfileDraft(
        id = id, label = label, hostname = hostname, username = username, port = requireNotNull(portText.toIntOrNull()),
        protocol = protocol, route = route, authentication = authentication, keyId = keyId,
        startupSnippetId = startupSnippetId, agentForwarding = protocol == ConnectionProtocol.SSH && agentForwarding,
        overrides = overrides, agentPolicy = agentPolicy, requireReauthentication = reauthenticate,
        workspace = workspace, jumpProfileIds = jumpIds, favorite = favorite, position = position,
        lastConnectedAtEpochMillis = lastConnectedAtEpochMillis, connectionCount = connectionCount,
    )

    companion object {
        fun from(host: ConnectionProfile?) = host?.let {
            HostEditorDraft(it.id, it.label, it.hostname, it.username, it.port.toString(), it.protocol, it.route,
                it.authentication, it.keyId, it.startupSnippetId, it.agentForwarding, it.overrides, it.agentPolicy,
                it.requireReauthentication, it.workspace, it.jumpProfileIds, it.favorite, it.position,
                it.lastConnectedAtEpochMillis, it.connectionCount)
        } ?: HostEditorDraft()
    }
}
