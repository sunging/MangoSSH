#!/usr/bin/env python3
"""Replay the pinned termlib JNI import, or verify the maintained native tree."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
from state import export_source, files, sha256, source_identity

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("checkout", type=Path, nargs="?", default=ROOT / ".tools/termlib-source",
                        help="Prepared checkout of the pinned upstream commit")
    parser.add_argument("--fetch", action="store_true", help="Explicit online preparation of a missing upstream checkout")
    parser.add_argument("--update", action="store_true", help="Replace the native import after review of local modifications")
    args = parser.parse_args()
    spec = json.loads((ROOT / "third_party/termlib/native-import.json").read_text())
    if args.fetch and not args.checkout.exists():
        args.checkout.mkdir(parents=True)
        subprocess.run(["git", "init", "-q", str(args.checkout)], check=True)
        subprocess.run(["git", "-C", str(args.checkout), "fetch", "--depth=1", spec["repository"], spec["commit"]], check=True)
        subprocess.run(["git", "-C", str(args.checkout), "checkout", "-q", "--detach", "FETCH_HEAD"], check=True)
    source_identity(args.checkout.resolve(), spec["commit"])
    with tempfile.TemporaryDirectory(prefix="mangossh-termlib-") as temp:
        upstream = Path(temp) / "upstream"
        export_source(args.checkout.resolve(), upstream, "locked")
        source = upstream / spec["source"]
        for patch in spec["patches"]:
            subprocess.run(["patch", "--batch", "--forward", "--fuzz=0", "-p1", "-d", str(source), "-i", str(ROOT / patch)], check=True)
        destination = ROOT / spec["destination"]
        license_path = ROOT / "third_party/termlib/LICENSE"
        if args.update:
            if destination.exists():
                shutil.rmtree(destination)
            shutil.copytree(source, destination)
            shutil.copy2(upstream / "LICENSE", license_path)
            shutil.copy2(source / "libvterm/LICENSE", ROOT / "app/src/main/assets/licenses/MIT-libvterm.txt")
        if files(source) != files(destination) or sha256(upstream / "LICENSE") != sha256(license_path):
            raise SystemExit("termlib native import differs; record intentional changes as ordered patches")
        if sha256(source / "libvterm/LICENSE") != sha256(ROOT / "app/src/main/assets/licenses/MIT-libvterm.txt"):
            raise SystemExit("Packaged libvterm license differs from the imported source")
        print("termlib JNI and libvterm import verified")


if __name__ == "__main__":
    main()
