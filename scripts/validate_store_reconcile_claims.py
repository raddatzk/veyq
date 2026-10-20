#!/usr/bin/env python3
"""Guard that the bucket reconcile's claim set is every table that keeps a storage key alive (ADR 0062 § Consequences).

**What it asserts.** The set of tables the migrations give a `storage_key` column — read from every migration under
`src/backend/` in version order: a `CREATE TABLE` whose column list names `storage_key`, an `ALTER TABLE … ADD [COLUMN]
storage_key`, and a later `DROP TABLE` or `DROP COLUMN storage_key` taking one away — minus the reconcile's own
`veyq_store_orphan`, equals the `CLAIM_TABLES` list of the one Kotlin file in `src/backend/**/src/main/kotlin` that
declares it (`StoreReconcileStore`). Both ways: a table missing from the list is red, and so is a listed table that
no migration gives a `storage_key` (the claim query would fail at run time, on MariaDB first). The exemption must
itself be a table a migration creates with that column, or the guard is red on a stale exemption.

The derivation is from the migrations, never from a list here (changes/README.md § A guard derives its anchor set
from the code): the case it exists for is the table added after it was written.

**The defect that bought it.** No incident — built with cr-bucket-reconcile because the owner's answer to its O1
made the reconcile delete: a key no claim row names, in every pass for the grace, goes onto the reclaim list. A
table that keeps a key alive and is not asked (the planned `veyq_hold_pin` of cr-report-and-hold is the one in sight)
turns from a wrong count into lost media after thirty days, silently — the hermetic suite seeds only the tables the
claim query already reads, so no test would notice.

**Its limit.** (a) It reads the column by its name: a table that keeps a key alive under another column name
(`object_key`, a JSON field) is invisible to it, as it is to the claim query. Every table today calls it
`storage_key`, and `BlobStore.chunkKey` names the prefix that. (b) It proves the claim query NAMES each table, never
that it reads it correctly — that a row's `chunk_count` explains an object's index is `StoreReconcileSliceTest`'s.
(c) Its SQL reading is a regex over statements, not a parser; a column added by a form it does not know (a `CHANGE
COLUMN` rename to `storage_key`) is missed, which is why it parses nothing it cannot name and says so here.
"""
from __future__ import annotations

import pathlib
import re
import sys

import backend_tree  # where the backend's sources live — resolved by glob, never spelled here

ROOT = pathlib.Path(__file__).resolve().parent.parent

COLUMN = "storage_key"
# The reconcile's own record of what NO row claims: a claim by it would make every orphan explained.
OWN_TABLES = {"veyq_store_orphan"}

CREATE = re.compile(r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`?(\w+)`?\s*\((.*?)\)\s*;", re.I | re.S)
ADD = re.compile(r"ALTER\s+TABLE\s+`?(\w+)`?\s+ADD\s+(?:COLUMN\s+)?`?(\w+)`?", re.I)
DROP_TABLE = re.compile(r"DROP\s+TABLE\s+(?:IF\s+EXISTS\s+)?`?(\w+)`?", re.I)
DROP_COLUMN = re.compile(r"ALTER\s+TABLE\s+`?(\w+)`?\s+DROP\s+(?:COLUMN\s+)?`?(\w+)`?", re.I)
DECLARATION = re.compile(r"CLAIM_TABLES\s*=\s*listOf\(([^)]*)\)", re.S)


def strip_comments(sql: str) -> str:
    return re.sub(r"--[^\n]*", "", sql)


def tables_with_column() -> set[str]:
    """Every table the migrations, applied in version order, leave with a `storage_key` column."""
    holders: set[str] = set()
    for path in sorted(backend_tree.migrations(), key=lambda p: p.name):
        sql = strip_comments(path.read_text())
        events: list[tuple[int, str, str]] = []
        for m in CREATE.finditer(sql):
            if re.search(rf"(^|[\s(,]){COLUMN}\s", m.group(2), re.I):
                events.append((m.start(), "add", m.group(1).lower()))
        for m in ADD.finditer(sql):
            if m.group(2).lower() == COLUMN:
                events.append((m.start(), "add", m.group(1).lower()))
        for m in DROP_TABLE.finditer(sql):
            events.append((m.start(), "drop", m.group(1).lower()))
        for m in DROP_COLUMN.finditer(sql):
            if m.group(2).lower() == COLUMN:
                events.append((m.start(), "drop", m.group(1).lower()))
        for _, kind, table in sorted(events):
            (holders.add if kind == "add" else holders.discard)(table)
    return holders


def claim_tables() -> tuple[list[str], list[str]]:
    """(the declared list, the files that declare one) — exactly one file must."""
    found: list[tuple[pathlib.Path, list[str]]] = []
    for path in backend_tree.main_kotlin():
        text = path.read_text()
        for m in DECLARATION.finditer(text):
            found.append((path, re.findall(r'"(\w+)"', m.group(1))))
    return (found[0][1] if len(found) == 1 else []), [p.relative_to(ROOT).as_posix() for p, _ in found]


def main() -> int:
    errors: list[str] = []
    holders = tables_with_column()
    if not holders:
        errors.append("no migration gives any table a storage_key column — is the migration glob still right?")
    declared, where = claim_tables()
    if len(where) != 1:
        errors.append(f"expected exactly one CLAIM_TABLES = listOf(…) in the backend's main Kotlin, found {where or 'none'}")
    for own in sorted(OWN_TABLES - holders):
        errors.append(f"the exemption {own} names no table a migration gives a {COLUMN} column — strike it")
    wanted = holders - OWN_TABLES
    for table in sorted(wanted - set(declared)):
        errors.append(f"{table} has a {COLUMN} column and is not in CLAIM_TABLES ({where[0] if where else '?'}): the "
                      f"reconcile would read its keys as unclaimed and put them on the reclaim list after the grace")
    for table in sorted(set(declared) - wanted):
        errors.append(f"CLAIM_TABLES names {table}, which no migration gives a {COLUMN} column")
    if errors:
        print(f"STORE-RECONCILE CLAIMS VALIDATION FAILED ({len(errors)}):")
        for e in errors:
            print(f"  - {e}")
        return 1
    print(f"store-reconcile claims OK: CLAIM_TABLES names every table with a {COLUMN} column "
          f"({', '.join(sorted(wanted))}; {', '.join(sorted(OWN_TABLES))} is the reconcile's own)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
