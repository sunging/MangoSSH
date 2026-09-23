"""Reject missing, failed or skipped required JUnit regression results."""
import sys
from pathlib import Path
import xml.etree.ElementTree as ET

total = 0
failures = []
for directory in sys.argv[1:]:
    reports = list(Path(directory).rglob("TEST-*.xml"))
    if not reports:
        raise SystemExit("Required regression reports are missing")
    for report in reports:
        root = ET.parse(report).getroot()
        cases = list(root.iter("testcase"))
        for case in cases:
            status = next((name for name in ("failure", "error", "skipped") if case.find(name) is not None), None)
            if status is not None:
                detail = case.find(status)
                message = " ".join((detail.text or "").split()) if detail is not None else ""
                failures.append((case.attrib.get("classname", "unknown"), case.attrib.get("name", "unknown"), status, message))
        total += len(cases)
if total == 0:
    raise SystemExit("No required regressions executed")
if failures:
    for classname, name, status, message in failures:
        suffix = f": {message[:500]}" if message else ""
        # GitHub renders these as check annotations even when the Gradle step has no summary.
        print(f"::error title=Instrumented regression::{classname}.{name} [{status}]{suffix}")
    raise SystemExit(f"Required regression failed or was skipped ({len(failures)} case(s))")
print(f"Verified {total} required regressions without failures or skips")
