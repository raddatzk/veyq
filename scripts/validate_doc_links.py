#!/usr/bin/env python3
"""Validate that every relative link under `docs/` and `changes/` resolves to a real file.

**Asserts:** every inline markdown link/image in those trees points at a path that exists. The reliable,
false-positive-free half of "are the docs (incl. ADRs) still correct?": a doc linking to a
moved/renamed/deleted file is unambiguously broken, unlike fuzzy prose-drift, which a machine can't judge.

**Bought by:** cr-0038's ADR 0001 finding — a doc pointing at a path that no longer exists. `changes/` was
added to the walk later for the same reason `.claude/` got its own `validate_skill_links.py`: it is a
pointer-heavy tree that sat outside every link guard. `changes/README.md` defines the guard-docstring
contract and is cited from four other files, so a rename would have rotted those pointers silently.

**Limit — resolution, never aptness.** A link that resolves is not thereby a link that points at the *right*
thing. External (http/https/mailto/tel) and pure in-page anchors (`#section`) are skipped, and a `#fragment`
is **stripped before resolving**, so a dead anchor inside an existing file passes (pinned as open gap 6 in
`validate_guard_gaps.py`). And **prose pointers are invisible by construction**: a bare filename in a
sentence (a `validate_*.py` cited in another guard's docstring) and a `§ section-name` citation are not
markdown links, so nothing here — or anywhere — checks them.

Scans every `*.md` under those roots for inline markdown links + images `[text](target)` / `![alt](target)`:
  - external (http/https/mailto/tel) and pure in-page anchors (`#section`) are skipped
  - a `#fragment` and a "title" suffix are stripped before resolving
  - the remainder is resolved relative to the linking file and must exist (file or directory)
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
DOC_ROOTS = [ROOT / "docs", ROOT / "changes"]

LINK = re.compile(r"!?\[[^\]]*\]\(([^)]+)\)")
SKIP_SCHEME = ("http://", "https://", "mailto:", "tel:")

errors: list[str] = []


def targets(text: str):
    for m in LINK.finditer(text):
        raw = m.group(1).strip()
        # drop an optional "title": [x](path "the title")
        target = raw.split()[0] if raw else raw
        target = target.strip("<>")
        if not target or target.startswith("#") or target.startswith(SKIP_SCHEME):
            continue
        yield target.split("#", 1)[0]  # strip any in-page anchor


for md in sorted(p for root in DOC_ROOTS for p in root.rglob("*.md")):
    for target in targets(md.read_text()):
        if not target:
            continue
        resolved = (md.parent / target).resolve()
        if not resolved.exists():
            errors.append(f"{md.relative_to(ROOT)}: dead link → {target}")

if errors:
    print("DOC LINK VALIDATION FAILED:")
    for e in errors:
        print("  -", e)
    sys.exit(1)

n = sum(1 for root in DOC_ROOTS for _ in root.rglob("*.md"))
print(f"doc links OK: all relative links in {n} doc(s) resolve")
