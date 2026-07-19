# MangoSSH

Jetpack Compose Android SSH client prototype.

- Package: `website.sung.mangossh`
- Minimum Android API: 26
- Local data: Android Keystore + AES-GCM encrypted vault

## Implemented

- Multiple SSH host profiles, shared private-key vault, Ed25519 key generation, OpenSSH/PEM import, public/private key export.
- SSH terminal with verified host fingerprints, password authentication, public-key authentication, keyboard-interactive / OTP prompts, selected-text copy, quick paste, physical `Ctrl+V`, and an on-screen shortcut bar.
- SSH agent forwarding for unencrypted vault keys; encrypted keys remain unavailable to the forwarded agent until a future per-session agent-unlock flow is added.
- Tailscale SSH profile mode: uses SSH `none` authentication first and displays authentication banners (including check-mode URLs). It requires the device to already be connected to the target tailnet through the Tailscale Android VPN.
- Auto-run command snippets after opening a shell.
- Local, remote, and dynamic SOCKS5 port forwards, including auto-start on connect and lifecycle cleanup with the SSH session.
- SCP upload and download through Android's document picker; uploads are staged in the app cache and limited to 1 GiB.
- Quick server-resource report from an open terminal session (`hostname`, uptime/load, memory, root disk, CPU count).
- Local PIN lock with a salted PBKDF2 verifier and optional strong-biometric unlock.
- Password-protected manual export/import and HTTPS WebDAV upload/download. Portable backups use PBKDF2-HMAC-SHA256 (310,000 iterations) and AES-256-GCM; new backups authenticate their header and legacy v1 backups remain importable.

## Mosh status

The profile model and UI include Mosh, but this checkout does **not** ship a native Mosh client runtime yet. A genuine Mosh session requires the native UDP state-synchronization client as well as a remote `mosh-server`; it cannot be honestly implemented as an SSH fallback. The next integration step is to vendor or build the GPL Mosh Android binaries with the NDK and bridge them through a PTY/JNI layer.

## Build and verify

Prerequisites:

- JDK 17
- Android SDK API 36.1

```text
gradlew.bat :app:testDebugUnitTest
gradlew.bat :app:lint :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Security notes

- Private keys, host profiles, known-host fingerprints, snippets, and WebDAV credentials are stored inside the local encrypted vault.
- A WebDAV sync passphrase is separate from the device-bound vault key. Importing a backup intentionally replaces the current vault after successful decryption.
- WebDAV endpoints are restricted to HTTPS and use HTTP Basic authentication. Use an app-specific password where the provider supports it.
- Never commit `local.properties`, keystores, private keys, portable backups, or signing material.
