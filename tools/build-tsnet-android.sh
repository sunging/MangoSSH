#!/usr/bin/env bash
# Builds the pinned outbound-only tsnet gomobile bridge for all Android ABIs.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=tools/lib/linux-host.sh
source "$PROJECT_DIR/tools/lib/linux-host.sh"
mangossh_require_linux_x86_64
mangossh_require_commands \
    bash chmod cp find flock git grep install mkdir rm python3 sha256sum
BRIDGE_DIR="$PROJECT_DIR/native/tsnetbridge"
TOOLS_DIR="$PROJECT_DIR/.tools"
GO_VERSION="1.26.5"
JDK_VERSION="17.0.19+10"
TAILSCALE_VERSION="v1.102.2"
TAILSCALE_TSNET_GO_SHA256="6a8d6cc7deae3006729ef688ed5d33770284e04699f2dd040bc52c08de667ca5"
TAILSCALE_SOCKS5_GO_SHA256="e2fa5c1aca0cc1ca63417c8515acaaa800d13862fde48bfa4a576d844307d6f4"
TAILSCALE_TSNET_PATCHED_SHA256="5e432071e90d527f105fe984c9aa4e81fa5e8b119b3cad76541628cc929abfae"
TAILSCALE_SOCKS5_PATCHED_SHA256="68c1b5eb44a76210f120931a83ab259b0d539f84c9b33452cd8023d6b34ea95f"
GOMOBILE_VERSION="v0.0.0-20260709172247-6129f5bee9d5"
NDK_REVISION="27.3.13750724"
STRICT_OFFLINE="${MANGOSSH_OFFLINE_BUILD:-0}"
GO_ROOT="${MANGOSSH_GO_ROOT:-${GOROOT:-$TOOLS_DIR/go/$GO_VERSION}}"
GOBIN="${MANGOSSH_GOBIN:-$TOOLS_DIR/go-bin/$GO_VERSION}"
WORK_DIR="/tmp/mangossh-tsnetbridge-v1.102.2"
WORK_LOCK="/tmp/mangossh-tsnetbridge-v1.102.2.lock"
OUTPUT_DIR="$PROJECT_DIR/app/build/generated/tsnet"
OUTPUT_AAR="$OUTPUT_DIR/mangossh-tsnet.aar"
PATCH_FILE="$PROJECT_DIR/tools/patches/tailscale-v1.102.2-tsnet-no-logtail.patch"
VENDOR_DIR="$BRIDGE_DIR/vendor"

ANDROID_SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
[[ -d "$ANDROID_SDK_DIR" ]] || {
    printf 'ANDROID_SDK_ROOT or ANDROID_HOME must point to the Android SDK.\n' >&2
    exit 1
}
export ANDROID_HOME="$ANDROID_SDK_DIR"
export ANDROID_SDK_ROOT="$ANDROID_SDK_DIR"

if [[ ! -x "$GO_ROOT/bin/go" ]]; then
    if [[ "$STRICT_OFFLINE" == "1" ]]; then
        printf 'MANGOSSH_GO_ROOT or GOROOT must provide Go %s in offline mode.\n' "$GO_VERSION" >&2
        exit 1
    fi
    bash "$PROJECT_DIR/tools/fetch-go.sh"
    GO_ROOT="$TOOLS_DIR/go/$GO_VERSION"
fi
[[ "$("$GO_ROOT/bin/go" version)" == "go version go${GO_VERSION} linux/amd64" ]] || {
    printf 'Go %s for linux/amd64 is required at %s.\n' "$GO_VERSION" "$GO_ROOT" >&2
    exit 1
}
[[ -f "$VENDOR_DIR/modules.txt" ]] || {
    printf 'Vendored Go sources are required at %s.\n' "$VENDOR_DIR" >&2
    exit 1
}
grep -Fqx "# golang.org/x/mobile $GOMOBILE_VERSION" "$VENDOR_DIR/modules.txt" || {
    printf 'Vendored gomobile source does not match %s.\n' "$GOMOBILE_VERSION" >&2
    exit 1
}
grep -Fqx "# tailscale.com $TAILSCALE_VERSION" "$VENDOR_DIR/modules.txt" || {
    printf 'Vendored Tailscale source does not match %s.\n' "$TAILSCALE_VERSION" >&2
    exit 1
}
export PATH="$GO_ROOT/bin:$GOBIN:$PATH"
export GOBIN
export GOWORK=off
export GOTOOLCHAIN=local
export GOFLAGS="-mod=vendor -trimpath"
export CGO_ENABLED=1
export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-315532800}"

if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/javac" ]]; then
    if [[ "$STRICT_OFFLINE" == "1" ]]; then
        printf 'JAVA_HOME must provide JDK 17 in offline mode.\n' >&2
        exit 1
    fi
    JDK_CACHE_ROOT="${MANGOSSH_JDK_CACHE_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/mangossh}"
    JAVA_HOME="$JDK_CACHE_ROOT/jdk/$JDK_VERSION"
    if [[ ! -x "$JAVA_HOME/bin/javac" ]]; then
        JDK_TOOLS_DIR="$JDK_CACHE_ROOT" \
            JDK_ARCHIVE_PATH="$TOOLS_DIR/OpenJDK17U-jdk_x64_linux_hotspot_17.0.19_10.tar.gz" \
            bash "$PROJECT_DIR/tools/fetch-jdk17.sh"
    fi
fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

if [[ -n "${ANDROID_NDK_HOME:-}" ]] && [[ -x "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" ]]; then
    NDK_HOME="$ANDROID_NDK_HOME"
