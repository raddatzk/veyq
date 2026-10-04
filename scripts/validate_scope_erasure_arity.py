#!/usr/bin/env python3
"""Guard that a group's hard-erasure can complete — every table reachable by foreign key from `veyq_scope` is
erased with it or cascades (uc.spaces.delete-group, ADR 0014 §6).

**What it asserts.** The transitive closure of foreign keys *below* `veyq_scope` is computed from the migrations,
and every table in it must be either named by `ScopePurge.eraseScope` **or by the `eraseScope` of any class
implementing `ScopeEraser`** (the erasure is a core list plus one step per owning package, cr-platform-core-
boundaries step 5), or carry `ON DELETE CASCADE`. A table that
is neither holds the scope row hostage: `eraseScope`'s final `ScopeEntity.deleteById` raises a constraint
violation, the transaction rolls back, and the group stays in pending-deletion forever with its bytes
unreclaimed — on the operator's own disk, under self-hosting.

The closure is read from three keys that are all cheap and all textual:

  - *edges* — every `REFERENCES <table>` in every migration root (`backend_tree.migrations()`), read in **numeric**
    migration (version) order, each attributed to the table of the `CREATE TABLE` / `ALTER TABLE` statement it
    sits in.
  - *closure* — a walk from `veyq_scope` down those edges. Transitive, not one-hop: a child of a child blocks the
    scope row just as hard as a direct child does.
  - *erased* — the entities deleted by `ScopePurge.eraseScope` and by every file declaring a class that
    implements `ScopeEraser`, unioned and resolved through their `@Table(name = …)` to table names, so
    the guard compares schema against schema and never against a Kotlin class name.

**The defect that bought it.** A live one, found 2026-08-17 and fixed with `cr-scope-erasure-fk-closure`:
`eraseScope` deleted eight tables and left **four** in its closure undeleted — `veyq_job`, `veyq_job_decline`, `veyq_share`,
`veyq_share_identity` — so every scheduled tick died on `FK_JOB_SCOPE` the moment one purgeable group had ever
enqueued a rotation job. Measured, not reasoned: `ScopesSliceTest.purgeErasesAGroupThatEverEnqueuedAJob` reddened
with `JdbcSQLIntegrityConstraintViolationException … delete from veyq_scope where id=?` against the untouched
tree. The four were found by this predicate, run before the fix existed; the same run was the discovery, the bite
and the fix-list.

**Its limit.** Five, and the first two are the shapes reached for first — both are the easier thing to write and
both are silently wrong, so they are recorded here rather than re-derived:

  (a) *One-hop* — "every table that `REFERENCES veyq_scope`". It reports **2** where the truth is 4, because
      `veyq_job_decline` and `veyq_share_identity` reference `veyq_job`/`veyq_share` and never the scope. A fix
      sized by that shape still throws, one table further down. The question has to be transitive.
  (b) *The table name as written at `CREATE TABLE`* is taken as the live name: a later `DROP TABLE` or
      `ALTER TABLE … RENAME TO` is not replayed. The baselines create every table under its live name, so none
      exists today; one added later leaves the old name in the closure, reported uncovered — red, never a false
      green — and that migration is the moment to teach this guard the replay again.
  (c) It reads **declarations, not a database**. A constraint added outside `db/migration`, or an `ON DELETE`
      clause wrapped onto a line of its own, is invisible — every such clause in this tree sits on its
      `REFERENCES` line today (`grep -rn 'ON DELETE' backend --include='*.sql'` → 4 hits, all
      inline), which is what makes the one-line assumption safe rather than lucky. `ON DELETE SET NULL` is
      deliberately **not** accepted as coverage: it stops the FK from refusing, and leaves a row pointing at an
      erased scope, which is the leak this use-case exists to close.
  (d) It proves each table is **named**, never that the deletes are correctly **ordered** or actually reached.
      Deleting `veyq_job` before `veyq_job_decline` names both and still throws at runtime; a delete behind a
      dead branch is named and never runs. That half is held by the slice tests in `ScopesSliceTest`, which
      execute the purge, not by any grep.
  (e) Eraser discovery is textual: a class is an eraser when a `class … : ScopeEraser` line appears in a file,
      and every `XEntity.delete(` after that file's first `fun eraseScope` counts. The declaration regex
      rejects a `=` or `{` anywhere before the `:` — a primary-constructor default argument or a lambda — and
      does not follow an abstract base class that implements the interface, so such an eraser is not
      discovered: the guard is red although the eraser exists (conservative, never a false green). An eraser
      the purge never collects (not a bean) is named and never runs — `ScopeEraserOrderSliceTest` and the
      purge slice tests, which execute it, hold that half, as in (d).
"""
from __future__ import annotations

