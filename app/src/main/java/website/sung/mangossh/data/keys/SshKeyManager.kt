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
    /** Creates an unencrypted OpenSSH private key for the requested algorithm. */
    fun generateKey(type: SshKeyGenerationType, label: String): StoredSshKey {
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
        val privateKeyPem = SshKeyCodec.encodePrivate(keyPair)
        return recordFrom(
            id = UUID.randomUUID().toString(),
            label = normalizedLabel,
            keyPair = keyPair,
            privateKeyPem = privateKeyPem,
            requiresPassphrase = false,
        )
    }

    /** Creates an Ed25519 key while preserving the original convenience API. */
    fun generateEd25519(label: String): StoredSshKey =
        generateKey(SshKeyGenerationType.ED25519, label)

    fun importPrivateKey(
        label: String,
        privateKeyPem: String,
        passphrase: String? = null,
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
        )
    }

    fun decodeKeyPair(key: StoredSshKey, passphrase: String? = null): KeyPair {
        if (key.algorithm == "ssh-dss") throw UnsupportedDsaKeyException()
        requireSupportedEncryption(key.privateKeyPem)
        if (key.requiresPassphrase && passphrase.isNullOrEmpty()) {
            throw KeyPassphraseRequiredException()
        }
        return decodeKeyPair(key.privateKeyPem, passphrase)
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
        )
    }
}

class KeyPassphraseRequiredException : IllegalArgumentException("The private key requires a passphrase.")

/** Historical DSA records remain serializable/exportable, but never enter authentication. */
class UnsupportedDsaKeyException : IllegalArgumentException("DSA authentication is unsupported.")

/** Disabled key containers remain exportable without attempting to decrypt their contents. */
class UnsupportedKeyEncryptionException : IllegalArgumentException("Private key encryption format is unsupported.")
