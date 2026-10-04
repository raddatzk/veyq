#!/usr/bin/env python3
"""Guard the backend's Gradle module layout where Gradle and Quarkus would let it go wrong without an error (ADR 0079,
cr-platform-gradle-modules). Buildless: it reads the build files, the config files and the Dockerfile, never a build.

**What it asserts.** Rules carry the letters the change-request gave them; the missing ones join with the steps that
create their subjects. The modules are the projects `settings.gradle.kts` includes (`:apps:x` is `backend/apps/x`).
The project edges are read from the build files (`<configuration>(project(":x"))`): `implementation`/`api`/
`runtimeOnly` are runtime edges, `testImplementation`/`testRuntimeOnly` test edges, `compileOnly` none. A module the
root reaches over runtime edges **ships** (its jar is in the fast-jar's `quarkus-app/lib/main`); `:test-support` is
reached over test edges only and ships nothing.

- **(c) Every module is indexed.** Each included project has `src/main/resources/META-INF/beans.xml`. Without it
  Quarkus does not index the jar: injected beans fail the build, but an `@All` list silently loses the module's
  implementations and its generated `@Path` interfaces answer 404 (spike, ADR 0079's change-request).
- **(e) The image builds every module.** The Dockerfile's `backend-build-base` stage copies each included project's
  `build.gradle.kts` — its warm-up `gradlew help` configures every included project and dies on one with no
  directory (measured: "Configuring project ':core' without an existing directory is not allowed") — and some stage
  copies the `src` of every module that ships. Nothing in `./gradlew check` would show either gap; only the release
  build would.
- **(f) Every module jar is named.** `gradle/module.gradle.kts` sets `group = "com.veyq"` and `archivesName`
  `veyq-<module>`, every module applies it, and no module's build file sets a group or an archivesName of its own.
  Unnamed, the jar the root's fast-jar carries in `quarkus-app/lib/main` is `<module>-unspecified.jar`, a name a
  third-party `core` artifact can share.
- **(g) One home per config key, per classpath.** The config files are every `application*.properties` in a
  `src/{main,testFixtures,test}/resources` under `backend/` (found by `backend_tree`, build output excluded). They
  are judged per **classpath**, the way SmallRye Config meets them: the *main* classpath is the main resources of the
  root and every module that ships (what the fast-jar reads); each project with tests has a *test* classpath of its
  own — the main resources of itself and of everything it reaches (runtime edges, and its test edges' runtime
  closure) plus its own `src/test/resources`. Test resources ship in no jar and two projects' test classpaths are
  separate JVMs, so the same `%test` key in two modules' `application-test.properties` is two classpaths, not a
  conflict. On every classpath:
  (g1) no `(profile, key)` pair is defined in two files — a key of `application.properties` is profile `""` unless
  written `%<profile>.<key>`, a key of `application-<p>.properties` is profile `p`. SmallRye Config loads **every**
  `application.properties` on the classpath as a source at the same ordinal (250), so one key in two of them has no
  defined winner; a profile file overriding a base key is a different pair and the intended overlay — which is how
  a module's `application-test.properties` overrides the base on purpose. On a test classpath this is the rule that
  a test config redefines nothing its classpath's main resources define, except as a `%test` override.
  (g2) Exactly one `application.properties` holds keys — the base, every profile's ground truth, which ships. Every
  other one is an *anchor*: comment-only, there to make the profile files beside it discoverable, and a key in it is
  red — a key in a test anchor is a second base at the base's ordinal.
  (g3) Every `application-<p>.properties` has an `application.properties` in the same directory. SmallRye finds a
  profile file only as the sibling of a discovered `application.properties`; one without that sibling is **silently
  never read** — the `%dev`/`%e2e`/`%prod` (or `%test`) keys would vanish with no error anywhere.
  (g4) No config file sits in a `src/testFixtures/resources`: a `java-test-fixtures` output is not part of a
  `@QuarkusTest`'s application (measured under Quarkus 3.36.0), so a file there is silently never read by the tests
  it was written for.

**The defect that bought it.** None yet: built with cr-platform-gradle-modules step 6, which moved the base file into
`:core`, left the root's profile files beside a comment-only anchor and gave each module with `@QuarkusTest`s its own
`%test` file beside its own anchor. Every failure mode it names is silent at runtime — an ambiguous key is resolved
by classpath order, a profile file with no anchor or in a test-fixtures output is skipped, an unindexed module
shrinks an `@All` list — and each was measured in this tree before the layout relied on it.

**Its limit.** It reads text: an `include` built by code, a `projectDir` re-pointed in the settings, a project
dependency written another way than `project(":x")` (a version catalog accessor, a variable), or a Dockerfile `COPY`
of a whole parent directory is not modelled — the first three make a module or an edge invisible to the rules (an
invisible edge shrinks a classpath, so a conflict through it goes unseen), the last reads as a missing copy (red,
never a false green). (e) does not know which other files a module's build reads at configuration time (`:core`'s
`pitest.scope.json`); the warm-up run in a directory holding only the copied files is the proof for those. (c) checks
the file exists, not that it is the module's own index (a `beans.xml` with `bean-discovery-mode="none"` passes). For
(g), it reads files, not the classpath: a config source declared another way (`quarkus.config.locations`, a
`META-INF/microprofile-config.properties`, an environment variable, a `@TestProfile`'s overrides, the chart's mounted
file) is not seen, and a key's *value* is never compared — only where it is defined. Keys are parsed as
`.properties` keys (continuation lines joined, `=`/`:`/whitespace separators); an escaped separator inside a key is
not modelled.
"""
from __future__ import annotations

