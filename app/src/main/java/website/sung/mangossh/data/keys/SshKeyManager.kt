package website.sung.mangossh.data.keys

import website.sung.mangossh.session.ssh.SshKeyCodec
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import website.sung.mangossh.data.vault.StoredSshKey

/** Algorithms and strengths available when creating a new SSH client key. */
enum class SshKeyGenerationType(
    internal val defaultLabel: String,
) {
    ED25519("MangoSSH Ed25519"),
    ECDSA_P256("MangoSSH ECDSA P-256"),
    ECDSA_P384("MangoSSH ECDSA P-384"),
    ECDSA_P521("MangoSSH ECDSA P-521"),
    RSA_2048("MangoSSH RSA 2048"),
    RSA_3072("MangoSSH RSA 3072"),
    RSA_4096("MangoSSH RSA 4096"),
}

/**
 * Imports and creates client keys entirely in memory. Persisting a returned key
 * is the caller's responsibility; MangoSSH stores it only inside the encrypted vault.
 */
class SshKeyManager {
    /**
     * Creates an OpenSSH private key for the requested algorithm, encrypted when [passphrase]
     * is non-empty. The passphrase is kept in the record only when [rememberPassphrase] is set.
     */
    fun generateKey(
        type: SshKeyGenerationType,
        label: String,
        passphrase: String? = null,
        rememberPassphrase: Boolean = false,
    ): StoredSshKey {
        val encrypt = !passphrase.isNullOrEmpty()
        val normalizedLabel = label.ifBlank { type.defaultLabel }
        val keyPair = when (type) {
            SshKeyGenerationType.ED25519 -> SshKeyCodec.generateEd25519()
            SshKeyGenerationType.ECDSA_P256 -> generateEcKeyPair("secp256r1")
            SshKeyGenerationType.ECDSA_P384 -> generateEcKeyPair("secp384r1")
            SshKeyGenerationType.ECDSA_P521 -> generateEcKeyPair("secp521r1")
            SshKeyGenerationType.RSA_2048 -> generateRsaKeyPair(2048)
            SshKeyGenerationType.RSA_3072 -> generateRsaKeyPair(3072)
            SshKeyGenerationType.RSA_4096 -> generateRsaKeyPair(4096)
        }
        val privateKeyPem = SshKeyCodec.encodePrivate(keyPair, passphrase.takeIf { encrypt })
        return recordFrom(
            id = UUID.randomUUID().toString(),
            label = normalizedLabel,
            keyPair = keyPair,
            privateKeyPem = privateKeyPem,
            requiresPassphrase = encrypt,
            savedPassphrase = passphrase.takeIf { encrypt && rememberPassphrase },
        )
    }

    /** Creates an Ed25519 key while preserving the original convenience API. */
    fun generateEd25519(label: String): StoredSshKey =
        generateKey(SshKeyGenerationType.ED25519, label)

    /**
     * Imports a private key after proving it decodes. A passphrase verified by that decode is
     * kept only for an encrypted key and only when [rememberPassphrase] is set.
     */
    fun importPrivateKey(
        label: String,
        privateKeyPem: String,
        passphrase: String? = null,
        rememberPassphrase: Boolean = false,
    ): StoredSshKey {
        val normalized = privateKeyPem.replace("\r\n", "\n").trim().plus("\n")
        require(normalized.contains("PRIVATE KEY")) { "The selected data is not a private key." }
        if (SshKeyCodec.isDsa(normalized)) throw UnsupportedDsaKeyException()
        requireSupportedEncryption(normalized)
        val encrypted = isPassphraseProtected(normalized)
        if (encrypted && passphrase.isNullOrEmpty()) {
            throw KeyPassphraseRequiredException()
        }
        val keyPair = decodeKeyPair(normalized, passphrase)
        return recordFrom(
            id = UUID.randomUUID().toString(),
            // Algorithm names are locale-neutral and make an imported key
            // identifiable without persisting a language-specific default.
            label = label.ifBlank { keyPair.public.algorithm },
            keyPair = keyPair,
            privateKeyPem = normalized,
            requiresPassphrase = encrypted,
            savedPassphrase = passphrase.takeIf { encrypted && rememberPassphrase },
        )
    }

