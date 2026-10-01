#!/usr/bin/env python3
"""Offline native build adapter shared by Gradle, CI and F-Droid.

Gradle schedules producers; this adapter verifies source identities and handles
component-level local incrementality, including externally supplied source trees.
It never downloads inputs and never writes into a source directory.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import zipfile

from artifacts import verify_elf
from state import cached_build, digest, export_source, files, locked, prune_cache, publish_directory, run, sha256, source_identity

ROOT = Path(__file__).resolve().parents[2]
CONFIG = json.loads((ROOT / "native/toolchains.json").read_text())
RECIPES = ROOT / "native/mosh/recipes"


def generated_path(path: Path, state: bool = False) -> Path:
    """Keep generated files out of source trees.

    Outputs belong below build/, app/build/ or /tmp/. The component state may also
    live outside the checkout, such as on the WSL file system for a Windows build,
    but never at a file-system root or in a directory that contains the checkout.
    """
    path = path.resolve()
    if any(path.is_relative_to(base) and path != base for base in (ROOT / "build", ROOT / "app/build", Path("/tmp"))):
        return path
    if state and not path.is_relative_to(ROOT) and not ROOT.is_relative_to(path) and path != Path(path.anchor):
        return path
    where = "below build/, app/build/, /tmp/ or outside the checkout" if state else "below build/, app/build/ or /tmp/"
    raise ValueError(f"Generated path must be {where}: {path}")


def selected_abis(value: str) -> list[str]:
    values = value.replace(",", " ").split()
    if not values or len(values) != len(set(values)) or set(values) - set(CONFIG["abis"]):
        raise ValueError(f"Expected distinct ABIs from {CONFIG['abis']}, got {value!r}")
    return [abi for abi in CONFIG["abis"] if abi in values]


def source_roots() -> dict:
    deps = Path(os.environ.get("MANGOSSH_MOSH_DEPS_DIR", ROOT / ".fdroid/sources")).resolve()
    result = {}
    for line in (ROOT / "tools/fdroid-sources.lock").read_text().splitlines():
        if not line or line.startswith("#"):
            continue
        name, url, ref, commit = line.split("|")
        source = ROOT / "third_party/mosh4android" if name == "mosh4android" else deps / name
        result[name] = (source, commit)
    return result


def verified_sources(mode: str) -> dict:
    return {name: (path, source_identity(path, commit, mode)) for name, (path, commit) in source_roots().items()}


def tool_identity(names: list[str]) -> dict:
    result = {}
    for name in names:
        path = shutil.which(name)
        if not path:
            raise ValueError(f"Missing build tool: {name}; see docs/building.md")
        result[name] = {"sha256": sha256(Path(path).resolve()), "version": run(path, "--version").splitlines()[0]}
    return result


def ndk_home() -> Path:
    path = Path(os.environ.get("ANDROID_NDK_HOME", ROOT / f".tools/android-ndk-linux/{CONFIG['ndk']}")).resolve()
    props = path / "source.properties"
    if not props.exists() or not re.search(rf"^Pkg.Revision\s*=\s*{re.escape(CONFIG['ndk'])}\s*$", props.read_text(), re.M):
        raise ValueError(f"Prepare Android NDK {CONFIG['ndk']} first: {path}")
    return path


def deterministic_env() -> dict:
    env = dict(os.environ)
    for name in ("CC", "CXX", "CFLAGS", "CXXFLAGS", "CPPFLAGS", "LDFLAGS", "AR", "RANLIB", "MAKEFLAGS", "PKG_CONFIG_PATH", "PKG_CONFIG_LIBDIR", "CONFIG_SITE"):
        env.pop(name, None)
    env.update(LC_ALL="C", TZ="UTC", SOURCE_DATE_EPOCH=str(CONFIG["sourceDateEpoch"]),
               MANGOSSH_OFFLINE_BUILD="1", GOTOOLCHAIN="local", GOPROXY="off", GOSUMDB="off")
    return env


def execute(command: list, env: dict, log: Path):
    """Retain full per-component logs and show useful diagnostics on failure."""
    with log.open("w") as output:
        result = subprocess.run([str(arg) for arg in command], env=env, stdout=output, stderr=subprocess.STDOUT, cwd=ROOT)
    if result.returncode:
        print("\n".join(log.read_text(errors="replace").splitlines()[-80:]), file=sys.stderr)
        raise RuntimeError(f"Build failed ({result.returncode}); log: {log}")


def merge_installs(entries: list[Path], destination: Path):
    destination.mkdir()
    for entry in entries:
        for item in (entry / "install").iterdir():
            target = destination / item.name
            if item.is_dir():
                shutil.copytree(item, target, dirs_exist_ok=True, symlinks=True)
            else:
                shutil.copy2(item, target, follow_symlinks=False)
    # Installed .pc files contain their original prefix. A restored cache must
    # resolve libraries in this invocation's dependency sysroot, not that path.
    for pc in destination.rglob("*.pc"):
        pc.write_text(re.sub(r"^prefix=.*$", f"prefix={destination}", pc.read_text(), flags=re.M))


class MoshBuilder:
    def __init__(self, args):
        self.args = args
        self.sources = verified_sources(args.source_mode)
        self.ndk = ndk_home()
        self.llvm = self.ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
        self.host_tools = tool_identity(["bash", "cmake", "ninja", "cc", "c++", "make", "autoconf", "automake", "libtoolize", "bison", "gperf", "pkg-config", "rsync", "patch", "perl", "m4", "sed"])
        self.target_tools = {"ndk": CONFIG["ndk"], "clang": sha256((self.llvm / "clang").resolve()), "api": CONFIG["androidApi"]}
        self.adapter = {path.name: sha256(path) for path in (Path(__file__), ROOT / "tools/native/state.py", ROOT / "tools/native/artifacts.py")}

    def component(self, name: str, source: str, abi: str = "host", dependencies=()) -> Path:
        recipe = RECIPES / f"{name}.sh"
        identity = {"source": self.sources[source][1], "mode": self.args.source_mode,
                    "recipe": sha256(recipe), "adapter": self.adapter,
                    "hostTools": self.host_tools, "abi": abi,
                    "dependencies": [json.loads((dep / "manifest.json").read_text()) for dep in dependencies],
                    "epoch": CONFIG["sourceDateEpoch"]}
        if abi != "host":
            identity.update(target=self.target_tools, flags=sha256(RECIPES / "target-env.sh"))

        def build(work, install):
            sources = work / "sources"
            export_source(self.sources[source][0], sources / source, self.args.source_mode)
            dep_prefix = work / "dependencies"
            merge_installs(list(dependencies), dep_prefix)
            env = deterministic_env()
            env.update(WORK_DIR=str(work), BUILD_DIR=str(work / "build"), INSTALL_DIR=str(install),
                       SOURCES_DIR=str(sources), ROOT_DIR=str(sources / source), DEPS_PREFIX=str(dep_prefix),
                       ANDROID_NDK_HOME=str(self.ndk), ANDROID_API=str(CONFIG["androidApi"]),
                       ANDROID_PLATFORM=f"android-{CONFIG['androidApi']}", ABI=abi, NCPU=str(self.args.jobs),
                       PROTOC_BIN=str(dep_prefix / "bin/protoc"), MANGOSSH_TIC=str(dep_prefix / "bin/tic"))
            execute(["bash", recipe], env, work / "build.log")
            if name == "mosh":
                verify_elf(install / "mosh-client", abi, self.llvm / "llvm-readelf", executable=True)
        return cached_build(self.args.state, f"{name}-{abi}", identity, build)

    def build_abi(self, abi, protoc, tic):
        zlib = self.component("zlib", "zlib", abi)
        protobuf = self.component("protobuf", "protobuf", abi, [zlib])
        ncurses = self.component("ncurses", "ncurses", abi, [tic])
        nettle = self.component("nettle", "nettle", abi)
        terminfo_input = ncurses / "install/share/terminfo/x/xterm-256color"
        terminfo_identity = {"ncurses": json.loads((ncurses / "manifest.json").read_text()), "adapter": self.adapter}

        def terminfo(work, install):
            (install / "share").mkdir()
            with zipfile.ZipFile(install / "share/terminfo.zip", "w") as archive:
                info = zipfile.ZipInfo("share/terminfo/x/xterm-256color", (1980, 1, 1, 0, 0, 0))
                info.external_attr = 0o100644 << 16
                info.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(info, terminfo_input.read_bytes())
        terminfo_entry = cached_build(self.args.state, f"terminfo-{abi}", terminfo_identity, terminfo)
        return self.component("mosh", "mosh4android", abi, [zlib, protobuf, ncurses, nettle, protoc, terminfo_entry])

    def build(self):
        protoc = self.component("host-protoc", "protobuf")
        tic = self.component("host-tic", "ncurses")
        with concurrent.futures.ThreadPoolExecutor(max_workers=self.args.parallel) as pool:
            results = dict(zip(self.args.abis, pool.map(lambda abi: self.build_abi(abi, protoc, tic), self.args.abis)))
        # Recheck inputs before publishing; never label a concurrently edited tree
        # with the pre-build identity.
        if verified_sources(self.args.source_mode) != self.sources:
            raise ValueError("Sources changed during the build; rerun before packaging")
        package = self.args.state / "package-mosh"
        with locked(self.args.state / "locks/package-mosh.lock"):
            shutil.rmtree(package, ignore_errors=True)
            jni, assets, symbols = package / "jniLibs", package / "assets", package / "symbols"
            (assets / "mosh").mkdir(parents=True)
            terminfo = None
            for abi, entry in results.items():
                library = jni / abi / "libmosh_client.so"
                library.parent.mkdir(parents=True)
                shutil.copy2(entry / "install/mosh-client", library)
                (symbols / abi).mkdir(parents=True)
                shutil.copy2(entry / "install/mosh-client", symbols / abi / "mosh-client")
                subprocess.run([str(self.llvm / "llvm-strip"), "--strip-unneeded", str(library)], check=True)
                verify_elf(library, abi, self.llvm / "llvm-readelf", executable=True)
                data = (entry / "install/terminfo.zip").read_bytes()
                if terminfo is not None and data != terminfo:
                    raise ValueError("terminfo differs across ABIs")
                terminfo = data
            (assets / "mosh/terminfo.zip").write_bytes(terminfo)
            manifest = {"schema": 1, "component": "mosh", "abis": self.args.abis,
                        "sourceMode": self.args.source_mode, "sources": {n: identity for n, (_, identity) in self.sources.items()},
                        "components": {abi: json.loads((entry / "manifest.json").read_text()) for abi, entry in results.items()},
                        "jniLibs": files(jni), "assets": files(assets)}
            # Invalidate publication first; failed copies must not leave a valid manifest.
            self.args.manifest.unlink(missing_ok=True)
            publish_directory(jni, self.args.jni_dir)
            publish_directory(assets, self.args.assets_dir)
            publish_directory(symbols, self.args.symbols_dir)
            self.args.manifest.parent.mkdir(parents=True, exist_ok=True)
            self.args.manifest.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
        print(f"native: packaged Mosh for {', '.join(self.args.abis)}")


def build_tsnet(args):
    ndk = ndk_home()
    go_root = Path(os.environ.get("MANGOSSH_GO_ROOT", ROOT / ".tools/go/1.26.7")).resolve()
    java = Path(os.environ.get("JAVA_HOME", ""))
    if not java.is_absolute() or not (java / "bin/javac").is_file():
        raise ValueError("JAVA_HOME must provide a Linux JDK 17")
    java_version = run(java / "bin/javac", "-version", stderr=subprocess.STDOUT)
    if not java_version.startswith("javac 17."):
        raise ValueError(f"JDK 17 required, found {java_version}")
    sdk = Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", "")))
    android_jar = sdk / f"platforms/android-{CONFIG['androidApi']}/android.jar"
    if not android_jar.is_file():
        raise ValueError(f"Install SDK platform android-{CONFIG['androidApi']} before building tsnet")
    source = ROOT / "native/tsnetbridge"
    source_files = {name: value for name, value in files(source).items() if not name.startswith(".build/")}
    scripts = [ROOT / "tools/build-tsnet-android.sh", ROOT / "tools/normalize-tsnet-aar.py", ROOT / "tools/generate-tsnet-notices.py"]
    scripts += sorted((ROOT / "tools/lib").glob("*.sh"))
    scripts += sorted((ROOT / "tools/patches").glob("tailscale-*.patch"))
    scripts += [Path(__file__), ROOT / "tools/native/state.py", ROOT / "tools/native/artifacts.py"]
    identity = {"sources": source_files, "scripts": {str(p.relative_to(ROOT)): sha256(p) for p in scripts},
                "abis": args.abis, "config": CONFIG, "java": java_version,
                "javac": sha256(java / "bin/javac"), "jdkRelease": sha256(java / "release"),
                "go": sha256(go_root / "bin/go"), "ndk": sha256(ndk / "source.properties"),
                "clang": sha256((ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/clang").resolve()),
                "androidJar": sha256(android_jar), "hostTools": tool_identity(["bash", "python3", "git", "patch"])}

    def build(work, install):
        env = deterministic_env()
        env.update(ANDROID_NDK_HOME=str(ndk), MANGOSSH_GO_ROOT=str(go_root),
                   ABIS=" ".join(args.abis), MANGOSSH_TSNET_WORK_DIR=str(work / "gomobile"),
                   MANGOSSH_GOBIN=str(work / "go-bin"), MANGOSSH_TSNET_OUTPUT_DIR=str(install),
                   MANGOSSH_NATIVE_STATE=str(args.state),
                   MANGOSSH_ANDROID_API=str(CONFIG["androidApi"]))
        execute(["bash", ROOT / "tools/build-tsnet-android.sh"], env, work / "build.log")
        if {n: v for n, v in files(source).items() if not n.startswith(".build/")} != source_files:
            raise ValueError("tsnet sources changed during build")
        with zipfile.ZipFile(install / "mangossh-tsnet.aar") as archive:
            actual = {name.split("/")[1] for name in archive.namelist() if re.fullmatch(r"jni/[^/]+/libgojni\.so", name)}
            if actual != set(args.abis):
                raise ValueError(f"Unexpected tsnet ABI set: {actual}")
            for abi in args.abis:
                lib = work / f"{abi}.so"
                lib.write_bytes(archive.read(f"jni/{abi}/libgojni.so"))
                verify_elf(lib, abi, ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf")
    entry = cached_build(args.state, "tsnet", identity, build)
    with locked(args.state / "locks/package-tsnet.lock"):
        publish_directory(entry / "install", args.output_dir, {"manifest.json": entry / "manifest.json"})


def prune(args):
    """Drop cache entries the published Mosh/tsnet manifests no longer reference."""
    manifests = [json.loads(path.read_text()) for path in args.keep]
    with locked(args.state / "locks/prune.lock"):
        for entry in prune_cache(args.state, manifests):
            print(f"native: pruned {entry.parent.name} {entry.name[:12]}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("component", choices=["verify-sources", "mosh", "tsnet", "host-protoc", "host-tic", "prune"])
    parser.add_argument("--abis", default=os.environ.get("ABIS", " ".join(CONFIG["abis"])))
    parser.add_argument("--source-mode", choices=["locked", "worktree"], default=os.environ.get("MANGOSSH_NATIVE_SOURCE_MODE", "locked"))
    parser.add_argument("--state", type=Path, default=ROOT / "build/native")
    parser.add_argument("--jni-dir", type=Path, default=ROOT / "app/build/generated/native/mosh/jniLibs")
    parser.add_argument("--assets-dir", type=Path, default=ROOT / "app/build/generated/native/mosh/assets")
    parser.add_argument("--symbols-dir", type=Path, default=ROOT / "app/build/generated/native/mosh/symbols")
    parser.add_argument("--manifest", type=Path, default=ROOT / "app/build/generated/native/mosh/manifest.json")
    parser.add_argument("--output-dir", type=Path, default=ROOT / "app/build/generated/tsnet")
    parser.add_argument("--keep", type=Path, action="append", default=[],
                        help="prune: published manifest whose cache entries are kept (repeatable)")
    parser.add_argument("--jobs", type=int, default=int(os.environ.get("MANGOSSH_ABI_BUILD_JOBS", "2")))
    parser.add_argument("--parallel", type=int, default=int(os.environ.get("MANGOSSH_ABI_PARALLELISM", "2")))
    args = parser.parse_args()
    args.abis = selected_abis(args.abis)
    if not 1 <= args.parallel <= 4 or args.jobs < 1:
        raise ValueError("parallel must be 1..4 and jobs must be positive")
    for name in ("state", "jni_dir", "assets_dir", "symbols_dir", "manifest", "output_dir"):
        setattr(args, name, generated_path(getattr(args, name), state=name == "state"))
    if args.component == "verify-sources":
        for name, (_, identity) in verified_sources(args.source_mode).items():
            print(f"Verified {name}: {identity['commit']} dirty={identity['dirty']}")
        return
    if args.component == "prune":
        if not args.keep:
            raise ValueError("prune requires at least one --keep manifest")
        prune(args)
        return
    if platform.system() != "Linux" or platform.machine() != "x86_64":
        raise ValueError("Mosh/tsnet recipes require Linux x86_64 (Windows: use WSL)")
    if args.component == "tsnet":
        build_tsnet(args)
    else:
        builder = MoshBuilder(args)
        if args.component == "mosh":
            builder.build()
        else:
            source = "protobuf" if args.component == "host-protoc" else "ncurses"
            print(builder.component(args.component, source) / "install/bin" / ("protoc" if source == "protobuf" else "tic"))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, OSError, subprocess.CalledProcessError) as error:
        sys.exit(f"native: {error}")
