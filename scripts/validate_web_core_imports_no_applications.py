#!/usr/bin/env python3
"""Guard that the web core does not import, nor name the kinds of, the Memories application (ADR 0079, web phase).

**What it asserts.** The application is `web/src/features/memory/**` (the one constant `APPLICATION` below); core
is everything else under `web/src` — by exclusion, so a new directory is guarded the day it appears. Dependencies
run application → core, never back. Three rules, each read over every non-test `.ts`/`.vue` file of core:
- **import**: no file names the application by an import specifier — `from '…'` (so also `export … from '…'`),
  `import('…')`, or a bare `import '…'`. A relative specifier is resolved against the importing file and tested
  for landing inside the application, so `'../../memory/memoryNodes'` and `'../features/memory/memoryNodes'` are the same edge;
  a non-relative specifier that spells `features/memory` is refused too (no alias exists today).
- **glob**: no file contains `import.meta.glob`. Discovery by glob would hide an edge from the rule above, and
  ADR 0079 Decision 5 says what runs is what was reviewed.
- **kind**: no file names `NodeKind.Memory`, `NodeKind.Chapter`, `'MEMORY'` or `'CHAPTER'`. The shell asks a
  registry what to render for a kind; it does not know which kinds the one application has.
Exempt by exact path: `web/src/applications.ts`, the one composition root that may import the application (a
sibling file importing the application is red; an absent root is not an error). Exempt by location: `web/src/test/**` and `*.test.ts` — and the generated API
client, which is untracked and spells the kinds by definition.

**A ratchet, not a clean slate.** Core imports the application at 14 files today (cr-platform-web-boundaries
§ Inventory rows 1 and 3), so a plain guard would be red on the day it was born. `ALLOWLIST` names each remaining
violation per file and per rule; the guard is green with it, a violation not on it is red, and **an entry that no
longer violates is red too** (stale), so the list can only shrink. Each step of the CR deletes the entries it
removes; step 9 deletes the list and asserts it is empty.

**The defect that bought it.** No incident — the web half of ADR 0079 was prose until this guard. By the CR's
measurement `app/`, `components/app/`, `sw/`, `views/` and three core features imported `features/memory` directly,
which also made two import cycles (`app/activeSpace` ↔ `features/memory/memoryNodesStore`, `features/spaces` ↔
`features/memory`); an application that core cannot import is what makes a second one possible.

**Its limit.** Regex over source, not an AST: a commented-out import is read as an import (conservative), and a
specifier built at runtime (`import(base + name)`) is not seen — the glob rule and the review of the composition
root are what stand against discovery by computation. Tests and `src/test/` are not read (a test is a consumer of
both sides, as in the backend guard). `shared/ui/SecretKeyField` → `features/account/` is a different edge (shared
→ a core feature, not core → application), named in the ui-web styleguide, and this guard does not look at it.
The application's own outbound edges are fine (it may use core) and are not read.
"""
from __future__ import annotations

import os
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "web" / "src"
APPLICATION = ("features", "memory")  # relative to web/src — the one constant to change when the directory is renamed
COMPOSITION_ROOT = "applications.ts"  # relative to web/src; exact path, never a directory
GENERATED = ("api", "generated")  # untracked client; defines the kinds, so it names them

RULE_IMPORT, RULE_GLOB, RULE_KIND = "import", "glob", "kind"

# The remaining violations, per file (relative to web/src) and rule. Generated from the untouched tree
# (cr-platform-web-boundaries § Inventory rows 1 and 3, minus src/test/). Only shrinks: see the docstring.
ALLOWLIST: dict[str, set[str]] = {
    "app/activeSpace.ts": {RULE_IMPORT},
    "app/favoritesStore.ts": {RULE_IMPORT},
    "app/itemFavoritesStore.ts": {RULE_IMPORT},
    "features/account/rotation/rotateKeys.ts": {RULE_IMPORT},
    "views/ShareView.vue": {RULE_IMPORT},
}

