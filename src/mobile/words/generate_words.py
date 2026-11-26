#!/usr/bin/env python3
"""Generates every platform's words from the apps' one catalog (ADR 0161 decision 3).

`words.json` beside this script is the source: per key its English (`en`) and German (`de`) text — a plural is an
object of CLDR categories (`one`, `other`) — and an optional `note` for translators. A placeholder is `{n}` (a string)
or `{n:int}` (an integer), numbered from 1 in the order the English text gives them; every language uses the same set.
A key without `de` is red unless it says `"untranslated": true` (a name such as `veyq`). `apple_en` / `apple_de` is a
key's form on Apple where its guidelines word it otherwise (Title Case on buttons and menu entries); the same placeholders.
`"core_text": true` marks a key whose words the core itself renders, not a platform: a text the core writes into a file
(the Emergency Kit's PDF, ADR 0148 decision 5), never one shown on a screen. Such a key is no plural.
`"apple_infoplist": "<Info.plist key>"` marks a key a manifest shows on Apple (a permission prompt's usage description,
`NSCameraUsageDescription`), `"metainfo": "summary"` / `"description"` one the Flatpak's AppStream metainfo shows (its one
summary; the description's paragraphs in the catalog's order): such a key is no plural and has no placeholders.

The apps never write a word of their own: this script writes what each platform reads, and the builds run it.

Outputs (each optional; each a directory or file this script owns and rewrites whole):
  --kotlin DIR          `me/raddatz/veyq/mobile/words/Msg.kt`: every key as a word of the core's `Msg` (a getter of its
                        companion, never an enum: inbox #1341), with its placeholder count and whether it is a
                        plural — what the core's screen models raise; and
                        `me/raddatz/veyq/mobile/words/CoreText.kt`: the English and German text of every `core_text`
                        key, `CoreText.of(msg, language, args)` filling its placeholders by number — what the core
                        writes itself.
  --android-res DIR     `values/veyq_words.xml`, `values-de/veyq_words.xml`: `<string>` and `<plurals>` under the key's
                        own name, so `R.string.<key>` is the key.
  --android-kotlin DIR  `me/raddatz/veyq/mobile/android/Words.kt`: `Context.words(msg, args)`, a `Msg` to its resource
                        (an array of ids in the catalog's order).
  --apple-strings FILE  a String Catalog (table `Words`): the Apple app's `say(msg, args)` looks the key up there.
  --apple-swift FILE    `Msg+Words.swift`: each word a static member of `Msg` in Swift (`Msg.trashMoved`,
                        `say(.trashMoved)`), through the companion Kotlin/Native exports the core's `Msg` class with (inbox #1341).
  --gettext DIR         the Linux app's (ADR 0123's Consequences, the Linux app): `veyq.pot` and one `<lang>.po` per language
                        (`msgctxt` the key, `msgid` the English, `msgstr` the language's; a plural's CLDR categories
                        mapped to the language's `Plural-Forms`, a language without a known mapping refused), which the
                        app's build compiles with `msgfmt`, and `words.rs`: one function per key with typed arguments
                        (`{n}` a `&str`, `{n:int}` an `i64`, a plural's count first) looking the key up in the domain
                        `veyq`, and `say(key, args)` reaching the same function by its key (a core `Notice`).
  --resw DIR            the Windows app's (ADR 0123's Consequences, the Windows app): `Strings/<culture>/Resources.resw`
                        per language (`en-US`, `de-DE`; the resource's name the key, a plural one entry per CLDR category
                        its language's rule names, `key#one`, `key#other`; a name the English of an untranslated key in
                        every language) with `_language` naming the language whose rule the plurals follow, and
                        `Words.g.cs`: one method per key with typed arguments (`{n}` a `string`, `{n:int}` a `long`, a
                        plural's count first), its placeholders filled by number, the plural's category picked by the
                        rule of the language the catalog that answered names (a language without a rule refused), and
                        `Say(key, args)` reaching the same method by its key (a core `Notice`).
  --apple-infoplist DIR the Apple app's manifest words: `InfoPlist.xcstrings` (each `apple_infoplist` key's English and
                        German under its Info.plist key, which Xcode compiles into `<lang>.lproj/InfoPlist.strings`) and
                        `InfoPlist.xcconfig` (`INFOPLIST_KEY_<key> = <English>`: the generated Info.plist's own value,
                        the fallback for a language the app has not).
  --metainfo TEMPLATE OUT
                        the Flatpak's AppStream metainfo: TEMPLATE with its line `@SUMMARY@` replaced by the `summary`
                        key's `<summary>` and its line `@DESCRIPTION@` by each `description` key's `<p>`, every one
                        in English and then each other language's (`xml:lang`); the rest of TEMPLATE as it is (its own
                        `@APP_ID@`, `@VERSION@` and `@DATE@` are `run.py`'s).
  --web FILE            the web's share of the catalog (inbox #1060): the keys `web.json`'s `read` names (the words the
                        core's notices raise, ADR 0155 decision 7.2), as JSON `{"en": {key: message}, "de": {…}}` in
                        vue-i18n's message syntax — a placeholder `{n}` / `{n:int}` the list interpolation `{n-1}`, a
                        plural its forms joined by ` | ` (`zero`, `one`, `other` as present), `{`, `}`, `@`, `|` and `$`
                        as literal interpolations, `%%` one `%`; a key without German its English. The web registers
                        it as its `words` namespace; the file is committed, and `scripts/validate_web_words.py` keeps
                        it equal to this output.

Python 3.9 (Xcode's and Gradle's `python3` on the developer's Mac), standard library only.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

CATALOG = Path(__file__).resolve().parent / "words.json"
WEB_LINKS = Path(__file__).resolve().parent / "web.json"
LANGS = ("en", "de")
PLURAL_CATEGORIES = ("zero", "one", "two", "few", "many", "other")
PLACEHOLDER = re.compile(r"\{(\d+)(:int)?\}")


class CatalogError(Exception):
    pass


def _no_duplicate_keys(pairs: list) -> dict:
    # json keeps the last of two equal keys silently; git's merge writes exactly that when both sides add the same
    # key, without a conflict (block F's phases did, 2026-11-19), so a duplicate is refused here, not read.
    seen = set()
    for key, _ in pairs:
        if key in seen:
            raise CatalogError(f"{key}: the catalog holds this key twice (a merge's leftover?) — keep one")
        seen.add(key)
    return dict(pairs)


def load() -> dict:
    words = json.loads(CATALOG.read_text(encoding="utf-8"), object_pairs_hook=_no_duplicate_keys)
    for key, entry in words.items():
        if not re.fullmatch(r"[a-z][a-z0-9_]*", key):
            raise CatalogError(f"{key}: a key is lower snake case")
        if "en" not in entry:
            raise CatalogError(f"{key}: no English text")
        if "de" not in entry and not entry.get("untranslated"):
            raise CatalogError(f"{key}: no German text (mark a name `\"untranslated\": true`)")
        shapes = {}
        for lang in LANGS + tuple(f"apple_{l}" for l in LANGS):
            if lang not in entry:
                continue
            v = entry[lang]
            texts = list(v.values()) if isinstance(v, dict) else [v]
            if isinstance(v, dict) and (not v or any(c not in PLURAL_CATEGORIES for c in v) or "other" not in v):
                raise CatalogError(f"{key}.{lang}: a plural names CLDR categories and always `other`")
            shapes[lang] = (isinstance(v, dict), placeholders_of(texts, f"{key}.{lang}"))
        if len(set(shapes.values())) > 1:
            raise CatalogError(f"{key}: its languages differ in plural shape or placeholders: {shapes}")
        if "core_text" in entry and entry["core_text"] is not True:
            raise CatalogError(f"{key}: `core_text` is `true` or absent")
        if entry.get("core_text") and isinstance(entry["en"], dict):
            raise CatalogError(f"{key}: a `core_text` key is no plural (the core picks no plural form)")
        manifest = [f for f in ("apple_infoplist", "metainfo") if f in entry]
        if manifest and (isinstance(entry["en"], dict) or any(shape[1] for shape in shapes.values())):
            raise CatalogError(f"{key}: a manifest's key ({manifest[0]}) is no plural and has no placeholders")
        if "apple_infoplist" in entry and not re.fullmatch(r"[A-Z][A-Za-z]+", str(entry["apple_infoplist"])):
            raise CatalogError(f"{key}: `apple_infoplist` names an Info.plist key, such as NSCameraUsageDescription")
        if entry.get("metainfo", "summary") not in ("summary", "description"):
            raise CatalogError(f"{key}: `metainfo` is `summary` or `description`")
    infoplist = [e["apple_infoplist"] for e in words.values() if "apple_infoplist" in e]
    if len(set(infoplist)) < len(infoplist):
        raise CatalogError(f"two keys name the same Info.plist key: {sorted(k for k in infoplist if infoplist.count(k) > 1)}")
    if sum(e.get("metainfo") == "summary" for e in words.values()) > 1:
        raise CatalogError("more than one key is the metainfo's `summary`")
    return words


def placeholders_of(texts: list, where: str) -> tuple:
    found = {}
    for t in texts:
        for m in PLACEHOLDER.finditer(t):
            n, typed = int(m.group(1)), bool(m.group(2))
            if found.get(n, typed) != typed:
                raise CatalogError(f"{where}: placeholder {n} is a string in one place and an integer in another")
            found[n] = typed
    if found and sorted(found) != list(range(1, max(found) + 1)):
        raise CatalogError(f"{where}: placeholders are numbered 1..n without gaps, found {sorted(found)}")
    return tuple(found[n] for n in sorted(found))


def is_plural(entry: dict) -> bool:
    return isinstance(entry["en"], dict)


def arity(entry: dict) -> int:
    texts = list(entry["en"].values()) if is_plural(entry) else [entry["en"]]
    return len(placeholders_of(texts, "en"))


# ---- Kotlin (core) ----

def kotlin(words: dict, out: Path) -> None:
    # Not an enum (inbox #1341): Kotlin/JS builds every entry of an enum in one initializer the first time any entry is
    # read, so the web's every first load carried all of them (~27 KB gz of the platform module); a getter per word is
    # one the JavaScript link drops wherever nothing reads it. What the apps read of the enum stays: `Msg.X`, `key`,
    # `args`, `plural`, `name`, `entries`, equality, `toString()`, and the wire's enum by name (`MsgName`).
    lines = [
        "package me.raddatz.veyq.mobile.words",
        "",
        "/**",
        " * Every word the apps show, by its key in `src/mobile/words/words.json` (generated by `generate_words.py`, never",
        " * edited): what the core's screen models raise; each platform renders it in the person's language.",
        " * [args] is the placeholder count, [plural] whether the first is the count a plural form is chosen by.",
        " *",
        " * A class of one getter per word, not an enum (inbox #1341, ADR 0161 § The words' form for JavaScript): a",
        " * Kotlin/JS enum builds all its entries the first time one is read, so every first load of the web carried all of",
        " * them; a getter nothing reads is dropped by the JavaScript link. Two words are equal by their [key]; [name] and",
        " * [entries] are what the enum's were, and on the wire a word is the enum by its name it was ([MsgName]) unless a",
        " * field says [MsgKey].",
        " */",
        "@kotlinx.serialization.Serializable(with = MsgName::class)",
        "class Msg private constructor(val key: String, val args: Int, val plural: Boolean) {",
        "    /** The word's name as an enum's entry had it: its key in capitals (`TRASH_MOVED`). */",
        "    val name: String get() = key.uppercase()",
        "",
        "    override fun equals(other: Any?): Boolean = other is Msg && other.key == key",
        "    override fun hashCode(): Int = key.hashCode()",
        "    override fun toString(): String = name",
        "",
        "    companion object {",
    ]
    for key, entry in words.items():
        lines.append(f'        val {key.upper()}: Msg get() = Msg("{key}", {arity(entry)}, {"true" if is_plural(entry) else "false"})')
    lines += [
        "",
        "        /** Every word, in the catalog's order (as an enum's entries). */",
        "        val entries: List<Msg> get() = ENTRIES",
        "    }",
        "}",
        "",
        "private val ENTRIES: List<Msg> by lazy {",
        "    listOf(",
        *[f"        Msg.{key.upper()}," for key in words],
        "    )",
        "}",
        "",
    ]
    write(out / "me/raddatz/veyq/mobile/words/Msg.kt", "\n".join(lines))
    core_text(words, out)


def kotlin_str(t: str) -> str:
    t = desktop_text(t)
    return '"' + t.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$").replace("\n", "\\n").replace("\t", "\\t") + '"'


def core_text(words: dict, out: Path) -> None:
    lines = [
        "package me.raddatz.veyq.mobile.words",
        "",
        "/**",
        " * The words the core writes itself, not a platform (`\"core_text\": true` in `src/mobile/words/words.json`; generated",
        " * by `generate_words.py`, never edited): the Emergency Kit's PDF is written in the person's language by the core.",
        " */",
        "object CoreText {",
        "    /**",
        "     * [msg]'s text in [language] (a language tag: `de` and `de-*` German, anything else English), its placeholders",
        "     * `{n}` filled by number from [args]; an IllegalArgumentException for a key that is no `core_text` one or for",
        "     * another count of [args] than its placeholders.",
        "     */",
        "    fun of(msg: Msg, language: String, vararg args: String): String {",
        "        val german = language.lowercase().substringBefore('-').substringBefore('_') == \"de\"",
        "        require(args.size == msg.args) { \"${msg.key} takes ${msg.args} arguments, not ${args.size}\" }",
        "        val text = when (msg.key) {",
    ]
    for key, entry in words.items():
        if not entry.get("core_text"):
            continue
        en = entry["en"]
        de = entry.get("de", en)
        lines.append(f'            "{key}" -> if (german) {kotlin_str(de)} else {kotlin_str(en)}')
    lines += [
        "            else -> throw IllegalArgumentException(\"${msg.key} is no core_text key\")",
        "        }",
        "        return PLACEHOLDER.replace(text) { m -> args[m.groupValues[1].toInt() - 1] }",
        "    }",
        "",
        "    private val PLACEHOLDER = Regex(\"\"\"\\{(\\d+)(?::int)?\\}\"\"\")",
        "}",
        "",
    ]
    write(out / "me/raddatz/veyq/mobile/words/CoreText.kt", "\n".join(lines))


# ---- Android ----

def android_text(t: str) -> str:
    t = PLACEHOLDER.sub(lambda m: f"%{m.group(1)}${'d' if m.group(2) else 's'}", t)
    t = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    t = t.replace("\\", "\\\\").replace("'", "\\'").replace('"', '\\"').replace("\n", "\\n")
    if t[:1] in ("@", "?"):
        t = "\\" + t
    return t


def android_res(words: dict, out: Path) -> None:
    for lang, folder in (("en", "values"), ("de", "values-de")):
        lines = ['<?xml version="1.0" encoding="utf-8"?>', "<!-- Generated from src/mobile/words/words.json by generate_words.py; never edited. -->", "<resources>"]
        for key, entry in words.items():
            if lang not in entry:
                continue
            v = entry[lang]
            if isinstance(v, dict):
                lines.append(f'    <plurals name="{key}">')
                for cat, text in v.items():
                    lines.append(f'        <item quantity="{cat}">{android_text(text)}</item>')
                lines.append("    </plurals>")
            else:
                lines.append(f'    <string name="{key}">{android_text(v)}</string>')
        lines += ["</resources>", ""]
        write(out / folder / "veyq_words.xml", "\n".join(lines))


def android_kotlin(words: dict, out: Path) -> None:
    # A resource id per word in the catalog's order, which is `Msg.entries`' order: a `when` over 1,574 keys is a method
    # larger than the JVM allows, an array of ids is one field read (inbox #1341 made `Msg` a class, no enum to switch on).
    lines = [
        "package me.raddatz.veyq.mobile.android",
        "",
        "import android.content.Context",
        "import me.raddatz.veyq.mobile.words.Msg",
        "",
        "// Generated from src/mobile/words/words.json by generate_words.py; never edited.",
        "",
        "/** [msg] in the person's language, its placeholders filled from [args] (a plural's count first). */",
        "internal fun Context.words(msg: Msg, vararg args: Any): String {",
        "    val a = args.map { if (it is Long) it.toInt() else it }.toTypedArray()",
        "    // every Msg is generated from the same catalog, so each has its place here",
        '    val id = WORD_IDS[WORD_AT.getValue(msg.key)]',
        "    return if (msg.plural) resources.getQuantityString(id, a[0] as Int, *a) else getString(id, *a)",
        "}",
        "",
        "/** Each word's place in the catalog's order (`Msg.entries`'), which [WORD_IDS] follows. */",
        "private val WORD_AT: Map<String, Int> by lazy { Msg.entries.withIndex().associate { (i, m) -> m.key to i } }",
        "",
        "/** Each word's resource, in the catalog's order: a `<plurals>` for a plural, else a `<string>`. */",
        "private val WORD_IDS = intArrayOf(",
    ]
    for key, entry in words.items():
        lines.append(f"    R.{'plurals' if is_plural(entry) else 'string'}.{key},")
    lines += [")", ""]
    write(out / "me/raddatz/veyq/mobile/android/Words.kt", "\n".join(lines))


# ---- Apple ----

def apple_text(t: str) -> str:
    return PLACEHOLDER.sub(lambda m: f"%{m.group(1)}${'lld' if m.group(2) else '@'}", t)


def apple_strings(words: dict, out: Path) -> None:
    strings = {}
    for key, entry in words.items():
        locs = {}
        for lang in LANGS:
            # Apple's own form where its guidelines word it otherwise (Title Case on a button or menu entry)
            v = entry.get(f"apple_{lang}", entry.get(lang))
            if v is None:
                continue
            if isinstance(v, dict):
                locs[lang] = {"variations": {"plural": {cat: {"stringUnit": {"state": "translated", "value": apple_text(t)}} for cat, t in v.items()}}}
            else:
                locs[lang] = {"stringUnit": {"state": "translated", "value": apple_text(v)}}
        item = {"extractionState": "manual", "localizations": locs}
        if "note" in entry:
            item["comment"] = entry["note"]
        strings[key] = item
    catalog = {"sourceLanguage": "en", "strings": strings, "version": "1.0"}
    write(out, json.dumps(catalog, ensure_ascii=False, indent=2) + "\n")


# The names Kotlin/Native's Objective-C export gives a member: camel case, and `the` before a name that would open one of
# Objective-C's method families (`copyDone` would be a method returning a retained copy), as it named the enum's entries.
OBJC_FAMILIES = re.compile(r"(alloc|copy|mutableCopy|new|init)([A-Z0-9]|$)")
SWIFT_KEYWORDS = {
    "associatedtype", "class", "deinit", "enum", "extension", "fileprivate", "func", "import", "init", "inout", "internal",
    "let", "open", "operator", "private", "precedencegroup", "protocol", "public", "rethrows", "static", "struct",
    "subscript", "typealias", "var", "break", "case", "catch", "continue", "default", "defer", "do", "else",
    "fallthrough", "for", "guard", "if", "in", "repeat", "return", "throw", "switch", "where", "while", "as", "false",
    "is", "nil", "self", "Self", "super", "throws", "true", "try", "Type", "Protocol",
}


def swift_name(key: str) -> str:
    """[key]'s name in Swift, as the enum's entry had it through the Objective-C export (`trash_moved` → `trashMoved`,
    `copy_done` → `theCopyDone`)."""
    head, *rest = key.split("_")
    name = head + "".join(p[:1].upper() + p[1:] for p in rest)
    if OBJC_FAMILIES.match(name):
        name = "the" + name[:1].upper() + name[1:]
    return f"`{name}`" if name in SWIFT_KEYWORDS else name


def apple_swift(words: dict, out: Path) -> None:
    """Each word as a static member of `Msg` in Swift: the core's `Msg` is a class with a companion (inbox #1341), which
    Swift reaches as `Msg.companion.TRASH_MOVED`; these keep `Msg.trashMoved` and `say(.trashMoved)` as the enum gave
    them."""
    names: dict = {}
    for key in words:
        name = swift_name(key)
        if name in names or name in ("companion", "entries"):
            raise CatalogError(f"{key}: its Swift name {name} is taken (by {names.get(name, 'Msg itself')})")
        names[name] = key
    lines = [
        "// Generated from src/mobile/words/words.json by generate_words.py; never edited, never committed.",
        "// Every word of the core's `Msg` as Swift reached it while it was an enum: `Msg.trashMoved`, `say(.trashMoved)`.",
        "import VeyqCore",
        "",
        "extension Msg {",
        *[f"    static var {name}: Msg {{ Msg.companion.{key.upper()} }}" for name, key in names.items()],
        "}",
        "",
    ]
    write(out, "\n".join(lines))


def apple_infoplist(words: dict, out: Path) -> None:
    strings, settings = {}, []
    for key, entry in words.items():
        name = entry.get("apple_infoplist")
        if not name:
            continue
        en = entry.get("apple_en", entry["en"])
        # an xcconfig value ends at `//` (a comment) and expands `$(…)`; a manifest's word holds neither
        if "//" in en or "$" in en or "\n" in en:
            raise CatalogError(f"{key}: an Info.plist word holds no `//`, `$` or line break (an xcconfig value)")
        strings[name] = {"comment": entry.get("note", key), "extractionState": "manual", "localizations": {
            lang: {"stringUnit": {"state": "translated", "value": entry.get(f"apple_{lang}", entry.get(lang))}}
            for lang in LANGS if entry.get(f"apple_{lang}", entry.get(lang)) is not None}}
        settings.append(f"INFOPLIST_KEY_{name} = {en}")
    write(out / "InfoPlist.xcstrings", json.dumps({"sourceLanguage": "en", "strings": strings, "version": "1.0"},
                                                  ensure_ascii=False, indent=2) + "\n")
    write(out / "InfoPlist.xcconfig", "\n".join(
        ["// Generated from src/mobile/words/words.json by generate_words.py; never edited, never committed.",
         "// The Info.plist's own value of each manifest word: English, the fallback; InfoPlist.xcstrings localizes it."]
        + settings) + "\n")


# ---- Linux (AppStream metainfo) ----

def metainfo(words: dict, template: Path, out: Path) -> None:
    text = template.read_text(encoding="utf-8")
    parts = {"summary": [], "description": []}
    for key, entry in words.items():
        if "metainfo" in entry:
            parts[entry["metainfo"]].append(entry)
    if not parts["summary"] or not parts["description"]:
        raise CatalogError("the metainfo needs a `summary` key and at least one `description` key")
    lines = text.split("\n")
    for marker, tag in (("@SUMMARY@", "summary"), ("@DESCRIPTION@", "p")):
        at = [i for i, line in enumerate(lines) if line.strip() == marker]
        if len(at) != 1:
            raise CatalogError(f"{template}: holds the line {marker} {len(at)} times, not once")
        indent = lines[at[0]][: len(lines[at[0]]) - len(lines[at[0]].lstrip())]
        made = []
        for entry in parts["summary" if tag == "summary" else "description"]:
            for lang in LANGS:
                if lang in entry:
                    attr = "" if lang == "en" else f' xml:lang="{lang}"'
                    made.append(f"{indent}<{tag}{attr}>{xml_text(entry[lang])}</{tag}>")
        lines[at[0]: at[0] + 1] = made
    write(out, "\n".join(lines))


# ---- Linux (gettext) ----

# A language's gettext plural rule and which CLDR category each of its forms is, in order. A language missing here is
# refused: its plural is a decision (a rule and its categories), never a silent wrong form.
GETTEXT_PLURALS = {
    "en": ("nplurals=2; plural=(n != 1);", ("one", "other")),
    "de": ("nplurals=2; plural=(n != 1);", ("one", "other")),
}
RUST_KEYWORDS = {
    "as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum", "extern", "false", "fn", "for",
    "gen", "if", "impl", "in", "let", "loop", "match", "mod", "move", "mut", "pub", "ref", "return", "self", "static",
    "struct", "super", "trait", "true", "type", "unsafe", "use", "where", "while", "abstract", "become", "box", "do",
    "final", "macro", "override", "priv", "try", "typeof", "unsized", "virtual", "yield",
}


def desktop_text(t: str) -> str:
    """A word as the desktops' catalogs hold it: words.json writes a percent sign `%%`, as Android's and Apple's format
    strings read it; gettext's and Windows' catalogs are not format strings, so there it is one `%`."""
    return t.replace("%%", "%")


def po_quote(t: str) -> str:
    t = desktop_text(t)
    return '"' + t.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n").replace("\t", "\\t") + '"'


def plural_forms(entry: dict, lang: str, key: str) -> list:
    """[lang]'s text of a plural [entry] per gettext form, in the order GETTEXT_PLURALS gives its categories."""
    _, cats = GETTEXT_PLURALS[lang]
    v = entry[lang]
    unknown = sorted(set(v) - set(cats))
    if unknown:
        raise CatalogError(f"{key}.{lang}: gettext's rule for {lang} has no form for {', '.join(unknown)}")
    return [v.get(c, v["other"]) for c in cats]


