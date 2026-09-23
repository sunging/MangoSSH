package website.sung.mangossh.session.ssh

import org.connectbot.sshlib.SshClientConfig

/** Explicit host opt-in appends only approved legacy algorithms after upstream defaults. */
internal fun SshClientConfig.Builder.applyMangoAlgorithmPolicy(legacy: Boolean) {
    autoDisconnectOnLastChannelClose = false
    if (!legacy) return
    fun String.append(vararg names: String): String = (split(',') + names).distinct().joinToString(",")
    hostKeyAlgorithms = hostKeyAlgorithms.append("ssh-rsa")
    kexAlgorithms = kexAlgorithms.append("diffie-hellman-group14-sha1", "diffie-hellman-group-exchange-sha1")
    encryptionAlgorithms = encryptionAlgorithms.append("aes256-cbc", "aes128-cbc")
    macAlgorithms = macAlgorithms.append("hmac-sha1-etm@openssh.com", "hmac-sha1")
}
