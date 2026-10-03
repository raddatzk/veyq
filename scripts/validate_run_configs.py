#!/usr/bin/env python3
"""Validate that every IntelliJ run configuration in `.run/` still LOADS, and that its subject exists.

**What it asserts.** For each `.run/*.xml` (and there must be at least one):
  - the file is well-formed XML and carries a `<configuration>` with a non-empty `name` and `type` — the
    precondition for the IDE offering it at all;
  - per configuration *type*, derived from the `type` attribute rather than from a list of files:
    - **npm** (`js.build_tools.npm`) — the `package-json` it names exists, and when it runs a script
      (`<command value="run"/>`), that script is declared in that `package.json`'s `"scripts"`;
    - **Gradle** (`GradleRunConfiguration`) — `externalProjectPath` is a directory holding a Gradle build file;
    - **shell** (`ShConfigurationType`) — the working directory exists, and every file the script body passes
      to a `-f`/`--file` flag resolves against it.
  `$PROJECT_DIR$` resolves to the repo root, the way the IDE expands it.

**The defect that bought it.** On 2026-09-10 the comments in `Run Backend` and `Bootstrap DB + Garage` grew a
`docker compose` flag and a Gradle note written with a literal double hyphen — which XML forbids *inside a
comment*. Both files stopped being well-formed, IntelliJ's `RCInArbitraryFileManager` logged `Failed to parse
file` at project open, and it offered **neither** configuration: the backend and the dev-infra starter simply
were not in the dropdown. That went unnoticed for ten days and was found on 2026-09-20 by reading `idea.log`,
because nothing else says it — the IDE does not surface the warning, and the *other* three configurations kept
working, so the tree looked healthy. `.run/` had no guard of any kind; this is the first one.

Deliberately **not** "grep `.run/` for a double hyphen in a comment": that shape only ever catches the one
defect already seen, and the property that matters is the one the IDE actually needs — *it parses*. Parsing
catches the double hyphen, an unclosed tag and a bad entity alike, for free. The corpus is `.run/*.xml` only,
so — unlike a literal-scanning guard — this file is not part of what it scans.

**Its limit.** Green means a configuration LOADS and points at something real; it never means the
configuration *works*.
  - Nothing here runs anything: a Gradle **task name** (`quarkusDev`), a Quarkus profile, and every `<env>`
    value (store endpoint, `VEYQ_BLOB_STORE_PUBLIC_ENDPOINT`, a port) are unchecked. A configuration whose
    `PUBLIC_ENDPOINT` disagrees with the origin you browse is green here and broken in the browser.
  - Paths embedded in `<env>` values are deliberately **not** resolved: build outputs legitimately do not
    exist yet on a clean tree (a file the configured backend writes under `backend/build/`), so
    requiring them would make the guard red on arrival — red-because-wrong, which is indistinguishable from
    red-because-broken for every later reader.
  - It sees the files that exist, never the one that is **missing**: `.run/` carried no admin-surface
    configuration at all for as long as `admin-web/` has existed, and no assertion over the present tree can
    notice an absence nobody declared.
  - This is `xml.etree`, not IntelliJ's parser. It proves well-formedness (which is what bit), not that the
    IDE accepts every element — an option the IDE no longer understands parses fine here.
"""
import json
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
RUN_DIR = ROOT / ".run"

# A file the script body hands to a compose/`make`-style file flag, e.g. `docker compose -f infra/foo.yml`.
FILE_FLAG = re.compile(r"(?:^|\s)(?:-f|--file)[=\s]+(\S+)")

errors: list[str] = []


def project_path(value: str) -> pathlib.Path:
    """Expand `$PROJECT_DIR$` the way the IDE does, and resolve anything else against the repo root."""
    cleaned = value.strip().replace("$PROJECT_DIR$", str(ROOT))
    return pathlib.Path(cleaned) if pathlib.Path(cleaned).is_absolute() else ROOT / cleaned


