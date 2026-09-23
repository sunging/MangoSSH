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
