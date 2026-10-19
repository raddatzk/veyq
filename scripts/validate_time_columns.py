#!/usr/bin/env python3
"""Hold every stored time to `DATETIME`: no column the migrations leave behind is MariaDB's `TIMESTAMP` (inbox #509).

**What it asserts.** It replays every Flyway migration under `backend/` (`backend_tree.migrations()`) in version order —
`CREATE TABLE` bodies, `ALTER TABLE … ADD / MODIFY / CHANGE / DROP COLUMN / RENAME COLUMN / RENAME TO`, `RENAME TABLE`,
`DROP TABLE` — into the schema they build, and asserts:

  1. no column of that schema has the type `TIMESTAMP` or `TIMESTAMP(n)`. MariaDB's `TIMESTAMP` is a 32-bit count of
     seconds: on the pinned 11.4 it holds 1970-01-01T00:00:01Z to 2038-01-19T03:14:07Z, so a share's `expiresAt` in
     2577 failed its `UPDATE` (error 1292, a 500), and the server's own clock reaches the end in 2038. A time column is
     `DATETIME` or `DATETIME(n)`, holding the UTC wall clock Hibernate writes (`Instant`, bound with a UTC calendar) —
     so a migration that adds a `TIMESTAMP` column, or a conversion that missed one, is red here;
  2. the replay found time columns at all (more than zero `DATETIME`s) — a parser that lost the `CREATE TABLE` bodies
     would otherwise see no `TIMESTAMP` and pass.

**The defect that bought it.** Inbox #509: the API fuzzing's positive writes (#495) sent a share an `expiresAt` in 2577,
and the `UPDATE` of the `TIMESTAMP` column failed with a 500; every time column was converted to `DATETIME` by
`V202610192230__core_time_columns_are_datetime.sql` and `V202610192231__memories_time_columns_are_datetime.sql`.

**Its limit.** (a) It reads *declarations, not a database*: a column made through dynamic SQL, a `CREATE TABLE … AS
SELECT` (its columns are not declared; the one such table today is dropped in the same file), or a type named through
a domain or a `SET` variable is not seen — the engine's own answer is `StoredTimeSliceTest`, which reads
`INFORMATION_SCHEMA` on MariaDB (`scripts/run_backend_races_on_mariadb.py`). Flyway's own `flyway_schema_history`
is no migration's: its `installed_on` stays the `TIMESTAMP` Flyway declares, and neither check reads it. (b) It does not check that a column holds
UTC: that is the writer's (Hibernate's `Instant` binding) and the H2 test URL's `TIME ZONE=UTC`, held by the same
test. (c) It does not check precision: a plain `DATETIME` keeps whole seconds on purpose (what the server stores, to
the second, is a published statement), and a column that orders rows needs `DATETIME(3)` — `backend/CLAUDE.md`
§ Migrations says which.
"""
from __future__ import annotations

import pathlib
import re
import sys

import backend_tree  # where the backend's sources live — resolved by glob, never spelled here

ROOT = pathlib.Path(__file__).resolve().parent.parent
VERSION = re.compile(r"^V(\d+)__")
BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.DOTALL)
LINE_COMMENT = re.compile(r"--[^\n]*")
CREATE = re.compile(r"^CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`?(\w+)`?\s*(.*)$", re.IGNORECASE | re.DOTALL)
ALTER = re.compile(r"^ALTER\s+TABLE\s+`?(\w+)`?\s+(.*)$", re.IGNORECASE | re.DOTALL)
DROP = re.compile(r"^DROP\s+TABLE\s+(?:IF\s+EXISTS\s+)?(.*)$", re.IGNORECASE | re.DOTALL)
RENAME_TABLE = re.compile(r"^RENAME\s+TABLE\s+(.*)$", re.IGNORECASE | re.DOTALL)
# A body element that is no column: a table constraint or an index.
NOT_A_COLUMN = re.compile(r"^(CONSTRAINT|PRIMARY|UNIQUE|KEY|INDEX|FOREIGN|CHECK|FULLTEXT|SPATIAL)\b", re.IGNORECASE)
ADD = re.compile(r"^ADD\s+(?:COLUMN\s+)?(?:IF\s+NOT\s+EXISTS\s+)?`?(\w+)`?\s+(.+)$", re.IGNORECASE | re.DOTALL)
MODIFY = re.compile(r"^MODIFY\s+(?:COLUMN\s+)?(?:IF\s+EXISTS\s+)?`?(\w+)`?\s+(.+)$", re.IGNORECASE | re.DOTALL)
CHANGE = re.compile(r"^CHANGE\s+(?:COLUMN\s+)?(?:IF\s+EXISTS\s+)?`?(\w+)`?\s+`?(\w+)`?\s+(.+)$", re.IGNORECASE | re.DOTALL)
DROP_COLUMN = re.compile(r"^DROP\s+(?:COLUMN\s+)?(?:IF\s+EXISTS\s+)?`?(\w+)`?\s*$", re.IGNORECASE)
RENAME_COLUMN = re.compile(r"^RENAME\s+COLUMN\s+`?(\w+)`?\s+TO\s+`?(\w+)`?\s*$", re.IGNORECASE)
RENAME_TO = re.compile(r"^RENAME\s+(?:TO|AS)\s+`?(\w+)`?\s*$", re.IGNORECASE)
# What ADD/DROP may name that is not a column.
NOT_A_COLUMN_SPEC = re.compile(r"^(?:ADD|DROP)\s+(CONSTRAINT|PRIMARY|UNIQUE|KEY|INDEX|FOREIGN|CHECK)\b", re.IGNORECASE)
TIME_TYPE = re.compile(r"^(TIMESTAMP|DATETIME)\b", re.IGNORECASE)


