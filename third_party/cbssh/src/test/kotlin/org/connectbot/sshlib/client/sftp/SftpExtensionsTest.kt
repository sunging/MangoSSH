package org.connectbot.sshlib.client.sftp

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Verifies untrusted VERSION extension declarations before enabling atomic replacement. */
class SftpExtensionsTest {
    private fun packet(vararg fields: String): ByteArray {
        val encoded = fields.map { it.toByteArray(Charsets.UTF_8) }
        return ByteBuffer.allocate(4 + encoded.sumOf { 4 + it.size }).apply {
            putInt(3)
            encoded.forEach { putInt(it.size); put(it) }
        }.array()
    }

    @Test fun preservesExactExtensionValues() {
        assertEquals(mapOf("posix-rename@openssh.com" to "1", "example" to "2"),
            SftpClientImpl.decodeExtensions(packet("posix-rename@openssh.com", "1", "example", "2")))
        assertEquals(emptyMap(), SftpClientImpl.decodeExtensions(packet()))
    }

    @Test fun rejectsIncompleteOrDuplicateDeclarations() {
        assertFailsWith<SftpDecodeException> { SftpClientImpl.decodeExtensions(packet("name")) }
        assertFailsWith<SftpDecodeException> { SftpClientImpl.decodeExtensions(packet("name", "1", "name", "2")) }
        assertFailsWith<SftpDecodeException> { SftpClientImpl.decodeExtensions(byteArrayOf(0)) }
        assertFailsWith<SftpDecodeException> {
            SftpClientImpl.decodeExtensions(ByteBuffer.allocate(8).putInt(3).putInt(Int.MAX_VALUE).array())
        }
    }

    @Test fun boundsExtensionCount() {
        val fields = (0..128).flatMap { listOf("extension-$it", "1") }.toTypedArray()
        assertFailsWith<SftpDecodeException> { SftpClientImpl.decodeExtensions(packet(*fields)) }
    }
}
