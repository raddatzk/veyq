#!/usr/bin/env python3
"""Guard the Flyway migrations: one global version scheme across modules, every file pinned, and the module
direction of ADR 0079 held in the schema (backend/CLAUDE.md § Migrations).

**What it asserts.** Over every `*.sql` in a `src/main/resources/db/migration` directory anywhere under `backend/`
(build output excluded), and the pin file `scripts/applied_migrations.sha256`:

  1. *Name shape.* `V<yyyymmddHHMM>__<module>_<slug>.sql`, the module one of `MODULES`. Twelve digits, so
     versions compare as numbers the way Flyway compares them, and the owner is readable without opening the path.
  2. *Unique versions across every directory.* Flyway refuses two files with one version at boot; this says so
     before a boot, and names both paths.
  3. *Pinned.* Every migration has a line in the pin file whose SHA-256 equals the file's bytes, and every line
     names a file that exists. An edit to a migration is therefore red until its pin line changes in the same
     diff — the only way the pre-release rule ("a baseline may change, but visibly") can be held by a check.
  4. *Ascending.* The pin lines are in strictly ascending version order, so a migration is appended above every
     earlier one. Flyway runs with `outOfOrder=false`: an instance already past a version refuses a new file below
     it at start.
  5. *Module direction.* A `core` migration names no table a non-core migration creates (an application may
     depend on core; core never on an application). A non-core migration's `REFERENCES` point at a core table or
     at a table of its own module, created at a lower version or earlier in the same file — never at another
     application's table, and never at a table no migration creates.
  6. *Release line.* The pin file carries exactly one `release: none | <version>` line, and a version it names is
     a pinned one. Only the owner declares a release; the guard checks the line's shape, not the event.

**The defect that bought it.** No incident: it was built with cr-platform-gradle-modules, step 1, which replaced
67 migrations by two module baselines. Two facts made a check worth having. The history's own rule — an applied
migration is never edited — failed silently where it was tested: `%dev` runs `repair-at-start=true`, so an edited
migration stays green on the machine that edited it and fails startup only on someone else's. And once migrations
live per module, two modules can pick one version, or a core migration can start naming a Memories table, with
no compiler to object.

**Its limit.** (a) It reads *declarations, not a database*: a statement is attributed to the table of its own
`CREATE TABLE`/`ALTER TABLE`, comments stripped, and table names are matched as words — a table named through
dynamic SQL, or a reference split across lines between `REFERENCES` and its table, is not seen. (b) The pin proves
a file is unchanged *since its line was written*, not that the line was written before anyone applied the file:
an edit that re-pins in the same commit is green by design, and before the release that is the rule. After the
release, an edit to a released migration plus its pin line is still green — the release line documents the
moment, the review of the pin diff is what holds it. (c) The module of a file is read from its name; from step 7
of cr-platform-gradle-modules on it must also match the module whose source root holds the file, which this guard
does not check yet (all migrations sit in one directory until then). (d) `MODULES` is a constant here until the
build's included projects are the source (cr-platform-gradle-modules step 8b): a third application is then a
guard edit, red until made.
"""
from __future__ import annotations

import hashlib
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
BACKEND = ROOT / "backend"
PIN_FILE = ROOT / "scripts" / "applied_migrations.sha256"
MODULES = ("core", "memories")
CORE = "core"

NAME = re.compile(r"^V(\d{12})__(" + "|".join(MODULES) + r")_\w+\.sql$")
PIN_LINE = re.compile(r"^([0-9a-f]{64})  (\S+)$")
RELEASE_LINE = re.compile(r"^release: (none|\d{12})$")
CREATE_TABLE = re.compile(r"\bCREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?(\w+)", re.IGNORECASE)
REFERENCES = re.compile(r"\bREFERENCES\s+(\w+)", re.IGNORECASE)
WORD = re.compile(r"\b\w+\b")
BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.DOTALL)
LINE_COMMENT = re.compile(r"--[^\n]*")


def migration_dirs() -> list[pathlib.Path]:
    """Every `src/main/resources/db/migration` under backend/, build output excluded — found, never listed, so a
    second module's directory is read the day it exists."""
    return sorted(
        d for d in BACKEND.glob("**/src/main/resources/db/migration")
        if d.is_dir() and "build" not in d.relative_to(BACKEND).parts
    )


def statements(path: pathlib.Path) -> list[str]:
    """The file's SQL statements, comments stripped (a mention in a comment is not a statement about a table)."""
    text = LINE_COMMENT.sub("", BLOCK_COMMENT.sub("", path.read_text(encoding="utf-8")))
    return [s.strip() for s in text.split(";") if s.strip()]


