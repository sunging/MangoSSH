#!/usr/bin/env bash
# Downloads the pinned stock Go toolchain used by the Android tsnet bridge.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=tools/lib/linux-host.sh
source "$PROJECT_DIR/tools/lib/linux-host.sh"
mangossh_require_linux_x86_64
mangossh_require_commands curl mkdir mv rm sha256sum tar
GO_VERSION="1.26.7"
GO_ARCHIVE="go${GO_VERSION}.linux-amd64.tar.gz"
GO_SHA256="ffb5f8de10c62550dfddab66b36b57030721e0a44a3218e9e1181d7b59f121ca"
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
