#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
GO_VERSION="1.26.5"
GO_SOURCE="${MANGOSSH_GO_SOURCE:-}"
GO_ROOT="${MANGOSSH_GO_ROOT:-$PROJECT_DIR/.tools/go/$GO_VERSION}"
DEPS_DIR="${MANGOSSH_MOSH_DEPS_DIR:-}"

declare -a BOOTSTRAP_VERSIONS=("1.20.14" "1.22.12" "1.24.6")

die() {
    printf 'error: %s\n' "$*" >&2
    exit 1
}

[[ -f "$GO_SOURCE/src/make.bash" ]] || die "MANGOSSH_GO_SOURCE must provide Go $GO_VERSION source"
[[ -d "$DEPS_DIR" ]] || die "MANGOSSH_MOSH_DEPS_DIR is required"

go_version() {
    "$1/bin/go" version 2>/dev/null || true
}

build_go() {
    local source_root="$1"
    local version="$2"
    local bootstrap_root="$3"

    [[ -f "$source_root/src/make.bash" ]] || die "missing Go $version source: $source_root"
    [[ -x "$bootstrap_root/bin/go" ]] || die "invalid Go bootstrap root: $bootstrap_root"
    if [[ "$(go_version "$source_root")" != "go version go$version linux/amd64" ]]; then
        (
            cd "$source_root/src"
            env GOROOT_BOOTSTRAP="$bootstrap_root" GOTOOLCHAIN=local ./make.bash
        )
    fi
    [[ "$(go_version "$source_root")" == "go version go$version linux/amd64" ]] ||
        die "source build did not produce Go $version for linux/amd64"
    printf 'Source-built Go %s bootstrap is ready.\n' "$version"
}

bootstrap_root="${GOROOT_BOOTSTRAP:-}"
if [[ -z "$bootstrap_root" ]]; then
    command -v go >/dev/null 2>&1 || die "GOROOT_BOOTSTRAP or a system Go compiler is required"
    bootstrap_root="$(go env GOROOT)"
fi
[[ -x "$bootstrap_root/bin/go" ]] || die "invalid initial Go bootstrap root: $bootstrap_root"

for version in "${BOOTSTRAP_VERSIONS[@]}"; do
    source_root="$DEPS_DIR/go-bootstrap-$version"
    build_go "$source_root" "$version" "$bootstrap_root"
    bootstrap_root="$source_root"
done
build_go "$GO_SOURCE" "$GO_VERSION" "$bootstrap_root"

case "$GO_ROOT" in
    "$PROJECT_DIR"/.tools/go/$GO_VERSION) ;;
    *) die "MANGOSSH_GO_ROOT must be $PROJECT_DIR/.tools/go/$GO_VERSION" ;;
esac
mkdir -p "$(dirname "$GO_ROOT")"
rm -rf -- "$GO_ROOT"
ln -s "$GO_SOURCE" "$GO_ROOT"
printf 'Source-built Go %s is available at %s\n' "$GO_VERSION" "$GO_ROOT"
