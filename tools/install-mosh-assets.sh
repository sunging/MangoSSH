#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Validates and installs the Mosh release archives produced by
# build-mosh-android-wsl.sh into Android packaging locations. The executable is
# named .so solely so Android extracts it into nativeLibraryDir with executable
# permissions; MoshPtyProcess executes it directly and never dlopens it.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
NDK_REVISION="27.3.13750724"
NDK_HOME="${ANDROID_NDK_HOME:-$PROJECT_DIR/.tools/android-ndk-linux/$NDK_REVISION}"
BUILD_DIR="${WORK_DIR:-$PROJECT_DIR/.tools/mosh-android-build}"
MOSH_SOURCE="$PROJECT_DIR/third_party/mosh4android"
JNI_LIBS_DIR="$PROJECT_DIR/app/src/main/jniLibs"
ASSETS_DIR="$PROJECT_DIR/app/src/main/assets"
READELF="$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
STRIP="$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
ABIS="${ABIS:-arm64-v8a armeabi-v7a x86 x86_64}"
REQUIRED_ALIGNMENT=$((16 * 1024))

[[ -x "$READELF" && -x "$STRIP" ]] || { echo "Android NDK r27d is required." >&2; exit 1; }
[[ -f "$MOSH_SOURCE/COPYING" ]] || { echo "Mosh source submodule is unavailable." >&2; exit 1; }

## Fails the asset installation if an ELF cannot map on a 16 KiB-page device.
#
# The final APK check is still required because packaging can add other native
# libraries. This build-time guard keeps invalid Mosh or PTY binaries out of
# source control before they reach Gradle.
require_16k_load_alignment() {
    local binary="$1"
    local label="$2"
    local alignment
    local saw_load_segment=0

    while IFS= read -r alignment; do
        saw_load_segment=1
        if (( alignment < REQUIRED_ALIGNMENT )); then
            printf '%s is not 16 KiB compatible: PT_LOAD alignment %s.\n' \
                "$label" "$alignment" >&2
            exit 1
        fi
    done < <("$READELF" -lW "$binary" | awk '$1 == "LOAD" { print $NF }')

    (( saw_load_segment == 1 )) || {
        echo "$label has no PT_LOAD segments." >&2
        exit 1
    }
}

temp_dir="$(mktemp -d)"
trap 'rm -rf "$temp_dir"' EXIT
first_terminfo=""

for abi in $ABIS; do
    archive="$BUILD_DIR/mosh-android-$abi.zip"
    bridge="$JNI_LIBS_DIR/$abi/libmangossh_pty.so"
    [[ -f "$archive" ]] || { echo "Missing Mosh archive for $abi: $archive" >&2; exit 1; }
    [[ -f "$bridge" ]] || { echo "Missing PTY bridge for $abi: $bridge" >&2; exit 1; }
    unzip -p "$archive" mosh-client > "$temp_dir/mosh-client-$abi"
    unzip -p "$archive" terminfo.zip > "$temp_dir/terminfo-$abi.zip"
    [[ -s "$temp_dir/mosh-client-$abi" && -s "$temp_dir/terminfo-$abi.zip" ]] || {
        echo "Invalid Mosh archive for $abi." >&2
        exit 1
    }
    "$READELF" -h "$temp_dir/mosh-client-$abi" | grep -q 'Type:.*DYN' || {
        echo "Mosh client for $abi is not a PIE ELF executable." >&2
        exit 1
    }
    "$READELF" -h "$bridge" | grep -q 'Type:.*DYN' || {
        echo "PTY bridge for $abi is not a shared ELF library." >&2
        exit 1
    }
    require_16k_load_alignment "$temp_dir/mosh-client-$abi" "Mosh client for $abi"
    require_16k_load_alignment "$bridge" "PTY bridge for $abi"
    if "$READELF" -d "$temp_dir/mosh-client-$abi" | grep -q 'libc++_shared\.so'; then
        echo "Mosh client for $abi unexpectedly needs libc++_shared.so." >&2
        exit 1
    fi
    # terminfo is architecture-independent; retain the first validated copy.
    [[ -n "$first_terminfo" ]] || first_terminfo="$temp_dir/terminfo-$abi.zip"
    mkdir -p "$JNI_LIBS_DIR/$abi"
    install -m 0755 "$temp_dir/mosh-client-$abi" "$JNI_LIBS_DIR/$abi/libmosh_client.so"
    # Keep the packaged executable and JNI bridge compact without changing
    # their ELF type, interpreter, or required dynamic symbols.
    "$STRIP" --strip-unneeded "$JNI_LIBS_DIR/$abi/libmosh_client.so"
    "$STRIP" --strip-unneeded "$bridge"
    require_16k_load_alignment "$JNI_LIBS_DIR/$abi/libmosh_client.so" "Packaged Mosh client for $abi"
    require_16k_load_alignment "$bridge" "Packaged PTY bridge for $abi"
done

mkdir -p "$ASSETS_DIR/mosh" "$ASSETS_DIR/licenses"
install -m 0644 "$first_terminfo" "$ASSETS_DIR/mosh/terminfo.zip"
install -m 0644 "$MOSH_SOURCE/COPYING" "$ASSETS_DIR/licenses/GPL-3.0-or-later.txt"
install -m 0644 "$MOSH_SOURCE/COPYING" "$PROJECT_DIR/LICENSE"

echo "Installed validated Mosh assets for: $ABIS"
