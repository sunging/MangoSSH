package website.sung.mangossh.session.ssh

import java.io.ByteArrayInputStream
import java.io.DataInputStream

/**
 * An agent may sign only SSH user authentication bound to the verified transport.
 * Reject arbitrary signing payloads, mismatched keys and trailing bytes before prompting.
 */
internal fun validAgentSignature(data: ByteArray, sessionId: ByteArray, publicKey: ByteArray,
    serverKey: ByteArray, algorithm: String): Boolean = runCatching {
    require(data.size <= 1024 * 1024)
    val input = DataInputStream(ByteArrayInputStream(data))
    fun field(): ByteArray {
        val length = input.readInt()
        require(length >= 0 && length <= input.available())
        return ByteArray(length).also(input::readFully)
    }
    require(field().contentEquals(sessionId))
    require(input.readUnsignedByte() == 50)
    require(field().isNotEmpty())
    require(field().contentEquals("ssh-connection".toByteArray()))
    val method = field().toString(Charsets.US_ASCII)
    require(method == "publickey" || method == "publickey-hostbound-v00@openssh.com")
    require(input.readUnsignedByte() == 1)
    require(field().contentEquals(algorithm.toByteArray(Charsets.US_ASCII)))
    require(field().contentEquals(publicKey))
    if (method != "publickey") require(field().contentEquals(serverKey))
    require(input.available() == 0)
    true
}.getOrDefault(false)
