#!/usr/bin/env python3
"""Guard the backend's Gradle module layout where Gradle and Quarkus would let it go wrong without an error (ADR 0079,
cr-platform-gradle-modules). Buildless: it reads the build files, the config files and the Dockerfile, never a build.

**What it asserts.** The rules carry the letters they were built under (a–g). The modules are the projects
`settings.gradle.kts` includes (`:apps:x` is `backend/apps/x`). The project edges are read from the build files
(`<configuration>(project(":x"))`): `implementation`/`api`/`runtimeOnly` are runtime edges,
`testImplementation`/`testRuntimeOnly` test edges, `compileOnly` neither (it is still an edge for rule (b)). A
module the root reaches over runtime edges **ships** (its jar is in the fast-jar's `quarkus-app/lib/main`);
`:test-support` is reached over test edges only and ships nothing.

- **(a) The settings name every module, and only the three kinds there are.** The included projects equal the
  directories below `backend/` holding a `build.gradle.kts` (build output excluded). A module directory the settings
  do not include is compiled by nothing — its classes, migrations and tests silently never run — and an include
  without a build file configures an empty project. Every included project is `:core` (the platform),
  `:test-support` (the test helpers) or `:apps:<name>` (an application, ADR 0079 Decision 1): rule (b)'s direction
  is defined for exactly those, so a fourth kind is red until the direction is decided for it.
- **(b) The dependency direction (ADR 0079 Decision 4).** Every project edge, any configuration: `:core` depends on
  no project except `:test-support` over a test configuration; an application depends on `:core` and, over a test
  configuration, on `:test-support` — **never on another application** and never on the root; `:test-support`
  depends on `:core` only (an edge to an application would put that application into `:core`'s tests, which must
  boot core alone); `:test-support` is reached over test configurations only, from anywhere. The root composes any
  module. Gradle refuses `:core` → application as a cycle, but nothing in Gradle refuses `:apps:a` → `:apps:b`.
- **(c) Every module is indexed.** Each included project has `src/main/resources/META-INF/beans.xml`. Without it
  Quarkus does not index the jar: injected beans fail the build, but an `@All` list silently loses the module's
  implementations and its generated `@Path` interfaces answer 404 (measured on the deliverable: the fast-jar boots
  and the Memories routes 404).
- **(d) No package spans two modules.** Every package a shipped project declares — its hand-written main Kotlin
  (the `package` lines) and its generated API (the `SpecSet` packages of `gradle/openapi.gradle.kts`, attributed to
  the project whose build file names that owner in `extra["veyq.openapi.owner"]`) — is declared by that project
  alone. Kotlin compiles a split package and Quarkus indexes both halves, so nothing fails; but the package no
  longer says its owner, and the platform/application boundary the names carry (`com.veyq.core.*`,
  `com.veyq.<application>.*`) is a lie for the half that sits elsewhere. `:test-support` is test code and declares
  its helpers inside core's packages on purpose (`com.veyq.core.blobstore.RecordingBlobStore`); it is not judged.
- **(e) The image builds every module.** The Dockerfile's `backend-build-base` stage copies each included project's
  `build.gradle.kts` — its warm-up `gradlew help` configures every included project and dies on one with no
  directory (measured: "Configuring project ':core' without an existing directory is not allowed") — and some stage
  copies the `src` of every module that ships. Nothing in `./gradlew check` would show either gap; only the release
  build would.
- **(f) Every module jar is named by its group, and never renamed.** `gradle/module.gradle.kts` sets
  `group = "com.veyq"`, every module applies it, no module's build file sets a group of its own, and no backend build
  file (the convention, the root's, a module's) sets an `archivesName`. Quarkus names a `quarkus-app/lib/main` entry
  `<group>.<project name>.jar` (`com.veyq.core.jar`, `com.veyq.memories.jar`); without the group a module's jar there
  would carry no namespace a third-party `core` artifact cannot share. An `archivesName` is red because it is silent:
  Quarkus cannot map a renamed project's `java-test-fixtures` jar, so `testImplementation(testFixtures(project(…)))`
  drops out of a `@QuarkusTest`'s application model with no error (measured 2026-10-05, Quarkus 3.36.0).
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
  (g4) No config file sits in a `src/testFixtures/resources`: one there was measured never read by the
  `@QuarkusTest`s it was written for (Quarkus 3.36.0) — while the module jars were renamed, which alone drops a
  fixtures jar out of the application (rule (f)). Whether Quarkus reads one without the rename is unmeasured (inbox
  #333), so the rule stays until it is.

**The defect that bought it.** None: built with cr-platform-gradle-modules (steps 6 and 8b), which split the
backend into `:core`, `:apps:memories` and `:test-support`. Every failure mode it names is silent — an ambiguous key
is resolved by classpath order, a profile file with no anchor or in a test-fixtures output is skipped, an unindexed
module shrinks an `@All` list and 404s its routes, an un-included module directory is compiled by nobody, an
application → application edge and a split package compile — and the runtime ones were measured in this tree
before the layout relied on them. Rule (b) is the only place the "never another application" half of ADR 0079
Decision 4 is checked at all; its bite is a forged second application, since no real one exists yet.

**Its limit.** It reads text: an `include` built by code, a `projectDir` re-pointed in the settings, a project
dependency written another way than `project(":x")` (a version catalog accessor, a variable,
`project(path = …)`), or a Dockerfile `COPY` of a whole parent directory is not modelled — the first three make a
module or an edge invisible to the rules (an invisible edge shrinks a classpath, so a conflict through it goes
unseen, and an app → app edge spelled that way passes (b)), the last reads as a missing copy (red, never a false
green). (b) judges declared edges, not imports: a `:core` file importing an application without an edge does not
compile, which is the compiler's check, not this one. (d) reads `package` lines (comments masked by the Kotlin
lexer) and the generator's `SpecSet` literals; a package derived another way is not seen. (e) does not know which other files a module's build reads at configuration time (`:core`'s
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
from kotlin_lexer import mask  # the one Kotlin lexer in scripts/: comments + literals blanked, offsets kept

ROOT = pathlib.Path(__file__).resolve().parent.parent
BACKEND = backend_tree.BACKEND
SETTINGS = BACKEND / "settings.gradle.kts"
MODULE_SCRIPT = BACKEND / "gradle" / "module.gradle.kts"
OPENAPI_SCRIPT = BACKEND / "gradle" / "openapi.gradle.kts"
CORE = ":core"
TEST_SUPPORT = ":test-support"
APPLICATION = re.compile(r"^:apps:[\w-]+$")
# Any project reference in a dependencies block, with the configuration around it — `testImplementation(
# testFixtures(project(":core")))` is a `testImplementation` edge, and `"api"(project(…))` (a script plugin's
# string-named configuration) is an `api` one.
ANY_EDGE = re.compile(r'"?\b(\w+)"?\s*\(\s*(?:\w+\s*\(\s*)?project\s*\(\s*"(:[^"]*)"\s*\)')
TEST_EDGE_CONFIGS = {"testImplementation", "testRuntimeOnly", "testCompileOnly"}
# `SpecSet(coreOwner, coreSpecs, "com.veyq.core.generated", …)` — the owner (a literal or a `val`) and the package root.
SPEC_SET = re.compile(r'\bSpecSet\(\s*("?)(\w+)\1\s*,\s*\w+\s*,\s*"([\w.]+)"')
OWNER_VAL = re.compile(r'\bval\s+(\w+)\s*=\s*"(\w+)"')
OPENAPI_OWNER = re.compile(r'extra\["veyq\.openapi\.owner"\]\s*=\s*"(\w+)"')
PACKAGE_LINE = re.compile(r"^\s*package\s+([\w.]+)", re.M)
# Any spelling of a renamed archive (`archivesName.set(…)`, `archivesName = …`, `base { archivesName… }`), read on
# comment-masked text so the convention's own warning against it is not what is found.
ARCHIVES_NAME = re.compile(r"\barchivesName\b|\barchivesBaseName\b")
ARCHIVES_NAME_WHY = ("Quarkus cannot map a renamed project's test-fixtures jar, so a `testFixtures(project(…))` "
                     "dependency silently drops out of a @QuarkusTest's application (measured 2026-10-05). The jar is "
                     "named by the group: `<group>.<project name>.jar` in quarkus-app/lib/main.")
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
            errors.append(f"(g4) {rel(f)} is in a test-fixtures output, which a @QuarkusTest is not known to read "
                          f"as a config source. A module's %test keys go beside its tests, in src/test/resources.")
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


def build_file_dirs() -> dict[str, pathlib.Path]:
    """`{project path: directory}` for every directory below `backend/` that holds a `build.gradle.kts`."""
    found = {}
    for f in BACKEND.glob("**/build.gradle.kts"):
        rel_parts = f.parent.relative_to(BACKEND).parts
        if backend_tree._SKIP & set(rel_parts):
            continue
        found[":" + ":".join(rel_parts)] = f.parent
    return found


def rule_a() -> list[str]:
    errors = []
    included = {p for p in backend_tree.gradle_projects() if p != ":"}
    on_disk = {p for p in build_file_dirs() if p != ":"}
    for p in sorted(on_disk - included):
        errors.append(f"(a) backend/{p.strip(':').replace(':', '/')}/build.gradle.kts belongs to no project "
                      f"{rel(SETTINGS)} includes: Gradle compiles nothing there — its classes, migrations and tests "
                      f"silently never run. Include it, or delete the directory.")
    for p in sorted(included - on_disk):
        errors.append(f"(a) {rel(SETTINGS)} includes {p}, which has no build.gradle.kts — an empty project.")
    for p in sorted(included):
        if p not in (CORE, TEST_SUPPORT) and not APPLICATION.match(p):
            errors.append(f"(a) {p} is neither {CORE} (the platform), {TEST_SUPPORT} (the test helpers) nor "
                          f":apps:<name> (an application, ADR 0079) — rule (b) has no direction for it.")
    return errors


def all_edges(project: str) -> list[tuple[str, str]]:
    """Every `(configuration, target project)` edge `project`'s build file declares, comments stripped."""
    f = build_file(project)
    text = re.sub(r"//[^\n]*", "", f.read_text(encoding="utf-8")) if f.is_file() else ""
    return [(config, ":" + target.strip(":")) for config, target in ANY_EDGE.findall(text)]


