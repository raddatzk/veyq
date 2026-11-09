#!/usr/bin/env python3
"""Guard that the veyq app writes no word of its own on any platform: every word it shows comes from the one catalog
`src/mobile/words/words.json` (ADR 0127 decision 3).

**What it asserts.** Three rules, over the app's sources (`src/mobile/android/src`, `src/mobile/apple`, no `build/`):
- **android-strings**: no `res/values*/strings.xml` (nor any `res/values*/*.xml` holding a `<string` or `<plurals`): the
  Android words are generated (`generate_words.py --android-res`), and a hand-written resource would sit beside them
  until one of the two drifted.
- **apple-localized**: no Swift file calls `String(localized:`, which looks a sentence up in the default table — where
  no German stands — instead of the catalog's `Words` (`say(.key, …)`).
- **apple-literal**: no Swift file hands an English sentence to a view that shows it — `Text`, `Button`, `Label`,
  `Section`, `Toggle`, `TextField`, `SecureField`, `Picker`, `DatePicker`, `Menu`, `ContentUnavailableView`,
  `LabeledContent`, `.navigationTitle`, `.alert`, `.confirmationDialog`, `.accessibilityLabel`, `.help`,
  `.createAction`, `.createMenu` — as a literal with letters in it, alone or as either side of a `?:`. SwiftUI reads
  such a literal as a key of the default table, so it shows the English in every language.

**The defect that bought it.** The Apple app had no German at all: ~480 English literals, while Android carried its
own copy of the same words in German (owner 2026-11-09, ADR 0127). A word added on one platform alone is how they
drifted apart.

**Its limit.** It reads literals where they are shown, not where they come from: a sentence built in a helper and
handed to `Text` as a variable is not seen, nor a Kotlin `Text("…")` on Android (Compose's `stringResource` is the
only door today, unguarded). Format strings and identifiers (no letters, or lowercase dotted keys) pass.

Exit 0 green, 1 red (every violation printed), 2 when the tree is not where it should be.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MOBILE = ROOT / "src" / "mobile"

VIEWS = (r"(?:(?<![\w.])(?:Text|Button|Label|Section|Toggle|TextField|SecureField|Picker|DatePicker|Menu|"
         r"ContentUnavailableView|LabeledContent)|\.(?:navigationTitle|alert|confirmationDialog|accessibilityLabel|"
         r"help|createAction|createMenu))\(")
# the shown argument: a literal, or a `cond ? a : b` whose either side is one
LITERAL_ARG = re.compile(VIEWS + r'\s*(?:[^,()"]*\?\s*)?"((?:[^"\\]|\\.)*)"(?:\s*:\s*"((?:[^"\\]|\\.)*)")?')
PROSE = re.compile(r"[A-Za-z]{2}")
IDENT = re.compile(r"[a-z0-9_.\-/:]+")


def strip_comment(line: str) -> str:
    i = line.find("//")
    return line if i < 0 else line[:i]


def check(root: Path = MOBILE) -> list[str]:
    errors: list[str] = []
    android = root / "android" / "src"
    if android.exists():
        for xml in sorted(android.glob("*/res/values*/*.xml")):
            text = xml.read_text(encoding="utf-8")
            if xml.name == "strings.xml" or "<string " in text or "<plurals " in text:
                errors.append(f"android-strings: {xml.relative_to(ROOT)}: words belong in src/mobile/words/words.json")
    apple = root / "apple"
    for swift in sorted(apple.rglob("*.swift")) if apple.exists() else []:
        if "build" in swift.relative_to(apple).parts:
            continue
        rel = swift.relative_to(ROOT)
        for n, raw in enumerate(swift.read_text(encoding="utf-8").splitlines(), 1):
            line = strip_comment(raw)
            if "String(localized:" in line:
                errors.append(f"apple-localized: {rel}:{n}: `String(localized:` — use `say(.key, …)` from the catalog")
            for m in LITERAL_ARG.finditer(line):
                for lit in (m.group(1), m.group(2)):
                    if lit and PROSE.search(lit) and not IDENT.fullmatch(lit):
                        errors.append(f"apple-literal: {rel}:{n}: \"{lit[:60]}\" shown as English in every language — use `say(.key, …)`")
    return errors


def main() -> int:
    if not MOBILE.is_dir():
        print(f"mobile words: {MOBILE} not found", file=sys.stderr)
        return 2
    errors = check()
    if errors:
        print("MOBILE WORDS VALIDATION FAILED:")
        for e in errors:
            print("  -", e)
        return 1
    print("mobile words OK: no word outside src/mobile/words/words.json")
    return 0


if __name__ == "__main__":
    sys.exit(main())
