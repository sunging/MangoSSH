# Embedded tsnet build and verification

## Scope and routing

MangoSSH has three explicit profile routes:

- `DIRECT` uses the Android network selected by the system.
- `TAILNET` preserves the existing system Tailscale VPN behavior and forces
  Tailscale SSH authentication.
- `TSNET` uses the app's independent userspace Tailnet node. SSH bootstrap
  traffic uses tsnet's authenticated loopback SOCKS5 listener. Mosh then uses a
  loopback UDP relay backed by the same node.

The embedded node handles only profiles that select `TSNET`. It does not
request Android VPN permission, install routes for other apps, or depend on the
Tailscale Android app. The first release supports only the official Tailscale
control plane. Headscale, custom `ControlURL`, exit nodes, and full-device VPN
routing are intentionally unsupported.

## Pinned toolchain and source

The reproducible bridge build pins:

- Go `1.26.5`;
- Eclipse Temurin JDK `17.0.19+10` for the WSL gomobile wrapper;
- Android NDK `27.3.13750724` (r27d);
- `tailscale.com v1.98.8`;
- `golang.org/x/mobile v0.0.0-20260709172247-6129f5bee9d5`.

Local-development download scripts verify the published Go SHA-256, Temurin
SHA-256, and NDK size/SHA-1 before extracting. F-Droid builds instead require
those toolchains to be supplied by the build environment and fail before any
download is attempted. The complete Go module source graph is committed under
`native/tsnetbridge/vendor`. In addition to `go.sum`, both patched Tailscale
source files have pinned pre-patch and post-patch SHA-256 values.
`tools/patches/tailscale-v1.98.8-tsnet-no-logtail.patch` is applied with
`git apply --check`; a source mismatch, skipped hunk, or unexpected patched
result stops both the test and production builds.

The audited patch is deliberately narrow. It disables creation of the raw
logtail buffer when no-support logging is disabled, routes the loopback SOCKS5
dial through `tsnet.Server.Dial`, extends only the initial SOCKS5 destination
dial to 30 seconds, and exposes a network-monitor refresh hook for Android.

From Ubuntu, Linux CI, or Ubuntu WSL at the repository root:

```text
bash tools/test-tsnet-bridge-wsl.sh
bash tools/build-tsnet-android-wsl.sh
```

On Windows, normal Gradle tasks invoke the same WSL build script:

```text
gradlew.bat :app:testDebugUnitTest
gradlew.bat :app:lint :app:assembleDebug
```

The output is `app/build/generated/tsnet/mangossh-tsnet.aar`. It contains
`libgojni.so` for `arm64-v8a`, `armeabi-v7a`, `x86_64`, and `x86`. Both
`-Wl,-z,max-page-size=16384` and
`-Wl,-z,common-page-size=16384` are passed to the external linker. The
normalized AAR has deterministic ZIP metadata and includes the Tailscale BSD
license plus notices for the packages linked into the binary. The AAR,
downloaded local-development toolchains, and intermediate build trees are
ignored build artifacts; the vendored Go source is versioned input.

For the network-isolated F-Droid path, export the externally provided JDK 17,
Android SDK/NDK r27d, Go 1.26.5, and pinned Mosh dependency source roots, then
run `tools/build-fdroid-release.sh`. The entry point rejects signing variables,
sets the Go proxy and checksum database offline, and creates only the unsigned
release APK.

## Identity and secret boundaries

One installation uses a stable name of the form
`mangossh-android-<random-suffix>`. Node state is stored separately from the
MangoSSH vault in `noBackupFilesDir`, encrypted with a dedicated Android
Keystore AES-256-GCM key, and committed through `AtomicFile`. It is excluded
from Android backup, portable exports, and WebDAV.

Browser authorization URLs are one-shot in-memory events. The UI validates the
official `https://login.tailscale.com` origin before sending the URL to the
system browser; it never displays or persists the complete URL. Auth Key input
is not saveable UI state. It is converted to a mutable character array, passed
directly through the restricted bridge, and cleared on every completion path.
Failures use fixed categories and require fresh input.

The bridge exposes only start, fixed status, authenticated SOCKS5, UDP relay,
logout, and close operations. It does not expose Tailscale LocalAPI. Upstream
text logging is discarded, `TS_NO_LOGS_NO_SUPPORT` is enabled, and the audited
patch prevents creation or upload of the raw logtail buffer. MangoSSH logs only
fixed state event codes.

## Automated verification

Use JDK 17 and run:

```text
gradlew.bat :app:testDebugUnitTest
gradlew.bat :app:connectedDebugAndroidTest
gradlew.bat :app:lint :app:assembleDebug :app:assembleRelease
```

Then inspect the APK:

```text
bash tools/check-16kb-elf-wsl.sh \
  app/build/outputs/apk/debug/app-debug.apk
zipalign -c -P 16 -v 4 app/build/outputs/apk/debug/app-debug.apk
bash tools/check-16kb-elf-wsl.sh \
  app/build/outputs/apk/release/app-release-unsigned.apk
zipalign -c -P 16 -v 4 app/build/outputs/apk/release/app-release-unsigned.apk
```

Confirm that all four ABI directories contain `libgojni.so`,
`libmangossh_pty.so`, and `libmosh_client.so`, and that every packaged
`PT_LOAD` segment has at least 16 KiB alignment.

## Emulator and lab acceptance

Use the already Tailscale-connected `lab` server without changing server
configuration:

1. Disable the emulator's system Tailscale VPN. In MangoSSH Settings, choose
   **Use Auth Key**. Enter a one-time, non-reusable key directly on the device;
   never paste it into chat, shell history, source, fixtures, or logs.
2. Create `TSNET` SSH profiles for both the lab MagicDNS name and Tailnet IP.
   Verify Tailscale SSH, first-use host-key confirmation, terminal I/O, and
   ordinary password/key/interactive authentication where configured.
3. Verify Mosh bootstrap, UDP input/output, terminal resize, network switching,
   reconnection, background/foreground transitions, and explicit disconnect.
4. Force-stop and restart MangoSSH. Confirm the same app node reconnects
   without another Auth Key.
5. Disconnect all `TSNET` sessions, sign out, then enroll through the system
   browser. Repeat SSH and Mosh checks and retain the browser-created identity.
6. Re-enable system Tailscale. Re-test an existing `TAILNET` profile and a
   `DIRECT` profile to confirm there is no routing regression.

After testing, inspect only sanitized metadata:

```text
adb logcat -d -s MangoSSH
adb shell run-as website.sung.mangossh find no_backup -maxdepth 2 -type f
adb shell ps -A
```

Do not print private-file contents. Confirm Logcat and filenames/process state
contain no Auth Key, authorization URL, target hostname/IP, Mosh session key,
SOCKS credential, raw Tailscale log, or orphaned native process.