    /** Decodes a stored key; without an explicit [passphrase] the remembered one is used. */
    fun decodeKeyPair(key: StoredSshKey, passphrase: String? = null): KeyPair {
        if (key.algorithm == "ssh-dss") throw UnsupportedDsaKeyException()
        requireSupportedEncryption(key.privateKeyPem)
        val effective = passphrase?.takeIf(String::isNotEmpty) ?: key.savedPassphrase
        if (key.requiresPassphrase && effective.isNullOrEmpty()) {
            throw KeyPassphraseRequiredException()
        }
        return decodeKeyPair(key.privateKeyPem, effective)
    }

    /**
     * Applies a rename and passphrase change to [key]. Renaming rewrites only the public-key
     * comment. Any change that needs the private material first proves the current passphrase
     * by decoding, then re-encodes as OpenSSH and checks the public key is unchanged. The id,
     * creation time and fingerprint are preserved so profile references keep working.
     */
    fun editKey(key: StoredSshKey, request: KeyEditRequest): StoredSshKey {
        val label = request.label.trim().ifBlank { key.label }
        val renamed = key.copy(label = label, publicKey = withComment(key.publicKey, label))
        val change = request.passphrase
        val encryptedAfter = when (change) {
            KeyPassphraseChange.Keep -> key.requiresPassphrase
            is KeyPassphraseChange.Set -> true
            KeyPassphraseChange.Remove -> false
        }
        val remember = request.rememberPassphrase && encryptedAfter
        if (change == KeyPassphraseChange.Keep) {
            if (!remember) return renamed.copy(savedPassphrase = null)
            if (key.savedPassphrase != null) return renamed
            if (key.algorithm == "ssh-dss") throw UnsupportedDsaKeyException()
            requireSupportedEncryption(key.privateKeyPem)
            // Remember only a passphrase proven to decrypt this exact key.
            val current = request.currentPassphrase?.takeIf(String::isNotEmpty) ?: throw KeyPassphraseRequiredException()
            decodeWithCurrent(key, current)
            return renamed.copy(savedPassphrase = current)
        }
        if (change is KeyPassphraseChange.Set) require(change.passphrase.isNotEmpty())
        if (key.algorithm == "ssh-dss") throw UnsupportedDsaKeyException()
        requireSupportedEncryption(key.privateKeyPem)
        val current = request.currentPassphrase?.takeIf(String::isNotEmpty) ?: key.savedPassphrase
        if (key.requiresPassphrase && current.isNullOrEmpty()) throw KeyPassphraseRequiredException()
        val keyPair = decodeWithCurrent(key, current)
        val newPassphrase = (change as? KeyPassphraseChange.Set)?.passphrase
        val rewritten = recordFrom(
            id = key.id,
            label = label,
            keyPair = keyPair,
            privateKeyPem = SshKeyCodec.encodePrivate(keyPair, newPassphrase),
            requiresPassphrase = encryptedAfter,
            savedPassphrase = newPassphrase.takeIf { remember },
        ).copy(createdAtEpochMillis = key.createdAtEpochMillis)
        check(rewritten.fingerprint == key.fingerprint) { "Re-encoded key does not match the stored public key." }
        return rewritten
    }

    /** A decode failure on an encrypted key is reported as a wrong passphrase. */
    private fun decodeWithCurrent(key: StoredSshKey, current: String?): KeyPair {
        if (!key.requiresPassphrase) return decodeKeyPair(key.privateKeyPem, null)
        return try {
            decodeKeyPair(key.privateKeyPem, current)
        } catch (_: Exception) {
            throw IncorrectKeyPassphraseException()
        }
    }

