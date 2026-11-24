#!/usr/bin/env python3
"""Guard the Kotlin core's module layout (ADR 0155 decision 7.1, the apps' half of ADR 0079): an application uses the
platform, never the reverse and never another application, read from the build files as the backend's
`validate_backend_module_graph.py` reads its own. Buildless: it reads the settings, the build files and the sources.

**What it asserts.** The modules are the projects `src/mobile/settings.gradle.kts` includes (`repo_layout.mobile_projects`;
`:apps:x` is `src/mobile/apps/x`). An edge is `<configuration>(project(":x"))` in a build file — `implementation`, `api`,
`compileOnly`, `runtimeOnly`, the framework's `export` — and it is a test edge where it sits inside a source set's
`dependencies {}` block whose set's name ends in `Test` (`commonTest.dependencies { … }`), a main edge everywhere else.

- **(a) The settings name every module, and the applications are `ApplicationId`'s.** The included projects equal the
  directories below `src/mobile/` holding a `build.gradle.kts` (build output aside): a module directory nobody includes
  is compiled by nothing, an include without one configures an empty project. Every included project is one of the
  kinds the direction is defined for — `:core` (the platform), `:apps:<application>`, `:composition`, `:testing` (the
  shared test support) — or a client (`:bridge`, `:android`). And the `:apps:*` set equals the entries of the
  platform's `ApplicationId` (`navigation/Applications.kt`, `MEMORIES("memories")` names `:apps:memories`) both ways: an
  application without a module has no home, a module no `ApplicationId` names is reached by no registry.
- **(b) The direction.** `:core` has no main edge, and no test edge but to `:testing`; an application's main edges go
  to `:core` alone (never another application, never `:composition`), its test edges to `:core` and `:testing`;
  `:composition` reaches `:core` and every application (each one `api` and `export`ed, so a client sees them all) and,
  in its tests, `:testing`; `:testing` reaches `:core` alone; a client (`:bridge`, `:android`) reaches `:composition` and,
  in its tests, `:testing`. Nothing reaches `:testing` over a main edge. Gradle refuses only a cycle; an `:apps:a` →
  `:apps:b` edge or a platform that names an application compiles.
- **(c) A package lives in one module.** Every `package` a main source set of the platform, an application or the
  composition declares (comments masked) is declared by that module alone; an application's packages
  (`…mobile.<application>`, `…mobile.api.<application>`) only in its module, `…mobile.composition` only in
  `:composition`; and a file's package is its directory's. The shared test support (`:testing`) is test code: it
  declares helpers in the platform's packages on purpose and is not read.
- **(d) The platform's API opens to its own only.** The opt-in of `me.raddatz.veyq.mobile.PlatformApi` — `optIn(…)` in a
  build file, `@OptIn(PlatformApi::class)` or `@file:OptIn(…PlatformApi…)` in a source — appears only in the core's
  modules: the convention in `src/mobile/build.gradle.kts`, whose module test names `:core`, `:composition`, `:testing`
  and `:apps:` and nothing else, and those modules' own files. A client opting in would reach what the platform kept
  from it.
- **(e) What opens stays hidden from Objective-C.** Every `@PlatformApi` declaration of a common or native main source
  set of the platform or an application also carries `@HiddenFromObjC`, so Swift sees what it saw while it was
  `internal` (a JVM or JavaScript set has no such annotation).
- **(f) The composition holds only its root** (inbox #1048, #1215; ADR 0156 entries 67–76). `:composition`'s main sources
  are `AppComposition.kt` alone — the web's `applications.ts`, which only installs each application's part into the
  platform's registry. What reads several applications is the platform's behind a hook or an interface an application
  implements (the shell's model `navigation/ShellModel`, the drop's `PageIntake` and `blob/LevelUploads`), and each
  application's pages over the shell are its own extensions in its module (`<application>/ShellPages.kt`). Any other main
  file of `:composition` is red, whatever it names; so is a composition without its root.

**The defect that bought it.** None: built with the core's split (ADR 0155 decision 7.1), which replaced
`validate_mobile_ui_direction.py`'s import rules over one module and its allow-list of 18 files that named
applications from the platform. With the modules, the compiler refuses any import the graph does not carry; this guard
holds the graph.

**Its limit.** It reads text, as the backend's does: an `include` built by code, a `projectDir` re-pointed, a project
dependency written another way than `project(":x")` (a variable, a type-safe accessor) or a source set's dependencies
declared outside a `<set>.dependencies { }` block is not modelled — the last reads as a main edge (red, never a false
green); the others are invisible. (d) reads the module test of the convention by its spelled paths, not Gradle's
evaluation; (e) reads the annotation lines before a declaration, not the compiler's view (an annotation in a block
comment is masked; one carried by a type alias is not seen). (f) reads file names alone: what the root itself
names is the compiler's and (b)'s to hold, and an application's import in the platform is refused by the build (`:core`
has no edge to an application, rule (b)), not by a text rule here (`validate_guard_gaps.py`'s retired 38 and 47).
"""
from __future__ import annotations

