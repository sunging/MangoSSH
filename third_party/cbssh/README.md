# ConnectBot SSH library for MangoSSH

Upstream: https://github.com/connectbot/cbssh/tree/v0.6.4

Version: 0.6.4

Commit: `1a84dc6db2167827defc56f3cadad31f87564acc`

GitHub source ZIP SHA-256:
`882fb2479e41db95c0409f569aaa9feba9fec0f28c5297830f6e55ed6cd70332`

The `sshlib/src/main` and `protocol/src/main` source trees are imported here
as `src/main` and `protocol/src/main`. The Apache 2.0 license is retained.
`upstream-api.txt` is upstream's `sshlib/api.txt` at the pinned revision.
Build configuration is integrated with MangoSSH's JDK 17 build and dependency
repositories. Generated Kaitai Java files are build output, never source input.

MangoSSH uses a no-output SLF4J provider. Applications must not enable upstream
diagnostic logging: it may contain authentication or remote protocol data.

The SNTRUP761 and raw-key Poly1305 sources under `src/main/java` are upstream's
extraction from Bouncy Castle 1.86 (`bcprov-jdk18on`); their hashes match
upstream's `third_party/bouncycastle/upstream.json`. The MIT license is kept at
`src/main/resources/META-INF/LICENSE-bouncycastle.md`. Bouncy Castle and
Conscrypt are test-only dependencies.

## Local patch inventory

No upstream version changes are inferred from local modifications. Each item
is relative to v0.6.4.

- Build: JDK 17 Kotlin/JVM modules and pinned Kaitai 0.11 protocol generation.
  `keccak-jvm` is held at 2.5.1 because Kyber's transitive 2.3.0 is Java 21
  bytecode. Release keep rules for the Ed25519 provider are in
  `src/main/resources/META-INF/proguard/cbssh.pro`.
- `SshConnection`: without the ping extension, `ping()` sends OpenSSH's
  `keepalive@openssh.com` through the global-request FIFO and treats either
  reply as liveness. Authentication drops methods outside
  `AuthHandler.supportedMethods`, honours `AuthHandler.preferPasswordAuth`, and
  does not fall back to keyboard-interactive after a preferred password is
  rejected. Server EXT_INFO is accepted on the strength of our `ext-info-c`
  offer, independently of the server's `ext-info-s`.
- `SshClient`: own the connection during the handshake, propagate coroutine
  cancellation, close failed SFTP handshakes, and impose a 15-second total ping
  deadline that closes the captured connection on cancellation.
- `SftpClient` / `SftpClientImpl`: `extensionVersions` keeps the bounded,
  duplicate-free server extension declarations next to upstream's `extensions`
  name set; `posixRename` requires an explicit version `1`; unexpected status
  reply types are rejected.
- `SftpDispatcher` / `SftpPacketIO`: one request deadline covers queue
  admission, the write and the response, and returns `IoError(TimeoutException)`.
- `SshKeys`: expose encryption inspection and Android-compatible Ed25519 key
  generation without registering a global provider.
- `Base64Compat`: use `java.util.Base64` consistently; MangoSSH requires API 26.
- `SshSession` / `SessionChannel` / channel-request codec: expose the standard
  `auth-agent-req@openssh.com` request so the application can request forwarding
  after installing its identity allowlist, consent and session-binding policy.
- Forwarders: `ForwardConnections` reports live and opening connections as
  `PortForwardStatus`/`PortForwardActivity`, releases listener selectors and
  owned sockets on cancellation, and aborts local forwarding resources even when
  sending remote cancellation is blocked. Remote forwarding keeps upstream's
  protocol-executor ordering for confirmations and failure replies.
  SOCKS5 CONNECT replies wait for remote channel acceptance; refused destinations
  receive a failure reply, and failed replies release the already-open SSH channel.
- Cipher registry and PEM writer: remove 3DES transport and DES/3DES private-key
  encryption implementations; no `SshCryptoPolicy` preset or override can
  enable them.
- Key decoding and encoding: remove DES/3DES PEM decryption and encrypted
  PKCS#8 support, including encrypted ML-DSA PEM output. MangoSSH preserves
  historical records for backup/export and asks users to convert these
  containers externally; it never rewrites saved private keys.
- Android compatibility: Ed25519 signatures use the existing Tink
  implementation for raw keys without registering a global JCA provider; seeds
  are read without resolving `EdECPrivateKey` before Android 13; EC point
  coordinates avoid an API 33 `BigInteger` constructor; Ed448 is offered only
  when a provider exists; ChaCha20-Poly1305 only when its cipher is available.
  Transport adapters propagate coroutine cancellation rather than wrapping it as
  a transport failure.
- `AgentChannel`: a window-credit write that fails because the connection
  closed ends only that agent request instead of escaping into the shared
  connection scope.

Upstream v0.6.4 supersedes the earlier local state-machine mutex, packet-writer
and close mutexes, global-request poisoning, crossed-packet suppression after a
local CLOSE, session and forwarding window-credit isolation (now reported to
the connection through channel delivery), and the Poly1305, identification-line
and ML-KEM backports.

## Tests

Tests imported from the same revision cover protocol serialization and state
machines, packet I/O, SFTP framing and dispatch, connection flow, key exchange
conformance, rekeying, keepalive, back-pressure, channel draining, MFA and
connection close. Tests that need mockk, Testcontainers or key fixtures are
not vendored; the imported key-based tests generate their keys. The 3DES ETM
case is removed and the EXT_INFO case asserts the accepted-extension policy.
Local tests cover authentication policy, forward connection accounting,
malformed or duplicate SFTP extensions, send-inclusive deadlines, disabled
ciphers for every policy preset, Android key encodings, and closing a transport
while a packet write is blocked.

Run `gradlew :third_party:cbssh:test :third_party:cbssh:protocol:test`.
