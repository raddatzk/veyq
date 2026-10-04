#!/usr/bin/env python3
"""Rename the backend's packages to their owner — `cr-platform-gradle-modules` step 2. Scaffolding: deleted when
that CR closes (it is no guard and asserts nothing about the tree after the step).

**What it does** (`python3 scripts/rename_packages.py`, on a tree whose backend still has the old layout):
1. reads every Kotlin file under the backend's main and test roots (`backend_tree`), checks that its directory is
   its `package`, maps the package through `MAP` (longest prefix wins) and refuses — before touching anything —
   when two old packages would land in one new package that `MERGES` does not declare, when a declared merge does
   not happen, or when two files would land on one path;
2. `git mv`s every file to the directory of its new package;
3. rewrites every old spelling in every text file of the source tree (what git lists, tracked or untracked and not
   ignored) except `EXCEPTIONS`: the dotted form `com.veyq.app.shared.x.Name`, the slash form
   `com/veyq/app/shared/x/Name.kt`, and the path-segment join `"com" / "veyq" / "app" / "shared"` (a Python spelling;
   step 2's first commit routed the guards through `backend_tree`, so none is expected — it is still rewritten
   and scanned for, so a stray one cannot survive). Only names that start with a `MAP` key change; the
   generated API (`com.veyq.app.generated.*`) has no key and is left alone (its owner is decided by the contracts
   split, step 5);
4. scans for residue (below).

**`--check <base>`** re-derives the expected tree from `git show <base>:<path>` for every file of `<base>` — its
new path and its new content, by the same mapping — and compares byte for byte with the working tree, file by file;
the working tree must hold exactly those files plus this script. It then prints how many lines changed and of
which kind (package / import / other), and runs the residue scan.

**The residue scan** fails on any old spelling of a renamed package — `com.veyq.app.(core|shared|features|testing)`,
its slash form or its segment join — outside `EXCEPTIONS`, and outside `HAND_EDITS`: four prose sites that describe
the old layout as a whole (`features/{…}`, `features.*`), which no package map can rewrite; they are listed by file
and literal and the step's third commit rewrites them by hand. `--final` drops that allowance.
"""
from __future__ import annotations

import argparse
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
OLD = "com.veyq.app"
# Old package (below com.veyq.app) → new package. Longest prefix wins.
MAP = {
    "core": "com.veyq.core",
    "shared": "com.veyq.core",
    "features.account": "com.veyq.core.account",
    "features.admin": "com.veyq.core.admin",
    "features.platform": "com.veyq.core.platform",
    "features.sharing": "com.veyq.core.sharing",
    "features.spaces": "com.veyq.core.spaces",
    "features.tasks": "com.veyq.core.tasks",
    "features.content": "com.veyq.core.content",
    "features.content.favorites": "com.veyq.memories.favorites",
    "features.content.itemfavorites": "com.veyq.memories.itemfavorites",
    "features.media": "com.veyq.memories.media",
    # No file declares it: the name the kernel guard forbade ahead of time for a Memories package. After the rename
    # the whole Memories application is `com.veyq.memories`, so that is what the prospective ban becomes.
    "features.memory": "com.veyq.memories",
    "testing": "com.veyq.core.testing",
}
# The packages that merge, declared — two old packages landing in one new one anywhere else is refused.
MERGES = {
    "com.veyq.core.scope": {"com.veyq.app.core.scope", "com.veyq.app.shared.scope"},
    "com.veyq.core.sharing": {"com.veyq.app.core.sharing", "com.veyq.app.features.sharing"},
}
EXCEPTIONS = {
    "changes/cr-platform-gradle-modules.md",  # the work order: it states the map in the old names
    "scripts/rename_packages.py",              # this manifest
}
# Prose that names the old layout as a whole; rewritten by hand in the step's third commit.
HAND_EDITS = {
    ("docs/apps/memories.md", "com/veyq/app/features`"),
    ("docs/decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md", "com/veyq/app/features/{"),
    ("docs/inbox.md", "com/veyq/app/{"),
    ("scripts/validate_mutation_trigger_paths.py", "com.veyq.app.features.*"),
}
RENAMED_TOPS = ("core", "shared", "features", "testing")

