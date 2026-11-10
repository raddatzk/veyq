#!/usr/bin/env python3
"""Guard that the veyq app's creating action follows each platform's guidelines, through one building block each
(ADR 0123 decision 9).

**What it asserts.** A view's one creating action ("Add entry", "New pot") is Material 3's on Android and Apple's
HIG's on iOS and macOS and GNOME's on Linux, and each platform builds it in exactly one place, so a new list view cannot
drift from it. Six rules, over the sources of `src/mobile/android/src` (`.kt`), `src/mobile/apple` (`.swift`, no
`build/`) and `src/mobile/linux` (`.rs`, no `target/`), read with comments and literals blanked (`kotlin_lexer.mask`,
`rust_lexer.mask` for Rust), so a name in prose or a bracket in a string poses as nothing; only the "+" rules read the
literals:
- **android-fab**: no file but the building block `CreateAction.kt` names a floating action button
  (`FloatingActionButton`, `ExtendedFloatingActionButton`, `SmallFloatingActionButton`, `LargeFloatingActionButton`).
  Material 3 gives a screen at most one, for its primary action; the block is an extended FAB that collapses to its
  icon on scroll and hands the list under it the room below its last row.
- **android-block**: `CreateAction.kt` exists, builds an `ExtendedFloatingActionButton(` and collapses it on
  `canScrollBackward` — a block that lost its collapse would pass the first rule with every caller.
- **apple-floating-button**: no Swift file floats a button over a view's bottom — `.overlay(…)` or `ZStack(…)` aligned
  `.bottom…`, or `.safeAreaInset(edge: .bottom…)`, whose arguments and trailing closure hold a `Button`, `ShareLink`,
  `Menu` or `Link`. iOS has no floating action button; the "+" belongs in the navigation bar. A notice aligned there
  (the trash's Undo) is a view named in the closure, not a button in it, and passes.
- **apple-block**: `CreateAction.swift` exists and places its button `.primaryAction`; and no other file draws the bare
  "+" (`Image(systemName: "plus")`), so a hand-made "+" in a bar goes through `.createAction`. A labelled "+"
  (`Label("New", systemImage: "plus")`, the sidebar's) is a row with its words, not the bar's symbol, and passes.
- **linux-block**: `create_action.rs` exists and builds a `gtk::Button` and a `gtk::MenuButton` (a level that creates
  several kinds) drawn with GNOME's add icon — the header bar's or the sidebar's "+"; and no other Rust file names that
  icon, so a hand-made "+" goes through the block.
- **linux-floating-button**: no Rust file hands a button to `Overlay::add_overlay` — as an argument naming `Button`
  (`gtk::Button`, `MenuButton`, …) or as a variable the same file binds to one (`let b = gtk::Button::…;`, the
  block's `create_action(…)`). GNOME has no floating action button.

**The defect that bought it.** The Pot's "Add entry" and Expenses' "New pot" shipped as a floating capsule on iOS, the
web's and Android's look carried over, until the owner found each by eye (2026-11-09): the first covered the last
entries, the second was found only after the first was fixed. The owner then asked that the guidelines hold everywhere
(Apple HIG and Material 3 over a uniform look), not only where someone looks.

**Its limit.** It reads names, not rendering. A button built inside another view and only that view's name in the
overlay's closure is not seen (the trash notice's case, which is why it passes); nor is a floating button drawn by
offsets or `GeometryReader`, nor a Compose `Box` that aligns a plain `Button` at the bottom end. The Swift files are
read by the Kotlin lexer: a string interpolation holding a quote (`"\\(f("x"))"`) can unbalance a literal, which may hide
or invent a match after it. It does not judge row actions (an overflow menu on Android, swipe actions and a context
menu on iOS), which no rule here names. On Linux a button reaching `add_overlay` through a function's return value or a
field (`add_overlay(&self.make())`) is not seen; nor a widget floated by a `gtk::Fixed` or margins.
"""
from __future__ import annotations

import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import repo_layout  # noqa: E402
from kotlin_lexer import mask  # noqa: E402
from rust_lexer import mask as mask_rust  # noqa: E402

ROOT = repo_layout.ROOT
ANDROID_SRC = repo_layout.MOBILE_ANDROID / "src"
APPLE = repo_layout.MOBILE_APPLE
ANDROID_BLOCK = "CreateAction.kt"
APPLE_BLOCK = "CreateAction.swift"
LINUX = repo_layout.MOBILE_LINUX
LINUX_BLOCK = "create_action.rs"
ADD_ICON = '"list-add-symbolic"'
GTK_BUTTON = re.compile(r"\bButton::(?:builder|new|from_icon_name|with_label)\s*\(")
GTK_MENU_BUTTON = re.compile(r"\bMenuButton::(?:builder|new)\s*\(")
ADD_OVERLAY = re.compile(r"\.add_overlay\s*\(")

FAB = re.compile(r"\b(?:Extended|Small|Large)?FloatingActionButton\b")
# A bottom-aligned overlay or stack, or a bottom inset: where a floating button would sit.
FLOATING = re.compile(r"(?:\.overlay|\bZStack)\s*\(|\.safeAreaInset\s*\(")
BOTTOM = re.compile(r"alignment\s*:\s*\.bottom|edge\s*:\s*\.bottom")
BUTTON = re.compile(r"\b(?:Button|ShareLink|Menu|Link)\s*[({]")
BARE_PLUS = re.compile(r'Image\s*\(\s*systemName\s*:\s*"plus"\s*\)')


