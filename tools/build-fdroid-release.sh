#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Composes native preparation with the final offline Gradle assembly for local
# and CI verification. fdroidserver calls prepare-fdroid-native.sh from its
# custom build step, then runs assembleFdroidRelease itself.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=tools/lib/linux-host.sh
source "$PROJECT_DIR/tools/lib/linux-host.sh"
mangossh_require_linux_x86_64
mangossh_require_commands bash grep

die() {
    printf 'error: %s\n' "$*" >&2
    exit 1
}

export MANGOSSH_GO_ROOT="${MANGOSSH_GO_ROOT:-$PROJECT_DIR/.tools/go/1.26.5}"
export MANGOSSH_OFFLINE_BUILD=1
export GOTOOLCHAIN=local
export GOPROXY=off
export GOSUMDB=off
export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-315532800}"

cd "$PROJECT_DIR"
bash tools/prepare-fdroid-native.sh
"${MANGOSSH_GRADLE_BIN:-$PROJECT_DIR/gradlew}" \
    --offline --no-daemon \
    -PmangosshOfflineBuild=true \
    :app:verifyReleaseVersion \
    :app:assembleFdroidRelease

APK="$PROJECT_DIR/app/build/outputs/apk/fdroid/release/app-fdroid-release-unsigned.apk"
[[ -f "$APK" ]] || die "unsigned release APK was not produced"
bash tools/check-16kb-elf.sh "$APK"
printf 'Built unsigned F-Droid APK: %s\n' "$APK"