def gettext_catalogs(words: dict, out: Path) -> None:
    for lang in LANGS:
        if lang not in GETTEXT_PLURALS:
            raise CatalogError(f"{lang}: no gettext plural rule for this language — add it to GETTEXT_PLURALS")
    head = ["# Generated from src/mobile/words/words.json by generate_words.py; never edited.", 'msgid ""', 'msgstr ""']

    def header(lang: str | None) -> list:
        rule = GETTEXT_PLURALS[lang or "en"][0]
        lines = ['"Project-Id-Version: veyq\\n"', '"MIME-Version: 1.0\\n"', '"Content-Type: text/plain; charset=UTF-8\\n"',
                 '"Content-Transfer-Encoding: 8bit\\n"', f'"Plural-Forms: {rule}\\n"']
        if lang:
            lines.insert(1, f'"Language: {lang}\\n"')
        return head + lines + [""]

    for lang in (None,) + LANGS:
        lines = header(lang)
        for key, entry in words.items():
            en = entry["en"]
            lines.append(f"msgctxt {po_quote(key)}")
            if is_plural(entry):
                one, other = plural_forms(entry, "en", key)
                lines += [f"msgid {po_quote(one)}", f"msgid_plural {po_quote(other)}"]
                forms = plural_forms(entry, lang, key) if lang and lang in entry else ["", ""]
                lines += [f"msgstr[{i}] {po_quote(t)}" for i, t in enumerate(forms)]
            else:
                lines.append(f"msgid {po_quote(en)}")
                lines.append(f"msgstr {po_quote(entry[lang] if lang and lang in entry else '')}")
            lines.append("")
        write(out / (f"{lang}.po" if lang else "veyq.pot"), "\n".join(lines))