import pathlib
import re
import sys
from collections import defaultdict

import backend_tree  # where the backend's sources live — resolved by glob, never spelled here

ROOT = pathlib.Path(__file__).resolve().parent.parent
BACKEND = backend_tree.BACKEND
SETTINGS = BACKEND / "settings.gradle.kts"
MODULE_SCRIPT = BACKEND / "gradle" / "module.gradle.kts"
DOCKERFILE = ROOT / "Dockerfile"
BUILD_BASE_STAGE = "backend-build-base"
BASE_NAME = "application.properties"
PROFILE_FILE = re.compile(r"^application-([\w.-]+)\.properties$")
PROFILED_KEY = re.compile(r"^%([^.]+)\.(.+)$")
# `implementation(project(":core"))` — the configuration and the project path; `testFixtures(project(…))` is none.
PROJECT_EDGE = re.compile(r'\b(\w+)\s*\(\s*project\s*\(\s*"(:[^"]*)"\s*\)\s*\)')
RUNTIME_CONFIGS = {"implementation", "api", "runtimeOnly"}
TEST_CONFIGS = {"testImplementation", "testRuntimeOnly"}


def rel(path: pathlib.Path) -> str:
    try:
        return str(path.resolve().relative_to(ROOT))
    except ValueError:
        return str(path)


def keys_of(path: pathlib.Path) -> list[str]:
    """The keys a `.properties` file defines, in order; comments, blank lines and continuations skipped."""
    keys: list[str] = []
    continued = False
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        was_continued, continued = continued, line.endswith("\\") and not line.endswith("\\\\")
        if was_continued or not line or line[0] in "#!":
            continue
        keys.append(re.split(r"\s*[=:\s]", line, maxsplit=1)[0])
    return keys


def config_files() -> list[pathlib.Path]:
    return sorted(
        p for r in backend_tree.resource_roots() for p in r.glob("application*.properties")
        if p.name == BASE_NAME or PROFILE_FILE.match(p.name)
    )


# --- the project graph --------------------------------------------------------------------------------------------

def build_file(project: str) -> pathlib.Path:
    return backend_tree.gradle_projects()[project] / "build.gradle.kts"


def edges(project: str) -> tuple[set[str], set[str]]:
    """(runtime edges, test edges) of `project`, read from its build file (comments stripped)."""
    f = build_file(project)
    text = re.sub(r"//[^\n]*", "", f.read_text(encoding="utf-8")) if f.is_file() else ""
    runtime, test = set(), set()
    for config, target in PROJECT_EDGE.findall(text):
        target = ":" + target.strip(":")
        if config in RUNTIME_CONFIGS:
            runtime.add(target)
        elif config in TEST_CONFIGS:
            test.add(target)
    return runtime, test


def runtime_closure(start: set[str]) -> set[str]:
    seen: set[str] = set()
    todo = list(start)
    while todo:
        p = todo.pop()
        if p in seen:
            continue
        seen.add(p)
        if p in backend_tree.gradle_projects():
            todo.extend(edges(p)[0])
    return seen


