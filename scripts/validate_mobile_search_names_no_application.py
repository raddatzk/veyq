#!/usr/bin/env python3
"""Guard that the apps' core search names no application (ADR 0146 decision 4; ADR 0079: an application may use core,
never the reverse).

**What it asserts.** No Kotlin file of the core's search package — `src/mobile/core/src/<set>/kotlin/me/raddatz/veyq/mobile/
search/`, every main source set (`commonMain`, `jvmMain`, `appleMain`, …; a `…Test` set is a consumer of both sides and
is not read) — references an application's package `me.raddatz.veyq.mobile.<application>`, by an import or a qualified
name. The applications are not listed here: they are derived from the entries of `ApplicationId`
(`core/…/navigation/Applications.kt`, `MEMORIES("memories")` names the package `…mobile.memories`), so an application
added there is guarded the day it lands. An application's part reaches the search through `AppKinds.search` (the
composition root, outside the package), as the web's registry does for `src/web/src/core/search/`. The sources are read
with comments and literals blanked (`kotlin_lexer.mask`), so a name in a KDoc is no reference. The guard is red where it
reads no search source or derives no application, so a moved package or a renamed enum cannot leave it green over
nothing.

**The defect that bought it.** No incident — built with cr-app-search (2026-11-18) because the mobile side had no
direction guard at all: core's `navigation/ShellModel.kt`, `ShareSheet.kt`, `session/LinkForm.kt` and `words/Notices.kt`
already name application packages (measured at that CR's refine), and the search's port was the moment a new core package
was born that must stay clean, as the web's `validate_web_core_imports_no_applications.py` holds `core/search/` there.

**Its limit.** It guards the search package only, not the rest of core (whose existing references the CR above counted
and left). It reads names, not types: an application's class reached through a type alias declared outside the package,
an `Any` cast at runtime, or an application's kind string spelled as a literal (`"MEMORY"`, which the mask blanks) is not
seen — a kind literal in the package is a review's catch. A package reached by a star import of a parent
(`me.raddatz.veyq.mobile.*`, then `memories.X`) is not seen either.
"""
from __future__ import annotations

import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import repo_layout  # noqa: E402
from kotlin_lexer import mask  # noqa: E402

CORE_SRC = repo_layout.MOBILE / "core" / "src"
PACKAGE = pathlib.Path("kotlin/me/raddatz/veyq/mobile/search")
APPLICATIONS = CORE_SRC / "commonMain/kotlin/me/raddatz/veyq/mobile/navigation/Applications.kt"
ENTRY = re.compile(r"^\s*([A-Z][A-Z0-9_]*)\s*\(\s*\"([a-z][a-z0-9]*)\"\s*\)\s*,", re.MULTILINE)


def applications(source: str) -> list[str]:
    """The application ids `ApplicationId`'s entries carry (each one the name of its package)."""
    m = re.search(r"enum class ApplicationId\b[^{]*\{(.*?)\n\s*;", source, re.DOTALL)
    return [] if m is None else [e.group(2) for e in ENTRY.finditer(m.group(1))]


def search_sources(core_src: pathlib.Path) -> list[pathlib.Path]:
    """Every Kotlin file of the search package in a main source set."""
    out: list[pathlib.Path] = []
    for source_set in sorted(p for p in core_src.iterdir() if p.is_dir() and not p.name.endswith("Test")):
        folder = source_set / PACKAGE
        if folder.is_dir():
            out += sorted(folder.rglob("*.kt"))
    return out


def check(core_src: pathlib.Path, applications_file: pathlib.Path) -> list[str]:
    errors: list[str] = []
    if not applications_file.is_file():
        return [f"{applications_file}: not found — the applications are derived from ApplicationId's entries"]
    apps = applications(applications_file.read_text(encoding="utf-8"))
    if not apps:
        return [f"{applications_file}: no ApplicationId entry read — the guard would check nothing"]
    files = search_sources(core_src)
    if not files:
        return [f"{core_src}: no Kotlin file under */{PACKAGE} — the guard would check nothing"]
    named = re.compile(r"\bme\s*\.\s*raddatz\s*\.\s*veyq\s*\.\s*mobile\s*\.\s*(" + "|".join(map(re.escape, apps)) + r")\b")
    for f in files:
        masked = mask(f.read_text(encoding="utf-8"))
        for m in named.finditer(masked):
            line = masked.count("\n", 0, m.start()) + 1
            errors.append(
                f"{f.relative_to(repo_layout.ROOT) if f.is_relative_to(repo_layout.ROOT) else f}:{line}: names the application "
                f"package `me.raddatz.veyq.mobile.{m.group(1)}` — core's search reaches an application only through AppKinds.search",
            )
    return errors


def main() -> int:
    errors = check(CORE_SRC, APPLICATIONS)
    for e in errors:
        print(e)
    if errors:
        print(f"validate_mobile_search_names_no_application: {len(errors)} violation(s)")
        return 1
    print(f"validate_mobile_search_names_no_application: OK ({len(search_sources(CORE_SRC))} files)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
