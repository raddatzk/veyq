#!/usr/bin/env python3
"""Builds libsodium for the Apple targets `core` compiles to (ADR 0123), as static libraries Kotlin/Native links.

The Apple platforms' primitives are libsodium's (ML-KEM-768, X25519, Ed25519, Argon2id, HKDF-SHA256, AES-256-GCM,
SHA-256): one maintained, vendor-neutral C library, reached from Kotlin through cinterop, so the suite's Apple code is
Kotlin and the conformance suite runs on it as it runs on the JVM's BouncyCastle.

The source is the release tarball, checked against the SHA-256 below before anything is built. That hash was taken
from the tarball the 1.0.22 release publishes; its minisign signature verifies under libsodium's published key
(RWQf6LRCGA9i5wo9HfNnazrIs0NImEukX60BIekT/3jXngTz10KIA0QNHhbHH8sobMtntYu/Y2j+3BP3lzI/elViHDtShDBTEAI=, the key in the
`.minisig` beside the tarball names it) — check it with `minisign -Vm libsodium-1.0.22.tar.gz -P <key>` when moving
the pin. Only the three slices the build uses are compiled: macOS arm64, iOS arm64 and the iOS simulator on arm64.

Usage: build_libsodium.py <out-dir>   (the Gradle build passes src/mobile/build/libsodium; idempotent)
"""
from __future__ import annotations

import hashlib
import os
import shutil
import subprocess
import sys
import tarfile
import time
import urllib.request
from pathlib import Path

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


def build(out: Path, name: str, sdk: str, arch: str, version_flag: str, host: str, tarball: Path) -> None:
    prefix = out / name
    if (prefix / "lib/libsodium.a").exists():
        return
    work = out / "work" / name
    shutil.rmtree(work, ignore_errors=True)
    work.mkdir(parents=True)
    with tarfile.open(tarball) as t:
        t.extractall(work, filter="data") if sys.version_info >= (3, 12) else t.extractall(work)
    src = work / f"libsodium-{VERSION}"
    sysroot = sh("xcrun", "--sdk", sdk, "--show-sdk-path")
    env = dict(os.environ)
    env["CC"] = sh("xcrun", "--sdk", sdk, "--find", "clang")
    env["CFLAGS"] = f"-O3 -arch {arch} -isysroot {sysroot} {version_flag}"
    env["LDFLAGS"] = f"-arch {arch} -isysroot {sysroot} {version_flag}"
    sh("./configure", f"--host={host}", f"--prefix={prefix}", "--disable-shared", "--enable-static", "--with-pic",
       "--disable-dependency-tracking", cwd=src, env=env)
    sh("make", f"-j{os.cpu_count() or 4}", "install", cwd=src, env=env)
    shutil.rmtree(work)


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    out = Path(sys.argv[1]).resolve()
    tarball = fetch(out)
    for name, (sdk, arch, version_flag, host) in SLICES.items():
        build(out, name, sdk, arch, version_flag, host, tarball)
        print(f"{name}: {out / name / 'lib/libsodium.a'}")


if __name__ == "__main__":
    main()
