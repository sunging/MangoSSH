package website.sung.mangossh.session

import com.trilead.ssh2.ChannelCondition
import com.trilead.ssh2.Connection
import website.sung.mangossh.domain.TmuxWorkspace
import website.sung.mangossh.domain.WorkspaceMode

/** A server ID is the attach target; a display name never substitutes for it. */
data class RemoteWorkspace(val id: String, val name: String)

/** Optional tmux management uses bounded exec channels, never an interactive terminal's input. */
internal object TmuxWorkspaces {
    fun list(connection: Connection): List<RemoteWorkspace> {
        requireAvailable(connection)
        val result = command(connection, "tmux list-sessions -F '#{session_id}\\t#{session_name}'")
        if (result.second != 0) return emptyList()
        return result.first.lineSequence().mapNotNull { line ->
            val split = line.indexOf('\\').takeIf { it >= 0 && line.substring(it).startsWith("\\t") }
                ?: line.indexOf('\t').takeIf { it >= 0 } ?: return@mapNotNull null
            val id = line.substring(0, split)
            val name = line.substring(split + if (line[split] == '\t') 1 else 2)
            if (!validId(id)) null else RemoteWorkspace(id, name.take(256))
        }.take(200).toList()
    }

    /** Resolves a configured startup to an exact remote session ID before opening the PTY. */
    fun prepare(connection: Connection, workspace: TmuxWorkspace): String? {
        require(workspace.isValid())
        return when (workspace.mode) {
            WorkspaceMode.DISABLED -> null
            WorkspaceMode.ATTACH -> list(connection).firstOrNull { it.id == workspace.sessionId }?.id
                ?: throw WorkspaceUnavailableException()
            WorkspaceMode.CREATE, WorkspaceMode.CREATE_OR_ATTACH -> {
                requireAvailable(connection)
                val reuse = workspace.mode == WorkspaceMode.CREATE_OR_ATTACH
                if (reuse) findByExactName(connection, workspace.name)?.let { return it }
                val result = command(connection, "tmux new-session -d -P -F '#{session_id}' -s ${quote(workspace.name)}")
                result.first.trim().takeIf { result.second == 0 && validId(it) }
                    // Another client may create the requested name between lookup and creation.
                    ?: (if (reuse) findByExactName(connection, workspace.name) else null)
                    ?: throw WorkspaceUnavailableException()
            }
        }
    }

    /** The trailing colon identifies a session within a pane target; = disables prefix matching. */
    private fun findByExactName(connection: Connection, name: String): String? {
        val result = command(connection, "tmux display-message -p -t ${quote("=$name:")} '#{session_id}'")
        return result.first.trim().takeIf { result.second == 0 && validId(it) }
    }

    fun attachCommand(id: String): String {
        require(validId(id))
        return "tmux attach-session -t ${quote(id)}"
    }

    private fun requireAvailable(connection: Connection) {
        if (command(connection, "tmux -V").second != 0) throw WorkspaceUnavailableException()
    }

    private fun command(connection: Connection, command: String): Pair<String, Int?> = BlockingOperation(15_000).use { operation ->
        val session = connection.openSession()
        check(operation.own(session))
        try {
            session.execCommand(command)
            val output = BoundedProtocolReader.bytes(session.stdout, 32 * 1024).toString(Charsets.UTF_8)
            session.waitForCondition(ChannelCondition.EXIT_STATUS or ChannelCondition.CLOSED, 15_000)
            output to session.exitStatus
        } finally { operation.release(session) }
    }

    private fun validId(value: String) = value.matches(Regex("\\$[0-9]{1,10}"))
    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}

/** Sanitized missing tmux/session category; server output is never diagnostic text. */
internal class WorkspaceUnavailableException : Exception()
