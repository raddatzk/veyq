#!/usr/bin/env python3
"""Guard that the veyq app writes no word of its own on any platform: every word it shows comes from the one catalog
`src/mobile/words/words.json` (ADR 0127 decision 3).

**What it asserts.** Five rules, over the app's sources (`src/mobile/android/src`, `src/mobile/apple`, no `build/`;
`src/mobile/linux`, no `target/`):
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
- **linux-literal**: no Rust file hands a literal with letters to a widget's shown text — a builder's or a setter's
  `title`, `subtitle`, `label`, `description`, `tooltip_text`, `placeholder_text`, `heading`, `body` (`.title(…)`,
  `.set_title(…)`, …), `Label::new(`, `Button::with_label(`, `Toast::new(`, `SidebarItem::new(`,
  `WindowTitle::new(`, `gio::MenuItem::new(`, a menu's `.append_section(` — directly, as `Some(…)`, or as a
  `format!` whose template has letters. The words are `words::<key>(…)`, generated from the catalog with its gettext
  catalogs (CR core-as-a-native-library D10); a literal shows the English in every language. Read with comments
  blanked (`rust_lexer.mask`). The guard fails when it reads no Rust source.
- **linux-po**: no `.po` / `.pot` file under `src/mobile/linux/`: the catalogs are generated (`generate_words.py
  --gettext`) into the build directory, and a hand-written one would sit beside them until they drifted.

**The defect that bought it.** The Apple app had no German at all: ~480 English literals, while Android carried its
own copy of the same words in German (owner 2026-11-09, ADR 0127). A word added on one platform alone is how they
drifted apart.

**Its limit.** It reads literals where they are shown, not where they come from: a sentence built in a helper and
handed to `Text` as a variable is not seen, nor a Kotlin `Text("…")` on Android (Compose's `stringResource` is the
only door today, unguarded). Format strings and identifiers (no letters, or lowercase dotted keys) pass. On Linux a
widget given its words at construction by a property name the list above lacks (`gtk::Label::builder().label` passes
through `.label(`, but `AdwEntryRow`'s `.text(…)` or `glib::Object::builder().property("title", …)` do not) is not seen.

Exit 0 green, 1 red (every violation printed), 2 when the tree is not where it should be.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import repo_layout  # noqa: E402
from rust_lexer import mask as mask_rust  # noqa: E402

ROOT = repo_layout.ROOT
MOBILE = repo_layout.MOBILE

VIEWS = (r"(?:(?<![\w.])(?:Text|Button|Label|Section|Toggle|TextField|SecureField|Picker|DatePicker|Menu|"
         r"ContentUnavailableView|LabeledContent)|\.(?:navigationTitle|alert|confirmationDialog|accessibilityLabel|"
         r"help|createAction|createMenu))\(")
# the shown argument: a literal, or a `cond ? a : b` whose either side is one
LITERAL_ARG = re.compile(VIEWS + r'\s*(?:[^,()"]*\?\s*)?"((?:[^"\\]|\\.)*)"(?:\s*:\s*"((?:[^"\\]|\\.)*)")?')
PROSE = re.compile(r"[A-Za-z]{2}")
IDENT = re.compile(r"[a-z0-9_.\-/:]+")


# A widget's shown text in gtk-rs / libadwaita-rs: a builder's or setter's property, or a constructor taking it.
RUST_SHOWN = (r"(?:\.(?:set_)?(?:title|subtitle|label|description|tooltip_text|placeholder_text|heading|body)|"
              r"\bLabel::new|\bButton::with_label|\bToast::new|\bSidebarItem::new|\bWindowTitle::new|\bMenuItem::new|"
              r"\.append_section)\(")
# its argument: a literal (plain or raw), as it is, as `Some(…)`, borrowed, or as a `format!` template
RUST_LITERAL_ARG = re.compile(RUST_SHOWN + r'\s*(?:Some\(\s*)?&?\s*(?:format!\(\s*)?r?#*"((?:[^"\\]|\\.)*)"')


def rust_sources(linux: Path) -> list[Path]:
    return sorted(p for p in linux.rglob("*.rs") if "target" not in p.relative_to(linux).parts)


def check_linux(linux: Path) -> list[str]:
    errors: list[str] = []
    rs = rust_sources(linux) if linux.exists() else []
    if not rs:
        errors.append(f"linux-literal: no Rust source under {linux} — the rule would read nothing")
    for f in rs:
        said = mask_rust(f.read_text(encoding="utf-8"), literals=False)
        for m in RUST_LITERAL_ARG.finditer(said):
            lit = m.group(1)
            if PROSE.search(lit) and not IDENT.fullmatch(lit):
                line = said.count("\n", 0, m.start()) + 1
                errors.append(f"linux-literal: {f.relative_to(ROOT)}:{line}: \"{lit[:60]}\" shown as English in every "
                              "language — use `words::<key>(…)`")
    for po in sorted(linux.rglob("*.po*")) if linux.exists() else []:
        if po.suffix in (".po", ".pot") and "target" not in po.relative_to(linux).parts:
            errors.append(f"linux-po: {po.relative_to(ROOT)}: the catalogs are generated from "
                          "src/mobile/words/words.json (generate_words.py --gettext)")
    return errors


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
    errors += check_linux(root / repo_layout.MOBILE_LINUX.relative_to(MOBILE))
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
