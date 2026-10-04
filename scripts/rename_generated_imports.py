#!/usr/bin/env python3
"""Point every use of the generated API at its owner's package — `cr-platform-gradle-modules` step 5. Scaffolding:
deleted when that CR closes (it is no guard and asserts nothing about the tree after the step).

**What it does** (`python3 scripts/rename_generated_imports.py`): the generated API used to be one package,
`com.veyq.app.generated.{api,model}`; the contracts split generates core's specs into `com.veyq.core.generated.*`
and the Memories specs (`contracts/api/memories/`) into `com.veyq.memories.generated.*`. A type's owner is
**derived from the specs**, never listed: a model is the Memories' when a Memories spec defines it (an alias to
`_common` is no definition — it maps to core's class), an API interface when a Memories operation carries its tag;
every other generated name, and `AdminChannelOperations`, is core's. It rewrites the dotted form of every
`com.veyq.app.generated.<api|model>.<Name>` (and the bare `com.veyq.app.generated.AdminChannelOperations`) in the
files git lists under `backend/src` and `scripts/` (this script and `rename_packages.py` excepted).

**`--check <base>`** re-derives every file that held the old package at `<base>` (`git show <base>:<path>`) through
the same mapping and compares byte for byte with the working tree; files in `HAND_EDITS` are exempt from the byte
compare (their hand edit is the step's, the residue scan still covers them). It then fails on any residual
`com.veyq.app.generated` in the files git lists, outside `RESIDUE_EXCEPTIONS`.
"""
from __future__ import annotations

import argparse
import pathlib
import re
import subprocess
import sys

import contract_specs

ROOT = pathlib.Path(__file__).resolve().parent.parent
OLD = "com.veyq.app.generated"
CORE = "com.veyq.core.generated"
APP = "com.veyq.memories.generated"
SCOPES = ("backend/src/", "scripts/")
SELF = {"scripts/rename_generated_imports.py", "scripts/rename_packages.py"}
# Files whose step-5 change is more than the mapping: MediaResource loses the three blob operations (now core's
# BlobResource), OperatorBlindProbeTest derives its operation set from BOTH owners' API packages, WrapMethodTest's
# KDoc names where `MasterKeyWrap.method` is defined now (`_common`), validate_guard_gaps' request-bound forgeries
# name the spec their schema moved to (`blob.openapi.yaml`, `memories/media.openapi.yaml`).
HAND_EDITS = {
    "scripts/validate_guard_gaps.py",
    "backend/src/main/kotlin/com/veyq/memories/media/MediaResource.kt",
    "backend/src/test/kotlin/com/veyq/core/http/OperatorBlindProbeTest.kt",
    "backend/src/test/kotlin/com/veyq/core/identity/WrapMethodTest.kt",
}
# Where the old package name may stay: the work order and the flow/state prose that narrates the step, and the
# step-2 script's description of what it left alone.
RESIDUE_EXCEPTIONS = {"changes/cr-platform-gradle-modules.md", "docs/inbox.md", "scripts/rename_packages.py",
                      "scripts/rename_generated_imports.py"}
NAME = re.compile(re.escape(OLD) + r"\.(?:(api|model)\.([A-Za-z0-9_]+)|(AdminChannelOperations)\b)")


def memories_types() -> set[tuple[str, str]]:
    """(kind, Name) of every generated type a Memories spec owns."""
    owned: set[tuple[str, str]] = set()
    for spec in contract_specs.specs(common=False):
        key = contract_specs.key(spec)
        if not key.startswith("memories/"):
            continue
        doc = contract_specs.load(key)
        for name, schema in ((doc.get("components") or {}).get("schemas") or {}).items():
            if not (isinstance(schema, dict) and set(schema) == {"$ref"}):
                owned.add(("model", name))
        for item in (doc.get("paths") or {}).values():
            for op in (item or {}).values():
                for tag in (op.get("tags") or []) if isinstance(op, dict) else []:
                    owned.add(("api", "".join(w[:1].upper() + w[1:] for w in tag.split("-")) + "Api"))
    if not owned:
        raise SystemExit("derived no Memories-owned generated type — the spec walk is broken")
    return owned


def rewrite(text: str, owned: set[tuple[str, str]]) -> str:
    def sub(m: re.Match) -> str:
        if m.group(3):
            return f"{CORE}.AdminChannelOperations"
        kind, name = m.group(1), m.group(2)
        return f"{APP if (kind, name) in owned else CORE}.{kind}.{name}"
    return NAME.sub(sub, text)


def git(*args: str) -> str:
    return subprocess.run(["git", *args], cwd=ROOT, check=True, capture_output=True, text=True).stdout


def listed() -> list[str]:
    return [p for p in git("ls-files", "--cached", "--others", "--exclude-standard").splitlines() if (ROOT / p).is_file()]


def residue() -> list[str]:
    hits = []
    for rel in listed():
        if rel in RESIDUE_EXCEPTIONS or not rel.endswith((".kt", ".py", ".md", ".kts", ".yaml", ".yml", ".json")):
            continue
        for n, line in enumerate((ROOT / rel).read_text(errors="replace").splitlines(), 1):
            if OLD in line:
                hits.append(f"{rel}:{n}: {line.strip()}")
    return hits


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", metavar="BASE")
    args = ap.parse_args()
    owned = memories_types()
    if not args.check:
        n = 0
        for rel in listed():
            if not rel.startswith(SCOPES) or rel in SELF:
                continue
            path = ROOT / rel
            text = path.read_text()
            new = rewrite(text, owned)
            if new != text:
                path.write_text(new)
                n += 1
        print(f"rewrote {n} file(s); {len(owned)} Memories-owned generated type(s)")
        return 0
    problems = []
    base_files = [p for p in git("grep", "-l", OLD, args.check, "--", *SCOPES).splitlines()]
    checked = 0
    lines = {"import": 0, "other": 0}
    for entry in base_files:
        rel = entry.split(":", 1)[1]
        if rel in SELF:
            continue
        old = git("show", f"{args.check}:{rel}")
        for a, b in zip(old.splitlines(), rewrite(old, owned).splitlines()):
            if a != b:
                lines["import" if a.startswith("import ") else "other"] += 1
        if rel in HAND_EDITS:
            continue
        cur = (ROOT / rel).read_text() if (ROOT / rel).exists() else None
        if cur != rewrite(old, owned):
            problems.append(f"{rel}: differs from the mapping of {args.check}")
        checked += 1
    problems += [f"residue: {h}" for h in residue()]
    print(f"--check {args.check}: {checked} file(s) byte-equal to the mapping of their pre-image "
          f"({len(HAND_EDITS)} hand-edited exempt); changed lines {lines}")
    if problems:
        print("\n".join(problems))
        return 1
    print("no residual " + OLD + " outside the declared exceptions")
    return 0


if __name__ == "__main__":
    sys.exit(main())
