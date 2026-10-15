#!/usr/bin/env python3
"""Every published site page names the docs it speaks for, its German translation declares the same ones, and those
docs name the page back.

The name is the wiki's, which this guard was built for (cr-wiki-pages-name-their-source); since ADR 0087 the
published pages are the Starlight site's, `site/src/content/docs/` (English at the root, German under `de/` at the
English path — which is how Starlight pairs a page with its translation, so the pair needs no declaring).

What it asserts, per page (`*.md` / `*.mdx`) under that directory:
  (a) the page opens with a YAML frontmatter block, and — except the landing pair `index.md` / `de/index.md`, which
      speaks for no doc — declares `sources:`, a non-empty list of repo-relative paths;
  (b) every declared path exists;
  (c) a German page translates an English page at the same path, and both declare the same `sources` set;
  (d) an English page outside the English-only areas has its German translation. The areas that are English by
      design (ADR 0087 §1) are `run/` (administrators) and `security/` (researchers); there, a missing German page
      is Starlight's fallback, not a gap;
  (e) every declared source under `docs/` contains a markdown link that resolves to the English page's file. A
      source outside `docs/` (`infra/README.md`) is exempt: it is not where an engineering change to a user-facing
      claim is made.

The defect that bought it (inbox #120): `wiki/{en,de}/account-keys-and-recovery.md` told users their password is
sent to the server to log in — the opposite of the design, where the device derives a keypair and shows the server
only the public half, which `en.account.key-model` had always said correctly. The published half had drifted from
the internal half, the worse direction, and it was found because a user asked. Nothing tied the page to the doc,
and the docs claimed a generator assembled the wiki from the use-cases, which never existed.

Its limit — what a green run does NOT prove: that the page and the doc **claim the same thing**. This guard could
not have caught the sentence above; what it buys is an address, so a change to a doc shows the published pages to
re-read. Pinned open in `validate_guard_gaps.py`. Also unchecked: that the declared set is complete (a doc a page
restates but does not declare is invisible here), and the reverse direction — a doc that links a page without
being declared by it. Links between the pages themselves are the site's own check (`npm run check` in `site/`).

A `validate_*.py`, so the trust-chain glob runs it on every push with no build. Runnable locally:

    python3 scripts/validate_wiki_sources.py
"""
from __future__ import annotations

import pathlib
import re
import sys

try:
    import yaml
except ModuleNotFoundError:  # pragma: no cover - same shape as the other guards
    print("PyYAML is required (pip install pyyaml)", file=sys.stderr)
    sys.exit(2)

ROOT = pathlib.Path(__file__).resolve().parent.parent
PAGES = ROOT / "site" / "src" / "content" / "docs"
DE = "de"  # the one translation locale; English is the root
LANDING = {"index.md", "de/index.md"}  # the landing pair lists the pages and speaks for no doc
ENGLISH_ONLY = ("run/", "security/")  # areas English by design (ADR 0087 §1): Run and Security

FRONTMATTER = re.compile(r"\A---\n(?P<yaml>.*?)\n---\n", re.S)
LINK = re.compile(r"!?\[[^\]]*\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")


def pages() -> list[pathlib.Path]:
    return sorted(p for p in PAGES.rglob("*") if p.suffix in {".md", ".mdx"} and p.is_file())


def rel(p: pathlib.Path) -> str:
    return str(p.relative_to(ROOT))


def links_to(doc: pathlib.Path, page: pathlib.Path) -> bool:
    for m in LINK.finditer(doc.read_text()):
        target = m.group(1).strip("<>").split("#", 1)[0]
        if target and not target.startswith(("http://", "https://", "mailto:")):
            if (doc.parent / target).resolve() == page.resolve():
                return True
    return False


errors: list[str] = []
declared: dict[str, list[str]] = {}  # path under PAGES -> declared sources
all_pages = pages()

for page in all_pages:
    path = page.relative_to(PAGES).as_posix()
    m = FRONTMATTER.match(page.read_text())
    if not m:  # (a)
        errors.append(f"{rel(page)}: no frontmatter — a page opens with `---`, its `title:` and `sources:`, `---`")
        continue
    try:
        front = yaml.safe_load(m.group("yaml")) or {}
    except yaml.YAMLError as e:
        errors.append(f"{rel(page)}: frontmatter does not parse ({str(e).splitlines()[0]})")
        continue
    sources = front.get("sources") if isinstance(front, dict) else None
    if path in LANDING:
        continue
    if not isinstance(sources, list) or not sources or not all(isinstance(s, str) and s.strip() for s in sources):
        errors.append(f"{rel(page)}: `sources:` must list the docs this page speaks for (repo-relative paths)")
        continue
    for s in sources:  # (b)
        if not (ROOT / s).is_file():
            errors.append(f"{rel(page)}: speaks for `{s}`, which does not exist")
    declared[path] = sources

for path, sources in declared.items():
    if path.startswith(DE + "/"):  # (c)
        english = path[len(DE) + 1:]
        if english not in declared:
            state = "declares no sources" if (PAGES / english).is_file() else "does not exist"
            errors.append(f"{rel(PAGES / path)}: translates `{english}`, which {state} — a German page sits at its "
                          f"English page's path")
            continue
        if set(sources) != set(declared[english]):
            only_here = sorted(set(sources) - set(declared[english]))
            only_there = sorted(set(declared[english]) - set(sources))
            errors.append(f"{rel(PAGES / path)}: speaks for a different set than its English page `{english}` — "
                          f"only here: {only_here or '—'}, only there: {only_there or '—'}")
    elif not path.startswith(ENGLISH_ONLY) and f"{DE}/{path}" not in declared:  # (d)
        errors.append(f"{rel(PAGES / path)}: has no German translation at `{DE}/{path}` — only "
                      f"{', '.join(ENGLISH_ONLY)} are English-only by design")

for path, sources in declared.items():  # (e), from the English side: a translation declares the same set
    if path.startswith(DE + "/"):
        continue
    page = PAGES / path
    for s in sources:
        doc = ROOT / s
        if s.startswith("docs/") and doc.is_file() and not links_to(doc, page):
            errors.append(f"{rel(page)}: `{s}` does not link back to {rel(page)} — whoever edits that doc "
                          f"must see the published page that speaks for it")

if not declared:
    print("SITE SOURCES VALIDATION FAILED: found no site page declaring sources — site/src/content/docs moved?")
    sys.exit(1)

if errors:
    print("SITE SOURCES VALIDATION FAILED:")
    for e in errors:
        print("  -", e)
    sys.exit(1)

print(f"site sources OK: {len(declared)} page(s) name their docs, each German page its English page's set, and "
      f"every docs/ source links back")
