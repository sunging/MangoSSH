#!/usr/bin/env sh
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Reports whether a POSIX build host has the command-line prerequisites for
# building the bundled Android Mosh client. It is intentionally read-only.

set -eu

for tool in autoconf automake cmake curl git make ninja patch perl pkg-config rsync unzip zip; do
    if command -v "$tool" >/dev/null 2>&1; then
        printf 'present %s\n' "$tool"
    else
        printf 'missing %s\n' "$tool"
    fi
done
