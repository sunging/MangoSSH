# ConnectBot SSH library for MangoSSH

Upstream: https://github.com/connectbot/cbssh/tree/v0.4.2

Version: 0.4.2

Commit: `9811f5a321c1afd88721498cdd7a5201c0c4abc0`

GitHub source ZIP SHA-256:
`1a8d959b87516eb65d616176ea1320736dd61b7220c3044c4ee5adb00f996b50`

The `sshlib/src/main` and `protocol/src/main` source trees are imported here
as `src/main` and `protocol/src/main`. The Apache 2.0 license is retained.
`upstream-api.txt` records the public API at the pinned source revision.
Build configuration is integrated with MangoSSH's JDK 17 build and dependency
repositories. Generated Kaitai Java files are build output, never source input.

MangoSSH uses a no-output SLF4J provider. Applications must not enable upstream
diagnostic logging: it may contain authentication or remote protocol data.

## Local patch inventory

No upstream version changes are inferred from local modifications.

- Build: JDK 17 Kotlin/JVM modules and pinned Kaitai 0.11 protocol generation.
- `SshConnection`: serialize reply-bearing global requests; fallback OpenSSH
  keepalive request; poison the transport after an interrupted global request;
  close the transport independently of a blocked packet writer; clean up ping
  registrations when sending is cancelled.
- `SshClient`: own the connection during handshake, propagate coroutine
  cancellation, close failed SFTP handshakes, and impose a 15-second total ping
  deadline that closes the captured connection on cancellation.
- `SftpClient` / `SftpClientImpl`: retain bounded server extension declarations,
  allow POSIX rename only for an explicit version `1`, reject unexpected status
  reply types, and avoid blocking channel teardown on the SFTP state mutex.
- `SftpDispatcher` / `SftpPacketIO`: include sending in request deadlines and
  propagate cancellation while releasing pending requests.
- `SshKeys`: expose encryption inspection and Android-compatible Ed25519 key
  generation without registering a global provider.
- `Base64Compat`: use `java.util.Base64` consistently; MangoSSH requires API 26.
- `SshSession` / `SessionChannel` / channel-request codec: expose the standard
  `auth-agent-req@openssh.com` request so the application can request forwarding
  after installing its identity allowlist, consent and session-binding policy.
- Forwarders: release listener selectors and owned sockets on cancellation,
  and abort local forwarding resources even when sending remote cancellation
  is blocked. The application bounds the stop request independently.
- Cipher registry and PEM writer: remove 3DES transport and DES/3DES private-key
  encryption implementations; neither can be enabled through library options.

Tests imported from the same revision cover protocol serialization, SFTP
framing, attributes, dispatch and client operations, plus connection close.
Local tests cover malformed/duplicate extension declarations, blocked-send
deadlines and closing a transport while a packet write is blocked. The upstream
test that accepted a non-STATUS response to a status operation now expects a
protocol failure. Test credentials and key fixtures are not vendored.

Run `gradlew :third_party:cbssh:test :third_party:cbssh:protocol:test`.
