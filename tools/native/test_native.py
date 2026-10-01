#!/usr/bin/env python3
"""Regression tests for source policy, cache invalidation and deterministic archives."""
import errno
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest import mock
import zipfile

from build import generated_path, javac_version, merge_installs, selected_abis
from state import (cached_build, digest, export_source, files, prune_cache, publish_directory,
                   replace_directory, run, source_identity, valid_entry)

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("normalizer", ROOT / "tools/normalize-tsnet-aar.py")
normalizer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(normalizer)


class NativeStateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="mangossh-native-test-")
        self.root = Path(self.temp.name)
        self.repo = self.root / "source"
        self.repo.mkdir()
        run("git", "init", "-q", self.repo)
        run("git", "-C", self.repo, "config", "user.email", "native-test@example.invalid")
        run("git", "-C", self.repo, "config", "user.name", "Native tests")
        (self.repo / "source.c").write_text("original\n")
        run("git", "-C", self.repo, "add", ".")
        run("git", "-C", self.repo, "commit", "-qm", "fixture")
        self.commit = run("git", "-C", self.repo, "rev-parse", "HEAD")

    def tearDown(self):
        self.temp.cleanup()

    def test_misspelled_source_mode_cannot_bypass_dirty_check(self):
        (self.repo / "source.c").write_text("local edit")
        with self.assertRaisesRegex(ValueError, "Unknown native source mode"):
            source_identity(self.repo, self.commit, "lockd")

    def test_locked_rejects_modified_untracked_deleted_and_staged_sources(self):
        for kind in ("modified", "untracked", "deleted", "staged"):
            with self.subTest(kind=kind):
                run("git", "-C", self.repo, "reset", "--hard", "-q", self.commit)
                run("git", "-C", self.repo, "clean", "-fdq")
                source = self.repo / "source.c"
                if kind == "deleted":
                    source.unlink()
                elif kind == "untracked":
                    (self.repo / "new.c").write_text("new")
                else:
                    source.write_text("changed")
                    if kind == "staged":
                        run("git", "-C", self.repo, "add", ".")
                with self.assertRaises(ValueError):
                    source_identity(self.repo, self.commit)

    def test_locked_accepts_crlf_checkout_and_exports_committed_bytes(self):
        # Reproduce a Windows core.autocrlf=true checkout inspected from WSL: the
        # index records CRLF sizes but other stat data differs, as across OSes.
        source = self.repo / "source.c"
        source.unlink()
        run("git", "-c", "core.autocrlf=true", "-C", self.repo, "checkout", "--", "source.c")
        self.assertEqual(source.read_bytes(), b"original\r\n")
        os.utime(source, ns=(source.stat().st_atime_ns, source.stat().st_mtime_ns + 5_000_000_000))
        self.assertTrue(run("git", "--no-optional-locks", "-C", self.repo, "status", "--porcelain"))
        self.assertFalse(source_identity(self.repo, self.commit)["dirty"])
        destination = self.root / "export"
        export_source(self.repo, destination, "locked")
        self.assertEqual((destination / "source.c").read_bytes(), b"original\n")
        (self.repo / "source.c").write_bytes(b"changed\r\n")
        with self.assertRaises(ValueError):
            source_identity(self.repo, self.commit)

    def test_worktree_exports_additions_edits_and_deletions(self):
        before = source_identity(self.repo, self.commit, "worktree")
        (self.repo / "source.c").unlink()
        (self.repo / "added.c").write_text("actual working source")
        after = source_identity(self.repo, self.commit, "worktree")
        self.assertNotEqual(digest(before), digest(after))
        destination = self.root / "export"
        export_source(self.repo, destination, "worktree")
        self.assertFalse((destination / "source.c").exists())
        self.assertEqual((destination / "added.c").read_text(), "actual working source")
        self.assertTrue(after["dirty"])

    def test_wrong_commit_is_rejected_even_in_development(self):
        with self.assertRaises(ValueError):
            source_identity(self.repo, "0" * 40, "worktree")

    def test_parent_repository_is_not_a_valid_uninitialized_source(self):
        child = self.repo / "missing-submodule"
        child.mkdir()
        with self.assertRaises(ValueError):
            source_identity(child, self.commit)

    def test_recursive_submodule_mismatch_and_dirty_content(self):
        parent = self.root / "parent"
        run("git", "clone", "-q", self.repo, parent)
        run("git", "-C", parent, "config", "user.email", "native-test@example.invalid")
        run("git", "-C", parent, "config", "user.name", "Native tests")
        run("git", "-c", "protocol.file.allow=always", "-C", parent, "submodule", "add", "-q", self.repo, "dep")
        run("git", "-C", parent, "commit", "-qam", "add submodule")
        expected = run("git", "-C", parent, "rev-parse", "HEAD")
        source_identity(parent, expected)
        (parent / "dep/source.c").write_text("modified dependency")
        with self.assertRaises(ValueError):
            source_identity(parent, expected)
        self.assertTrue(source_identity(parent, expected, "worktree")["dirty"])
        run("git", "-C", parent / "dep", "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qam", "mismatch")
        with self.assertRaises(ValueError):
            source_identity(parent, expected, "worktree")

    def test_cache_reuses_only_matching_identity_and_complete_outputs(self):
        calls = []

        def build(work, install):
            calls.append(1)
            (install / "lib.a").write_text("valid archive")

        identity = {"source": "one", "abi": "arm64-v8a"}
        entry = cached_build(self.root, "component", identity, build)
        self.assertTrue(valid_entry(entry, identity))
        self.assertEqual(entry, cached_build(self.root, "component", identity, build))
        self.assertEqual(len(calls), 1)
        (entry / "install/lib.a").write_text("corrupted")
        cached_build(self.root, "component", identity, build)
        self.assertEqual(len(calls), 2)
        (entry / "install/lib.a").unlink()
        cached_build(self.root, "component", identity, build)
        self.assertEqual(len(calls), 3)
        cached_build(self.root, "component", {**identity, "source": "two"}, build)
        self.assertEqual(len(calls), 4)

    def test_failure_never_publishes_success_manifest(self):
        identity = {"source": "bad"}

        def broken(work, install):
            (install / "incomplete.a").write_text("partial")
            raise RuntimeError("compiler failed")

        with self.assertRaises(RuntimeError):
            cached_build(self.root, "failure", identity, broken)
        self.assertFalse(valid_entry(self.root / "cache/failure" / digest(identity)))

    def test_switching_abi_set_removes_stale_outputs(self):
        source, output = self.root / "new", self.root / "old"
        (source / "arm64-v8a").mkdir(parents=True)
        (source / "arm64-v8a/lib.so").write_bytes(b"new")
        (output / "x86").mkdir(parents=True)
        (output / "x86/lib.so").write_bytes(b"old")
        publish_directory(source, output)
        self.assertEqual(files(source), files(output))
        self.assertFalse((output / "x86").exists())

    def test_unchanged_publication_with_manifest_is_not_copied_again(self):
        source, output, manifest = self.root / "install", self.root / "out", self.root / "manifest.json"
        source.mkdir()
        (source / "bridge.aar").write_bytes(b"aar")
        manifest.write_text("{}\n")
        publish_directory(source, output, {"manifest.json": manifest})
        self.assertEqual((output / "manifest.json").read_text(), "{}\n")
        # A Windows drive through WSL reports every published file as executable.
        (output / "bridge.aar").chmod(0o777)
        marker = (output / "bridge.aar").stat().st_ino, (output / "bridge.aar").stat().st_mtime_ns
        publish_directory(source, output, {"manifest.json": manifest})
        self.assertEqual(marker, ((output / "bridge.aar").stat().st_ino, (output / "bridge.aar").stat().st_mtime_ns))
        manifest.write_text('{"changed": true}\n')
        publish_directory(source, output, {"manifest.json": manifest})
        self.assertEqual((output / "manifest.json").read_text(), '{"changed": true}\n')

    def test_replace_directory_retries_transient_windows_access_denied(self):
        staging, destination = self.root / "entry.partial", self.root / "entry"
        staging.mkdir()
        (staging / "out").write_text("new")
        destination.mkdir()
        (destination / "out").write_text("old")
        rename, calls = Path.rename, []

        def flaky(path, target):
            calls.append(path)
            if len(calls) < 3:
                raise PermissionError(errno.EACCES, "held by a scanner")
            return rename(path, target)

        with mock.patch("state.time.sleep"), mock.patch.object(Path, "rename", flaky):
            replace_directory(staging, destination)
        self.assertEqual(len(calls), 3)
        self.assertEqual((destination / "out").read_text(), "new")
        self.assertFalse(staging.exists())

    def test_prune_keeps_only_entries_reachable_from_published_manifests(self):
        def build(work, install):
            (install / "out").write_text(work.name)

        dependency = cached_build(self.root, "zlib-x86", {"source": "zlib"}, build)
        old = cached_build(self.root, "zlib-x86", {"source": "old zlib"}, build)
        client_identity = {"dependencies": [json.loads((dependency / "manifest.json").read_text())]}
        client = cached_build(self.root, "mosh-x86", client_identity, build)
        stale = cached_build(self.root, "tsnet", {"source": "old bridge"}, build)
        (self.root / "cache/tsnet/partial.partial").mkdir()
        published = {"component": "mosh", "components": {"x86": json.loads((client / "manifest.json").read_text())}}
        prune_cache(self.root, [published])
        self.assertTrue(valid_entry(dependency) and valid_entry(client))
        self.assertFalse(old.exists() or stale.exists())
        self.assertFalse((self.root / "cache/tsnet/partial.partial").exists())
        with self.assertRaises(ValueError):
            prune_cache(self.root, [{"component": "mosh"}])

    def test_state_may_leave_checkout_but_outputs_and_sources_may_not(self):
        outside = Path("/var/cache/mangossh-native-test/state")  # Not created; outside /tmp.
        self.assertEqual(generated_path(outside, state=True), outside.resolve())
        self.assertEqual(generated_path(ROOT / "build/native", state=True), (ROOT / "build/native").resolve())
        for path in (outside, ROOT / "native/mosh", ROOT / "app/src/main/jniLibs"):
            with self.assertRaises(ValueError):
                generated_path(path)
        for path in (ROOT / "native/mosh", ROOT, ROOT.parent, Path("/")):
            with self.assertRaises(ValueError):
                generated_path(path, state=True)

    def test_javac_version_ignores_java_tool_options_banner(self):
        javac = self.root / "jdk/bin/javac"
        javac.parent.mkdir(parents=True)
        javac.write_text("#!/bin/sh\necho 'Picked up JAVA_TOOL_OPTIONS: -Dx=y' >&2\necho 'javac 17.0.20.1' >&2\n")
        javac.chmod(0o755)
        self.assertEqual(javac_version(self.root / "jdk"), "javac 17.0.20.1")

    def test_abi_selection_is_exact_and_canonical(self):
        self.assertEqual(selected_abis("x86_64,arm64-v8a"), ["arm64-v8a", "x86_64"])
        for value in ("", "arm64-v8a arm64-v8a", "mips", "../x86"):
            with self.assertRaises(ValueError):
                selected_abis(value)

    def test_nested_jar_timestamp_and_order_normalization(self):
        jars = []
        for year, names in [(2020, ["B.class", "A.class"]), (2026, ["A.class", "B.class"])]:
            stream = io.BytesIO()
            with zipfile.ZipFile(stream, "w") as archive:
                for name in names:
                    archive.writestr(zipfile.ZipInfo(name, (year, 1, 1, 0, 0, 0)), name.encode())
            jars.append(normalizer.normalize_nested_zip(stream.getvalue()))
        self.assertEqual(jars[0], jars[1])

    def test_protobuf_pkgconfig_survives_cache_relocation(self):
        import os
        entry = self.root / "cached"
        prefix = entry / "install"
        pcdir = prefix / "lib/pkgconfig"
        pcdir.mkdir(parents=True)
        (prefix / "lib/libprotobuf.a").write_bytes(b"archive fixture")
        (prefix / "include").mkdir()
        (pcdir / "protobuf.pc").write_text(
            f"prefix={prefix}\nexec_prefix={prefix}\nlibdir={prefix}/lib\nincludedir={prefix}/include\n"
            "Name: protobuf\nDescription: relocation fixture\nVersion: 29.1\n"
            "Libs: -L${libdir} -lprotobuf\nCflags: -I${includedir}\n"
        )
        # Exercise the recipe's existing-library path: metadata normalization
        # must run both after compilation and when adapting an installed prefix.
        env = dict(os.environ, ABI="arm64-v8a", ANDROID_NDK_HOME="/unused", ANDROID_API="26",
                   WORK_DIR=str(self.root), BUILD_DIR=str(self.root / "build"), INSTALL_DIR=str(prefix))
        subprocess.run(["bash", str(ROOT / "native/mosh/recipes/protobuf.sh")], env=env, check=True)
        relocated = self.root / "consumer-sysroot"
        merge_installs([entry], relocated)
        shutil.rmtree(entry)
        flags = run("pkg-config", "--cflags", "--libs", "protobuf",
                    env=dict(os.environ, PKG_CONFIG_LIBDIR=str(relocated / "lib/pkgconfig")))
        self.assertIn(f"-I{relocated}/include", flags)
        self.assertIn(f"-L{relocated}/lib", flags)
        self.assertNotIn(str(prefix), flags)


if __name__ == "__main__":
    unittest.main()
