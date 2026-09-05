#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Builds every native input from pinned source. It intentionally stops before
# Gradle so fdroidserver can perform its standard assembleFdroidRelease step.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=tools/lib/linux-host.sh
source "$PROJECT_DIR/tools/lib/linux-host.sh"
mangossh_require_linux_x86_64
mangossh_require_commands bash cmake git grep ln rm

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
[[ -d "${MANGOSSH_GO_SOURCE:-}" ]] || die "MANGOSSH_GO_SOURCE is required"
[[ -d "${MANGOSSH_MOSH_DEPS_DIR:-}" ]] || die "MANGOSSH_MOSH_DEPS_DIR is required"

for secret_name in \
    MANGOSSH_RELEASE_STORE_FILE \
    MANGOSSH_RELEASE_STORE_PASSWORD \
    MANGOSSH_RELEASE_KEY_ALIAS \
    MANGOSSH_RELEASE_KEY_PASSWORD; do
    [[ -z "${!secret_name:-}" ]] || die "release signing variables are forbidden in F-Droid builds"
done

export MANGOSSH_GO_ROOT="${MANGOSSH_GO_ROOT:-$PROJECT_DIR/.tools/go/1.26.7}"
export MANGOSSH_OFFLINE_BUILD=1
export GOTOOLCHAIN=local
export GOPROXY=off
export GOSUMDB=off
export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-315532800}"
export LC_ALL=C
export TZ=UTC

bash "$PROJECT_DIR/tools/verify-fdroid-sources.sh"
bash "$PROJECT_DIR/tools/build-fdroid-go.sh"

# Never let a failed source build fall back to binaries checked into the
# developer repository. fdroidserver's scandelete performs the same removal
# before this script runs; keeping it here makes local and CI builds equally
# strict.
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    rm -f -- \
        "$PROJECT_DIR/app/src/main/jniLibs/$abi/libmangossh_pty.so" \
        "$PROJECT_DIR/app/src/main/jniLibs/$abi/libmosh_client.so"
done
rm -f -- \
    "$PROJECT_DIR/app/src/main/assets/mosh/terminfo.zip" \
    "$PROJECT_DIR/app/build/generated/tsnet/mangossh-tsnet.aar"

export MANGOSSH_PROTOBUF_SOURCE="$MANGOSSH_MOSH_DEPS_DIR/protobuf"
bash "$PROJECT_DIR/tools/build-host-protoc.sh"
export MANGOSSH_PROTOC="${MANGOSSH_HOST_PROTOC_BUILD_ROOT:-$PROJECT_DIR/.tools/host-protoc-29.1}/protoc"
[[ -x "$MANGOSSH_PROTOC" ]] || die "MANGOSSH_PROTOC must provide protoc 29.1"
[[ "$("$MANGOSSH_PROTOC" --version)" == "libprotoc 29.1" ]] || die "protoc 29.1 is required"

export MANGOSSH_NCURSES_SOURCE="$MANGOSSH_MOSH_DEPS_DIR/ncurses"
bash "$PROJECT_DIR/tools/build-host-tic.sh"
export MANGOSSH_TIC="${MANGOSSH_HOST_TIC_BUILD_ROOT:-$PROJECT_DIR/.tools/host-tic-6.4}/progs/tic"
[[ -x "$MANGOSSH_TIC" ]] || die "MANGOSSH_TIC must provide ncurses 6.4 tic"
[[ "$("$MANGOSSH_TIC" -V 2>&1)" == "ncurses 6.4.20221231" ]] ||
    die "ncurses 6.4.20221231 host tic is required"

cd "$PROJECT_DIR"
bash tools/build-pty-bridge.sh
bash tools/build-mosh-android-parallel.sh
bash tools/install-mosh-assets.sh
printf 'Pinned native F-Droid inputs are ready for assembleFdroidRelease.\n'
