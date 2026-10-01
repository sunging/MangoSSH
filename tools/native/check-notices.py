#!/usr/bin/env python3
"""Check native license copies and the dependency versions referenced by notices."""
from pathlib import Path
import re
import tomllib

ROOT = Path(__file__).resolve().parents[2]
versions = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text())["versions"]
notices = (ROOT / "THIRD_PARTY_NOTICES.md").read_text()
for component, pattern in (("okhttp", r"OkHttp\s+([\d.]+)"), ("termlib", r"termlib\s+([\d.]+)")):
    match = re.search(pattern, notices)
    if not match or match[1] != versions[component]:
        raise SystemExit(f"THIRD_PARTY_NOTICES.md must describe {component} {versions[component]}")
for source, packaged in (
    ("third_party/mosh4android/COPYING", "GPL-3.0-or-later.txt"),
    ("third_party/termlib/src/main/cpp/libvterm/LICENSE", "MIT-libvterm.txt"),
):
    if (ROOT / source).read_bytes() != (ROOT / "app/src/main/assets/licenses" / packaged).read_bytes():
        raise SystemExit(f"Packaged license differs from {source}")
print("Notice versions and native license copies verified")
