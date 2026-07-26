package website.sung.mangossh.data.tsnet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TsnetStateCodecTest {
    @Test
    fun roundTripsDeterministicallyAndCopiesValues() {
        val first = linkedMapOf(
            "z-state" to byteArrayOf(3, 4),
            "a-state" to byteArrayOf(1, 2),
        )
        val second = linkedMapOf(
            "a-state" to byteArrayOf(1, 2),
            "z-state" to byteArrayOf(3, 4),
        )

        val encoded = TsnetStateCodec.encode(first)
        assertArrayEquals(encoded, TsnetStateCodec.encode(second))
        val decoded = TsnetStateCodec.decode(encoded)
        assertEquals(setOf("a-state", "z-state"), decoded.keys)
        assertArrayEquals(byteArrayOf(1, 2), decoded.getValue("a-state"))
    }

    @Test
    fun rejectsTruncationTrailingBytesAndDuplicateKeys() {
        val encoded = TsnetStateCodec.encode(mapOf("state" to byteArrayOf(1, 2, 3)))
        assertThrows(Exception::class.java) {
            TsnetStateCodec.decode(encoded.copyOf(encoded.size - 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TsnetStateCodec.decode(encoded + 0)
        }

        val duplicate = encoded.copyOf().also {
            // Change the encoded entry count from one to two while leaving only
            // one entry; strict decoding must reject the malformed payload.
            it[8] = 2
        }
        assertThrows(Exception::class.java) { TsnetStateCodec.decode(duplicate) }
    }
}
