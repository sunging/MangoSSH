/*
 * ConnectBot SSH Library
 * Copyright 2025-2026 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.connectbot.sshlib.transport

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertFailsWith

/** Banner cases from upstream 4dbcd51, adapted to the vendored test helpers. */
class PacketIOBannerTest {
    private class ByteArrayTransport(private val input: ByteArray = ByteArray(0)) : Transport {
        private var position = 0
        private val written = java.io.ByteArrayOutputStream()

        val isInputExhausted: Boolean get() = position == input.size

        override suspend fun read(count: Int): ByteArray {
            if (input.size - position < count) throw TransportException("End of stream")
            return input.copyOfRange(position, position + count).also { position += count }
        }

        override suspend fun write(data: ByteArray) = written.writeBytes(data)

        override suspend fun close() = Unit

        override val isConnected: Boolean = true

        fun writtenData(): ByteArray = written.toByteArray()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "2.0-OpenSSH_3.7.1p2",
            "1.99-OpenSSH_5.3",
            "2.0-OpenSSH_10.6",
            "2.0-dropbear_2026.91",
            "2.0-FutureServer_99.0 vendor comment",
        ],
    )
    fun `LF and CRLF banners preserve the next packet`(version: String) = runBlocking {
        val following = byteArrayOf(0, 0, 1, 0)
        for (ending in listOf("\n", "\r\n", "\r\r\n")) {
            val transport = ByteArrayTransport("SSH-$version$ending".toByteArray(Charsets.US_ASCII) + following)
            assertEquals(version + ending.removeSuffix("\n"), PacketIO(transport).readBanner().protoVersion())
            assertArrayEquals(following, transport.read(following.size))
            assertTrue(transport.isInputExhausted)
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "SSH-2.0-Test\u0000\n",
            "SSH-2.0-Test\u0000\r\n",
            "SSH-2.0-Test\rX\n",
            "SSH-2.0-Test\r\rX\r\n",
            "SSH-2.0-Test\r\u0000\n",
        ],
    )
    fun `readBanner rejects NUL and ordinary characters after CR`(wire: String) = runBlocking {
        val error = assertFailsWith<TransportException> { PacketIO(ByteArrayTransport(wire.toByteArray())).readBanner() }
        assertEquals("Malformed SSH identification", error.message)
    }

    @ParameterizedTest
    @ValueSource(strings = ["\n", "\r\n"])
    fun `banner length limit includes its terminator`(ending: String) = runBlocking {
        val prefix = "SSH-2.0-OpenSSH_3.7.1p2 "
        val maximum = prefix + "x".repeat(255 - prefix.length - ending.length) + ending
        assertEquals(
            maximum.substring(4).removeSuffix("\n"),
            PacketIO(ByteArrayTransport(maximum.toByteArray())).readBanner().protoVersion(),
        )
        val tooLong = maximum.dropLast(ending.length) + "x" + ending
        val error = assertFailsWith<TransportException> { PacketIO(ByteArrayTransport(tooLong.toByteArray())).readBanner() }
        assertEquals("Banner too long", error.message)
    }

    @ParameterizedTest
    @ValueSource(strings = ["SSH-2.0-OpenSSH_3.7.1p2", "SSH-2.0-OpenSSH_3.7.1p2\r", "", "\n", "BAD-2.0-Test\r\n"])
    fun `readBanner rejects truncated or malformed input`(wire: String) = runBlocking {
        val error = assertFailsWith<TransportException> { PacketIO(ByteArrayTransport(wire.toByteArray())).readBanner() }
        assertTrue(error.message.orEmpty().isNotEmpty())
    }

    @Test
    fun `writeBanner keeps the standard CRLF ending`() = runBlocking {
        val transport = ByteArrayTransport()
        PacketIO(transport).writeBanner("SSH-2.0-TestClient")
        assertArrayEquals("SSH-2.0-TestClient\r\n".toByteArray(Charsets.US_ASCII), transport.writtenData())
    }
}