def rust_str(t: str) -> str:
    t = desktop_text(t)
    return '"' + t.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n").replace("\t", "\\t") + '"'


def rust_words(words: dict, out: Path) -> None:
    lines = [
        "// Generated from src/mobile/words/words.json by generate_words.py; never edited, never committed.",
        "// Each key a function: its words in the person's language (gettext, domain `veyq`, msgctxt the key), its",
        "// placeholders filled from its typed arguments; `say` reaches the same function by its key.",
        "",
    ]
    dispatch = []
    for key, entry in words.items():
        typed = placeholders_of(list(entry["en"].values()) if is_plural(entry) else [entry["en"]], key)
        name = f"r#{key}" if key in RUST_KEYWORDS else key
        params = [f"a{i + 1}: {'i64' if t else '&str'}" for i, t in enumerate(typed)]
        args = [f"Arg::{'Int' if t else 'Text'}(a{i + 1})" for i, t in enumerate(typed)]
        fills = ", ".join(args)
        # an integer is copied out of the pattern's reference; a text reference is handed on as it is
        names = [f"*a{i + 1}" if t else f"a{i + 1}" for i, t in enumerate(typed)]
        if is_plural(entry):
            if typed and not typed[0]:
                raise CatalogError(f"{key}: a plural's first placeholder is its count, an integer ({{1:int}})")
            if not typed:
                params, args, names = ["n: i64"], ["Arg::Int(n)"], ["*n"]
            one, other = plural_forms(entry, "en", key)
            count = "a1" if typed else "n"
            look = f"plural({rust_str(key)}, {rust_str(one)}, {rust_str(other)}, {count})"
        else:
            look = f"single({rust_str(key)}, {rust_str(entry['en'])})"
        shown = entry["en"]["other"] if is_plural(entry) else entry["en"]
        lines.append(f"/// `{key}`: {' '.join(shown.split())}")
        lines.append(f"pub fn {name}({', '.join(params)}) -> String {{")
        lines.append(f"    fill(&{look}, &[{fills}])")
        lines.append("}")
        lines.append("")
        pattern = ", ".join(args)
        dispatch.append(f"        ({rust_str(key)}, [{pattern}]) => Some({name}({', '.join(names)})),")
    lines += [
        "/// [key]'s words with [args] (a core `Notice`'s key and arguments); None for a key the catalog does not have, or",
        "/// arguments of another count or kind than its placeholders.",
        "pub fn say(key: &str, args: &[Arg]) -> Option<String> {",
        "    match (key, args) {",
        *dispatch,
        "        _ => None,",
        "    }",
        "}",
        "",
        "/// Every key of the catalog, in its order: a `static`, since a `const` of more than 1,024 keys is one clippy's",
        "/// `large_const_arrays` refuses (it would be copied into every use).",
        f"pub static KEYS: [&str; {len(words)}] = [",
        *[f"    {rust_str(k)}," for k in words],
        "];",
        "",
    ]
    write(out / "words.rs", "\n".join(lines))


