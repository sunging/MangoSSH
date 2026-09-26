#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Exercises installation failures and offline reuse with a tiny local archive.
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
work_dir="$(mktemp -d)"
trap 'rm -rf -- "$work_dir"' EXIT
mkdir -p "$work_dir/tools/lib" "$work_dir/.tools/downloads" "$work_dir/archive/go/bin" "$work_dir/bin"
cp "$PROJECT_DIR/tools/fetch-go.sh" "$work_dir/tools/"
cp "$PROJECT_DIR/tools/lib/"{linux-host,go-toolchain}.sh "$work_dir/tools/lib/"
source "$work_dir/tools/lib/go-toolchain.sh"

expect_failure() {
    if "$@" > "$work_dir/failure.log" 2>&1; then
        printf 'Expected failure: %s\n' "$*" >&2
        exit 1
    fi
}

# A supplied compiler must never trigger toolchain auto-download, even if the
# calling environment requested a newer toolchain.
cat > "$work_dir/archive/go/bin/go" <<'GO'
#!/usr/bin/env bash
[[ "${GOTOOLCHAIN:-}" == local ]] || exit 90
printf 'go version go1.26.7 linux/amd64\n'
GO
chmod +x "$work_dir/archive/go/bin/go"
export GOTOOLCHAIN=go99.0.0
expect_failure mangossh_require_go "$work_dir/missing"
sed -i 's/go1.26.7/go1.26.6/' "$work_dir/archive/go/bin/go"
expect_failure mangossh_require_go "$work_dir/archive/go"
sed -i 's/go1.26.6/go1.26.7/' "$work_dir/archive/go/bin/go"
mangossh_require_go "$work_dir/archive/go"

archive="$work_dir/.tools/downloads/go1.26.7.linux-amd64.tar.gz"
tar -czf "$archive" -C "$work_dir/archive" go
# Networking is always forbidden in these tests; the fixture never fetches Go.
cat > "$work_dir/bin/curl" <<'CURL'
#!/usr/bin/env bash
echo 'Unexpected network access' >&2
exit 91
CURL
chmod +x "$work_dir/bin/curl"
export PATH="$work_dir/bin:$PATH"
# Exercise the real offline bridge entry point: it must fail before a download
# or any native compilation when its supplied Go installation is absent/wrong.
cp "$PROJECT_DIR/tools/build-tsnet-android.sh" "$work_dir/tools/"
cp "$PROJECT_DIR/tools/lib/tsnet-version.sh" "$work_dir/tools/lib/"
mkdir -p "$work_dir/sdk"
expect_failure env MANGOSSH_OFFLINE_BUILD=1 ANDROID_SDK_ROOT="$work_dir/sdk" \
    MANGOSSH_GO_ROOT="$work_dir/missing" bash "$work_dir/tools/build-tsnet-android.sh"
grep -q 'offline mode' "$work_dir/failure.log"
sed -i 's/go1.26.7/go1.26.6/' "$work_dir/archive/go/bin/go"
expect_failure env MANGOSSH_OFFLINE_BUILD=1 ANDROID_SDK_ROOT="$work_dir/sdk" \
    MANGOSSH_GO_ROOT="$work_dir/archive/go" bash "$work_dir/tools/build-tsnet-android.sh"
grep -q 'must be preinstalled' "$work_dir/failure.log"
sed -i 's/go1.26.6/go1.26.7/' "$work_dir/archive/go/bin/go"
expect_failure bash "$work_dir/tools/fetch-go.sh"
grep -q 'FAILED' "$work_dir/failure.log"
[[ ! -e "$work_dir/.tools/go/1.26.7" ]]

# Substitute only the fixture hash; production's hash remains pinned.
fixture_hash="$(sha256sum "$archive" | cut -d ' ' -f 1)"
sed -i "s/$MANGOSSH_GO_SHA256/$fixture_hash/" "$work_dir/tools/lib/go-toolchain.sh"
bash "$work_dir/tools/fetch-go.sh"
[[ "$(cat "$work_dir/.tools/go/1.26.7/.mangossh-archive-sha256")" == "$fixture_hash" ]]
rm "$archive"
# No archive and no network: a verified installation must still be reusable.
bash "$work_dir/tools/fetch-go.sh"

# An invalid replacement archive must not destroy an existing installation.
sed -i 's/go1.26.7/go1.26.6/' "$work_dir/archive/go/bin/go"
tar -czf "$archive" -C "$work_dir/archive" go
replacement_hash="$(sha256sum "$archive" | cut -d ' ' -f 1)"
sed -i "s/$fixture_hash/$replacement_hash/" "$work_dir/tools/lib/go-toolchain.sh"
expect_failure bash "$work_dir/tools/fetch-go.sh"
grep -q 'must be preinstalled' "$work_dir/failure.log"
mangossh_require_go "$work_dir/.tools/go/1.26.7"
echo 'Go toolchain missing/version/hash failures and offline cache reuse passed.'
