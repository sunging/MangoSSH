#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Builds offline Mosh ABI archives in isolated workers. Source and build trees
# are never shared between workers because several upstream configure steps
# modify their input trees.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
OUTPUT_DIR="${WORK_DIR:-$PROJECT_DIR/.tools/mosh-android-build}"
PARALLELISM="${MANGOSSH_ABI_PARALLELISM:-2}"
BUILD_JOBS="${MANGOSSH_ABI_BUILD_JOBS:-2}"
ABIS="${ABIS:-arm64-v8a armeabi-v7a x86 x86_64}"

die() {
    printf 'error: %s\n' "$*" >&2
    exit 1
}

[[ "${MANGOSSH_OFFLINE_BUILD:-0}" == "1" ]] ||
    die "parallel Mosh builds require MANGOSSH_OFFLINE_BUILD=1"
[[ "$PARALLELISM" =~ ^[1-4]$ ]] || die "MANGOSSH_ABI_PARALLELISM must be between 1 and 4"
[[ "$BUILD_JOBS" =~ ^[1-9][0-9]*$ ]] || die "MANGOSSH_ABI_BUILD_JOBS must be positive"
case "$OUTPUT_DIR" in
    "$PROJECT_DIR"/.tools/*|/tmp/mangossh-*) ;;
    *) die "unsafe parallel Mosh work directory: $OUTPUT_DIR" ;;
esac

rm -rf -- "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR"

pids=()
labels=()

stop_workers() {
    local status=$?
    if (( status != 0 )); then
        for pid in "${pids[@]:-}"; do
            kill "$pid" 2>/dev/null || true
        done
    fi
}
trap stop_workers EXIT

build_abi() {
    local abi="$1"
    local worker_dir="$OUTPUT_DIR/work-$abi"
    local patched_source="$PROJECT_DIR/.tools/mosh4android-patched-$abi"

    env \
        ABIS="$abi" \
        WORK_DIR="$worker_dir" \
        MAX_BUILD_JOBS="$BUILD_JOBS" \
        MANGOSSH_PATCHED_MOSH_SOURCE="$patched_source" \
        bash "$PROJECT_DIR/tools/build-mosh-android-wsl.sh" \
        >"$OUTPUT_DIR/$abi.log" 2>&1
    cp "$worker_dir/mosh-android-$abi.zip" "$OUTPUT_DIR/"
}

wait_oldest() {
    local pid="${pids[0]}"
    local label="${labels[0]}"
    if ! wait "$pid"; then
        tail -n 80 "$OUTPUT_DIR/$label.log" >&2 || true
        die "parallel Mosh worker failed for $label"
    fi
    pids=("${pids[@]:1}")
    labels=("${labels[@]:1}")
}

for abi in $ABIS; do
    while (( ${#pids[@]} >= PARALLELISM )); do
        wait_oldest
    done
    build_abi "$abi" &
    pids+=("$!")
    labels+=("$abi")
done

while (( ${#pids[@]} > 0 )); do
    wait_oldest
done

for abi in $ABIS; do
    [[ -s "$OUTPUT_DIR/mosh-android-$abi.zip" ]] ||
        die "parallel worker did not produce $abi"
done

printf 'Built isolated Mosh archives with ABI parallelism %s: %s\n' "$PARALLELISM" "$ABIS"
