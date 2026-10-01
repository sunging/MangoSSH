#!/usr/bin/env bash
# Builds the pinned outbound-only tsnet gomobile bridge for all Android ABIs.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=tools/lib/linux-host.sh
source "$PROJECT_DIR/tools/lib/linux-host.sh"
mangossh_require_linux_x86_64
mangossh_require_commands \
    bash cat chmod cp find flock git grep install mkdir rm patch python3 sha256sum
BRIDGE_DIR="$PROJECT_DIR/native/tsnetbridge"
TOOLS_DIR="$PROJECT_DIR/.tools"
# shellcheck source=tools/lib/go-toolchain.sh
source "$PROJECT_DIR/tools/lib/go-toolchain.sh"
GO_VERSION="$MANGOSSH_GO_VERSION"
# shellcheck source=tools/lib/tsnet-version.sh
source "$PROJECT_DIR/tools/lib/tsnet-version.sh"
TAILSCALE_TSNET_GO_SHA256="6a8d6cc7deae3006729ef688ed5d33770284e04699f2dd040bc52c08de667ca5"
TAILSCALE_SOCKS5_GO_SHA256="e2fa5c1aca0cc1ca63417c8515acaaa800d13862fde48bfa4a576d844307d6f4"
TAILSCALE_TSNET_PATCHED_SHA256="5e432071e90d527f105fe984c9aa4e81fa5e8b119b3cad76541628cc929abfae"
TAILSCALE_SOCKS5_PATCHED_SHA256="68c1b5eb44a76210f120931a83ab259b0d539f84c9b33452cd8023d6b34ea95f"
GOMOBILE_VERSION="v0.0.0-20260709172247-6129f5bee9d5"
NDK_REVISION="27.3.13750724"
GO_ROOT="${MANGOSSH_GO_ROOT:-${GOROOT:-$TOOLS_DIR/go/$GO_VERSION}}"
GOBIN="${MANGOSSH_GOBIN:-$TOOLS_DIR/go-bin/$GO_VERSION}"
WORK_DIR="${MANGOSSH_TSNET_WORK_DIR:-$PROJECT_DIR/build/native/tsnet-standalone}"
WORK_LOCK="$WORK_DIR.lock"
OUTPUT_DIR="${MANGOSSH_TSNET_OUTPUT_DIR:-$PROJECT_DIR/app/build/generated/tsnet}"
OUTPUT_AAR="$OUTPUT_DIR/mangossh-tsnet.aar"
PATCH_FILE="$PROJECT_DIR/tools/patches/tailscale-v1.102.4-tsnet-no-logtail.patch"
VENDOR_DIR="$BRIDGE_DIR/vendor"

ANDROID_SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -d "$ANDROID_SDK_DIR" ]] || {
    printf 'ANDROID_SDK_ROOT or ANDROID_HOME must point to the Android SDK.\n' >&2
    exit 1
}
export ANDROID_HOME="$ANDROID_SDK_DIR"
export ANDROID_SDK_ROOT="$ANDROID_SDK_DIR"

mangossh_require_go "$GO_ROOT"
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
[[ "$(cat "$VENDOR_DIR/tailscale.com/VERSION.txt")" == "${TAILSCALE_VERSION#v}" ]] || {
    printf 'Vendored Tailscale version does not match linker stamps.\n' >&2
    exit 1
}
export PATH="$GO_ROOT/bin:$GOBIN:$PATH"
export GOBIN
export GOWORK=off
export GOTOOLCHAIN=local
export GOPROXY=off GOSUMDB=off
export GOFLAGS="-mod=vendor -trimpath"
export CGO_ENABLED=1
export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-315532800}"

[[ -x "${JAVA_HOME:-}/bin/javac" ]] || {
    echo 'JAVA_HOME must provide a prepared JDK 17.' >&2; exit 1;
}
"$JAVA_HOME/bin/javac" -version 2>&1 | grep -q '^javac 17\.' || {
    echo 'JDK 17 is required.' >&2; exit 1;
}
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
NDK_HOME="${ANDROID_NDK_HOME:-$TOOLS_DIR/android-ndk-linux/$NDK_REVISION}"
[[ -x "$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" ]] || {
    echo 'Prepare Android NDK r27d before compilation.' >&2; exit 1;
}
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

