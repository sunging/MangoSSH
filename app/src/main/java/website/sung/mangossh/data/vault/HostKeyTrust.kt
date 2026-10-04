package website.sung.mangossh.data.vault

/** RSA signature names identify the same public-key format; this never changes negotiation policy. */
private fun keyFamily(algorithm: String): String = when (algorithm) {
    "rsa-sha2-256", "rsa-sha2-512" -> "ssh-rsa"
    else -> algorithm
}

/** Trust stays scoped to the exact endpoint and public-key family, independently of signature choice. */
internal fun TrustedHostKey.sameHostKeySlot(hostname: String, port: Int, algorithm: String): Boolean =
    this.hostname == hostname && this.port == port && keyFamily(this.algorithm) == keyFamily(algorithm)

/** Conflicting historical aliases require confirmation; matching one stale alias must not allow rollback. */
internal fun isTrustedHostKey(known: List<TrustedHostKey>, hostname: String, port: Int,
    algorithm: String, encoded: String): Boolean {
    val matching = known.filter { it.sameHostKeySlot(hostname, port, algorithm) }
    return matching.isNotEmpty() && matching.all { it.keyBlobBase64 == encoded }
}

/** An explicitly approved replacement revokes every alias of the previous key at this endpoint. */
internal fun replaceTrustedHostKey(known: List<TrustedHostKey>, approved: TrustedHostKey): List<TrustedHostKey> =
    known.filterNot { it.sameHostKeySlot(approved.hostname, approved.port, approved.algorithm) } + approved

/** Key families with at least one trusted key at this endpoint. */
internal fun trustedHostKeyFamilies(known: List<TrustedHostKey>, hostname: String, port: Int): Set<String> =
    known.filter { it.hostname == hostname && it.port == port }.mapTo(mutableSetOf()) { keyFamily(it.algorithm) }

/**
 * Moves host-key algorithms of [trustedFamilies] to the front of [algorithms], keeping
 * the relative order of both groups and never adding a name. Like OpenSSH, this makes a
 * known server prove the key the user already confirmed instead of presenting a new type.
 */
internal fun preferTrustedHostKeyAlgorithms(algorithms: String, trustedFamilies: Set<String>): String {
    if (trustedFamilies.isEmpty()) return algorithms
    val (trusted, other) = algorithms.split(',').partition { keyFamily(it) in trustedFamilies }
    return (trusted + other).joinToString(",")
}

/** Outcome of checking a presented host key against the keys trusted for its endpoint. */
internal sealed interface HostKeyCheck {
    data object Trusted : HostKeyCheck

    /** Nothing is trusted for this endpoint yet. */
    data object FirstUse : HostKeyCheck

    /** A different key of the same family is trusted: the server's key changed. */
    data class Changed(val previousFingerprint: String) : HostKeyCheck

    /**
     * Only keys of other families are trusted. Trusted families are negotiated first,
     * so a known server lands here only after it stopped offering all of them, which
     * deserves the same suspicion as a changed key rather than a first-use prompt.
     */
    data class NewKeyType(val trusted: List<TrustedHostKey>) : HostKeyCheck
}

/** Classifies [encoded] (base64 key blob) of type [algorithm] presented by [hostname]:[port]. */
internal fun classifyHostKey(known: List<TrustedHostKey>, hostname: String, port: Int,
    algorithm: String, encoded: String): HostKeyCheck {
    val endpoint = known.filter { it.hostname == hostname && it.port == port }
    if (isTrustedHostKey(endpoint, hostname, port, algorithm, encoded)) return HostKeyCheck.Trusted
    endpoint.firstOrNull { it.sameHostKeySlot(hostname, port, algorithm) }
        ?.let { return HostKeyCheck.Changed(it.fingerprint) }
    return if (endpoint.isEmpty()) HostKeyCheck.FirstUse else HostKeyCheck.NewKeyType(endpoint)
}
