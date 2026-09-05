#!/usr/bin/env bash
# Runs bridge tests against the same patched Tailscale source used by gomobile.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=tools/lib/linux-host.sh
source "$PROJECT_DIR/tools/lib/linux-host.sh"
mangossh_require_linux_x86_64
mangossh_require_commands bash chmod cp flock git mkdir rm sha256sum
BRIDGE_DIR="$PROJECT_DIR/native/tsnetbridge"
GO_ROOT="$PROJECT_DIR/.tools/go/1.26.7"
TAILSCALE_VERSION="v1.102.3"
TAILSCALE_TSNET_GO_SHA256="6a8d6cc7deae3006729ef688ed5d33770284e04699f2dd040bc52c08de667ca5"
TAILSCALE_SOCKS5_GO_SHA256="e2fa5c1aca0cc1ca63417c8515acaaa800d13862fde48bfa4a576d844307d6f4"
TAILSCALE_TSNET_PATCHED_SHA256="5e432071e90d527f105fe984c9aa4e81fa5e8b119b3cad76541628cc929abfae"
TAILSCALE_SOCKS5_PATCHED_SHA256="68c1b5eb44a76210f120931a83ab259b0d539f84c9b33452cd8023d6b34ea95f"
WORK_DIR="/tmp/mangossh-tsnetbridge-test-v1.102.3"
WORK_LOCK="/tmp/mangossh-tsnetbridge-test-v1.102.3.lock"
PATCH_FILE="$PROJECT_DIR/tools/patches/tailscale-v1.102.3-tsnet-no-logtail.patch"

bash "$PROJECT_DIR/tools/fetch-go.sh"
export PATH="$GO_ROOT/bin:$PATH"
export GOWORK=off
GO_MODULE_CACHE_ROOT="${MANGOSSH_GO_TEST_MOD_CACHE_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/mangossh/go-mod/tsnetbridge-test}"
mkdir -p "$GO_MODULE_CACHE_ROOT"
export GOMODCACHE="$GO_MODULE_CACHE_ROOT"

case "$WORK_DIR" in
    /tmp/mangossh-tsnetbridge-test-v1.102.3) ;;
    *) printf 'Unsafe tsnet test path: %s\n' "$WORK_DIR" >&2; exit 1 ;;
esac
exec 9>"$WORK_LOCK"
flock --wait 1800 9 || {
    printf 'Timed out waiting for the tsnet test lock.\n' >&2
    exit 1
}
rm -rf -- "$WORK_DIR"
mkdir -p "$WORK_DIR/bridge"
cp "$BRIDGE_DIR"/*.go "$BRIDGE_DIR/go.mod" "$BRIDGE_DIR/go.sum" "$WORK_DIR/bridge/"

pushd "$WORK_DIR/bridge" >/dev/null
TAILSCALE_MODULE_DIR="$GOMODCACHE/tailscale.com@${TAILSCALE_VERSION}"
case "$TAILSCALE_MODULE_DIR" in
    "$GO_MODULE_CACHE_ROOT"/tailscale.com@v1.102.3) ;;
    *) printf 'Unsafe Tailscale test module path: %s\n' "$TAILSCALE_MODULE_DIR" >&2; exit 1 ;;
esac
rm -rf -- "$TAILSCALE_MODULE_DIR"
go mod download "tailscale.com@$TAILSCALE_VERSION"
[[ -d "$TAILSCALE_MODULE_DIR" ]] || {
    printf 'Unable to locate downloaded Tailscale test module.\n' >&2
    exit 1
}
printf '%s  %s\n%s  %s\n' \
    "$TAILSCALE_TSNET_GO_SHA256" \
    "$TAILSCALE_MODULE_DIR/tsnet/tsnet.go" \
    "$TAILSCALE_SOCKS5_GO_SHA256" \
    "$TAILSCALE_MODULE_DIR/net/socks5/socks5.go" |
    sha256sum --check --status -
chmod -R u+w "$TAILSCALE_MODULE_DIR"
git -C "$TAILSCALE_MODULE_DIR" apply --check "$PATCH_FILE"
git -C "$TAILSCALE_MODULE_DIR" apply "$PATCH_FILE"
printf '%s  %s\n%s  %s\n' \
    "$TAILSCALE_TSNET_PATCHED_SHA256" \
    "$TAILSCALE_MODULE_DIR/tsnet/tsnet.go" \
    "$TAILSCALE_SOCKS5_PATCHED_SHA256" \
    "$TAILSCALE_MODULE_DIR/net/socks5/socks5.go" |
    sha256sum --check --status -
go mod tidy
go test ./...
popd >/dev/null
