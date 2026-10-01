#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Derived from mosh4android 2de58be + MangoSSH offline/no-GMP adaptations.
set -euo pipefail
source "$(dirname "$0")/target-env.sh"

build_nettle() {
  local host="$1"
  local prefix="$2"
  local build_dir="$3"

  if [[ -f "$prefix/lib/libnettle.a" ]]; then
    return
  fi

  local src_dir="$build_dir/nettle-src"
  rm -rf "$src_dir"
  rsync -a "$SOURCES_DIR/nettle/" "$src_dir/"
  (
    cd "$src_dir"
    autoreconf -fvi
    ./configure \
      --prefix="$prefix" \
      CC="$CC" \
      CFLAGS="$CFLAGS" \
      CXX="$CXX" \
      CXXFLAGS="$CXXFLAGS" \
      LD="$CC" \
      AR="$AR" \
      RANLIB="$RANLIB" \
      ASM_FLAGS="$ASMFLAGS" \
      --host="$host" \
      --target="$host" \
      --disable-public-key \
      --disable-mini-gmp \
      --with-include-path="$prefix/include" \
      --with-lib-path="$prefix/lib"
    make -s -j"$NCPU" libnettle.a
    make -s install
  )
}


build_nettle "$HOST" "$INSTALL_DIR" "$BUILD_DIR"
