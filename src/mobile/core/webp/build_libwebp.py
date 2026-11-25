#!/usr/bin/env python3
"""Builds libwebp for every native target `core` compiles to, as one static library Kotlin/Native links.

A member's picture is a WebP (ADR 0127). Windows decodes WebP only through the Store's WebP Image Extension, which a
person can remove (ADR 0139 decision 6), so the core decodes the picture itself where the platform cannot
(`media/WebpPicture.kt`, `Picture.Decode` on the bridge); and the core ENCODES one's own picture on every native target
(`media/LibwebpEncoder.kt`, ADR 0148 decision 2: Apple's ImageIO writes no WebP). The library is libwebp's own (the WebM
project's, maintained, BSD-3-Clause with Google's patent grant: `COPYING` and `PATENTS` land beside the library):
`makefile.unix`'s `LIBWEBP_OBJS` (the decoder and the encoder) with `LIBSHARPYUV_OBJS` (which the encoder's colour
conversion calls), read from the source, never listed here. The Linux and Windows slices are compiled by Kotlin/Native's
own clang against the C library it links the core against (`core/konan_toolchain.py` says why); the Apple slices by
Xcode's clang for each SDK (`xcrun`), as `build_libsodium.py` builds libsodium's.

**One libwebp in the repository.** The tag and its commit are the web encoder's pin (`src/web/webp-encoder/libwebp.pin`,
ADR 0127 decision 2), read from there: ADR 0127's weekly workflow moves that pin, and this build follows it. The tag is
cloned and refused unless it is the pinned commit (git checks the tree against it), as the encoder's build does. A slice
already built from that commit is kept (`commit` beside it); one built from another is built anew.

Usage: build_libwebp.py <out-dir>   (the Gradle build passes src/mobile/build/libwebp; idempotent)
"""
from __future__ import annotations

import re
import shutil
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import konan_toolchain  # noqa: E402

UPSTREAM = "https://github.com/webmproject/libwebp"
PIN = Path(__file__).resolve().parents[3] / "web" / "webp-encoder" / "libwebp.pin"
HEADERS = ("decode.h", "encode.h", "types.h")
LIBRARY = "libwebp.a"

# An Apple slice -> (SDK, -arch, the deployment floor): `build_libsodium.py`'s, the core's Apple targets.
APPLE_SLICES = {
    "macos_arm64": ("macosx", "arm64", "-mmacosx-version-min=14.0"),
    "ios_arm64": ("iphoneos", "arm64", "-mios-version-min=17.0"),
    "ios_simulator_arm64": ("iphonesimulator", "arm64", "-mios-simulator-version-min=17.0"),
    "tvos_arm64": ("appletvos", "arm64", "-mtvos-version-min=17.0"),
    "tvos_simulator_arm64": ("appletvsimulator", "arm64", "-mtvos-simulator-version-min=17.0"),
}


def read_pin() -> tuple[str, str]:
    lines = {}
    for raw in PIN.read_text().splitlines():
        parts = raw.split()
        if len(parts) == 2 and parts[0] in ("tag", "commit"):
            lines[parts[0]] = parts[1]
    if not re.fullmatch(r"v\d+\.\d+\.\d+", lines.get("tag", "")) or not re.fullmatch(r"[0-9a-f]{40}", lines.get("commit", "")):
        sys.exit(f"{PIN}: no stable `tag vX.Y.Z` and 40-hex `commit` line")
    return lines["tag"], lines["commit"]


