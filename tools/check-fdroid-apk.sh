#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
APK="${1:-}"
AAPT2="${AAPT2:-}"

die() {
    printf 'error: %s\n' "$*" >&2
    exit 1
}

[[ -f "$APK" ]] || die "APK does not exist: $APK"
for required_command in grep mktemp rm unzip; do
    command -v "$required_command" >/dev/null 2>&1 || die "missing required command: $required_command"
done
if [[ -z "$AAPT2" ]]; then
    sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    [[ -d "$sdk_root/build-tools" ]] || die "AAPT2 or ANDROID_HOME is required"
    AAPT2="$(find "$sdk_root/build-tools" -mindepth 2 -maxdepth 2 -type f -name aapt2 | sort -V | tail -n 1)"
fi
[[ -x "$AAPT2" ]] || die "aapt2 is not executable: $AAPT2"

expected_version="$(tr -d '\r\n' < "$PROJECT_DIR/version.txt")"
expected_code="$(tr -d '\r\n' < "$PROJECT_DIR/version-code.txt")"
badging="$($AAPT2 dump badging "$APK")"
grep -Fq "package: name='website.sung.mangossh' versionCode='$expected_code' versionName='$expected_version'" \
    <<<"$badging" || die "unexpected package or version identity"

permissions="$($AAPT2 dump permissions "$APK")"
if grep -Fq 'android.permission.REQUEST_INSTALL_PACKAGES' <<<"$permissions"; then
    die "F-Droid APK requests package installation permission"
fi

manifest="$($AAPT2 dump xmltree --file AndroidManifest.xml "$APK")"
if grep -Fq 'androidx.core.content.FileProvider' <<<"$manifest" ||
    grep -Fq 'website.sung.mangossh.updates' <<<"$manifest"; then
    die "F-Droid APK contains the self-update FileProvider"
fi

dex_dump="$(mktemp)"
resource_dump="$(mktemp)"
trap 'rm -f -- "$dex_dump" "$resource_dump"' EXIT
unzip -p "$APK" 'classes*.dex' > "$dex_dump"
for forbidden_marker in \
    'api.github.com/repos/sunging/MangoSSH/releases/latest' \
    'application/vnd.android.package-archive' \
    'GitHubReleaseClient' \
    'AppUpdateFileStore' \
    'AppUpdateLogEvent' \
    'app_update.' \
    'archiveMatchesInstalledSigner'; do
    if grep -aFq "$forbidden_marker" "$dex_dump"; then
        die "F-Droid APK contains updater marker: $forbidden_marker"
    fi
done

unzip -p "$APK" resources.arsc > "$resource_dump"
for forbidden_resource_marker in \
    'app_update_' \
    'settings_category_updates_' \
    'MangoSSH checks GitHub for a newer signed release' \
    'The update check could not reach GitHub' \
    'Download update' \
    'Check for and install new versions'; do
    if grep -aFq "$forbidden_resource_marker" "$resource_dump"; then
        die "F-Droid APK contains updater resource marker: $forbidden_resource_marker"
    fi
done

entries="$(unzip -Z1 "$APK")"
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    grep -Fxq "lib/$abi/libmangossh_pty.so" <<<"$entries" || die "missing PTY bridge for $abi"
    grep -Fxq "lib/$abi/libmosh_client.so" <<<"$entries" || die "missing Mosh client for $abi"
done

if [[ "${MANGOSSH_SKIP_ELF_CHECK:-0}" != "1" ]]; then
    bash "$PROJECT_DIR/tools/check-16kb-elf.sh" "$APK"
fi
printf 'Verified updater-free four-ABI F-Droid APK: %s\n' "$APK"
