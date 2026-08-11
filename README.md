# MangoSSH

MangoSSH is a Jetpack Compose Android SSH and Mosh client with encrypted local
profiles, reusable keys, encrypted WebDAV backups, port forwarding, SFTP file
and folder transfer, keyboard shortcuts, app lock, system-Tailscale routing,
and an outbound-only embedded tsnet node for explicitly selected SSH/Mosh
profiles.

## Port forwarding

Local, remote, and SOCKS5 rules live on the **Forwarding** page. Starting a rule
reuses an open SSH terminal or a Mosh terminal's authenticated companion SSH
connection for the same host. Otherwise it opens a connection dedicated to the
forward, so a tunnel does not require keeping a terminal open. A dedicated
connection closes as soon as its last forward stops, and its host-key or
password prompt appears over the page that started it. If a Mosh companion SSH
disconnects, its forwards are marked failed without ending the UDP terminal;
start a failed rule again to reconnect it.

## File transfers

Browse a host over SFTP from the **Files** button inside an open SSH or Mosh
terminal, which reuses that session's authenticated SSH connection, or from a
host's overflow-menu **Files** action, which opens a connection dedicated to
transfers. Single files and whole folders can be downloaded and uploaded. Mosh
terminals use the same SSH companion for the server-resource report in their
terminal toolbar.

Running transfers appear behind the transfer icon in the host list top bar, with
combined progress. That sheet can pause, resume, cancel, and retry a transfer,
open a finished download in another app, and clear finished records. Pausing
stops at a chunk boundary and keeps the byte offset, so resuming continues from
there over the same connection. Resuming and retrying need that connection: once
it closes, the transfer has to be started again from the file browser.

## License

This Mosh-enabled distribution is licensed under GPL-3.0-or-later. See
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the native Mosh source,
build provenance, and distribution obligations. Initialize submodules before
building:

```text
git submodule update --init --recursive
```

## Language

MangoSSH includes English and Simplified Chinese. The language card at the top
of **Settings** can follow the Android system/app-language preference (the
default), or explicitly select **English** or **简体中文**. Changing it recreates
the UI but keeps application-scoped SSH/Mosh sessions and transfers running.

## Terminal appearance

Settings &gt; Terminal appearance controls the shared font, base size, and color
theme used by every SSH and Mosh terminal. The default is Cascadia Mono PL at
12sp with Mango Dark. JetBrains Mono NL and Fira Code are also bundled; each
font is an open-source static Regular TTF.

Choose Mango Dark, Dracula, Nord, Solarized Dark, or Solarized Light. The
chosen theme is fixed and does not follow the Android system light/dark mode.
Custom mode starts from one of those presets, preserving its ANSI palette and
selection colors while letting you choose opaque `#RRGGBB` foreground,
background, and cursor colors that meet a 3:1 contrast ratio.

The preference is stored locally on the device outside the encrypted vault and
portable backup archive. Pinch-to-zoom remains available inside a terminal but
only changes that currently displayed terminal for its lifetime. Font and
palette licenses, versions, and SHA-256 values are recorded in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

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

The Go packages linked into that bridge are committed as source under
`native/tsnetbridge/vendor`. Local builds may download the pinned Go, JDK, and
NDK toolchains when they are missing, but do not download Go module source.

## Versioning and releases

`version.txt` is the only application-version source. It contains stable SemVer
without a leading `v`, while Git tags and GitHub Releases use `v<version>`.
Gradle derives both Android values from that file:

```text
versionName = MAJOR.MINOR.PATCH
versionCode = MAJOR * 1,000,000 + MINOR * 1,000 + PATCH
```

For example, `0.0.1` maps to version code `1`, `0.1.0` to `1000`, and
`1.2.3` to `1002003`. Minor and patch components are limited to `0..999`, and
the resulting code must fit Android's `2,100,000,000` limit. This deterministic
mapping ensures that GitHub and network-isolated F-Droid builds of the same tag
carry identical version metadata.

