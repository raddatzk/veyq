#!/usr/bin/env python3
"""Guard that the web and the apps say one meaning in one wording: the web's messages (`src/web/src/i18n/index.ts` and
each application's `src/web/src/features/<owner>/i18n.ts`) and the apps' catalog (`src/mobile/words/words.json`, ADR 0161
decision 3) are held together by `src/mobile/words/web.json` (inbox #1060, ADR 0161 § The web's words).

**What it asserts.** Seven rules:
- **resolves**: every key `web.json` names is in its catalog — an app key in `words.json`, a web key (dotted, as `t()`
  takes it) a string leaf of a web catalog; a pair is not both twins and apart.
- **twin-equal**: each pair under `twins` says the same in English and in German. Compared exactly, after the two
  syntaxes are read: the app's `{n}` / `{n:int}` and the web's named `{name}` (or list `{0}`) are matched by the order
  they first stand in the English, so the German must use them the same; a vue-i18n literal (`{'@'}`) is its character,
  the app's `%%` one `%`; a plural's forms (the app's CLDR `zero`, `one`, `other` as present, the web's ` | ` parts)
  compare one by one, so a web plural with a zero form is no twin of an app plural without one. An Apple form
  (`apple_en`, `apple_de`) is not compared: Apple's title case is the one wording a platform's guidelines keep apart.
- **unpaired**: a key of either catalog whose English matches a key of the other loosely (placeholders, case, quote style
  and a trailing `.`, `!`, `?`, `:` or `…` ignored; three words or more) stands in some pair of `twins`, `apart` or
  `open`. A new sentence both sides say is thereby paired the day it lands, and a sentence worded apart is decided —
  twinned (then equal), apart (a different meaning, or a platform's convention, with why) or open (the owner's copy
  question, with what is asked).
- **open-differs**: a pair under `open` still differs; once it is equal it belongs under `twins`.
- **read-is-notices**: every key of `web.json`'s `read` is one the core's notices tables name — a `Msg.<KEY>` in a
  `*Notices.kt` of the core's or an application's `commonMain` (ADR 0155 decision 7.2). Not every such word is read: the
  web registers `read` eagerly, so it names only the words the web says (ADR 0161 § The web's words, the notices' move;
  `src/web/src/i18n/i18n.test.ts` reddens a registered word no source names, and one a source names that is not there).
- **read-has-no-twin**: a word the web reads from the catalog has no copy of its own on the web — a `read` key stands in
  no pair of `twins` or `open`, and no web message says what a `read` word says (the same in English and German, read as
  twin-equal reads them, of any length) unless the pair is `apart`. So the twin the notices' move deleted cannot come
  back as a key of the web's own, not even a one-word one ("Queued").
- **generated-fresh**: `src/web/src/i18n/words.generated.json` is byte for byte what `generate_words.py --web` writes
  from the catalog: the web reads those words from the apps' catalog, never a copy of its own.

The web's catalogs are read by a small TypeScript object-literal reader: keys (identifiers or quoted), string values
(`'…'`, `"…"`, a template without `${…}`), nested objects, comments, trailing commas. Anything else in the `const en = {`
/ `const de: typeof en = {` blocks (a spread, a call, a reference) is refused with exit 2, never read past.

**The defect that bought it.** The two catalogs grew apart unseen (inbox #1060, measured 2026-11-22 by the sweep the CR
ran): 1,134 of the apps' 1,503 keys say what a web message says, and 42 such pairs said it differently — "pot" on the
apps where the web and ADR 0086 say "Pot", "Spaces" in the apps' German where the web says "Bereiche", "Titelbild" against
"Cover", a straight apostrophe against a typographic one.

**Its limit.** It reads wording, not meaning: a pair is only as true as `web.json` says, so two keys twinned by mistake
are held equal, and a meaning the two catalogs word apart in English entirely (no loose match, as "device" against
"browser") is never offered for pairing. Short texts (one or two words: "Delete", "Open") are paired only where
`web.json` names them — homographs ("Open" the state, "Open" the verb) would otherwise be forced together. A pair's
placeholders are matched by order of first use; a sentence that names two placeholders in the opposite order in each
catalog's English reads as a mismatch, not a pass. The notices' words are found by name (`Msg.<KEY>` in a file called
`*Notices.kt`): a notice raised elsewhere (a screen model building `Notice(Msg.…)` itself) cannot be in `read`.
A web copy of a `read` word is found by its text: one worded otherwise (a different case or punctuation, the same
meaning) passes read-has-no-twin — and, from three words on, is red under unpaired.

Exit 0 green, 1 red (every violation printed), 2 when the tree is not where it should be or a catalog cannot be read.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import repo_layout  # noqa: E402

ROOT = repo_layout.ROOT
WORDS_DIR = repo_layout.MOBILE / "words"
CATALOG = WORDS_DIR / "words.json"
LINKS = WORDS_DIR / "web.json"
WEB_SRC = repo_layout.WEB / "src"
WEB_CORE_CATALOG = WEB_SRC / "i18n" / "index.ts"
GENERATED = WEB_SRC / "i18n" / "words.generated.json"
NOTICE_ROOTS = (repo_layout.MOBILE_CORE, repo_layout.MOBILE_APPS)

APP_PH = re.compile(r"\{(\d+)(?::int)?\}")
WEB_TOKEN = re.compile(r"\{\s*'([^']*)'\s*\}|\{\s*([A-Za-z0-9_]+)\s*\}")
MIN_WORDS = 3


class Unreadable(Exception):
    pass


# ---- the web's catalogs: a reader for the object literals they are ----

TOKEN = re.compile(r"""\s+|//[^\n]*|/\*.*?\*/|'(?:[^'\\\n]|\\.)*'|"(?:[^"\\\n]|\\.)*"|`(?:[^`\\$]|\\.|\$(?!\{))*`|[A-Za-z_$][\w$]*|[{}:,]""", re.S)
ESCAPES = {"n": "\n", "t": "\t", "r": "\r", "\\": "\\", "'": "'", '"': '"', "`": "`", "0": "\0"}


