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
args=("$1" --state "$MANGOSSH_NATIVE_STATE")
case "$1" in
    mosh) args+=(--jni-dir "$MANGOSSH_JNI_DIR" --assets-dir "$MANGOSSH_ASSETS_DIR" --manifest "$MANGOSSH_MANIFEST") ;;
    tsnet) args+=(--output-dir "$MANGOSSH_TSNET_OUTPUT_DIR") ;;
    *) echo 'Unsupported native producer' >&2; exit 1 ;;
esac
exec python3 "$PROJECT_DIR/tools/native/build.py" "${args[@]}"
