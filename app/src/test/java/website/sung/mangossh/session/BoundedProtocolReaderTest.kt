package website.sung.mangossh.session

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InputStream

class BoundedProtocolReaderTest {
    @Test fun unterminatedPeerLineIsBoundedBeforeAllocation() {
        var read = 0
        val input = object : InputStream() { override fun read(): Int { read++; return 65 } }
        assertThrows(IOException::class.java) { BoundedProtocolReader.lines(input, 10, 64, 1024) { null } }
        assertEquals(65, read)
    }

    @Test fun reportStopsAtOneBytePastBudget() {
        var read = 0
        val input = object : InputStream() { override fun read(): Int { read++; return 65 } }
        assertThrows(IOException::class.java) { BoundedProtocolReader.bytes(input, 32 * 1024) }
        assertEquals(32 * 1024 + 1, read)
    }

    @Test fun acceptedLineDoesNotWaitForPeerEof() {
        val bytes = "notice\r\nready\ntrailing".byteInputStream()
        assertEquals("ready", BoundedProtocolReader.lines(bytes, 4, 128, 256) { it.takeIf { it == "ready" } })
        assertEquals("trailing", bytes.readBytes().toString(Charsets.UTF_8))
    }
}
