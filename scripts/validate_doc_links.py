#!/usr/bin/env python3
"""Validate that every relative link under `docs/` and `changes/`, and every repository link on the published site
(`site/src/content/docs/`), resolves to a real file.

**Asserts:** every inline markdown link/image in those trees points at a path that exists. The reliable,
false-positive-free half of "are the docs (incl. ADRs) still correct?": a doc linking to a
moved/renamed/deleted file is unambiguously broken, unlike fuzzy prose-drift, which a machine can't judge.

**Bought by:** cr-0038's ADR 0001 finding — a doc pointing at a path that no longer exists. `changes/` was
added to the walk later for the same reason `.claude/` got its own `validate_skill_links.py`: it is a
pointer-heavy tree that sat outside every link guard. `changes/README.md` defines the guard-docstring
contract and is cited from four other files, so a rename would have rotted those pointers silently. `wiki/`
joined with cr-wiki-pages-name-their-source: the published pages carried 36 relative links between themselves
that no run read, so the one surface strangers see was the one whose links nothing checked. When the wiki became
the Starlight site (ADR 0087) its pages stopped linking each other relatively — a link between them is a site URL
under `/veyq/`, which the site's own `npm run check` resolves (starlight-links-validator), so this guard skips it.
What a site page links in the repository is a GitHub URL (`https://github.com/raddatzk/veyq/blob/main/<path>`, the
Security area's whole reading path), and that is read here as the repository path it names: a moved or deleted
doc would otherwise leave a dead link in front of exactly the readers who follow them.

**Limit — resolution, never aptness.** A link that resolves is not thereby a link that points at the *right*
thing. External (http/https/mailto/tel) and pure in-page anchors (`#section`) are skipped, and a `#fragment`
is **stripped before resolving**, so a dead anchor inside an existing file passes (pinned as open gap 6 in
`validate_guard_gaps.py`). And **prose pointers are invisible by construction**: a bare filename in a
sentence (a `validate_*.py` cited in another guard's docstring) and a `§ section-name` citation are not
markdown links, so nothing here — or anywhere — checks them.

**Citing a LINE: write `#L60`, never `:60`.** The fragment is stripped before resolving, so
`…/rotateKeys.ts#L60` resolves to the file *and* still carries the line for a reader — and it is the anchor
GitHub renders. A `:60` is part of the path as far as this guard is concerned, so the link is reported dead.
Measured, both forms in one probe file against this guard: the anchor passed, the colon failed. The cost of
not knowing it is real — cr-change-password-cold-credential-gate shipped ten `:NNN` links in one file and
reddened the run — so the recommendation lives here, where somebody reading the failure will find it.

Scans every `*.md` under those roots for inline markdown links + images `[text](target)` / `![alt](target)`:
  - external (http/https/mailto/tel) and pure in-page anchors (`#section`) are skipped
  - a `#fragment` and a "title" suffix are stripped before resolving
  - the remainder is resolved relative to the linking file and must exist (file or directory)
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SITE = ROOT / "site" / "src" / "content" / "docs"
DOC_ROOTS = [ROOT / "docs", ROOT / "changes", SITE]
SITE_BASE = "/veyq/"  # site/astro.config.mjs `base`: a link under it is a page URL, checked by the site's build
REPO_URL = re.compile(r"^https://github\.com/raddatzk/veyq/(?:blob|tree)/main/(?P<path>[^#?]+)")

LINK = re.compile(r"!?\[[^\]]*\]\(([^)]+)\)")
SKIP_SCHEME = ("http://", "https://", "mailto:", "tel:")

errors: list[str] = []


def targets(text: str, site: bool):
    for m in LINK.finditer(text):
        raw = m.group(1).strip()
        # drop an optional "title": [x](path "the title")
        target = raw.split()[0] if raw else raw
        target = target.strip("<>")
        repo = REPO_URL.match(target) if site else None
        if repo:
            yield ROOT / repo.group("path"), target
            continue
        if not target or target.startswith("#") or target.startswith(SKIP_SCHEME):
            continue
        if site and target.startswith(SITE_BASE):
            continue
        yield None, target.split("#", 1)[0]  # strip any in-page anchor


for md in sorted(p for root in DOC_ROOTS for p in root.rglob("*.md*") if p.suffix in (".md", ".mdx")):
    for absolute, target in targets(md.read_text(), SITE in md.parents):
        if not target:
            continue
        resolved = absolute.resolve() if absolute else (md.parent / target).resolve()
        if not resolved.exists():
            errors.append(f"{md.relative_to(ROOT)}: dead link → {target}")

if errors:
    print("DOC LINK VALIDATION FAILED:")
    for e in errors:
        print("  -", e)
    sys.exit(1)

n = sum(1 for root in DOC_ROOTS for p in root.rglob("*.md*") if p.suffix in (".md", ".mdx"))
print(f"doc links OK: all relative links in {n} doc(s) resolve")
