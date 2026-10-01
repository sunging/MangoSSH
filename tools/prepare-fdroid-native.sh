#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Builds application native inputs from pinned source using preinstalled tools.
# It stops before Gradle so fdroidserver can perform its standard
# assembleFdroidRelease step.

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
[[ -d "${MANGOSSH_MOSH_DEPS_DIR:-}" ]] || die "MANGOSSH_MOSH_DEPS_DIR is required"

for secret_name in \
    MANGOSSH_RELEASE_STORE_FILE \
    MANGOSSH_RELEASE_STORE_PASSWORD \
    MANGOSSH_RELEASE_KEY_ALIAS \
    MANGOSSH_RELEASE_KEY_PASSWORD; do
    [[ -z "${!secret_name:-}" ]] || die "release signing variables are forbidden in F-Droid builds"
done

# shellcheck source=tools/lib/go-toolchain.sh
source "$PROJECT_DIR/tools/lib/go-toolchain.sh"
export MANGOSSH_GO_ROOT="${MANGOSSH_GO_ROOT:-$PROJECT_DIR/.tools/go/$MANGOSSH_GO_VERSION}"
mangossh_require_go "$MANGOSSH_GO_ROOT"
export MANGOSSH_OFFLINE_BUILD=1
export GOTOOLCHAIN=local
export GOPROXY=off
export GOSUMDB=off
export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-315532800}"
export LC_ALL=C
export TZ=UTC

bash "$PROJECT_DIR/tools/verify-fdroid-sources.sh"

# PTY and termlib are built by AGP externalNativeBuild during assembly.
# This compatibility entry prewarms the same Mosh component cache used by Gradle.
[[ "${MANGOSSH_NATIVE_SOURCE_MODE:-locked}" == locked ]] || die "F-Droid requires locked sources"
[[ "${ABIS:-arm64-v8a armeabi-v7a x86 x86_64}" == "arm64-v8a armeabi-v7a x86 x86_64" ]] || die "F-Droid requires all four ABIs"
bash "$PROJECT_DIR/tools/build-mosh-android.sh"
printf 'Pinned native sources and Mosh cache are ready for assembleFdroidRelease.\n'
