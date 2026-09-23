package website.sung.mangossh.session.ssh

import java.security.KeyPair

/** App-owned agent policy; the connection additionally enforces verified session binding. */
internal interface SshAgent {
    fun identities(): List<SshAgentIdentity>
    fun keyForSignature(publicKey: ByteArray): KeyPair?
}
internal class SshAgentIdentity(val label: String, val publicKey: ByteArray)
