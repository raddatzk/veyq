#!/usr/bin/env python3
"""Every `ADR NNNN` citation in a load-bearing surface must resolve to docs/decisions/NNNN-*.md.

The gap this closes is named by `docs/decisions/README.md` itself: *"no validator checks that [a cited ADR
still resolves]"* (inbox #95). It bit once — the native drop (`7a1c4e9f`) deleted ADR 0009 but left 27
citations dangling in a spec description and the web crypto layer, swept only later in cr-0178 by repointing
them to ADR 0017. A deleted ADR's citations must be repointed to a successor or removed; this guard makes an
unswept one red instead of silent.

Scope — the surfaces where an `ADR NNNN` is *always a live citation*, so "must resolve" is unambiguous:
  `contracts/`, `backend/src`, `web/src`, `docs/features/`, `docs/crosscutting/`, and the ADR bodies
  `docs/decisions/*.md`.
Deliberately NOT scanned — the narrative registers where a *historical* mention of a retired ADR legitimately
does not resolve (e.g. "the native drop deleted ADR 0009"): `docs/inbox.md`, `changes/`, and
`docs/decisions/README.md`. That exclusion is why no retired-ADR allowlist is needed: in a spec description or
a code comment nobody narrates an ADR's deletion, so every hit there is a citation that must point at a live
decision. The honest cost: a dangling ADR ref *in* the inbox is not caught — an acceptable scratch-surface gap
traded for zero false positives on legitimate history.

History-blindness stays a principled edge (trust-chain skill § edges): this guard sees only the present tree.
An ADR deleted *after* a green run dangles until the next run — green means nothing *currently* dangles.

A `validate_*.py`, so the trust-chain glob runs it on every push with no build. Runnable locally:

    python3 scripts/validate_adr_citations.py
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
DECISIONS = ROOT / "docs" / "decisions"

# Load-bearing surfaces: a directory root + the file suffixes to read under it.
SCAN = [
    (ROOT / "contracts", {".yaml", ".yml", ".json", ".md"}),
    (ROOT / "backend" / "src", {".kt"}),
    (ROOT / "web" / "src", {".ts", ".vue", ".js"}),
    (ROOT / "docs" / "features", {".md"}),
    (ROOT / "docs" / "crosscutting", {".md"}),
]

# Path components that mark a generated / vendored tree — a citation there is derivation, not source
# (web/src/api/generated is gitignored: it re-derives from contracts/ on the next codegen).
EXCLUDE_PARTS = {"generated", "node_modules", "build", "dist", "target", ".gradle", "__pycache__"}

CITE_RE = re.compile(r"\bADR (\d{4})\b")

live = {m.group(1) for p in DECISIONS.glob("*.md") if (m := re.match(r"(\d{4})-", p.name))}


def scanned_files():
    # The ADR bodies themselves — but NOT decisions/README.md, a narrative register (it recounts deletions).
    for p in sorted(DECISIONS.glob("*.md")):
        if p.name != "README.md":
            yield p
    for root, suffixes in SCAN:
        if not root.exists():
            continue
        for p in sorted(root.rglob("*")):
            if p.suffix not in suffixes:
                continue
            if EXCLUDE_PARTS & set(p.parts):
                continue
            yield p


errors: list[str] = []
count = 0
for p in scanned_files():
    count += 1
    try:
        text = p.read_text()
    except (UnicodeDecodeError, OSError):
        continue
    for i, line in enumerate(text.splitlines(), 1):
        for m in CITE_RE.finditer(line):
            adr = m.group(1)
            if adr not in live:
                errors.append(f"{p.relative_to(ROOT)}:{i}: cites ADR {adr}, "
                              f"which resolves to no docs/decisions/{adr}-*.md")

if errors:
    print("ADR-CITATION VALIDATION FAILED (a cited ADR no longer resolves — repoint to a successor or remove):")
    for e in errors:
        print("  -", e)
    sys.exit(1)

print(f"adr citations OK: every ADR NNNN across {count} scanned file(s) resolves to a live decision")
