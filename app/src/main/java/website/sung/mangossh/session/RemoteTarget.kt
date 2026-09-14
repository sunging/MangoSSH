package website.sung.mangossh.session

/** Metadata captured before conflict approval; capability is explicitly advertised by the peer. */
internal data class RemoteTarget(val identity: SourceIdentity?, val permissions: Int?, val atomicReplace: Boolean)

/** Unsupported atomic replacement never silently becomes destructive direct writing. */
internal class AtomicReplaceUnavailableException : java.io.IOException()
