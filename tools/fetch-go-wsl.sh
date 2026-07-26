#!/usr/bin/env bash
# Downloads the pinned stock Go toolchain used by the Android tsnet bridge.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GO_VERSION="1.26.5"
GO_ARCHIVE="go${GO_VERSION}.linux-amd64.tar.gz"
GO_SHA256="5c2c3b16caefa1d968a94c1daca04a7ca301a496d9b086e17ad77bb81393f053"
DOWNLOAD_DIR="$PROJECT_DIR/.tools/downloads"
GO_ROOT="$PROJECT_DIR/.tools/go/$GO_VERSION"
ARCHIVE_PATH="$DOWNLOAD_DIR/$GO_ARCHIVE"

if [[ -x "$GO_ROOT/bin/go" ]] && [[ "$("$GO_ROOT/bin/go" version)" == "go version go${GO_VERSION} linux/amd64" ]]; then
    printf 'Go %s is ready at %s\n' "$GO_VERSION" "$GO_ROOT"
    exit 0
fi

mkdir -p "$DOWNLOAD_DIR" "$(dirname "$GO_ROOT")"
if [[ ! -f "$ARCHIVE_PATH" ]]; then
    curl --continue-at - --fail --location --retry 3 \
        --output "$ARCHIVE_PATH" "https://go.dev/dl/$GO_ARCHIVE"
fi
printf '%s  %s\n' "$GO_SHA256" "$ARCHIVE_PATH" | sha256sum --check -

STAGING="$PROJECT_DIR/.tools/go/.go-${GO_VERSION}.staging"
case "$STAGING" in
    "$PROJECT_DIR"/.tools/go/*) ;;
    *) printf 'Unsafe Go staging path: %s\n' "$STAGING" >&2; exit 1 ;;
esac
rm -rf -- "$STAGING"
mkdir -p "$STAGING"
tar -xzf "$ARCHIVE_PATH" -C "$STAGING"
rm -rf -- "$GO_ROOT"
mv "$STAGING/go" "$GO_ROOT"
rm -rf -- "$STAGING"
"$GO_ROOT/bin/go" version
