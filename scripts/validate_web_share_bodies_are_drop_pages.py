#!/usr/bin/env python3
"""Guard that a web share body is a registered WRITE_ONLY drop page and nothing else (inbox #401).

**What it asserts.** Over `web/src` (tests and the generated client excluded):
  1. every `*ShareBody.vue` is claimed by an application — its `register.ts` imports it and passes it to
     `registry.claimShareBody(KIND, Component)`;
  2. every component an application claims that way is a `*ShareBody.vue`;
  3. `claimShareBody(` is called from an application's `register.ts` only.

**Why.** A share view looks exactly like the member view; a link's role only removes functions (the owner principle
of 2026-10-09, ADR 0079 Consequences, `web/CLAUDE.md`). The registry's share body is therefore the one page with no
member equivalent — a `WRITE_ONLY` link's drop, whose guest holds no key — and the shell (`views/ShareView.vue`)
mounts it for that role alone. Memories' share body had grown into a second gallery beside the member's (grouped
chapters, its own lightbox wiring, its own delete), and every such copy drifted (#356, #361, #363); the rebuild
deleted it. This guard keeps the name `ShareBody` meaning "a claimed drop page": a guest-only body written under that
name and mounted by hand, or a claim of something that is not one, is red.

**Its limits.** It anchors on the file name and the claim call, so a guest-only UI under any other name is invisible
to it — that the shell gives a `READ`/`READ_WRITE` link the member's views and never a claimed body is pinned by
`web/src/views/ShareView.registry.test.ts`, which no text guard can replace. A claim spelled other than
`claimShareBody(KIND, Identifier)` with a default import of a `.vue` file is reported as unresolved, never skipped.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

import repo_layout  # where the code surfaces live (ADR 0106)

ROOT = Path(__file__).resolve().parent.parent
WEB_SRC = repo_layout.WEB / "src"
CLAIM = re.compile(r"claimShareBody\(\s*[\w.]+\s*,\s*(\w+)\s*[,)]")
IMPORT = re.compile(r"^import\s+(\w+)\s+from\s+'([^']+\.vue)'", re.MULTILINE)


def sources(root: Path) -> list[Path]:
    out = []
    for p in root.rglob("*"):
        if p.suffix not in (".ts", ".vue") or "generated" in p.parts:
            continue
        if p.name.endswith((".test.ts", ".e2e.test.ts")) or "test" in p.relative_to(root).parts[:1]:
            continue
        out.append(p)
    return out


def check(root: Path) -> list[str]:
    errors: list[str] = []
    files = sources(root)
    bodies = {p.resolve() for p in files if p.name.endswith("ShareBody.vue")}
    claimed: set[Path] = set()
    for p in files:
        text = p.read_text(encoding="utf-8")
        calls = CLAIM.findall(text)
        if "claimShareBody(" not in text:
            continue
        rel = p.relative_to(root)
        if p.name != "register.ts":
            if p.name != "registry.ts":  # the registry declares and implements the door
                errors.append(f"{rel}: calls claimShareBody — only an application's register.ts claims a drop page")
            continue
        if text.count("claimShareBody(") != len(calls):
            errors.append(f"{rel}: a claimShareBody call is not `claimShareBody(KIND, Component)` — cannot resolve it")
        imports = {name: path for name, path in IMPORT.findall(text)}
        for name in calls:
            if name not in imports:
                errors.append(f"{rel}: claims {name}, which is no default import of a .vue file")
                continue
            target = (p.parent / imports[name]).resolve()
            if not target.name.endswith("ShareBody.vue"):
                errors.append(f"{rel}: claims {target.name} — a claimed share body is a WRITE_ONLY drop page named *ShareBody.vue")
            claimed.add(target)
    for body in sorted(bodies - claimed):
        errors.append(f"{body.relative_to(root)}: a *ShareBody.vue no application claims — a share body is a claimed drop page, "
                      "never a guest-only view mounted by hand (a READ/READ_WRITE guest gets the member's views)")
    return errors


def main() -> int:
    # A missing or empty source root is a web client that moved: `check` would read nothing and say OK.
    if not WEB_SRC.is_dir() or not sources(WEB_SRC):
        print(f"SHARE-BODY VALIDATION FAILED: no source under {WEB_SRC.relative_to(ROOT)} - the web client moved? "
              "This guard would check nothing there; point it at the client's `src/` (scripts/repo_layout.py).")
        return 1
    errors = check(WEB_SRC)
    if errors:
        print("SHARE-BODY VALIDATION FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1
    print("share bodies OK: every *ShareBody.vue is a drop page an application claims, and only those are claimed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
