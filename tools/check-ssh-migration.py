"""Assert production source and packaged DEX no longer reference the removed SSH implementation."""
from pathlib import Path
import sys
import zipfile

markers = (b"com" + b".trilead", b"com" + b"/trilead")
for root in (Path("app/src"), Path("third_party/cbssh/src")):
    for source in root.rglob("*"):
        if source.is_file() and source.suffix in (".kt", ".java", ".xml"):
            if any(marker in source.read_bytes() for marker in markers):
                raise SystemExit("Removed SSH implementation remains in source")
if not sys.argv[1:]:
    raise SystemExit("APK paths are required")
for apk in sys.argv[1:]:
    with zipfile.ZipFile(apk) as archive:
        dex = [name for name in archive.namelist() if name.endswith(".dex")]
        if not dex:
            raise SystemExit("APK has no DEX files")
        for name in dex:
            if any(marker in archive.read(name) for marker in markers):
                raise SystemExit("Removed SSH implementation remains in APK")
print("SSH source and APK migration scan passed")
