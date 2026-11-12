#!/usr/bin/env python3
"""Build the WebP encoder from libwebp's pinned release tag, and compare it with the committed bytes (ADR 0127).

Two roles in one file, so the host and the container cannot disagree about a path or a flag:

  - **On the host** (the default): build the image from this directory's `Dockerfile` (a digest-pinned Emscripten),
    run this script inside it, and read what it wrote. Then
      `build.py`              compare the build with `libwebp.pin` and the committed files: exit 1 on any difference,
                              listing it (the CI rebuild check, and the reproducibility proof);
      `build.py --write`      write the build into this directory and its hashes into `libwebp.pin`;
      `build.py --tag vX.Y.Z --commit <sha> --write`
                              the same for another tag (the update workflow; the commit is what `git ls-remote` named);
      `build.py --newest`     print libwebp's newest stable tag, its commit, and whether it is newer than the pin, as
                              `key=value` lines (the update workflow appends them to `$GITHUB_OUTPUT`). Needs git only.
  - **In the container** (`--inside`): fetch the tag into a fixed path, refuse it unless it is the pinned commit, and
    compile the encoder alone into `/out`.

The paths inside the container are fixed (`/work/libwebp`, `/work/encoder.c`, `/out`) and the compile names no file
of the host, so the output does not depend on where the checkout lives. Python 3.9 on the host (the guards' Python on
the owner's Mac), 3.12 in the image.
"""
from __future__ import annotations

import argparse
import hashlib
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
PIN_FILE = HERE / "libwebp.pin"
DOCKERFILE = HERE / "Dockerfile"
IMAGE_TAG = "veyq-webp-encoder-build:local"
UPSTREAM = "https://github.com/webmproject/libwebp"

# What the build writes, relative to this directory (and to /out in the container). Each is pinned by SHA-256.
OUTPUTS = ("wasm/webp_encoder.wasm", "wasm/webp_encoder.mjs", "COPYING", "PATENTS")

STABLE_TAG = re.compile(r"^v[0-9]+\.[0-9]+\.[0-9]+$")
COMMIT = re.compile(r"^[0-9a-f]{40}$")

# The encoder only: libwebp's encoder, its DSP and utilities, and sharpyuv (the RGB->YUV conversion the encoder
# calls). The decoder's DSP files sit in the same directory; nothing references them, and the linker drops them.
SOURCE_GLOBS = ("src/enc/*.c", "src/dsp/*.c", "src/utils/*.c", "sharpyuv/*.c")
EMCC_FLAGS = [
    "-O3",
    "-DNDEBUG",
    # No threads (no SharedArrayBuffer, so no cross-origin isolation): libwebp's own worker interface is off.
    "-DWEBP_USE_THREAD=0",
    "-I/work/libwebp",
    "-sMODULARIZE=1",
    "-sEXPORT_ES6=1",
    "-sEXPORT_NAME=createWebpEncoder",
    # No `eval`, no `new Function` in the glue: the page's CSP grants 'wasm-unsafe-eval', never 'unsafe-eval'.
    "-sDYNAMIC_EXECUTION=0",
    "-sFILESYSTEM=0",
    "-sENVIRONMENT=web,worker",
    "-sALLOW_MEMORY_GROWTH=1",
    # The module is handed its bytes (`wasmBinary`); it never fetches or streams them itself.
    "-sINCOMING_MODULE_JS_API=wasmBinary",
    "-sEXPORTED_FUNCTIONS=_webp_alloc,_webp_free,_webp_encode_rgba,_webp_result,_webp_release",
    "-sEXPORTED_RUNTIME_METHODS=HEAPU8",
]


def read_pin() -> dict:
    pin: dict = {"sha256": {}}
    for raw in PIN_FILE.read_text().splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if parts[0] == "sha256" and len(parts) == 3:
            pin["sha256"][parts[2]] = parts[1]
        elif parts[0] in ("tag", "commit", "image") and len(parts) == 2:
            pin[parts[0]] = parts[1]
        else:
            sys.exit(f"{PIN_FILE.name}: cannot read the line {raw!r}")
    for key in ("tag", "commit", "image"):
        if key not in pin:
            sys.exit(f"{PIN_FILE.name}: no `{key}` line")
    return pin


def dockerfile_image() -> str:
    froms = re.findall(r"^\s*FROM\s+(\S+)", DOCKERFILE.read_text(), re.MULTILINE | re.IGNORECASE)
    if len(froms) != 1:
        sys.exit(f"{DOCKERFILE.name}: expected exactly one FROM, found {froms!r}")
    return froms[0]


def write_pin(tag: str, commit: str, image: str, hashes: dict) -> None:
    lines = [line for line in PIN_FILE.read_text().splitlines() if line.startswith("#")]
    lines += [f"tag {tag}", f"commit {commit}", f"image {image}"]
    lines += [f"sha256 {hashes[name]} {name}" for name in OUTPUTS]
    PIN_FILE.write_text("\n".join(lines) + "\n")