def edge_refusal(src: str, config: str, dst: str, included: set[str]) -> str | None:
    """Why the edge `src` → `dst` over `config` breaks the direction, or None when it is allowed."""
    if dst not in included:
        return f"{dst} is no project {rel(SETTINGS)} includes"
    if dst == src:
        return "a project depending on itself"
    if dst == TEST_SUPPORT:
        return None if config in TEST_EDGE_CONFIGS else (
            f"{TEST_SUPPORT} is test code and reaches a project over a test configuration only "
            f"({', '.join(sorted(TEST_EDGE_CONFIGS))}), never over `{config}` — it would ship")
    if src == ":":
        return None  # the composition root composes every module
    if src == CORE:
        return f"{CORE} depends on no application and on nothing else (ADR 0079 Decision 4)"
    if src == TEST_SUPPORT:
        return None if dst == CORE else (
            f"{TEST_SUPPORT} reaches {CORE} only: an edge to {dst} puts it into {CORE}'s tests, which boot core alone")
    if APPLICATION.match(src):
        if dst == CORE:
            return None
        if APPLICATION.match(dst):
            return (f"an application never depends on another application (ADR 0079 Decision 4) — refer to its "
                    f"objects through a core object reference (a scope id and a node id)")
        return f"an application depends on {CORE} only"
    return f"{src} is no module kind rule (b) has a direction for (see rule (a))"