# ---- Windows (.resw, MRT Core) ----

# A language's culture folder and its CLDR plural rule for an integer count, as C# over `n` (a `long`), in the order
# its categories are tested, the last one `other`. A language missing here is refused: its plural is a decision.
RESW_LANGS = {
    "en": ("en-US", (("one", "n == 1"), ("other", "true"))),
    "de": ("de-DE", (("one", "n == 1"), ("other", "true"))),
}
CSHARP_KEYWORDS = {
    "abstract", "as", "base", "bool", "break", "byte", "case", "catch", "char", "checked", "class", "const", "continue",
    "decimal", "default", "delegate", "do", "double", "else", "enum", "event", "explicit", "extern", "false", "finally",
    "fixed", "float", "for", "foreach", "goto", "if", "implicit", "in", "int", "interface", "internal", "is", "lock",
    "long", "namespace", "new", "null", "object", "operator", "out", "override", "params", "private", "protected",
    "public", "readonly", "ref", "return", "sbyte", "sealed", "short", "sizeof", "stackalloc", "static", "string",
    "struct", "switch", "this", "throw", "true", "try", "typeof", "uint", "ulong", "unchecked", "unsafe", "ushort",
    "using", "virtual", "void", "volatile", "while",
}


def xml_text(t: str) -> str:
    return desktop_text(t).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def resw_forms(entry: dict, lang: str, key: str) -> list:
    """(category, text) of a plural [entry] in [lang] for each category [lang]'s rule names; a category the entry does not
    name takes its `other`, one the rule does not know is refused (it would never be chosen)."""
    _, rule = RESW_LANGS[lang]
    cats = [c for c, _ in rule]
    v = entry.get(lang, entry["en"])
    unknown = sorted(set(v) - set(cats))
    if unknown:
        raise CatalogError(f"{key}.{lang}: the plural rule for {lang} has no category {', '.join(unknown)}")
    return [(c, v.get(c, v["other"])) for c in cats]


