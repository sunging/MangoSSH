#!/usr/bin/env python3
"""Reject incomplete, stale or corrupted generated Mosh packaging directories."""
import argparse
import json
from pathlib import Path
from artifacts import verify_elf
from build import CONFIG, ROOT, ndk_home, selected_abis, verified_sources
from state import files


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jni-dir", type=Path, default=ROOT / "app/build/generated/native/mosh/jniLibs")
    parser.add_argument("--assets-dir", type=Path, default=ROOT / "app/build/generated/native/mosh/assets")
    parser.add_argument("--manifest", type=Path, default=ROOT / "app/build/generated/native/mosh/manifest.json")
    parser.add_argument("--abis", default=" ".join(CONFIG["abis"]))
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text())
    abis = selected_abis(args.abis)
    if manifest["abis"] != abis or manifest["jniLibs"] != files(args.jni_dir) or manifest["assets"] != files(args.assets_dir):
        raise SystemExit("Generated Mosh manifest/ABI/output mismatch; rebuild before packaging")
    current_sources = {name: identity for name, (_, identity) in verified_sources(manifest["sourceMode"]).items()}
    if current_sources != manifest["sources"]:
        raise SystemExit("Mosh source identity differs from the producer manifest")
    readelf = ndk_home() / "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
    for abi in abis:
        verify_elf(args.jni_dir / abi / "libmosh_client.so", abi, readelf, executable=True)
    print("Mosh manifest, ABI set, ELF dependencies and alignment verified")


if __name__ == "__main__":
    main()
