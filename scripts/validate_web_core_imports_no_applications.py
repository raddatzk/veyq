#!/usr/bin/env python3
"""Guard that the web core does not import, nor name the kinds of, the Memories application (ADR 0079, web phase).

**What it asserts.** The application is `web/src/features/memory/**` (the one constant `APPLICATION` below) **plus
every generated API client whose `generate:api` run reads a spec under `contracts/api/memories/`** (the constant
`APPLICATION_SPECS`); core is everything else under `web/src` — by exclusion, so a new directory is guarded the day
it appears. The client half is **derived**, never listed: each `openapi-generator-cli generate` step of
`web/package.json`'s `generate:api` script is read for its `-i` (spec) and `-o` (output directory), and an output
whose spec lies under `APPLICATION_SPECS` is the application's (today `api/generated/favorites` and
`api/generated/media`), so a new Memories client is covered the day its line lands. Core's own clients (`blob`,
`content`, …) stay core's: importing them is the point of generating them. Dependencies run application → core,
never back. Three rules, each read over every non-test `.ts`/`.vue` file of core:
- **import**: no file names the application by an import specifier — `from '…'` (so also `export … from '…'`),
  `import('…')`, or a bare `import '…'`. A relative specifier is resolved against the importing file and tested
  for landing inside the application or one of its generated clients, so `'../../memory/memoryNodes'` and
  `'../features/memory/memoryNodes'` are the same edge, as are `'../generated/media'` and `'../../api/generated/media/models'`;
  a non-relative specifier that spells `features/memory` or an application client's path is refused too (no alias exists today).
- **glob**: no file contains `import.meta.glob`. Discovery by glob would hide an edge from the rule above, and
  ADR 0079 Decision 5 says what runs is what was reviewed.
- **kind**: no file names `NodeKind.Memory`, `NodeKind.Chapter`, `'MEMORY'` or `'CHAPTER'`. The shell asks a
  registry what to render for a kind; it does not know which kinds the one application has.
Exempt by exact path: `web/src/applications.ts`, the one composition root that may import the application (a
sibling file importing the application is red; an absent root is not an error). Exempt by location: `web/src/test/**` and `*.test.ts` — and the generated API
client, which is untracked and spells the kinds by definition. `api/client.ts` is **not** exempt: core's factory
file building a Memories client is the edge this guard exists for (the Memories factories live in the application).

**Fail closed.** No `web/package.json`, no `generate:api` script, a generate step without its `-i`/`-o`, no generate
step at all, or no step reading `APPLICATION_SPECS` (while that directory holds a spec) is red: a parse that found
nothing would otherwise read as "the application owns no client".

**No allowlist.** Any violation is red. (The guard was born as a ratchet — core imported the application at 14
files on the day it landed, so a plain guard would have been red — and the allowlist shrank to nothing as the
boundary was drawn; a list that can be added to is a list that is added to, so the mechanism went with the last entry.)

**The defect that bought it.** No incident — the web half of ADR 0079 was prose until this guard. `app/`,
`components/app/`, `sw/`, `views/` and three core features imported `features/memory` directly, which also made two
import cycles (`app/activeSpace` ↔ the Memories tree store, `features/spaces` ↔ `features/memory`); an application
that core cannot import is what makes a second one possible.

**Its limit.** Regex over source, not an AST: a commented-out import is read as an import (conservative), and a
specifier built at runtime (`import(base + name)`) is not seen — the glob rule and the review of the composition
root are what stand against discovery by computation. Tests and `src/test/` are not read (a test is a consumer of
both sides, as in the backend guard). `shared/ui/SecretKeyField` → `features/account/` is a different edge (shared
→ a core feature, not core → application), named in the ui-web styleguide, and this guard does not look at it.
The application's own outbound edges are fine (it may use core) and are not read. **A client generated outside
`generate:api`** (another script, a hand-run generator, a checked-in copy) is not seen: the client half of the
application is exactly what that script's `-i`/`-o` pairs say, and a spec under `contracts/api/memories/` generated
by any other route is core's to this guard.
"""
from __future__ import annotations

import json
import os
import pathlib
import re
import shlex
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
WEB = ROOT / "web"
SRC = WEB / "src"
PACKAGE = WEB / "package.json"
GENERATE_SCRIPT = "generate:api"
APPLICATION = ("features", "memory")  # relative to web/src — the one constant to change when the directory is renamed
APPLICATION_SPECS = ("contracts", "api", "memories")  # relative to the repo root — the application's specs
COMPOSITION_ROOT = "applications.ts"  # relative to web/src; exact path, never a directory
GENERATED = ("api", "generated")  # untracked client; defines the kinds, so it names them

RULE_IMPORT, RULE_GLOB, RULE_KIND = "import", "glob", "kind"

# `from '…'` covers import/export-from, also across lines; `import('…')` and a bare `import '…'` the others.
SPECIFIER = re.compile(r"""(?:\bfrom\s*|\bimport\s*\(\s*|\bimport\s+)(['"])([^'"\n]+)\1""")
GLOB = re.compile(r"\bimport\.meta\.glob(?:Eager)?\b")
# Built from parts so this file never spells a kind literal it forbids.
KIND = re.compile(r"\bNodeKind\.(?:Memory|Chapter)\b|['\"](?:" + "MEMO" + "RY|" + "CHAP" + "TER)['\"]")


def is_exempt(rel: pathlib.PurePosixPath) -> bool:
    parts = rel.parts
    return (parts[:len(APPLICATION)] == APPLICATION
            or str(rel) == COMPOSITION_ROOT
            or parts[0] == "test"
            or parts[:2] == GENERATED
            or rel.name.endswith(".test.ts"))


class ParseError(Exception):
    pass