def _unquote(tok: str) -> str:
    body = tok[1:-1]
    return re.sub(r"\\u\{([0-9a-fA-F]+)\}|\\u([0-9a-fA-F]{4})|\\(.)",
                  lambda m: chr(int(m.group(1) or m.group(2), 16)) if (m.group(1) or m.group(2)) else ESCAPES.get(m.group(3), m.group(3)),
                  body)


def _tokens(text: str, where: str) -> list:
    out, at = [], 0
    while at < len(text):
        m = TOKEN.match(text, at)
        if not m:
            raise Unreadable(f"{where}: cannot read `{text[at:at + 40]}` — a catalog holds string literals and objects only")
        tok = m.group(0)
        at = m.end()
        if not tok.strip() or tok.startswith("//") or tok.startswith("/*"):
            continue
        out.append(tok)
    return out


def _object(toks: list, i: int, where: str) -> tuple:
    if toks[i] != "{":
        raise Unreadable(f"{where}: expected an object, found `{toks[i]}`")
    i += 1
    obj = {}
    while toks[i] != "}":
        key = toks[i]
        key = _unquote(key) if key[0] in "'\"`" else key
        if toks[i + 1] != ":":
            raise Unreadable(f"{where}: `{key}` is not `key: value` (a spread or a shorthand is not read)")
        i += 2
        if toks[i] == "{":
            value, i = _object(toks, i, f"{where}.{key}")
        elif toks[i][0] in "'\"`":
            value, i = _unquote(toks[i]), i + 1
        else:
            raise Unreadable(f"{where}.{key}: the value `{toks[i]}` is no string literal or object")
        if key in obj:
            raise Unreadable(f"{where}.{key}: the key stands twice")
        obj[key] = value
        if toks[i] == ",":
            i += 1
        elif toks[i] != "}":
            raise Unreadable(f"{where}.{key}: expected `,` or `}}`, found `{toks[i]}`")
    return obj, i + 1


def _block(text: str, header: str, where: str) -> dict:
    at = text.find(header)
    if at < 0:
        raise Unreadable(f"{where}: no `{header}` block")
    start = at + len(header) - 1
    end = text.find("\n}\n", start)
    if end < 0:
        raise Unreadable(f"{where}: `{header}` is not closed by a `}}` of its own line")
    toks = _tokens(text[start:end + 2], where)
    obj, i = _object(toks, 0, where)
    if i != len(toks):
        raise Unreadable(f"{where}: `{toks[i]}` after the block's end")
    return obj


def _flatten(obj: dict, prefix: str, out: dict) -> None:
    for k, v in obj.items():
        if isinstance(v, dict):
            _flatten(v, f"{prefix}{k}.", out)
        else:
            out[f"{prefix}{k}"] = v


