#!/usr/bin/env python3
"""Builds libsodium for the Apple, Linux and Windows targets `core` compiles to (ADR 0123, 0127), as static libraries Kotlin/Native links.

The native platforms' primitives are libsodium's (ML-KEM-768, X25519, Ed25519, Argon2id, HKDF-SHA256, AES-256-GCM,
SHA-256): one maintained, vendor-neutral C library, reached from Kotlin through cinterop, so the suite's native code is
Kotlin and the conformance suite runs on it as it runs on the JVM's BouncyCastle.

The source is the release tarball, checked against the SHA-256 below before anything is built. That hash was taken
from the tarball the 1.0.22 release publishes; its minisign signature verifies under libsodium's published key
(RWQf6LRCGA9i5wo9HfNnazrIs0NImEukX60BIekT/3jXngTz10KIA0QNHhbHH8sobMtntYu/Y2j+3BP3lzI/elViHDtShDBTEAI=, the key in the
`.minisig` beside the tarball names it) — check it with `minisign -Vm libsodium-1.0.22.tar.gz -P <key>` when moving
the pin. Only the slices the build uses are compiled: macOS arm64, iOS arm64 and the iOS simulator on arm64 by Xcode's
clang, and Linux arm64 and x86-64 and Windows x86-64 (mingw) by Kotlin/Native's own clang against the C library it
links the core against (`core/konan_toolchain.py` says why, and finds it).

On mingw, libsodium's CPU detection reads XCR0 only through the `_xgetbv` intrinsic (`HAVE__XGETBV`): its `configure`
refuses the inline-assembly path (`HAVE_AVX_ASM`) for Windows x64 by design, and clang accepts the intrinsic only with
`-mxsave`. Without it `sodium_runtime_has_avx()` is 0 on every CPU and AES-256-GCM refuses to run, silently — so the
mingw slice is compiled with `-mxsave` (it lets the compiler accept the intrinsic and emits no XSAVE of its own;
libsodium calls it only after CPUID said OSXSAVE), and the build fails unless `config.log` says the intrinsic was found
(measured 2026-11-10, cr-the-windows-app-over-the-native-core W5).

Usage: build_libsodium.py <out-dir>   (the Gradle build passes src/mobile/build/libsodium; idempotent)
"""
from __future__ import annotations

import hashlib
import os
import re
import shutil
import subprocess
import sys
import tarfile
import time
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import konan_toolchain  # noqa: E402

VERSION = "1.0.22"
URL = f"https://github.com/jedisct1/libsodium/releases/download/{VERSION}-RELEASE/libsodium-{VERSION}.tar.gz"
SHA256 = "adbdd8f16149e81ac6078a03aca6fc03b592b89ef7b5ed83841c086191be3349"

# slice -> (SDK, -arch, version flag, configure host)
SLICES = {
    "macos_arm64": ("macosx", "arm64", "-mmacosx-version-min=14.0", "aarch64-apple-darwin23"),
    "ios_arm64": ("iphoneos", "arm64", "-mios-version-min=17.0", "aarch64-apple-darwin23"),
    "ios_simulator_arm64": ("iphonesimulator", "arm64", "-mios-simulator-version-min=17.0", "aarch64-apple-darwin23"),
}


def sh(*args: str, cwd: Path | None = None, env: dict[str, str] | None = None) -> str:
    return subprocess.run(args, cwd=cwd, env=env, check=True, capture_output=True, text=True).stdout.strip()


def fetch(out: Path) -> Path:
    tarball = out / f"libsodium-{VERSION}.tar.gz"
    if not tarball.exists():
        out.mkdir(parents=True, exist_ok=True)
        part = tarball.with_suffix(".part")
        for attempt in range(1, 4):
            try:
                with urllib.request.urlopen(URL, timeout=120) as r, open(part, "wb") as f:
                    shutil.copyfileobj(r, f)
                break
            except OSError as e:  # GitHub's release CDN answers a 504 now and then
                if attempt == 3:
                    sys.exit(f"cannot download {URL}: {e}")
                time.sleep(5 * attempt)
        part.rename(tarball)
    digest = hashlib.sha256(tarball.read_bytes()).hexdigest()
    if digest != SHA256:
        tarball.unlink()
        sys.exit(f"libsodium-{VERSION}.tar.gz has SHA-256 {digest}, not the pinned {SHA256}: refused")
    return tarball


