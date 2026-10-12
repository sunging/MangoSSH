package website.sung.mangossh.session

import website.sung.mangossh.session.ssh.SshFileAttributes

/**
 * Validates a reserved path and its opened handle before any truncation or write.
 * Windows OpenSSH projects ACLs through LSTAT and CRT modes through FSTAT, so
 * their access bits cannot be compared directly. Path-to-path access checks stay
 * exact; handle type, ownership, size and modification time still bind the open.
 */
internal object RemoteStagingIdentity {
    /** Drive paths are canonical Windows SFTP paths, not shell commands or OS banners. */
    fun isWindowsPath(path: String): Boolean = path.length >= 3 && path[0] == '/' &&
        (path[1] in 'A'..'Z' || path[1] in 'a'..'z') && path[2] == ':' &&
        (path.length == 3 || path[3] == '/')

    /** Refuses links, path substitution and changed metadata before the handle can modify content. */
    fun requireMatches(path: String, before: SshFileAttributes?, opened: SshFileAttributes, after: SshFileAttributes) {
        if (before == null || before.permissions != null && !before.isRegularFile ||
            opened.permissions != null && !opened.isRegularFile || after.permissions != null && !after.isRegularFile)
            throw SourceChangedException()
        if (before.uid != after.uid || before.gid != after.gid || before.permissions != after.permissions ||
            before.size != after.size || before.mtime != after.mtime)
            throw SourceChangedException()
        if (opened.uid != after.uid || opened.gid != after.gid || opened.size != after.size || opened.mtime != after.mtime)
            throw SourceChangedException()
        if (!isWindowsPath(path) && opened.permissions != after.permissions) throw SourceChangedException()
    }
}