DOT = re.compile(r"(?<![\w])com\.veyq\.app((?:\.\w+)+)")
SLASH = re.compile(r"(?<![\w])com/veyq/app((?:/\w+)+)")
JOIN = re.compile(r'"com"(\s*/\s*)"veyq"\s*/\s*"app"((?:\s*/\s*"\w+")+)')
RESIDUE = [
    re.compile(r"(?<![\w])com\.veyq\.app\.(?:" + "|".join(RENAMED_TOPS) + r")(?![\w])"),
    re.compile(r"(?<![\w])com/veyq/app/(?:" + "|".join(RENAMED_TOPS) + r")(?![\w])"),
    re.compile(r'"com"\s*/\s*"veyq"\s*/\s*"app"\s*/\s*"(?:' + "|".join(RENAMED_TOPS) + r')"'),
    re.compile(r"com/veyq/app/\{"),  # the brace form names the old layout too
]
PACKAGE = re.compile(r"^package\s+([\w.]+)", re.M)
KOTLIN_ROOTS = ("backend/src/main/kotlin", "backend/src/test/kotlin")


def map_segments(segs: list[str]) -> list[str] | None:
    """Old segments below com.veyq.app → the full new segments, or None when no MAP key prefixes them."""
    for n in range(len(segs), 0, -1):
        key = ".".join(segs[:n])
        if key in MAP:
            return MAP[key].split(".") + segs[n:]
    return None


def map_package(pkg: str) -> str:
    assert pkg.startswith(OLD + "."), pkg
    new = map_segments(pkg[len(OLD) + 1:].split("."))
    if new is None:
        raise SystemExit(f"rename: no MAP entry for package {pkg}")
    return ".".join(new)


def rewrite(text: str) -> str:
    def dot(m: re.Match) -> str:
        new = map_segments(m.group(1)[1:].split("."))
        return m.group(0) if new is None else ".".join(new)

    def slash(m: re.Match) -> str:
        new = map_segments(m.group(1)[1:].split("/"))
        return m.group(0) if new is None else "/".join(new)

    def join(m: re.Match) -> str:
        sep = m.group(1)
        segs = re.findall(r'"(\w+)"', m.group(2))
        new = map_segments(segs)
        return m.group(0) if new is None else sep.join(f'"{s}"' for s in new).lstrip()

    return JOIN.sub(join, SLASH.sub(slash, DOT.sub(dot, text)))


def git(*args: str) -> str:
    return subprocess.run(["git", *args], cwd=ROOT, check=True, capture_output=True, text=True).stdout


def source_tree() -> list[str]:
    return sorted(set(git("ls-files", "--cached", "--others", "--exclude-standard").split("\n")) - {""})


def is_text(data: bytes) -> bool:
    if b"\0" in data:
        return False
    try:
        data.decode("utf-8")
        return True
    except UnicodeDecodeError:
        return False


def kotlin_moves(listing: list[str], read) -> dict[str, str]:
    """old path → new path for every Kotlin file under the kotlin roots; refuses an undeclared merge or a clash."""
    moves: dict[str, str] = {}
    olds_of: dict[tuple[str, str], set[str]] = {}
    for path in listing:
        root = next((r for r in KOTLIN_ROOTS if path.startswith(r + "/")), None)
        if root is None or not path.endswith(".kt"):
            continue
        m = PACKAGE.search(read(path))
        if not m:
            raise SystemExit(f"rename: {path} declares no package")
        pkg = m.group(1)
        rel_dir = str(pathlib.PurePosixPath(path).parent.relative_to(root))
        if rel_dir.replace("/", ".") != pkg:
            raise SystemExit(f"rename: {path} sits in {rel_dir} but declares {pkg}; move it first")
        new_pkg = map_package(pkg)
        moves[path] = f"{root}/{new_pkg.replace('.', '/')}/{pathlib.PurePosixPath(path).name}"
        olds_of.setdefault((root, new_pkg), set()).add(pkg)
    errors = []
    seen: dict[str, str] = {}
    for old, new in moves.items():
        if new in seen:
            errors.append(f"two files land on {new}: {seen[new]} and {old}")
        seen[new] = old
    happened = set()
    for (root, new_pkg), olds in olds_of.items():
        if len(olds) > 1:
            declared = MERGES.get(new_pkg, set())
            if not olds <= declared:
                errors.append(f"{root}: {sorted(olds)} would merge into {new_pkg}, and MERGES does not declare it")
            happened.add(new_pkg)
    for new_pkg in sorted(set(MERGES) - happened):
        errors.append(f"MERGES declares {new_pkg}, but no tree merges into it — a stale declaration")
    if errors:
        raise SystemExit("rename refused:\n  - " + "\n  - ".join(errors))
    return moves


