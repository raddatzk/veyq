#!/usr/bin/env python3
"""Guard the backend's Gradle module layout where Gradle and Quarkus would let it go wrong without an error (ADR 0079,
cr-platform-gradle-modules). Buildless: it reads the build files, the config files and the Dockerfile, never a build.

**What it asserts.** Rules carry the letters the change-request gave them; the missing ones join with the steps that
create their subjects. The modules are the projects `settings.gradle.kts` includes (`:apps:x` is `backend/apps/x`).

- **(e) The image builds every module.** The Dockerfile's `backend-build-base` stage copies each module's
  `build.gradle.kts` — its warm-up `gradlew help` configures every included project and dies on one with no
  directory (measured: "Configuring project ':core' without an existing directory is not allowed") — and some stage
  copies the module's `src`. Nothing in `./gradlew check` would show either gap; only the release build would.
- **(f) Every module jar is named.** `gradle/module.gradle.kts` sets `group = "com.veyq"` and `archivesName`
  `veyq-<module>`, every module applies it, and no module's build file sets a group or an archivesName of its own.
  Unnamed, the jar the root's fast-jar carries in `quarkus-app/lib/main` is `<module>-unspecified.jar`, a name a
  third-party `core` artifact can share.
- **(g) One home per config key.** Over every `application*.properties` in a `src/{main,testFixtures,test}/resources`
  under `backend/` (found by `backend_tree`, build output excluded):
  (g1) no `(profile, key)` pair is defined in two files — a key of `application.properties` is profile `""` unless
  written `%<profile>.<key>`, a key of `application-<p>.properties` is profile `p`. SmallRye Config loads **every**
  `application.properties` on the classpath as a source at the same ordinal (250), so one key in two of them has no
  defined winner; a profile file overriding a base key is a different pair and the intended overlay.
  (g2) Exactly one `application.properties` holds keys — the base, every profile's ground truth. Every other one is
  an *anchor*: comment-only, there to make the profile files beside it discoverable, and a key in it is red.
  (g3) Every `application-<p>.properties` has an `application.properties` in the same directory. SmallRye finds a
  profile file only as the sibling of a discovered `application.properties`; one without that sibling is **silently
  never read** — the `%dev`/`%e2e`/`%prod` (or `%test`) keys would vanish with no error anywhere.

**The defect that bought it.** None yet: built with cr-platform-gradle-modules step 6, which moved the base file into
`:core` and left the root's profile files beside a comment-only anchor. Both failure modes it names are silent at
runtime — an ambiguous key is resolved by classpath order, a profile file with no anchor is skipped — and both were
read from SmallRye Config 3.17.2's sources and confirmed in this tree before the layout relied on them.

**Its limit.** (e) and (f) read text: an `include` built by code, a `projectDir` re-pointed in the settings, or a
Dockerfile `COPY` of a whole parent directory is not modelled — the first two make a module invisible to the rules,
the last reads as a missing copy (red, never a false green). (e) does not know which other files a module's build
reads at configuration time (`:core`'s `pitest.scope.json`); the warm-up run in a directory holding only the
copied files is the proof for those. For (g), it reads files, not the classpath: a config source declared another way (`quarkus.config.locations`,
a `META-INF/microprofile-config.properties`, an environment variable, the chart's mounted file) is not seen, and a
key's *value* is never compared — only where it is defined. Keys are parsed as `.properties` keys (continuation
lines joined, `=`/`:`/whitespace separators); an escaped separator inside a key is not modelled.
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


def rule_g(files: list[pathlib.Path]) -> list[str]:
    errors: list[str] = []
    homes: dict[tuple[str, str], list[pathlib.Path]] = defaultdict(list)
    keyed_bases = []
    for f in files:
        m = PROFILE_FILE.match(f.name)
        keys = keys_of(f)
        if m is None and keys:
            keyed_bases.append(f)
        if m is not None and not (f.parent / BASE_NAME).is_file():
            errors.append(f"(g3) {rel(f)} has no {BASE_NAME} beside it: SmallRye finds a profile file only next to a "
                          f"discovered {BASE_NAME}, so its keys are silently never read. Add a comment-only anchor.")
        for k in keys:
            pk = PROFILED_KEY.match(k)
            profile, key = (pk.group(1), pk.group(2)) if pk else (m.group(1) if m else "", k)
            homes[(profile, key)].append(f)
    for (profile, key), where in sorted(homes.items()):
        if len(where) > 1:
            errors.append(f"(g1) ({profile!r}, {key!r}) is defined in {len(where)} files: "
                          f"{', '.join(rel(p) for p in where)} — two sources at one ordinal have no defined winner.")
    if not keyed_bases:
        errors.append(f"(g2) no {BASE_NAME} under {rel(backend_tree.BACKEND)} holds a key — the base config "
                      f"(every profile's ground truth) was not found; a moved tree must be re-pointed, not passed.")
    elif len(keyed_bases) > 1:
        errors.append(f"(g2) {len(keyed_bases)} {BASE_NAME} files hold keys: {', '.join(rel(p) for p in keyed_bases)} "
                      f"— one is the base, every other is a comment-only anchor for the profile files beside it.")
    return errors


def modules() -> list[str]:
    """The projects `settings.gradle.kts` includes, as directories below `backend/` (`:apps:x` → `apps/x`)."""
    return [d.relative_to(BACKEND).as_posix() for p, d in backend_tree.gradle_projects().items() if p != ":"]


def rule_e(mods: list[str]) -> list[str]:
    """Every included module's build file is copied into the Dockerfile's `backend-build-base` stage, and its
    sources into a later stage."""
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
    if not base:
        errors.append(f"(e) the Dockerfile has no `{BUILD_BASE_STAGE}` stage copying a file — the module check reads nothing")
    for mod in mods:
        if f"backend/{mod}/build.gradle.kts" not in base:
            errors.append(f"(e) the Dockerfile's `{BUILD_BASE_STAGE}` stage does not copy backend/{mod}/build.gradle.kts: "
                          f"its warm-up `gradlew help` fails configuring an included project with no directory.")
        if (BACKEND / mod / "src").is_dir() and f"backend/{mod}/src" not in every:
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
    errors = rule_e(mods) + rule_f(mods) + rule_g(files)
    if errors:
        print("BACKEND MODULE GRAPH VALIDATION FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1
    print(f"OK: {len(mods)} module(s) ({', '.join(mods)}) copied by the Dockerfile and named by the convention; "
          f"{len(files)} config file(s), every (profile, key) defined once, one base, every profile file anchored.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