def resw(words: dict, out: Path) -> None:
    for lang in LANGS:
        if lang not in RESW_LANGS:
            raise CatalogError(f"{lang}: no plural rule for this language on Windows — add it to RESW_LANGS")
    for lang in LANGS:
        culture, _ = RESW_LANGS[lang]
        lines = [
            '<?xml version="1.0" encoding="utf-8"?>',
            "<!-- Generated from src/mobile/words/words.json by generate_words.py; never edited, never committed. -->",
            "<root>",
            '  <resheader name="resmimetype"><value>text/microsoft-resx</value></resheader>',
            '  <resheader name="version"><value>2.0</value></resheader>',
            '  <resheader name="reader"><value>System.Resources.ResXResourceReader, System.Windows.Forms, Version=4.0.0.0, '
            'Culture=neutral, PublicKeyToken=b77a5c561934e089</value></resheader>',
            '  <resheader name="writer"><value>System.Resources.ResXResourceWriter, System.Windows.Forms, Version=4.0.0.0, '
            'Culture=neutral, PublicKeyToken=b77a5c561934e089</value></resheader>',
            f'  <data name="_language" xml:space="preserve"><value>{lang}</value></data>',
        ]
        for key, entry in words.items():
            note = f"<comment>{xml_text(entry['note'])}</comment>" if "note" in entry else ""
            if is_plural(entry):
                for cat, text in resw_forms(entry, lang, key):
                    lines.append(f'  <data name="{key}#{cat}" xml:space="preserve"><value>{xml_text(text)}</value>{note}</data>')
            else:
                text = entry.get(lang, entry["en"])
                lines.append(f'  <data name="{key}" xml:space="preserve"><value>{xml_text(text)}</value>{note}</data>')
        lines += ["</root>", ""]
        write(out / "Strings" / culture / "Resources.resw", "\n".join(lines))


