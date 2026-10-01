#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Derived from mosh4android 2de58be + MangoSSH offline/no-GMP adaptations.
set -euo pipefail
source "$(dirname "$0")/target-env.sh"

build_ncurses() {
  local host="$1"
  local prefix="$2"
  local build_dir="$3"

  if [[ -f "$prefix/lib/libtinfo.a" ]]; then
    return
  fi

  sed -i /tsearch/d "$SOURCES_DIR/ncurses/configure"
  mkdir -p "$build_dir/ncurses"
  (
    cd "$build_dir/ncurses"
    "$SOURCES_DIR/ncurses/configure" \
      --prefix="$prefix" \
      --with-default-terminfo-dir=/usr/share/terminfo \
      --with-terminfo-dirs=/usr/share/terminfo \
      --with-tic-path="$MANGOSSH_TIC" \
      --enable-pc-files \
      --without-shared \
      --without-debug \
      --without-manpages \
      --disable-stripping \
      --with-termlib \
      CC="$CC" \
      CFLAGS="$CFLAGS -DHAVE_TSEARCH=0" \
      CXX="$CXX" \
      CXXFLAGS="$CXXFLAGS" \
      LD="$CC" \
      AR="$AR" \
      RANLIB="$RANLIB" \
      --host="$host" \
      --target="$host"
    make -s -j"$NCPU"
    make -s install.libs install.includes
    make -s install.data DESTDIR="$prefix"
    mkdir -p "$prefix/share/terminfo/x"
    cp "$prefix/usr/share/terminfo/x/xterm-256color" "$prefix/share/terminfo/x/"
    rm -rf "$prefix/usr"
  )
}


build_ncurses "$HOST" "$INSTALL_DIR" "$BUILD_DIR"
