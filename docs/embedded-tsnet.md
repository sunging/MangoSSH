# Embedded tsnet build and verification

## Scope and routing

MangoSSH has three explicit profile routes:

- `DIRECT` uses the Android network selected by the system.
- `TAILNET` preserves the existing system Tailscale VPN behavior and forces
  Tailscale SSH authentication.
- `TSNET` uses the app's independent userspace Tailnet node. SSH bootstrap and
  the authenticated companion used by Mosh files, resource queries, and port
  forwards use tsnet's loopback SOCKS5 listener. Mosh terminal traffic uses a
  loopback UDP relay backed by the same node.

The embedded node handles only profiles that select `TSNET`. It does not
request Android VPN permission, install routes for other apps, or depend on the
Tailscale Android app. Exit nodes and full-device VPN routing are
intentionally unsupported.

## Control server

Enrollment uses Tailscale's own control plane unless the user enters another
coordination server, such as a self-hosted Headscale instance, in
Settings → Embedded Tailscale. The bridge passes that URL to
`tsnet.Server.ControlURL`; an empty value keeps tsnet's default.

- Only `https://` URLs without credentials, a query, or a fragment are
  accepted. Android normalizes the value and the Go bridge rejects anything
  else before starting, so registration never runs over cleartext.
- The URL is stored in the node's encrypted state next to the identity it
  belongs to. It can change only while no registration exists; logging out
  clears it. Choosing a different server before a pending enrollment completes
  discards that half-enrolled state first.
- A registered node always re-authenticates with its own server.

## Pinned toolchain and source

The reproducible bridge build pins:

- Go `1.26.7`;
- JDK 17 supplied by the build environment (`tools/fetch-jdk17.sh` can prepare
  checksum-pinned Eclipse Temurin `17.0.19+10` before compilation);
- Android NDK `27.3.13750724` (r27d);
- `tailscale.com v1.102.4`;
- `golang.org/x/mobile v0.0.0-20260709172247-6129f5bee9d5`.

Local and GitHub release preparation use `tools/fetch-go.sh` to install the
SHA-256-pinned official Go archive; Go itself is not compiled. Download scripts
verify the published Go SHA-256, Temurin
SHA-256, and NDK size/SHA-1 before extracting. F-Droid builds instead require
those toolchains to be supplied by the build environment and fail before any
download is attempted. The complete Go module source graph is committed under
`native/tsnetbridge/vendor`. In addition to `go.sum`, both patched Tailscale
source files have pinned pre-patch and post-patch SHA-256 values.
The vendor tree already includes
`tools/patches/tailscale-v1.102.4-tsnet-no-logtail.patch`, so ordinary `go test`
and source analysis see the same network-refresh API as Android builds.
The production build verifies the patched hashes, reverses the patch in its
disposable copy to verify the upstream hashes, then reapplies it with
zero-fuzz `patch` dry runs. The bridge tests consume this same vendor tree with
network access disabled; updating dependencies is a separate maintenance step.
A source mismatch, skipped hunk, or unexpected result stops the build.

`tools/lib/tsnet-version.sh` pins the upstream tag and commit and provides the
linker stamps consumed by Tailscale's `version.Long()` and `version.Short()`.
Both report `1.102.4`, including in the GOPATH gomobile build where Go module
build information is absent. The upstream commit is stamped separately;
the MangoSSH source and patch retain the local changes' provenance. Stamped
version tests run with the same flags before binding the Android libraries.

The audited patch is deliberately narrow. It disables creation of the raw
logtail buffer when no-support logging is disabled, routes the loopback SOCKS5
dial through `tsnet.Server.Dial`, extends only the initial SOCKS5 destination
dial to 30 seconds, and exposes a network-monitor refresh hook for Android.

`app/build.gradle.kts` reads this same pin from
`native/tsnetbridge/vendor/tailscale.com/VERSION.txt` at configure time, cross-
checks it against `vendor/modules.txt`, and generates a Kotlin constant that
Settings → Embedded Tailscale displays below the enrollment card. That path
never touches the gomobile build, so the display can't drift from the source
`tools/build-tsnet-android.sh` actually links into `mangossh-tsnet.aar`.

From a glibc-compatible Linux x86_64 host at the repository root:

```text
bash tools/test-tsnet-bridge.sh
bash tools/build-tsnet-android.sh
```

Direct builds require `ANDROID_SDK_ROOT` or `ANDROID_HOME` to name an existing
Android SDK directory. The build normalizes the selected path and exports both
variables to its child tools.
On Linux, Gradle explicitly passes its running JDK as `JAVA_HOME`, so the
native subprocess and Gradle use the same installation. Gradle itself requires
a preinstalled JDK 17 and does not provision Java toolchains.

