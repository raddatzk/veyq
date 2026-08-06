#!/usr/bin/env python3
"""Validate the feature-doc wiring: index completeness + Serves/back-reference symmetry.

The deterministic half of "is every use-case / enabler discoverable and honestly cross-linked?" — the
class of drift we otherwise fix by hand (a refined `uc.*`/`en.*` file that never made it into its feature
index; an enabler whose `Serves` names a use-case that doesn't actually build on it).

Two checks, both false-positive-free (they only ever assert on *links that already resolve*, which
validate_doc_links guarantees):

  A. Index completeness — every `docs/features/<feature>/{use-cases,enablers}/{uc,en}.*.md` is linked from
     that feature's `_index.md`. Catches the "file exists, refined, but invisible from the index" bug
     (e.g. uc.memory.chapters / uc.account.remember-this-device before the fitness pass).

  B. Serves ↔ back-reference symmetry — if an enabler's **Serves** field links to a use-case or enabler X,
     then X must link back to the enabler. Keeps `Serves` an accurate reverse-index of "who builds on this"
     instead of naming aspirational/indirect consumers. Only `uc.*.md` / `en.*.md` link targets are checked;
     prose ("*planned* group flows") and directory/ADR links are ignored by construction.
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
FEATURES = ROOT / "docs" / "features"

LINK = re.compile(r"!?\[[^\]]*\]\(([^)]+)\)")
SKIP_SCHEME = ("http://", "https://", "mailto:", "tel:")

errors: list[str] = []


def rel(p: pathlib.Path) -> str:
    return str(p.relative_to(ROOT))


def link_targets(md: pathlib.Path) -> set[pathlib.Path]:
    """Resolved paths of every relative markdown link in `md` (anchors/titles stripped)."""
    out: set[pathlib.Path] = set()
    for m in LINK.finditer(md.read_text()):
        raw = m.group(1).strip()
        target = raw.split()[0].strip("<>") if raw else ""
        if not target or target.startswith("#") or target.startswith(SKIP_SCHEME):
            continue
        target = target.split("#", 1)[0]
        if not target:
            continue
        out.add((md.parent / target).resolve())
    return out


def field_region(text: str, field: str) -> str | None:
    """The text of a `- **<field> …:** …` bullet incl. its wrapped continuation lines."""
    lines = text.splitlines()
    start = next((i for i, ln in enumerate(lines)
                  if re.match(r"\s*-\s*\*\*" + re.escape(field), ln)), None)
    if start is None:
        return None
    region = [lines[start]]
    for ln in lines[start + 1:]:
        s = ln.lstrip()
        if s.startswith("- **") or s.startswith("## ") or s.startswith("> "):
            break
        region.append(ln)
    return "\n".join(region)


def is_ucen(p: pathlib.Path) -> bool:
    return p.suffix == ".md" and (p.name.startswith("uc.") or p.name.startswith("en."))


features = sorted(d for d in FEATURES.iterdir() if d.is_dir())

# --- Check A: every uc/en file is linked from its feature _index.md -------------------------------
for feature in features:
    index = feature / "_index.md"
    if not index.exists():
        errors.append(f"{rel(feature)}: missing _index.md")
        continue
    linked = link_targets(index)
    for sub in ("use-cases", "enablers"):
        for doc in sorted((feature / sub).glob("*.md")) if (feature / sub).exists() else []:
            if doc.resolve() not in linked:
                errors.append(f"{rel(index)}: does not link {rel(doc)} (every uc/en must appear in its index)")

# --- Check B: enabler Serves → target must link back ----------------------------------------------
for feature in features:
    endir = feature / "enablers"
    if not endir.exists():
        continue
    for en in sorted(endir.glob("en.*.md")):
        region = field_region(en.read_text(), "Serves")
        if region is None:
            errors.append(f"{rel(en)}: enabler has no **Serves** field")
            continue
        # links in the Serves bullet that point at another uc/en doc
        for m in LINK.finditer(region):
            raw = m.group(1).strip()
            target = raw.split()[0].strip("<>") if raw else ""
            if not target or target.startswith("#") or target.startswith(SKIP_SCHEME):
                continue
            resolved = (en.parent / target.split("#", 1)[0]).resolve()
            if not (resolved.exists() and is_ucen(resolved) and resolved != en.resolve()):
                continue  # only assert on uc/en targets; doc_links owns "does it resolve"
            if en.resolve() not in link_targets(resolved):
                errors.append(
                    f"{rel(en)}: Serves names {rel(resolved)}, but that doc does not link back "
                    f"(fix the Serves list or add the back-reference)")

if errors:
    print("FEATURE WIRING VALIDATION FAILED:")
    for e in errors:
        print("  -", e)
    sys.exit(1)

n_uc = sum(1 for f in features for _ in (f / "use-cases").glob("*.md") if (f / "use-cases").exists())
n_en = sum(1 for f in features for _ in (f / "enablers").glob("*.md") if (f / "enablers").exists())
print(f"feature wiring OK: {n_uc} use-case(s) + {n_en} enabler(s) indexed, Serves back-refs symmetric")
