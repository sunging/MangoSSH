package website.sung.mangossh.data.tsnet

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Strict binary codec for the plaintext map stored behind Android Keystore.
 *
 * Lengths and trailing data are validated before values reach the Go runtime,
 * so a damaged file cannot turn into partially accepted Tailscale state.
 */
internal object TsnetStateCodec {
    private const val FORMAT_VERSION = 1
    private const val MAX_ENTRY_COUNT = 64
    private const val MAX_KEY_BYTES = 256
    private const val MAX_VALUE_BYTES = 2 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 5 * 1024 * 1024
    private val MAGIC = byteArrayOf('M'.code.toByte(), 'T'.code.toByte(), 'S'.code.toByte(), 'S'.code.toByte())

    fun encode(values: Map<String, ByteArray>): ByteArray {
        require(values.size <= MAX_ENTRY_COUNT) { "Too many embedded tsnet state entries" }
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.write(MAGIC)
            data.writeByte(FORMAT_VERSION)
            data.writeInt(values.size)
            values.toSortedMap().forEach { (key, value) ->
                val keyBytes = key.encodeToByteArray()
                require(keyBytes.isNotEmpty() && keyBytes.size <= MAX_KEY_BYTES) {
                    "Invalid embedded tsnet state key"
                }
                require(value.isNotEmpty() && value.size <= MAX_VALUE_BYTES) {
                    "Invalid embedded tsnet state value"
                }
                data.writeShort(keyBytes.size)
                data.write(keyBytes)
                data.writeInt(value.size)
                data.write(value)
            }
        }
        return output.toByteArray().also {
            require(it.size <= MAX_TOTAL_BYTES) { "Embedded tsnet state is too large" }
        }
    }

    fun decode(encoded: ByteArray): Map<String, ByteArray> {
        require(encoded.size <= MAX_TOTAL_BYTES) { "Embedded tsnet state is too large" }
        val input = DataInputStream(ByteArrayInputStream(encoded))
        val magic = ByteArray(MAGIC.size)
        input.readFully(magic)
        require(magic.contentEquals(MAGIC)) { "Invalid embedded tsnet state header" }
        require(input.readUnsignedByte() == FORMAT_VERSION) { "Unsupported embedded tsnet state format" }
        val count = input.readInt()
        require(count in 0..MAX_ENTRY_COUNT) { "Invalid embedded tsnet state entry count" }
        val values = LinkedHashMap<String, ByteArray>(count)
        repeat(count) {
            val keyLength = input.readUnsignedShort()
            require(keyLength in 1..MAX_KEY_BYTES) { "Invalid embedded tsnet state key length" }
            val keyBytes = ByteArray(keyLength)
            input.readFully(keyBytes)
            val key = keyBytes.decodeToString(throwOnInvalidSequence = true)
            require(key !in values) { "Duplicate embedded tsnet state key" }
            val valueLength = input.readInt()
            require(valueLength in 1..MAX_VALUE_BYTES) { "Invalid embedded tsnet state value length" }
            values[key] = ByteArray(valueLength).also(input::readFully)
        }
        require(input.available() == 0) { "Embedded tsnet state has trailing data" }
        return values
    }
}