# `from '…'` covers import/export-from, also across lines; `import('…')` and a bare `import '…'` the others.
SPECIFIER = re.compile(r"""(?:\bfrom\s*|\bimport\s*\(\s*|\bimport\s+)(['"])([^'"\n]+)\1""")
GLOB = re.compile(r"\bimport\.meta\.glob(?:Eager)?\b")
# Built from parts so this file never spells a kind literal it forbids.
KIND = re.compile(r"\bNodeKind\.(?:Memory|Chapter)\b|['\"](?:" + "MEMO" + "RY|" + "CHAP" + "TER)['\"]")


def is_exempt(rel: pathlib.PurePosixPath) -> bool:
    parts = rel.parts
    return (parts[:len(APPLICATION)] == APPLICATION
            or str(rel) == COMPOSITION_ROOT
            or parts[0] == "test"
            or parts[:2] == GENERATED
            or rel.name.endswith(".test.ts"))


def reaches_application(rel: pathlib.PurePosixPath, spec: str) -> bool:
    if spec.startswith("."):
        target = pathlib.PurePosixPath(os.path.normpath(str(rel.parent / spec)))
        return target.parts[:len(APPLICATION)] == APPLICATION
    return bool(re.search(r"(?:^|/)" + "/".join(APPLICATION) + r"(?:/|$)", spec))


def violations(rel: pathlib.PurePosixPath, text: str) -> set[str]:
    found: set[str] = set()
    if any(reaches_application(rel, m.group(2)) for m in SPECIFIER.finditer(text)):
        found.add(RULE_IMPORT)
    if GLOB.search(text):
        found.add(RULE_GLOB)
    if KIND.search(text):
        found.add(RULE_KIND)
    return found


def main() -> int:
    files = sorted(p for p in SRC.rglob("*") if p.suffix in (".ts", ".vue") and p.is_file())
    if not (SRC.is_dir() and (SRC / pathlib.Path(*APPLICATION)).is_dir() and files):
        print(f"WEB-CORE-IMPORTS FAILED — no source under {SRC.relative_to(ROOT)} or no application directory "
              f"{'/'.join(APPLICATION)}; the guard checked nothing. If web/ moved or the application was renamed, "
              "move this guard's anchors with it.")
        return 1

    errors: list[str] = []
    seen: dict[str, set[str]] = {}
    checked = 0
    for path in files:
        rel = pathlib.PurePosixPath(path.relative_to(SRC).as_posix())
        if is_exempt(rel):
            continue
        checked += 1
        found = violations(rel, path.read_text(encoding="utf-8"))
        if found:
            seen[str(rel)] = found

    for rel, found in sorted(seen.items()):
        for rule in sorted(found - ALLOWLIST.get(rel, set())):
            errors.append(f"web/src/{rel}: core breaks the `{rule}` rule — " + {
                RULE_IMPORT: "it imports the Memories application; ask the registry or move the shared piece "
                             "into core (ADR 0079)",
                RULE_GLOB: "`import.meta.glob` hides edges from this guard; register applications by a static "
                           "import in web/src/applications.ts",
                RULE_KIND: "it names a Memories node kind; ask the registry what to render for a kind",
            }[rule])
    for rel, rules in sorted(ALLOWLIST.items()):
        if not (SRC / rel).is_file():
            errors.append(f"ALLOWLIST entry web/src/{rel} names a file that no longer exists — delete the entry")
            continue
        for rule in sorted(rules - seen.get(rel, set())):
            errors.append(f"ALLOWLIST entry web/src/{rel} ({rule}) is stale — the file no longer violates it; "
                          "delete the entry (the list only shrinks)")

    if errors:
        print("WEB-CORE-IMPORTS FAILED:")
        for e in errors:
            print("  -", e)
        return 1
    remaining = sum(len(r) for r in ALLOWLIST.values())
    print(f"web core imports OK: {checked} core file(s) read, {remaining} allowlisted violation(s) in "
          f"{len(ALLOWLIST)} file(s), none new, none stale")
    return 0


if __name__ == "__main__":
    sys.exit(main())
