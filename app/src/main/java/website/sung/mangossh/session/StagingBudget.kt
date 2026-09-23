package website.sung.mangossh.session

import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream

/** Accounts only for unwritten reservations: already-written bytes are reflected in available space. */
internal class StagingBudget(private val availableBytes: () -> Long, private val reserveBytes: Long = 64L * 1024 * 1024) {
    private val remaining = mutableMapOf<File, Long>()

    @Synchronized fun reserve(file: File, total: Long?) {
        val wanted = total?.let { (it - file.length()).coerceAtLeast(0) } ?: CHUNK
        checkSpace(file, wanted)
        remaining[file] = wanted
    }

    private fun checkSpace(file: File, wanted: Long) {
        val other = remaining.filterKeys { it != file }.values.fold(0L) { a, b -> (a + b).coerceAtLeast(a) }
        val usable = (availableBytes() - reserveBytes - other).coerceAtLeast(0)
        if (wanted > usable) throw StagingSpaceException(wanted - usable)
    }

    /** Checks again immediately before each write; concurrent transfers cannot spend the same reservation. */
    fun output(file: File, delegate: OutputStream): OutputStream = object : FilterOutputStream(delegate) {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
        override fun write(bytes: ByteArray, offset: Int, length: Int) = synchronized(this@StagingBudget) {
            val reserved = remaining[file] ?: 0
            val wanted = if (reserved < length) maxOf(CHUNK, length.toLong()) else reserved
            checkSpace(file, wanted)
            out.write(bytes, offset, length)
            remaining[file] = wanted - length
        }
    }

    @Synchronized fun release(file: File) { remaining.remove(file) }

    private companion object { const val CHUNK = 8L * 1024 * 1024 }
}

/** Carries only a byte count, never a selected path. */
internal class StagingSpaceException(val requiredBytes: Long) : java.io.IOException()
