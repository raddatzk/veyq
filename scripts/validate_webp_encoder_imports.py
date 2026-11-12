#!/usr/bin/env python3
"""Guard that the WebP encoder's directory imports nothing of veyq, and that the client reaches it only through its API
(ADR 0127 decision 5).

**What it asserts.** The directory is `repo_layout.WEBP_ENCODER` (`src/web/webp-encoder/`); its files are what
`git ls-files` lists under it, never a list. Two rules:
- **out** (the directory's own files):
  - a script (`.ts`, `.mts`, `.mjs`, `.js`) names, by an import specifier (`from '…'`, `import('…')`, a bare
    `import '…'`) or by `new URL('…', import.meta.url)`, only a relative path that resolves inside the directory, a
    `node:` builtin, or — in a `*.test.ts` — `vitest`;
  - a C file includes by quotes only libwebp's public headers (`src/webp/…`, resolved inside the build's checkout of
    the tag);
  - a Python file imports no module that is a tracked `.py` of the repository outside the directory — veyq's Python
    is exactly those files, derived, never listed.
  So the directory can be lifted out whole and published on its own without restructuring, which is why it holds its
  build, its pin, its output, its tests and its fixtures.
- **in** (every tracked `.ts`/`.mts`/`.mjs`/`.js`/`.vue` of the client's source, `src/web/src/`): a specifier that
  resolves into the directory resolves to `encodeWebp.ts`, the API — never to the glue or the module behind it. The
  build's own tooling (`vite.config.ts`, `src/web/scripts/`) is not held to it: it reads the pin, the licence files and
  the committed `.wasm` to check and to credit the encoder, which is no use of it.

Fail closed: no tracked file under the directory, no `encodeWebp.ts` there, or no script there that this guard read, is
red; so is a client that names the API nowhere (the guard would hold a door nobody uses, and the encoder would not be
in the client at all).

**The defect that bought it.** No incident — built with cr-webp-encoder-from-libwebp, whose Definition of done asks
for the directory's isolation to be a check rather than a one-shot grep: one import of a veyq helper into the encoder
would have been green in every other check, and would have tied the directory to the client for good.

**Its limits — what a green run does NOT prove.** Regex over source, not an AST: a commented-out import is read as one
(conservative), and a specifier built at run time (`import(base + name)`, a `new URL` of a variable) is not seen. It
reads what the files name, not what the build links: the Emscripten glue's own references (`import.meta.url`) are
read like any script's, but what `emcc` pulls from its system libraries is the toolchain's, pinned by the image digest.
A Python file that puts another directory on `sys.path` and imports from it under a name no tracked `.py` has is not
seen. And the in rule reads the client's source only: the build tooling may read any file of the directory (it is exempt
by location, above), and another surface reaching into it is not read.
"""
from __future__ import annotations

import pathlib
import posixpath
import re
import subprocess
import sys

import repo_layout  # where the code surfaces live (ADR 0106)

ROOT = repo_layout.ROOT
DIR = repo_layout.rel(repo_layout.WEBP_ENCODER)
CLIENT = repo_layout.rel(repo_layout.WEB / "src")  # the client's source; the build tooling beside it is exempt
API = f"{DIR}/encodeWebp.ts"

SCRIPT = (".ts", ".mts", ".mjs", ".js")
SPECIFIER = re.compile(r"""(?:\bfrom\s*|\bimport\s*\(\s*|\bimport\s+)(['"])([^'"\n]+)\1""")
URL_SPECIFIER = re.compile(r"""\bnew\s+URL\(\s*(['"])([^'"\n]+)\1\s*,\s*import\.meta\.url\s*\)""")
C_INCLUDE = re.compile(r'^\s*#\s*include\s+"([^"]+)"', re.MULTILINE)
PY_IMPORT = re.compile(r"^\s*(?:from\s+([A-Za-z_][\w.]*)\s+import\b|import\s+([A-Za-z_][\w.]*(?:\s*,\s*[A-Za-z_][\w.]*)*))", re.MULTILINE)
LIBWEBP_PUBLIC = "src/webp/"

