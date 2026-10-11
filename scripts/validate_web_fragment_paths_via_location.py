#!/usr/bin/env python3
"""Guard that a content path in the URL fragment is built and read by `core/node/location.ts` only (inbox #328).

**What it asserts.** Over `web/src` (tests and the generated client excluded), no file but `core/node/location.ts`
  1. splits a fragment/hash on `/` (a line that has `hash` in it and `.split('/')` — the parse),
  2. joins path segments behind a `#` (a line with `'#'`/`` `# `` and `.join('/')` — the build), or
  3. writes a literal fragment path: a string or template literal that starts with `#`, interpolates something and
     then continues with a `/` (`` `#${scope}/${slug}` ``). A bare scope token (`` `#@${id}` ``, `#~me`) has no path
     after it and passes.

**Why.** The grammar of a position is not "slugs joined by slashes" any more (ADR 0015, amended 2026-10-11): a folder
ends in a slash, a Files node is its plain name, any other application's node carries the application's route slug,
a claimed slug is reserved, and folders and files are numbered apart. Every hand-built or hand-split path would carry
a private, older copy of that grammar and open the wrong node — or the scope root — for the cases it forgot. The one
door is `parseHash` / `buildHash` / `hashForNode` / `resolveSlugPath`.

**Its limits.** It is a text guard over the three spellings above; a path assembled some other way (a `reduce`, a
`concat` in two statements) is invisible to it. That a built path resolves back to its node, for every kind, scope and
list order, is pinned by `web/src/core/node/location.test.ts`, which no text guard can replace.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WEB_SRC = ROOT / "web" / "src"
LOCATION = Path("core") / "node" / "location.ts"

SPLIT = re.compile(r"hash.*\.split\(\s*['\"]/['\"]\s*\)|\.split\(\s*['\"]/['\"]\s*\).*hash", re.IGNORECASE)
JOIN = re.compile(r"""(['"`]#['"`]|`#).*\.join\(\s*['"]/['"]\s*\)|\.join\(\s*['"]/['"]\s*\).*['"`]#""")
# A literal that opens with `#`, interpolates, and then continues with a slash: `#${a}/${b}` / '#' + a + '/' + b.
LITERAL = re.compile(r"""[`'"]#[^`'"\n]*\$\{[^`\n]*\}[^`'"\n]*/|['"]#['"]\s*\+[^\n]*['"][^'"\n]*/""")


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
    for p in sources(root):
        rel = p.relative_to(root)
        if rel == LOCATION:
            continue
        for n, line in enumerate(p.read_text(encoding="utf-8").splitlines(), 1):
            code = line.split("//", 1)[0] if "://" not in line else line
            if SPLIT.search(code):
                errors.append(f"{rel}:{n}: splits a fragment on `/` — parse it with location.ts `parseHash`")
            elif JOIN.search(code) or LITERAL.search(code):
                errors.append(
                    f"{rel}:{n}: builds a fragment path by hand — use location.ts `buildHash` / `hashForNode` "
                    "(a folder's trailing slash and an application's segment are its grammar)"
                )
    return errors


def main() -> int:
    errors = check(WEB_SRC)
    if errors:
        print("FRAGMENT-PATH VALIDATION FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1
    print("fragment paths OK: only core/node/location.ts builds or splits a content path in the URL fragment")
    return 0


if __name__ == "__main__":
    sys.exit(main())