def generated_pairs(package_text: str) -> list[tuple[str, str]]:
    """The (spec, output) pair of every `openapi-generator-cli generate` step of `generate:api`, as written."""
    try:
        script = json.loads(package_text)["scripts"][GENERATE_SCRIPT]
    except (ValueError, KeyError, TypeError) as e:
        raise ParseError(f"no `scripts.{GENERATE_SCRIPT}` in web/package.json ({e!r})")
    pairs: list[tuple[str, str]] = []
    for step in script.split("&&"):
        try:
            argv = shlex.split(step)
        except ValueError as e:
            raise ParseError(f"a `{GENERATE_SCRIPT}` step does not parse ({e}): {step.strip()[:80]}")
        if "openapi-generator-cli" not in argv or "generate" not in argv:
            continue
        spec_out: dict[str, str] = {}
        for flag, value in zip(argv, argv[1:]):
            if flag in ("-i", "--input-spec"):
                spec_out["i"] = value
            elif flag in ("-o", "--output"):
                spec_out["o"] = value
        if set(spec_out) != {"i", "o"}:
            raise ParseError(f"a `{GENERATE_SCRIPT}` generate step lacks its -i or -o: {step.strip()[:80]}")
        pairs.append((spec_out["i"], spec_out["o"]))
    if not pairs:
        raise ParseError(f"`{GENERATE_SCRIPT}` has no `openapi-generator-cli generate` step")
    return pairs


def application_clients(pairs: list[tuple[str, str]], web: pathlib.Path,
                        root: pathlib.Path) -> list[pathlib.PurePosixPath]:
    """The output directories (relative to web/src) of the steps that read a spec under `APPLICATION_SPECS`."""
    specs_dir = pathlib.Path(os.path.normpath(root.joinpath(*APPLICATION_SPECS)))
    src = pathlib.Path(os.path.normpath(web / "src"))
    owned: list[pathlib.PurePosixPath] = []
    for spec, out in pairs:
        spec_path = pathlib.Path(os.path.normpath(web / spec))
        if specs_dir not in spec_path.parents:
            continue
        out_path = pathlib.Path(os.path.normpath(web / out))
        if out_path != src and src not in out_path.parents:
            raise ParseError(f"the application's client from {spec} is generated outside web/src ({out}); "
                             "this guard cannot tell its importers")
        owned.append(pathlib.PurePosixPath(out_path.relative_to(src).as_posix()))
    if not owned and any(specs_dir.glob("*.openapi.yaml")):
        raise ParseError(f"no `{GENERATE_SCRIPT}` step reads a spec under {'/'.join(APPLICATION_SPECS)}/, which "
                         "holds specs — the parse found nothing, or the application's clients moved")
    return owned


def _inside(target: pathlib.PurePosixPath, anchor: tuple[str, ...]) -> bool:
    return target.parts[:len(anchor)] == anchor


def reaches_application(rel: pathlib.PurePosixPath, spec: str,
                        clients: list[pathlib.PurePosixPath] = ()) -> bool:
    anchors = [APPLICATION] + [c.parts for c in clients]
    if spec.startswith("."):
        target = pathlib.PurePosixPath(os.path.normpath(str(rel.parent / spec)))
        return any(_inside(target, a) for a in anchors)
    return any(re.search(r"(?:^|/)" + re.escape("/".join(a)) + r"(?:/|$)", spec) for a in anchors)


def violations(rel: pathlib.PurePosixPath, text: str, clients: list[pathlib.PurePosixPath] = ()) -> set[str]:
    found: set[str] = set()
    if any(reaches_application(rel, m.group(2), clients) for m in SPECIFIER.finditer(text)):
        found.add(RULE_IMPORT)
    if GLOB.search(text):
        found.add(RULE_GLOB)
    if KIND.search(text):
        found.add(RULE_KIND)
    return found


def main() -> int:
    files = sorted(p for p in SRC.rglob("*") if p.suffix in (".ts", ".vue") and p.is_file())
    if not (SRC.is_dir() and (SRC / pathlib.Path(*APPLICATION)).is_dir() and files):
        print(f"WEB-CORE-IMPORTS FAILED — no source under {SRC.relative_to(ROOT)} or no application directory "
              f"{'/'.join(APPLICATION)}; the guard checked nothing. If web/ moved or the application was renamed, "
              "move this guard's anchors with it.")
        return 1
    try:
        if not PACKAGE.is_file():
            raise ParseError("web/package.json is missing")
        clients = application_clients(generated_pairs(PACKAGE.read_text(encoding="utf-8")), WEB, ROOT)
    except ParseError as e:
        print(f"WEB-CORE-IMPORTS FAILED — cannot derive the application's generated clients: {e}. Fix the "
              f"`{GENERATE_SCRIPT}` script or move this guard's anchors with it.")
        return 1

    errors: list[str] = []
    checked = 0
    for path in files:
        rel = pathlib.PurePosixPath(path.relative_to(SRC).as_posix())
        if is_exempt(rel):
            continue
        checked += 1
        for rule in sorted(violations(rel, path.read_text(encoding="utf-8"), clients)):
            errors.append(f"web/src/{rel}: core breaks the `{rule}` rule — " + {
                RULE_IMPORT: "it imports the Memories application or one of its generated clients; ask the "
                             "registry or move the shared piece into core (ADR 0079)",
                RULE_GLOB: "`import.meta.glob` hides edges from this guard; register applications by a static "
                           "import in web/src/applications.ts",
                RULE_KIND: "it names a Memories node kind; ask the registry what to render for a kind",
            }[rule])
    if errors:
        print("WEB-CORE-IMPORTS FAILED:")
        for e in errors:
            print("  -", e)
        return 1
    print(f"web core imports OK: {checked} core file(s) read, no violation (application clients: "
          f"{', '.join(str(c) for c in clients) or 'none'})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
