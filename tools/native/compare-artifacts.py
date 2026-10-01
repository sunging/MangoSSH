#!/usr/bin/env python3
"""Compare native packaging outputs from two independent clean builds."""
import argparse
from pathlib import Path
import zipfile
from state import sha256


def outputs(root):
    return {p.relative_to(root).as_posix(): p for p in root.rglob("*")
            if p.is_file() and "symbols" not in p.relative_to(root).parts
            and p.suffix in (".so", ".aar", ".zip")}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("left", type=Path)
    parser.add_argument("right", type=Path)
    args = parser.parse_args()
    left, right = outputs(args.left), outputs(args.right)
    if not left or left.keys() != right.keys():
        raise SystemExit(f"Artifact sets differ or are empty: {sorted(left.keys() ^ right.keys())}")
    different = []
    for name in left:
        if sha256(left[name]) == sha256(right[name]):
            continue
        different.append(name)
        print(f"DIFF {name}")
        if zipfile.is_zipfile(left[name]) and zipfile.is_zipfile(right[name]):
            with zipfile.ZipFile(left[name]) as a, zipfile.ZipFile(right[name]) as b:
                entries = set(a.namelist()) | set(b.namelist())
                for entry in sorted(entries):
                    if entry not in a.namelist() or entry not in b.namelist() or a.read(entry) != b.read(entry):
                        print(f"  content differs: {entry}")
    if different:
        raise SystemExit(1)
    print(f"Byte-identical: {len(left)} native artifacts")


if __name__ == "__main__":
    main()
