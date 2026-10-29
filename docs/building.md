# Building MangoSSH

## Prepare inputs (network enabled)

Use a full JDK 17, Python 3.11.8+ and an Android SDK. The Android native contract
is `native/toolchains.json`: NDK r27d (`27.3.13750724`), CMake 3.22.1, API 26,
and four ABIs. Mosh's host CMake/Autotools versions are recorded in its cache
identity; install autoconf, automake, bison, cmake, gperf, libtool, make, ninja,
pkg-config, rsync, texinfo, binutils, zip and unzip on Linux x86_64.

```sh
git submodule update --init --recursive
bash tools/fetch-fdroid-sources.sh
bash tools/fetch-android-ndk.sh
bash tools/fetch-go.sh
sdkmanager 'cmake;3.22.1' 'platforms;android-26' 'platforms;android-37.0' 'build-tools;36.0.0'
export ANDROID_NDK_HOME="$PWD/.tools/android-ndk-linux/27.3.13750724"
export MANGOSSH_MOSH_DEPS_DIR="$PWD/.fdroid/sources"
```

Set `JAVA_HOME` to JDK 17 and `ANDROID_HOME` to the SDK. Android Studio must use
the same JDK. Go 1.27.3 and gomobile remain pinned by the existing Go contracts;
`go.mod`, `go.sum` and vendor are not replaced by a second dependency database.
The historically named `tools/fdroid-sources.lock` is the single source lock for
Mosh dependencies in **all** distributions. External F-Droid srclibs can provide
the same directory layout: zlib, protobuf (recursive submodules), ncurses, nettle.

The build adapters, compiler contract and vendored Go text use canonical LF
endings on every host. Respect `.gitattributes` when creating Windows worktrees:
CRLF in `vendor/modules.txt` breaks the shell version check and different file
bytes produce different native cache identities. Binary vendor fixtures remain binary.

Preparation moves a clean existing dependency checkout to the locked commit and
resumes an interrupted fetch in place. It refuses checkouts with local changes
and non-Git directories; preserve that work before retrying. Ordinary compilation never
fetches tools or source, even without `-PmangosshOfflineBuild=true`. Gradle Maven
resolution is controlled separately by `--offline`; prefetch dependencies before
using it. CI's shared preparation action follows this same separation.

## Build

```sh
./gradlew :app:assembleGithubDebug
# Only the ABIs needed by a local debug device:
./gradlew -PmangosshAbis=arm64-v8a :app:assembleGithubDebug
# F-Droid, with dependencies already available locally:
./gradlew --offline --no-build-cache -PmangosshOfflineBuild=true :app:assembleFdroidRelease
```

Release tasks require all four ABIs and locked sources. Both distributions use
the same native producers. `build-logic` defines the script task adapters; PTY
and termlib use AGP `externalNativeBuild`. Mosh remains an executable disguised
as `libmosh_client.so` for Android extraction into `nativeLibraryDir`. Legacy
JNI packaging remains enabled.