# tools/native/build.py passes its validated state directory, which may be
# outside the checkout (for example on the WSL file system for Windows builds).
STATE_WORK_DIR=""
[[ "${MANGOSSH_NATIVE_STATE:-}" != /* ]] || STATE_WORK_DIR="${MANGOSSH_NATIVE_STATE%/}/work/tsnet/"
case "$WORK_DIR" in
    "$PROJECT_DIR"/build/native/*|/tmp/mangossh-*) ;;
    *)
        [[ -n "$STATE_WORK_DIR" && "$WORK_DIR" == "$STATE_WORK_DIR"?* && "$WORK_DIR" != *..* ]] || {
            printf 'Unsafe tsnet work path: %s\n' "$WORK_DIR" >&2
            exit 1
        }
        ;;
esac
mkdir -p "$(dirname "$WORK_LOCK")"
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
[[ -d "$TAILSCALE_MODULE_DIR" ]] || {
    printf 'Unable to locate vendored Tailscale module.\n' >&2
    exit 1
}
printf '%s  %s\n%s  %s\n' \
    "$TAILSCALE_TSNET_PATCHED_SHA256" "$TAILSCALE_MODULE_DIR/tsnet/tsnet.go" \
    "$TAILSCALE_SOCKS5_PATCHED_SHA256" "$TAILSCALE_MODULE_DIR/net/socks5/socks5.go" |
    sha256sum --check --status -
patch --batch --fuzz=0 --dry-run --reverse -p1 -d "$TAILSCALE_MODULE_DIR" -i "$PATCH_FILE"
patch --batch --fuzz=0 --reverse -p1 -d "$TAILSCALE_MODULE_DIR" -i "$PATCH_FILE"
printf '%s  %s\n%s  %s\n' \
    "$TAILSCALE_TSNET_GO_SHA256" \
    "$TAILSCALE_MODULE_DIR/tsnet/tsnet.go" \
    "$TAILSCALE_SOCKS5_GO_SHA256" \
    "$TAILSCALE_MODULE_DIR/net/socks5/socks5.go" |
    sha256sum --check --status -
chmod -R u+w "$TAILSCALE_MODULE_DIR"
patch --batch --fuzz=0 --dry-run --forward -p1 -d "$TAILSCALE_MODULE_DIR" -i "$PATCH_FILE"
patch --batch --fuzz=0 --forward -p1 -d "$TAILSCALE_MODULE_DIR" -i "$PATCH_FILE"
printf '%s  %s\n%s  %s\n' \
    "$TAILSCALE_TSNET_PATCHED_SHA256" \
    "$TAILSCALE_MODULE_DIR/tsnet/tsnet.go" \
    "$TAILSCALE_SOCKS5_PATCHED_SHA256" \
    "$TAILSCALE_MODULE_DIR/net/socks5/socks5.go" |
    sha256sum --check --status -
# Full bridge tests are a separate verification task; production compilation
# still validates the linker version stamp in the GOPATH layout below.
go list -deps -json ./... > "$WORK_DIR/modules.json"
python3 "$PROJECT_DIR/tools/generate-tsnet-notices.py" \
    "$WORK_DIR/modules.json" \
    "$WORK_DIR/tsnet-third-party-notices.txt" \
    "$TAILSCALE_VERSION" \
    "$BRIDGE_WORK_DIR/vendor"

# gomobile creates a temporary module and runs `go mod tidy` for each target
# when invoked from module mode. GOPATH mode is deliberately used for the bind
# step so every import resolves through the copied, audited source tree without
# network access or a generated module cache.
cp -a "$BRIDGE_WORK_DIR/vendor/." "$GOPATH_ROOT/src/"
# Resolve each dependency at its canonical import path. Keeping a package-local
# vendor tree in GOPATH mode gives version a vendor-prefixed linker symbol,
# causing the upstream -X version stamps to be silently ignored.
rm -rf -- "$BRIDGE_WORK_DIR/vendor"
TAILSCALE_MODULE_DIR="$GOPATH_ROOT/src/tailscale.com"
export GO111MODULE=off
export GOPATH="$GOPATH_ROOT"
export GOFLAGS="-trimpath"
go test -run '^TestStampedVersion$' -tags mangossh_versioncheck -ldflags "$TAILSCALE_LDFLAGS" ./...

UNSTRIPPED_AAR="$WORK_DIR/mangossh-tsnet-unstripped.aar"
ABIS="${ABIS:-arm64-v8a armeabi-v7a x86 x86_64}"
targets=()
for abi in $ABIS; do
    case "$abi" in
        arm64-v8a) targets+=(android/arm64) ;;
        armeabi-v7a) targets+=(android/arm) ;;
        x86) targets+=(android/386) ;;
        x86_64) targets+=(android/amd64) ;;
        *) echo "Unsupported ABI: $abi" >&2; exit 1 ;;
    esac
done
target_csv="$(IFS=,; echo "${targets[*]}")"
"$GOBIN/gomobile" bind \
    -target "$target_csv" \
    -androidapi "${MANGOSSH_ANDROID_API:-26}" \
    -trimpath \
    -tags "ts_omit_cachenetmap,ts_omit_netlog" \
    -ldflags "$TAILSCALE_LDFLAGS -linkmode=external -extldflags=-Wl,-z,max-page-size=16384,-z,common-page-size=16384 -buildid=" \
    -o "$UNSTRIPPED_AAR" .
popd >/dev/null

python3 "$PROJECT_DIR/tools/normalize-tsnet-aar.py" \
    "$UNSTRIPPED_AAR" \
    "$OUTPUT_AAR" \
    "$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" \
    "$TAILSCALE_MODULE_DIR/LICENSE" \
    "$WORK_DIR/tsnet-third-party-notices.txt" \
    "$ABIS"
mkdir -p "$OUTPUT_DIR/symbols"
cp "$UNSTRIPPED_AAR" "$OUTPUT_DIR/symbols/mangossh-tsnet.aar"
printf 'Built %s\n' "$OUTPUT_AAR"