import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import repo_layout  # noqa: E402
from kotlin_lexer import mask  # noqa: E402

MOBILE = repo_layout.MOBILE
PKG = "me.raddatz.veyq.mobile"
APPLICATIONS = repo_layout.MOBILE_CORE / "src/commonMain/kotlin/me/raddatz/veyq/mobile/navigation/Applications.kt"
ENTRY = re.compile(r"^\s*([A-Z][A-Z0-9_]*)\s*\(\s*\"([a-z][a-z0-9]*)\"\s*\)\s*,", re.MULTILINE)
EDGE = re.compile(r'\b(implementation|api|compileOnly|runtimeOnly|export)\s*\(\s*project\s*\(\s*"(:[\w:-]+)"\s*\)\s*\)')
DEPS_BLOCK = re.compile(r'(?:\b(\w+)|getByName\s*\(\s*"(\w+)"\s*\))\s*\.\s*dependencies\s*\{')
OPT_IN_BUILD = re.compile(r'optIn\s*\(\s*"me\.raddatz\.veyq\.mobile\.PlatformApi"\s*\)')
OPT_IN_SOURCE = re.compile(r"@(?:file\s*:\s*)?OptIn\s*\([^)]*\bPlatformApi\b")
PACKAGE = re.compile(r"^\s*package\s+([\w.]+)", re.MULTILINE)
CLIENTS = (":bridge", ":android")


def rel(p: pathlib.Path) -> str:
    return repo_layout.rel(p)


def applications() -> list[str]:
    src = APPLICATIONS.read_text(encoding="utf-8") if APPLICATIONS.is_file() else ""
    m = re.search(r"enum class ApplicationId\b[^{]*\{(.*?)\n\s*;", src, re.DOTALL)
    return [] if m is None else [e.group(2) for e in ENTRY.finditer(m.group(1))]


def kind(project: str) -> str:
    if project.startswith(":apps:"):
        return "application"
    return {":core": "platform", ":composition": "composition", ":testing": "testing"}.get(project, "client" if project in CLIENTS else "unknown")


def module_dirs() -> set[str]:
    """Every directory below src/mobile/ holding a build.gradle.kts, as a project path (build output aside)."""
    out = set()
    for f in MOBILE.rglob("build.gradle.kts"):
        parts = f.relative_to(MOBILE).parts[:-1]
        if not parts or any(p in ("build", ".gradle", "node_modules") for p in parts):
            continue
        out.add(":" + ":".join(parts))
    return out


def edges(project: str) -> list[tuple[str, str, bool, int]]:
    """(configuration, target, is_test, line) of every project edge in [project]'s build file."""
    build = repo_layout.mobile_project_dir(project) / "build.gradle.kts"
    if not build.is_file():
        return []
    raw = build.read_text(encoding="utf-8")
    # matched on the text as written (a project's path is a string literal), kept only where the masked text — comments
    # and literals blanked, offsets kept — still holds the code: a commented-out edge is no edge
    text, code = raw, mask(raw)
    blocks: list[tuple[int, int, str]] = []
    for m in DEPS_BLOCK.finditer(text):
        if code[m.start():m.start() + 3] != text[m.start():m.start() + 3]:
            continue
        name = m.group(1) or m.group(2)
        depth, i = 1, m.end()
        while i < len(text) and depth:
            depth += {"{": 1, "}": -1}.get(text[i], 0)
            i += 1
        blocks.append((m.start(), i, name))
    out = []
    for m in EDGE.finditer(text):
        if code[m.start():m.start() + len(m.group(1))] != m.group(1):
            continue
        inner = [b for b in blocks if b[0] <= m.start() < b[1]]
        is_test = bool(inner) and max(inner, key=lambda b: b[0])[2].endswith("Test")
        out.append((m.group(1), m.group(2), is_test, text.count("\n", 0, m.start()) + 1))
    return out


def rule_a(projects: list[str], apps: list[str]) -> list[str]:
    errors = []
    dirs = module_dirs()
    for d in sorted(dirs - set(projects)):
        errors.append(f"(a) {rel(repo_layout.mobile_project_dir(d))} has a build file the settings do not include — compiled by nothing")
    for p in projects:
        if p not in dirs:
            errors.append(f"(a) {rel(repo_layout.MOBILE_SETTINGS)} includes {p}, which has no build file — an empty project")
        if kind(p) == "unknown":
            errors.append(f"(a) {p} is none of the kinds the direction is defined for (:core, :apps:<application>, "
                          f":composition, :testing, a client {', '.join(CLIENTS)})")
    modules = {p.removeprefix(":apps:") for p in projects if p.startswith(":apps:")}
    if not apps:
        errors.append(f"(a) {rel(APPLICATIONS)}: no ApplicationId entry read — the rule would check nothing")
    for a in sorted(set(apps) - modules):
        errors.append(f"(a) ApplicationId names `{a}`, which has no module :apps:{a} — its code has no home")
    for a in sorted(modules - set(apps)):
        errors.append(f"(a) :apps:{a} is no ApplicationId's — no registry reaches it")
    return errors


