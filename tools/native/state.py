#!/usr/bin/env python3
"""Content identities and transactional local caches for native build recipes.

Caches are local acceleration only: source policy is checked before looking up
an entry, and every published output is hashed before it can be reused.
"""
from __future__ import annotations

import contextlib
import errno
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import time


def run(*args: str | Path, **kwargs) -> str:
    return subprocess.check_output([str(a) for a in args], text=True, **kwargs).strip()


def digest(value) -> str:
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def files(root: Path) -> dict:
    """Hash relative names, file contents, executable bits and symlink targets."""
    result = {}
    for path in sorted(root.rglob("*")):
        name = path.relative_to(root).as_posix()
        if path.is_symlink():
            result[name] = {"link": os.readlink(path)}
        elif path.is_file():
            result[name] = {"sha256": sha256(path), "executable": bool(path.stat().st_mode & 0o111)}
    return result


def published_contents(tree: dict) -> dict:
    """Compare a published tree by content and symlinks only.

    Windows drives seen through WSL report every file as executable, and AGP does
    not use the mode of packaged inputs, so the bit would only cause false
    mismatches between a staging tree and its published copy.
    """
    return {name: {k: v for k, v in entry.items() if k != "executable"} for name, entry in tree.items()}


def source_identity(path: Path, commit: str, mode: str = "locked") -> dict:
    """Verify the checkout AND recursive gitlinks; never silently ignore edits."""
    if mode not in ("locked", "worktree"):
        raise ValueError(f"Unknown native source mode: {mode!r}; expected locked or worktree")
    if run("git", "-C", path, "rev-parse", "--show-toplevel") != str(path.resolve()):
        raise ValueError(f"Source must be its own initialized Git checkout: {path}")
    actual = run("git", "-C", path, "rev-parse", "HEAD")
    if actual != commit:
        raise ValueError(f"{path.name}: expected commit {commit}, found {actual}")
    submodules = run("git", "-C", path, "submodule", "status", "--recursive")
    # Do not strip the leading status character from individual lines.
    raw = subprocess.check_output(["git", "-C", str(path), "submodule", "status", "--recursive"], text=True)
    if any(line and line[0] != " " for line in raw.splitlines()):
        raise ValueError(f"{path.name}: uninitialized or mismatched recursive submodule")
    # Locked builds export Git blobs, so line endings in the work tree never reach
    # the build. A Windows checkout made with core.autocrlf=true has CRLF files
    # that WSL Git (which cannot read the Windows config) would report as edited.
    eol = ["-c", "core.autocrlf=true"] if mode == "locked" else []
    status = run("git", "--no-optional-locks", *eol, "-C", path, "status", "--porcelain",
                 "--untracked-files=all", "--ignore-submodules=none")
    if mode == "locked" and status:
        raise ValueError(f"{path.name}: dirty source; commit/revert changes or explicitly select worktree mode")
    result = {"commit": actual, "submodules": submodules, "dirty": bool(status)}
    if mode == "worktree":
        result["worktree"] = worktree_files(path)
    return result


def worktree_files(path: Path) -> dict:
    """Include tracked and untracked files, deletions and recursive submodules."""
    names = subprocess.check_output([
        "git", "-C", str(path), "ls-files", "-z", "--cached", "--others", "--exclude-standard",
    ]).decode().split("\0")
    result = {}
    for name in sorted(set(filter(None, names))):
        child = path / name
        if child.is_symlink():
            result[name] = {"link": os.readlink(child)}
        elif child.is_file():
            result[name] = {"sha256": sha256(child), "executable": bool(child.stat().st_mode & 0o111)}
        elif child.is_dir():
            result[name] = worktree_files(child)
        else:
            result[name] = None
    return result


def export_source(source: Path, destination: Path, mode: str):
    destination.mkdir(parents=True, exist_ok=True)
    if mode == "worktree":
        for name, identity in worktree_files(source).items():
            src, dst = source / name, destination / name
            if identity is None:
                continue
            dst.parent.mkdir(parents=True, exist_ok=True)
            if src.is_symlink():
                dst.symlink_to(os.readlink(src))
            elif src.is_dir():
                export_source(src, dst, mode)
            else:
                shutil.copy2(src, dst)
        return
    # Git blobs preserve canonical LF endings on Windows/WSL checkouts.
    archive = subprocess.Popen(["git", "-C", str(source), "archive", "HEAD"], stdout=subprocess.PIPE)
    try:
        with tarfile.open(fileobj=archive.stdout, mode="r|") as stream:
            stream.extractall(destination, filter="data")
    finally:
        archive.stdout.close()
    if archive.wait() != 0:
        raise RuntimeError(f"Failed to export {source}")
    gitmodules = source / ".gitmodules"
    if gitmodules.exists():
        entries = run("git", "-C", source, "config", "-f", ".gitmodules", "--get-regexp", r"submodule\..*\.path")
        for entry in entries.splitlines():
            name = entry.split(" ", 1)[1]
            export_source(source / name, destination / name, mode)


