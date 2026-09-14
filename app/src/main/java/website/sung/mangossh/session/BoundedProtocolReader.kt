package website.sung.mangossh.session

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException

/** Rejects oversized peer output while reading, before allocating an unbounded string. */
internal object BoundedProtocolReader {
    fun bytes(input: InputStream, limit: Int): ByteArray {
        require(limit > 0)
        val result = ByteArrayOutputStream(minOf(limit, 4096))
        val buffer = ByteArray(minOf(limit + 1, 4096))
        while (true) {
            val read = input.read(buffer, 0, minOf(buffer.size, limit - result.size() + 1))
            if (read < 0) return result.toByteArray()
            if (read == 0) continue
            if (result.size() + read > limit) throw IOException("Peer output limit exceeded")
            result.write(buffer, 0, read)
        }
    }

    /** Stops as soon as a parser accepts a line; no secret peer text enters exceptions. */
    fun <T> lines(input: InputStream, maxLines: Int, maxLineBytes: Int, maxBytes: Int, accept: (String) -> T?): T? {
        val line = ByteArrayOutputStream(minOf(maxLineBytes, 256))
        var bytes = 0
        var lines = 0
        while (lines < maxLines) {
            val next = input.read()
            if (next < 0) return if (line.size() == 0) null else accept(line.toString("UTF-8").trimEnd('\r'))
            if (++bytes > maxBytes) throw IOException("Peer output limit exceeded")
            if (next == 10) {
                accept(line.toString("UTF-8").trimEnd('\r'))?.let { return it }
                line.reset()
                lines++
            } else {
                if (line.size() >= maxLineBytes) throw IOException("Peer line limit exceeded")
                line.write(next)
            }
        }
        return null
    }
}