def residue(files: dict[str, str], final: bool) -> list[str]:
    found = []
    allowed = set() if final else HAND_EDITS
    for path, text in sorted(files.items()):
        if path in EXCEPTIONS:
            continue
        for lineno, line in enumerate(text.splitlines(), start=1):
            for rx in RESIDUE:
                for m in rx.finditer(line):
                    if any(path == f and line.startswith(lit, m.start()) for f, lit in allowed):
                        continue
                    found.append(f"{path}:{lineno}: {line.strip()[:160]}")
    return sorted(set(found))


def read_tree_text(listing: list[str]) -> dict[str, str]:
    out = {}
    for path in listing:
        p = ROOT / path
        if p.is_file() and not p.is_symlink():
            data = p.read_bytes()
            if is_text(data):
                out[path] = data.decode("utf-8")
    return out


def apply() -> int:
    listing = source_tree()
    moves = kotlin_moves(listing, lambda p: (ROOT / p).read_text(encoding="utf-8"))
    for old, new in sorted(moves.items()):
        (ROOT / new).parent.mkdir(parents=True, exist_ok=True)
        git("mv", old, new)
    for old in moves:  # git mv leaves the emptied directories behind
        d = (ROOT / old).parent
        while d != ROOT and d.is_dir() and not any(d.iterdir()):
            d.rmdir()
            d = d.parent
    listing = source_tree()
    changed = 0
    for path, text in read_tree_text(listing).items():
        if path in EXCEPTIONS:
            continue
        new = rewrite(text)
        if new != text:
            (ROOT / path).write_text(new, encoding="utf-8")
            changed += 1
    print(f"rename: {len(moves)} Kotlin file(s) moved, {changed} file(s) rewritten")
    left = residue(read_tree_text(source_tree()), final=False)
    if left:
        print("rename: residue left —\n  " + "\n  ".join(left))
        return 1
    print(f"rename: no old spelling left outside {sorted(EXCEPTIONS)} and the {len(HAND_EDITS)} declared hand edits")
    return 0


def check(base: str, final: bool) -> int:
    base_listing = sorted(set(git("ls-tree", "-r", "--name-only", base).split("\n")) - {""})

    def base_bytes(path: str) -> bytes:
        return subprocess.run(["git", "show", f"{base}:{path}"], cwd=ROOT, check=True, capture_output=True).stdout

    moves = kotlin_moves(base_listing, lambda p: base_bytes(p).decode("utf-8"))
    errors: list[str] = []
    kinds = {"package": 0, "import": 0, "other": 0}
    expected_paths = set()
    rewritten = 0
    for path in base_listing:
        new_path = moves.get(path, path)
        expected_paths.add(new_path)
        data = base_bytes(path)
        if path not in EXCEPTIONS and is_text(data):
            old_text = data.decode("utf-8")
            expected = rewrite(old_text).encode("utf-8")
            if expected != data:
                rewritten += 1
                for a, b in zip(old_text.splitlines(), expected.decode("utf-8").splitlines()):
                    if a != b:
                        kinds["package" if a.startswith("package ") else "import" if a.startswith("import ")
                              else "other"] += 1
        else:
            expected = data
        actual = ROOT / new_path
        if not actual.is_file():
            errors.append(f"missing: {new_path} (from {path})")
        elif actual.read_bytes() != expected:
            errors.append(f"differs from the mapping of its pre-image: {new_path} (from {base}:{path})")
    actual_paths = set(source_tree())
    extra = actual_paths - expected_paths - {"scripts/rename_packages.py"}
    errors += [f"not derived from {base}: {p}" for p in sorted(extra)]
    print(f"check: {len(base_listing)} file(s) of {base[:12]} re-derived; {len(moves)} moved, {rewritten} rewritten; "
          f"changed lines: {kinds['package']} package, {kinds['import']} import, {kinds['other']} other")
    left = residue(read_tree_text(sorted(actual_paths)), final)
    errors += [f"residue: {r}" for r in left]
    if errors:
        print("CHECK FAILED:\n  - " + "\n  - ".join(errors))
        return 1
    print("check OK: every file equals the mapping of its pre-image, and no old spelling is left"
          + ("" if final else f" outside the {len(HAND_EDITS)} declared hand edits"))
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", metavar="BASE", help="re-derive from BASE and compare byte for byte")
    ap.add_argument("--final", action="store_true", help="residue scan without the hand-edit allowance")
    ap.add_argument("--scan", action="store_true", help="only the residue scan")
    args = ap.parse_args()
    if args.scan:
        left = residue(read_tree_text(source_tree()), args.final)
        print("\n".join(left) if left else "residue scan OK")
        return 1 if left else 0
    if args.check:
        return check(args.check, args.final)
    return apply()


if __name__ == "__main__":
    sys.exit(main())
