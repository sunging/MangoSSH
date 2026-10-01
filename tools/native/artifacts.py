#!/usr/bin/env python3
"""Validate Android ELF identity before packaging or trusting a build result."""
import re
from pathlib import Path
from state import run

MACHINES = {
    "arm64-v8a": ("ELF64", "AArch64"),
    "armeabi-v7a": ("ELF32", "ARM"),
    "x86": ("ELF32", "Intel 80386"),
    "x86_64": ("ELF64", "Advanced Micro Devices X86-64"),
}
SYSTEM_LIBS = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libz.so"}


def verify_elf(path: Path, abi: str, readelf: Path | str, executable=False, packaged=()):
    header = run(readelf, "-hW", path)
    expected_class, machine = MACHINES[abi]
    actual_class = re.search(r"Class:\s+(\S+)", header)
    actual_machine = re.search(r"Machine:\s+(.+)", header)
    if not actual_class or actual_class[1] != expected_class or not actual_machine or actual_machine[1].strip() != machine:
        raise ValueError(f"Wrong ELF architecture for {abi}: {path}")
    if not re.search(r"Type:\s+DYN\b", header):
        raise ValueError(f"Expected a shared library or PIE: {path}")
    program = run(readelf, "-lW", path)
    loads = re.findall(r"^\s*LOAD\s+.+\s+(0x[0-9a-fA-F]+)\s*$", program, re.M)
    if not loads or any(int(value, 16) < 16384 for value in loads):
        raise ValueError(f"ELF lacks 16 KiB PT_LOAD alignment: {path}")
    if executable:
        entry = re.search(r"Entry point address:\s+(0x[0-9a-fA-F]+)", header)
        interpreter = "/system/bin/linker64" if expected_class == "ELF64" else "/system/bin/linker"
        if not entry or int(entry[1], 16) == 0 or f"[Requesting program interpreter: {interpreter}]" not in program:
            raise ValueError(f"Invalid Android executable entry/interpreter: {path}")
    needed = set(re.findall(r"\(NEEDED\).*\[(.*?)\]", run(readelf, "-dW", path)))
    missing = needed - SYSTEM_LIBS - set(packaged)
    if missing or (executable and "libc++_shared.so" in needed):
        raise ValueError(f"Unexpected dynamic dependencies in {path}: {sorted(missing or needed)}")