    /** Keeps the algorithm and blob fields of an OpenSSH public-key line and replaces its comment. */
    private fun withComment(publicKey: String, label: String): String {
        val fields = publicKey.trim().split(' ', limit = 3)
        return if (fields.size < 2) publicKey else fields[0] + " " + fields[1] + " " + label
    }

    fun isPassphraseProtected(privateKeyPem: String): Boolean = SshKeyCodec.isEncrypted(privateKeyPem)

    /** Fail before prompting for credentials, including agent and saved-key authentication. */
    fun requireSupportedEncryption(privateKeyPem: String) {
        if (SshKeyCodec.hasDisabledEncryption(privateKeyPem)) throw UnsupportedKeyEncryptionException()
    }

    private fun decodeKeyPair(privateKeyPem: String, passphrase: String?): KeyPair =
        SshKeyCodec.decodePrivate(privateKeyPem, passphrase)

    private fun generateEcKeyPair(curveName: String): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec(curveName))
        }.generateKeyPair()

    private fun generateRsaKeyPair(bitSize: Int): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply {
            initialize(bitSize)
        }.generateKeyPair()

    private fun recordFrom(
        id: String,
        label: String,
        keyPair: KeyPair,
        privateKeyPem: String,
        requiresPassphrase: Boolean,
        savedPassphrase: String? = null,
    ): StoredSshKey {
        val encoded = SshKeyCodec.publicKey(keyPair)
        val publicKey = encoded.algorithmName + " " + Base64.getEncoder().encodeToString(encoded.publicKeyBlob) + " " + label
        val blob = encoded.publicKeyBlob
        val fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(blob),
        )
        return StoredSshKey(
            id = id,
            label = label,
            algorithm = publicKey.substringBefore(' '),
            publicKey = publicKey,
            fingerprint = fingerprint,
            privateKeyPem = privateKeyPem,
            requiresPassphrase = requiresPassphrase,
            savedPassphrase = savedPassphrase,
        )
    }
}

/**
 * Whether [SshKeyManager.editKey] may decrypt and rewrite this key's private material. It
 * inspects only format headers, so it is cheap enough to evaluate while composing a dialog.
 */
fun StoredSshKey.canChangePassphrase(): Boolean =
    algorithm != "ssh-dss" && !SshKeyCodec.hasDisabledEncryption(privateKeyPem)

/** How [SshKeyManager.editKey] treats a key's private-key encryption. */
sealed interface KeyPassphraseChange {
    /** Leave the private key bytes untouched. */
    data object Keep : KeyPassphraseChange

    /** Encrypt with a new, non-empty passphrase. */
    class Set(val passphrase: String) : KeyPassphraseChange {
        override fun toString(): String = "Set(<redacted>)"
    }

    /** Store the private key unencrypted inside the vault. */
    data object Remove : KeyPassphraseChange
}

/**
 * One edit of a stored key. [currentPassphrase] proves the existing passphrase when the key has
 * none remembered; [rememberPassphrase] keeps the resulting passphrase in the encrypted vault.
 */
class KeyEditRequest(
    val label: String,
    val currentPassphrase: String?,
    val passphrase: KeyPassphraseChange,
    val rememberPassphrase: Boolean,
) {
    override fun toString(): String = "KeyEditRequest(label=$label, passphrase=$passphrase, remember=$rememberPassphrase)"
}

/** The supplied passphrase does not decrypt the stored private key. */
class IncorrectKeyPassphraseException : IllegalArgumentException("The private key passphrase is incorrect.")

class KeyPassphraseRequiredException : IllegalArgumentException("The private key requires a passphrase.")

/** Historical DSA records remain serializable/exportable, but never enter authentication. */
class UnsupportedDsaKeyException : IllegalArgumentException("DSA authentication is unsupported.")

/** Disabled key containers remain exportable without attempting to decrypt their contents. */
class UnsupportedKeyEncryptionException : IllegalArgumentException("Private key encryption format is unsupported.")