def rule_b(projects: list[str]) -> list[str]:
    errors = []
    apps = {p for p in projects if p.startswith(":apps:")}
    allowed_main = {"platform": set(), "application": {":core"}, "composition": {":core"} | apps,
                    "testing": {":core"}, "client": {":composition"}}
    allowed_test = {"platform": {":testing"}, "application": {":core", ":testing"}, "composition": {":core", ":testing"} | apps,
                    "testing": {":core"}, "client": {":composition", ":testing"}}
    why = {
        "platform": "the platform names no other module: an application uses it, never the reverse",
        "application": "an application uses the platform alone — never another application, never the composition",
        "composition": "the composition reaches the platform and the applications",
        "testing": "the shared test support reaches the platform alone",
        "client": "a client reaches the composition (and, in its tests, the test support)",
    }
    for p in projects:
        k = kind(p)
        if k == "unknown":
            continue
        build = rel(repo_layout.mobile_project_dir(p) / "build.gradle.kts")
        seen_api, seen_export = set(), set()
        for conf, target, is_test, line in edges(p):
            ok = allowed_test[k] if is_test else allowed_main[k]
            if target not in ok:
                errors.append(f"(b) {build}:{line}: {p} → {target} ({conf}{', a test edge' if is_test else ''}) — {why[k]}")
            if k == "composition" and not is_test:
                (seen_api if conf == "api" else seen_export if conf == "export" else set()).add(target)
        if k == "composition":
            for target in sorted(({":core"} | apps) - seen_api):
                errors.append(f"(b) {build}: :composition does not `api` {target} — a client would not see it")
            for target in sorted(({":core"} | apps) - seen_export):
                errors.append(f"(b) {build}: :composition does not `export` {target} into VeyqCore — Swift would not see it")
    return errors


def main_sources(project: str) -> list[pathlib.Path]:
    src = repo_layout.mobile_project_dir(project) / "src"
    return sorted(f for s in src.glob("*Main") for f in (s / "kotlin").rglob("*.kt")) if src.is_dir() else []


def rule_c(projects: list[str], apps: list[str]) -> list[str]:
    errors = []
    owner: dict[str, str] = {}
    app_pkg = re.compile(r"^" + re.escape(PKG) + r"\.(?:api\.)?(" + "|".join(map(re.escape, apps)) + r")(?:\.|$)") if apps else None
    comp_pkg = re.compile(r"^" + re.escape(PKG) + r"\.composition(?:\.|$)")
    for p in projects:
        k = kind(p)
        if k not in ("platform", "application", "composition"):
            continue
        files = main_sources(p)
        if not files:
            errors.append(f"(c) {p}: no Kotlin file under src/*Main/kotlin — the rule would read nothing of it")
        for f in files:
            m = PACKAGE.search(mask(f.read_text(encoding="utf-8")))
            pkg = re.sub(r"\s+", "", m.group(1)) if m else ""
            expected = ".".join(f.relative_to(f.parents[len(f.relative_to(repo_layout.mobile_project_dir(p) / "src").parts) - 3]).parent.parts)
            if pkg != expected:
                errors.append(f"(c) {rel(f)}: declares package `{pkg}`, its directory is `{expected}` — a package is its directory's")
            a = app_pkg.match(pkg) if app_pkg else None
            if k == "application" and (a is None or a.group(1) != p.removeprefix(":apps:")):
                errors.append(f"(c) {rel(f)}: package `{pkg}` in {p} — an application's code is in its own packages")
            if k != "application" and a is not None:
                errors.append(f"(c) {rel(f)}: package `{pkg}` is the application `{a.group(1)}`'s, in {p} — it lives in :apps:{a.group(1)}")
            if (k == "composition") != bool(comp_pkg.match(pkg)):
                errors.append(f"(c) {rel(f)}: package `{pkg}` in {p} — `{PKG}.composition` is the composition's, and only its")
            prev = owner.setdefault(pkg, p)
            if prev != p:
                errors.append(f"(c) {rel(f)}: package `{pkg}` is declared by {prev} and {p} — a package lives in one module")
    return errors


def in_code(pattern: re.Pattern[str], raw: str) -> bool:
    """Whether [pattern] matches [raw] where it is code: matched on the text as written (the marker's name is a string
    literal), kept only where the masked text still holds the match's first characters — a comment is no opt-in."""
    code = mask(raw)
    return any(code[m.start():m.start() + 4] == raw[m.start():m.start() + 4] for m in pattern.finditer(raw))


