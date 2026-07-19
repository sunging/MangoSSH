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
# `curl --continue-at -` resumes interrupted transfers, while this validity
# check avoids asking the server for a range beyond an already complete archive.
if ! unzip -tqq "$ARCHIVE_PATH" >/dev/null 2>&1; then
    curl --continue-at - --fail --location --retry 3 --output "$ARCHIVE_PATH" "$NDK_URL"
fi
rm -rf "$TARGET_DIR" "$EXTRACTED_DIR"
unzip -q "$ARCHIVE_PATH" -d "$(dirname "$TARGET_DIR")"
mv "$EXTRACTED_DIR" "$TARGET_DIR"
printf '%s\n' "$TARGET_DIR"
