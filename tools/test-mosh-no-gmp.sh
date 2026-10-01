#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Checks that the effective Mosh recipes exclude GMP, without building or networking.
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
recipes="$PROJECT_DIR/native/mosh/recipes"
recipe="$recipes/nettle.sh"
bash -n "$recipe"
grep -Fq -- '--disable-public-key' "$recipe"
grep -Fq -- '--disable-mini-gmp' "$recipe"
if grep -REq 'GMP_VERSION|build_gmp|SOURCES_DIR/gmp|libgmp|libhogweed' "$recipes" ||
    grep -q '^gmp|' "$PROJECT_DIR/tools/fdroid-sources.lock"; then
    echo 'Unexpected GMP dependency in the effective Mosh build.' >&2
    exit 1
fi
echo 'Mosh recipes exclude GMP and disable Nettle public-key support.'
