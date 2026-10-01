#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Derived from mosh4android 2de58be + MangoSSH offline/no-GMP adaptations.
set -euo pipefail
source "$(dirname "$0")/target-env.sh"

build_mosh_client() {
  local host="$1"
  local prefix="$DEPS_PREFIX"
  local build_dir="$3"
  local package_dir="$4"

  local src_dir="$build_dir/mosh-src"
  rm -rf "$src_dir"
  rsync -a --exclude .git --exclude build "$ROOT_DIR/" "$src_dir/"
  patch --batch --forward --fuzz=0 -d "$src_dir" -p1 < "$ROOT_DIR/android/mosh-android.patch"

  (
    cd "$src_dir"
    ./autogen.sh
  )

  (
    cd "$src_dir"
    env \
      PATH="$(dirname "$PROTOC_BIN"):$PATH" \
      PKG_CONFIG="pkg-config --static" \
      PKG_CONFIG_LIBDIR="$prefix/lib/pkgconfig:$prefix/share/pkgconfig" \
      PROTOC="$PROTOC_BIN" \
      CC="$CC" \
      CXX="$CXX" \
      CFLAGS="$CFLAGS -DHAVE_LANGINFO_H" \
      CXXFLAGS="$CXXFLAGS -DHAVE_LANGINFO_H" \
      CPPFLAGS="-I$prefix/include" \
      LDFLAGS="$LDFLAGS -L$prefix/lib" \
      AR="$AR" \
      RANLIB="$RANLIB" \
      ./configure \
        --prefix=/usr \
        --host="$host" \
        --target="$host" \
        --enable-client \
        --disable-server \
        --disable-examples \
        --without-utempter \
        --with-crypto-library=nettle \
        --with-ncurses="$prefix" \
        --enable-static-libraries \
        --enable-static-libstdc++ \
        --enable-static-protobuf \
        --enable-static-zlib \
        --enable-static-curses \
        --enable-static-crypto
    make -s -j"$NCPU"
  )

  mkdir -p "$package_dir"
  cp "$src_dir/src/frontend/mosh-client" "$package_dir/mosh-client"
  chmod 755 "$package_dir/mosh-client"
  cp "$prefix/share/terminfo.zip" "$package_dir/terminfo.zip"
}


build_mosh_client "$HOST" "$INSTALL_DIR" "$BUILD_DIR" "$INSTALL_DIR"