@contextlib.contextmanager
def locked(path: Path):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a") as stream:
        fcntl.flock(stream, fcntl.LOCK_EX)
        yield


def valid_entry(path: Path, identity: dict | None = None) -> bool:
    try:
        manifest = json.loads((path / "manifest.json").read_text())
        return (identity is None or manifest["input"] == identity) and bool(manifest["outputs"]) and manifest["outputs"] == files(path / "install")
    except (OSError, ValueError, KeyError):
        return False


def cached_build(root: Path, name: str, identity: dict, builder) -> Path:
    """Only a complete, hash-verified install tree is a reusable cache entry."""
    key = digest(identity)
    entry = root / "cache" / name / key
    with locked(root / "locks" / f"{name}-{key}.lock"):
        if valid_entry(entry, identity):
            print(f"native: reuse {name} {key[:12]}", flush=True)
            return entry
        print(f"native: build {name} {key[:12]}", flush=True)
        work = root / "work" / name / key
        shutil.rmtree(work, ignore_errors=True)
        work.mkdir(parents=True)
        install = work / "install"
        install.mkdir()
        builder(work, install)
        outputs = files(install)
        if not outputs:
            raise ValueError(f"{name}: empty build output")
        staging = entry.with_name(key + ".partial")
        shutil.rmtree(staging, ignore_errors=True)
        staging.mkdir(parents=True)
        shutil.copytree(install, staging / "install", symlinks=True)
        (staging / "manifest.json").write_text(json.dumps({"schema": 1, "component": name, "input": identity, "outputs": outputs}, sort_keys=True, indent=2) + "\n")
        replace_directory(staging, entry)
        return entry


def replace_directory(staging: Path, destination: Path, attempts: int = 10):
    """Atomically move a completed staging directory over its destination.

    On a Windows drive used through WSL, virus scanners and the search indexer
    briefly hold handles to files that were just written; renaming or deleting
    their directory then fails with EACCES. Retry with backoff instead of
    failing a long native build at its last step.
    """
    for attempt in range(attempts):
        try:
            shutil.rmtree(destination, ignore_errors=True)
            staging.rename(destination)
            return
        except OSError as error:
            if attempt == attempts - 1 or error.errno not in (errno.EACCES, errno.EPERM, errno.ENOTEMPTY, errno.EEXIST):
                raise
            time.sleep(min(0.1 * 2 ** attempt, 5))


def publish_directory(source: Path, destination: Path, extra: dict[str, Path] | None = None):
    """Replace the complete directory, so removed ABIs cannot survive a build.

    `extra` maps top-level names to files published beside the copied tree, such
    as a manifest. They are part of the comparison, so an unchanged publication
    is left in place instead of being deleted and copied again on every build.
    """
    extra = extra or {}
    expected = files(source)
    expected.update({name: {"sha256": sha256(path)} for name, path in extra.items()})
    if destination.exists() and published_contents(expected) == published_contents(files(destination)):
        return
    staging = destination.with_name(destination.name + ".partial")
    shutil.rmtree(staging, ignore_errors=True)
    staging.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(source, staging, symlinks=True)
    for name, path in extra.items():
        shutil.copy2(path, staging / name)
    replace_directory(staging, destination)


def referenced_entries(manifest) -> set[tuple[str, str]]:
    """Collect (component, cache key) for a manifest and every nested dependency."""
    found = set()
    if isinstance(manifest, dict):
        if {"component", "input", "outputs"} <= manifest.keys():
            found.add((manifest["component"], digest(manifest["input"])))
        values = manifest.values()
    elif isinstance(manifest, list):
        values = manifest
    else:
        return found
    for value in values:
        found |= referenced_entries(value)
    return found


def prune_cache(root: Path, manifests: list) -> list[Path]:
    """Delete cache entries that none of the given published manifests reference."""
    keep = set().union(*(referenced_entries(manifest) for manifest in manifests))
    if not keep:
        raise ValueError("Refusing to prune: the manifests reference no cache entries")
    removed = []
    for entry in sorted((root / "cache").glob("*/*")):
        if (entry.parent.name, entry.name) not in keep:
            shutil.rmtree(entry)
            removed.append(entry)
    return removed