def source_set_of(f: pathlib.Path) -> str:
    """`main`, `test` or `testFixtures` — the `src/<set>/resources` the file sits in."""
    parts = f.resolve().parts
    i = len(parts) - 1 - parts[::-1].index("resources")
    return parts[i - 1]


def classpaths(files: list[pathlib.Path]) -> dict[str, list[pathlib.Path]]:
    """`{name: config files}` — the main classpath and one test classpath per project with a `src/test` tree."""
    projects = backend_tree.gradle_projects()
    owner = {f: backend_tree.project_of(f) for f in files}
    main_of: dict[str, list[pathlib.Path]] = defaultdict(list)
    test_of: dict[str, list[pathlib.Path]] = defaultdict(list)
    for f in files:
        if source_set_of(f) == "main":
            main_of[owner[f]].append(f)
        elif source_set_of(f) == "test":
            test_of[owner[f]].append(f)
    shipped = runtime_closure({":"})
    out = {"main": sorted(f for p in shipped for f in main_of.get(p, []))}
    for p, d in sorted(projects.items()):
        if not (d / "src" / "test").is_dir():
            continue
        runtime, test = edges(p)
        reach = runtime_closure({p}) | runtime_closure(test)
        out[f"test of {p}"] = sorted([f for q in reach for f in main_of.get(q, [])] + test_of.get(p, []))
    return out


# --- the rules ----------------------------------------------------------------------------------------------------

def rule_g(files: list[pathlib.Path]) -> list[str]:
    errors: list[str] = []
    for f in files:
        if PROFILE_FILE.match(f.name) and not (f.parent / BASE_NAME).is_file():
            errors.append(f"(g3) {rel(f)} has no {BASE_NAME} beside it: SmallRye finds a profile file only next to a "
                          f"discovered {BASE_NAME}, so its keys are silently never read. Add a comment-only anchor.")
        if source_set_of(f) == "testFixtures":
            errors.append(f"(g4) {rel(f)} is in a test-fixtures output, which is not part of a @QuarkusTest's "
                          f"application: it is never read. A module's %test keys go beside its tests, in src/test/resources.")
    for name, cp in classpaths(files).items():
        homes: dict[tuple[str, str], list[pathlib.Path]] = defaultdict(list)
        keyed_bases = []
        for f in cp:
            m = PROFILE_FILE.match(f.name)
            keys = keys_of(f)
            if m is None and keys:
                keyed_bases.append(f)
            for k in keys:
                pk = PROFILED_KEY.match(k)
                profile, key = (pk.group(1), pk.group(2)) if pk else (m.group(1) if m else "", k)
                homes[(profile, key)].append(f)
        for (profile, key), where in sorted(homes.items()):
            if len(where) > 1:
                errors.append(f"(g1) on the {name} classpath, ({profile!r}, {key!r}) is defined in {len(where)} files: "
                              f"{', '.join(rel(p) for p in where)} — two sources at one ordinal have no defined winner.")
        if not keyed_bases:
            errors.append(f"(g2) no {BASE_NAME} on the {name} classpath holds a key — the base config (every "
                          f"profile's ground truth) was not found; a moved tree must be re-pointed, not passed.")
        elif len(keyed_bases) > 1:
            errors.append(f"(g2) {len(keyed_bases)} {BASE_NAME} files on the {name} classpath hold keys: "
                          f"{', '.join(rel(p) for p in keyed_bases)} — one is the base, every other is a comment-only "
                          f"anchor for the profile files beside it.")
    return errors


def modules() -> list[str]:
    """The projects `settings.gradle.kts` includes, as directories below `backend/` (`:apps:x` → `apps/x`)."""
    return [d.relative_to(BACKEND).as_posix() for p, d in backend_tree.gradle_projects().items() if p != ":"]


def shipped_modules() -> set[str]:
    projects = backend_tree.gradle_projects()
    return {projects[p].relative_to(BACKEND).as_posix() for p in runtime_closure({":"}) if p != ":" and p in projects}


def rule_c(mods: list[str]) -> list[str]:
    return [f"(c) backend/{mod}/src/main/resources/META-INF/beans.xml is missing: Quarkus does not index the module, so "
            f"its injected beans fail the build and — silently — an @All list loses its implementations and its "
            f"@Path interfaces answer 404."
            for mod in mods if not (BACKEND / mod / "src" / "main" / "resources" / "META-INF" / "beans.xml").is_file()]


