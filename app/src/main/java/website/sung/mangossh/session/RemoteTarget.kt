package website.sung.mangossh.session

/** Metadata captured before conflict approval; capability is explicitly advertised by the peer. */
internal data class RemoteTarget(val identity: SourceIdentity?, val permissions: Int?, val atomicReplace: Boolean,
    val uid: Int? = null, val gid: Int? = null) {
    /** Capability is not identity; ownership and mode changes invalidate an approval. */
    fun matches(other: RemoteTarget): Boolean = identity == other.identity && permissions == other.permissions && uid == other.uid && gid == other.gid
}

/** Replacement must not silently change the target's owner or access policy. */
internal class MetadataPreservationException : java.io.IOException()

/** Unsupported atomic replacement never silently becomes destructive direct writing. */
internal class AtomicReplaceUnavailableException : java.io.IOException()
