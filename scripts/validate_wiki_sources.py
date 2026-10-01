#!/usr/bin/env python3
"""Every published wiki page names the docs it speaks for and its other-language pair, and those docs name it back.

What it asserts, per page under a language directory of `wiki/` (`wiki/en/`, `wiki/de/`; the root `Home.md` is
the index and carries no header):
  (a) line 1 is the header `<!-- speaks-for: <repo-relative path>, …; pair: <page relative to wiki/> -->`, or
      with `pair: none — <reason>` in place of a page;
  (b) every `speaks-for` path exists;
  (c) the pair exists and is a page, its own `pair` points back, and both declare the same `speaks-for` set;
  (d) `pair: none` appears only under `wiki/en/`, with a reason — an English operator page may stand alone, a
      translated page cannot;
  (e) every `speaks-for` target under `docs/` contains a markdown link that resolves to the page's English
      side. A doc outside `docs/` (`infra/README.md`) is exempt: it is not where an engineering change to a
      user-facing claim is made.
The header is an HTML comment because `wiki-sync.yml` publishes `wiki/` alone: a relative link into `docs/`
would be dead on the published wiki, and a visible absolute link would put engineering docs in front of the
plain-language reader on every page.

The defect that bought it (inbox #120): `wiki/{en,de}/account-keys-and-recovery.md` told users their password is
sent to the server to log in — the opposite of the design, where the device derives a keypair and shows the server
only the public half, which `en.account.key-model` had always said correctly. The published half had drifted from
the internal half, the worse direction, and it was found because a user asked. Nothing tied the page to the doc,
and the docs claimed a generator assembled the wiki from the use-cases, which never existed.

Its limit — what a green run does NOT prove: that the page and the doc **claim the same thing**. This guard could
not have caught the sentence above; what it buys is an address, so a change to a doc shows the published pages to
re-read. Pinned open in `validate_guard_gaps.py`. Also unchecked: that the declared set is complete (a doc a page
restates but does not declare is invisible here), and the reverse direction — a doc that links a page without
being declared by it. The pairing itself cannot be derived: EN and DE file names are translated, so only the
header says which pages belong together.

A `validate_*.py`, so the trust-chain glob runs it on every push with no build. Runnable locally:

    python3 scripts/validate_wiki_sources.py
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
WIKI = ROOT / "wiki"
EN = "en"  # the one language a page may stand alone in: the operator page is technical and English-only

HEADER = re.compile(r"^<!--\s*speaks-for:\s*(?P<sources>[^;]*?)\s*;\s*pair:\s*(?P<pair>.*?)\s*-->\s*$")
NONE = re.compile(r"^none\s*[—-]\s*(?P<reason>\S.*)$")
LINK = re.compile(r"!?\[[^\]]*\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")


def pages() -> list[pathlib.Path]:
    return sorted(p for lang in WIKI.iterdir() if lang.is_dir() for p in lang.glob("*.md"))


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
headers: dict[pathlib.Path, tuple[list[str], str]] = {}
all_pages = pages()

for page in all_pages:
    first = page.read_text().split("\n", 1)[0]
    m = HEADER.match(first)
    if not m:  # (a)
        errors.append(f"{rel(page)}: no header — line 1 must be "
                      f"`<!-- speaks-for: docs/…md, …; pair: <other-language page> | none — <reason> -->`")
        continue
    sources = [s.strip() for s in m.group("sources").split(",") if s.strip()]
    if not sources:
        errors.append(f"{rel(page)}: `speaks-for` names no doc")
    for s in sources:  # (b)
        if not (ROOT / s).is_file():
            errors.append(f"{rel(page)}: speaks for `{s}`, which does not exist")
    headers[page] = (sources, m.group("pair"))

for page, (sources, pair) in headers.items():
    lang = page.parent.name
    none = NONE.match(pair)
    if pair.startswith("none"):
        if lang != EN:  # (d)
            errors.append(f"{rel(page)}: `pair: none` is allowed only under wiki/{EN}/ — a {lang} page is a "
                          f"translation, so it has an English pair")
        elif not none:
            errors.append(f"{rel(page)}: `pair: none` must say why (`none — <reason>`)")
        continue
    other = WIKI / pair
    if other not in headers:  # (c)
        state = "has no header" if other in all_pages else "is not a wiki page"
        errors.append(f"{rel(page)}: pair `{pair}` {state}")
        continue
    if other.parent.name == lang:
        errors.append(f"{rel(page)}: pair `{pair}` is in the same language")
    back_sources, back = headers[other]
    if WIKI / back != page:
        errors.append(f"{rel(page)}: pair `{pair}` points back at `{back}`, not at this page")
    if set(back_sources) != set(sources):
        only_here = sorted(set(sources) - set(back_sources))
        only_there = sorted(set(back_sources) - set(sources))
        errors.append(f"{rel(page)}: speaks for a different set than its pair `{pair}` — "
                      f"only here: {only_here or '—'}, only there: {only_there or '—'}")

for page, (sources, _pair) in headers.items():  # (e), from the English side: a pair declares the same set
    if page.parent.name != EN:
        continue
    for s in sources:
        doc = ROOT / s
        if s.startswith("docs/") and doc.is_file() and not links_to(doc, page):
            errors.append(f"{rel(page)}: `{s}` does not link back to {rel(page)} — whoever edits that doc "
                          f"must see the published page that speaks for it")

if not all_pages:
    print("WIKI SOURCES VALIDATION FAILED: found no wiki page at all — wiki/ moved?")
    sys.exit(1)

if errors:
    print("WIKI SOURCES VALIDATION FAILED:")
    for e in errors:
        print("  -", e)
    sys.exit(1)

print(f"wiki sources OK: {len(all_pages)} page(s) name their docs and pair, and every docs/ source links back")
