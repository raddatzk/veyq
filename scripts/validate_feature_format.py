#!/usr/bin/env python3
"""Validate that every use-case / enabler doc follows the documented format.

The format is Cockburn-lite, specified in `docs/features/README.md`. This guard is the half of it a
machine can hold. It exists because the format was documented and then drifted anyway: one use-case had
invented its own headings (`- Feature:` / `- Actor:` / `## Goal`), 16 of 36 disagreed on whether the header
bullets were bold, one inlined its bilingual section as `EN / DE` prose, and one wrote its whole flow as
four bold paragraphs with no steps in them at all. None of that is catchable by review at scale, and all of
it costs the next reader.

What it asserts, and why each one is worth a check:

  A. Header bullets exist — a use-case names its ID, actor, goal, preconditions and postconditions; an
     enabler names its type, what it provides, what it serves, and what it depends on. These are the fields
     a reader needs before the flow means anything.
  B. The ID matches the filename. A doc that misnames itself breaks every cross-reference silently.
  C. The required sections exist. For a use-case that is `## Main flow`, `## Alternate / exception flows` and
     `## Related` — a use-case without a flow is not a use-case. **An enabler owes only `## Related`**, see
     below.
  D. **A main flow has numbered steps.** This is the one that earns the guard: a use-case's job is to say *how
     something runs*, and prose paragraphs describing a mechanism are not that. `uc.memory.place` had a rich,
     correct, entirely un-followable flow for months, and nobody could point at what was wrong with it.
  E. A use-case carries `## User impact`: **one** English paragraph saying why the feature exists for the
     user ([ADR 0087](../docs/decisions/0087-published-docs-are-a-starlight-site-docs-is-for-development.md)
     decision 5). The user-facing prose lives only on the docs site's Use pages, so the section is a single
     paragraph, carries no `**EN —**` / `**DE —**` blocks, and the former bilingual heading
     (`What this means for you`) is refused wherever it reappears. Enablers carry neither (they have no
     standalone end-user story). Bought by the move to the site: 74 bilingual sections held German text that
     `docs/` no longer keeps, and nothing would have stopped the next use-case from growing one again.

**Why an enabler owes no `## Main flow`.** `docs/features/README.md` says an enabler carries "the same
Main-flow / exceptions" as a use-case. Measured against the 13 that exist, that is not true and never was:
9 describe a flow (media, tree, the scope enablers), and 4 describe a **model** — a key inventory, a table
layout, an envelope shape. `en.account.key-model` has no steps because there is no sequence to state; forcing
one would mean inventing it. So the rule here is the weaker, true one: an enabler *may* have a flow, and **if
it does, it must be numbered like any other** (D applies to both kinds).

Deliberately NOT asserted: the *content* of any section, and whether `## Quality notes` exists (the format
calls it optional, and a use-case with no invariant worth stating should not be made to invent one).
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
FEATURES = ROOT / "docs" / "features"

UC_HEADERS = ["ID", "Primary actor", "Goal", "Preconditions", "Postconditions"]
EN_HEADERS = ["ID", "Type", "Provides", "Serves", "Depends on"]
UC_SECTIONS = ["Main flow", "Alternate / exception flows", "Related"]
EN_SECTIONS = ["Related"]          # a flow is optional on an enabler — see the module docstring
IMPACT = "User impact"
FORMER = "What this means for you"  # the bilingual section ADR 0087 decision 5 replaced

errors: list[str] = []


def rel(p: pathlib.Path) -> str:
    return str(p.relative_to(ROOT))


def has_header(text: str, field: str) -> bool:
    """A `- **<field>…:** …` bullet (the documented form; bold is part of the format).

    Tolerant of a plural and of a parenthetical qualifier, because both are legitimate: `uc.admin.create-tenant`
    genuinely has two co-equal **Primary actors**, and `Postconditions (success)` is the format's own example.
    The field name is what is asserted, not its grammar.
    """
    return bool(re.search(r"^-\s+\*\*" + re.escape(field) + r"s?\b[^*]*:\*\*", text, re.M))


def section(text: str, name: str) -> str | None:
    """The body of a `## <name>…` section, up to the next `##` (heading suffixes allowed)."""
    m = re.search(r"^##\s+" + re.escape(name) + r".*?$(.*?)(?=^##\s|\Z)", text, re.M | re.S)
    return m.group(1) if m else None


def check(doc: pathlib.Path, is_uc: bool) -> None:
    text = doc.read_text()
    kind = "use-case" if is_uc else "enabler"

    # A — header bullets
    for field in (UC_HEADERS if is_uc else EN_HEADERS):
        if not has_header(text, field):
            errors.append(f"{rel(doc)}: {kind} has no `- **{field}:**` header bullet "
                          f"(docs/features/README.md § format)")

    # B — the ID names the file
    m = re.search(r"^-\s+\*\*ID:\*\*\s*(\S+)", text, re.M)
    if m and m.group(1) != doc.stem:
        errors.append(f"{rel(doc)}: ID '{m.group(1)}' does not match the filename '{doc.stem}'")

    # C — required sections
    for name in (UC_SECTIONS if is_uc else EN_SECTIONS):
        if section(text, name) is None:
            errors.append(f"{rel(doc)}: missing `## {name}` section")

    # D — a main flow, where one exists, must actually be a flow
    flow = section(text, "Main flow")
    if flow is not None and not re.search(r"^\s*\d+\.\s+\S", flow, re.M):
        errors.append(f"{rel(doc)}: `## Main flow` has no numbered steps — a flow describes how something "
                      f"runs, step by step; prose paragraphs about the mechanism belong in `## Quality notes` "
                      f"or in the enabler that owns them")

    # E — one English line of user impact on a use-case; neither it nor the former bilingual section on an enabler
    if section(text, FORMER) is not None:
        errors.append(f"{rel(doc)}: carries the former `## {FORMER}` section — a use-case states its "
                      f"`## {IMPACT}` in one English line, and the user-facing prose lives on the site's Use pages "
                      f"(ADR 0087 decision 5)")
    impact = section(text, IMPACT)
    if is_uc:
        body = (impact or "").strip()
        if impact is None or not body:
            errors.append(f"{rel(doc)}: missing `## {IMPACT}` — one English line on why the feature exists "
                          f"for the user (docs/features/README.md § use-case format)")
        elif "\n\n" in body or re.search(r"\*\*(EN|DE)\s*[—-]", body):
            errors.append(f"{rel(doc)}: `## {IMPACT}` must be one English paragraph, with no language blocks — "
                          f"the plain-language text belongs on the site's Use pages")
    elif impact is not None:
        errors.append(f"{rel(doc)}: an enabler must not carry a `## {IMPACT}` section — its value reaches "
                      f"the user through the use-cases it serves (docs/features/README.md § enabler format)")

ucs = sorted(FEATURES.glob("*/use-cases/uc.*.md"))
ens = sorted(FEATURES.glob("*/enablers/en.*.md"))
for doc in ucs:
    check(doc, is_uc=True)
for doc in ens:
    check(doc, is_uc=False)

if errors:
    print("FEATURE FORMAT VALIDATION FAILED:")
    for e in errors:
        print("  -", e)
    sys.exit(1)

print(f"feature format OK: {len(ucs)} use-case(s) + {len(ens)} enabler(s) — headers, ids, sections, "
      f"numbered flows, one line of user impact")
