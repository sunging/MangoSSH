#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Derived from mosh4android 2de58be + MangoSSH offline/no-GMP adaptations.
set -euo pipefail
source "$(dirname "$0")/target-env.sh"

patch_protobuf_pkg_config() {
  local prefix="$1"
  local absl_log_pc="$prefix/lib/pkgconfig/absl_log_internal_log_sink_set.pc"

  # CMake emits absolute exec_prefix/libdir/includedir in protobuf's .pc files.
  # Make them follow prefix before caching: the consumer relocates prefix into
  # its dependency sysroot. Otherwise __FILE__ in inline headers leaks the old
  # build directory and a restored cache still reads the original install tree.
  python3 - "$prefix" <<'PY'
import sys
from pathlib import Path
prefix = Path(sys.argv[1])
for pc in (prefix / "lib/pkgconfig").glob("*.pc"):
    lines = pc.read_text().splitlines(keepends=True)
    pc.write_text("".join(line if line.startswith("prefix=") else
                          line.replace(str(prefix), "${prefix}") for line in lines))
PY

  if [[ -f "$absl_log_pc" ]] &&
    ! grep -q -- '-Wl,-Bdynamic -llog -Wl,-Bstatic' "$absl_log_pc"; then
    sed -i 's/ -llog/ -Wl,-Bdynamic -llog -Wl,-Bstatic/' "$absl_log_pc"
  fi
}

build_protobuf() {
  local abi="$1"
  local prefix="$2"
  local build_dir="$3"

  if [[ -f "$prefix/lib/libprotobuf.a" ]]; then
    patch_protobuf_pkg_config "$prefix"
    return
  fi

  cmake -S "$SOURCES_DIR/protobuf" -B "$build_dir/protobuf" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" \
    -DANDROID_PLATFORM="$ANDROID_PLATFORM" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$prefix" \
    -DCMAKE_PREFIX_PATH="$DEPS_PREFIX" \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DCMAKE_C_FLAGS="$CFLAGS" \
    -DCMAKE_CXX_FLAGS="$CXXFLAGS" \
    -DCMAKE_EXE_LINKER_FLAGS="-static-libstdc++" \
    -Dprotobuf_BUILD_TESTS=OFF \
    -Dprotobuf_BUILD_SHARED_LIBS=OFF \
    -Dprotobuf_BUILD_PROTOC_BINARIES=OFF \
    -Dprotobuf_BUILD_LIBPROTOC=OFF \
    -Dprotobuf_WITH_ZLIB=ON \
    -DABSL_PROPAGATE_CXX_STD=ON
  cmake --build "$build_dir/protobuf" --target install --parallel "$NCPU"
  patch_protobuf_pkg_config "$prefix"
}


build_protobuf "$ABI" "$INSTALL_DIR" "$BUILD_DIR"