def rule_b() -> list[str]:
    projects = backend_tree.gradle_projects()
    included = set(projects)
    errors = []
    for src in sorted(projects):
        for config, dst in all_edges(src):
            why = edge_refusal(src, config, dst, included)
            if why:
                where = rel(build_file(src))
                errors.append(f"(b) {where}: `{config}(project(\"{dst}\"))` — {why}.")
    if not any(all_edges(p) for p in projects if p != ":"):
        errors.append(f"(b) no module's build file declares a project edge — the direction rule read nothing. "
                      f"If the edges are written another way now, teach ANY_EDGE that spelling.")
    return errors


def generated_packages() -> tuple[dict[str, set[str]], list[str]]:
    """`{project: generated packages}` from `gradle/openapi.gradle.kts`'s spec sets and each build file's owner."""
    text = re.sub(r"//[^\n]*", "", OPENAPI_SCRIPT.read_text(encoding="utf-8")) if OPENAPI_SCRIPT.is_file() else ""
    vals = dict(OWNER_VAL.findall(text))
    roots = {}
    for quoted, owner, root in SPEC_SET.findall(text):
        roots[owner if quoted else vals.get(owner, owner)] = root
    errors = []
    if not roots:
        errors.append(f"(d) {rel(OPENAPI_SCRIPT)} declares no `SpecSet(owner, specs, \"<package root>\", …)` — the "
                      f"generated packages are no longer read. If the generation moved, re-point this rule.")
    out: dict[str, set[str]] = defaultdict(set)
    for p in backend_tree.gradle_projects():
        f = build_file(p)
        text = re.sub(r"//[^\n]*", "", f.read_text(encoding="utf-8")) if f.is_file() else ""
        for owner in OPENAPI_OWNER.findall(text):
            if owner not in roots:
                errors.append(f"(d) {rel(f)} generates the `{owner}` spec set, which {rel(OPENAPI_SCRIPT)} does "
                              f"not declare (it knows {sorted(roots)}).")
                continue
            out[p] |= {roots[owner], roots[owner] + ".api", roots[owner] + ".model"}
    return out, errors