On Windows, Gradle uses WSL to invoke the same Linux adapter. Linux SDK, NDK and
JDK installations are selected using the `MANGOSSH_LINUX_*` variables documented
in building.md; Windows compiler installations are not used by the Linux recipes.

```text
gradlew.bat :app:testGithubDebugUnitTest :app:testFdroidDebugUnitTest
gradlew.bat :app:lintGithubDebug :app:assembleGithubDebug
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
Android SDK/NDK r27d, Go 1.26.7, and pinned Mosh dependency source roots, then
run `tools/build-fdroid-release.sh`. The entry point rejects signing variables,
sets the Go proxy and checksum database offline, and creates only the unsigned
release APK.

## Identity and secret boundaries

One installation uses a stable name of the form
`mangossh-android-<random-suffix>`. Node state is stored separately from the
MangoSSH vault in `noBackupFilesDir`, encrypted with a dedicated Android
Keystore AES-256-GCM key, and committed through `AtomicFile`. It is excluded
from Android backup, portable exports, and WebDAV.

Browser authorization URLs are one-shot in-memory events. Before sending the
URL to the system browser, the UI requires `https` and the identity's own
server: `login.tailscale.com` for the default control plane, otherwise the
configured server's host and port. It never displays or persists the complete
URL. Auth Key input
is not saveable UI state. It is converted to a mutable character array, passed
directly through the restricted bridge, and cleared on every completion path.
Failures use fixed categories and require fresh input.

The bridge exposes only start, fixed status, a read-only network snapshot,
authenticated SOCKS5, UDP relay, logout, and close operations. It does not
expose Tailscale LocalAPI. The network snapshot is an explicit allow-list:
this node's hostname, MagicDNS name, and Tailnet addresses, and for each peer
its stable ID, hostname, MagicDNS name, OS, Tailnet addresses, online state,
last-seen time, and whether it advertises Tailscale SSH host keys. Node keys,
endpoints, DERP/relay data, user identities, and SSH host key contents never
cross the boundary. Sharee nodes and Mullvad exit nodes are omitted.

Settings → Embedded Tailscale keeps an enrolled node up while the page is
visible, polls the snapshot every five seconds, and releases the node five
seconds after the page is hidden unless a session owns it. This browse-only
hold never starts the foreground service or opens a browser authorization,
and never starts a node that has not enrolled. The snapshot is held only in
memory while the page is visible. Peer names and addresses are displayed
verbatim and never logged.

Upstream text logging is discarded, `TS_NO_LOGS_NO_SUPPORT` is enabled, and the audited
patch prevents creation or upload of the raw logtail buffer. The `ts_omit_netlog`
build tag keeps upstream's logtail-backed network flow logger out of the binary
rather than relying on its runtime check. MangoSSH logs only fixed state event
codes.

## Automated verification

Use JDK 17 and run:

```text
gradlew.bat :app:testGithubDebugUnitTest :app:testFdroidDebugUnitTest
gradlew.bat :app:connectedGithubDebugAndroidTest
gradlew.bat :app:lintGithubDebug :app:assembleGithubDebug :app:assembleGithubRelease
```

Then inspect the APK:

```text
bash tools/check-16kb-elf.sh \
  app/build/outputs/apk/github/debug/app-github-debug.apk
zipalign -c -P 16 -v 4 app/build/outputs/apk/github/debug/app-github-debug.apk
bash tools/check-16kb-elf.sh \
  app/build/outputs/apk/github/release/app-github-release-unsigned.apk
zipalign -c -P 16 -v 4 app/build/outputs/apk/github/release/app-github-release-unsigned.apk
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
3. Verify Mosh bootstrap, UDP input/output, terminal resize, SFTP browsing,
   resource queries, all three port-forward types, network switching, companion
   SSH reconnection, background/foreground transitions, and explicit disconnect.
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

## Native build contract

The shared Gradle adapter now validates a content-addressed local cache, builds
only `-PmangosshAbis` for debug configurations, and records a producer manifest.
Release builds still require all four ABIs. Work directories and gomobile tools
are isolated per checkout and input fingerprint. All compiler inputs must be
prepared before compilation; the producer never downloads toolchains. Full Go
tests run separately with `bash tools/test-tsnet-bridge.sh`, using the same
vendored sources with GOPROXY and GOSUMDB disabled. The AAR normalizer covers
classes.jar metadata as well as the outer archive. See [building.md](building.md).
