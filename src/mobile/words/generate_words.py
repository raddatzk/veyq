#!/usr/bin/env python3
"""Generates every platform's words from the apps' one catalog (ADR 0127 decision 3).

`words.json` beside this script is the source: per key its English (`en`) and German (`de`) text — a plural is an
object of CLDR categories (`one`, `other`) — and an optional `note` for translators. A placeholder is `{n}` (a string)
or `{n:int}` (an integer), numbered from 1 in the order the English text gives them; every language uses the same set.
A key without `de` is red unless it says `"untranslated": true` (a name such as `veyq`). `apple_en` / `apple_de` is a
key's form on Apple where its guidelines word it otherwise (Title Case on buttons and menu entries); the same placeholders.

The apps never write a word of their own: this script writes what each platform reads, and the builds run it.

Outputs (each optional; each a directory or file this script owns and rewrites whole):
  --kotlin DIR          `me/raddatz/veyq/mobile/words/Msg.kt`: every key as an entry of the core's `Msg`, with its
                        placeholder count and whether it is a plural — what the core's screen models raise.
  --android-res DIR     `values/veyq_words.xml`, `values-de/veyq_words.xml`: `<string>` and `<plurals>` under the key's
                        own name, so `R.string.<key>` is the key.
  --android-kotlin DIR  `me/raddatz/veyq/mobile/android/Words.kt`: `Context.words(msg, args)`, a `Msg` to its resource.
  --apple-strings FILE  a String Catalog (table `Words`): the Apple app's `say(msg, args)` looks the key up there.
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

Python 3.9 (Xcode's and Gradle's `python3` on the developer's Mac), standard library only.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

CATALOG = Path(__file__).resolve().parent / "words.json"
LANGS = ("en", "de")
PLURAL_CATEGORIES = ("zero", "one", "two", "few", "many", "other")
PLACEHOLDER = re.compile(r"\{(\d+)(:int)?\}")


class CatalogError(Exception):
    pass


def load() -> dict:
    words = json.loads(CATALOG.read_text(encoding="utf-8"))
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
    lines = [
        "package me.raddatz.veyq.mobile.words",
        "",
        "/**",
        " * Every word the apps show, by its key in `src/mobile/words/words.json` (generated by `generate_words.py`, never",
        " * edited): what the core's screen models raise; each platform renders it in the person's language.",
        " * [args] is the placeholder count, [plural] whether the first is the count a plural form is chosen by.",
        " */",
        "enum class Msg(val key: String, val args: Int, val plural: Boolean) {",
    ]
    for key, entry in words.items():
        lines.append(f'    {key.upper()}("{key}", {arity(entry)}, {"true" if is_plural(entry) else "false"}),')
    lines += ["}", ""]
    write(out / "me/raddatz/veyq/mobile/words/Msg.kt", "\n".join(lines))


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
        "    return when (msg) {",
    ]
    for key, entry in words.items():
        if is_plural(entry):
            lines.append(f"        Msg.{key.upper()} -> resources.getQuantityString(R.plurals.{key}, a[0] as Int, *a)")
        else:
            lines.append(f"        Msg.{key.upper()} -> getString(R.string.{key}, *a)")
    lines += ["    }", "}", ""]
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


def po_quote(t: str) -> str:
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
        "/// Every key of the catalog, in its order.",
        f"pub const KEYS: [&str; {len(words)}] = [",
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
    return t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


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
        guard = f" when {' && '.join(conds)}" if conds else ""
        dispatch.append(f"        ({cs_str(key)}, {arity}){guard} => {pascal(key)}({', '.join(names)}),")
    lines += [
        "    /// <summary>",
        "    /// <paramref name=\"key\"/>'s words with <paramref name=\"args\"/> (a core Notice's key and arguments: a",
        "    /// <c>string</c> for a <c>{n}</c> placeholder, a <c>long</c> for a <c>{n:int}</c> one); null for a key this catalog",
        "    /// does not have, or arguments of another count or kind than its placeholders.",
        "    /// </summary>",
        "    public static string? Say(string key, IReadOnlyList<object> args) => (key, args.Count) switch",
        "    {",
        *dispatch,
        "        _ => null,",
        "    };",
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
    ap.add_argument("--gettext", type=Path)
    ap.add_argument("--resw", type=Path)
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
    return 0


if __name__ == "__main__":
    sys.exit(main())
