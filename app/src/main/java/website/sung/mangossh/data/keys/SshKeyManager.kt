package website.sung.mangossh.data.keys

import com.trilead.ssh2.crypto.OpenSSHKeyDecoder
import com.trilead.ssh2.crypto.OpenSSHKeyEncoder
import com.trilead.ssh2.crypto.PEMDecoder
import com.trilead.ssh2.crypto.PublicKeyUtils
import com.trilead.ssh2.crypto.keys.Ed25519KeyPairGenerator
import com.trilead.ssh2.crypto.keys.Ed25519PrivateKey
import com.trilead.ssh2.crypto.keys.Ed25519PublicKey
import java.security.KeyPair
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import website.sung.mangossh.data.vault.StoredSshKey

/**
 * Imports and creates client keys entirely in memory. Persisting a returned key
 * is the caller's responsibility; MangoSSH stores it only inside the encrypted vault.
 */
class SshKeyManager {
    fun generateEd25519(label: String): StoredSshKey {
        val keyPair = Ed25519KeyPairGenerator().generateKeyPair()
        val privateKey = keyPair.private as Ed25519PrivateKey
        val publicKey = keyPair.public as Ed25519PublicKey
        val normalizedLabel = label.ifBlank { "MangoSSH Ed25519" }
        val privateKeyPem = OpenSSHKeyEncoder.exportOpenSSHEd25519(privateKey, publicKey, normalizedLabel)
        return recordFrom(
            id = UUID.randomUUID().toString(),
            label = normalizedLabel,
            keyPair = keyPair,
            privateKeyPem = privateKeyPem,
            requiresPassphrase = false,
        )
    }

    fun importPrivateKey(
        label: String,
        privateKeyPem: String,
        passphrase: String? = null,
    ): StoredSshKey {
        val normalized = privateKeyPem.replace("\r\n", "\n").trim().plus("\n")
        require(normalized.contains("PRIVATE KEY")) { "请选择私钥文件，而不是公钥文件。" }
        val encrypted = isPassphraseProtected(normalized)
        if (encrypted && passphrase.isNullOrEmpty()) {
            throw KeyPassphraseRequiredException()
        }
        val keyPair = decodeKeyPair(normalized, passphrase)
        return recordFrom(
            id = UUID.randomUUID().toString(),
            label = label.ifBlank { "导入的 ${keyPair.public.algorithm} 密钥" },
            keyPair = keyPair,
            privateKeyPem = normalized,
            requiresPassphrase = encrypted,
        )
    }

    fun decodeKeyPair(key: StoredSshKey, passphrase: String? = null): KeyPair {
        if (key.requiresPassphrase && passphrase.isNullOrEmpty()) {
            throw KeyPassphraseRequiredException()
        }
        return decodeKeyPair(key.privateKeyPem, passphrase)
    }

    fun isPassphraseProtected(privateKeyPem: String): Boolean {
        return if (privateKeyPem.contains("BEGIN OPENSSH PRIVATE KEY")) {
            OpenSSHKeyDecoder.isEncrypted(openSshPayload(privateKeyPem))
        } else {
            PEMDecoder.isPEMEncrypted(PEMDecoder.parsePEM(privateKeyPem.toCharArray()))
        }
    }

    private fun decodeKeyPair(privateKeyPem: String, passphrase: String?): KeyPair =
        if (privateKeyPem.contains("BEGIN OPENSSH PRIVATE KEY")) {
            OpenSSHKeyDecoder.decode(openSshPayload(privateKeyPem), passphrase)
        } else {
            PEMDecoder.decode(privateKeyPem.toCharArray(), passphrase)
        }

    private fun openSshPayload(privateKeyPem: String): ByteArray {
        val encoded = privateKeyPem
            .lineSequence()
            .filterNot { line -> line.trimStart().startsWith("-----") }
            .joinToString(separator = "") { line -> line.trim() }
        require(encoded.isNotBlank()) { "OpenSSH private key payload is empty" }
        return try {
            Base64.getDecoder().decode(encoded)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("OpenSSH private key is not valid Base64", error)
        }
    }

    private fun recordFrom(
        id: String,
        label: String,
        keyPair: KeyPair,
        privateKeyPem: String,
        requiresPassphrase: Boolean,
    ): StoredSshKey {
        val publicKey = PublicKeyUtils.toAuthorizedKeysFormat(keyPair.public, label)
        val blob = PublicKeyUtils.extractPublicKeyBlob(keyPair.public)
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

class KeyPassphraseRequiredException : IllegalArgumentException("此私钥受口令保护。")
