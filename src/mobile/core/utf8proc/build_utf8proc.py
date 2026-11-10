#!/usr/bin/env python3
"""Builds utf8proc for the Linux targets `core` compiles to, as static libraries Kotlin/Native links, and fetches the
Unicode NormalizationTest.txt of the version it implements.

Linux's `nfc`/`nfkd` are utf8proc's (CR core-as-a-native-library D3): JuliaStrings' maintained, MIT-licensed C library,
one C file plus its tables, reached from Kotlin through cinterop (`utf8proc_NFC`, `utf8proc_NFKD`). Its Linux slices are
compiled by Kotlin/Native's own clang against the glibc it links the core against (`core/konan_toolchain.py` says why).

Both downloads are refused unless they match the SHA-256 below:
- the source is GitHub's tag tarball of v2.11.3 (https://github.com/JuliaStrings/utf8proc/releases/tag/v2.11.3,
  2025-12-30); the hash was taken from that tarball as downloaded on 2026-11-10 (GitHub keeps tag archives
  byte-stable). utf8proc 2.11.3 implements Unicode 17.0.0 (`utf8proc_unicode_version()`), so
- the conformance file is Unicode's own NormalizationTest.txt for 17.0.0 (its header: `NormalizationTest-17.0.0.txt`,
  dated 2025-06-30), hashed on the same day. `NormalizationTest` (linuxTest) checks every line of it.
Moving utf8proc moves both pins: the Unicode version is the one its `utf8proc_unicode_version()` returns.

Usage: build_utf8proc.py <out-dir>   (the Gradle build passes src/mobile/build/utf8proc; idempotent)
"""
from __future__ import annotations

import hashlib
import shutil
import subprocess
import sys
import tarfile
import time
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import konan_toolchain  # noqa: E402

VERSION = "2.11.3"
URL = f"https://github.com/JuliaStrings/utf8proc/archive/refs/tags/v{VERSION}.tar.gz"
SHA256 = "abfed50b6d4da51345713661370290f4f4747263ee73dc90356299dfc7990c78"

UNICODE = "17.0.0"
TEST_URL = f"https://www.unicode.org/Public/{UNICODE}/ucd/NormalizationTest.txt"
TEST_SHA256 = "5019ffd530751a741900c849c0e010332f142a3612234639bd200b82138a87db"


def fetch(url: str, dest: Path, sha256: str) -> Path:
    if dest.exists() and hashlib.sha256(dest.read_bytes()).hexdigest() != sha256:
        dest.unlink()  # a file kept from an earlier pin: fetched anew, and checked below
    if not dest.exists():
        dest.parent.mkdir(parents=True, exist_ok=True)
        part = dest.with_suffix(dest.suffix + ".part")
        for attempt in range(1, 4):
            try:
                with urllib.request.urlopen(url, timeout=120) as r, open(part, "wb") as f:
                    shutil.copyfileobj(r, f)
                break
            except OSError as e:  # GitHub's CDN answers a 504 now and then
                if attempt == 3:
                    sys.exit(f"cannot download {url}: {e}")
                time.sleep(5 * attempt)
        part.rename(dest)
    digest = hashlib.sha256(dest.read_bytes()).hexdigest()
    if digest != sha256:
        dest.unlink()
        sys.exit(f"{dest.name} has SHA-256 {digest}, not the pinned {sha256}: refused")
    return dest


def build_linux(out: Path, name: str, tarball: Path) -> None:
    prefix = out / name
    if (prefix / "lib/libutf8proc.a").exists():
        return
    tc = konan_toolchain.linux(name)
    work = out / "work" / name
    shutil.rmtree(work, ignore_errors=True)
    work.mkdir(parents=True)
    with tarfile.open(tarball) as t:
        t.extractall(work, filter="data") if sys.version_info >= (3, 12) else t.extractall(work)
    src = work / f"utf8proc-{VERSION}"
    obj = work / "utf8proc.o"
    # UTF8PROC_STATIC and hidden visibility: the library is linked into the core, never exported from it.
    subprocess.run([str(tc.clang), "-O2", "-std=c99", "-DUTF8PROC_STATIC", "-fvisibility=hidden", *tc.cflags(),
                    "-c", str(src / "utf8proc.c"), "-o", str(obj)], check=True)
    (prefix / "lib").mkdir(parents=True, exist_ok=True)
    (prefix / "include").mkdir(parents=True, exist_ok=True)
    subprocess.run([str(tc.ar), "rcs", str(prefix / "lib/libutf8proc.a"), str(obj)], check=True)
    shutil.copy2(src / "utf8proc.h", prefix / "include/utf8proc.h")
    shutil.copy2(src / "LICENSE.md", prefix / "LICENSE.md")
    shutil.rmtree(work)


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    out = Path(sys.argv[1]).resolve()
    tarball = fetch(URL, out / f"utf8proc-{VERSION}.tar.gz", SHA256)
    test = fetch(TEST_URL, out / "NormalizationTest.txt", TEST_SHA256)
    for name in konan_toolchain.LINUX_SLICES:
        build_linux(out, name, tarball)
        print(f"{name}: {out / name / 'lib/libutf8proc.a'}")
    print(f"Unicode {UNICODE}: {test}")


if __name__ == "__main__":
    main()
