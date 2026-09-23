#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Checks both Mosh patches against canonical sources, without building or networking.
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
work_dir="$(mktemp -d)"
trap 'rm -rf -- "$work_dir"' EXIT
git -C "$PROJECT_DIR/third_party/mosh4android" archive HEAD | tar -xf - -C "$work_dir"
for name in offline-sources no-gmp; do
    patch --batch --forward --fuzz=0 -d "$work_dir" -p1 \
        --input="$PROJECT_DIR/tools/patches/mosh4android-$name.patch"
done
script="$work_dir/android/build-android-release-assets.sh"
bash -n "$script"
grep -Fq -- '--disable-public-key' "$script"
grep -Fq -- '--disable-mini-gmp' "$script"
if grep -Eq 'GMP_VERSION|build_gmp|SOURCES_DIR/gmp|libgmp|libhogweed' "$script" ||
    grep -q '^gmp|' "$PROJECT_DIR/tools/fdroid-sources.lock"; then
    echo 'Unexpected GMP dependency in the effective Mosh build.' >&2
    exit 1
fi
echo 'Mosh patch chain excludes GMP and disables Nettle public-key support.'
