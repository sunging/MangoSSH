#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Fetches the Linux-host Android NDK used by the WSL Mosh build. The toolchain
# remains in .tools (which is ignored), so it neither modifies the Windows SDK
# nor becomes part of a source commit.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
NDK_REVISION="27.3.13750724"
NDK_ARCHIVE="android-ndk-r27d-linux.zip"
NDK_URL="https://dl.google.com/android/repository/$NDK_ARCHIVE"
NDK_SIZE="663956036"
NDK_SHA1="22105e410cf29afcf163760cc95522b9fb981121"
TOOLS_DIR="${TOOLS_DIR:-$PROJECT_DIR/.tools}"
# A caller can retain a large downloaded archive on the workspace volume while
# placing the extracted toolchain on WSL's faster Linux filesystem.
ARCHIVE_PATH="${NDK_ARCHIVE_PATH:-$TOOLS_DIR/$NDK_ARCHIVE}"
TARGET_DIR="$TOOLS_DIR/android-ndk-linux/$NDK_REVISION"
EXTRACTED_DIR="$(dirname "$TARGET_DIR")/android-ndk-r27d"

if [[ -x "$TARGET_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" ]]; then
    printf '%s\n' "$TARGET_DIR"
    exit 0
fi

mkdir -p "$TOOLS_DIR" "$(dirname "$TARGET_DIR")" "$(dirname "$ARCHIVE_PATH")"

archive_is_valid() {
    [[ -f "$1" ]] &&
        [[ "$(stat -c '%s' "$1")" == "$NDK_SIZE" ]] &&
        [[ "$(sha1sum "$1" | cut -d ' ' -f 1)" == "$NDK_SHA1" ]]
}

PARTIAL_PATH="$ARCHIVE_PATH.partial"
if ! archive_is_valid "$ARCHIVE_PATH"; then
    if [[ -f "$ARCHIVE_PATH" && ! -f "$PARTIAL_PATH" ]]; then
        mv "$ARCHIVE_PATH" "$PARTIAL_PATH"
    fi
    # Some HTTP retries can append a repeated final range. Preserve the valid
    # prefix when that happens, but accept it only after the pinned checksum
    # succeeds.
    if [[ -f "$PARTIAL_PATH" ]] &&
        (( "$(stat -c '%s' "$PARTIAL_PATH")" > NDK_SIZE )); then
        head -c "$NDK_SIZE" "$PARTIAL_PATH" > "$PARTIAL_PATH.trimmed"
        mv "$PARTIAL_PATH.trimmed" "$PARTIAL_PATH"
    fi
    if ! archive_is_valid "$PARTIAL_PATH"; then
        curl --continue-at - --fail --location --retry 3 \
            --output "$PARTIAL_PATH" "$NDK_URL"
    fi
    archive_is_valid "$PARTIAL_PATH" || {
        printf 'NDK archive checksum or size mismatch.\n' >&2
        exit 1
    }
    mv "$PARTIAL_PATH" "$ARCHIVE_PATH"
fi
rm -rf "$TARGET_DIR" "$EXTRACTED_DIR"
unzip -q "$ARCHIVE_PATH" -d "$(dirname "$TARGET_DIR")"
mv "$EXTRACTED_DIR" "$TARGET_DIR"
printf '%s\n' "$TARGET_DIR"
