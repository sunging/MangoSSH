#!/usr/bin/env bash
# Test exactly the vendored source consumed by the offline Android producer.
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
source "$PROJECT_DIR/tools/lib/go-toolchain.sh"
source "$PROJECT_DIR/tools/lib/tsnet-version.sh"
GO_ROOT="${MANGOSSH_GO_ROOT:-$PROJECT_DIR/.tools/go/$MANGOSSH_GO_VERSION}"
mangossh_require_go "$GO_ROOT"
export PATH="$GO_ROOT/bin:$PATH"
export GOTOOLCHAIN=local GOWORK=off GOPROXY=off GOSUMDB=off
cd "$PROJECT_DIR/native/tsnetbridge"
go test -mod=vendor ./...
go test -mod=vendor -tags mangossh_versioncheck -ldflags "$TAILSCALE_LDFLAGS" ./...
