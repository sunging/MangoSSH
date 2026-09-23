package website.sung.mangossh.data.keys

import java.security.KeyPairGenerator
import java.security.interfaces.DSAPublicKey
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test
import website.sung.mangossh.data.vault.StoredSshKey
import website.sung.mangossh.data.vault.VaultPayloadCodec
import website.sung.mangossh.data.vault.VaultSnapshot

/** Historical key bytes survive persistence unchanged, even though authentication is prohibited. */
class LegacyDsaCompatibilityTest {
    @Test fun historicalRecordRoundTripsButCannotAuthenticateOrImport() {
        val pair = KeyPairGenerator.getInstance("DSA").apply { initialize(1024) }.generateKeyPair()
        val public = pair.public as DSAPublicKey
        val blob = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                fun field(value: ByteArray) { output.writeInt(value.size); output.write(value) }
                field("ssh-dss".toByteArray())
                for (number in listOf(public.params.p, public.params.q, public.params.g, public.y)) field(number.toByteArray())
            }
        }.toByteArray()
        val pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(pair.private.encoded) +
            "\n-----END PRIVATE KEY-----\n"
        val record = StoredSshKey("legacy", "legacy", "ssh-dss", "ssh-dss " + Base64.getEncoder().encodeToString(blob),
            "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(blob)), pem)
        val decoded = VaultPayloadCodec.decode(VaultPayloadCodec.encode(VaultSnapshot(keys = listOf(record))))
        assertEquals(record, decoded.keys.single())
        assertThrows(UnsupportedDsaKeyException::class.java) { SshKeyManager().decodeKeyPair(decoded.keys.single()) }
        assertThrows(UnsupportedDsaKeyException::class.java) { SshKeyManager().importPrivateKey("legacy", pem) }
    }
}
