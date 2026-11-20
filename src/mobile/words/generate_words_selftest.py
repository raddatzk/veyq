#!/usr/bin/env python3
"""Self-test of generate_words.py's manifest outputs (`--apple-infoplist`, `--metainfo`) and the catalog rules they rest
on, against the real catalog and the real metainfo template, offline. Exit 0 green, 1 red.

The platform outputs (`--kotlin`, `--android-res`, `--apple-strings`, `--gettext`, `--resw`) are held by each
platform's own words test against what its build compiles; the manifests have no build of their own that reads a
language, so they are held here.
"""
from __future__ import annotations

import json
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import generate_words as g  # noqa: E402

TEMPLATE = HERE.parent / "linux" / "flatpak" / "metainfo.xml"
XML_LANG = "{http://www.w3.org/XML/1998/namespace}lang"
failures: list[str] = []


def check(cond: bool, what: str) -> None:
    if not cond:
        failures.append(what)


def refused(words: dict, what: str) -> None:
    """[words] (a catalog's entries) is refused by load()'s rules."""
    saved = g.CATALOG
    with tempfile.TemporaryDirectory() as d:
        g.CATALOG = Path(d) / "words.json"
        g.CATALOG.write_text(json.dumps(words), encoding="utf-8")
        try:
            g.load()
            failures.append(f"load() accepted {what}")
        except g.CatalogError:
            pass
        finally:
            g.CATALOG = saved


def main() -> int:
    words = g.load()
    with tempfile.TemporaryDirectory() as d:
        out = Path(d)

        # Apple: every `apple_infoplist` key in the String Catalog under its Info.plist key, English and German, and its
        # English in the config file the project reads.
        g.apple_infoplist(words, out)
        catalog = json.loads((out / "InfoPlist.xcstrings").read_text(encoding="utf-8"))
        config = (out / "InfoPlist.xcconfig").read_text(encoding="utf-8")
        marked = {e["apple_infoplist"]: e for e in words.values() if "apple_infoplist" in e}
        check({"NSCameraUsageDescription", "NSPhotoLibraryAddUsageDescription"} <= set(marked),
              "the catalog marks the camera's and the photo library's usage descriptions")
        check(set(catalog["strings"]) == set(marked), f"InfoPlist.xcstrings holds exactly the marked keys: {sorted(catalog['strings'])}")
        for name, entry in marked.items():
            locs = catalog["strings"].get(name, {}).get("localizations", {})
            for lang in g.LANGS:
                check(locs.get(lang, {}).get("stringUnit", {}).get("value") == entry[lang], f"{name}.{lang} in InfoPlist.xcstrings")
            check(f"INFOPLIST_KEY_{name} = {entry['en']}\n" in config, f"{name}'s English in InfoPlist.xcconfig")
        check(catalog["sourceLanguage"] == "en", "InfoPlist.xcstrings' source language is English")

        # The Flatpak: the template's markers become the summary and each paragraph, in English and then German; the
        # rest of the template stays, its own placeholders for run.py to fill.
        g.metainfo(words, TEMPLATE, out / "metainfo.xml")
        text = (out / "metainfo.xml").read_text(encoding="utf-8")
        check(not any(line.strip() in ("@SUMMARY@", "@DESCRIPTION@") for line in text.split("\n")), "the markers are replaced")
        check("@APP_ID@" in text and "@VERSION@" in text, "the template's own placeholders stay for run.py")
        root = ET.fromstring(text)
        summary = {s.get(XML_LANG, "en"): s.text for s in root.findall("summary")}
        want = next(e for e in words.values() if e.get("metainfo") == "summary")
        check(summary == {lang: want[lang] for lang in g.LANGS}, f"the summary in English and German: {summary}")
        paras = [(p.get(XML_LANG, "en"), p.text) for p in root.findall("description/p")]
        want_paras = [(lang, e[lang]) for e in words.values() if e.get("metainfo") == "description" for lang in g.LANGS]
        check(paras == want_paras and len(paras) >= 2, f"the description's paragraphs, each English then German: {paras}")

        # A template without its marker is refused, never written without the words.
        bare = out / "bare.xml"
        bare.write_text(TEMPLATE.read_text(encoding="utf-8").replace("@SUMMARY@", ""), encoding="utf-8")
        try:
            g.metainfo(words, bare, out / "never.xml")
            failures.append("a template without @SUMMARY@ was accepted")
        except g.CatalogError:
            pass
        check(not (out / "never.xml").exists(), "nothing written from a template without its marker")

    # The rules a manifest's key is held to.
    base = {"en": "Text", "de": "Text"}
    refused({"a": {**base, "en": "Uses {1}", "de": "Nutzt {1}", "apple_infoplist": "NSCameraUsageDescription"}},
            "a manifest key with a placeholder")
    refused({"a": {"en": {"one": "a", "other": "b"}, "de": {"one": "a", "other": "b"}, "metainfo": "description"}},
            "a plural manifest key")
    refused({"a": {**base, "apple_infoplist": "camera usage"}}, "an `apple_infoplist` that names no Info.plist key")
    refused({"a": {**base, "apple_infoplist": "NSCameraUsageDescription"},
             "b": {**base, "apple_infoplist": "NSCameraUsageDescription"}}, "two keys for one Info.plist key")
    refused({"a": {**base, "metainfo": "summary"}, "b": {**base, "metainfo": "summary"}}, "two metainfo summaries")
    refused({"a": {**base, "metainfo": "title"}}, "a `metainfo` that is no summary or description")
    try:
        with tempfile.TemporaryDirectory() as d:
            g.apple_infoplist({"a": {**base, "en": "See $(HOME) // here", "apple_infoplist": "NSCameraUsageDescription"}}, Path(d))
        failures.append("an Info.plist word an xcconfig cannot hold was accepted")
    except g.CatalogError:
        pass

    for f in failures:
        print("FAILED:", f)
    print("generate_words self-test:", "red" if failures else "green")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
