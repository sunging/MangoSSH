#!/usr/bin/env python3
"""Verify JCA's reflective Ed25519 entry points in the final APK's DEX definitions.

R8 can remove a service or its constructor while leaving its name in the string
table. Inspect SDK dexdump's class/constructor output, not arbitrary byte matches.
"""

import argparse
from pathlib import Path
import re
import subprocess
from tempfile import TemporaryDirectory
import xml.etree.ElementTree as ET
from zipfile import BadZipFile, ZipFile


PACKAGE = "org.connectbot.sshlib.crypto.ed25519"
REQUIRED_CLASSES = {
    "Ed25519Provider": "java.security.Provider",
    "Ed25519KeyFactory": "java.security.KeyFactorySpi",
    "Ed25519KeyPairGenerator": "java.security.KeyPairGeneratorSpi",
}


def check_definitions(definitions: dict[str, ET.Element]) -> None:
    """Require concrete public services with callable public zero-argument constructors."""
    failures = []
    for name, superclass in REQUIRED_CLASSES.items():
        definition = definitions.get(name)
        if definition is None:
            failures.append(f"{name}: class missing or renamed")
            continue
        if (definition.get("visibility") != "public"
                or definition.get("abstract") != "false"
                or definition.get("extends") != superclass):
            failures.append(f"{name}: expected a public concrete {superclass} subclass")
        if not any(
            constructor.get("visibility") == "public"
            and constructor.get("static") == "false"
            and not constructor.findall("parameter")
            for constructor in definition.findall("constructor")
        ):
            failures.append(f"{name}: public zero-argument constructor missing")
    if failures:
        raise ValueError("; ".join(failures))


def verify_apk(apk: Path, dexdump: str) -> None:
    """Read every root DEX file; temporary extraction stays beside the build artifact."""
    definitions = {}
    with ZipFile(apk) as archive, TemporaryDirectory(prefix="ed25519-check-", dir=apk.resolve().parent) as temporary:
        entries = [name for name in archive.namelist() if re.fullmatch(r"classes\d*\.dex", name)]
        if not entries:
            raise ValueError("APK has no DEX files")
        for entry in entries:
            dex = Path(temporary) / entry
            dex.write_bytes(archive.read(entry))
            result = subprocess.run(
                [dexdump, "-l", "xml", str(dex)], capture_output=True, check=True,
            )
            root = ET.fromstring(result.stdout)
            for package in root.findall("package"):
                if package.get("name") != PACKAGE:
                    continue
                for definition in package.findall("class"):
                    name = definition.get("name")
                    if name in REQUIRED_CLASSES:
                        if name in definitions:
                            raise ValueError(f"{name}: duplicate class definition")
                        definitions[name] = definition
    check_definitions(definitions)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dexdump", default="dexdump", help="Android SDK build-tools dexdump executable")
    parser.add_argument("apks", nargs="+", type=Path)
    arguments = parser.parse_args()
    for apk in arguments.apks:
        try:
            verify_apk(apk, arguments.dexdump)
        except (OSError, BadZipFile, subprocess.CalledProcessError, ET.ParseError, ValueError) as error:
            raise SystemExit(f"Ed25519 APK verification failed ({apk.name}): {error}") from None
        print(f"Ed25519 JCA classes and constructors verified: {apk.name}")


if __name__ == "__main__":
    main()
