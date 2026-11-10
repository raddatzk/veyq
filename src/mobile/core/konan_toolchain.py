"""Kotlin/Native's own C toolchain for a Linux target, as the C libraries the core links are built with it.

A static library the core links for Linux must be compiled against the SAME old glibc Kotlin/Native links the core
against (its gcc toolchain's sysroot: glibc 2.25 for arm64, 2.19 for x86-64): one built on a current distribution would
pull versioned symbols the final link cannot satisfy. So libsodium's and utf8proc's Linux slices are compiled by
Kotlin/Native's LLVM (`clang`, `llvm-ar`, `lld`) against that sysroot (ADR 0123's Consequences, the core on Linux).

Nothing here is spelled: the Kotlin version is the version catalog's, the distribution is the one Gradle unpacked under
`$KONAN_DATA_DIR` (default `~/.konan`), and the LLVM and the gcc toolchain are what its `konan.properties` names
(`llvm.<host>.user`, `toolchainDependency.<target>`). Not a guard and not a script: imported by the two build scripts.
"""
from __future__ import annotations

import os
import platform
import re
import sys
from dataclasses import dataclass
from pathlib import Path

MOBILE = Path(__file__).resolve().parents[1]
CATALOG = MOBILE / "gradle" / "libs.versions.toml"

# Kotlin/Native's name for this build host, in `konan.properties` keys and in its distribution's directory name.
_HOSTS = {("Darwin", "arm64"): ("macos_arm64", "macos-aarch64"), ("Darwin", "x86_64"): ("macos_x64", "macos-x86_64"),
          ("Linux", "x86_64"): ("linux_x64", "linux-x86_64")}

# A Linux slice of build/<library>/ -> (Kotlin/Native target, configure triple).
LINUX_SLICES = {"linux_arm64": ("linux_arm64", "aarch64-unknown-linux-gnu"),
                "linux_x64": ("linux_x64", "x86_64-unknown-linux-gnu")}

# The Gradle task that unpacks Kotlin/Native and downloads its dependencies for every target of `core`, the Linux
# toolchains among them (measured 2026-11-10 against an empty KONAN_DATA_DIR). `buildLibsodium` and `buildUtf8proc`
# depend on it, so a Gradle build never gets here without it; a person running a script by hand runs it first.
DOWNLOAD_TASK = "./gradlew :core:downloadKotlinNativeDistribution"


@dataclass(frozen=True)
class Toolchain:
    triple: str
    clang: Path
    ar: Path
    sysroot: Path
    gcc_toolchain: Path

    def cflags(self) -> list[str]:
        return [f"--target={self.triple}", f"--sysroot={self.sysroot}", f"--gcc-toolchain={self.gcc_toolchain}", "-fPIC"]

    def ldflags(self) -> list[str]:
        return [f"--target={self.triple}", f"--sysroot={self.sysroot}", f"--gcc-toolchain={self.gcc_toolchain}", "-fuse-ld=lld"]


def kotlin_version() -> str:
    m = re.search(r'^kotlin\s*=\s*"([^"]+)"', CATALOG.read_text(), re.MULTILINE)
    if not m:
        sys.exit(f"{CATALOG}: no `kotlin = \"…\"` version")
    return m.group(1)


def _properties(path: Path) -> dict[str, str]:
    """`konan.properties`: Java properties with `\\` continuations; `$key` references resolved on read."""
    raw: dict[str, str] = {}
    lines = path.read_text().splitlines()
    i = 0
    while i < len(lines):
        line = lines[i].strip()
        i += 1
        if not line or line.startswith("#") or "=" not in line:
            continue
        while line.endswith("\\") and i < len(lines):
            line = line[:-1] + " " + lines[i].strip()
            i += 1
        key, _, value = line.partition("=")
        raw[key.strip()] = value.strip()
    return raw


def _resolve(props: dict[str, str], key: str) -> str:
    value = props.get(key)
    if value is None:
        sys.exit(f"konan.properties has no `{key}`")
    return re.sub(r"\$([\w.]+)", lambda m: _resolve(props, m.group(1)), value)


def linux(slice_name: str) -> Toolchain:
    """The toolchain for one Linux slice; exits naming the Gradle task to run when Kotlin/Native has not downloaded it."""
    target, triple = LINUX_SLICES[slice_name]
    try:
        host, host_dir = _HOSTS[(platform.system(), platform.machine())]
    except KeyError:
        sys.exit(f"no Kotlin/Native host for {platform.system()} {platform.machine()}")
    data = Path(os.environ.get("KONAN_DATA_DIR", Path.home() / ".konan"))
    version = kotlin_version()
    props_file = data / f"kotlin-native-prebuilt-{host_dir}-{version}" / "konan" / "konan.properties"
    if not props_file.exists():
        sys.exit(f"Kotlin/Native {version} is not unpacked at {props_file.parent.parent}: run `{DOWNLOAD_TASK}` first")
    props = _properties(props_file)
    deps = data / "dependencies"
    llvm = deps / _resolve(props, f"llvm.{host}.user")
    gcc = deps / _resolve(props, f"toolchainDependency.{target}")
    sysroot = gcc / triple / "sysroot"
    tc = Toolchain(triple, llvm / "bin" / "clang", llvm / "bin" / "llvm-ar", sysroot, gcc)
    missing = [p for p in (tc.clang, tc.ar, tc.sysroot) if not p.exists()]
    if missing:
        sys.exit(f"Kotlin/Native's toolchain for {target} is not downloaded ({', '.join(map(str, missing))}): "
                 f"run `{DOWNLOAD_TASK}` first")
    return tc
