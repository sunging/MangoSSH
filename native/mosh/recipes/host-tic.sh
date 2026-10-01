#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
mkdir -p "$BUILD_DIR" "$INSTALL_DIR/bin"
cd "$BUILD_DIR"
CFLAGS="-ffile-prefix-map=$WORK_DIR=." "$SOURCES_DIR/ncurses/configure" \
    --without-shared --without-debug --without-manpages --without-ada \
    --without-cxx-binding --without-tests --with-termlib --disable-db-install
make -s -j"$NCPU"
install -m 0755 progs/tic "$INSTALL_DIR/bin/tic"
test "$("$INSTALL_DIR/bin/tic" -V 2>&1)" = 'ncurses 6.4.20221231'
