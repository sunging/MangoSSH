#!/usr/bin/env bash
# Fetches the checksum-pinned Linux JDK used by the WSL gomobile build.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
JDK_VERSION="17.0.19+10"
JDK_ARCHIVE="OpenJDK17U-jdk_x64_linux_hotspot_17.0.19_10.tar.gz"
JDK_URL="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.19%2B10/$JDK_ARCHIVE"
JDK_SHA256="d8afc263758141a66e0e3aafc321e783f7016696f4eaea067d340a269037d331"
TOOLS_DIR="${JDK_TOOLS_DIR:-$PROJECT_DIR/.tools}"
ARCHIVE_PATH="${JDK_ARCHIVE_PATH:-$TOOLS_DIR/$JDK_ARCHIVE}"
TARGET_DIR="$TOOLS_DIR/jdk/$JDK_VERSION"
PARTIAL_PATH="$ARCHIVE_PATH.partial"

if [[ -x "$TARGET_DIR/bin/javac" ]]; then
    printf '%s\n' "$TARGET_DIR"
    exit 0
fi

mkdir -p "$(dirname "$ARCHIVE_PATH")" "$(dirname "$TARGET_DIR")"
if [[ -f "$ARCHIVE_PATH" ]] &&
    [[ "$(sha256sum "$ARCHIVE_PATH" | cut -d ' ' -f 1)" != "$JDK_SHA256" ]]; then
    mv "$ARCHIVE_PATH" "$PARTIAL_PATH"
fi
if [[ ! -f "$ARCHIVE_PATH" ]]; then
    curl --continue-at - --fail --location --retry 3 \
        --output "$PARTIAL_PATH" "$JDK_URL"
    [[ "$(sha256sum "$PARTIAL_PATH" | cut -d ' ' -f 1)" == "$JDK_SHA256" ]] || {
        printf 'JDK archive checksum mismatch.\n' >&2
        exit 1
    }
    mv "$PARTIAL_PATH" "$ARCHIVE_PATH"
fi

EXTRACT_DIR="$(dirname "$TARGET_DIR")/jdk-17.0.19+10"
rm -rf -- "$TARGET_DIR" "$EXTRACT_DIR"
tar -xzf "$ARCHIVE_PATH" -C "$(dirname "$TARGET_DIR")"
[[ -x "$EXTRACT_DIR/bin/javac" ]] || {
    printf 'JDK archive did not contain the expected javac.\n' >&2
    exit 1
}
if [[ "$EXTRACT_DIR" != "$TARGET_DIR" ]]; then
    mv "$EXTRACT_DIR" "$TARGET_DIR"
fi
printf '%s\n' "$TARGET_DIR"