def fetch(out: Path, tag: str, commit: str) -> Path:
    src = out / "src"
    if src.is_dir():
        head = subprocess.run(["git", "-C", str(src), "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
        if head == commit:
            return src
        shutil.rmtree(src)
    out.mkdir(parents=True, exist_ok=True)
    subprocess.run(["git", "-c", "advice.detachedHead=false", "clone", "--quiet", "--depth", "1", "--branch", tag, UPSTREAM,
                    str(src)], check=True)
    head = subprocess.run(["git", "-C", str(src), "rev-parse", "HEAD"], check=True, capture_output=True, text=True).stdout.strip()
    if head != commit:
        shutil.rmtree(src)
        sys.exit(f"libwebp {tag} is commit {head}, not the pinned {commit}: refused")
    return src


def library_sources(src: Path) -> list[Path]:
    """`makefile.unix`'s LIBWEBP_OBJS and LIBSHARPYUV_OBJS, each `.o` as the `.c` it is built from."""
    text = src.joinpath("makefile.unix").read_text().replace("\\\n", " ")
    variables = {m.group(1): m.group(2).split() for m in re.finditer(r"^([A-Z_]+)\s*=\s*(.*)$", text, re.MULTILINE)}

    def expand(name: str) -> list[str]:
        items: list[str] = []
        for item in variables.get(name) or sys.exit(f"makefile.unix: no {name}"):
            ref = re.fullmatch(r"\$\(([A-Z_]+)\)", item)
            items += expand(ref.group(1)) if ref else [item]
        return items

    sources = [src / (o.removesuffix(".o") + ".c") for o in expand("LIBWEBP_OBJS") + expand("LIBSHARPYUV_OBJS")]
    missing = [s for s in sources if not s.is_file()]
    if missing or not sources:
        sys.exit(f"makefile.unix names sources that are not there: {missing}")
    return sources


def build_slice(out: Path, name: str, src: Path, commit: str, compile_cmd: list[str], ar_cmd: list[str]) -> None:
    """One slice: every source compiled by [compile_cmd] (a compiler and its flags), archived by [ar_cmd]."""
    prefix = out / name
    stamp = prefix / "commit"
    if (prefix / "lib" / LIBRARY).exists() and stamp.is_file() and stamp.read_text().strip() == commit:
        return
    shutil.rmtree(prefix, ignore_errors=True)
    work = out / "work" / name
    shutil.rmtree(work, ignore_errors=True)
    work.mkdir(parents=True)
    objects = []
    for c in library_sources(src):
        obj = work / (c.relative_to(src).as_posix().replace("/", "_").removesuffix(".c") + ".o")
        # WEBP_EXTERN plain extern with hidden visibility (ELF) and no dllexport (PE): linked into the core, never
        # exported from it. No WEBP_USE_THREAD: one picture at a time needs no worker thread.
        subprocess.run([*compile_cmd, "-O2", "-fvisibility=hidden", "-DWEBP_EXTERN=extern", "-I", str(src),
                        "-c", str(c), "-o", str(obj)], check=True)
        objects.append(str(obj))
    (prefix / "lib").mkdir(parents=True, exist_ok=True)
    (prefix / "include/webp").mkdir(parents=True, exist_ok=True)
    subprocess.run([*ar_cmd, str(prefix / "lib" / LIBRARY), *objects], check=True)
    for h in HEADERS:
        shutil.copy2(src / "src/webp" / h, prefix / "include/webp" / h)
    for notice in ("COPYING", "PATENTS"):
        shutil.copy2(src / notice, prefix / notice)
    stamp.write_text(commit + "\n")
    shutil.rmtree(work)


def build_konan(out: Path, name: str, src: Path, commit: str) -> None:
    tc = konan_toolchain.for_slice(name)
    build_slice(out, name, src, commit, [str(tc.clang), *tc.cflags()], [str(tc.ar), "rcs"])


def xcrun(*args: str) -> str:
    return subprocess.run(["xcrun", *args], check=True, capture_output=True, text=True).stdout.strip()


def build_apple(out: Path, name: str, src: Path, commit: str) -> None:
    sdk, arch, floor = APPLE_SLICES[name]
    clang = xcrun("--sdk", sdk, "--find", "clang")
    build_slice(out, name, src, commit, [clang, "-arch", arch, "-isysroot", xcrun("--sdk", sdk, "--show-sdk-path"), floor],
                [xcrun("--sdk", sdk, "--find", "ar"), "rcs"])


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    out = Path(sys.argv[1]).resolve()
    tag, commit = read_pin()
    src = fetch(out, tag, commit)
    for name in konan_toolchain.SLICES:
        build_konan(out, name, src, commit)
        print(f"{name}: {out / name / 'lib' / LIBRARY} (libwebp {tag})")
    # Apple's slices need Xcode, which only a Mac has: a Linux or Windows build host builds the bridged slices alone
    if sys.platform == "darwin":
        for name in APPLE_SLICES:
            build_apple(out, name, src, commit)
            print(f"{name}: {out / name / 'lib' / LIBRARY} (libwebp {tag})")


if __name__ == "__main__":
    main()
