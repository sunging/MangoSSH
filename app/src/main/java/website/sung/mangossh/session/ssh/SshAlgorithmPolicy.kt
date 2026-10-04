package website.sung.mangossh.session.ssh

import org.connectbot.sshlib.SshClientConfig
import website.sung.mangossh.data.vault.preferTrustedHostKeyAlgorithms

/**
 * Explicit host opt-in appends only approved legacy algorithms after upstream defaults.
 * Host-key algorithms of [trustedHostKeyFamilies] are then offered first, so a server
 * with several key types proves the one the user already trusts.
 */
internal fun SshClientConfig.Builder.applyMangoAlgorithmPolicy(
    legacy: Boolean,
    trustedHostKeyFamilies: Set<String> = emptySet(),
) {
    autoDisconnectOnLastChannelClose = false
    if (legacy) {
        fun String.append(vararg names: String): String = (split(',') + names).distinct().joinToString(",")
        hostKeyAlgorithms = hostKeyAlgorithms.append("ssh-rsa")
        kexAlgorithms = kexAlgorithms.append("diffie-hellman-group14-sha1", "diffie-hellman-group-exchange-sha1")
        encryptionAlgorithms = encryptionAlgorithms.append("aes256-cbc", "aes128-cbc")
        macAlgorithms = macAlgorithms.append("hmac-sha1-etm@openssh.com", "hmac-sha1")
    }
    hostKeyAlgorithms = preferTrustedHostKeyAlgorithms(hostKeyAlgorithms, trustedHostKeyFamilies)
}
