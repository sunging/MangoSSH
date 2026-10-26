#!/usr/bin/env bash
# Shared contract for the preinstalled, pinned Linux Go toolchain.
MANGOSSH_GO_VERSION="1.26.10"
MANGOSSH_GO_SHA256="ccfe7ae822d2c2f41f4fe7c956199967a4dac22d0ee0d2b2bfdb440aab3b20c5"

## Validates without downloading or allowing Go's automatic toolchain selection.
mangossh_require_go() {
    local go_root="$1"
    local actual
    actual="$(GOTOOLCHAIN=local "$go_root/bin/go" version 2>/dev/null)" || actual=""
    if [[ "$actual" != "go version go${MANGOSSH_GO_VERSION} linux/amd64" ]]; then
        printf 'Go %s for linux/amd64 must be preinstalled at %s.\n' \
            "$MANGOSSH_GO_VERSION" "$go_root" >&2
        return 1
    fi
}
