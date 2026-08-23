#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Network-enabled preparation step. The resulting trees are consumed only
# after the caller disables network access.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
LOCK_FILE="$SCRIPT_DIR/fdroid-sources.lock"
FETCH_ROOT="${MANGOSSH_FDROID_SOURCE_ROOT:-$PROJECT_DIR/.fdroid/sources}"

die() {
    printf 'error: %s\n' "$*" >&2
    exit 1
}

case "$FETCH_ROOT" in
    "$PROJECT_DIR"/.fdroid/sources) ;;
    *) die "unsafe F-Droid source root: $FETCH_ROOT" ;;
esac

mkdir -p "$FETCH_ROOT"
while IFS='|' read -r name repository release_ref commit; do
    [[ -n "$name" && "${name:0:1}" != "#" ]] || continue
    [[ "$name" != "mosh4android" ]] || continue
    target="$FETCH_ROOT/$name"
    rm -rf -- "$target"
    git init --quiet "$target"
    git -C "$target" remote add origin "$repository"
    git -C "$target" fetch --quiet --depth 1 origin "$commit"
    git -C "$target" checkout --quiet --detach FETCH_HEAD
    if [[ "$name" == "protobuf" ]]; then
        git -C "$target" submodule update --init --recursive --depth 1
    fi
    actual="$(git -C "$target" rev-parse HEAD)"
    [[ "$actual" == "$commit" ]] || die "$name checkout drifted from $commit"
    printf 'Fetched %-14s %s (%s)\n' "$name" "$commit" "$release_ref"
done < "$LOCK_FILE"

printf 'F-Droid source trees are ready under %s\n' "$FETCH_ROOT"