def web_catalogs(root: Path) -> dict:
    """Every web message as {dotted key: {"en": …, "de": …}}, core's and each application's."""
    src = root / WEB_SRC.relative_to(ROOT)
    files = [root / WEB_CORE_CATALOG.relative_to(ROOT)] + sorted((src / "features").glob("*/i18n.ts"))
    out: dict = {}
    for f in files:
        if not f.is_file():
            raise Unreadable(f"{f.relative_to(root)}: missing")
        text = f.read_text(encoding="utf-8")
        rel = str(f.relative_to(root))
        langs = {}
        for lang, header in (("en", "const en = {"), ("de", "const de: typeof en = {")):
            flat: dict = {}
            _flatten(_block(text, header, f"{rel} ({lang})"), "", flat)
            langs[lang] = flat
        for k in langs["en"]:
            if k in out:
                raise Unreadable(f"{rel}: `{k}` is held by another catalog too")
            out[k] = {"en": langs["en"][k], "de": langs["de"].get(k, langs["en"][k])}
    if len(out) < 100:
        raise Unreadable(f"read only {len(out)} web messages — the catalogs moved?")
    return out


# ---- the two syntaxes, read into one ----

def app_forms(v) -> list:
    return [v[c] for c in ("zero", "one", "other") if c in v] if isinstance(v, dict) else [v]


def web_forms(v: str) -> list:
    # vue-i18n splits a plural on `|`; a literal `{'|'}` is no separator
    parts, depth, cur = [], 0, ""
    for ch in v:
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
        if ch == "|" and depth == 0:
            parts.append(cur.strip())
            cur = ""
        else:
            cur += ch
    return parts + [cur.strip()] if parts else [v]


def app_read(forms: list, order: dict) -> list:
    """The app's forms with each placeholder as its rank by first use in the English (`order`: number -> rank)."""
    return [APP_PH.sub(lambda m: "{#%s}" % order.get(m.group(1), "?" + m.group(1)), f).replace("%%", "%") for f in forms]


def web_read(forms: list, order: dict) -> list:
    def one(m):
        if m.group(1) is not None:
            return m.group(1)
        return "{#%s}" % order.get(m.group(2), "?" + m.group(2))
    return [WEB_TOKEN.sub(one, f) for f in forms]


def first_use(forms: list, pattern: re.Pattern, group: int) -> dict:
    order: dict = {}
    for f in forms:
        for m in pattern.finditer(f):
            name = m.group(group)
            if name is not None and name not in order:
                order[name] = len(order) + 1
    return order


def compare(app: dict, web: dict) -> list:
    """What differs between an app entry and a web message, per language; empty when they say the same."""
    a_en, w_en = app_forms(app["en"]), web_forms(web["en"])
    a_order, w_order = first_use(a_en, APP_PH, 1), first_use(w_en, WEB_TOKEN, 2)
    diffs = []
    for lang in ("en", "de"):
        a = app_read(app_forms(app.get(lang, app["en"])), a_order)
        w = web_read(web_forms(web[lang]), w_order)
        if a != w:
            diffs.append(f"{lang}: app {' | '.join(a)!r} ≠ web {' | '.join(w)!r}")
    return diffs


LOOSE_QUOTES = str.maketrans({"’": "'", "‘": "'", "“": '"', "”": '"', "„": '"', "«": '"', "»": '"'})


def loose(text: str) -> str:
    t = APP_PH.sub("{}", text)
    t = re.sub(r"\{\s*'([^']*)'\s*\}", r"\1", t)
    t = re.sub(r"\{\s*[A-Za-z0-9_]+\s*\}", "{}", t).replace("%%", "%")
    t = t.translate(LOOSE_QUOTES).casefold()
    return re.sub(r"[\s.!?:…]+$", "", re.sub(r"\s+", " ", t)).strip()


def loose_of_app(v) -> str:
    return " | ".join(loose(f) for f in app_forms(v))


def loose_of_web(v: str) -> str:
    return " | ".join(loose(f) for f in web_forms(v))


# ---- the notices' words ----

def notice_keys(root: Path) -> set:
    keys = set()
    for base in NOTICE_ROOTS:
        for f in (root / base.relative_to(ROOT)).rglob("*Notices.kt"):
            if "/build/" in f.as_posix() or "/commonMain/" not in f.as_posix():
                continue
            keys.update(m.lower() for m in re.findall(r"\bMsg\.([A-Z][A-Z0-9_]*)\b", f.read_text(encoding="utf-8")))
    return keys


def generated_text(root: Path, words: dict, read: list) -> str:
    sys.path.insert(0, str(root / WORDS_DIR.relative_to(ROOT)))
    import generate_words  # noqa: E402
    return generate_words.web_messages(words, sorted(set(read)))


# ---- the rules ----