def statements(path: pathlib.Path) -> list[str]:
    text = LINE_COMMENT.sub("", BLOCK_COMMENT.sub("", path.read_text(encoding="utf-8")))
    return [" ".join(s.split()) for s in text.split(";") if s.strip()]


def top_level(text: str) -> list[str]:
    """`text` split at the commas outside parentheses and quotes."""
    parts, depth, quote, start = [], 0, None, 0
    for i, ch in enumerate(text):
        if quote:
            quote = None if ch == quote else quote
        elif ch in "'\"`":
            quote = ch
        elif ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        elif ch == "," and depth == 0:
            parts.append(text[start:i].strip())
            start = i + 1
    parts.append(text[start:].strip())
    return [p for p in parts if p]


def body(rest: str) -> str | None:
    """The parenthesised column list that opens `rest`, or None for `CREATE TABLE … AS SELECT`."""
    if not rest.startswith("("):
        return None
    depth = 0
    for i, ch in enumerate(rest):
        depth += ch == "("
        depth -= ch == ")"
        if depth == 0:
            return rest[1:i]
    return rest[1:]


def replay(files: list[pathlib.Path]) -> dict[str, dict[str, tuple[str, str]]]:
    """table -> column -> (its type and options as last declared, the file that declared them)."""
    schema: dict[str, dict[str, tuple[str, str]]] = {}
    for f in sorted(files, key=lambda p: int(VERSION.match(p.name).group(1)) if VERSION.match(p.name) else 0):
        where = str(f.relative_to(ROOT))
        for s in statements(f):
            if m := CREATE.match(s):
                table, columns = m.group(1).lower(), {}
                cols = body(m.group(2).strip())
                for element in top_level(cols) if cols is not None else []:
                    if not NOT_A_COLUMN.match(element):
                        name, _, definition = element.partition(" ")
                        columns[name.strip("`").lower()] = (definition, where)
                schema[table] = columns
            elif m := ALTER.match(s):
                table = m.group(1).lower()
                columns = schema.setdefault(table, {})
                for spec in top_level(m.group(2)):
                    if NOT_A_COLUMN_SPEC.match(spec):
                        continue
                    if c := ADD.match(spec):
                        columns[c.group(1).lower()] = (c.group(2), where)
                    elif c := MODIFY.match(spec):
                        columns[c.group(1).lower()] = (c.group(2), where)
                    elif c := CHANGE.match(spec):
                        columns.pop(c.group(1).lower(), None)
                        columns[c.group(2).lower()] = (c.group(3), where)
                    elif c := DROP_COLUMN.match(spec):
                        columns.pop(c.group(1).lower(), None)
                    elif c := RENAME_COLUMN.match(spec):
                        if c.group(1).lower() in columns:
                            columns[c.group(2).lower()] = columns.pop(c.group(1).lower())
                    elif c := RENAME_TO.match(spec):
                        schema[c.group(1).lower()] = schema.pop(table)
                        table, columns = c.group(1).lower(), schema[c.group(1).lower()]
            elif m := DROP.match(s):
                for name in top_level(m.group(1)):
                    schema.pop(name.strip("` ").lower(), None)
            elif m := RENAME_TABLE.match(s):
                for pair in top_level(m.group(1)):
                    parts = re.split(r"\s+TO\s+", pair, maxsplit=1, flags=re.IGNORECASE)
                    if len(parts) == 2 and parts[0].strip("` ").lower() in schema:
                        schema[parts[1].strip("` ").lower()] = schema.pop(parts[0].strip("` ").lower())
    return schema


def main() -> int:
    files = backend_tree.migrations()
    if not files:
        print("TIME COLUMNS VALIDATION FAILED:\n  - no migration found under backend/ — a moved tree must be re-pointed, "
              "not silently skipped.")
        return 1
    schema = replay(files)
    errors, datetimes = [], 0
    for table in sorted(schema):
        for column, (definition, where) in sorted(schema[table].items()):
            t = TIME_TYPE.match(definition)
            if not t:
                continue
            if t.group(1).upper() == "DATETIME":
                datetimes += 1
            else:
                errors.append(f"{table}.{column} is `{definition}` (last declared in {where}): a {t.group(1).upper()} "
                              f"column ends at 2038-01-19T03:14:07Z on MariaDB — declare it DATETIME (or DATETIME(3) "
                              f"where it orders rows), which holds the UTC wall clock Hibernate writes.")
    if not datetimes:
        errors.append("the replay found no DATETIME column at all — the migrations' CREATE TABLE bodies were not read, "
                      "so a green here would mean nothing.")
    if errors:
        print("TIME COLUMNS VALIDATION FAILED:\n" + "\n".join(f"  - {e}" for e in errors))
        return 1
    print(f"time columns OK: {datetimes} DATETIME columns, no TIMESTAMP column, over {len(files)} migrations.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
