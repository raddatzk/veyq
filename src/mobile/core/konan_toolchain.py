"""Kotlin/Native's own C toolchain for a Linux or a Windows (mingw) target, as the C libraries the core links are built with it.

A static library the core links for Linux must be compiled against the SAME old glibc Kotlin/Native links the core
against (its gcc toolchain's sysroot: glibc 2.25 for arm64, 2.19 for x86-64): one built on a current distribution would
pull versioned symbols the final link cannot satisfy. So libsodium's and utf8proc's Linux slices are compiled by
Kotlin/Native's LLVM (`clang`, `llvm-ar`, `lld`) against that sysroot (ADR 0123's Consequences, the core on Linux).

Nothing here is spelled: the Kotlin version is the version catalog's, the distribution is the one Gradle unpacked under
`$KONAN_DATA_DIR` (default `~/.konan`), and the LLVM and the gcc toolchain are what its `konan.properties` names
(`llvm.<host>.user`, `toolchainDependency.<target>`). Not a guard and not a script: imported by the two build scripts.

Windows (`mingw_x64`, ADR 0127 decision 5) is built the same way: Kotlin/Native's LLVM against the mingw-w64 toolchain
it links the core against (`toolchainDependency.mingw_x64`, an msys2 distribution that is its own sysroot,
`targetSysRoot.mingw_x64`). Kotlin/Native's LLVM carries no `llvm-ranlib`; `llvm-ar s` writes the same index.
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
# A Windows slice -> (Kotlin/Native target, configure triple: mingw-w64's, which libsodium's configure keys on).
MINGW_SLICES = {"mingw_x64": ("mingw_x64", "x86_64-w64-mingw32")}
# Every slice Kotlin/Native's own toolchain builds, in the order the scripts build them.
SLICES = LINUX_SLICES | MINGW_SLICES

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
    gcc_toolchain: Path | None  # Linux's gcc toolchain; mingw's sysroot is its whole toolchain

    @property
    def windows(self) -> bool:
        return self.gcc_toolchain is None

    def _base(self) -> list[str]:
        gcc = [] if self.gcc_toolchain is None else [f"--gcc-toolchain={self.gcc_toolchain}"]
        return [f"--target={self.triple}", f"--sysroot={self.sysroot}", *gcc]

    def cflags(self) -> list[str]:
        # position-independent code is ELF's question; a PE object is relocatable as it is
        return self._base() + ([] if self.windows else ["-fPIC"])

    def ldflags(self) -> list[str]:
        return self._base() + ["-fuse-ld=lld"]

    @property
    def ranlib(self) -> str:
        return f"{self.ar} s"  # the distribution carries no llvm-ranlib; `llvm-ar s` writes the same index


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


def for_slice(slice_name: str) -> Toolchain:
    """The toolchain for one slice of [SLICES]; exits naming the Gradle task to run when Kotlin/Native has not
    downloaded it."""
    target, triple = SLICES[slice_name]
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
    if slice_name in MINGW_SLICES:
        tc = Toolchain(triple, llvm / "bin" / "clang", llvm / "bin" / "llvm-ar",
                       deps / _resolve(props, f"targetSysRoot.{target}"), None)
    else:
        tc = Toolchain(triple, llvm / "bin" / "clang", llvm / "bin" / "llvm-ar", gcc / triple / "sysroot", gcc)
    missing = [p for p in (tc.clang, tc.ar, tc.sysroot) if not p.exists()]
    if missing:
        sys.exit(f"Kotlin/Native's toolchain for {target} is not downloaded ({', '.join(map(str, missing))}): "
                 f"run `{DOWNLOAD_TASK}` first")
    return tc
