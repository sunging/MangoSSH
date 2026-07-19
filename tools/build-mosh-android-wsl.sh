#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Reproducibly builds the pinned ConnectBot mosh4android submodule for Android.
# The upstream script fetches the GPL-compatible dependency sources and creates
# one archive per ABI containing a static PIE mosh-client plus terminfo.zip.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
NDK_REVISION="27.3.13750724"
NDK_HOME="${ANDROID_NDK_HOME:-$PROJECT_DIR/.tools/android-ndk-linux/$NDK_REVISION}"
# A temporary LF-normalized checkout can be supplied when the Windows working
# tree has converted the submodule's shell scripts to CRLF for local tooling.
MOSH_SOURCE="${MOSH_SOURCE:-$PROJECT_DIR/third_party/mosh4android}"

[[ -x "$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" ]] || {
    echo "Android NDK r27d is required; run tools/fetch-android-ndk-wsl.sh first." >&2
    exit 1
}
[[ -f "$MOSH_SOURCE/android/build-android-release-assets.sh" ]] || {
    echo "The mosh4android submodule is unavailable; initialize submodules first." >&2
    exit 1
}

export ANDROID_NDK_HOME="$NDK_HOME"
export WORK_DIR="${WORK_DIR:-$PROJECT_DIR/.tools/mosh-android-build}"
export ANDROID_PLATFORM="${ANDROID_PLATFORM:-android-26}"
export ABIS="${ABIS:-arm64-v8a armeabi-v7a x86 x86_64}"

# The upstream script derives its parallelism from getconf. Limit it through a
# PATH-local shim so a laptop does not compile four large dependency graphs at
# every logical CPU simultaneously. The upstream source remains unmodified.
MAX_BUILD_JOBS="${MAX_BUILD_JOBS:-4}"
[[ "$MAX_BUILD_JOBS" =~ ^[1-9][0-9]*$ ]] || {
    echo "MAX_BUILD_JOBS must be a positive integer." >&2
    exit 1
}
shim_dir="$(mktemp -d)"
trap 'rm -rf "$shim_dir"' EXIT
printf '%s\n' '#!/usr/bin/env sh' \
    'if [ "$1" = "_NPROCESSORS_ONLN" ]; then' \
    "  printf '%s\\n' '$MAX_BUILD_JOBS'" \
    'else' \
    '  command -p getconf "$@"' \
    'fi' > "$shim_dir/getconf"
chmod 755 "$shim_dir/getconf"
export PATH="$shim_dir:$PATH"

bash "$MOSH_SOURCE/android/build-android-release-assets.sh"