def _close(src: str, start: int, open_: str, close: str) -> int:
    """The index after the bracket matching the one at `start` (on masked source), or the end of `src`."""
    depth = 0
    for i in range(start, len(src)):
        if src[i] == open_:
            depth += 1
        elif src[i] == close:
            depth -= 1
            if depth == 0:
                return i + 1
    return len(src)


def _span(src: str, paren: int) -> str:
    """A call's arguments from its `(` at `paren`, with its trailing closure when one follows."""
    end = _close(src, paren, "(", ")")
    rest = src[end:]
    lead = len(rest) - len(rest.lstrip(" \t"))
    if rest[lead:lead + 1] == "{":
        end = _close(src, end + lead, "{", "}")
    return src[paren:end]


def _line(src: str, offset: int) -> int:
    return src.count("\n", 0, offset) + 1


def sources(base: pathlib.Path, suffix: str, out: str = "build") -> list[pathlib.Path]:
    return sorted(p for p in base.rglob(f"*{suffix}") if out not in p.relative_to(base).parts)


def check_linux(linux: pathlib.Path) -> list[str]:
    errors: list[str] = []
    rs = sources(linux, ".rs", out="target") if linux.exists() else []
    if not rs:
        errors.append(f"no Rust source under {linux} — the guard would read nothing")
    block = None
    for f in rs:
        text = f.read_text()
        src = mask_rust(text)
        if f.name == LINUX_BLOCK:
            block = (src, mask_rust(text, literals=False))
        elif ADD_ICON in mask_rust(text, literals=False):
            errors.append(f"linux-block: {f}: GNOME's add icon outside {LINUX_BLOCK} — "
                          "a view's creating action is `create_action(…)` / `create_menu(…)`")
        # A variable this file binds to a button: `let [mut] name[: T] = …Button…;`
        buttons = {m.group(1) for m in re.finditer(r"\blet\s+(?:mut\s+)?(\w+)[^=;]*=([^;]*);", src)
                   if re.search(r"Button\b|\bcreate_(?:action|menu)\s*\(", m.group(0))}
        for m in ADD_OVERLAY.finditer(src):
            arg = src[m.end() - 1:_close(src, m.end() - 1, "(", ")")]
            names = set(re.findall(r"\w+", arg))
            if re.search(r"Button\b", arg) or names & buttons:
                errors.append(f"linux-floating-button: {f}:{_line(src, m.start())}: a button floated over a view "
                              "through an Overlay — GNOME has no floating action button; use `create_action(…)` "
                              "in the header bar")
    if block is None:
        errors.append(f"linux-block: no {LINUX_BLOCK} under {linux}")
    else:
        code, said = block
        if ADD_ICON not in said:
            errors.append(f"linux-block: {LINUX_BLOCK} no longer draws its button with GNOME's add icon")
        if not GTK_BUTTON.search(code):
            errors.append(f"linux-block: {LINUX_BLOCK} builds no gtk::Button")
        if not GTK_MENU_BUTTON.search(code):
            errors.append(f"linux-block: {LINUX_BLOCK} builds no gtk::MenuButton for a level creating several kinds")
    return errors


def check(android_src: pathlib.Path, apple: pathlib.Path, linux: pathlib.Path = LINUX) -> list[str]:
    errors: list[str] = []
    kt = sources(android_src, ".kt")
    swift = sources(apple, ".swift")
    if not kt:
        errors.append(f"no Kotlin source under {android_src} — the guard would read nothing")
    if not swift:
        errors.append(f"no Swift source under {apple} — the guard would read nothing")

    android_block = None
    for f in kt:
        src = mask(f.read_text())
        if f.name == ANDROID_BLOCK:
            android_block = src
            continue
        for m in FAB.finditer(src):
            errors.append(f"android-fab: {f}:{_line(src, m.start())}: {m.group()} outside {ANDROID_BLOCK} — "
                          "a view's creating action is `CreateAction(…)`")
    if android_block is None:
        errors.append(f"android-block: no {ANDROID_BLOCK} under {android_src}")
    else:
        if "ExtendedFloatingActionButton(" not in android_block:
            errors.append(f"android-block: {ANDROID_BLOCK} builds no ExtendedFloatingActionButton")
        if "canScrollBackward" not in android_block:
            errors.append(f"android-block: {ANDROID_BLOCK} no longer collapses its button on scroll (canScrollBackward)")

    apple_block = None
    for f in swift:
        text = f.read_text()
        src = mask(text)
        said = mask(text, literals=False)
        if f.name == APPLE_BLOCK:
            apple_block = src
            continue
        for m in FLOATING.finditer(src):
            span = _span(src, m.end() - 1)
            if BOTTOM.search(span) and BUTTON.search(span):
                errors.append(f"apple-floating-button: {f}:{_line(src, m.start())}: a button floated over the view's "
                              "bottom — iOS has no floating action button; use `.createAction(…)` for the bar's \"+\"")
        for m in BARE_PLUS.finditer(said):
            errors.append(f"apple-block: {f}:{_line(src, m.start())}: a bare \"+\" outside {APPLE_BLOCK} — "
                          "a view's creating action is `.createAction(…)`")
    if apple_block is None:
        errors.append(f"apple-block: no {APPLE_BLOCK} under {apple}")
    elif not re.search(r"placement\s*:\s*\.primaryAction", apple_block):
        errors.append(f"apple-block: {APPLE_BLOCK} no longer places its button .primaryAction")
    errors += check_linux(linux)
    return errors


def main() -> int:
    errors = check(ANDROID_SRC, APPLE)
    for e in errors:
        print(e)
    if errors:
        print(f"validate_mobile_platform_guidelines: {len(errors)} violation(s)")
        return 1
    print("validate_mobile_platform_guidelines: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