errors: list[str] = []


def tracked() -> list[str]:
    out = subprocess.run(["git", "ls-files", "-z"], cwd=ROOT, capture_output=True, text=True)
    if out.returncode != 0:
        errors.append(f"`git ls-files` failed, so nothing was read: {out.stderr.strip()}")
        return []
    return [p for p in out.stdout.split("\0") if p]


def specifiers(text: str) -> list[str]:
    """Every specifier a script names. A `new URL` one is relative to the file even when bare (`'x.wasm'` is
    `'./x.wasm'`): that is how a URL resolves, unlike an import."""
    urls = [m.group(2) for m in URL_SPECIFIER.finditer(text)]
    return [m.group(2) for m in SPECIFIER.finditer(text)] + [u if u.startswith((".", "/")) or ":" in u else f"./{u}" for u in urls]


def resolve(importer: str, spec: str) -> str | None:
    """The repository-relative path a relative specifier names (extension-less as written), else None."""
    if not spec.startswith((".", "/")):
        return None
    return posixpath.normpath(posixpath.join(posixpath.dirname(importer), spec))


def inside(path: str) -> bool:
    return path == DIR or path.startswith(DIR + "/")


def check_out(files: list[str]) -> int:
    own = [p for p in files if inside(p)]
    if not own:
        errors.append(f"no tracked file under {DIR}/ — re-anchor this guard (repo_layout.WEBP_ENCODER)")
        return 0
    if API not in own:
        errors.append(f"{API} is not tracked — the client has no API to reach the encoder through")
    veyq_python = {pathlib.PurePosixPath(p).stem for p in files if p.endswith(".py") and not inside(p)}
    scripts = 0
    for rel in own:
        text = (ROOT / rel).read_text(errors="replace") if not rel.endswith((".wasm", ".gz")) else ""
        if rel.endswith(SCRIPT):
            scripts += 1
            for spec in specifiers(text):
                target = resolve(rel, spec)
                if target is not None:
                    if not inside(target):
                        errors.append(f"{rel}: `{spec}` reaches out of {DIR}/ (to {target})")
                elif spec.startswith("node:"):
                    continue
                elif spec == "vitest" and rel.endswith(".test.ts"):
                    continue
                else:
                    errors.append(f"{rel}: `{spec}` is a package, not a file of {DIR}/ — the encoder imports nothing "
                                  f"but its own files and Node's builtins")
        elif rel.endswith(".c"):
            for header in C_INCLUDE.findall(text):
                if not header.startswith(LIBWEBP_PUBLIC):
                    errors.append(f"{rel}: #include \"{header}\" is not one of libwebp's public headers ({LIBWEBP_PUBLIC}…)")
        elif rel.endswith(".py"):
            for m in PY_IMPORT.finditer(text):
                names = [m.group(1)] if m.group(1) else [n.strip() for n in m.group(2).split(",")]
                for name in names:
                    if name.split(".")[0] in veyq_python:
                        errors.append(f"{rel}: `import {name}` is a module of veyq's own Python, outside {DIR}/")
    if not scripts:
        errors.append(f"no script under {DIR}/ was read — re-anchor this guard")
    return len(own)


def check_in(files: list[str]) -> None:
    callers = 0
    for rel in files:
        if not rel.startswith(CLIENT + "/") or not rel.endswith((*SCRIPT, ".vue")):
            continue
        for spec in specifiers((ROOT / rel).read_text(errors="replace")):
            target = resolve(rel, spec)
            if target is None or not inside(target):
                continue
            if target in (API, API[: -len(".ts")]):
                callers += 1
            else:
                errors.append(f"{rel}: `{spec}` reaches into {DIR}/ past its API — import {API} only")
    if not callers:
        errors.append(f"no file of the web client imports {API} — the encoder is not in the client")


def main() -> int:
    files = tracked()
    count = check_out(files) if files else 0
    if files:
        check_in(files)
    if errors:
        print("WEBP-ENCODER-IMPORTS FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1
    print(f"webp-encoder imports: {count} files under {DIR}/ import nothing of veyq; the client reaches them through {API}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
