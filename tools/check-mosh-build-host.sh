#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Reports whether a Linux x86_64 build host has the command-line prerequisites for
# building the bundled Android Mosh client. It is intentionally read-only.

set -eu

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=tools/lib/linux-host.sh
source "$PROJECT_DIR/tools/lib/linux-host.sh"
mangossh_require_linux_x86_64

status=0
for tool in \
    autoconf automake awk bash bison chmod cmake cp curl cut env find flock \
    getconf git gperf grep head install libtoolize make mkdir mktemp mv ninja \
    patch perl pkg-config python3 readelf rm rsync sed sha1sum sha256sum sort \
    stat tail tar unzip zip; do
    if command -v "$tool" >/dev/null 2>&1; then
        printf 'present %s\n' "$tool"
    else
        printf 'missing %s\n' "$tool"
        status=1
    fi
done
exit "$status"