def rule_d(projects: list[str]) -> list[str]:
    errors = []
    core_kinds = ("platform", "application", "composition", "testing")
    root = MOBILE / "build.gradle.kts"
    root_text = root.read_text(encoding="utf-8")
    if in_code(OPT_IN_BUILD, root_text):
        m = re.search(r"fun\s+Project\.isCoreModule\s*\(\s*\)\s*=\s*([^\n]*)", root_text)
        named = set(re.findall(r'"(:[\w:-]*)"', m.group(1))) if m else set()
        if m is None or named - {":core", ":composition", ":testing", ":apps:"} or not named:
            errors.append(f"(d) {rel(root)} opts into PlatformApi for modules its `isCoreModule()` does not hold to :core, "
                          f":composition, :testing and :apps: (it names {sorted(named) or 'nothing'})")
    for p in projects:
        d = repo_layout.mobile_project_dir(p)
        if kind(p) in core_kinds:
            continue
        build = d / "build.gradle.kts"
        if build.is_file() and in_code(OPT_IN_BUILD, build.read_text(encoding="utf-8")):
            errors.append(f"(d) {rel(build)}: {p} opts into PlatformApi — only the core's modules do; a client sees what it saw "
                          f"while the members were internal")
        for f in sorted(d.rglob("*.kt")):
            if "build" in f.relative_to(d).parts:
                continue
            if in_code(OPT_IN_SOURCE, f.read_text(encoding="utf-8")):
                errors.append(f"(d) {rel(f)}: opts into PlatformApi in {p} — only the core's modules do")
    return errors


def rule_e(projects: list[str]) -> list[str]:
    errors = []
    for p in projects:
        if kind(p) not in ("platform", "application", "composition"):
            continue
        for f in main_sources(p):
            if f.relative_to(repo_layout.mobile_project_dir(p) / "src").parts[0].startswith(("jvm", "js")):
                continue
            lines = mask(f.read_text(encoding="utf-8")).split("\n")
            for n, line in enumerate(lines):
                if "@PlatformApi" not in line or "@PlatformApi constructor" in line:
                    continue
                # the annotation run the marker stands in: its own line and the annotation-only lines around it
                run = [line]
                k = n - 1
                while k >= 0 and lines[k].strip().startswith("@"):
                    run.append(lines[k]); k -= 1
                k = n + 1
                while k < len(lines) and lines[k].strip().startswith("@"):
                    run.append(lines[k]); k += 1
                if not any("@HiddenFromObjC" in r for r in run):
                    errors.append(f"(e) {rel(f)}:{n + 1}: a @PlatformApi declaration without @HiddenFromObjC — Swift would see "
                                  f"what it did not while it was internal")
    return errors


# The composition's one main file (rule f), by `<source set>/<file name>`.
COMPOSITION_ROOT = "commonMain/AppComposition.kt"


def rule_f(projects: list[str]) -> list[str]:
    """(f) The composition holds only its root: every main source file of `:composition` but COMPOSITION_ROOT is red, and so
    is a composition without it."""
    if ":composition" not in projects:
        return []
    errors = []
    src = repo_layout.mobile_project_dir(":composition") / "src"
    seen = set()
    for f in main_sources(":composition"):
        key = f"{f.relative_to(src).parts[0]}/{f.name}"
        seen.add(key)
        if key != COMPOSITION_ROOT:
            errors.append(f"(f) {rel(f)}: the composition holds its root alone ({COMPOSITION_ROOT}) — what reads several "
                          f"applications is the platform's behind a hook or an interface an application implements, an "
                          f"application's part (its pages over ShellModel among them) lives in its module")
    if COMPOSITION_ROOT not in seen:
        errors.append(f"(f) :composition holds no {COMPOSITION_ROOT} — the root that installs every application's part is gone")
    return errors


def main() -> int:
    projects = repo_layout.mobile_projects()
    if not projects:
        print(f"MOBILE MODULE GRAPH VALIDATION FAILED:\n  - {rel(repo_layout.MOBILE_SETTINGS)} includes no project — the rules read nothing.")
        return 1
    apps = applications()
    errors = rule_a(projects, apps) + rule_b(projects) + rule_c(projects, apps) + rule_d(projects) + rule_e(projects) + rule_f(projects)
    if errors:
        print("MOBILE MODULE GRAPH VALIDATION FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1
    count = sum(len(edges(p)) for p in projects)
    print(f"validate_mobile_module_graph: OK ({len(projects)} projects: {', '.join(projects)}; {count} project edge(s), "
          f"none against the direction; the applications {', '.join(apps)} each their module, every package in one module, "
          f"PlatformApi opted into by the core's modules alone and hidden from Objective-C)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
