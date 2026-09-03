#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
LOCK_FILE="$SCRIPT_DIR/fdroid-sources.lock"

die() {
    printf 'error: %s\n' "$*" >&2
    exit 1
}

[[ -f "$LOCK_FILE" ]] || die "missing source lock: $LOCK_FILE"
[[ -d "${MANGOSSH_GO_SOURCE:-}" ]] || die "MANGOSSH_GO_SOURCE is required"
[[ -d "${MANGOSSH_MOSH_DEPS_DIR:-}" ]] || die "MANGOSSH_MOSH_DEPS_DIR is required"

source_dir() {
    case "$1" in
        go) printf '%s\n' "$MANGOSSH_GO_SOURCE" ;;
        mosh4android) printf '%s\n' "$PROJECT_DIR/third_party/mosh4android" ;;
        *) printf '%s\n' "$MANGOSSH_MOSH_DEPS_DIR/$1" ;;
    esac
}

while IFS='|' read -r name repository release_ref expected_commit; do
    expected_commit="${expected_commit%$'\r'}"
    [[ -n "$name" && "${name:0:1}" != "#" ]] || continue
    directory="$(source_dir "$name")"
    [[ -d "$directory" ]] || die "missing $name source directory: $directory"
    actual_commit="$(git -C "$directory" rev-parse --verify HEAD 2>/dev/null)" ||
        die "$name source is not a Git checkout: $directory"
    [[ "$actual_commit" == "$expected_commit" ]] ||
        die "$name source is $actual_commit; expected $expected_commit ($release_ref)"
    printf 'Verified %-14s %s\n' "$name" "$expected_commit"
done < "$LOCK_FILE"
