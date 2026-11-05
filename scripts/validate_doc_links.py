#!/usr/bin/env python3
"""Validate that every relative link in a Markdown file of the repository, and every repository link on the published
site (`site/src/content/docs/`), resolves to a real file.

**Asserts:** every inline markdown link/image in every `*.md`/`*.mdx` the tree holds outside `node_modules/` points at
a path that exists. The reliable,
false-positive-free half of "are the docs (incl. ADRs) still correct?": a doc linking to a
moved/renamed/deleted file is unambiguously broken, unlike fuzzy prose-drift, which a machine can't judge.

**Bought by:** cr-0038's ADR 0001 finding — a doc pointing at a path that no longer exists. `changes/` was
added to the walk later for the same reason `.claude/` got its own `validate_skill_links.py`: it is a
pointer-heavy tree that sat outside every link guard. `changes/README.md` defines the guard-docstring
contract and is cited from four other files, so a rename would have rotted those pointers silently. `wiki/`
joined with cr-wiki-pages-name-their-source: the published pages carried 36 relative links between themselves
that no run read, so the one surface strangers see was the one whose links nothing checked. When the wiki became
the Starlight site (ADR 0087) its pages stopped linking each other relatively — a link between them is a site URL
from the root (`/run/upgrade/`; the site has no `base` since it moved to https://veyq.app/), which the site's own
`pnpm run check` resolves (starlight-links-validator), so this guard skips it.
What a site page links in the repository is a GitHub URL (`https://github.com/raddatzk/veyq/blob/main/<path>`, the
Security area's whole reading path), and that is read here as the repository path it names: a moved or deleted
doc would otherwise leave a dead link in front of exactly the readers who follow them. The walk was three named trees
until cr-repo-layout-src widened it to every Markdown file: stage 1 of ADR 0106 moved the deployment files and the
links that broke sat in the root `README.md`, a surface's `CLAUDE.md` and `infra/README.md` — files no root named.

**Limit — resolution, never aptness.** A link that resolves is not thereby a link that points at the *right*
thing. A link in a code comment (a `.ts`, `.kt` or `.py` file) is not read, and neither is one inside an inline code
span, which is text, not a link (a regex in a code span reads as one). External (http/https/mailto/tel) and pure in-page anchors (`#section`) are skipped, and a `#fragment`
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

The files: `git ls-files --cached --others --exclude-standard` in a checkout (what is tracked, and what is new and not
ignored); in a tree without `.git` — `validate_guard_gaps.py`'s copy, made of exactly that list — every file under it.
Scans each for inline markdown links + images `[text](target)` / `![alt](target)`, inline code spans removed first:
  - external (http/https/mailto/tel) and pure in-page anchors (`#section`) are skipped
  - a `#fragment` and a "title" suffix are stripped before resolving
  - the remainder is resolved relative to the linking file and must exist (file or directory)
"""
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SITE = ROOT / "site" / "src" / "content" / "docs"
SUFFIXES = (".md", ".mdx")
# site/astro.config.mjs's `base` (none: the root): a site page's link under it is a page URL, checked by the site's build
SITE_BASE = "/"
REPO_URL = re.compile(r"^https://github\.com/raddatzk/veyq/(?:blob|tree)/main/(?P<path>[^#?]+)")

LINK = re.compile(r"!?\[[^\]]*\]\(([^)]+)\)")
# An inline code span on one line: removed before the scan, so a regex or a literal in backticks is not read as a link.
CODE_SPAN = re.compile(r"`[^`\n]*`")
SKIP_SCHEME = ("http://", "https://", "mailto:", "tel:")

errors: list[str] = []


def markdown_files() -> list[pathlib.Path]:
    """Every Markdown file of the tree outside `node_modules/` (the module docstring says which tree)."""
    if (ROOT / ".git").exists():
        out = subprocess.run(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
                             cwd=ROOT, capture_output=True, text=True)
        if out.returncode != 0:
            print(f"DOC LINK VALIDATION FAILED: `git ls-files` failed, so no file was read: {out.stderr.strip()}")
            sys.exit(1)
        paths = [ROOT / p for p in out.stdout.split("\0") if p]
    else:
        paths = [p for p in ROOT.rglob("*") if p.is_file()]
    return sorted(p for p in paths if p.suffix in SUFFIXES and "node_modules" not in p.relative_to(ROOT).parts
                  and p.is_file())


def targets(text: str, site: bool):
    for m in LINK.finditer(CODE_SPAN.sub("", text)):
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


files = markdown_files()
if not any(SITE in p.parents for p in files) or not any(p.parent == ROOT for p in files):
    print("DOC LINK VALIDATION FAILED: the walk found no site page or no root Markdown file — it reads the wrong tree")
    sys.exit(1)
for md in files:
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

print(f"doc links OK: all relative links in {len(files)} Markdown file(s) resolve")
