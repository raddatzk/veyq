"""Refine-time probe for `cr-scope-erasure-fk-closure` — NOT a guard, and deliberately not named `validate_*`.

CI loops over `scripts/validate_*.py`, so that name is the registry; this file carries the *predicate* the CR's
guard will ship, run against the tree before the guard was written (`changes/README.md` § run a new guard's
predicate against the UNTOUCHED tree). It lives here, next to the CR, and is deleted with it: once
`scripts/validate_scope_erasure_arity.py` exists, this is a second copy of the same rule and rule 2 (never
restate the canonical source) applies.

It answers one question: **which tables can block the erasure of a scope?** Every table reachable by foreign key
*from* `veyq_scope` must be either named by `ScopePurge.eraseScope` or carry `ON DELETE CASCADE`; anything else
holds the scope row hostage and the purge can never complete.

Output at `0bd3241` (the run that corrected the inbox row from two arms to four):

    live FK closure from veyq_scope (12)  ->  8 deleted, 1 CASCADE, 4 MISSING
      veyq_job             FK -> veyq_scope   V20__background_jobs.sql:26
      veyq_job_decline     FK -> veyq_job     V30__job_decline.sql:14
      veyq_share           FK -> veyq_scope   V31__share.sql:20
      veyq_share_identity  FK -> veyq_share   V32__contributor_share.sql:13

**Two predicate shapes were tried first and both failed silently — carry them into the guard's docstring as its
limit, because the next reader reaches for the same two.**

1. *One-hop* — "every `REFERENCES veyq_scope`". Misses `veyq_job_decline` and `veyq_share_identity` entirely,
   because they reference `veyq_job`/`veyq_share` rather than the scope. It reports 2 and would have let the CR
   land a fix that still throws, one table further down. The question has to be transitive.
2. *Table name as written at `CREATE TABLE`*. V9 and V21 rebuild `veyq_membership` via a fresh table plus
   `RENAME TO` (the portable H2 ∩ MariaDB way to change a column), so this shape reports `veyq_membership_new`
   and `veyq_membership_v21` as two uncovered tables that do not exist — while the live `veyq_membership`, which
   `eraseScope` does delete, never appears at all. The replay of `DROP TABLE` / `ALTER TABLE … RENAME TO` below
   is what fixes it, and it must run in **numeric** migration order: sorted as strings, V9 comes after V10.

`from __future__ import annotations` is required — python3 on the author's machine is 3.9.6.
"""
from __future__ import annotations

import collections
import glob
import os
import re

MIG = "backend/src/main/resources/db/migration"
PURGE = "backend/src/main/kotlin/com/veyq/app/features/spaces/scope/ScopePurge.kt"
ROOT = "veyq_scope"


def _version(path: str) -> int:
    """V9 sorts before V10 — numerically, which string sort gets wrong."""
    return int(re.match(r"V(\d+)__", os.path.basename(path)).group(1))


def fk_edges() -> list[dict]:
    """Every foreign key in the migrations, replayed to the LIVE table names (drops + renames applied)."""
    edges: list[dict] = []
    current = None
    for path in sorted(glob.glob(os.path.join(MIG, "*.sql")), key=_version):
        for n, line in enumerate(open(path, encoding="utf-8"), 1):
            if re.match(r"\s*--", line):  # a comment naming a table is not a statement about one
                continue
            if m := re.search(r"CREATE TABLE\s+(?:IF NOT EXISTS\s+)?(\w+)", line, re.I):
                current = m.group(1)
            if m := re.search(r"ALTER TABLE\s+(\w+)", line, re.I):
                current = m.group(1)
            if m := re.search(r"REFERENCES\s+(\w+)\s*\(", line, re.I):
                edges.append({
                    "child": current,
                    "parent": m.group(1),
                    "cascade": bool(re.search(r"ON DELETE CASCADE", line, re.I)),
                    "loc": f"{os.path.basename(path)}:{n}",
                })
            if m := re.search(r"DROP TABLE\s+(?:IF EXISTS\s+)?(\w+)", line, re.I):
                gone = m.group(1)
                edges = [e for e in edges if e["child"] != gone]
            if m := re.search(r"ALTER TABLE\s+(\w+)\s+RENAME TO\s+(\w+)", line, re.I):
                old, new = m.group(1), m.group(2)
                for e in edges:
                    if e["child"] == old:
                        e["child"] = new
                    if e["parent"] == old:
                        e["parent"] = new
    return edges


def closure(edges: list[dict]) -> list[dict]:
    """Transitive, not one-hop: a child of a child still blocks the scope row."""
    children = collections.defaultdict(list)
    for e in edges:
        children[e["parent"]].append(e)
    seen, order, stack = set(), [], [ROOT]
    while stack:
        for e in children.get(stack.pop(), []):
            if e["child"] not in seen:
                seen.add(e["child"])
                order.append(e)
                stack.append(e["child"])
    return order


def erased_tables() -> set[str]:
    """The tables `eraseScope` deletes, resolved from entity class -> @Table(name = ...)."""
    body = open(PURGE, encoding="utf-8").read().split("private fun eraseScope")[1]
    entities = set(re.findall(r"(\w+Entity)\.(?:delete|deleteById)\(", body))
    table_of = {}
    for path in glob.glob("backend/src/main/kotlin/**/*.kt", recursive=True):
        text = open(path, encoding="utf-8").read()
        for m in re.finditer(
            r'@Table\(name\s*=\s*"(\w+)"\)\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:open\s+)?class\s+(\w+)', text
        ):
            table_of[m.group(2)] = m.group(1)
    return {table_of.get(e, "?" + e) for e in entities}


def main() -> int:
    reachable = closure(fk_edges())
    erased = erased_tables()

    print(f"live FK closure from {ROOT} ({len(reachable)} tables):")
    for e in sorted(reachable, key=lambda e: e["child"]):
        verdict = "deleted" if e["child"] in erased else ("CASCADE" if e["cascade"] else "*** MISSING ***")
        print("  %-22s <- %-16s %-16s %s" % (e["child"], e["parent"], verdict, e["loc"]))

    missing = [e for e in reachable if e["child"] not in erased and not e["cascade"]]
    print(f"\nblocking the erasure ({len(missing)}):")
    for e in sorted(missing, key=lambda e: e["child"]):
        print("  %-22s FK -> %-14s %s" % (e["child"], e["parent"], e["loc"]))
    return 1 if missing else 0


if __name__ == "__main__":
    raise SystemExit(main())
