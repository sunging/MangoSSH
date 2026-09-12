package website.sung.mangossh.data.vault

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupCompatibilityTest {
    @Test fun legacyContainersAndEverySupportedSchemaMigrate() {
        val password = UUID.randomUUID().toString().toCharArray()
        try {
            for (version in 1..2) for (schema in 1..5) {
                val payload = JSONObject(VaultPayloadCodec.encode(VaultSnapshot()).decodeToString()).put("schemaVersion", schema).toString().encodeToByteArray()
                val archive = PortableVaultCodec.decryptArchive(legacy(version, payload, password), password)
                assertNull(archive.metadata)
                assertEquals(VaultSnapshot(), archive.snapshot)
            }
        } finally { password.fill('\u0000') }
    }

    @Test fun v3MetadataIsAuthenticatedAndFutureVersionsRejected() {
        val password = UUID.randomUUID().toString().toCharArray()
        val blob = PortableVaultCodec.encrypt(VaultSnapshot(), password, "test")
        val archive = PortableVaultCodec.decryptArchive(blob, password)
        assertEquals("test", archive.metadata?.appVersion)
        assertNotNull(archive.metadata?.id)
        assertEquals(3, blob[5].toInt())
        val future = blob.copyOf().also { it[5] = 127 }
        assertEquals(BackupFailure.VERSION, assertThrows(BackupException::class.java) { PortableVaultCodec.decrypt(future, password) }.reason)
        val changed = blob.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertEquals(BackupFailure.AUTHENTICATION, assertThrows(BackupException::class.java) { PortableVaultCodec.decrypt(changed, password) }.reason)
        assertEquals(BackupFailure.AUTHENTICATION, assertThrows(BackupException::class.java) { PortableVaultCodec.decrypt(blob, UUID.randomUUID().toString().toCharArray()) }.reason)
        password.fill('\u0000')
    }

    @Test fun oversizedTruncatedAndCoercedPayloadsAreRejected() {
        assertThrows(BackupException::class.java) { PortableVaultCodec.decrypt(ByteArray(PortableVaultCodec.MAX_FILE_BYTES + 1), charArrayOf('x')) }
        assertThrows(IllegalArgumentException::class.java) { PortableVaultCodec.decrypt(ByteArray(10), charArrayOf('x')) }
        val payload = JSONObject(VaultPayloadCodec.encode(VaultSnapshot()).decodeToString())
        payload.put("schemaVersion", "5")
        assertThrows(IllegalArgumentException::class.java) { VaultPayloadCodec.decode(payload.toString().encodeToByteArray()) }
        payload.put("schemaVersion", 6)
        assertEquals(BackupFailure.VERSION, assertThrows(BackupException::class.java) { VaultPayloadCodec.decode(payload.toString().encodeToByteArray()) }.reason)
    }

    private fun legacy(version: Int, plaintext: ByteArray, password: CharArray): ByteArray {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        fun header(length: Int) = ByteBuffer.allocate(40).put("MSSHX".encodeToByteArray()).put(version.toByte()).put(16.toByte()).put(salt).put(12.toByte()).put(nonce).putInt(length).array()
        val spec = PBEKeySpec(password, salt, 310_000, 256)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(header(if (version == 1) 0 else plaintext.size + 16))
            val ciphertext = cipher.doFinal(plaintext)
            header(ciphertext.size) + ciphertext
        } finally { key.fill(0); plaintext.fill(0) }
    }
}
