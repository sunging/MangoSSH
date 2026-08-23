#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
GO_VERSION="1.26.5"
GO_SOURCE="${MANGOSSH_GO_SOURCE:-}"
GO_ROOT="${MANGOSSH_GO_ROOT:-$PROJECT_DIR/.tools/go/$GO_VERSION}"

die() {
    printf 'error: %s\n' "$*" >&2
    exit 1
}

[[ -f "$GO_SOURCE/src/make.bash" ]] || die "MANGOSSH_GO_SOURCE must provide Go $GO_VERSION source"

if [[ ! -x "$GO_SOURCE/bin/go" ]] ||
    [[ "$("$GO_SOURCE/bin/go" version 2>/dev/null || true)" != "go version go$GO_VERSION linux/amd64" ]]; then
    bootstrap_root="${GOROOT_BOOTSTRAP:-}"
    if [[ -z "$bootstrap_root" ]]; then
        command -v go >/dev/null 2>&1 || die "GOROOT_BOOTSTRAP or a system Go compiler is required"
        bootstrap_root="$(go env GOROOT)"
    fi
    [[ -x "$bootstrap_root/bin/go" ]] || die "invalid Go bootstrap root: $bootstrap_root"
    (
        cd "$GO_SOURCE/src"
        env GOROOT_BOOTSTRAP="$bootstrap_root" GOTOOLCHAIN=local ./make.bash
    )
fi

[[ "$("$GO_SOURCE/bin/go" version)" == "go version go$GO_VERSION linux/amd64" ]] ||
    die "source build did not produce Go $GO_VERSION for linux/amd64"

case "$GO_ROOT" in
    "$PROJECT_DIR"/.tools/go/$GO_VERSION) ;;
    *) die "MANGOSSH_GO_ROOT must be $PROJECT_DIR/.tools/go/$GO_VERSION" ;;
esac
mkdir -p "$(dirname "$GO_ROOT")"
rm -rf -- "$GO_ROOT"
ln -s "$GO_SOURCE" "$GO_ROOT"
printf 'Source-built Go %s is available at %s\n' "$GO_VERSION" "$GO_ROOT"