import collections
import pathlib
import re
import sys

import backend_tree  # where the backend's sources live — resolved by glob, never spelled here

ROOT = pathlib.Path(__file__).resolve().parent.parent
PURGE = backend_tree.source_file("com.veyq.core.spaces.scope.ScopePurge")
ERASE_FN = "private fun eraseScope"
ERASER_FN = "fun eraseScope"
ERASER_CLASS = re.compile(r"^\s*(?:\w+\s+)*class\s+\w+[^{=]*:\s*(?:[\w.,\s]*,\s*)?ScopeEraser\b", re.MULTILINE)
ROOT_TABLE = "veyq_scope"

VERSION = re.compile(r"V(\d+)__")
SQL_COMMENT = re.compile(r"\s*--")
CREATE_TABLE = re.compile(r"CREATE TABLE\s+(?:IF NOT EXISTS\s+)?(\w+)", re.IGNORECASE)
ALTER_TABLE = re.compile(r"ALTER TABLE\s+(\w+)", re.IGNORECASE)
REFERENCES = re.compile(r"REFERENCES\s+(\w+)\s*\(", re.IGNORECASE)
ON_DELETE_CASCADE = re.compile(r"ON DELETE CASCADE", re.IGNORECASE)

# `XEntity.delete(…)` / `XEntity.deleteById(…)` — the Panache calls eraseScope erases rows with.
ERASED_ENTITY = re.compile(r"(\w+Entity)\.(?:delete|deleteById)\(")
# `@Table(name = "veyq_x")` … `class XEntity` — intervening annotations are skipped, the class must follow.
ENTITY_TABLE = re.compile(
    r'@Table\(name\s*=\s*"(\w+)"\)\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:open\s+)?class\s+(\w+)'
)


def _version(path: pathlib.Path) -> int:
    """Versions compare as numbers, as Flyway compares them — a string sort is right only while every version has
    the same number of digits."""
    match = VERSION.match(path.name)
    if not match:
        raise ValueError(f"migration outside the V<n>__ convention: {path.name}")
    return int(match.group(1))


def fk_edges() -> list[dict]:
    """Every foreign key in the migrations, attributed to the table whose statement declares it."""
    edges: list[dict] = []
    current = None
    for path in sorted(backend_tree.migrations(), key=_version):
        for lineno, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            if SQL_COMMENT.match(line):  # a comment naming a table is not a statement about one
                continue
            if match := CREATE_TABLE.search(line):
                current = match.group(1)
            if match := ALTER_TABLE.search(line):
                current = match.group(1)
            if match := REFERENCES.search(line):
                edges.append({
                    "child": current,
                    "parent": match.group(1),
                    "cascade": bool(ON_DELETE_CASCADE.search(line)),
                    "loc": f"{path.name}:{lineno}",
                })
    return edges


def closure(edges: list[dict]) -> list[dict]:
    """Every table reachable by FK from the root — transitive, because a child of a child blocks it too."""
    children = collections.defaultdict(list)
    for edge in edges:
        children[edge["parent"]].append(edge)
    seen: set[str] = set()
    reached: list[dict] = []
    stack = [ROOT_TABLE]
    while stack:
        for edge in children.get(stack.pop(), []):
            if edge["child"] not in seen:
                seen.add(edge["child"])
                reached.append(edge)
                stack.append(edge["child"])
    return reached


