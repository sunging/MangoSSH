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
    commit="${commit%$'\r'}"
    [[ -n "$name" && "${name:0:1}" != "#" ]] || continue
    [[ "$name" != "mosh4android" ]] || continue
    target="$FETCH_ROOT/$name"
    if [[ -d "$target/.git" ]] &&
        [[ "$(git -C "$target" rev-parse HEAD 2>/dev/null || true)" == "$commit" ]]; then
        git -C "$target" reset --hard --quiet "$commit"
        git -C "$target" clean -dffx --quiet
        if [[ "$name" == "protobuf" ]]; then
            git -C "$target" submodule update --init --recursive --depth 1
        fi
        printf 'Reused  %-14s %s (%s)\n' "$name" "$commit" "$release_ref"
        continue
    fi
    rm -rf -- "$target"
    git init --quiet "$target"
    git -C "$target" remote add origin "$repository"
    fetched=0
    for attempt in 1 2 3; do
        if git -C "$target" fetch --quiet --depth 1 origin "$commit"; then
            fetched=1
            break
        fi
        if ((attempt < 3)); then
            printf 'Retrying %-14s fetch (%d/3)\n' "$name" "$((attempt + 1))" >&2
            sleep "$((attempt * 2))"
        fi
    done
    [[ "$fetched" == 1 ]] || die "failed to fetch $name at $commit after 3 attempts"
    git -C "$target" checkout --quiet --detach FETCH_HEAD
    if [[ "$name" == "protobuf" ]]; then
        git -C "$target" submodule update --init --recursive --depth 1
    fi
    actual="$(git -C "$target" rev-parse HEAD)"
    [[ "$actual" == "$commit" ]] || die "$name checkout drifted from $commit"
    printf 'Fetched %-14s %s (%s)\n' "$name" "$commit" "$release_ref"
done < "$LOCK_FILE"

printf 'F-Droid source trees are ready under %s\n' "$FETCH_ROOT"
