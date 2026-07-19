#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Cross-compiles MangoSSH's JNI PTY bridge with the same Linux-host NDK used by
# Mosh. The resulting shared libraries are source-controlled Android inputs so
# Gradle on Windows can package them without downloading a second Windows NDK.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
NDK_REVISION="27.3.13750724"
NDK_HOME="${ANDROID_NDK_HOME:-$PROJECT_DIR/.tools/android-ndk-linux/$NDK_REVISION}"
SOURCE_DIR="$PROJECT_DIR/app/src/main/cpp"
BUILD_ROOT="${BUILD_ROOT:-$PROJECT_DIR/.tools/pty-bridge-build}"
JNI_LIBS_DIR="$PROJECT_DIR/app/src/main/jniLibs"
ABIS="${ABIS:-arm64-v8a armeabi-v7a x86 x86_64}"
READELF="$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
STRIP="$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
REQUIRED_ALIGNMENT=$((16 * 1024))

[[ -x "$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" && -x "$READELF" && -x "$STRIP" ]] || {
    echo "Android NDK r27d is required; run tools/fetch-android-ndk-wsl.sh first." >&2
    exit 1
}

## Rejects a bridge whose loadable ELF segments cannot map on 16 KiB devices.
#
# NDK r27 does not enable this linker layout by default. Keeping this check in
# the producer script prevents a future CMake or toolchain change from silently
# reintroducing a 4 KiB-only JNI library.
require_16k_load_alignment() {
    local library="$1"
    local alignment
    local saw_load_segment=0

    while IFS= read -r alignment; do
        saw_load_segment=1
        if (( alignment < REQUIRED_ALIGNMENT )); then
            printf '16 KiB compatibility failure: %s has PT_LOAD alignment %s.\n' \
                "$library" "$alignment" >&2
            exit 1
        fi
    done < <("$READELF" -lW "$library" | awk '$1 == "LOAD" { print $NF }')

    (( saw_load_segment == 1 )) || {
        echo "No PT_LOAD segments found in $library." >&2
        exit 1
    }
}

for abi in $ABIS; do
    build_dir="$BUILD_ROOT/$abi"
    cmake -S "$SOURCE_DIR" -B "$build_dir" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$NDK_HOME/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" \
        -DANDROID_PLATFORM=android-26 \
        -DCMAKE_BUILD_TYPE=Release
    cmake --build "$build_dir" --parallel
    require_16k_load_alignment "$build_dir/libmangossh_pty.so"
    mkdir -p "$JNI_LIBS_DIR/$abi"
    install -m 0755 "$build_dir/libmangossh_pty.so" "$JNI_LIBS_DIR/$abi/libmangossh_pty.so"
    # Keep source-controlled JNI assets free of compiler debug information even
    # when this script is run without the later Mosh asset installation step.
    "$STRIP" --strip-unneeded "$JNI_LIBS_DIR/$abi/libmangossh_pty.so"
    require_16k_load_alignment "$JNI_LIBS_DIR/$abi/libmangossh_pty.so"
done

echo "Installed PTY bridge for: $ABIS"
