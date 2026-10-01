#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Compatibility entry; Gradle and F-Droid use the same offline producer.
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
exec python3 "$PROJECT_DIR/tools/native/build.py" host-tic "$@"