def unpack(out: Path, name: str, tarball: Path) -> tuple[Path, Path]:
    work = out / "work" / name
    shutil.rmtree(work, ignore_errors=True)
    work.mkdir(parents=True)
    with tarfile.open(tarball) as t:
        t.extractall(work, filter="data") if sys.version_info >= (3, 12) else t.extractall(work)
    return work, work / f"libsodium-{VERSION}"


def configure_and_install(src: Path, host: str, prefix: Path, env: dict[str, str]) -> None:
    sh("./configure", f"--host={host}", f"--prefix={prefix}", "--disable-shared", "--enable-static", "--with-pic",
       "--disable-dependency-tracking", cwd=src, env=env)
    sh("make", f"-j{os.cpu_count() or 4}", "install", cwd=src, env=env)


def build(out: Path, name: str, sdk: str, arch: str, version_flag: str, host: str, tarball: Path) -> None:
    prefix = out / name
    if (prefix / "lib/libsodium.a").exists():
        return
    work, src = unpack(out, name, tarball)
    sysroot = sh("xcrun", "--sdk", sdk, "--show-sdk-path")
    env = dict(os.environ)
    env["CC"] = sh("xcrun", "--sdk", sdk, "--find", "clang")
    env["CFLAGS"] = f"-O3 -arch {arch} -isysroot {sysroot} {version_flag}"
    env["LDFLAGS"] = f"-arch {arch} -isysroot {sysroot} {version_flag}"
    configure_and_install(src, host, prefix, env)
    shutil.rmtree(work)


# The line of config.log that says the mingw slice can read XCR0 (`configure`'s check and its result, two log lines).
XGETBV_FOUND = re.compile(r"checking if _xgetbv\(\) is available\n(?:configure:\d+: .*\n)*?configure:\d+: result: yes\n")
MINGW_FLAGS = ["-mxsave", "-DSODIUM_STATIC"]  # -mxsave: the module docstring; SODIUM_STATIC: no dllexport in the headers


def check_xgetbv(src: Path) -> None:
    """Fails the build unless libsodium's `configure` found `_xgetbv()`: without it the slice finds no AVX on any CPU."""
    log = (src / "config.log").read_text(errors="replace")
    if not XGETBV_FOUND.search(log):
        sys.exit(f"{src / 'config.log'} does not say `checking if _xgetbv() is available... yes`: this libsodium would "
                 "find no AVX on any CPU and refuse AES-256-GCM (is -mxsave in CFLAGS?)")


def build_konan(out: Path, name: str, tarball: Path) -> None:
    """A slice compiled by Kotlin/Native's own toolchain: Linux's and Windows'."""
    prefix = out / name
    if (prefix / "lib/libsodium.a").exists():
        return
    tc = konan_toolchain.for_slice(name)
    work, src = unpack(out, name, tarball)
    env = dict(os.environ)
    env["CC"] = str(tc.clang)
    env["AR"] = str(tc.ar)
    env["RANLIB"] = tc.ranlib
    env["CFLAGS"] = " ".join(["-O3", *tc.cflags(), *(MINGW_FLAGS if tc.windows else [])])
    env["LDFLAGS"] = " ".join(tc.ldflags())
    sh("./configure", f"--host={tc.triple}", f"--prefix={prefix}", "--disable-shared", "--enable-static", "--with-pic",
       "--disable-dependency-tracking", cwd=src, env=env)
    if tc.windows:
        check_xgetbv(src)
    sh("make", f"-j{os.cpu_count() or 4}", "install", cwd=src, env=env)
    if tc.windows and not (prefix / "lib/libsodium.a").exists():
        # libtool names a mingw static library `libsodium.lib`; cinterop's `staticLibraries` names `libsodium.a`
        shutil.copy2(prefix / "lib/libsodium.lib", prefix / "lib/libsodium.a")
    shutil.rmtree(work)


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    out = Path(sys.argv[1]).resolve()
    tarball = fetch(out)
    for name, (sdk, arch, version_flag, host) in SLICES.items():
        build(out, name, sdk, arch, version_flag, host, tarball)
        print(f"{name}: {out / name / 'lib/libsodium.a'}")
    for name in konan_toolchain.SLICES:
        build_konan(out, name, tarball)
        print(f"{name}: {out / name / 'lib/libsodium.a'}")


if __name__ == "__main__":
    main()