def cs_str(t: str) -> str:
    t = desktop_text(t)
    return '"' + t.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n").replace("\t", "\\t") + '"'


def pascal(key: str) -> str:
    return "".join(p[:1].upper() + p[1:] for p in key.split("_"))


def csharp_words(words: dict, out: Path) -> None:
    methods: dict = {}
    for key in words:
        name = pascal(key)
        if name in methods or name in ("Say", "Keys", "Lookup", "Category", "Fill", "Single", "Plural"):
            raise CatalogError(f"{key}: its C# name {name} is taken (by {methods.get(name, 'Words itself')})")
        methods[name] = key
    rules = []
    for lang in LANGS:
        _, rule = RESW_LANGS[lang]
        arms = " : ".join(f'{cond} ? "{cat}"' for cat, cond in rule[:-1])
        rules.append(f'        "{lang}" => {arms} : "{rule[-1][0]}",' if arms else f'        "{lang}" => "{rule[-1][0]}",')
    lines = [
        "// Generated from src/mobile/words/words.json by generate_words.py; never edited, never committed.",
        "// Each key a method: its words in the person's language (the catalog Lookup reads: MRT Core's resources.pri in",
        "// the app), its placeholders filled by number from its typed arguments; Say reaches the same method by its key.",
        "#nullable enable",
        "using System.Collections.Generic;",
        "",
        "namespace Veyq;",
        "",
        "public static partial class Words",
        "{",
        "    /// <summary>",
        "    /// The plural category of <paramref name=\"n\"/> under the rule of <paramref name=\"language\"/>, the language the",
        "    /// catalog that answered names (its <c>_language</c>); null for a language this catalog has no rule for.",
        "    /// </summary>",
        "    public static string? Category(string language, long n) => language switch",
        "    {",
        *rules,
        "        _ => null,",
        "    };",
        "",
    ]
    dispatch = []
    for key, entry in words.items():
        typed = placeholders_of(list(entry["en"].values()) if is_plural(entry) else [entry["en"]], key)
        params = [f"{'long' if t else 'string'} a{i + 1}" for i, t in enumerate(typed)]
        names = [f"a{i + 1}" for i in range(len(typed))]
        if is_plural(entry):
            if typed and not typed[0]:
                raise CatalogError(f"{key}: a plural's first placeholder is its count, an integer ({{1:int}})")
            count = "a1" if typed else "n"
            if not typed:
                params, names = ["long n"], ["n"]
            look = f"Plural({cs_str(key)}, {cs_str(entry['en'].get('one', entry['en']['other']))}, {cs_str(entry['en']['other'])}, {count})"
        else:
            look = f"Single({cs_str(key)}, {cs_str(entry['en'])})"
        fills = ", ".join(f"(object){n}" for n in names[: len(typed)])
        shown = entry["en"]["other"] if is_plural(entry) else entry["en"]
        lines.append(f"    /// <summary><c>{key}</c>: {xml_text(' '.join(shown.split()))}</summary>")
        lines.append(f"    public static string {pascal(key)}({', '.join(params)}) => Fill({look}, [{fills}]);")
        lines.append("")
        conds = [f"args[{i}] is {'long' if t else 'string'} {n}" for i, (t, n) in enumerate(zip(typed, names))]
        if is_plural(entry) and not typed:
            conds = ["args[0] is long n"]
        arity = max(len(typed), 1 if is_plural(entry) else 0)
        test = " && ".join([f"args.Count == {arity}", *conds])
        dispatch.append(f"            case {cs_str(key)}: {{ return {test} ? {pascal(key)}({', '.join(names)}) : null; }}")
    lines += [
        "    /// <summary>",
        "    /// <paramref name=\"key\"/>'s words with <paramref name=\"args\"/> (a core Notice's key and arguments: a",
        "    /// <c>string</c> for a <c>{n}</c> placeholder, a <c>long</c> for a <c>{n:int}</c> one); null for a key this catalog",
        "    /// does not have, or arguments of another count or kind than its placeholders.",
        "    /// </summary>",
        "    // A switch statement over the key alone, each case its own block: a switch expression over (key, count) with a",
        "    // guard per arm sent Roslyn's reachability check of its decision DAG past an hour at ~1,600 words (measured on",
        "    // the Windows VM 2026-11-26, `DecisionDagBuilder.CheckOrAndAndReachability`); a string switch is a hash jump.",
        "    public static string? Say(string key, IReadOnlyList<object> args)",
        "    {",
        "        switch (key)",
        "        {",
        *dispatch,
        "            default: return null;",
        "        }",
        "    }",
        "",
        "    /// <summary>Every key of the catalog, in its order.</summary>",
        "    public static readonly IReadOnlyList<string> Keys =",
        "    [",
        *[f"        {cs_str(k)}," for k in words],
        "    ];",
        "}",
        "",
    ]
    write(out / "Words.g.cs", "\n".join(lines))


