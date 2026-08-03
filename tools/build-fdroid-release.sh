#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Performs MangoSSH's network-isolated, source-only F-Droid release build.
# The caller must prefetch Gradle artifacts, toolchains, and Mosh dependency
# sources before disabling network access.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

die() {
    printf 'error: %s\n' "$*" >&2
    exit 1
}

[[ -x "${JAVA_HOME:-}/bin/javac" ]] || die "JAVA_HOME must provide JDK 17"
"$JAVA_HOME/bin/javac" -version 2>&1 | grep -q '^javac 17\.' || die "JDK 17 is required"
[[ -d "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]] || die "ANDROID_HOME or ANDROID_SDK_ROOT is required"
[[ -x "${ANDROID_NDK_HOME:-}/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" ]] ||
    die "ANDROID_NDK_HOME must provide Android NDK r27d"
grep -q '^Pkg.Revision = 27\.3\.13750724$' "$ANDROID_NDK_HOME/source.properties" ||
    die "Android NDK revision 27.3.13750724 (r27d) is required"
[[ -x "${MANGOSSH_GO_ROOT:-}/bin/go" ]] || die "MANGOSSH_GO_ROOT must provide Go 1.26.5"
[[ "$("$MANGOSSH_GO_ROOT/bin/go" version)" == "go version go1.26.5 linux/amd64" ]] ||
    die "Go 1.26.5 for linux/amd64 is required"
[[ -d "${MANGOSSH_MOSH_DEPS_DIR:-}" ]] || die "MANGOSSH_MOSH_DEPS_DIR is required"

for dependency in zlib protobuf ncurses gmp nettle; do
    [[ -d "$MANGOSSH_MOSH_DEPS_DIR/$dependency" ]] ||
        die "missing offline Mosh dependency source: $dependency"
done

for secret_name in \
    MANGOSSH_RELEASE_STORE_FILE \
    MANGOSSH_RELEASE_STORE_PASSWORD \
    MANGOSSH_RELEASE_KEY_ALIAS \
    MANGOSSH_RELEASE_KEY_PASSWORD; do
    [[ -z "${!secret_name:-}" ]] || die "release signing variables are forbidden in F-Droid builds"
done

export MANGOSSH_OFFLINE_BUILD=1
export GOTOOLCHAIN=local
export GOPROXY=off
export GOSUMDB=off
export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-315532800}"

if [[ -z "${MANGOSSH_PROTOC:-}" ]]; then
    export MANGOSSH_PROTOBUF_SOURCE="$MANGOSSH_MOSH_DEPS_DIR/protobuf"
    bash "$PROJECT_DIR/tools/build-host-protoc.sh"
    export MANGOSSH_PROTOC="${MANGOSSH_HOST_PROTOC_BUILD_ROOT:-$PROJECT_DIR/.tools/host-protoc-29.1}/protoc"
fi
[[ -x "$MANGOSSH_PROTOC" ]] || die "MANGOSSH_PROTOC must provide protoc 29.1"
[[ "$("$MANGOSSH_PROTOC" --version)" == "libprotoc 29.1" ]] || die "protoc 29.1 is required"

cd "$PROJECT_DIR"
bash tools/build-pty-bridge-wsl.sh
bash tools/build-mosh-android-parallel-wsl.sh
bash tools/install-mosh-assets.sh
./gradlew --offline --no-daemon :app:verifyReleaseVersion :app:assembleRelease

APK="$PROJECT_DIR/app/build/outputs/apk/release/app-release-unsigned.apk"
[[ -f "$APK" ]] || die "unsigned release APK was not produced"
bash tools/check-16kb-elf-wsl.sh "$APK"
printf 'Built unsigned F-Droid APK: %s\n' "$APK"
