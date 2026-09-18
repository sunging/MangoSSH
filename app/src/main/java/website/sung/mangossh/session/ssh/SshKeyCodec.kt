package website.sung.mangossh.session.ssh

import java.security.KeyPair
import org.connectbot.sshlib.SshKeys
import org.connectbot.sshlib.SshSigning

/** Key codec boundary keeps library-specific key classes out of persistence and UI code. */
internal object SshKeyCodec {
    fun generateEd25519(): KeyPair = SshKeys.generateEd25519KeyPair()
    fun encodePrivate(key: KeyPair): String = SshKeys.encodeOpenSshPrivateKey(key)
    fun decodePrivate(pem: String, passphrase: String?): KeyPair = SshKeys.decodePemPrivateKey(pem, passphrase)
    fun isEncrypted(pem: String): Boolean = SshKeys.isEncrypted(pem)
    /** OpenSSH exposes its public key before the encrypted private payload; inspect only that prefix. */
    fun isDsa(pem: String): Boolean {
        if (pem.contains("BEGIN DSA PRIVATE KEY")) return true
        if (pem.contains("BEGIN PRIVATE KEY")) return runCatching {
            require(pem.length <= 1024 * 1024)
            val encoded = pem.lineSequence().filterNot { it.startsWith("-----") }.joinToString("")
            java.security.KeyFactory.getInstance("DSA").generatePrivate(java.security.spec.PKCS8EncodedKeySpec(
                java.util.Base64.getDecoder().decode(encoded)))
            true
        }.getOrDefault(false)
        if (!pem.contains("BEGIN OPENSSH PRIVATE KEY")) return false
        return runCatching {
            require(pem.length <= 1024 * 1024)
            val encoded = pem.lineSequence().filterNot { it.startsWith("-----") }.joinToString("")
            val input = java.io.DataInputStream(java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(encoded)))
            val magic = ByteArray(15).also(input::readFully)
            require(magic.contentEquals("openssh-key-v1\u0000".toByteArray()))
            fun field(): ByteArray {
                val size = input.readInt()
                require(size >= 0 && size <= input.available())
                return ByteArray(size).also(input::readFully)
            }
            field(); field(); field()
            require(input.readInt() == 1)
            val public = java.io.DataInputStream(java.io.ByteArrayInputStream(field()))
            val size = public.readInt()
            require(size in 1..128 && size <= public.available())
            ByteArray(size).also(public::readFully).contentEquals("ssh-dss".toByteArray())
        }.getOrDefault(false)
    }
    fun publicKey(key: KeyPair): EncodedPublicKey = SshSigning.encodePublicKey(key).let {
        EncodedPublicKey(it.algorithmName, it.publicKeyBlob)
    }
}

/** Wire public-key identity; the algorithm is a blob type, not a signature policy. */
internal class EncodedPublicKey(val algorithmName: String, val publicKeyBlob: ByteArray)
