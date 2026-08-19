#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Builds the exact host protoc used to generate Mosh's Android protobuf code.
# F-Droid supplies the protobuf source tree before the network-isolated build.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=tools/lib/linux-host.sh
source "$PROJECT_DIR/tools/lib/linux-host.sh"
mangossh_require_linux_x86_64
mangossh_require_commands cmake grep ninja
PROTOBUF_VERSION="29.1"
PROTOBUF_SOURCE="${MANGOSSH_PROTOBUF_SOURCE:-${MANGOSSH_MOSH_DEPS_DIR:-}/protobuf}"
BUILD_ROOT="${MANGOSSH_HOST_PROTOC_BUILD_ROOT:-$PROJECT_DIR/.tools/host-protoc-$PROTOBUF_VERSION}"
PROTOC_BIN="$BUILD_ROOT/protoc"

[[ -f "$PROTOBUF_SOURCE/CMakeLists.txt" ]] || {
    printf 'protobuf %s source is required at %s.\n' "$PROTOBUF_VERSION" "$PROTOBUF_SOURCE" >&2
    exit 1
}
[[ -f "$PROTOBUF_SOURCE/third_party/abseil-cpp/CMakeLists.txt" ]] || {
    printf 'protobuf submodules are incomplete under %s.\n' "$PROTOBUF_SOURCE" >&2
    exit 1
}

cmake -S "$PROTOBUF_SOURCE" -B "$BUILD_ROOT" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -Dprotobuf_BUILD_TESTS=OFF \
    -Dprotobuf_BUILD_SHARED_LIBS=OFF \
    -Dprotobuf_WITH_ZLIB=OFF
cmake --build "$BUILD_ROOT" --target protoc --parallel "${MAX_BUILD_JOBS:-4}"

[[ -x "$PROTOC_BIN" ]] || { printf 'Host protoc was not produced.\n' >&2; exit 1; }
[[ "$("$PROTOC_BIN" --version)" == "libprotoc $PROTOBUF_VERSION" ]] || {
    printf 'Unexpected protoc version at %s.\n' "$PROTOC_BIN" >&2
    exit 1
}
printf '%s\n' "$PROTOC_BIN"
