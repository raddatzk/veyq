#!/usr/bin/env python3
"""Guard that the per-locale markdown editor exists ONCE, in `web/src/shared/ui/`, and that every caller uses it.

**What it asserts.** Three things about `LocaleMarkdownEditor.vue`, which is the only component in `web/src`
two unrelated features drive:
  1. exactly one file under `web/src` carries that name — no second copy anywhere;
  2. it sits directly in `web/src/shared/ui/`, next to its colocated test;
  3. every `import … from '…/LocaleMarkdownEditor.vue'` anywhere in `web/src` **resolves to that one file** —
     which is how the guard notices a caller pointing at a copy rather than at the shared component.

**The defect that bought it.** The component shipped inside `features/memory/components/`, driving the share
note, while the tenant-terms panel grew its own hand-written two-textarea editor beside it — a second per-locale
editor in everything but name. cr-terms-editor-lists-the-languages-that-have-terms lifted the component out and
rebuilt the terms panel on it. Nothing but this file stops the next reader from copying it back into whichever
feature needs the third variant, and the copy would look entirely reasonable at review time: one import path
changed, one component duplicated, two files that drift apart over a year.

**Why the caller set is scanned and never listed.** `changes/README.md` § *a guard derives its anchor set from
the code* — the case a guard misses is the one added after it was written. The two callers today are the share
dialog and the terms panel; a third is found by the same walk, with no edit here.

**Its limits, and both wrong shapes are recorded because each is what the next reader reaches for first.**

  - *The one-shot proof this guard replaces does not work.* The CR specified it as
    `grep -rln '<the component name>' web/src/features/ --include=*.vue`, and run against the untouched tree it
    returned exactly one path: `ShareCreateDialog.vue`, the **importer**. It never named the definition, because
    a component file does not contain its own name — the name is the filename. So a content grep is green
    whether the component lives in a feature or not, and the very reason this guard is filename-anchored.

  - *"A feature component imported from another feature belongs in `shared/ui/`" does not bite this.* It is the
    obvious general rule and it was measured on the untouched tree: **zero** violations — but zero on the
    *defective* tree too, because the one caller then, `ShareCreateDialog`, sat in the **same** feature as the
    component. A rule that is green on the tree it was meant to red is a rule about something else. (That
    measurement did surface one real inversion, for its own row and not this guard's: `shared/ui/`'s
    `SecretKeyField.vue` imports a component out of `features/account/`.)

  - *What it does not see.* A **second** per-locale editor under a **different** name is invisible — the anchor
    is this component's identity, not the concept. So is a dynamic `import()`, a re-export through a barrel, and
    anything outside `web/src`: `admin-web/` is a separate package with its own tree, and when it grows a
    locale editor the answer is `packages/`, which is a different guard's subject. Green means exactly: **one
    file by that name, in `shared/ui/`, and every import of it lands there.**
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
WEB_SRC = ROOT / "web" / "src"
COMPONENT = "LocaleMarkdownEditor.vue"
HOME = WEB_SRC / "shared" / "ui"

# `import X from '<path>'` where the path ends in the component's filename. Built from COMPONENT rather than
# typed out, so the pattern can never drift from the name the walk above searches for.
IMPORT_OF = re.compile(r"""from\s+['"]([^'"]*%s)['"]""" % re.escape(COMPONENT))


def rel(path: pathlib.Path) -> str:
    """A repo-relative path for the error message; absolute is unreadable in CI output."""
    try:
        return str(path.resolve().relative_to(ROOT))
    except ValueError:
        return str(path)


def main() -> int:
    if not WEB_SRC.is_dir():
        print(f"LOCALE EDITOR HOME VALIDATION FAILED:\n  - missing web source tree: {rel(WEB_SRC)}")
        return 1

    errors: list[str] = []

    # (1) + (2): how many files carry the name, and where the one lives.
    homes = sorted(p for p in WEB_SRC.rglob("*.vue") if p.name == COMPONENT)
    if not homes:
        errors.append(
            f"no {COMPONENT} anywhere under {rel(WEB_SRC)}. If the component was renamed or retired, point this "
            f"guard at the new shape — do not delete it: what it protects is that ONE editor exists, not that "
            f"this exact filename does."
        )
    elif len(homes) > 1:
        errors.append(
            f"{len(homes)} files named {COMPONENT}: {', '.join(rel(p) for p in homes)}. A second copy is how the "
            f"share note and the tenant terms drift apart — the two callers want the same editor, which is why "
            f"it was lifted into {rel(HOME)} in the first place."
        )
    else:
        home = homes[0]
        if home.parent.resolve() != HOME.resolve():
            errors.append(
                f"{COMPONENT} lives at {rel(home)}, not in {rel(HOME)}. It is driven by two unrelated features "
                f"(the share note and the tenant terms), so a feature directory is not its home — a component "
                f"stored in one feature and imported by another is a shared component wearing a feature's name."
            )

    # (3): every import lands on that one file. The caller set is WALKED, never listed — a third caller is
    # covered by this loop without touching this file.
    if len(homes) == 1:
        target = homes[0].resolve()
        for src in sorted(list(WEB_SRC.rglob("*.vue")) + list(WEB_SRC.rglob("*.ts"))):
            for spec in IMPORT_OF.findall(src.read_text(encoding="utf-8")):
                if not spec.startswith("."):
                    errors.append(
                        f"{rel(src)} imports {COMPONENT} through the non-relative specifier {spec!r}. This guard "
                        f"resolves relative paths only, so it cannot tell which file that reaches — spell it "
                        f"relative, or teach this guard the alias."
                    )
                    continue
                resolved = (src.parent / spec).resolve()
                if resolved != target:
                    errors.append(
                        f"{rel(src)} imports {COMPONENT} from {spec!r}, which resolves to {rel(resolved)} — not "
                        f"to the shared component at {rel(target)}."
                    )

    if errors:
        print("LOCALE EDITOR HOME VALIDATION FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1

    callers = sum(
        1
        for src in list(WEB_SRC.rglob("*.vue")) + list(WEB_SRC.rglob("*.ts"))
        if IMPORT_OF.search(src.read_text(encoding="utf-8"))
    )
    print(f"OK: one {COMPONENT}, in {rel(HOME)}, and all {callers} imports of it resolve there.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
