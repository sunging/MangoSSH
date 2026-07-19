#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Checks every native library packaged in an APK for 16 KiB PT_LOAD segment
# alignment. It intentionally uses the host readelf so it can run in Ubuntu
# WSL without requiring an Android device or Gradle task.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
APK_PATH="${1:-$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk}"
REQUIRED_ALIGNMENT=$((16 * 1024))

[[ -f "$APK_PATH" ]] || {
    echo "APK is missing: $APK_PATH" >&2
    exit 1
}
command -v readelf >/dev/null 2>&1 || {
    echo "readelf is required." >&2
    exit 1
}
command -v unzip >/dev/null 2>&1 || {
    echo "unzip is required." >&2
    exit 1
}

temp_dir="$(mktemp -d)"
trap 'rm -rf "$temp_dir"' EXIT
unzip -q "$APK_PATH" 'lib/*/*.so' -d "$temp_dir"

status=0
shopt -s nullglob
libraries=("$temp_dir"/lib/*/*.so)
if (( ${#libraries[@]} == 0 )); then
    echo "APK does not contain any native libraries to validate." >&2
    exit 1
fi

for library in "${libraries[@]}"; do
    relative_library="${library#"$temp_dir"/}"
    saw_load_segment=0
    while IFS= read -r alignment; do
        saw_load_segment=1
        alignment_value=$((alignment))
        if (( alignment_value < REQUIRED_ALIGNMENT )); then
            printf 'NOT_16KB %s PT_LOAD alignment=0x%x\n' "$relative_library" "$alignment_value" >&2
            status=1
        fi
    done < <(readelf -lW "$library" | awk '$1 == "LOAD" { print $NF }')
    if (( saw_load_segment == 0 )); then
        printf 'INVALID_ELF %s has no PT_LOAD segments.\n' "$relative_library" >&2
        status=1
    fi
done

if (( status == 0 )); then
    printf 'All APK native libraries use at least 16 KiB PT_LOAD alignment.\n'
fi
exit "$status"
