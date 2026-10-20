#!/usr/bin/env python3
"""Guard that the web core does not import, nor name the kinds of, an application, and that no application imports
another (ADR 0079, web phase).

**What it asserts.** The applications are the directories of `APPLICATIONS` below (today `web/src/features/memories`,
Memories', and `web/src/features/depot`, Depot's), each keyed by its owner name — the directory its specs live in under
`contracts/api/<owner>/`, the owner rule `buildSrc`'s `SpecSets` builds by (inbox #344) — and named for it: the
directory is `web/src/features/<owner>`, derived from `OWNERS`, never listed (inbox #324 moved `features/memory` there),
so a directory named otherwise is a missing one — **plus every generated API
client whose `generate:api` run reads an application's spec**: an application owns the clients generated from its
own `contracts/api/<owner>/`. Core is everything else under `web/src` — by exclusion, so a new directory is guarded
the day it appears. The client half is **derived**, never listed: each `openapi-generator-cli generate` step of
`web/package.json`'s `generate:api` script is read for its `-i` (spec) and `-o` (output directory), and an output
whose spec lies in an application's directory is that application's (today `api/generated/item-favorites`,
`api/generated/media` and `api/generated/share-media`, all Memories'; the node pins' `api/generated/favorites` is core's), so a new application client is covered the day
its line lands. Core's own clients (`blob`, `content`, …) stay core's: importing them is the point of generating them.
Dependencies run application → core, never back, and never application → application. Six rules:
- **import** (core): no core file names an application by an import specifier — `from '…'` (so also
  `export … from '…'`), `import('…')`, or a bare `import '…'`. A relative specifier is resolved against the importing
  file and tested for landing inside an application or one of its generated clients, so `'../../memories/memoryNodes'`
  and `'../features/memories/memoryNodes'` are the same edge, as are `'../generated/media'` and
  `'../../api/generated/media/models'`; a non-relative specifier that spells an application's directory or client
  path is refused too (no alias exists today).
- **application** (every application): no file of one application names ANOTHER application, or another
  application's generated client, by the same specifier reading. An application imports core and itself; what two
  applications share belongs in core (ADR 0079 Decision 4, the backend's rule (b) in
  `validate_backend_module_graph.py`). Nor does it read another application's i18n namespace (a quoted key, as the
  i18n rule below reads core): Depot borrowing `media.*` would be the same edge, spelled as a string.
- **glob** (core): no file contains `import.meta.glob`. Discovery by glob would hide an edge from the rules above,
  and ADR 0079 Decision 5 says what runs is what was reviewed.
- **kind** (core): no file names an application's node kind — `NodeKind.Memory`, `NodeKind.Chapter`,
  `NodeKind.File`, `'MEMORY'`, `'CHAPTER'`, `'FILE'` or `'DEPOT_DROPBOX'`. The shell asks a registry what to render for a kind; it does
  not know which kinds the applications have. Since the contract's `NodeKind` opened to a bounded token
  (cr-platform-contract-split slice 4) the generated client has no `NodeKind` enum and core types a kind as a string
  with a `FOLDER_KIND` constant (`core/node/kind.ts`), so a literal is the spelling left; the enum spelling stays
  matched for a client generated from an older contract. Each application's constants live in the application
  (`features/memories/kinds.ts`, `features/depot/kinds.ts`), which the import rule already keeps from core. The kinds
  are listed here by hand (a kind is a value, not a path): a new application adds its kinds to `KIND`.
- **i18n** (core): no file names a message key of an application's i18n namespace — a quoted `'memory.…'`,
  `'media.…'` (Memories') or `'depot.…'` (Depot's) — `I18N_NAMESPACES`, by hand like `KIND`. All namespaces end up in
  ONE vue-i18n instance at runtime, so a key resolves wherever its file lives, and nothing but this rule keeps core's
  strings from borrowing an application's: core serves every application's nodes, and a core dialog that read
  `memory.deleteMemoryTitle` titled a Depot drop box "Delete Memory" (inbox #375). A kind's own noun reaches core
  through the registry (`KindDescriptor.deleteTitle`, a key the application names), never as a literal in core.
- **catalog** (the catalogs' layout, inbox #513): each application owns its namespaces in its own catalog,
  `web/src/features/<owner>/i18n.ts` (derived from the owner, never listed), whose `en` and `de` carry exactly that
  application's `I18N_NAMESPACES` as top-level blocks — not another application's, not core's; and core's catalog
  (`i18n/index.ts`) carries none of them. A catalog that is missing, or whose `en`/`de` block does not parse, is red,
  and so is an `I18N_NAMESPACES` that does not name every application of `APPLICATIONS`: a namespace that moved or
  was renamed would otherwise leave the i18n rule matching nothing. The composition root's catalog half,
  `web/src/applicationMessages.ts`, adds each application's catalog to core's instance (which refuses a namespace
  already held, at runtime); it is held by exact path to importing an application's catalog and NOTHING else of it
  (the **catalog-root** reading of the import rule) — a component imported there would load the application where only
  its strings were meant to.
Every rule reads the non-test `.ts`/`.vue` files. Exempt by exact path: `web/src/applications.ts`, the one
composition root that may import the applications (a sibling file importing one is red; an absent root is not an
error); `web/src/applicationMessages.ts` is not exempt but may reach an application's catalog, as above. Exempt by location: `web/src/test/**` and `*.test.ts` — and the generated API client, which is untracked and
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
`components/app/`, `sw/`, `views/` and three core features imported `features/memories` directly, which also made two
import cycles (`app/activeSpace` ↔ the Memories tree store, `features/spaces` ↔ `features/memories`); an application
that core cannot import is what makes a second one possible. The application rule came with that second one
(cr-files-application F0), before any Depot file could reach into Memories.

**Its limit.** Regex over source, not an AST: a commented-out import is read as an import (conservative), and a
specifier built at runtime (`import(base + name)`) is not seen — the glob rule and the review of the composition
root are what stand against discovery by computation. The catalog rule reads the top-level keys of the `const en = {` and `const de… = {` blocks at
two-space indentation, as the files are formatted: a namespace added any other way (a spread, a computed key, a
`mergeLocaleMessage` call elsewhere) is not seen by it — the runtime's write-once `addApplicationMessages` refuses
a namespace already held, which covers a taken one but not a new one. The i18n rule reads a key spelled whole in quotes: a key in a
template literal or put together at runtime (`t(ns + '.x')`) is not seen, and a quoted file name that happens to read
as a key (`'media.ts'`) is red (conservative). The glob and kind rules read core only: an application naming
another's kind or globbing is not seen (an application → application edge by import, or by a quoted key of another's
namespace, still is). Tests and `src/test/`
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
# Every application by its owner name — the directory of its specs (`contracts/api/<owner>/`) and its backend module
# (`:apps:<owner>`). The one place to change when an application is added or renamed.
OWNERS = ("memories", "depot")
# Its directory relative to web/src is DERIVED from the owner name, never listed: `features/<owner>`, as the backend
# derives `:apps:<owner>` (ADR 0079). A web directory named otherwise is a missing one, and a missing one is red.
APPLICATIONS: dict[str, tuple[str, ...]] = {owner: ("features", owner) for owner in OWNERS}
CONTRACTS_API = ("contracts", "api")  # relative to the repo root; a spec directly in a directory below it is an application's
COMPOSITION_ROOT = "applications.ts"  # relative to web/src; exact path, never a directory
# The composition root's catalog half (inbox #513): may import an application's catalog, nothing else of it.
CATALOG_ROOT = "applicationMessages.ts"  # relative to web/src; exact path
APPLICATION_CATALOG = "i18n.ts"  # an application's own catalog: features/<owner>/i18n.ts, derived like the directory
GENERATED = ("api", "generated")  # untracked client; defines the kinds, so it names them

RULE_IMPORT, RULE_APPLICATION, RULE_GLOB, RULE_KIND, RULE_I18N = "import", "application", "glob", "kind", "i18n"
RULE_CATALOG_ROOT = "catalog-root"

# `from '…'` covers import/export-from, also across lines; `import('…')` and a bare `import '…'` the others.
SPECIFIER = re.compile(r"""(?:\bfrom\s*|\bimport\s*\(\s*|\bimport\s+)(['"])([^'"\n]+)\1""")
GLOB = re.compile(r"\bimport\.meta\.glob(?:Eager)?\b")
# The applications' kinds — Memories' MEMORY and CHAPTER, Depot's FILE. Built from parts so this file never spells a
# kind literal it forbids.
KIND = re.compile(r"\bNodeKind\.(?:Memory|Chapter|File)\b|['\"](?:" + "MEMO" + "RY|" + "CHAP" + "TER|" + "FI" + "LE|" + "DEPOT_DROP" + "BOX)['\"]")
# Each application's top-level i18n namespaces — the blocks of its own catalog (`APPLICATION_CATALOG`), keyed like
# `APPLICATIONS`.
I18N_NAMESPACES: dict[str, tuple[str, ...]] = {
    "memories": ("memory", "media"),
    "depot": ("depot",),
}
CATALOG = ("i18n", "index.ts")  # core's catalog, relative to web/src


def i18n_key(namespaces) -> re.Pattern:
    """A message key of one of `namespaces` in a '…' or "…" literal: `t('memory.deleteMemoryTitle')`, `{ key: 'depot.x' }`."""
    return re.compile(r"(['\"])(?:" + "|".join(namespaces) + r")(?:\.[A-Za-z_][A-Za-z0-9_]*)+\1")


I18N_KEY = i18n_key(n for ns in I18N_NAMESPACES.values() for n in ns)


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


def is_application_catalog(rel: pathlib.PurePosixPath, spec: str) -> bool:
    """Whether the relative specifier `spec`, read from `rel`, names an application's own catalog module exactly."""
    if not spec.startswith("."):
        return False
    target = pathlib.PurePosixPath(os.path.normpath(str(rel.parent / spec)))
    stem = APPLICATION_CATALOG.rsplit(".", 1)[0]
    return any(target.parts in ((*d, stem), (*d, APPLICATION_CATALOG)) for d in APPLICATIONS.values())


def violations(rel: pathlib.PurePosixPath, text: str,
               clients: dict[str, list[pathlib.PurePosixPath]] | None = None) -> set[str]:
    clients = clients or {}
    owner = application_of(rel)
    found: set[str] = set()
    if owner is not None:
        other_owners = [o for o in APPLICATIONS if o != owner]
        others = anchors_of(other_owners, clients)
        if any(reaches(rel, m.group(2), others) for m in SPECIFIER.finditer(text)):
            found.add(RULE_APPLICATION)
        if other_owners and i18n_key(n for o in other_owners for n in I18N_NAMESPACES[o]).search(text):
            found.add(RULE_I18N)
        return found
    edges = [m.group(2) for m in SPECIFIER.finditer(text)
             if reaches(rel, m.group(2), anchors_of(APPLICATIONS, clients))]
    if str(rel) == CATALOG_ROOT:
        if any(not is_application_catalog(rel, spec) for spec in edges):
            found.add(RULE_CATALOG_ROOT)
    elif edges:
        found.add(RULE_IMPORT)
    if GLOB.search(text):
        found.add(RULE_GLOB)
    if KIND.search(text):
        found.add(RULE_KIND)
    if I18N_KEY.search(text):
        found.add(RULE_I18N)
    return found


MESSAGES = {
    RULE_IMPORT: "it imports an application or one of its generated clients; ask the registry or move the shared "
                 "piece into core (ADR 0079)",
    RULE_APPLICATION: "it imports another application or one of its generated clients; an application depends on "
                      "core only — move what both need into core (ADR 0079 Decision 4)",
    RULE_GLOB: "`import.meta.glob` hides edges from this guard; register applications by a static import in "
               "web/src/applications.ts",
    RULE_KIND: "it names an application's node kind; ask the registry what to render for a kind",
    RULE_I18N: "it reads an application's i18n namespace (core), or another application's (an application); core's "
               "strings name no application — use a core key (e.g. `node.*`), or let the kind bring its wording "
               "through the registry; an application's strings are its own or core's",
    RULE_CATALOG_ROOT: "the composition root's catalog half imports an application's catalog "
                       f"(`features/<owner>/{APPLICATION_CATALOG}`) and nothing else of it — register code in "
                       f"web/src/{COMPOSITION_ROOT}",
}


LOCALE_BLOCK = re.compile(r"^const (en|de)\b[^=\n]*=\s*\{[ \t]*\n(.*?)^\}", re.M | re.S)
TOP_LEVEL_KEY = re.compile(r"^  ([A-Za-z_$][\w$]*)\s*:", re.M)


def catalog_namespaces(text: str) -> dict[str, set[str]]:
    """The top-level keys of a catalog file's `const en = {…}` and `const de… = {…}` blocks, per locale. A block that
    is missing or carries no key raises — a parse that found nothing would read as "names no namespace"."""
    found = {m.group(1): set(TOP_LEVEL_KEY.findall(m.group(2))) for m in LOCALE_BLOCK.finditer(text)}
    if set(found) != {"en", "de"} or not all(found.values()):
        raise ParseError("no `const en = {…}` and `const de… = {…}` block with top-level keys at two-space indentation")
    return found


def catalog_errors(src: pathlib.Path) -> list[str]:
    """Where the catalogs' layout breaks the catalog rule: core's names an application's namespace, an application's
    names anything but its own, a catalog is missing or does not parse, or `I18N_NAMESPACES` misses an application."""
    errors: list[str] = []
    if set(I18N_NAMESPACES) != set(APPLICATIONS):
        errors.append("I18N_NAMESPACES does not name every application of APPLICATIONS (and nothing else)")
    owned = {n for ns in I18N_NAMESPACES.values() for n in ns}
    catalogs = [(None, pathlib.PurePosixPath(*CATALOG))] + [
        (o, pathlib.PurePosixPath(*APPLICATIONS[o], APPLICATION_CATALOG)) for o in sorted(I18N_NAMESPACES)
        if o in APPLICATIONS]
    for owner, rel in catalogs:
        path = src / rel
        who = "core" if owner is None else f"the application `{owner}`"
        if not path.is_file():
            errors.append(f"web/src/{rel}: {who} has no catalog there")
            continue
        try:
            per_locale = catalog_namespaces(path.read_text(encoding="utf-8"))
        except ParseError as e:
            errors.append(f"web/src/{rel}: {e}")
            continue
        for locale, names in sorted(per_locale.items()):
            if owner is None and names & owned:
                errors.append(f"web/src/{rel}: core's `{locale}` carries an application's namespace "
                              f"({', '.join(sorted(names & owned))}) — it belongs in that application's "
                              f"features/<owner>/{APPLICATION_CATALOG}")
            elif owner is not None and names != set(I18N_NAMESPACES[owner]):
                errors.append(f"web/src/{rel}: the `{locale}` of {who}'s catalog carries {', '.join(sorted(names))}"
                              f" — exactly its own namespaces ({', '.join(I18N_NAMESPACES[owner])}) belong there; "
                              "core's go in web/src/i18n/index.ts, another application's in its own catalog")
    return errors


def main() -> int:
    files = sorted(p for p in SRC.rglob("*") if p.suffix in (".ts", ".vue") and p.is_file())
    missing = [d for d in APPLICATIONS.values() if not (SRC / pathlib.Path(*d)).is_dir()]
    if not (SRC.is_dir() and files) or missing:
        print(f"WEB-CORE-IMPORTS FAILED — no source under {SRC.relative_to(ROOT)}, or an application directory is "
              f"missing ({', '.join('/'.join(d) for d in missing) or 'none'}); the guard would check nothing there. "
              "If web/ moved or an application was renamed, move this guard's OWNERS with it — an application's "
              "web directory is features/<owner>.")
        return 1
    try:
        if not PACKAGE.is_file():
            raise ParseError("web/package.json is missing")
        clients = application_clients(generated_pairs(PACKAGE.read_text(encoding="utf-8")), WEB, ROOT)
    except ParseError as e:
        print(f"WEB-CORE-IMPORTS FAILED — cannot derive the applications' generated clients: {e}. Fix the "
              f"`{GENERATE_SCRIPT}` script or move this guard's anchors with it.")
        return 1

    errors: list[str] = [f"{e} (the `catalog` rule)" for e in catalog_errors(SRC)]
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
          + f", no violation (application clients — {listed}; application catalogs — "
          + ", ".join(f"{o}: {'/'.join(ns)}" for o, ns in I18N_NAMESPACES.items()) + ")")
    return 0


if __name__ == "__main__":
    sys.exit(main())
