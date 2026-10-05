#!/usr/bin/env python3
"""Every doc id cited in a load-bearing surface must resolve: `ADR NNNN`, and `uc.`/`en.`/`cc.` ids.

What it asserts — three id shapes, each resolved against its own home:
  - `ADR NNNN` → `docs/decisions/NNNN-*.md`.
  - `uc.<feature>.<name>` / `en.<feature>.<name>` → a stem under
    `docs/features/<feature>/{use-cases,enablers}/`. `<feature>` is read from the directories of
    `docs/features/`, never listed here.
  - `cc.<first>.…` → a stem under `docs/crosscutting/`. `<first>` is read from the first segment of the
    existing `cc.*.md` stems. A trailing `.md` on the citation is stripped before resolving.
  A lookbehind keeps an id out when a word character, `.`, `$`, `/` or `-` precedes it: `foo.uc.x` is an
  attribute chain and `crosscutting/cc.x.md` is a path, which `validate_doc_links.py` resolves.

The defects that bought it:
  - `ADR NNNN` (inbox #95): the native drop (`7a1c4e9f`) deleted ADR 0009 and left 27 citations dangling in a
    spec description and the web crypto layer. They were swept only later, in cr-0178, by repointing them to
    ADR 0017. A deleted ADR's citations must be repointed to a successor or removed.
  - `uc.`/`en.` (inbox #122, cr-doc-citations-resolve-from-code): code comments cite their governing use-case or
    enabler in almost every file, and nothing checked that the id named a doc. When this half landed, 7 ids
    dangled at 12 sites, in three different shapes: the wrong prefix (`en.` for a use-case and the reverse), a
    use-case split in two since it was cited, and two ids that never existed. Two agents reading the code
    followed them into nothing before a grep measured the class.
  The guard was called `validate_adr_citations.py` until the second shape joined.

Scope — the surfaces where an id is *always a live citation*, so "must resolve" is unambiguous:
  `contracts/`, every `.kt` under `backend/` (all Gradle modules), the `src/` of every npm workspace (read from the root `package.json`, so a new
  package is scanned the day it is added — the hard-coded list this replaced named `web/src` alone and missed
  `admin-web/src` and `packages/crypto/src` for as long as they existed), `docs/features/`,
  `docs/crosscutting/`, and the ADR bodies `docs/decisions/*.md`.
Deliberately NOT scanned — the narrative registers, where a *historical* mention of a retired id legitimately
does not resolve (e.g. "the native drop deleted ADR 0009"): `docs/inbox.md`, `changes/`,
`docs/decisions/README.md`, and `scripts/` (whose docstrings, this one included, narrate retired ids). That
exclusion is why no retired-id allowlist is needed: in a spec description or a code comment nobody narrates a
deletion, so every hit there is a citation that must point at a live doc.

Its limit — what a green run does NOT prove:
  - An id under a feature directory that does not exist (`uc.<no such feature>.x`) is not read at all. The
    anchor on a real feature directory is what keeps attribute chains such as `.parent.parent` out (unanchored,
    the same regex read one in `scripts/validate_coverage.py` as an id); it is also exactly this blind spot.
    Pinned open in `validate_guard_gaps.py`.
  - An id in `scripts/` or in a narrative register (above).
  - Whether the cited doc is the *right* one: resolution, never aptness — the same limit
    `validate_doc_links.py` states of itself.
  - History: a doc deleted *after* a green run dangles until the next run (trust-chain skill § edges).

A `validate_*.py`, so the trust-chain glob runs it on every push with no build. Runnable locally:

    python3 scripts/validate_doc_citations.py
"""
from __future__ import annotations

import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
DECISIONS = ROOT / "docs" / "decisions"
FEATURES = ROOT / "docs" / "features"
CROSSCUTTING = ROOT / "docs" / "crosscutting"

CODE_SUFFIXES = {".ts", ".vue", ".js", ".mjs"}


def workspace_srcs() -> list[pathlib.Path]:
    """Every npm workspace's `src/`, from the root manifest — the scan follows the package list, not a copy of it."""
    manifest = json.loads((ROOT / "package.json").read_text())
    return [ROOT / ws / "src" for ws in manifest.get("workspaces", [])]


