#!/usr/bin/env python3
"""Verify every packaged ELF and emit a native provenance sidecar for an APK."""
import argparse
import hashlib
import json
from pathlib import Path
import tempfile
import zipfile
from artifacts import verify_elf
from build import CONFIG, ROOT, selected_abis
from state import files, sha256

REQUIRED = {"libmangossh_pty.so", "libmosh_client.so", "libjni_cb_term.so", "libgojni.so"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--abis", default=" ".join(CONFIG["abis"]))
    parser.add_argument("--readelf", default="readelf")
    args = parser.parse_args()
    abis = selected_abis(args.abis)
    native = {}
    assets = {}
    with tempfile.TemporaryDirectory(prefix="mangossh-apk-elf-") as directory, zipfile.ZipFile(args.apk) as archive:
        libraries = [name for name in archive.namelist() if name.startswith("lib/") and name.endswith(".so")]
        if {name.split("/")[1] for name in libraries} != set(abis):
            raise SystemExit("APK native ABI set does not match the requested build")
        for abi in abis:
            names = {name.split("/")[-1] for name in libraries if name.startswith(f"lib/{abi}/")}
            if not REQUIRED <= names:
                raise SystemExit(f"APK is missing required native components for {abi}: {REQUIRED - names}")
            for name in sorted(names):
                entry = f"lib/{abi}/{name}"
                data = archive.read(entry)
                elf = Path(directory) / name
                elf.write_bytes(data)
                verify_elf(elf, abi, args.readelf, executable=name == "libmosh_client.so", packaged=names)
                native[entry] = hashlib.sha256(data).hexdigest()
        for name in ("mosh/terminfo.zip", "licenses/GPL-3.0-or-later.txt", "licenses/MIT-libvterm.txt"):
            data = archive.read(f"assets/{name}")
            if not data:
                raise SystemExit(f"APK contains an empty required asset: {name}")
            assets[name] = hashlib.sha256(data).hexdigest()
    manifests = {}
    for component, path in {
        "mosh": ROOT / "app/build/generated/native/mosh/manifest.json",
        "tsnet": ROOT / "app/build/generated/tsnet/manifest.json",
    }.items():
        manifests[component] = json.loads(path.read_text())
    mosh = manifests["mosh"]
    if mosh["abis"] != abis:
        raise SystemExit("Mosh producer ABI set differs from the APK")
    if assets["mosh/terminfo.zip"] != mosh["assets"]["mosh/terminfo.zip"]["sha256"]:
        raise SystemExit("APK terminfo differs from the verified producer")
    for name in ("licenses/GPL-3.0-or-later.txt", "licenses/MIT-libvterm.txt"):
        if assets[name] != sha256(ROOT / "app/src/main/assets" / name):
            raise SystemExit(f"APK license differs from its source: {name}")
    for abi in abis:
        name = f"{abi}/libmosh_client.so"
        if native[f"lib/{name}"] != mosh["jniLibs"][name]["sha256"]:
            raise SystemExit(f"APK Mosh bytes differ from the verified producer: {abi}")
    aar = ROOT / "app/build/generated/tsnet/mangossh-tsnet.aar"
    if sha256(aar) != manifests["tsnet"]["outputs"]["mangossh-tsnet.aar"]["sha256"]:
        raise SystemExit("tsnet AAR differs from its producer manifest")
    if manifests["tsnet"]["input"]["abis"] != abis:
        raise SystemExit("tsnet producer ABI set differs from the APK")
    with zipfile.ZipFile(aar) as archive:
        for abi in abis:
            expected = hashlib.sha256(archive.read(f"jni/{abi}/libgojni.so")).hexdigest()
            if native[f"lib/{abi}/libgojni.so"] != expected:
                raise SystemExit(f"APK tsnet bytes differ from the verified producer: {abi}")
    record = {"schema": 1, "apk": sha256(args.apk), "toolchains": CONFIG,
              "libraries": native, "assets": assets, "producers": manifests,
              "ptySources": files(ROOT / "app/src/main/cpp"),
              "termlibSources": files(ROOT / "third_party/termlib/src/main/cpp")}
    output = args.apk.with_suffix(".native.json")
    output.write_text(json.dumps(record, sort_keys=True, indent=2) + "\n")
    print(f"Verified {len(native)} native files; provenance: {output}")


if __name__ == "__main__":
    main()
