#!/usr/bin/env bash
# Builds the pinned outbound-only tsnet gomobile bridge for all Android ABIs.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BRIDGE_DIR="$PROJECT_DIR/native/tsnetbridge"
TOOLS_DIR="$PROJECT_DIR/.tools"
GO_VERSION="1.26.5"
JDK_VERSION="17.0.19+10"
TAILSCALE_VERSION="v1.98.8"
TAILSCALE_TSNET_GO_SHA256="322062bfafaf38d5e5b258faae61d355d06fe4157cb8c34c22f35cbb22924867"
TAILSCALE_SOCKS5_GO_SHA256="e2fa5c1aca0cc1ca63417c8515acaaa800d13862fde48bfa4a576d844307d6f4"
TAILSCALE_TSNET_PATCHED_SHA256="d2ce46080b1c772010ee74b005696371cb37c1b91eb22ff5966449e63be94eed"
TAILSCALE_SOCKS5_PATCHED_SHA256="68c1b5eb44a76210f120931a83ab259b0d539f84c9b33452cd8023d6b34ea95f"
GOMOBILE_VERSION="v0.0.0-20260709172247-6129f5bee9d5"
NDK_REVISION="27.3.13750724"
GO_ROOT="$TOOLS_DIR/go/$GO_VERSION"
GOBIN="$TOOLS_DIR/go-bin/$GO_VERSION"
WORK_DIR="/tmp/mangossh-tsnetbridge-v1.98.8"
WORK_LOCK="/tmp/mangossh-tsnetbridge-v1.98.8.lock"
OUTPUT_DIR="$PROJECT_DIR/app/build/generated/tsnet"
OUTPUT_AAR="$OUTPUT_DIR/mangossh-tsnet.aar"
PATCH_FILE="$PROJECT_DIR/tools/patches/tailscale-v1.98.8-tsnet-no-logtail.patch"

bash "$PROJECT_DIR/tools/fetch-go-wsl.sh"
export PATH="$GO_ROOT/bin:$GOBIN:$PATH"
export GOBIN
export GOWORK=off
export CGO_ENABLED=1
export SOURCE_DATE_EPOCH=0
GO_MODULE_CACHE_ROOT="${MANGOSSH_GO_MOD_CACHE_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/mangossh/go-mod/tsnetbridge}"
mkdir -p "$GO_MODULE_CACHE_ROOT"
export GOMODCACHE="$GO_MODULE_CACHE_ROOT"

if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/javac" ]]; then
    JDK_CACHE_ROOT="${MANGOSSH_JDK_CACHE_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/mangossh}"
    JAVA_HOME="$JDK_CACHE_ROOT/jdk/$JDK_VERSION"
    if [[ ! -x "$JAVA_HOME/bin/javac" ]]; then
        JDK_TOOLS_DIR="$JDK_CACHE_ROOT" \
            JDK_ARCHIVE_PATH="$TOOLS_DIR/OpenJDK17U-jdk_x64_linux_hotspot_17.0.19_10.tar.gz" \
            bash "$PROJECT_DIR/tools/fetch-jdk17-wsl.sh"
    fi
fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

if [[ -n "${ANDROID_NDK_HOME:-}" ]] && [[ -x "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" ]]; then
    NDK_HOME="$ANDROID_NDK_HOME"
else
    # The NDK archive contains case-distinct Linux headers that cannot coexist
    # on the default case-insensitive Windows filesystem. Keep the large
    # archive in the ignored workspace cache, but extract into WSL's native
    # filesystem. CI also benefits from the conventional ~/.cache location.
    NDK_CACHE_ROOT="${MANGOSSH_NDK_CACHE_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/mangossh}"
    NDK_HOME="$NDK_CACHE_ROOT/android-ndk-linux/$NDK_REVISION"
    if [[ ! -x "$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" ]]; then
        TOOLS_DIR="$NDK_CACHE_ROOT" \
            NDK_ARCHIVE_PATH="$TOOLS_DIR/android-ndk-r27d-linux.zip" \
            bash "$PROJECT_DIR/tools/fetch-android-ndk-wsl.sh"
    fi
fi
export ANDROID_NDK_HOME="$NDK_HOME"

if [[ -z "${ANDROID_HOME:-}" && -z "${ANDROID_SDK_ROOT:-}" ]]; then
    DEFAULT_ANDROID_HOME="/mnt/d/code/android/sdk"
    [[ -d "$DEFAULT_ANDROID_HOME" ]] || {
        printf 'ANDROID_HOME or ANDROID_SDK_ROOT must point to the Android SDK.\n' >&2
        exit 1
    }
    export ANDROID_HOME="$DEFAULT_ANDROID_HOME"
fi

mkdir -p "$GOBIN" "$OUTPUT_DIR"
if [[ ! -x "$GOBIN/gomobile" ]]; then
    go install "golang.org/x/mobile/cmd/gomobile@$GOMOBILE_VERSION"
fi
if [[ ! -x "$GOBIN/gobind" ]]; then
    go install "golang.org/x/mobile/cmd/gobind@$GOMOBILE_VERSION"
fi

case "$WORK_DIR" in
    /tmp/mangossh-tsnetbridge-v1.98.8) ;;
    *) printf 'Unsafe tsnet work path: %s\n' "$WORK_DIR" >&2; exit 1 ;;
esac
exec 9>"$WORK_LOCK"
flock --wait 1800 9 || {
    printf 'Timed out waiting for the tsnet build lock.\n' >&2
    exit 1
}
rm -rf -- "$WORK_DIR"
mkdir -p "$WORK_DIR/bridge"
cp "$BRIDGE_DIR"/*.go "$BRIDGE_DIR/go.mod" "$BRIDGE_DIR/go.sum" "$WORK_DIR/bridge/"

pushd "$WORK_DIR/bridge" >/dev/null
TAILSCALE_MODULE_DIR="$GOMODCACHE/tailscale.com@${TAILSCALE_VERSION}"
case "$TAILSCALE_MODULE_DIR" in
    "$GO_MODULE_CACHE_ROOT"/tailscale.com@v1.98.8) ;;
    *) printf 'Unsafe Tailscale module path: %s\n' "$TAILSCALE_MODULE_DIR" >&2; exit 1 ;;
esac
rm -rf -- "$TAILSCALE_MODULE_DIR"
go mod download "tailscale.com@$TAILSCALE_VERSION"
[[ -d "$TAILSCALE_MODULE_DIR" ]] || {
    printf 'Unable to locate downloaded Tailscale module.\n' >&2
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
go get -tool "golang.org/x/mobile/cmd/gobind@$GOMOBILE_VERSION"
go mod tidy
go list -deps -json ./... > "$WORK_DIR/modules.json"
python3 "$PROJECT_DIR/tools/generate-tsnet-notices.py" \
    "$WORK_DIR/modules.json" \
    "$WORK_DIR/tsnet-third-party-notices.txt"
go test ./...

UNSTRIPPED_AAR="$WORK_DIR/mangossh-tsnet-unstripped.aar"
gomobile bind \
    -target android \
    -androidapi 26 \
    -trimpath \
    -tags "ts_omit_cachenetmap" \
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
