#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Derived from mosh4android 2de58be + MangoSSH offline/no-GMP adaptations.
set -euo pipefail
source "$(dirname "$0")/target-env.sh"

build_zlib() {
  local abi="$1"
  local prefix="$2"
  local build_dir="$3"

  if [[ -f "$prefix/lib/libz.a" ]]; then
    return
  fi

  cmake -S "$SOURCES_DIR/zlib" -B "$build_dir/zlib" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" \
    -DANDROID_PLATFORM="$ANDROID_PLATFORM" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$prefix" \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DBUILD_SHARED_LIBS=OFF \
    -DCMAKE_C_FLAGS="$CFLAGS"
  cmake --build "$build_dir/zlib" --target install --parallel "$NCPU"
}


build_zlib "$ABI" "$INSTALL_DIR" "$BUILD_DIR"