# Load-bearing surfaces: a directory root + the file suffixes to read under it.
SCAN = [
    (ROOT / "contracts", {".yaml", ".yml", ".json", ".md"}),
    # The whole backend, not `backend/src`: every Gradle module keeps its sources in its own `src` (`backend/core/src`,
    # `backend/apps/memories/src`, …), and a root that names one project stops seeing the others.
    (ROOT / "backend", {".kt"}),
    *[(src, CODE_SUFFIXES) for src in workspace_srcs()],
    (FEATURES, {".md"}),
    (CROSSCUTTING, {".md"}),
]

# Path components that mark a generated / vendored tree — a citation there is derivation, not source
# (web/src/api/generated is gitignored: it re-derives from contracts/ on the next codegen).
EXCLUDE_PARTS = {"generated", "node_modules", "build", "dist", "target", ".gradle", "__pycache__"}

live_adrs = {m.group(1) for p in DECISIONS.glob("*.md") if (m := re.match(r"(\d{4})-", p.name))}

features = sorted(p.name for p in FEATURES.iterdir() if p.is_dir())
live_feature_ids = {p.stem for p in FEATURES.glob("*/use-cases/*.md")} | {
    p.stem for p in FEATURES.glob("*/enablers/*.md")}

live_cc_ids = {p.stem for p in CROSSCUTTING.glob("cc.*.md")}
cc_firsts = sorted({stem.split(".")[1] for stem in live_cc_ids})

# Not an id when a word char, `.`, `$`, `/` or `-` precedes it (an attribute chain, a path, a longer name).
_NOT_AFTER = r"(?<![\w.$/-])"
ADR_RE = re.compile(r"\bADR (\d{4})\b")
FEATURE_ID_RE = re.compile(
    _NOT_AFTER + r"(?:uc|en)\.(?:" + "|".join(map(re.escape, features)) + r")\.[a-z0-9-]+")
CC_ID_RE = re.compile(
    _NOT_AFTER + r"cc\.(?:" + "|".join(map(re.escape, cc_firsts)) + r")(?:\.[a-z0-9-]+)*")


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
            # Relative, never `p.parts`: a checkout under a directory named `build` (or any other excluded name)
            # would otherwise exclude every file, and the guard would report green over nothing.
            if EXCLUDE_PARTS & set(p.relative_to(ROOT).parts):
                continue
            yield p


def dangling(line: str):
    """Yield a message for every id on the line that names no doc."""
    for m in ADR_RE.finditer(line):
        if m.group(1) not in live_adrs:
            yield f"cites ADR {m.group(1)}, which resolves to no docs/decisions/{m.group(1)}-*.md"
    for m in FEATURE_ID_RE.finditer(line):
        if m.group(0) not in live_feature_ids:
            kind, feature, _ = m.group(0).split(".", 2)
            home = "use-cases" if kind == "uc" else "enablers"
            yield f"cites {m.group(0)}, which resolves to no docs/features/{feature}/{home}/{m.group(0)}.md"
    for m in CC_ID_RE.finditer(line):
        cited = m.group(0)
        stem = cited[: -len(".md")] if cited.endswith(".md") else cited
        if stem not in live_cc_ids:
            yield f"cites {cited}, which resolves to no docs/crosscutting/{stem}.md"


errors: list[str] = []
count = 0
for p in scanned_files():
    count += 1
    try:
        text = p.read_text()
    except (UnicodeDecodeError, OSError):
        continue
    for i, line in enumerate(text.splitlines(), 1):
        for msg in dangling(line):
            errors.append(f"{p.relative_to(ROOT)}:{i}: {msg}")

if not count:
    print("DOC-CITATION VALIDATION FAILED: scanned no file at all — a scan root moved?")
    sys.exit(1)

if errors:
    print("DOC-CITATION VALIDATION FAILED (a cited doc id resolves to nothing — repoint it or remove it):")
    for e in errors:
        print("  -", e)
    sys.exit(1)

print(f"doc citations OK: every ADR NNNN and uc./en./cc. id across {count} scanned file(s) resolves to a doc")