def check(root: Path) -> list:
    words = json.loads((root / CATALOG.relative_to(ROOT)).read_text(encoding="utf-8"))
    links = json.loads((root / LINKS.relative_to(ROOT)).read_text(encoding="utf-8"))
    web = web_catalogs(root)
    errors = []

    twins = links.get("twins", {})
    pairs = {"twins": [(a, w) for a, ws in twins.items() for w in ws]}
    for section in ("apart", "open"):
        pairs[section] = [(p["app"], p["web"]) for p in links.get(section, [])]
        for p in links.get(section, []):
            if not str(p.get("why", "")).strip():
                errors.append(f"resolves: {section} {p['app']} ~ {p['web']} says no `why`")

    for section, ps in pairs.items():
        for a, w in ps:
            if a not in words:
                errors.append(f"resolves: {section} names the app key `{a}`, which words.json has not")
            if w not in web:
                errors.append(f"resolves: {section} names the web key `{w}`, which no web catalog has")
    for both in set(pairs["twins"]) & (set(pairs["apart"]) | set(pairs["open"])):
        errors.append(f"resolves: {both[0]} ~ {both[1]} is twins and apart (or open) at once")

    for a, w in pairs["twins"]:
        if a in words and w in web:
            for d in compare(words[a], web[w]):
                errors.append(f"twin-equal: {a} ~ {w}: {d} — say it one way (the web's wording wins, ADR 0154), or "
                              f"move the pair to `apart` / `open` with why")

    for a, w in pairs["open"]:
        if a in words and w in web and not compare(words[a], web[w]):
            errors.append(f"open-differs: {a} ~ {w} says the same now — move it to `twins`")

    paired_app = {a for ps in pairs.values() for a, _ in ps}
    paired_web = {w for ps in pairs.values() for _, w in ps}
    by_text: dict = {}
    for a, e in words.items():
        by_text.setdefault(loose_of_app(e["en"]), ([], []))[0].append(a)
    for w, m in web.items():
        if loose_of_web(m["en"]) in by_text:
            by_text[loose_of_web(m["en"])][1].append(w)
    for text, (apps, webs) in sorted(by_text.items()):
        if not webs or len(text.split()) < MIN_WORDS:
            continue
        for a in apps:
            if a not in paired_app:
                errors.append(f"unpaired: the app's `{a}` says what the web's {sorted(webs)} say ({text!r}) — pair it "
                              f"in src/mobile/words/web.json (`twins`, or `apart` / `open` with why)")
        for w in webs:
            if w not in paired_web:
                errors.append(f"unpaired: the web's `{w}` says what the app's {sorted(apps)} say ({text!r}) — pair it "
                              f"in src/mobile/words/web.json (`twins`, or `apart` / `open` with why)")

    read = links.get("read", [])
    notices = notice_keys(root)
    if not notices:
        raise Unreadable("found no Msg in any *Notices.kt — the notices tables moved?")
    for k in sorted(set(read) - notices):
        errors.append(f"read-is-notices: web.json's `read` names `{k}`, which no notices table raises")

    for section in ("twins", "open"):
        for a, w in pairs[section]:
            if a in read:
                errors.append(f"read-has-no-twin: `{a}` is read from words.json, yet {section} pairs it with the web's "
                              f"`{w}` — call t('words.{a}') and delete the web's key")
    apart = set(pairs["apart"])
    for a in sorted(set(read) & set(words)):
        said = loose_of_app(words[a]["en"])
        for w, m in sorted(web.items()):
            if (a, w) not in apart and loose_of_web(m["en"]) == said and not compare(words[a], m):
                errors.append(f"read-has-no-twin: the web's `{w}` says what `words.{a}` says, which the web reads from "
                              f"words.json — call t('words.{a}') and delete `{w}`, or pair them `apart` with why")
    unknown = [k for k in read if k not in words]
    if unknown:
        errors.append(f"read-is-notices: `read` names keys words.json has not: {unknown}")
    else:
        gen = root / GENERATED.relative_to(ROOT)
        want = generated_text(root, words, read)
        if not gen.is_file() or gen.read_text(encoding="utf-8") != want:
            errors.append(f"generated-fresh: {GENERATED.relative_to(ROOT)} is not what the catalog generates — run "
                          f"`python3 src/mobile/words/generate_words.py --web {GENERATED.relative_to(ROOT)}`")
    return errors


def main() -> int:
    try:
        errors = check(ROOT)
    except (Unreadable, OSError, json.JSONDecodeError, KeyError) as e:
        print(f"validate_web_words: {e}", file=sys.stderr)
        return 2
    for e in errors:
        print(e)
    if errors:
        print(f"\nvalidate_web_words: {len(errors)} violation(s)")
        return 1
    print("validate_web_words: the web's messages and words.json say each paired meaning alike")
    return 0


if __name__ == "__main__":
    sys.exit(main())
