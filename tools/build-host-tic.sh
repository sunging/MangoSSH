#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Builds the host-side terminfo compiler from the same pinned ncurses source
# used for Android, avoiding output drift from fdroidserver's system tic.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=tools/lib/linux-host.sh
source "$PROJECT_DIR/tools/lib/linux-host.sh"
mangossh_require_linux_x86_64
mangossh_require_commands cc make mkdir rm

NCURSES_SOURCE="${MANGOSSH_NCURSES_SOURCE:-${MANGOSSH_MOSH_DEPS_DIR:-}/ncurses}"
BUILD_ROOT="${MANGOSSH_HOST_TIC_BUILD_ROOT:-$PROJECT_DIR/.tools/host-tic-6.4}"
TIC_BIN="$BUILD_ROOT/progs/tic"
EXPECTED_VERSION="ncurses 6.4.20221231"

die() {
    printf 'error: %s\n' "$*" >&2
    exit 1
}

[[ -f "$NCURSES_SOURCE/configure" ]] || die "pinned ncurses source is required"
case "$BUILD_ROOT" in
    "$PROJECT_DIR"/.tools/*|/tmp/mangossh-*) ;;
    *) die "unsafe host tic build directory: $BUILD_ROOT" ;;
esac

if [[ -x "$TIC_BIN" && "$($TIC_BIN -V 2>&1)" == "$EXPECTED_VERSION" ]]; then
    printf '%s\n' "$TIC_BIN"
    exit 0
fi

rm -rf -- "$BUILD_ROOT"
mkdir -p "$BUILD_ROOT"
(
    cd "$BUILD_ROOT"
    "$NCURSES_SOURCE/configure" \
        --without-shared \
        --without-debug \
        --without-manpages \
        --without-ada \
        --without-cxx-binding \
        --without-tests \
        --with-termlib \
        --disable-db-install
    make -s -j"${MAX_BUILD_JOBS:-4}"
)

[[ -x "$TIC_BIN" ]] || die "source build did not produce host tic"
[[ "$($TIC_BIN -V 2>&1)" == "$EXPECTED_VERSION" ]] ||
    die "source build produced an unexpected tic version"
printf '%s\n' "$TIC_BIN"
