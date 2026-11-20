#!/usr/bin/env python3
"""Guard that the veyq app writes no word of its own on any platform: every word it shows comes from the one catalog
`src/mobile/words/words.json` (ADR 0136 decision 3).

**What it asserts.** Nine rules, over the app's sources (`src/mobile/android/src`, `src/mobile/apple`, no `build/`;
`src/mobile/linux`, no `target/`; `src/mobile/windows`, no `bin/` or `obj/`) and the two manifests that show text:
- **android-strings**: no `res/values*/strings.xml` (nor any `res/values*/*.xml` holding a `<string` or `<plurals`): the
  Android words are generated (`generate_words.py --android-res`), and a hand-written resource would sit beside them
  until one of the two drifted.
- **android-literal**: no Kotlin file hands a literal with letters to what Android shows — Compose's `Text(` (so a
  `title` / `label` / `placeholder` / `text` lambda's too), a `contentDescription =` or `stateDescription =` (a
  parameter's or a `semantics {}` property's), `Toast.makeText(`, `.showSnackbar(`, a platform dialog's or a
  notification's `.setTitle(` / `.setMessage(` / `.setContentTitle(` / `.setContentText(` / `.setTicker(` — alone or
  as either side of an `if (…) "a" else "b"`. A template's `$name` / `${…}` are code, not words: `"• ${say(it)}"`
  passes. The words are `words(Msg.…)` / `stringResource(R.string.<key>)`. Read with comments blanked
  (`kotlin_lexer.mask`). The guard fails when it reads no Kotlin source.
- **apple-localized**: no Swift file calls `String(localized:`, which looks a sentence up in the default table — where
  no German stands — instead of the catalog's `Words` (`say(.key, …)`).
- **apple-literal**: no Swift file hands an English sentence to a view that shows it — `Text`, `Button`, `Label`,
  `Section`, `Toggle`, `TextField`, `SecureField`, `Picker`, `DatePicker`, `Menu`, `ContentUnavailableView`,
  `LabeledContent`, `.navigationTitle`, `.alert`, `.confirmationDialog`, `.accessibilityLabel`, `.accessibilityHint`,
  `.accessibilityValue`, `.help`, `.createAction`, `.createMenu` — as a literal with letters in it, alone or as either
  side of a `?:`. SwiftUI reads such a literal as a key of the default table, so it shows the English in every
  language; `Text(verbatim:` shows it as it is, the English too. An interpolation's `\\(…)` is code, not words.
- **linux-literal**: no Rust file hands a literal with letters to a widget's shown text — a builder's or a setter's
  `title`, `subtitle`, `label`, `description`, `tooltip_text`, `placeholder_text`, `heading`, `body`, `text`
  (`.title(…)`, `.set_title(…)`, an `AdwEntryRow`'s `.text(…)`, …), `Label::new(`, `Button::with_label(`,
  `Toast::new(`, `SidebarItem::new(`, `WindowTitle::new(`, `gio::MenuItem::new(`, a menu's `.append_section(` and an
  item's `.append(` / `.prepend(` (`gio::Menu`'s label; a `gtk::Box` appends a widget, never a literal), an
  `AdwAlertDialog`'s response label (`.add_response(id, label)`, each `(id, label)` of `.add_responses(&[…])`) —
  directly, as `Some(…)`, or as a
  `format!` whose template has letters. The words are `words::<key>(…)`, generated from the catalog with its gettext
  catalogs (ADR 0123's Consequences, the Linux app); a literal shows the English in every language. Read with comments
  blanked (`rust_lexer.mask`). The guard fails when it reads no Rust source.
- **linux-po**: no `.po` / `.pot` file under `src/mobile/linux/`: the catalogs are generated (`generate_words.py
  --gettext`) into the build directory, and a hand-written one would sit beside them until they drifted.
- **windows-literal**: no XAML file gives a control shown text as a literal with letters — the attributes `Text`,
  `Content`, `Header`, `PlaceholderText`, `Title`, `Label`, `Description`, `Message` (an `InfoBar`'s),
  `PrimaryButtonText`, `SecondaryButtonText`, `CloseButtonText` (a `ContentDialog`'s), `AutomationProperties.Name`,
  `AutomationProperties.HelpText`, `ToolTipService.ToolTip` (a markup extension, `{x:Bind …}`, passes), nor as an
  element's content (`<TextBlock>Open</TextBlock>`; a colour such as `#FF5B4DF5` passes) — and no C# file assigns one to
  those properties (`Text = "…"`, an object initializer's too, a `$"…"` template with letters among them) or hands one
  to `AutomationProperties.SetName(`, `AutomationProperties.SetHelpText(`, `ToolTipService.SetToolTip(`,
  `CreateAction.Button(` / `.Menu(` or a save picker's
  `FileTypeChoices.Add(` (the type's label, shown in the system's dialog). The words are `Words.<Key>(…)`, generated
  from the catalog with its .resw catalogs (ADR 0123's Consequences, the Windows app). C# is read with comments blanked
  (`kotlin_lexer.mask`: C#'s comments are Kotlin's), XAML with its comments removed. The guard fails when it reads no C#
  source.
- **windows-resw**: no `.resw` file under `src/mobile/windows/`: the catalogs are generated (`generate_words.py
  --resw`) into the build directory.
- **manifest-literal**: no usage description (`NS…UsageDescription`, a permission prompt's words) in
  `src/mobile/apple/project.yml` (an `INFOPLIST_KEY_` setting) or any checked-in `Info.plist` under `src/mobile/apple`,
  and no `<summary>` or `<p>` in the Flatpak's metainfo template `src/mobile/linux/flatpak/metainfo.xml`: both are
  generated from the catalog in every language (`generate_words.py --apple-infoplist`, `--metainfo`), and a hand-written
  one is English only. Red when either file is missing. Windows' MSIX manifest shows only the name `veyq`, an
  untranslated name, so it is not read.

**The defect that bought it.** The Apple app had no German at all: ~480 English literals, while Android carried its
own copy of the same words in German (owner 2026-11-09, ADR 0136). A word added on one platform alone is how they
drifted apart.

**Its limit.** It reads literals where they are shown, not where they come from: a sentence built in a helper and
handed to `Text` as a variable, or reaching a shown property through a variable or a method's return, is not seen, on
every platform. Format strings and identifiers (no letters, or lowercase dotted keys) pass. A property or call the lists
above lack is not seen: on Linux `glib::Object::builder().property("title", …)`; on Android a composable of the app's
own taking a `String` it shows (`label = "…"` passed to it). A Kotlin template whose `${…}` holds a quoted literal
(`"${if (a) "x" else "y"}"`) ends the literal early, and a C# verbatim or raw string holding a quote (the Kotlin lexer
reads `@"a""b"` as two literals) is read as two. A manifest's other fields are not read (the metainfo's `<name>` and
`<developer>` hold the name `veyq`, an untranslated name).

Exit 0 green, 1 red (every violation printed), 2 when the tree is not where it should be.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import repo_layout  # noqa: E402
from kotlin_lexer import mask as mask_kotlin  # noqa: E402
from rust_lexer import mask as mask_rust  # noqa: E402

ROOT = repo_layout.ROOT
MOBILE = repo_layout.MOBILE

VIEWS = (r"(?:(?<![\w.])(?:Text|Button|Label|Section|Toggle|TextField|SecureField|Picker|DatePicker|Menu|"
         r"ContentUnavailableView|LabeledContent)|\.(?:navigationTitle|alert|confirmationDialog|accessibilityLabel|"
         r"accessibilityHint|accessibilityValue|help|createAction|createMenu))\(")
# the shown argument: a literal, or a `cond ? a : b` whose either side is one; `Text(verbatim:` shows its literal as it
# is, so a verbatim literal with letters is a word of the app's own too
LITERAL_ARG = re.compile(VIEWS + r'\s*(?:verbatim:\s*)?(?:[^,()"]*\?\s*)?"((?:[^"\\]|\\.)*)"(?:\s*:\s*"((?:[^"\\]|\\.)*)")?')
PROSE = re.compile(r"[A-Za-z]{2}")
IDENT = re.compile(r"[a-z0-9_.\-/:]+")


# A widget's shown text in gtk-rs / libadwaita-rs: a builder's or setter's property, or a constructor taking it.
RUST_SHOWN = (r"(?:(?:\.(?:set_)?(?:title|subtitle|label|description|tooltip_text|placeholder_text|heading|body|text)|"
              r"\bLabel::new|\bButton::with_label|\bToast::new|\bSidebarItem::new|\bWindowTitle::new|\bMenuItem::new|"
              r"\.append_section|\.(?:append|prepend))\("
              # an AdwAlertDialog's response: its id, then its label; `add_responses(&[(id, label), …])` each pair
              r"|(?:\.add_response\(|(?<=[\[,])\s*\()\s*\"[^\"]*\"\s*,)")
# its argument: a literal (plain or raw), as it is, as `Some(…)`, borrowed, or as a `format!` template
RUST_LITERAL_ARG = re.compile(RUST_SHOWN + r'\s*(?:Some\(\s*)?&?\s*(?:format!\(\s*)?r?#*"((?:[^"\\]|\\.)*)"')


RUST_RESPONSES = re.compile(r"\.add_responses\(\s*&\[(.*?)\]\s*\)", re.S)


def rust_sources(linux: Path) -> list[Path]:
    return sorted(p for p in linux.rglob("*.rs") if "target" not in p.relative_to(linux).parts)


def check_linux(linux: Path) -> list[str]:
    errors: list[str] = []
    rs = rust_sources(linux) if linux.exists() else []
    if not rs:
        errors.append(f"linux-literal: no Rust source under {linux} — the rule would read nothing")
    for f in rs:
        said = mask_rust(f.read_text(encoding="utf-8"), literals=False)
        responses = [(r.start(1), r.group(1)) for r in RUST_RESPONSES.finditer(said)]
        for m in RUST_LITERAL_ARG.finditer(said):
            if m.group().lstrip().startswith("(") and not any(a <= m.start() < a + len(b) for a, b in responses):
                continue  # a `(id, …)` pair outside `add_responses` is no response
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


# The properties a WinUI control shows as text, in XAML (an attribute) and C# (an assignment).
WINDOWS_SHOWN = ("Text", "Content", "Header", "PlaceholderText", "Title", "Label", "Description", "Message",
                 "PrimaryButtonText", "SecondaryButtonText", "CloseButtonText")
XAML_SHOWN = re.compile(r'(?<![\w.:])(' + "|".join(WINDOWS_SHOWN) + r'|AutomationProperties\.(?:Name|HelpText)|ToolTipService\.ToolTip)'
                        r'\s*=\s*"([^"]*)"')
XAML_CONTENT = re.compile(r">([^<>]*)<")
XAML_COMMENT = re.compile(r"<!--.*?-->", re.S)
HEX_COLOUR = re.compile(r"#[0-9A-Fa-f]{3,8}")
CS_SHOWN = re.compile(r'(?<![\w.])(?:\w+\.)?(?:' + "|".join(WINDOWS_SHOWN) + r')\s*=\s*[$@]*"((?:[^"\\]|\\.)*)"'
                      r'|\b(?:AutomationProperties\.Set(?:Name|HelpText)|ToolTipService\.SetToolTip)\s*\([^,()]*,\s*[$@]*"((?:[^"\\]|\\.)*)"'
                      r'|\bCreateAction\.(?:Button|Menu)\s*\(\s*[$@]*"((?:[^"\\]|\\.)*)"'
                      r'|\bFileTypeChoices\.Add\s*\(\s*[$@]*"((?:[^"\\]|\\.)*)"')


def windows_sources(windows: Path, suffix: str) -> list[Path]:
    return sorted(p for p in windows.rglob(f"*{suffix}") if not {"bin", "obj"} & set(p.relative_to(windows).parts))


def shown_prose(lit: str) -> bool:
    return bool(PROSE.search(lit)) and not IDENT.fullmatch(lit) and not lit.lstrip().startswith("{")


def check_windows(windows: Path) -> list[str]:
    errors: list[str] = []
    cs = windows_sources(windows, ".cs") if windows.exists() else []
    if not cs:
        errors.append(f"windows-literal: no C# source under {windows} — the rule would read nothing")
    for f in cs:
        said = mask_kotlin(f.read_text(encoding="utf-8"), literals=False)
        for m in CS_SHOWN.finditer(said):
            lit = next(g for g in m.groups() if g is not None)
            if shown_prose(lit):
                line = said.count("\n", 0, m.start()) + 1
                errors.append(f"windows-literal: {f.relative_to(ROOT)}:{line}: \"{lit[:60]}\" shown as English in every "
                              "language — use `Words.<Key>(…)`")
    for f in windows_sources(windows, ".xaml") if windows.exists() else []:
        text = XAML_COMMENT.sub(lambda m: "\n" * m.group().count("\n"), f.read_text(encoding="utf-8"))
        found = [(m.start(), m.group(2)) for m in XAML_SHOWN.finditer(text)]
        found += [(m.start(), m.group(1).strip()) for m in XAML_CONTENT.finditer(text) if not HEX_COLOUR.fullmatch(m.group(1).strip())]
        for at, lit in sorted(found):
            if shown_prose(lit):
                errors.append(f"windows-literal: {f.relative_to(ROOT)}:{text.count(chr(10), 0, at) + 1}: \"{lit[:60]}\" shown "
                              "as English in every language — set it from `Words.<Key>(…)`")
    for resw in windows_sources(windows, ".resw") if windows.exists() else []:
        errors.append(f"windows-resw: {resw.relative_to(ROOT)}: the catalogs are generated from "
                      "src/mobile/words/words.json (generate_words.py --resw)")
    return errors


def without_interpolations(lit: str, opener: str, close: str) -> str:
    """[lit] without its interpolations (Swift's `\\(…)`, Kotlin's `${…}`), balanced: code, not words; what is left
    between them is what the literal itself says."""
    out, i = [], 0
    while i < len(lit):
        if lit.startswith(opener, i):
            depth, i = 1, i + len(opener)
            while i < len(lit) and depth:
                depth += {opener[-1]: 1, close: -1}.get(lit[i], 0)
                i += 1
            out.append(" ")
        else:
            out.append(lit[i])
            i += 1
    return "".join(out)


# What Android shows as text from Kotlin: Compose's `Text(…)` (a `title`/`text`/`label`/`placeholder` lambda's too), a
# `contentDescription` / `stateDescription` (a parameter's or a `semantics {}` property's), a Toast, a snackbar, and a
# platform dialog's or a notification's title and message.
KOTLIN_SHOWN = (r"(?:(?<![\w.])Text\(\s*(?:text\s*=\s*)?|\b(?:contentDescription|stateDescription)\s*=\s*"
                r"|\bToast\.makeText\([^,()]*,\s*|\.(?:setTitle|setMessage|setContentTitle|setContentText|setTicker)\(\s*"
                r"|\.showSnackbar\(\s*(?:message\s*=\s*)?)")
# the shown argument: a literal, or an `if (…) "a" else "b"` whose either side is one
KOTLIN_LITERAL_ARG = re.compile(KOTLIN_SHOWN + r'(?:if\s*\([^()]*\)\s*)?"((?:[^"\\]|\\.)*)"(?:\s*else\s*"((?:[^"\\]|\\.)*)")?')
KOTLIN_NAME_TEMPLATE = re.compile(r"\$[A-Za-z_]\w*")


def check_android_kotlin(android: Path) -> list[str]:
    errors: list[str] = []
    kt = sorted(p for p in android.rglob("*.kt") if "build" not in p.relative_to(android).parts) if android.exists() else []
    if not kt:
        errors.append(f"android-literal: no Kotlin source under {android} — the rule would read nothing")
    for f in kt:
        said = mask_kotlin(f.read_text(encoding="utf-8"), literals=False)
        for m in KOTLIN_LITERAL_ARG.finditer(said):
            for lit in (m.group(1), m.group(2)):
                if lit is None:
                    continue
                words = KOTLIN_NAME_TEMPLATE.sub(" ", without_interpolations(lit, "${", "}"))
                if PROSE.search(words) and not IDENT.fullmatch(words):
                    line = said.count("\n", 0, m.start()) + 1
                    errors.append(f"android-literal: {f.relative_to(ROOT)}:{line}: \"{lit[:60]}\" shown as English in every "
                                  "language — use `words(Msg.…)` / `say(…)`")
    return errors


# The manifests that show a person text: Apple's usage descriptions (the permission prompts), the Flatpak's metainfo
# summary and description (a software centre's page). Both are generated from the catalog in every language
# (`generate_words.py --apple-infoplist`, `--metainfo`); a hand-written one is English only.
USAGE_YML = re.compile(r"^\s*(?:INFOPLIST_KEY_)?(NS\w*UsageDescription)\s*:", re.M)
USAGE_PLIST = re.compile(r"<key>\s*(NS\w*UsageDescription)\s*</key>")
METAINFO_SHOWN = re.compile(r"<(summary|p)\b")
# Staged (cr-apps-words-guard-reads-every-ui): the manifests' texts are in the catalog, and the generator that writes
# them is its phase 2, which empties this list; an entry is red once its manifest no longer holds it.
MANIFEST_PENDING = {
    ("apple/project.yml", "NSPhotoLibraryAddUsageDescription"),
    ("apple/project.yml", "NSCameraUsageDescription"),
    ("linux/flatpak/metainfo.xml", "summary"),
    ("linux/flatpak/metainfo.xml", "p"),
}


def check_manifests(root: Path) -> list[str]:
    errors: list[str] = []
    found: list = []  # (the manifest under root, what it holds by hand, where, the generator's flag)
    project, metainfo = root / "apple" / "project.yml", root / "linux" / "flatpak" / "metainfo.xml"
    for need in (project, metainfo):
        if not need.exists():
            errors.append(f"manifest-literal: no {need} — the rule would read nothing")
    plists = sorted(p for p in (root / "apple").rglob("Info.plist") if "build" not in p.relative_to(root / "apple").parts)
    for f, pattern, flag in [(project, USAGE_YML, "--apple-infoplist"), (metainfo, METAINFO_SHOWN, "--metainfo")] + [
            (p, USAGE_PLIST, "--apple-infoplist") for p in plists]:
        if not f.exists():
            continue
        text = XAML_COMMENT.sub(lambda m: "\n" * m.group().count("\n"), f.read_text(encoding="utf-8"))
        for m in pattern.finditer(text):
            found.append((f.relative_to(root).as_posix(), m.group(1), f"{f.relative_to(ROOT)}:{text.count(chr(10), 0, m.start()) + 1}", flag))
    for rel, what, at, flag in found:
        if (rel, what) not in MANIFEST_PENDING:
            errors.append(f"manifest-literal: {at}: {what} written by hand, English only — its words are the catalog's "
                          f"(generate_words.py {flag})")
    for rel, what in sorted(MANIFEST_PENDING - {(r, w) for r, w, _, _ in found}):
        errors.append(f"manifest-literal: MANIFEST_PENDING names {rel}'s {what}, which it no longer holds — drop the entry")
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
    errors += check_android_kotlin(android)
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
                    said = without_interpolations(lit, "\\(", ")") if lit else lit
                    if said and PROSE.search(said) and not IDENT.fullmatch(said):
                        errors.append(f"apple-literal: {rel}:{n}: \"{lit[:60]}\" shown as English in every language — use `say(.key, …)`")
    errors += check_linux(root / repo_layout.MOBILE_LINUX.relative_to(MOBILE))
    errors += check_windows(root / repo_layout.MOBILE_WINDOWS.relative_to(MOBILE))
    errors += check_manifests(root)
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