def erased_tables(errors: list[str]) -> set[str]:
    """The tables eraseScope deletes, resolved from entity class to its `@Table(name = …)`."""
    source = PURGE.read_text(encoding="utf-8")
    if ERASE_FN not in source:
        errors.append(
            f"{PURGE.relative_to(ROOT)} no longer declares `{ERASE_FN}`. This guard reads that function's body "
            f"to learn which tables the erasure covers; if it was renamed or split, re-point the guard at the "
            f"new shape — a guard that goes quiet because its subject moved proves nothing."
        )
        return set()

    entities = set(ERASED_ENTITY.findall(source.split(ERASE_FN, 1)[1]))
    erasers = 0
    for path in backend_tree.main_kotlin():
        text = path.read_text(encoding="utf-8")
        if ERASER_CLASS.search(text) and ERASER_FN in text:
            erasers += 1
            entities |= set(ERASED_ENTITY.findall(text.split(ERASER_FN, 1)[1]))
    if not erasers:
        errors.append(
            "no class implementing `ScopeEraser` with a `fun eraseScope` was found under backend main Kotlin. "
            "Media, favorites, nodes and shares are erased by those, so zero of them means the closure below "
            "would report their tables as uncovered for the wrong reason — or the discovery pattern drifted."
        )
    table_of: dict[str, str] = {}
    for path in backend_tree.main_kotlin():
        for match in ENTITY_TABLE.finditer(path.read_text(encoding="utf-8")):
            table_of[match.group(2)] = match.group(1)

    tables = set()
    for entity in sorted(entities):
        if entity in table_of:
            tables.add(table_of[entity])
        else:
            errors.append(
                f"the erasure deletes `{entity}`, but no `@Table(name = …)` in backend main Kotlin maps it to a "
                f"table. The closure below cannot tell whether that delete covers a blocking table, so it is "
                f"reported as uncovered rather than assumed away."
            )
    return tables


def main() -> int:
    errors: list[str] = []

    if not backend_tree.migrations() or not PURGE.is_file():
        print(
            "SCOPE ERASURE ARITY VALIDATION FAILED:\n"
            f"  - missing tree: no migration under {backend_tree.BACKEND}, or no {PURGE}. This guard reads backend sources directly; a moved "
            f"tree must be re-pointed, not silently skipped."
        )
        return 1

    reached = closure(fk_edges())
    erased = erased_tables(errors)

    if not reached:
        errors.append(
            f"no table references `{ROOT_TABLE}` at all. Either the root table was renamed — in which case this "
            f"guard is asking about a table that no longer exists — or the migration dialect changed shape. "
            f"An empty closure is vacuously green, which is why it is an error here."
        )

    blocking = sorted(
        (e for e in reached if e["child"] not in erased and not e["cascade"]),
        key=lambda e: e["child"],
    )
    for edge in blocking:
        errors.append(
            f"`{edge['child']}` (FK -> {edge['parent']}, {edge['loc']}) is reachable from {ROOT_TABLE} but is "
            f"neither deleted by ScopePurge.eraseScope or a ScopeEraser nor `ON DELETE CASCADE`. Its foreign key refuses the "
            f"scope row, so the hard-erasure of every group in the tick rolls back and no group is ever "
            f"reclaimed. Delete it in eraseScope or in the ScopeEraser of the package that owns it, child-first, or give the constraint a cascade."
        )

    if errors:
        print("SCOPE ERASURE ARITY VALIDATION FAILED:")
        for error in errors:
            print(f"  - {error}")
        return 1

    cascading = sum(1 for e in reached if e["cascade"])
    print(
        f"OK: all {len(reached)} tables in the FK closure below {ROOT_TABLE} are erased with it "
        f"({len(reached) - cascading} by ScopePurge.eraseScope / ScopeEraser, {cascading} by ON DELETE CASCADE)."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
