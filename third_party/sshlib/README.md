# ConnectBot SSH library

Upstream: https://github.com/connectbot/trilead-ssh2

Source artifact: `org.connectbot:sshlib:2.2.48:sources`

Source SHA-256: `9a26aad1f5a1d009b3da8de9ffa6e85e96ee8394874e7681b7b2c3bf12c0c6f7`

Java sources are imported without changing protocol algorithms. MangoSSH changes
are marked in source and add bounded directory enumeration, owned SFTP sessions,
channel abort, deadline-aware liveness checks, and POSIX rename negotiation.
Dependencies retain the published 2.2.48 versions.

Build and tests: `gradlew :third_party:sshlib:test`.

## Local patch inventory

- SFTPv3Client: bounded directory entries, caller-owned subsystem sessions,
  exact OpenSSH version-1 POSIX rename capability, exclusive temporary creation.
- Session/LocalStreamForwarder: abort only their owned channel.
- ChannelManager: bounded open/request waits, abandoned-open late-reply cleanup,
  channel-local agent reply counters, serialized global requests, and refusal
  to reuse a global reply stream after timeout/interruption.
- Connection: deadline ping including queued requests and blocked sends, a watchdog
  that closes only the captured transport generation, hard abort on failed ping, unlocked blocking channel
  creation, and monotonic packet send/receive timestamps.
- TransportManager: atomic adoption of a newly opened socket against close,
  including sockets provided by a direct-tcpip jump proxy.

The Maven source archive is the identity for this import; no unverified Git
commit is claimed for version 2.2.48. Keep the upstream license with every copy.