def sha256(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(cmd: list, shown: list | None = None, **kwargs) -> None:
    print("+ " + " ".join(shown or cmd), file=sys.stderr, flush=True)
    subprocess.run(cmd, check=True, **kwargs)


def version(tag: str) -> tuple:
    return tuple(int(part) for part in tag[1:].split("."))


def newest() -> None:
    """Print the newest stable `vX.Y.Z` tag upstream (never an `-rc`), the commit it names, and whether it is newer."""
    listing = subprocess.run(["git", "ls-remote", "--tags", UPSTREAM], check=True, capture_output=True, text=True).stdout
    commits: dict = {}
    for line in listing.splitlines():
        sha, ref = line.split("\t")
        name = ref[len("refs/tags/"):]
        peeled = name.endswith("^{}")
        name = name[: -len("^{}")] if peeled else name
        if STABLE_TAG.match(name) and (peeled or name not in commits):
            commits[name] = sha  # an annotated tag's peeled line names the commit; a lightweight tag's own line does
    if not commits:
        sys.exit(f"no stable vX.Y.Z tag at {UPSTREAM} — has libwebp changed how it tags releases?")
    tag = max(commits, key=version)
    pinned = read_pin()["tag"]
    print(f"tag={tag}\ncommit={commits[tag]}\npinned={pinned}\nnewer={'true' if version(tag) > version(pinned) else 'false'}")


def inside(tag: str, commit: str) -> None:
    """In the container: fetch, check, compile into /out."""
    src = pathlib.Path("/work/libwebp")
    out = pathlib.Path("/out")
    run(["git", "-c", "advice.detachedHead=false", "clone", "--quiet", "--depth", "1", "--branch", tag, UPSTREAM, str(src)])
    head = subprocess.run(["git", "-C", str(src), "rev-parse", "HEAD"], check=True, capture_output=True, text=True).stdout.strip()
    if head != commit:
        sys.exit(f"libwebp {tag} is commit {head}, but the pin says {commit} — the tag moved or the pin is wrong; refusing to build")
    sources = sorted(str(p) for pattern in SOURCE_GLOBS for p in src.glob(pattern))
    if not sources:
        sys.exit("no libwebp source matched — the tree's layout changed; read it before building")
    (out / "wasm").mkdir(parents=True, exist_ok=True)
    target = ["-o", str(out / "wasm/webp_encoder.mjs")]
    run(["emcc", *EMCC_FLAGS, "/work/encoder.c", *sources, *target], cwd="/work",
        shown=["emcc", *EMCC_FLAGS, "/work/encoder.c", f"<{len(sources)} libwebp sources: {', '.join(SOURCE_GLOBS)}>", *target])
    for name in ("COPYING", "PATENTS"):
        shutil.copyfile(src / name, out / name)


def build_on_host(tag: str, commit: str, image: str) -> pathlib.Path:
    """Build the image, run the inside role, and return the directory holding the outputs."""
    if dockerfile_image() != image:
        sys.exit(f"{DOCKERFILE.name} builds FROM {dockerfile_image()}, but {PIN_FILE.name} pins {image}. A changed image "
                 f"changes the output: run `build.py --write` and commit both.")
    run(["docker", "build", "--quiet", "--tag", IMAGE_TAG, "--file", str(DOCKERFILE), str(HERE)], stdout=subprocess.DEVNULL)
    out = pathlib.Path(tempfile.mkdtemp(prefix="webp-encoder-"))
    run([
        "docker", "run", "--rm", "--user", f"{os.getuid()}:{os.getgid()}", "--env", "HOME=/tmp",
        "--volume", f"{out}:/out", IMAGE_TAG, "--inside", "--tag", tag, "--commit", commit,
    ])
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--inside", action="store_true", help="the container role (called by the host role)")
    parser.add_argument("--tag", help="a libwebp release tag other than the pinned one (needs --commit)")
    parser.add_argument("--commit", help="the commit that tag must be")
    parser.add_argument("--write", action="store_true", help="write the build and its hashes instead of comparing")
    parser.add_argument("--newest", action="store_true", help="print libwebp's newest stable tag and whether it is newer")
    args = parser.parse_args()

    if args.newest:
        newest()
        return 0

    if args.inside:
        inside(args.tag, args.commit)
        return 0

    pin = read_pin()
    tag, commit = args.tag or pin["tag"], args.commit or pin["commit"]
    if bool(args.tag) != bool(args.commit):
        sys.exit("--tag and --commit go together")
    if not STABLE_TAG.match(tag) or not COMMIT.match(commit):
        sys.exit(f"not a stable release tag and a full commit: {tag} {commit}")
    out = build_on_host(tag, commit, pin["image"])
    try:
        built = {name: sha256(out / name) for name in OUTPUTS}
        if args.write:
            for name in OUTPUTS:
                (HERE / name).parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(out / name, HERE / name)
            write_pin(tag, commit, pin["image"], built)
            for name in OUTPUTS:
                print(f"{built[name]}  {name}")
            return 0
        differences = []
        for name in OUTPUTS:
            pinned = pin["sha256"].get(name)
            committed = sha256(HERE / name) if (HERE / name).is_file() else "(missing)"
            if not (built[name] == pinned == committed):
                differences.append(f"{name}: built {built[name]}, pinned {pinned}, committed {committed}")
        if args.tag:
            differences.insert(0, f"built {tag}, the pin holds {pin['tag']}")
        if differences:
            print("The rebuild differs from the pin — never drop this check; find the cause (ADR 0127):", file=sys.stderr)
            for line in differences:
                print(f"  {line}", file=sys.stderr)
            return 1
        print(f"libwebp {tag} ({commit[:10]}) rebuilt byte for byte: " + ", ".join(OUTPUTS))
        return 0
    finally:
        shutil.rmtree(out, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
