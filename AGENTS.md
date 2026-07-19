# MangoSSH contributor guide

## Project contract

- Android application ID and Kotlin namespace: `website.sung.mangossh`.
- The UI is Jetpack Compose and must remain usable on phones and tablets.
- Treat SSH profiles, private keys, WebDAV credentials, passphrases, OTP answers,
  host fingerprints, and Mosh session keys as secrets. Never log, display in
  diagnostics, commit, or include them in test fixtures.

## Architecture

- `data/vault`: encrypted persistence and portable backup codecs.
- `data/keys`: private-key generation, import, export, and decoding.
- `data/sync`: HTTPS WebDAV client for already-encrypted portable archives.
- `domain`: protocol-neutral profile and configuration models.
- `session`: live SSH/Mosh processes, terminal output, forwarding, SCP, and
  foreground-session lifetime.
- `presentation`: Compose screens and view models. Keep blocking I/O out of
  composables and the main dispatcher.

## Native Mosh and GPL compliance

- Native Mosh assets are GPL-3.0-or-later and must retain their upstream source,
  commit identity, license text, build instructions, and notices.
- Use the repository's `third_party/mosh4android` submodule and the WSL build
  scripts. Build `libmangossh_pty.so` before the Mosh client, then run
  `tools/install-mosh-assets.sh`; package all four ABI clients, terminfo, and
  the GPL text in the APK.
- Do not substitute Mosh with SSH. A Mosh profile must either run the bundled
  native client through the PTY bridge or report a clear runtime error.
- Native process arguments and environment variables must be passed directly;
  never build a shell command from profile data or log `MOSH_KEY`.
- The PTY bridge owns process lifecycle, file descriptors, resize signals, and
  child cleanup. Close all descriptors on every failure path.

## Kotlin, Compose, and documentation

- Add KDoc to public classes, public functions, security boundaries, protocol
  parsers, and non-obvious lifecycle code. Explain intent and invariants, not
  syntax already evident from the code.
- Use `android.util.Log` only through the project logging helper. Log operation
  identifiers, protocol/state transitions, and sanitized error categories; do
  not log credentials, command contents, remote banner contents, paths selected
  by a user, or cryptographic material.
- Put new platform-facing and session-facing text in Android string resources,
  with default English values and Simplified Chinese values under
  `values-zh-rCN`. The established Compose wording is currently routed through
  `MangoUiLiteralLocalization`; when touching one of those fixed literals, add
  its English mapping there rather than leaving English locales with Chinese
  text. User-provided values must never be translated or altered.
- Prefer immutable UI state and suspend functions. Never execute network, disk,
  cryptography, or subprocess work in a composable.

## Verification

Run from the repository root using JDK 17:

```text
gradlew.bat :app:testDebugUnitTest
gradlew.bat :app:lint :app:assembleDebug
```

For native code, also validate the ABI assets, ELF interpreter/dependencies,
and a debug APK package inspection. Every packaged `PT_LOAD` segment must have
at least 16 KiB alignment: run `tools/check-16kb-elf-wsl.sh` against the debug
APK and `zipalign -c -P 16 -v 4` against that APK. NDK r27 builds must keep
both `-Wl,-z,max-page-size=16384` and `-Wl,-z,common-page-size=16384` linker
options. When a device is available, test SSH host key confirmation, OTP, Mosh
bootstrap, reconnect, resize, process cleanup, and app background/foreground
behavior.

On the system user's Ubuntu WSL environment, the native sequence is:

```text
bash /mnt/d/code/android/mangossh/tools/fetch-android-ndk-wsl.sh
bash /mnt/d/code/android/mangossh/tools/build-pty-bridge-wsl.sh
bash /mnt/d/code/android/mangossh/tools/build-mosh-android-wsl.sh
bash /mnt/d/code/android/mangossh/tools/install-mosh-assets.sh
```

After producing the debug APK, run:

```text
bash /mnt/d/code/android/mangossh/tools/check-16kb-elf-wsl.sh \
  /mnt/d/code/android/mangossh/app/build/outputs/apk/debug/app-debug.apk
```

## Git workflow

- Keep unrelated work out of a commit.
- Use Conventional Commits, for example `feat: add native mosh runtime` or
  `docs: add contributor guidance`.
- Do not commit keystores, private keys, encrypted backups, local SDK paths,
  downloaded toolchains, or generated build output.