# ---- the web (inbox #1060) ----

WEB_LITERAL = re.compile(r"[{}@|$]")


def web_text(t: str) -> str:
    """A text in vue-i18n's message syntax: a placeholder by its list index, every other syntax character a literal."""
    out, at = [], 0
    for m in PLACEHOLDER.finditer(t):
        out.append(WEB_LITERAL.sub(lambda c: "{'" + c.group(0) + "'}", desktop_text(t[at:m.start()])))
        out.append("{" + str(int(m.group(1)) - 1) + "}")
        at = m.end()
    out.append(WEB_LITERAL.sub(lambda c: "{'" + c.group(0) + "'}", desktop_text(t[at:])))
    return "".join(out)


def web_message(v) -> str:
    if isinstance(v, dict):
        return " | ".join(web_text(v[c]) for c in ("zero", "one", "other") if c in v)
    return web_text(v)


def web_read(words: dict) -> list:
    read = json.loads(WEB_LINKS.read_text(encoding="utf-8"))["read"]
    unknown = [k for k in read if k not in words]
    if unknown:
        raise CatalogError(f"web.json's `read` names keys the catalog has not: {unknown}")
    return sorted(set(read))


def web_messages(words: dict, keys: list) -> str:
    """The `--web` file's text: deterministic, so a committed copy can be compared byte for byte."""
    data = {lang: {k: web_message(words[k].get(lang, words[k]["en"])) for k in keys} for lang in LANGS}
    return json.dumps(data, indent=2, ensure_ascii=False) + "\n"


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists() or path.read_text(encoding="utf-8") != text:
        path.write_text(text, encoding="utf-8")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--kotlin", type=Path)
    ap.add_argument("--android-res", type=Path)
    ap.add_argument("--android-kotlin", type=Path)
    ap.add_argument("--apple-strings", type=Path)
    ap.add_argument("--apple-swift", type=Path)
    ap.add_argument("--gettext", type=Path)
    ap.add_argument("--resw", type=Path)
    ap.add_argument("--apple-infoplist", type=Path)
    ap.add_argument("--metainfo", type=Path, nargs=2, metavar=("TEMPLATE", "OUT"))
    ap.add_argument("--web", type=Path)
    a = ap.parse_args()
    try:
        words = load()
    except CatalogError as e:
        print(f"words.json: {e}", file=sys.stderr)
        return 1
    if a.kotlin:
        kotlin(words, a.kotlin)
    if a.android_res:
        android_res(words, a.android_res)
    if a.android_kotlin:
        android_kotlin(words, a.android_kotlin)
    if a.apple_strings:
        apple_strings(words, a.apple_strings)
    if a.apple_swift:
        try:
            apple_swift(words, a.apple_swift)
        except CatalogError as e:
            print(f"words.json: {e}", file=sys.stderr)
            return 1
    if a.gettext:
        try:
            gettext_catalogs(words, a.gettext)
            rust_words(words, a.gettext)
        except CatalogError as e:
            print(f"words.json: {e}", file=sys.stderr)
            return 1
    if a.resw:
        try:
            resw(words, a.resw)
            csharp_words(words, a.resw)
        except CatalogError as e:
            print(f"words.json: {e}", file=sys.stderr)
            return 1
    try:
        if a.apple_infoplist:
            apple_infoplist(words, a.apple_infoplist)
        if a.metainfo:
            metainfo(words, *a.metainfo)
        if a.web:
            write(a.web, web_messages(words, web_read(words)))
    except CatalogError as e:
        print(f"words.json: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
