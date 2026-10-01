#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
if [[ -n "${WSL_DISTRO_NAME:-}" ]]; then
    if [[ -n "${MANGOSSH_LINUX_JAVA_HOME:-}" ]]; then
        export JAVA_HOME="$MANGOSSH_LINUX_JAVA_HOME"
    elif command -v javac >/dev/null 2>&1; then
        export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
    else
        echo 'Set MANGOSSH_LINUX_JAVA_HOME to a Linux JDK 17.' >&2; exit 1
    fi
    export ANDROID_HOME="${MANGOSSH_LINUX_SDK_HOME:-$PROJECT_DIR/.tools/android-sdk}"
    export ANDROID_NDK_HOME="${MANGOSSH_LINUX_NDK_HOME:-$PROJECT_DIR/.tools/android-ndk-linux/27.3.13750724}"
fi
export ANDROID_SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "${MANGOSSH_NATIVE_STATE:-}" ]]; then
    # Gradle on Windows leaves the choice to WSL: exported sources and objects
    # on a Windows drive are much slower than on the distribution's file system.
    [[ -n "${WSL_DISTRO_NAME:-}" ]] || { echo 'MANGOSSH_NATIVE_STATE is required' >&2; exit 1; }
    checkout="$(printf '%s' "$PROJECT_DIR" | sha256sum | cut -c1-16)"
    MANGOSSH_NATIVE_STATE="${XDG_CACHE_HOME:-$HOME/.cache}/mangossh/native-state/$checkout"
fi
args=("$1" --state "$MANGOSSH_NATIVE_STATE")
case "$1" in
    mosh) args+=(--jni-dir "$MANGOSSH_JNI_DIR" --assets-dir "$MANGOSSH_ASSETS_DIR" --symbols-dir "$MANGOSSH_SYMBOLS_DIR" --manifest "$MANGOSSH_MANIFEST") ;;
    tsnet) args+=(--output-dir "$MANGOSSH_TSNET_OUTPUT_DIR") ;;
    *) echo 'Unsupported native producer' >&2; exit 1 ;;
esac
exec python3 "$PROJECT_DIR/tools/native/build.py" "${args[@]}"
