#!/usr/bin/env python3
"""Guard that the web core does not import, nor name the kinds of, an application, and that no application imports
another (ADR 0079, web phase).

**What it asserts.** The applications are the directories of `APPLICATIONS` below (today `web/src/features/memory`,
Memories', and `web/src/features/files`, Files'), each keyed by its owner name — the directory its specs live in under
`contracts/api/<owner>/`, the owner rule `buildSrc`'s `SpecSets` builds by (inbox #344) — **plus every generated API
client whose `generate:api` run reads an application's spec**: an application owns the clients generated from its
own `contracts/api/<owner>/`. Core is everything else under `web/src` — by exclusion, so a new directory is guarded
the day it appears. The client half is **derived**, never listed: each `openapi-generator-cli generate` step of
`web/package.json`'s `generate:api` script is read for its `-i` (spec) and `-o` (output directory), and an output
whose spec lies in an application's directory is that application's (today `api/generated/favorites`,
`api/generated/media` and `api/generated/share-media`, all Memories'), so a new application client is covered the day
its line lands. Core's own clients (`blob`, `content`, …) stay core's: importing them is the point of generating them.
Dependencies run application → core, never back, and never application → application. Four rules:
- **import** (core): no core file names an application by an import specifier — `from '…'` (so also
  `export … from '…'`), `import('…')`, or a bare `import '…'`. A relative specifier is resolved against the importing
  file and tested for landing inside an application or one of its generated clients, so `'../../memory/memoryNodes'`
  and `'../features/memory/memoryNodes'` are the same edge, as are `'../generated/media'` and
  `'../../api/generated/media/models'`; a non-relative specifier that spells an application's directory or client
  path is refused too (no alias exists today).
- **application** (every application): no file of one application names ANOTHER application, or another
  application's generated client, by the same specifier reading. An application imports core and itself; what two
  applications share belongs in core (ADR 0079 Decision 4, the backend's rule (b) in
  `validate_backend_module_graph.py`).
- **glob** (core): no file contains `import.meta.glob`. Discovery by glob would hide an edge from the rules above,
  and ADR 0079 Decision 5 says what runs is what was reviewed.
- **kind** (core): no file names an application's node kind — `NodeKind.Memory`, `NodeKind.Chapter`,
  `NodeKind.File`, `'MEMORY'`, `'CHAPTER'` or `'FILE'`. The shell asks a registry what to render for a kind; it does
  not know which kinds the applications have. Since the contract's `NodeKind` opened to a bounded token
  (cr-platform-contract-split slice 4) the generated client has no `NodeKind` enum and core types a kind as a string
  with a `FOLDER_KIND` constant (`core/node/kind.ts`), so a literal is the spelling left; the enum spelling stays
  matched for a client generated from an older contract. Each application's constants live in the application
  (`features/memory/kinds.ts`, `features/files/kinds.ts`), which the import rule already keeps from core. The kinds
  are listed here by hand (a kind is a value, not a path): a new application adds its kinds to `KIND`.
Every rule reads the non-test `.ts`/`.vue` files. Exempt by exact path: `web/src/applications.ts`, the one
composition root that may import the applications (a sibling file importing one is red; an absent root is not an
error). Exempt by location: `web/src/test/**` and `*.test.ts` — and the generated API client, which is untracked and
spells the kinds by definition. `api/client.ts` is **not** exempt: core's factory file building a Memories client is
the edge this guard exists for (the Memories factories live in the application).

**Fail closed.** No `web/package.json`, no `generate:api` script, a generate step without its `-i`/`-o`, no generate
step at all, or no step reading an application's spec (while some application directory holds one) is red: a parse
that found nothing would otherwise read as "no application owns a client". That is one check over all applications,
not one per directory: an application whose specs land before its web client (the phase order is contracts → backend
→ web) is not red in between. A directory of `APPLICATIONS` that does not exist is red (the guard would read
nothing for it), and so is a client generated from a `contracts/api/<owner>/` whose owner `APPLICATIONS` does not
name: the guard could not tell which application owns it, so the application rule could not hold it.

**No allowlist.** Any violation is red. (The guard was born as a ratchet — core imported the application at 14
files on the day it landed, so a plain guard would have been red — and the allowlist shrank to nothing as the
boundary was drawn; a list that can be added to is a list that is added to, so the mechanism went with the last entry.)

**The defect that bought it.** No incident — the web half of ADR 0079 was prose until this guard. `app/`,
`components/app/`, `sw/`, `views/` and three core features imported `features/memory` directly, which also made two
import cycles (`app/activeSpace` ↔ the Memories tree store, `features/spaces` ↔ `features/memory`); an application
that core cannot import is what makes a second one possible. The application rule came with that second one
(cr-files-application F0), before any Files file could reach into Memories.

**Its limit.** Regex over source, not an AST: a commented-out import is read as an import (conservative), and a
specifier built at runtime (`import(base + name)`) is not seen — the glob rule and the review of the composition
root are what stand against discovery by computation. The glob and kind rules read core only: an application naming
another's kind or globbing is not seen (an application → application edge by import still is). Tests and `src/test/`
are not read (a test is a consumer of both sides, as in the backend guard). `shared/ui/SecretKeyField` →
`features/account/` is a different edge (shared → a core feature, not core → application), named in the ui-web
styleguide, and this guard does not look at it. **A client generated outside `generate:api`** (another script, a
hand-run generator, a checked-in copy) is not seen: the client half of an application is exactly what that script's
`-i`/`-o` pairs say, and a spec under `contracts/api/<owner>/` generated by any other route is core's to this guard.
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
# Every application: its owner name (the directory of its specs, `contracts/api/<owner>/`, and its backend module
# `:apps:<owner>`) → its directory relative to web/src. The one place to change when an application is added or renamed.
APPLICATIONS: dict[str, tuple[str, ...]] = {
    "memories": ("features", "memory"),
    "files": ("features", "files"),
}
CONTRACTS_API = ("contracts", "api")  # relative to the repo root; a spec directly in a directory below it is an application's
COMPOSITION_ROOT = "applications.ts"  # relative to web/src; exact path, never a directory
GENERATED = ("api", "generated")  # untracked client; defines the kinds, so it names them

RULE_IMPORT, RULE_APPLICATION, RULE_GLOB, RULE_KIND = "import", "application", "glob", "kind"

# `from '…'` covers import/export-from, also across lines; `import('…')` and a bare `import '…'` the others.
SPECIFIER = re.compile(r"""(?:\bfrom\s*|\bimport\s*\(\s*|\bimport\s+)(['"])([^'"\n]+)\1""")
GLOB = re.compile(r"\bimport\.meta\.glob(?:Eager)?\b")
# The applications' kinds — Memories' MEMORY and CHAPTER, Files' FILE. Built from parts so this file never spells a
# kind literal it forbids.
KIND = re.compile(r"\bNodeKind\.(?:Memory|Chapter|File)\b|['\"](?:" + "MEMO" + "RY|" + "CHAP" + "TER|" + "FI" + "LE)['\"]")


def application_of(rel: pathlib.PurePosixPath) -> str | None:
    """The owner of the application `rel` (relative to web/src) lies in, or None for core."""
    for owner, directory in APPLICATIONS.items():
        if rel.parts[:len(directory)] == directory:
            return owner
    return None


def is_exempt(rel: pathlib.PurePosixPath) -> bool:
    parts = rel.parts
    return (str(rel) == COMPOSITION_ROOT
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
                        root: pathlib.Path) -> dict[str, list[pathlib.PurePosixPath]]:
    """The output directories (relative to web/src) of the steps that read an application's spec — one directly in a
    directory below `CONTRACTS_API` (`contracts/api/<owner>/x.openapi.yaml`) — keyed by that owner."""
    api_dir = pathlib.Path(os.path.normpath(root.joinpath(*CONTRACTS_API)))
    src = pathlib.Path(os.path.normpath(web / "src"))
    owned: dict[str, list[pathlib.PurePosixPath]] = {}
    for spec, out in pairs:
        spec_path = pathlib.Path(os.path.normpath(web / spec))
        if api_dir not in spec_path.parents or len(spec_path.relative_to(api_dir).parts) != 2:
            continue
        owner = spec_path.relative_to(api_dir).parts[0]
        if owner not in APPLICATIONS:
            raise ParseError(f"the client from {spec} belongs to the application `{owner}`, which this guard's "
                             "APPLICATIONS does not name — add its web directory there")
        out_path = pathlib.Path(os.path.normpath(web / out))
        if out_path != src and src not in out_path.parents:
            raise ParseError(f"the application's client from {spec} is generated outside web/src ({out}); "
                             "this guard cannot tell its importers")
        owned.setdefault(owner, []).append(pathlib.PurePosixPath(out_path.relative_to(src).as_posix()))
    if not owned and any(api_dir.glob("*/*.openapi.yaml")):
        raise ParseError(f"no `{GENERATE_SCRIPT}` step reads a spec under {'/'.join(CONTRACTS_API)}/<app>/, where "
                         "applications hold specs — the parse found nothing, or the applications' clients moved")
    return owned


def _inside(target: pathlib.PurePosixPath, anchor: tuple[str, ...]) -> bool:
    return target.parts[:len(anchor)] == anchor


def anchors_of(owners, clients: dict[str, list[pathlib.PurePosixPath]]) -> list[tuple[str, ...]]:
    """The directories and generated clients of the applications `owners`, as path-part tuples below web/src."""
    return [a for o in owners for a in [APPLICATIONS[o]] + [c.parts for c in clients.get(o, [])]]


def reaches(rel: pathlib.PurePosixPath, spec: str, anchors: list[tuple[str, ...]]) -> bool:
    if spec.startswith("."):
        target = pathlib.PurePosixPath(os.path.normpath(str(rel.parent / spec)))
        return any(_inside(target, a) for a in anchors)
    return any(re.search(r"(?:^|/)" + re.escape("/".join(a)) + r"(?:/|$)", spec) for a in anchors)


def violations(rel: pathlib.PurePosixPath, text: str,
               clients: dict[str, list[pathlib.PurePosixPath]] | None = None) -> set[str]:
    clients = clients or {}
    owner = application_of(rel)
    found: set[str] = set()
    if owner is not None:
        others = anchors_of([o for o in APPLICATIONS if o != owner], clients)
        if any(reaches(rel, m.group(2), others) for m in SPECIFIER.finditer(text)):
            found.add(RULE_APPLICATION)
        return found
    if any(reaches(rel, m.group(2), anchors_of(APPLICATIONS, clients)) for m in SPECIFIER.finditer(text)):
        found.add(RULE_IMPORT)
    if GLOB.search(text):
        found.add(RULE_GLOB)
    if KIND.search(text):
        found.add(RULE_KIND)
    return found


MESSAGES = {
    RULE_IMPORT: "it imports an application or one of its generated clients; ask the registry or move the shared "
                 "piece into core (ADR 0079)",
    RULE_APPLICATION: "it imports another application or one of its generated clients; an application depends on "
                      "core only — move what both need into core (ADR 0079 Decision 4)",
    RULE_GLOB: "`import.meta.glob` hides edges from this guard; register applications by a static import in "
               "web/src/applications.ts",
    RULE_KIND: "it names an application's node kind; ask the registry what to render for a kind",
}


def main() -> int:
    files = sorted(p for p in SRC.rglob("*") if p.suffix in (".ts", ".vue") and p.is_file())
    missing = [d for d in APPLICATIONS.values() if not (SRC / pathlib.Path(*d)).is_dir()]
    if not (SRC.is_dir() and files) or missing:
        print(f"WEB-CORE-IMPORTS FAILED — no source under {SRC.relative_to(ROOT)}, or an application directory is "
              f"missing ({', '.join('/'.join(d) for d in missing) or 'none'}); the guard would check nothing there. "
              "If web/ moved or an application was renamed, move this guard's APPLICATIONS with it.")
        return 1
    try:
        if not PACKAGE.is_file():
            raise ParseError("web/package.json is missing")
        clients = application_clients(generated_pairs(PACKAGE.read_text(encoding="utf-8")), WEB, ROOT)
    except ParseError as e:
        print(f"WEB-CORE-IMPORTS FAILED — cannot derive the applications' generated clients: {e}. Fix the "
              f"`{GENERATE_SCRIPT}` script or move this guard's anchors with it.")
        return 1

    errors: list[str] = []
    read = {"core": 0, **{o: 0 for o in APPLICATIONS}}
    for path in files:
        rel = pathlib.PurePosixPath(path.relative_to(SRC).as_posix())
        if is_exempt(rel):
            continue
        owner = application_of(rel)
        read[owner or "core"] += 1
        for rule in sorted(violations(rel, path.read_text(encoding="utf-8"), clients)):
            who = f"the application `{owner}`" if owner else "core"
            errors.append(f"web/src/{rel}: {who} breaks the `{rule}` rule — {MESSAGES[rule]}")
    if errors:
        print("WEB-CORE-IMPORTS FAILED:")
        for e in errors:
            print("  -", e)
        return 1
    listed = ", ".join(f"{o}: {', '.join(str(c) for c in cs)}" for o, cs in sorted(clients.items())) or "none"
    print(f"web core imports OK: {read['core']} core file(s) read, "
          + ", ".join(f"{read[o]} {o} file(s)" for o in APPLICATIONS)
          + f", no violation (application clients — {listed})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
