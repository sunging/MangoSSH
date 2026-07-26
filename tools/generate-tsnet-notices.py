#!/usr/bin/env python3
"""Generate deterministic third-party notices from the Go module graph."""

from __future__ import annotations

import json
from pathlib import Path
import sys

LICENSE_PATTERNS = ("LICENSE*", "COPYING*", "NOTICE*")


def load_json_stream(path: Path) -> list[dict[str, object]]:
    text = path.read_text(encoding="utf-8")
    decoder = json.JSONDecoder()
    values: list[dict[str, object]] = []
    offset = 0
    while offset < len(text):
        while offset < len(text) and text[offset].isspace():
            offset += 1
        if offset == len(text):
            break
        value, offset = decoder.raw_decode(text, offset)
        values.append(value)
    return values


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: generate-tsnet-notices.py MODULES_JSON OUTPUT", file=sys.stderr)
        return 2
    values = load_json_stream(Path(sys.argv[1]))
    modules_by_path: dict[str, dict[str, object]] = {}
    for value in values:
        module = value.get("Module")
        if isinstance(module, dict):
            modules_by_path[str(module["Path"])] = module
        elif "Path" in value and ("Version" in value or value.get("Main")):
            modules_by_path[str(value["Path"])] = value
    modules = list(modules_by_path.values())
    sections: list[tuple[str, str]] = []
    for module in modules:
        if module.get("Main"):
            continue
        path = str(module["Path"])
        version = str(module.get("Version", ""))
        replacement = module.get("Replace")
        directory = module.get("Dir")
        go_mod = module.get("GoMod")
        if isinstance(replacement, dict):
            directory = replacement.get("Dir", directory)
            go_mod = replacement.get("GoMod", go_mod)
        if not directory and go_mod:
            directory = str(Path(str(go_mod)).parent)
        if not directory:
            print(f"module directory missing for {path}", file=sys.stderr)
            return 1
        root = Path(str(directory))
        license_paths = sorted(
            {
                candidate
                for pattern in LICENSE_PATTERNS
                for candidate in root.glob(pattern)
                if candidate.is_file()
            },
        )
        label_version = "v1.98.8" if path == "tailscale.com" else version
        label = f"{path} {label_version}".rstrip()
        license_text = (
            "\n\n".join(
                f"--- {license_path.name} ---\n"
                f"{license_path.read_text(encoding='utf-8', errors='replace').strip()}"
                for license_path in license_paths
            )
            if license_paths
            else "No root license file was distributed in this Go module archive."
        )
        sections.append((label, license_text))

    lines = [
        "MangoSSH embedded tsnet third-party notices",
        "",
        "Generated from the complete pinned Go module graph. Binary artifacts are",
        "not committed; this notice is packaged into every generated AAR/APK.",
    ]
    for label, license_text in sorted(sections):
        lines.extend(("", "=" * 78, label, "=" * 78, license_text))
    destination = Path(sys.argv[2])
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
