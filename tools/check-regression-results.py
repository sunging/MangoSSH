"""Reject missing, failed or skipped required JUnit regression results."""
import sys
from pathlib import Path
import xml.etree.ElementTree as ET

total = 0
for directory in sys.argv[1:]:
    reports = list(Path(directory).rglob("TEST-*.xml"))
    if not reports:
        raise SystemExit("Required regression reports are missing")
    for report in reports:
        root = ET.parse(report).getroot()
        cases = list(root.iter("testcase"))
        if any(case.find(status) is not None for case in cases for status in ("failure", "error", "skipped")):
            raise SystemExit("Required regression failed or was skipped")
        total += len(cases)
if total == 0:
    raise SystemExit("No required regressions executed")
print(f"Verified {total} required regressions without failures or skips")
