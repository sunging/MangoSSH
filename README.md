# MangoSSH

MangoSSH is a Jetpack Compose Android SSH and Mosh client with encrypted local
profiles, reusable keys, encrypted WebDAV backups, port forwarding, SCP,
keyboard shortcuts, app lock, system-Tailscale routing, and an outbound-only
embedded tsnet node for explicitly selected SSH/Mosh profiles.

## License

This Mosh-enabled distribution is licensed under GPL-3.0-or-later. See
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the native Mosh source,
build provenance, and distribution obligations. Initialize submodules before
building:

```text
git submodule update --init --recursive
```

## Native Mosh build

In Ubuntu WSL, run the following from the repository root:

```text
bash tools/fetch-android-ndk-wsl.sh
bash tools/build-pty-bridge-wsl.sh
bash tools/build-mosh-android-wsl.sh
bash tools/install-mosh-assets.sh
```

The final command validates and copies the four ABI archives into the Android
app. The scripts keep downloaded compilers and intermediate files in `.tools`,
which is not committed.

## 16 KiB page-size verification

After building the debug APK, validate both the ELF load segments and the APK
alignment. This is required for Android devices that use 16 KiB memory pages:

```text
bash tools/check-16kb-elf-wsl.sh \
  app/build/outputs/apk/debug/app-debug.apk
zipalign -c -P 16 -v 4 app/build/outputs/apk/debug/app-debug.apk
```

The JNI PTY bridge explicitly uses the NDK r27 16 KiB linker options. The
native build and Mosh asset installation scripts reject a binary whose
`PT_LOAD` segments do not meet that requirement.

## Embedded tsnet

`TAILNET` continues to use the device's system Tailscale VPN. The independent
`TSNET` route uses a process-scoped userspace node and does not request Android
VPN access or depend on the Tailscale app. It supports official Tailscale
browser enrollment and one-time Auth Key enrollment; Headscale, custom control
servers, exit nodes, and device-wide VPN routing are intentionally out of
scope.

Gradle builds the pinned four-ABI gomobile AAR on demand through
`tools/build-tsnet-android-wsl.sh`; the generated AAR and downloaded toolchains
are ignored and must not be committed. See
[docs/embedded-tsnet.md](docs/embedded-tsnet.md) for the exact tool versions,
security boundaries, build commands, and emulator/lab verification checklist.