def main() -> int:
    errors: list[str] = []
    dirs = migration_dirs()
    files = sorted(f for d in dirs for f in d.glob("*.sql"))
    if not files:
        print(f"MIGRATION VERSIONS VALIDATION FAILED:\n  - no migration found under {BACKEND} — a moved tree must "
              f"be re-pointed, not silently skipped.")
        return 1

    # 1 + 2: name shape, unique versions.
    migrations: list[tuple[int, str, pathlib.Path]] = []
    by_version: dict[int, list[pathlib.Path]] = {}
    for f in files:
        m = NAME.match(f.name)
        if not m:
            errors.append(f"{f.relative_to(ROOT)}: name is not V<yyyymmddHHMM>__<module>_<slug>.sql with a module "
                          f"in {MODULES}.")
            continue
        version = int(m.group(1))
        migrations.append((version, m.group(2), f))
        by_version.setdefault(version, []).append(f)
    for version, paths in sorted(by_version.items()):
        if len(paths) > 1:
            errors.append(f"version {version} is used by {len(paths)} migrations: "
                          f"{', '.join(str(p.relative_to(ROOT)) for p in paths)} — Flyway refuses to start.")

    # 3 + 4 + 6: the pin file.
    if not PIN_FILE.is_file():
        errors.append(f"{PIN_FILE.relative_to(ROOT)} is missing: every migration is pinned there.")
        pins: list[tuple[str, str]] = []
        releases: list[str] = []
    else:
        pins, releases = [], []
        for n, line in enumerate(PIN_FILE.read_text(encoding="utf-8").splitlines(), start=1):
            if not line.strip() or line.startswith("#"):
                continue
            if r := RELEASE_LINE.match(line):
                releases.append(r.group(1))
            elif p := PIN_LINE.match(line):
                pins.append((p.group(1), p.group(2)))
            else:
                errors.append(f"{PIN_FILE.name}:{n}: neither a pin line `<sha256>  <path>` nor `release: …`: {line!r}")
    pinned = {path for _sha, path in pins}
    for sha, rel in pins:
        f = ROOT / rel
        if not f.is_file():
            errors.append(f"pinned migration {rel} has vanished. A migration that may have been applied is never "
                          f"deleted; if the pin is wrong, say why in the commit that drops its line.")
        elif hashlib.sha256(f.read_bytes()).hexdigest() != sha:
            errors.append(f"{rel} differs from its pin. An applied migration is never edited (Flyway fails startup "
                          f"on its checksum on every instance that ran it). Before the release, a baseline may "
                          f"change together with its pin line in the same commit; after it, add a new migration.")
    for _v, _mod, f in migrations:
        if str(f.relative_to(ROOT)) not in pinned:
            errors.append(f"{f.relative_to(ROOT)} is not pinned: append its line to {PIN_FILE.name} in the commit "
                          f"that adds it.")
    last = None
    for _sha, rel in pins:
        m = NAME.match(pathlib.PurePosixPath(rel).name)
        if not m:
            continue  # reported above as a name-shape error, if the file exists
        version = int(m.group(1))
        if last is not None and version <= last:
            errors.append(f"{rel}: version {version} is not above the pin line before it ({last}). Flyway "
                          f"(outOfOrder=false) refuses a migration below the version an instance already has.")
        last = version if last is None else max(last, version)
    if len(releases) != 1:
        errors.append(f"{PIN_FILE.name} must carry exactly one `release: none | <version>` line, found {len(releases)}.")
    elif releases[0] != "none" and int(releases[0]) not in {v for v, _m, f in migrations
                                                            if str(f.relative_to(ROOT)) in pinned}:
        errors.append(f"release: {releases[0]} names no pinned migration version.")

    # 5: module direction.
    owner: dict[str, tuple[int, str, int]] = {}  # table -> (version, module, statement index)
    parsed = []
    for version, module, f in sorted(migrations):
        stmts = statements(f)
        parsed.append((version, module, f, stmts))
        for i, s in enumerate(stmts):
            if c := CREATE_TABLE.search(s):
                owner.setdefault(c.group(1).lower(), (version, module, i))
    for version, module, f, stmts in parsed:
        rel = f.relative_to(ROOT)
        for i, s in enumerate(stmts):
            if module == CORE:
                for word in {w.lower() for w in WORD.findall(s)}:
                    if word in owner and owner[word][1] != CORE:
                        errors.append(f"{rel}: a core migration names `{word}`, a table of `{owner[word][1]}`. "
                                      f"Core never depends on an application (ADR 0079).")
                continue
            for ref in REFERENCES.findall(s):
                t = ref.lower()
                if t not in owner:
                    errors.append(f"{rel}: REFERENCES `{t}`, which no migration creates.")
                    continue
                o_version, o_module, o_index = owner[t]
                if o_module not in (CORE, module):
                    errors.append(f"{rel}: a `{module}` migration references `{t}` of `{o_module}`. An application "
                                  f"never depends on another; refer through a core object (ADR 0079 Decision 4).")
                elif (o_version, o_index) >= (version, i):
                    errors.append(f"{rel}: references `{t}` before it is created (version {o_version}).")

    if errors:
        print("MIGRATION VERSIONS VALIDATION FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1
    tables = {m: sum(1 for t in owner.values() if t[1] == m) for m in MODULES}
    print(f"OK: {len(migrations)} migration(s) in {len(dirs)} director(ies), every one pinned and in ascending "
          f"version order (release: {releases[0]}); tables by module {tables}, no core migration names an "
          f"application table, every application reference points at core or its own earlier table.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
