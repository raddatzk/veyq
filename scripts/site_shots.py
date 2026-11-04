#!/usr/bin/env python3
"""The docs site's shot list: every `<Screenshot>` a Use page places, and which of its images exist.

The screenshots (ADR 0087 §1), one per language, are `site/src/assets/screenshots/<id>.<en|de>.png`, written by the
capture (`scripts/site_capture.py`);
until a file is there the page renders a placeholder that names the id and what the shot must show
(`site/src/components/Screenshot.astro`). This prints that to-do list: per id the English page it sits on, what it
must show, and which language files exist — then any image file no page places. It reads the `.mdx` sources, not a
build, so it needs no `pnpm install`.

A report, not a guard: it exits 0 with placeholders open. It exits 1 only when it cannot read a tag — an `id` or
`shows` that is not a plain string literal, the form the component asks for — since a list that silently skips a tag
would hide a to-do. It also says where the English and German page of a pair place different ids, which leaves one
language without a shot the other has.

    pnpm run shots            (in site/)
    python3 scripts/site_shots.py
    python3 scripts/site_shots.py --ids     (only the placed ids, one per line: what the capture reads)

The capture that takes the images is `scripts/site_capture.py`; the ids it leaves out, and why, are in
`tests/e2e/shots/left-out.json`.
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
PAGES = ROOT / "site" / "src" / "content" / "docs"
SHOTS = ROOT / "site" / "src" / "assets" / "screenshots"
LANGS = ("en", "de")

TAG = re.compile(r"<Screenshot\b(?P<attrs>[^>]*?)/?>", re.S)
ATTR = re.compile(r"""(?P<name>\w+)\s*=\s*(?:"(?P<dq>[^"]*)"|'(?P<sq>[^']*)'|(?P<expr>\{))""")


def lang_and_path(page: pathlib.Path) -> tuple[str, str]:
    rel = page.relative_to(PAGES).as_posix()
    return ("de", rel[3:]) if rel.startswith("de/") else ("en", rel)


def url(path: str) -> str:
    return "/" + re.sub(r"(^|/)index$", "", path.rsplit(".", 1)[0]) + "/"


errors: list[str] = []
# id -> {"pages": {path}, "shows": {lang: text}}; path is the English path of the page pair
shots: dict[str, dict] = {}
placed: dict[tuple[str, str], set[str]] = {}  # (lang, path) -> ids

for page in sorted(PAGES.rglob("*.mdx")):
    lang, path = lang_and_path(page)
    text = page.read_text()
    for m in TAG.finditer(text):
        line = text.count("\n", 0, m.start()) + 1
        attrs: dict[str, str | None] = {}
        for a in ATTR.finditer(m.group("attrs")):
            attrs[a.group("name")] = a.group("dq") if a.group("dq") is not None else a.group("sq")
        if not (isinstance(attrs.get("id"), str) and isinstance(attrs.get("shows"), str)):
            errors.append(f"{page.relative_to(ROOT)}:{line}: a <Screenshot> needs `id` and `shows` as plain strings")
            continue
        entry = shots.setdefault(attrs["id"], {"pages": set(), "shows": {}})
        entry["pages"].add(path)
        entry["shows"].setdefault(lang, attrs["shows"])
        placed.setdefault((lang, path), set()).add(attrs["id"])

if "--ids" in sys.argv[1:]:
    for e in errors:
        print(e, file=sys.stderr)
    print("\n".join(sorted(shots)))
    sys.exit(1 if errors else 0)

files = {p.name for p in SHOTS.glob("*.png")} if SHOTS.is_dir() else set()

missing = 0
for shot_id in sorted(shots, key=lambda i: (sorted(shots[i]["pages"]), i)):
    entry = shots[shot_id]
    have = {lang: f"{shot_id}.{lang}.png" in files for lang in LANGS}
    missing += sum(not h for h in have.values())
    state = "  ".join(f"{lang} {'yes' if have[lang] else '--'}" for lang in LANGS)
    print(f"{shot_id}  [{state}]  {', '.join(url(p) for p in sorted(entry['pages']))}")
    print(f"    {entry['shows'].get('en') or entry['shows'].get('de')}")

paths = {path for _, path in placed}
for path in sorted(paths):
    en, de = placed.get(("en", path), set()), placed.get(("de", path), set())
    if en != de:
        print(f"\n{url(path)}: the English and German page place different shots"
              f" (English only: {sorted(en - de) or '-'}; German only: {sorted(de - en) or '-'})")

orphans = sorted(f for f in files if f.rsplit(".", 2)[0] not in shots)
if orphans:
    print("\nImages no page places: " + ", ".join(orphans))

print(f"\n{len(shots)} screenshot(s), {missing} of {len(shots) * len(LANGS)} language files still to supply.")

for e in errors:
    print(e, file=sys.stderr)
sys.exit(1 if errors else 0)
