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

package org.connectbot.sshlib.crypto

import org.connectbot.sshlib.protocol.SshEd25519PublicKeyBlob
import org.connectbot.sshlib.protocol.SshEd25519SignatureBlob
import org.connectbot.sshlib.protocol.SshPublicKey
import org.connectbot.sshlib.protocol.SshSignature
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import org.connectbot.sshlib.crypto.ed25519.Ed25519PrivateKey
import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Ed25519Verify

internal object Ed25519SignatureAlgorithm : SshSignatureAlgorithm {
    private val ED25519_OID = byteArrayOf(0x2b, 0x65, 0x70) // 1.3.101.112

    override fun verify(pubKey: SshPublicKey, sig: SshSignature, data: ByteArray): Boolean {
        val keyBlob = pubKey.keyBlob() as SshEd25519PublicKeyBlob
        val rawKey = keyBlob.key().data()

        val sigBlob = sig.signatureBlob() as SshEd25519SignatureBlob
        return try {
            Ed25519Verify(rawKey).verify(sigBlob.signature().data(), data)
            true
        } catch (_: java.security.GeneralSecurityException) { false }
    }

    override fun sign(algorithmName: String, privateKey: PrivateKey, data: ByteArray): ByteArray {
        // Android raw-key decoding may use our fallback key class even when a
        // platform Signature provider exists but cannot consume that key type.
        val seed = Ed25519PrivateKey(PKCS8EncodedKeySpec(privateKey.encoded)).getSeed()
        val sigBytes = try { Ed25519Sign(seed).sign(data) } finally { seed.fill(0) }
        return encodeSshString("ssh-ed25519".toByteArray(Charsets.US_ASCII)) +
            encodeSshString(sigBytes)
    }
}
