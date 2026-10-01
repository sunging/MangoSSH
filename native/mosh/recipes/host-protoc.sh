#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
cmake -S "$SOURCES_DIR/protobuf" -B "$BUILD_DIR" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -Dprotobuf_BUILD_TESTS=OFF -Dprotobuf_BUILD_SHARED_LIBS=OFF \
    -Dprotobuf_WITH_ZLIB=OFF \
    "-DCMAKE_C_FLAGS=-ffile-prefix-map=$WORK_DIR=." \
    "-DCMAKE_CXX_FLAGS=-ffile-prefix-map=$WORK_DIR=."
cmake --build "$BUILD_DIR" --target protoc --parallel "$NCPU"
mkdir -p "$INSTALL_DIR/bin"
install -m 0755 "$BUILD_DIR/protoc" "$INSTALL_DIR/bin/protoc"
test "$("$INSTALL_DIR/bin/protoc" --version)" = 'libprotoc 29.1'
