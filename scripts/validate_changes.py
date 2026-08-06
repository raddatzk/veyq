#!/usr/bin/env python3
"""Validate change-request (CR) metadata + the CR↔doc links.

CRs are named `cr-<slug>.md` — a descriptive slug, no number. The number was dropped once it proved a pure
liability: stamped into immutable git history and code comments, its *only* failure mode was silent reuse of a
since-deleted CR's number (`git log --grep cr-0174` → two unrelated changes). A slug collides visibly and by
meaning instead, and it needs no allocator and no history scan — uniqueness is the filesystem's job. The `cr-`
prefix stays as a pure namespace marker: it is what separates a CR from this dir's `README.md` and any
`review-*.md` finding backlog (the `cr-*.md` glob below is an allowlist, not a blocklist). `changes/README.md`
§ naming owns the why.

Catches: a filename that isn't a lowercase slug, a frontmatter `id` that disagrees with the filename, a
`satisfies:`/`adrs:` pointing at a doc that doesn't exist, and — the subtle one — ADR ids written unquoted. In
YAML a bare `0013` is parsed as **octal 11** and `0009` as a string, so unquoted ADR ids silently mean the
wrong (or no) ADR; they must be quoted 4-digit strings (`adrs: ["0013"]`).

Checks per `changes/cr-<slug>.md`:
  - the filename is `cr-<slug>.md` (lowercase, hyphen-separated, no CR number)
  - frontmatter `id` equals the filename stem (`cr-<slug>`)
  - `status` is one of planned | in-progress | done | superseded
  - every `satisfies:` id resolves to a use-case (docs/features/*/use-cases/uc.<id>.md), an
    enabler (docs/features/*/enablers/en.<id>.md), or a cross-cutting concern
    (docs/crosscutting/cc.<id>.md)
  - every `adrs:` id is a quoted 4-digit string AND resolves to docs/decisions/NNNN-*.md
"""
import pathlib
import re
import sys

try:
    import yaml
except ImportError:
    print("PyYAML is required (pip install pyyaml)", file=sys.stderr)
    sys.exit(2)

ROOT = pathlib.Path(__file__).resolve().parent.parent
CHANGES = ROOT / "changes"
FEATURES = ROOT / "docs" / "features"
DECISIONS = ROOT / "docs" / "decisions"
CROSSCUTTING = ROOT / "docs" / "crosscutting"

# `superseded` = the work landed and was then undone, or its premise fell away (cr-0169: the guard it built was
# deleted the same day, obsoleted by a format change). Without it the only honest options are to lie (`done`)
# or to erase the record — and the record of a wrong turn is the part worth keeping.
KNOWN_STATUS = {"planned", "in-progress", "done", "superseded"}

# A CR filename stem: `cr-` + a lowercase hyphen-separated slug (digits allowed, so an old cr-NNNN name still
# parses — the number is no longer *required* or *allocated*, just no longer forbidden inside a slug).
STEM_RE = re.compile(r"cr-[a-z0-9]+(?:-[a-z0-9]+)*$")

errors: list[str] = []


def frontmatter(text: str) -> dict:
    if not text.startswith("---"):
        return {}
    end = text.find("\n---", 3)
    if end == -1:
        return {}
    return yaml.safe_load(text[3:end]) or {}


# a CR satisfies a use-case (uc.*), an enabler (en.*), or a cross-cutting concern (cc.*) — all three are
# first-class trust-chain targets a CR can close (a cc.* concern has no end-user goal but is still tracked).
satisfiable = ({p.stem for p in FEATURES.glob("*/use-cases/uc.*.md")}
               | {p.stem for p in FEATURES.glob("*/enablers/en.*.md")}
               | {p.stem for p in CROSSCUTTING.glob("cc.*.md")})
adr_ids = {m.group(1) for p in DECISIONS.glob("*.md") if (m := re.match(r"(\d{4})-", p.name))}

count = 0
for cr in sorted(CHANGES.glob("cr-*.md")):
    where = cr.name
    if not STEM_RE.fullmatch(cr.stem):
        errors.append(f"{where}: filename must be cr-<slug>.md (lowercase, hyphen-separated, no CR number)")
        continue
    count += 1

    data = frontmatter(cr.read_text())
    if not data:
        errors.append(f"{where}: missing or malformed YAML frontmatter")
        continue

    # No uniqueness pass is needed: two CRs cannot share a filename (the filesystem enforces it), and `id` is
    # pinned to the stem below — so distinct files have distinct ids by construction.
    if data.get("id") != cr.stem:
        errors.append(f"{where}: id '{data.get('id')}' must equal '{cr.stem}' (the filename stem)")

    status = data.get("status")
    if status not in KNOWN_STATUS:
        errors.append(f"{where}: status '{status}' must be one of {sorted(KNOWN_STATUS)}")

    for sat in data.get("satisfies") or []:
        if sat not in satisfiable:
            errors.append(f"{where}: satisfies '{sat}' resolves to no use-case, enabler or concern doc")

    for adr in data.get("adrs") or []:
        if not isinstance(adr, str):
            errors.append(f"{where}: adr {adr!r} must be a quoted 4-digit string — bare YAML mangles it "
                          f"(0013→octal 11, 0009→str). Write adrs: [\"0013\"].")
            continue
        if not re.fullmatch(r"\d{4}", adr):
            errors.append(f"{where}: adr '{adr}' must be a 4-digit id")
        elif adr not in adr_ids:
            errors.append(f"{where}: adrs '{adr}' resolves to no ADR in docs/decisions/")

if errors:
    print("CHANGE-REQUEST VALIDATION FAILED:")
    for e in errors:
        print("  -", e)
    sys.exit(1)

print(f"changes OK: {count} CR(s), ids/satisfies/adrs resolve")
