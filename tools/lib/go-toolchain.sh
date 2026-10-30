#!/usr/bin/env bash
# Shared contract for the preinstalled, pinned Linux Go toolchain.
MANGOSSH_GO_VERSION="1.27.3"
MANGOSSH_GO_SHA256="65efbc80fdc1fa2cd2631cad5b481777a68e8725f34e89f50fc6ac714e379b40"

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