Release Please owns version changes, `CHANGELOG.md`, `v*` tags, and GitHub
Releases. Commits merged into `main` must use Conventional Commit subjects;
`fix:` requests a patch release, `feat:` a minor release, and `feat!:` or a
`BREAKING CHANGE` footer a major release. Build, documentation, and CI-only
commits do not request an application release by themselves.

The release flow is:

1. Release Please creates or updates a release PR against `main`.
2. Review the proposed version and generated changelog. Add non-empty localized
   store notes named after the derived version code, for example:
   `fastlane/metadata/android/en-US/changelogs/1000.txt` and
   `fastlane/metadata/android/zh-CN/changelogs/1000.txt` for version `0.1.0`.
3. Approve the automated PR's Actions run when GitHub requests it, and merge
   only after Android CI passes.
4. The merge creates the version tag and a draft GitHub Release. The same
   workflow rebuilds native assets, tests, signs, verifies 16 KiB alignment,
   attaches the versioned APK/AAB and checksums, and then publishes the draft.

The repository must allow GitHub Actions to create pull requests and grant the
release workflow its declared `contents`, `issues`, and `pull-requests` write
permissions. The workflow uses only the built-in `GITHUB_TOKEN`; no PAT or
GitHub App credential is required. A manual run validates signing and uploads
an Actions artifact, but never creates a tag or publishes a GitHub Release.

Validate the current version, localized notes, and an optional tag locally:

```text
gradlew.bat :app:verifyReleaseVersion -PreleaseTag=v0.0.1
```

## In-app updates

A sideloaded install can check `Settings` for a newer signed release from
`https://github.com/sunging/MangoSSH/releases/latest`, download its APK to
app-private cache storage, verify the download's SHA-256 against the
release's published `SHA256SUMS` asset, and hand the verified file to the
system package installer. A release with no `SHA256SUMS` cannot be installed
in-app; MangoSSH links to the release page instead. Checking is manual
("Check now") plus an opt-in, throttled automatic check (at most once every
24 hours) on app start.

The feature depends on the same asset names the release workflow produces
(`MangoSSH-<tag>.apk`, `SHA256SUMS`) and is entirely absent from the request
made to GitHub's API rate limit budget until the user opens Settings or an
automatic check is due; it never sends a GitHub token.

The whole feature is hidden at runtime — not just disabled — whenever
`PackageManager` reports the app was installed by a store that already owns
its own updates (F-Droid, Google Play, and common OEM app stores). This keeps
a single build and manifest for every distribution channel, including the
network-isolated F-Droid build, so the `REQUEST_INSTALL_PACKAGES` permission
declared in the manifest is present but its code path is unreachable on
F-Droid installs. See `data/update/InstallSource.kt` for the exact channel
list.

## F-Droid source build

F-Droid builds use `tools/build-fdroid-release.sh` with network access disabled.
The build environment must provide JDK 17, Android SDK/NDK r27d, Go 1.26.5,
and the pinned zlib, protobuf, ncurses, GMP, and nettle source trees. It builds
protoc 29.1 from the supplied protobuf source unless `MANGOSSH_PROTOC` already
points to an exact host build. The script rejects release-signing variables and
produces an unsigned APK after rebuilding the PTY bridge, Mosh client, and
embedded tsnet bridge from source.

The expected external source layout is selected by
`MANGOSSH_MOSH_DEPS_DIR`:

```text
zlib/      v1.3.1
protobuf/  v29.1, including its submodules
ncurses/   v6.4
gmp/       v6.2.1
nettle/    nettle_3.10_release_20240616
```

`MANGOSSH_GO_ROOT`, `JAVA_HOME`, `ANDROID_HOME`, and `ANDROID_NDK_HOME`
must point to the corresponding pre-fetched toolchains. This interface keeps
the official F-Droid build independent of developer machines and downloaded
compiler binaries. Offline Mosh builds use isolated ABI workers; tune their
safe concurrency with `MANGOSSH_ABI_PARALLELISM` and
`MANGOSSH_ABI_BUILD_JOBS` (both default to `2` in the F-Droid path).