The shell Mosh/tsnet producers require Linux x86_64. On Windows, PTY and termlib
use the Windows SDK/NDK and CMake; Gradle runs Mosh/tsnet through WSL. Install the
Linux prerequisites above inside WSL. NDK selection first honors `ndk.dir` in
the root `local.properties` (including F-Droid's generated file), then
`ANDROID_NDK_HOME`, then the SDK's `ndk/27.3.13750724`. Every selected package
must have the locked revision and match the current host; invalid explicit
paths fail configuration. With `ndk.dir`, Gradle leaves `android.ndkPath` unset
to avoid AGP's conflicting-selector error. Linux Mosh and tsnet receive the
same selected directory, including when Gradle itself runs in WSL.
The one host fallback is a valid locked Linux NDK in `ANDROID_NDK_HOME` on
Windows: AGP uses the locked Windows SDK NDK and logs a warning.
`MANGOSSH_LINUX_JAVA_HOME`,
`MANGOSSH_LINUX_SDK_HOME`, and `MANGOSSH_LINUX_NDK_HOME` select Linux tools;
Windows `JAVA_HOME` and NDK paths are not reused for Linux executables. The
project and output paths are translated through WSLENV. Native component state
(source exports, build trees and the component cache) stays on the WSL file
system, at `${XDG_CACHE_HOME:-~/.cache}/mangossh/native-state/<checkout hash>`,
because building on a Windows drive through WSL is much slower. Set
`mangosshNativeStateDir` in your own `~/.gradle/gradle.properties` to choose a
different WSL path; a Windows path there is translated through WSLENV. A custom
`MANGOSSH_MOSH_DEPS_DIR` or `MANGOSSH_GO_ROOT` passed from Windows must already
be a Linux path. ABI and source-mode properties are forwarded identically, and
both adapters force the script compilation stage offline.

## Source policy and local edits

Default `mangosshNativeSourceMode=locked` validates the exact commit, a clean
working tree, and every recursive submodule before looking at cached output.
Tracked modifications, deletions, staged changes and untracked source files are
rejected. Ignored build output is not treated as source. Line-ending-only
differences are accepted because locked builds compile Git blobs, not the work
tree; this lets WSL build a Windows checkout made with `core.autocrlf=true`.

For deliberate development edits to a locked dependency checkout:

```sh
./gradlew -PmangosshNativeSourceMode=worktree -PmangosshAbis=arm64-v8a :app:assembleGithubDebug
```

This mode includes actual file contents, additions, deletions, modes and symlinks
in the identity and copies that working tree into the build sandbox. It still
requires matching HEAD and recursive gitlinks. Dirty identities appear in the
manifest; release builds reject worktree mode. Locked mode exports canonical Git
blobs so Windows line-ending conversion cannot alter the compiled source.

termlib JNI and libvterm come from the same upstream commit as its Kotlin import.
To verify or replay that import against a prepared upstream checkout:

```sh
python3 tools/native/termlib.py /path/to/pinned-termlib
# Explicit source maintenance only; preserve local changes first:
python3 tools/native/termlib.py /path/to/pinned-termlib --update
```

`third_party/termlib/native-import.json` records the source mapping and ordered
patches. Ordinary builds consume the checked-in source and never run the importer.
Go source updates must regenerate vendor, replay the Tailscale patch and version
metadata, update notices, and review the complete resulting diff. `go mod verify`
alone does not authenticate a vendor directory.

## Outputs, incrementality and concurrency

Gradle configuration caching and task-output caching are enabled. Keep normal
edit/build cycles incremental; `clean` is for explicitly testing reconstruction,
not a prerequisite to building. The command-line examples can use `--no-daemon`
when the Gradle JVM should exit at build completion. Kotlin stays in-process;
the pinned Kotlin 2.4.21 plugin uses BTA and supports incremental compilation in
that mode. See [build performance](build-performance.md) for profiling, measured
baselines, cache boundaries and CI behavior.

| Location | Purpose |
| --- | --- |
| `<state>/work/<component>/<fingerprint>/` | Isolated build trees and `build.log` |
| `<state>/cache/<component>/<fingerprint>/` | Validated install trees and manifests |
| `app/build/generated/native/mosh/` | Mosh `jniLibs/` and `assets/` packaging inputs, unstripped `symbols/`, and manifest, shared by Gradle and the CLI |
| `app/build/generated/tsnet/` | AAR, manifest, and unstripped AAR in `symbols/` |
| app/termlib `.cxx` and `build/` | AGP-managed JNI intermediate and symbol outputs |

`<state>` is `build/native` when Gradle runs on Linux (including CI), the WSL
directory above when it runs on Windows, or `mangosshNativeStateDir` when set.

Gradle skips `buildEmbeddedTsnetAar` as UP-TO-DATE when its declared inputs
(bridge sources, scripts, `toolchains.json`, ABIs and environment) and its
published outputs are unchanged, so an unchanged debug build never reaches WSL.
`buildMosh` is skipped the same way in locked mode, where `tools/fdroid-sources.lock`
pins every source; worktree mode always consults the adapter because Gradle
cannot see external worktree edits. Toolchains used inside WSL are verified only
by the adapter: after replacing one in place, run once with
`-PmangosshNativeAlwaysRun=true` (or `--rerun-tasks`).
The input sets include the producer scripts and notices generator, but exclude
unrelated test/verification tools and Python `__pycache__` files.

The Git revision and distribution are generated as untranslatable Android
resources, with the same twelve-character revision and archive fallback as
before. Changing only HEAD or `mangosshBuildRevision` updates resource packaging
without changing Kotlin source or its compile-time constants.

No binary or terminfo archive is generated into a source directory. Because AGP
would still package them, `verifyNoNativeSourceBinaries` fails the build when
any `app/src/*/jniLibs` file or `assets/mosh/terminfo.zip` exists. Root LICENSE and static license assets are maintained
as source inputs, never overwritten by a build. `clean` removes generated build
output; `.tools` toolchains and `.fdroid/sources` remain available.

Mosh has independent host-protoc, host-tic, zlib, protobuf, ncurses, nettle,
terminfo and client entries. Each identity records source, recipe, toolchain,
ABI/API, flags and dependency manifests. A changed client reuses its dependency
entries; a changed nettle recipe invalidates nettle and the client. Common flags
or adapter changes intentionally invalidate the components they govern.

The adapter verifies every cache hit against hashes of all installed outputs.
An empty or failed build never publishes a success manifest. Per-component locks
serialize identical work; ABI workers own separate writable source/build trees.
`MANGOSSH_ABI_PARALLELISM` defaults to 2, and `MANGOSSH_ABI_BUILD_JOBS` to 2.
Mosh and tsnet can execute concurrently when Gradle has available workers. Their
shared content-addressed cache is managed by adapter locks, rather than declared
as an overlapping Gradle `LocalState` output that would serialize both tasks.
Account for Go and ABI compiler processes when choosing a Gradle worker limit;
increasing every parallelism setting together can exhaust RAM.
Publication replaces the whole ABI directory so a narrower debug build cannot
retain stale ABIs. Do not run different Gradle invocations that publish into the
same checkout concurrently; use separate checkouts for independent builds.

Gradle task-output caching remains disabled for the native script tasks. When a
producer needs to run, its adapter verifies external source trees and output
integrity before reusing component output. An unchanged locked build can skip
the producer entirely as described above. AGP owns PTY/termlib's normal
CMake incrementality. A tsnet publication whose AAR and manifest are unchanged
is left in place. CI archive restoration is only a retrieval hint and does not
bypass the source/manifest checks. The CI verify job prunes entries that the
published manifests do not reference before saving the cache
(`python3 tools/native/build.py prune --keep <manifest>...`); the release
workflow does not restore component caches and explicitly disables Gradle task
output caching when compiling release artifacts. CodeQL also disables task and
configuration caching so its tracer observes compiler execution.

## Verification

Run the Android unit, lint and instrumentation compilation tasks in AGENTS.md.
Native contract checks and offline bridge tests:

```sh
python3 tools/native/test_native.py
python3 tools/native/build.py verify-sources
bash tools/test-mosh-no-gmp.sh
bash tools/test-go-toolchain.sh
bash tools/test-tsnet-bridge.sh
```

For every final APK, including transitive Maven JNI libraries:

```sh
python3 tools/native/verify-apk.py app/build/outputs/apk/github/debug/app-github-debug.apk
bash tools/check-16kb-elf.sh app/build/outputs/apk/github/debug/app-github-debug.apk
zipalign -c -P 16 -v 4 app/build/outputs/apk/github/debug/app-github-debug.apk
```

The verifier checks exact ABI coverage, required native components, ELF
Class/Machine, Android Mosh entry/interpreter, dynamic dependencies and 16 KiB
PT_LOAD alignment. It writes an APK `.native.json` sidecar linking library hashes
to producer manifests and local CMake source identities. ZIP alignment remains a
separate SDK check. Neither static validation nor compilation replaces device
acceptance for PTY lifecycle, terminal resize, Mosh reconnect and tsnet routing.

Release optimization must preserve the Ed25519 JCA provider's reflective service
classes and their public no-argument constructors. The cbssh JAR supplies these
consumer rules in `META-INF/proguard/cbssh.pro`. CI and release publication verify
the actual DEX definitions in both release APKs, since debug tests and string
searches cannot detect removed constructors or renamed services:

```sh
python3 tools/check-ed25519-apk.py --dexdump "$ANDROID_HOME/build-tools/36.0.0/dexdump" \
  app/build/outputs/apk/github/release/app-github-release-unsigned.apk \
  app/build/outputs/apk/fdroid/release/app-fdroid-release-unsigned.apk
```

For reproducibility, build with two independent `--state` directories and compare
packaged ELF, terminfo and AAR contents (including classes.jar); metadata containing
local build paths need not match. Use `tools/native/compare-artifacts.py` for a
byte-level output comparison. Record toolchain differences before interpreting a
mismatch. Keep unstripped outputs for diagnosis. Mosh/tsnet compiler options and
native dependency versions are unchanged by this migration. AGP selects Debug
and RelWithDebInfo for JNI; the previous PTY binary was built only as Release.

## F-Droid compatibility

`tools/prepare-fdroid-native.sh` verifies supplied inputs and prewarms the same Mosh
cache Gradle uses; PTY and termlib are compiled by AGP. `tools/build-fdroid-release.sh`
combines this with offline unsigned assembly and APK verification. Signing variables
remain forbidden in the F-Droid wrapper. Existing `build-mosh-android*` and host-tool
scripts delegate to the shared adapter; `install-mosh-assets.sh` is verification-only.
The standalone PTY script is a diagnostic build and writes under `build/native`.
The F-Droid verification wrapper explicitly disables Gradle task-output caching;
ordinary developer builds can use the cache independently of that entry point.

The authoritative fdroiddata recipe remains external. Update its SDK CMake/Python
prerequisites for this revision; previously published versions keep their original
build recipes. Source and compiler setup must finish before network isolation.

### Optional biometric sensor regressions

`BiometricEnrollmentInstrumentedTest` and `BiometricSensorInstrumentedTest` are skipped by
default, before creating an Activity or changing credentials. They require
`-e runBiometricSensor true` and a dedicated AVD named `mangossh-lock-regression`.
Enrollment also requires a fresh device without a system credential; its PIN is generated at
runtime and never logged. The host supplies virtual finger 1 for enrollment and successful
matches, and finger 2 for rejection, following the `biometricPhase` instrumentation statuses.
Never provision a developer's existing emulator or a physical phone for these tests.

### Disposable emulator regression diagnostics

The CI workflow runs two parallel jobs, GitHub and F-Droid plus termlib, each
with its own emulator. Each job compiles its device-test APKs and tests the host
helpers before booting Android, so heavy Gradle work does not compete with
system startup.
The API 35 emulator uses the full AOSP (`default`) image, retaining SystemUI and
DocumentsUI for real system-picker tests. The Google APIs image repeatedly
reached preflight with NexusLauncher already in an ANR dialog, even after
increasing its resources. The emulator has four virtual CPUs, 4 GiB RAM and a
512 MiB heap, with snapshots disabled.
`tools/start-ci-emulator.sh` boots it without injecting any key event. On this
image Launcher3 is force-stopped and restarted just after `sys.boot_completed`,
and the MENU key that `android-emulator-runner` always sends at that point made
Launcher3 ANR before preflight. Instead, `device-regression-support.py settle`
disables animations, waits until the focused window has stayed unchanged for
five seconds, and then dismisses the insecure keyguard through WindowManager.
An ANR dialog seen while settling is still fatal, and preflight still rejects
any ANR dialog before running tests.
`tools/run-device-regressions.sh` is CI-only and requires those prebuilt APKs. It
starts loopback SSH/echo and normal/stalled resource fixtures and runs the suites
named by `DEVICE_SUITES` (default `github fdroid termlib`) sequentially, checking
each selected suite's JUnit reports. Before each suite it checks the
emulator and focused window, rejects an existing ANR dialog, explicitly enables
and verifies deep Doze before exercising IDLE and restoring ACTIVE, enables both
networks, verifies reverse mappings, and
authenticates through both host and device-side listeners.
The authentication probe uses only the fixture's synthetic `fixture` account.
It does not validate application-level SSH behavior; the instrumented tests do.

Run the support checks with the fixture virtual environment:

```sh
python tools/test-device-regression-support.py  # requires Paramiko 4.0.0
bash -n tools/run-device-regressions.sh
```

`build/device-diagnostics` is uploaded even after a failure. It contains preflight
stages, device/mapping state, sanitized focused-window/ANR process metadata, and
allowlisted event summaries from logcat and fixture logs, never raw server
messages, window titles or credentials. ANR dialogs are never automatically
dismissed. The Downloads test logs
only accessibility structure (no text), and captures a screenshot only when the
active window is DocumentsUI and CI diagnostics are explicitly enabled. Existing
JUnit failures and skips remain fatal. A missing/failed preflight also fails the
job; no fixture opt-in is silently skipped.
