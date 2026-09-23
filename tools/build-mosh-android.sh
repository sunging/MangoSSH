#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Reproducibly builds the pinned ConnectBot mosh4android submodule for Android.
# The upstream script fetches the GPL-compatible dependency sources and creates
# one archive per ABI containing a static PIE mosh-client plus terminfo.zip.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=tools/lib/linux-host.sh
source "$PROJECT_DIR/tools/lib/linux-host.sh"
mangossh_require_linux_x86_64
mangossh_require_commands \
    autoconf automake awk bash bison chmod cmake cp find getconf git gperf \
    grep install libtoolize make mkdir mktemp ninja patch perl pkg-config \
    readelf rm rsync sed sort tar unzip zip
if [[ "${MANGOSSH_OFFLINE_BUILD:-0}" != "1" ]]; then
    mangossh_require_commands curl tic
    export MANGOSSH_TIC="${MANGOSSH_TIC:-$(command -v tic)}"
fi
NDK_REVISION="27.3.13750724"
NDK_HOME="${ANDROID_NDK_HOME:-$PROJECT_DIR/.tools/android-ndk-linux/$NDK_REVISION}"
# A temporary LF-normalized checkout can be supplied when the Windows working
# tree has converted the submodule's shell scripts to CRLF for local tooling.
UPSTREAM_MOSH_SOURCE="${MOSH_SOURCE:-$PROJECT_DIR/third_party/mosh4android}"
PATCH_FILE="$PROJECT_DIR/tools/patches/mosh4android-offline-sources.patch"
NO_GMP_PATCH_FILE="$PROJECT_DIR/tools/patches/mosh4android-no-gmp.patch"
PATCHED_MOSH_SOURCE="${MANGOSSH_PATCHED_MOSH_SOURCE:-$PROJECT_DIR/.tools/mosh4android-patched}"

[[ -x "$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" ]] || {
    echo "Android NDK r27d is required; run tools/fetch-android-ndk.sh first." >&2
    exit 1
}
[[ -f "$UPSTREAM_MOSH_SOURCE/android/build-android-release-assets.sh" ]] || {
    echo "The mosh4android submodule is unavailable; initialize submodules first." >&2
    exit 1
}
[[ -f "$PATCH_FILE" ]] || { echo "Missing Mosh offline-source patch." >&2; exit 1; }
[[ -f "$NO_GMP_PATCH_FILE" ]] || { echo "Missing Mosh no-GMP patch." >&2; exit 1; }

case "$PATCHED_MOSH_SOURCE" in
    "$PROJECT_DIR"/.tools/*) ;;
    *) echo "Unsafe patched Mosh source path: $PATCHED_MOSH_SOURCE" >&2; exit 1 ;;
esac
git -C "$UPSTREAM_MOSH_SOURCE" rev-parse --verify HEAD >/dev/null 2>&1 || {
    echo "The Mosh source must be a checked-out Git work tree." >&2
    exit 1
}
rm -rf -- "$PATCHED_MOSH_SOURCE"
mkdir -p "$PATCHED_MOSH_SOURCE"
# Export canonical Git blobs instead of copying the Windows work tree. This
# preserves the upstream LF line endings needed by autoconf and its patches.
git -C "$UPSTREAM_MOSH_SOURCE" archive --format=tar HEAD |
    tar -xf - -C "$PATCHED_MOSH_SOURCE"
(
    cd "$PATCHED_MOSH_SOURCE"
    patch --dry-run --batch --forward --fuzz=0 -p1 --input="$PATCH_FILE"
    patch --batch --forward --fuzz=0 -p1 --input="$PATCH_FILE"
    # Mosh only uses Nettle AES, not the GMP-backed public-key library.
    patch --dry-run --batch --forward --fuzz=0 -p1 --input="$NO_GMP_PATCH_FILE"
    patch --batch --forward --fuzz=0 -p1 --input="$NO_GMP_PATCH_FILE"
)
grep -Fq 'EXTERNAL_SOURCES_DIR="${MANGOSSH_MOSH_DEPS_DIR:-}"' \
    "$PATCHED_MOSH_SOURCE/android/build-android-release-assets.sh" || {
    echo "The exported Mosh source did not receive the offline-source patch." >&2
    exit 1
}
MOSH_SOURCE="$PATCHED_MOSH_SOURCE"

if [[ "${MANGOSSH_OFFLINE_BUILD:-0}" == "1" ]]; then
    [[ -d "${MANGOSSH_MOSH_DEPS_DIR:-}" ]] || {
        echo "MANGOSSH_MOSH_DEPS_DIR is required in offline mode." >&2
        exit 1
    }
    [[ -x "${MANGOSSH_PROTOC:-}" ]] || {
        echo "MANGOSSH_PROTOC must provide protoc 29.1 in offline mode." >&2
        exit 1
    }
    [[ -x "${MANGOSSH_TIC:-}" ]] || {
        echo "MANGOSSH_TIC must provide source-built ncurses 6.4 tic in offline mode." >&2
        exit 1
    }
    [[ "$("$MANGOSSH_TIC" -V 2>&1)" == "ncurses 6.4.20221231" ]] || {
        echo "MANGOSSH_TIC must be ncurses 6.4.20221231." >&2
        exit 1
    }
fi

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
if [[ "${MANGOSSH_OFFLINE_BUILD:-0}" == "1" ]]; then
    for network_tool in curl git wget; do
        printf '%s\n' \
            '#!/usr/bin/env sh' \
            'echo "Network-capable command is disabled during the offline Mosh build." >&2' \
            'exit 97' > "$shim_dir/$network_tool"
        chmod 755 "$shim_dir/$network_tool"
    done
fi
export PATH="$shim_dir:$PATH"

bash "$MOSH_SOURCE/android/build-android-release-assets.sh"