def rule_d() -> list[str]:
    declared, errors = generated_packages()
    homes: dict[str, set[str]] = defaultdict(set)
    for project, packages in declared.items():
        for pkg in packages:
            homes[pkg].add(project)
    for f in backend_tree.main_kotlin():
        m = PACKAGE_LINE.search(mask(f.read_text(encoding="utf-8")))
        if m:
            homes[m.group(1)].add(backend_tree.project_of(f) or f"<no project: {rel(f.parent)}>")
    if not homes:
        errors.append("(d) no main Kotlin file declares a package — the split-package rule read nothing.")
    for pkg, projects in sorted(homes.items()):
        if len(projects) > 1:
            errors.append(f"(d) package {pkg} is declared by {len(projects)} modules ({', '.join(sorted(projects))}) — "
                          f"a package belongs to one module, the one its name says (core's `com.veyq.core.*`, an "
                          f"application's `com.veyq.<application>.*`).")
    return errors


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
    for f in [MODULE_SCRIPT, BACKEND / "build.gradle.kts"]:
        if f.is_file() and ARCHIVES_NAME.search(mask(f.read_text(encoding="utf-8"))):
            errors.append(f"(f) {rel(f)} sets an `archivesName` — {ARCHIVES_NAME_WHY}")
    for mod in mods:
        build = BACKEND / mod / "build.gradle.kts"
        text = build.read_text(encoding="utf-8") if build.is_file() else ""
        if not text:
            errors.append(f"(f) backend/{mod}/build.gradle.kts is missing for an included project")
            continue
        if not re.search(r'apply\s*\(\s*from\s*=\s*"[./]*gradle/module\.gradle\.kts"\s*\)', text):
            errors.append(f"(f) backend/{mod}/build.gradle.kts does not apply gradle/module.gradle.kts — its jar is unnamed")
        if re.search(r"^\s*group\s*=", text, re.MULTILINE):
            errors.append(f"(f) backend/{mod}/build.gradle.kts overrides the group the convention sets")
        if ARCHIVES_NAME.search(mask(text)):
            errors.append(f"(f) backend/{mod}/build.gradle.kts sets an `archivesName` — {ARCHIVES_NAME_WHY}")
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
    errors = rule_a() + rule_b() + rule_c(mods) + rule_d() + rule_e(mods) + rule_f(mods) + rule_g(files)
    if errors:
        print("BACKEND MODULE GRAPH VALIDATION FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1
    cps = classpaths(files)
    edge_count = sum(len(all_edges(p)) for p in backend_tree.gradle_projects())
    print(f"OK: {len(mods)} module(s) ({', '.join(mods)}), each with a build file and included; {edge_count} project "
          f"edge(s), none against the direction (no application → application); no package in two modules; "
          f"indexed, copied by the Dockerfile and named by the "
          f"convention (in the fast-jar: {', '.join(sorted(shipped_modules()))}); {len(files)} config file(s) on {len(cps)} "
          f"classpath(s) ({', '.join(cps)}), on each every (profile, key) defined once, one base, every profile "
          f"file anchored.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
