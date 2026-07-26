#!/usr/bin/env python3
"""Strips gomobile libraries and writes a deterministic Android AAR."""

from __future__ import annotations

import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import zipfile


def main() -> int:
    if len(sys.argv) != 6:
        print(
            "usage: normalize-tsnet-aar.py INPUT OUTPUT LLVM_STRIP TAILSCALE_LICENSE NOTICES",
            file=sys.stderr,
        )
        return 2
    source = Path(sys.argv[1]).resolve()
    destination = Path(sys.argv[2]).resolve()
    strip = Path(sys.argv[3]).resolve()
    tailscale_license = Path(sys.argv[4]).resolve()
    notices = Path(sys.argv[5]).resolve()
    if not all(path.is_file() for path in (source, strip, tailscale_license, notices)):
        print("input AAR, llvm-strip, license, or notices is missing", file=sys.stderr)
        return 1

    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="mangossh-tsnet-aar-") as temp:
        root = Path(temp)
        with zipfile.ZipFile(source) as archive:
            archive.extractall(root)
        libraries = sorted((root / "jni").glob("*/libgojni.so"))
        if len(libraries) != 4:
            print(f"expected four libgojni.so files, found {len(libraries)}", file=sys.stderr)
            return 1
        for library in libraries:
            subprocess.run(
                [os.fspath(strip), "--strip-unneeded", os.fspath(library)],
                check=True,
            )
        licenses = root / "assets" / "licenses"
        licenses.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(tailscale_license, licenses / "tailscale-BSD-3-Clause.txt")
        shutil.copyfile(notices, licenses / "tsnet-third-party-notices.txt")

        temporary_output = destination.with_suffix(".aar.tmp")
        try:
            with zipfile.ZipFile(
                temporary_output,
                mode="w",
                compression=zipfile.ZIP_DEFLATED,
                compresslevel=9,
            ) as archive:
                for path in sorted(root.rglob("*")):
                    if not path.is_file():
                        continue
                    relative = path.relative_to(root).as_posix()
                    info = zipfile.ZipInfo(relative, date_time=(1980, 1, 1, 0, 0, 0))
                    info.compress_type = zipfile.ZIP_DEFLATED
                    info.external_attr = 0o100644 << 16
                    archive.writestr(info, path.read_bytes(), compresslevel=9)
            shutil.move(temporary_output, destination)
        finally:
            temporary_output.unlink(missing_ok=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
