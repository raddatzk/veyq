#!/usr/bin/env python3
"""Validate that every relative link in a `.claude/` skill or agent-role doc resolves to a real file.

The gap this closes was opened by the change that shipped it. [`validate_doc_links.py`](validate_doc_links.py)
walks the *documentation* trees (`docs/`, and since 2026-07-27 `changes/`) — never `.claude/`, so the moment the
skills layer (ADR 0046) put pointer-heavy `SKILL.md` files under `.claude/skills/`, their links landed
**outside every guard's reach** — a skill pointing at a moved or renamed canonical doc would rot silently. The
`changes/` widening was the *same* defect surfacing a second time, one tree over. That is exactly the failure
the skills layer must not
reintroduce: [ADR 0046](../docs/decisions/0046-operative-lore-in-skills-and-agents.md) rule 3 makes a skill an
asset *only* while it points at the canonical source instead of restating it, and a dead pointer is a restated
copy's quiet cousin — it stops leading anywhere without saying so.

Same shape as `validate_doc_links.py`, deliberately a **separate** guard over a **different tree** (the agent-
operational layer, not the published/internal docs) so each scopes its own failure and evolves independently.
Registers itself by being a `scripts/validate_*.py` — the trust-chain glob picks it up with no wiring
(`.github/workflows/trust-chain.yml`).

Scans every `*.md` under `.claude/skills/` and `.claude/agents/` for inline markdown links + images
`[text](target)` / `![alt](target)`:
  - external (http/https/mailto/tel) and pure in-page anchors (`#section`) are skipped
  - a `#fragment` and a "title" suffix are stripped before resolving
  - the remainder is resolved relative to the linking file and must exist (file or directory)
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SKILL_ROOTS = [ROOT / ".claude" / "skills", ROOT / ".claude" / "agents"]

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


n = 0
for root in SKILL_ROOTS:
    if not root.exists():
        continue
    for md in sorted(root.rglob("*.md")):
        n += 1
        for target in targets(md.read_text()):
            if not target:
                continue
            resolved = (md.parent / target).resolve()
            if not resolved.exists():
                errors.append(f"{md.relative_to(ROOT)}: dead link → {target}")

if errors:
    print("SKILL LINK VALIDATION FAILED:")
    for e in errors:
        print("  -", e)
    sys.exit(1)

print(f"skill links OK: all relative links in {n} skill/agent doc(s) resolve")