else
    if [[ "$STRICT_OFFLINE" == "1" ]]; then
        printf 'ANDROID_NDK_HOME must provide Android NDK r27d in offline mode.\n' >&2
        exit 1
    fi
    # Keep the large archive in the ignored workspace cache while extracting
    # the toolchain into the conventional per-user cache location.
    NDK_CACHE_ROOT="${MANGOSSH_NDK_CACHE_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/mangossh}"
    NDK_HOME="$NDK_CACHE_ROOT/android-ndk-linux/$NDK_REVISION"
    if [[ ! -x "$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" ]]; then
        TOOLS_DIR="$NDK_CACHE_ROOT" \
            NDK_ARCHIVE_PATH="$TOOLS_DIR/android-ndk-r27d-linux.zip" \
            bash "$PROJECT_DIR/tools/fetch-android-ndk.sh"
    fi
fi
grep -q '^Pkg.Revision = 27\.3\.13750724$' "$NDK_HOME/source.properties" || {
    printf 'Android NDK revision %s (r27d) is required at %s.\n' "$NDK_REVISION" "$NDK_HOME" >&2
    exit 1
}
export ANDROID_NDK_HOME="$NDK_HOME"

mkdir -p "$GOBIN" "$OUTPUT_DIR"
# Go's tool directive makes the pinned command packages available from vendor
# without treating them as ordinary module imports. Resolve and install those
# exact binaries instead of invoking a network-capable `go install` command.
pushd "$BRIDGE_DIR" >/dev/null
gomobile_tool="$(go tool -n gomobile)"
gobind_tool="$(go tool -n gobind)"
[[ -e "$GOBIN/gomobile" && "$gomobile_tool" -ef "$GOBIN/gomobile" ]] ||
    install -m 0755 "$gomobile_tool" "$GOBIN/gomobile"
[[ -e "$GOBIN/gobind" && "$gobind_tool" -ef "$GOBIN/gobind" ]] ||
    install -m 0755 "$gobind_tool" "$GOBIN/gobind"
popd >/dev/null

case "$WORK_DIR" in
    /tmp/mangossh-tsnetbridge-v1.102.2) ;;
    *) printf 'Unsafe tsnet work path: %s\n' "$WORK_DIR" >&2; exit 1 ;;
esac
exec 9>"$WORK_LOCK"
flock --wait 1800 9 || {
    printf 'Timed out waiting for the tsnet build lock.\n' >&2
    exit 1
}
rm -rf -- "$WORK_DIR"
GOPATH_ROOT="$WORK_DIR/gopath"
BRIDGE_WORK_DIR="$GOPATH_ROOT/src/website.sung.mangossh/tsnetbridge"
mkdir -p "$BRIDGE_WORK_DIR"
cp "$BRIDGE_DIR"/*.go "$BRIDGE_DIR/go.mod" "$BRIDGE_DIR/go.sum" "$BRIDGE_WORK_DIR/"
cp -a "$VENDOR_DIR" "$BRIDGE_WORK_DIR/vendor"
find "$BRIDGE_WORK_DIR/vendor" -type d -exec chmod 0755 {} +
find "$BRIDGE_WORK_DIR/vendor" -type f -exec chmod 0644 {} +

pushd "$BRIDGE_WORK_DIR" >/dev/null
TAILSCALE_MODULE_DIR="$BRIDGE_WORK_DIR/vendor/tailscale.com"
case "$TAILSCALE_MODULE_DIR" in
    /tmp/mangossh-tsnetbridge-v1.102.2/gopath/src/website.sung.mangossh/tsnetbridge/vendor/tailscale.com) ;;
    *) printf 'Unsafe Tailscale module path: %s\n' "$TAILSCALE_MODULE_DIR" >&2; exit 1 ;;
esac
[[ -d "$TAILSCALE_MODULE_DIR" ]] || {
    printf 'Unable to locate vendored Tailscale module.\n' >&2
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
go list -deps -json ./... > "$WORK_DIR/modules.json"
python3 "$PROJECT_DIR/tools/generate-tsnet-notices.py" \
    "$WORK_DIR/modules.json" \
    "$WORK_DIR/tsnet-third-party-notices.txt" \
    "$TAILSCALE_VERSION" \
    "$BRIDGE_WORK_DIR/vendor"

# gomobile creates a temporary module and runs `go mod tidy` for each target
# when invoked from module mode. GOPATH mode is deliberately used for the bind
# step so every import resolves through the package-local vendor tree without
# network access or a generated module cache.
cp -a "$BRIDGE_WORK_DIR/vendor/." "$GOPATH_ROOT/src/"
export GO111MODULE=off
export GOPATH="$GOPATH_ROOT"
export GOFLAGS="-trimpath"
go test ./...

UNSTRIPPED_AAR="$WORK_DIR/mangossh-tsnet-unstripped.aar"
"$GOBIN/gomobile" bind \
    -target android \
    -androidapi 26 \
    -trimpath \
    -tags "ts_omit_cachenetmap,ts_omit_netlog" \
    -ldflags "-linkmode=external -extldflags=-Wl,-z,max-page-size=16384,-z,common-page-size=16384 -buildid=" \
    -o "$UNSTRIPPED_AAR" .
popd >/dev/null

python3 "$PROJECT_DIR/tools/normalize-tsnet-aar.py" \
    "$UNSTRIPPED_AAR" \
    "$OUTPUT_AAR" \
    "$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" \
    "$TAILSCALE_MODULE_DIR/LICENSE" \
    "$WORK_DIR/tsnet-third-party-notices.txt"
printf 'Built %s\n' "$OUTPUT_AAR"
