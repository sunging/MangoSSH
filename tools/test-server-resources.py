"""Run Linux resource-command regressions with GNU tools and BusyBox on Linux/WSL.

Only synthetic mount tables and isolated temporary files are changed. Production
scripts are extracted unchanged except for redirecting the two mount-table inputs.
No mounts are created, and neither remote output nor personal data is logged.
"""
import importlib.util
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("resource_fixture", Path(__file__).with_name("server-resource-fixture.py"))
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)
SCRIPT = shlex.split(fixture.commands()[0])[2]
FILESYSTEMS = "ext4\nxfs\nsquashfs\nnodev\ttmpfs\nnodev\tproc\nnodev\tnfs\nnodev\tcifs\nnodev\toverlay\nnodev\tzfs\n"


class ResourceDiskCommandsTest(unittest.TestCase):
    def setUp(self):
        self.workspace = tempfile.TemporaryDirectory(prefix="mangossh-resource-test-")
        self.root = Path(self.workspace.name)
        self.addCleanup(self.workspace.cleanup)
        self.busybox = shutil.which("busybox")
        self.assertIsNotNone(self.busybox, "BusyBox is required to validate Alpine-compatible commands")

    def mount(self, name, kind="ext4", exists=True):
        target = self.root / name
        if exists:
            target.mkdir(exist_ok=True)
        encoded = str(target).replace("\\", "\\134").replace(" ", "\\040").replace("\t", "\\011").replace("\n", "\\012")
        return (target, f"/dev/example {encoded} {kind} rw 0 0\n")

    def run_command(self, mounts, mode, missing_table=False, native=False, df_exit=0):
        bin_dir = self.root / mode
        bin_dir.mkdir(exist_ok=True)
        fs = self.root / "filesystems"
        table = self.root / "mounts"
        fs.write_text(FILESYSTEMS)
        table.write_text("".join(row for _, row in mounts))
        args_file = self.root / f"{mode}-arguments"
        df = bin_dir / "df"
        if not df.exists():
            if native:
                df.symlink_to(self.busybox if mode == "busybox" else shutil.which("df"))
            else:
                df.write_text("#!/bin/sh\n" +
                    "printf '%s\\0' \"$@\" > \"$RESOURCE_TEST_ARGS\"\n" +
                    "[ \"$1\" = -P ] && [ \"$2\" = -k ] || exit 2\nshift 2\n" +
                    "printf 'Filesystem 1024-blocks Used Available Capacity Mounted on\\n'\n" +
                    "for target; do printf '/dev/example 100 60 30 67%% %s\\n' \"$target\"; done\n" +
                    f"exit {df_exit}\n")
                df.chmod(0o700)
            (bin_dir / "awk").symlink_to(self.busybox if mode == "busybox" else shutil.which("awk"))
        script = SCRIPT.replace("/proc/filesystems", shlex.quote(str(fs)))
        script = script.replace("/proc/self/mounts", shlex.quote(str(table if not missing_table else self.root / "missing-table")))
        # Standalone BusyBox ash can bypass PATH for applets; explicit wrappers keep the
        # synthetic df and the intended awk implementation under test in both shells.
        script = 'df() { "$RESOURCE_TEST_DF" "$@"; }; awk() { "$RESOURCE_TEST_AWK" "$@"; };\n' + script
        env = dict(os.environ, PATH=str(bin_dir) + os.pathsep + os.environ["PATH"], RESOURCE_TEST_ARGS=str(args_file),
                   RESOURCE_TEST_DF=str(df), RESOURCE_TEST_AWK=str(bin_dir / "awk"))
        argv = ([self.busybox, "sh"] if mode == "busybox" else ["sh"]) + ["-c", script]
        result = subprocess.run(argv, env=env, capture_output=True, timeout=10)
        self.assertEqual(0, result.returncode)
        output = result.stdout.decode()
        disk, status = output.split("@@disk\n", 1)[1].split("@@disk_status\n", 1)
        args = args_file.read_bytes().decode().rstrip("\0").split("\0") if args_file.exists() else []
        return status.splitlines()[0], disk.splitlines(), args

    def test_filters_network_and_temporary_mounts_before_querying(self):
        mounts = [self.mount(name, kind) for name, kind in (
            ("disk", "ext4"), ("remote", "nfs"), ("windows-share", "cifs"), ("temporary", "tmpfs"),
            ("kernel", "proc"), ("image", "squashfs"), ("union", "overlay"), ("dataset", "zfs"))]
        for mode in ("gnu", "busybox"):
            with self.subTest(mode=mode):
                status, _, args = self.run_command(mounts, mode)
                self.assertEqual("0", status)
                self.assertEqual(["-P", "-k"] + [str(mounts[i][0]) for i in (0, 6, 7)], args)

    def test_mount_names_remain_literal_arguments(self):
        mounts = [self.mount(name) for name in ("data volume", "tab\tvolume", "slash\\volume", "quote'volume", "$(printf injected)", "trailing\n")]
        for mode in ("gnu", "busybox"):
            with self.subTest(mode=mode):
                status, _, args = self.run_command(mounts, mode)
                self.assertEqual("0", status)
                self.assertEqual(["-P", "-k"] + [str(target) for target, _ in mounts], args)

    def test_stale_overlay_and_duplicate_mount_entries_do_not_fail_disks(self):
        disk = self.mount("disk")
        mounts = [self.mount("stale-overlay", "overlay", exists=False), disk, disk, self.mount("live-overlay", "overlay")]
        for mode in ("gnu", "busybox"):
            with self.subTest(mode=mode):
                status, _, args = self.run_command(mounts, mode)
                self.assertEqual("0", status)
                self.assertEqual(["-P", "-k", str(disk[0]), str(mounts[-1][0])], args)

    def test_no_local_disks_is_successful_empty_data(self):
        mounts = [self.mount("remote", "nfs")]
        for mode in ("gnu", "busybox"):
            with self.subTest(mode=mode):
                status, disk, args = self.run_command(mounts, mode)
                self.assertEqual("0", status)
                self.assertEqual(1, len(disk))
                self.assertEqual([], args)

    def test_unreadable_mount_table_is_not_successful_empty_data(self):
        for mode in ("gnu", "busybox"):
            with self.subTest(mode=mode):
                status, _, args = self.run_command([], mode, missing_table=True)
                self.assertNotEqual("0", status)
                self.assertEqual([], args)

    def test_failed_capacity_query_retains_failure_status(self):
        mounts = [self.mount("disk")]
        for mode in ("gnu", "busybox"):
            with self.subTest(mode=mode):
                status, _, _ = self.run_command(mounts, mode, df_exit=1)
                self.assertEqual("1", status)

    def test_real_gnu_and_busybox_df_accept_the_fixed_options(self):
        mounts = [(Path("/"), "/dev/example / ext4 rw 0 0\n")]
        for mode in ("gnu", "busybox"):
            with self.subTest(mode=mode):
                status, disk, _ = self.run_command(mounts, mode, native=True)
                self.assertEqual("0", status)
                self.assertTrue(any(re.search(r"\s\d+\s+\d+\s+\d+\s+\d+%\s+/\s*$", row) for row in disk[1:]))


if __name__ == "__main__":
    unittest.main()