def rule_e(mods: list[str]) -> list[str]:
    """Every included module's build file is copied into the Dockerfile's `backend-build-base` stage, and the
    sources of every module that ships into a later stage."""
    stages: dict[str, list[str]] = defaultdict(list)
    stage = ""
    for line in DOCKERFILE.read_text(encoding="utf-8").splitlines():
        m = re.match(r"\s*FROM\s+\S+(?:\s+AS\s+(\S+))?", line, re.IGNORECASE)
        if m:
            stage = m.group(1) or ""
            continue
        c = re.match(r"\s*COPY\s+(.*)$", line, re.IGNORECASE)
        if c and "--from=" not in c.group(1):
            stages[stage] += c.group(1).split()[:-1]
    errors = []
    base = set(stages.get(BUILD_BASE_STAGE, []))
    every = {s for srcs in stages.values() for s in srcs}
    shipped = shipped_modules()
    if not base:
        errors.append(f"(e) the Dockerfile has no `{BUILD_BASE_STAGE}` stage copying a file — the module check reads nothing")
    for mod in mods:
        if f"backend/{mod}/build.gradle.kts" not in base:
            errors.append(f"(e) the Dockerfile's `{BUILD_BASE_STAGE}` stage does not copy backend/{mod}/build.gradle.kts: "
                          f"its warm-up `gradlew help` fails configuring an included project with no directory.")
        if mod in shipped and f"backend/{mod}/src" not in every:
            errors.append(f"(e) the Dockerfile copies no backend/{mod}/src: the image's build would compile the "
                          f"module without its sources.")
    return errors


def rule_f(mods: list[str]) -> list[str]:
    """The module jars are named once, in the convention script, and no module overrides the name."""
    errors = []
    convention = MODULE_SCRIPT.read_text(encoding="utf-8") if MODULE_SCRIPT.is_file() else ""
    if not re.search(r'^group\s*=\s*"com\.veyq"', convention, re.MULTILINE):
        errors.append(f"(f) {rel(MODULE_SCRIPT)} sets no `group = \"com.veyq\"` — a module jar would carry no group.")
    if not re.search(r'archivesName\.set\("veyq-\$\{project\.name\}"\)', convention):
        errors.append(f"(f) {rel(MODULE_SCRIPT)} sets no `archivesName` `veyq-<module>` — the jar in "
                      f"quarkus-app/lib/main would be `<module>-unspecified.jar`, a name a third-party artifact can share.")
    for mod in mods:
        build = BACKEND / mod / "build.gradle.kts"
        text = build.read_text(encoding="utf-8") if build.is_file() else ""
        if not text:
            errors.append(f"(f) backend/{mod}/build.gradle.kts is missing for an included project")
            continue
        if not re.search(r'apply\s*\(\s*from\s*=\s*"[./]*gradle/module\.gradle\.kts"\s*\)', text):
            errors.append(f"(f) backend/{mod}/build.gradle.kts does not apply gradle/module.gradle.kts — its jar is unnamed")
        if re.search(r"archivesName|^\s*group\s*=", text, re.MULTILINE):
            errors.append(f"(f) backend/{mod}/build.gradle.kts overrides the group or archivesName the convention sets")
    return errors


def main() -> int:
    files = config_files()
    if not files:
        print(f"BACKEND MODULE GRAPH VALIDATION FAILED:\n  - no {BASE_NAME} or profile file under "
              f"{rel(backend_tree.BACKEND)}/**/src/*/resources — a moved tree must be re-pointed, not passed.")
        return 1
    mods = modules()
    if not mods:
        print(f"BACKEND MODULE GRAPH VALIDATION FAILED:\n  - {rel(SETTINGS)} includes no module — the module rules read nothing.")
        return 1
    errors = rule_c(mods) + rule_e(mods) + rule_f(mods) + rule_g(files)
    if errors:
        print("BACKEND MODULE GRAPH VALIDATION FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1
    cps = classpaths(files)
    print(f"OK: {len(mods)} module(s) ({', '.join(mods)}) indexed, copied by the Dockerfile and named by the "
          f"convention (in the fast-jar: {', '.join(sorted(shipped_modules()))}); {len(files)} config file(s) on {len(cps)} "
          f"classpath(s) ({', '.join(cps)}), on each every (profile, key) defined once, one base, every profile "
          f"file anchored.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