def option(config: ET.Element, name: str) -> str:
    """The value of an `<option name="…" value="…"/>`, at any depth; empty string when absent."""
    for opt in config.iter("option"):
        if opt.get("name") == name:
            return opt.get("value") or ""
    return ""


def check_npm(label: str, config: ET.Element) -> None:
    pkg_ref = config.find("package-json")
    if pkg_ref is None or not (pkg_ref.get("value") or "").strip():
        errors.append(f"{label}: npm configuration names no package-json")
        return
    pkg = project_path(pkg_ref.get("value") or "")
    if not pkg.is_file():
        errors.append(f"{label}: package-json does not exist → {pkg_ref.get('value')}")
        return
    command = config.find("command")
    if command is None or (command.get("value") or "") != "run":
        return  # `install`, `ci`, … name no script of their own
    declared = json.loads(pkg.read_text()).get("scripts", {})
    named = [s.get("value") or "" for s in config.iter("script")]
    if not named:
        errors.append(f"{label}: npm `run` configuration names no script")
    for script in named:
        if script not in declared:
            errors.append(
                f"{label}: script \"{script}\" is not declared in {pkg.relative_to(ROOT)}"
            )


def check_gradle(label: str, config: ET.Element) -> None:
    raw = option(config, "externalProjectPath")
    if not raw:
        errors.append(f"{label}: Gradle configuration names no externalProjectPath")
        return
    path = project_path(raw)
    if not path.is_dir():
        errors.append(f"{label}: externalProjectPath is not a directory → {raw}")
        return
    if not any((path / name).is_file() for name in ("build.gradle.kts", "build.gradle", "settings.gradle.kts", "settings.gradle")):
        errors.append(f"{label}: externalProjectPath holds no Gradle build file → {raw}")


def check_shell(label: str, config: ET.Element) -> None:
    raw_cwd = option(config, "SCRIPT_WORKING_DIRECTORY")
    cwd = project_path(raw_cwd) if raw_cwd else ROOT
    if not cwd.is_dir():
        errors.append(f"{label}: SCRIPT_WORKING_DIRECTORY is not a directory → {raw_cwd}")
        return
    for ref in FILE_FLAG.findall(option(config, "SCRIPT_TEXT")):
        target = ref.strip("\"'")
        if not (cwd / target).exists():
            errors.append(f"{label}: script body points at a file that does not exist → {target}")


CHECKS = {
    "js.build_tools.npm": check_npm,
    "GradleRunConfiguration": check_gradle,
    "ShConfigurationType": check_shell,
}

configs = sorted(RUN_DIR.glob("*.xml")) if RUN_DIR.is_dir() else []
if not configs:
    print("RUN CONFIG VALIDATION FAILED:")
    print(f"  - {RUN_DIR.relative_to(ROOT)} holds no *.xml — the guard validated nothing")
    sys.exit(1)

checked = 0
for path in configs:
    label = str(path.relative_to(ROOT))
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as exc:
        # The defect that bought this guard lands here: IntelliJ drops such a file and says so only in idea.log.
        errors.append(f"{label}: not well-formed XML, so IntelliJ offers nothing from it → {exc}")
        continue
    found = root.findall(".//configuration")
    if not found:
        errors.append(f"{label}: holds no <configuration>")
        continue
    for config in found:
        if config.get("default") == "true":
            continue
        name, kind = config.get("name") or "", config.get("type") or ""
        if not name or not kind:
            errors.append(f"{label}: <configuration> without a name or a type")
            continue
        checked += 1
        check = CHECKS.get(kind)
        if check:
            check(f"{label} [{name}]", config)

if errors:
    print("RUN CONFIG VALIDATION FAILED:")
    for e in errors:
        print("  -", e)
    sys.exit(1)

print(f"run configs OK: {checked} configuration(s) in {len(configs)} file(s) parse and resolve")
