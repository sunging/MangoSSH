#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Mosh now publishes generated directories itself. Retain the old entry as a
# verification-only adapter; it never edits source files or strips PTY output.
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
exec python3 "$PROJECT_DIR/tools/native/verify-published.py" "$@"
