# Building MangoSSH

## Prerequisites

- A preinstalled full JDK 17 and an Android SDK (`ANDROID_SDK_ROOT` or `ANDROID_HOME`).
- Git submodules:

  ```text
  git submodule update --init --recursive
  ```

- For the native Mosh client and PTY bridge: a glibc-compatible Linux x86_64
  host. On Windows, Gradle uses WSL only as an adapter for the same Linux
  scripts.

Set `JAVA_HOME` to your JDK 17 installation before invoking Gradle. In Android
Studio, select that installation in Settings > Build, Execution, Deployment >
Build Tools > Gradle > Gradle JDK. The settings script rejects other Java
versions and installations without `javac`. Gradle does not download a JDK or
override the selected JVM with daemon toolchain criteria. GitHub workflows
already install JDK 17 with `actions/setup-java`.

On Linux, Gradle passes its own JDK installation to the tsnet build subprocess.
On Windows, WSL uses a separate Linux JDK; the Windows `JAVA_HOME` is not
forwarded by the adapter. Direct Linux/WSL native scripts retain their pinned
JDK download fallback for online builds; offline builds require a supplied
Linux JDK 17. Machine-specific paths such as Debian's
`/usr/lib/jvm/java-17-openjdk-amd64` belong in the build environment, not in
the application's committed Gradle properties.

A debug build of the GitHub distribution:

```text
gradlew.bat :app:assembleGithubDebug
```

The test, lint and instrumentation-compile tasks that must pass before a change
is merged are listed in [AGENTS.md](../AGENTS.md#verification).

## Native Mosh build

On a glibc-compatible Linux x86_64 host, run the following from the repository
root:

```text
bash tools/fetch-android-ndk.sh
bash tools/build-pty-bridge.sh
bash tools/build-mosh-android.sh
bash tools/install-mosh-assets.sh
```

The final command validates and copies the four ABI archives into the Android
app. The scripts are distribution-neutral and report missing command-line
dependencies without invoking a package manager. They keep downloaded compilers
and intermediate files in `.tools`, which is not committed. On Windows, Gradle
uses WSL only as an adapter for these same Linux scripts.

Direct tsnet builds require `ANDROID_SDK_ROOT` or `ANDROID_HOME` to point to an
existing Android SDK. Gradle resolves the configured SDK itself and passes that
path to the generic Linux script.

## 16 KiB page-size verification

After building the debug APK, validate both the ELF load segments and the APK
alignment. This is required for Android devices that use 16 KiB memory pages:

```text
bash tools/check-16kb-elf.sh \
  app/build/outputs/apk/github/debug/app-github-debug.apk
zipalign -c -P 16 -v 4 app/build/outputs/apk/github/debug/app-github-debug.apk
```

The JNI PTY bridge explicitly uses the NDK r27 16 KiB linker options. The
native build and Mosh asset installation scripts reject a binary whose
`PT_LOAD` segments do not meet that requirement.

## Embedded tsnet

Gradle builds the pinned four-ABI gomobile AAR on demand through
`tools/build-tsnet-android.sh`; the generated AAR and downloaded toolchains
are ignored and must not be committed. See
[embedded-tsnet.md](embedded-tsnet.md) for the exact tool versions, security
boundaries, build commands, and emulator/lab verification checklist.

The Go packages linked into that bridge are committed as source under
`native/tsnetbridge/vendor`. Local builds may download the pinned Go, JDK, and
NDK toolchains when they are missing, but do not download Go module source.
The app declares NDK r27d (`27.3.13750724`) for Gradle's native-library
processing, matching the native build scripts.

## F-Droid source build

F-Droid builds use `tools/prepare-fdroid-native.sh` followed by the standard
`assembleFdroidRelease` Gradle task. `tools/build-fdroid-release.sh` composes
those steps for local and CI verification with Gradle offline. The build
environment must provide JDK 17, Android SDK/NDK r27d, and the source trees
locked by `tools/fdroid-sources.lock`. Install the pinned Go 1.26.7 Linux amd64
toolchain with `bash tools/fetch-go.sh` during network-enabled preparation;
this verifies the official archive SHA-256 instead of compiling Go itself.
Protoc 29.1 and the host `tic` used to compile terminfo are built from source;
release-signing variables are rejected, and the result is an unsigned APK
containing rebuilt PTY, Mosh, terminfo, and tsnet artifacts.

The expected external source layout is selected by
`MANGOSSH_MOSH_DEPS_DIR`:

```text
zlib/      v1.3.1
protobuf/  v29.1, including its submodules
ncurses/   v6.4
nettle/    nettle_3.10_release_20240616
```

The Mosh build applies `tools/patches/mosh4android-no-gmp.patch` after the
offline-source patch. It drops the upstream GMP build and configures Nettle
with `--disable-public-key`, while `--disable-mini-gmp` keeps mini-GMP
explicitly at its default off state. Mosh uses only Nettle AES, so neither GMP
nor Hogweed is needed. This does not change the separate SSH or tsnet
cryptography. Previously published releases and their fdroiddata recipes retain
their original source requirements.

`MANGOSSH_GO_ROOT`, `MANGOSSH_MOSH_DEPS_DIR`, `JAVA_HOME`, `ANDROID_HOME`,
and `ANDROID_NDK_HOME` complete the environment contract. The source-build
scripts set strict offline flags for Go and Gradle and fail instead of fetching
a missing input. Go defaults to `.tools/go/1.26.7`; no automatic toolchain
upgrade or compiler bootstrap is permitted during the build. GitHub releases
use the same verified prebuilt toolchain. F-Droid may supply a matching Debian
toolchain, subject to APK reproducibility verification, or the checksum-pinned
official archive. `tools/fetch-fdroid-sources.sh` is a separate, explicitly
network-enabled preparation helper for CI; it verifies every checkout against
the full commit in the lock file before the isolated build begins. Offline Mosh
builds use isolated ABI workers; tune their safe concurrency with
`MANGOSSH_ABI_PARALLELISM` and `MANGOSSH_ABI_BUILD_JOBS` (both default to `2`).
