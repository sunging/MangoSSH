package website.sung.mangossh.session.ssh

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import org.junit.Assert.*
import org.junit.Test

class SshAgentSignatureTest {
    private val random = SecureRandom()
    private val session = ByteArray(32).also(random::nextBytes)
    private val key = ByteArray(64).also(random::nextBytes)
    private val host = ByteArray(64).also(random::nextBytes)
    private fun request(bound: Boolean = false): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output ->
            fun field(value: ByteArray) { output.writeInt(value.size); output.write(value) }
            field(session); output.writeByte(50); field("fixture".toByteArray())
            field("ssh-connection".toByteArray())
            field((if (bound) "publickey-hostbound-v00@openssh.com" else "publickey").toByteArray())
            output.writeByte(1); field("rsa-sha2-512".toByteArray()); field(key)
            if (bound) field(host)
        }
    }.toByteArray()

    @Test fun acceptsOnlyMatchingAuthenticationBinding() {
        for (bound in listOf(false, true)) {
            val data = request(bound)
            assertTrue(validAgentSignature(data, session, key, host, "rsa-sha2-512"))
            assertFalse(validAgentSignature(data, session.reversedArray(), key, host, "rsa-sha2-512"))
            assertFalse(validAgentSignature(data, session, key.reversedArray(), host, "rsa-sha2-512"))
            assertFalse(validAgentSignature(data, session, key, host, "ssh-rsa"))
            assertFalse(validAgentSignature(data + byteArrayOf(0), session, key, host, "rsa-sha2-512"))
        }
        assertFalse(validAgentSignature(request(true), session, key, host.reversedArray(), "rsa-sha2-512"))
    }

    @Test fun rejectsTruncatedAndUnboundedFields() {
        val data = request()
        for (size in data.indices) assertFalse(validAgentSignature(data.copyOf(size), session, key, host, "rsa-sha2-512"))
        data.fill(127, 0, 4)
        assertFalse(validAgentSignature(data, session, key, host, "rsa-sha2-512"))
    }
}
