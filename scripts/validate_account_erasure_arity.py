#!/usr/bin/env python3
"""Guard that an account's and a tenant's erasure can complete, and that nothing keyed on an account is forgotten
(uc.account.erase-account, uc.admin.delete-tenant, ADR 0101 decision 11) — the twin of
`validate_scope_erasure_arity.py`, whose edge reader it reuses.

**What it asserts.** Three things, all read from the tree on every run:

  1. *The account closure.* Every table reachable by foreign key from `veyq_user` (transitive, from the migrations,
     with a later `DROP FOREIGN KEY <name>` / `DROP CONSTRAINT <name>` replayed) is deleted by `AccountPurge.eraseAccount`
     or by the `eraseAccount` of a class implementing `AccountEraser` (an application's own tables), or carries
     `ON DELETE CASCADE`. A table that is neither refuses the final `VeyqUser` delete: the purge rolls back and the
     account is never erased — a legal duty (GDPR Art. 17) silently unmet.
  2. *The tenant closure.* The same for `veyq_tenant` and `TenantPurge.eraseTenant`, with one delegation: the tables at
     or below `veyq_scope` are the scope erasure's (held by `validate_scope_erasure_arity.py`), so they count as covered
     exactly while `eraseTenant` calls it (`scopePurge.purge(`).
  3. *The second angle — account ids without a key.* Every column in the migrations whose name says it carries an
     account id (`…user_id`, `…account_id`, `…_account`, `…_by`, `personal_owner`, `owner_key`, `lease_holder`,
     `worker_id`, `moderator_id`, `actor_id`, `recipient_id`, `target_id`) and that is not a foreign key into the
     account closure is classified in the erasers' code — an entry `"table.column" to ERASED` or `"table.column" to
     kept("reason")` in `AccountPurge` or an `AccountEraser` — and every entry names a column that exists. A kept entry
     carries its reason (content attribution, a moderation record the law keeps).

**The defect that bought it.** No incident: built with `cr-account-erasure` because the legal audit of 2026-10-18 found
that nothing erased an account at all, and twelve tables referenced `veyq_user` with no `ON DELETE` while a further
set of columns named accounts without a key on purpose (editors, uploaders, bucket owners, moderation actors). The
scope erasure's own guard was bought by a live defect of exactly the first kind (four tables missing, every tick dead),
so the twin was written before the eraser, and run red on the tree without it.

**Its limit.** Five:

  (a) It proves each table is **named** and each column **classified**, never that the deletes run in a working order,
      that a conditional delete (the terms acceptance rows a moderation case keeps) chooses right, or that an `ERASED`
      column is really cleared — `AccountErasureSliceTest` and `TenantDeletionSliceTest` execute the purges for that.
  (b) The second angle sees a column only under one of the spellings above. A column that names an account under any
      other name (`subject`, `who`) is invisible to it; the spellings came from the refine's locator plus the moderation
      tables' `moderator_id`, `actor_id`, `recipient_id` and `target_id`, which the first locator missed.
  (c) The polymorphic columns (`target_id`, `recipient_id`, `owner_key`, `created_by`) are classified as a whole: the
      guard cannot tell the rows that hold an account id from those that hold a share or node id.
  (d) Eraser discovery is textual, as the scope guard's limit (e) says: a `class … : AccountEraser` line and every
      `XEntity.delete(` after its first `fun eraseAccount`. A delete through a store method or an `update` is not seen
      as one — so a table cleared that way is reported uncovered (red, never a false green).
  (e) It reads declarations, not a database (the scope guard's (c)): a constraint added outside `db/migration` is
      invisible, and a `DROP` is replayed only by the constraint name the `CONSTRAINT <name> FOREIGN KEY` clause gave.
"""
from __future__ import annotations

import collections
import pathlib
import re
import sys

import backend_tree  # where the backend's sources live — resolved by glob, never spelled here
import validate_scope_erasure_arity as scope_guard

ROOT = pathlib.Path(__file__).resolve().parent.parent

ACCOUNT_ROOT = "veyq_user"
TENANT_ROOT = "veyq_tenant"
SCOPE_ROOT = "veyq_scope"

ACCOUNT_PURGE = re.compile(r"^\s*(?:\w+\s+)*class\s+AccountPurge\b", re.MULTILINE)
TENANT_PURGE = re.compile(r"^\s*(?:\w+\s+)*class\s+TenantPurge\b", re.MULTILINE)
ACCOUNT_ERASER = re.compile(r"^\s*(?:\w+\s+)*class\s+\w+[^{=]*:\s*(?:[\w.,\s]*,\s*)?AccountEraser\b", re.MULTILINE)
ACCOUNT_FN = "fun eraseAccount"
TENANT_FN = "fun eraseTenant"
SCOPE_DELEGATION = "scopePurge.purge("

CONSTRAINT_FK = re.compile(r"CONSTRAINT\s+(\w+)\s+FOREIGN KEY", re.IGNORECASE)
FK_COLUMN = re.compile(r"FOREIGN KEY\s*\((\w+)\)\s*REFERENCES\s+(\w+)", re.IGNORECASE)
DROP_FK = re.compile(r"DROP\s+(?:FOREIGN KEY|CONSTRAINT)\s+(\w+)", re.IGNORECASE)
COLUMN = re.compile(r"^\s+(\w+)\s+(?:UUID|VARCHAR\(\d+\))(?=[\s,]|$)", re.IGNORECASE)
ADD_COLUMN = re.compile(r"ALTER TABLE\s+(\w+)\s+ADD COLUMN\s+(\w+)\s+(?:UUID|VARCHAR\(\d+\))", re.IGNORECASE)
NOT_A_COLUMN = {"CONSTRAINT", "PRIMARY", "UNIQUE", "KEY", "INDEX", "FOREIGN", "CHECK"}

ACCOUNT_SUFFIXES = ("user_id", "account_id", "_account", "_by")
ACCOUNT_NAMES = {"personal_owner", "owner_key", "lease_holder", "worker_id", "moderator_id", "actor_id",
                 "recipient_id", "target_id"}

FATE = re.compile(r'"(veyq_\w+)\.(\w+)"\s+to\s+(?:ERASED\b|kept\(\s*"([^"]*)"\s*\))')


def says_account(column: str) -> bool:
    name = column.lower()
    return name in ACCOUNT_NAMES or name.endswith(ACCOUNT_SUFFIXES)


def schema() -> tuple[list[dict], dict[tuple[str, str], str]]:
    """The live foreign keys (a dropped constraint replayed) and every UUID/VARCHAR column, by (table, column)."""
    edges: list[dict] = []
    columns: dict[tuple[str, str], str] = {}
    current = None
    for path in sorted(backend_tree.migrations(), key=scope_guard._version):
        for lineno, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            if scope_guard.SQL_COMMENT.match(line):
                continue
            loc = f"{path.name}:{lineno}"
            if match := scope_guard.CREATE_TABLE.search(line):
                current = match.group(1)
            if match := scope_guard.ALTER_TABLE.search(line):
                current = match.group(1)
            if match := ADD_COLUMN.search(line):
                columns[(match.group(1), match.group(2))] = loc
            elif current and (match := COLUMN.match(line)) and match.group(1).upper() not in NOT_A_COLUMN:
                columns[(current, match.group(1))] = loc
            if match := DROP_FK.search(line):
                edges = [e for e in edges if e["name"] != match.group(1)]
            if match := scope_guard.REFERENCES.search(line):
                name = CONSTRAINT_FK.search(line)
                column = FK_COLUMN.search(line)
                edges.append({
                    "child": current,
                    "parent": match.group(1),
                    "column": column.group(1) if column else None,
                    "name": name.group(1) if name else None,
                    "cascade": bool(scope_guard.ON_DELETE_CASCADE.search(line)),
                    "loc": loc,
                })
    return edges, columns


def closure(edges: list[dict], root: str) -> list[dict]:
    children = collections.defaultdict(list)
    for edge in edges:
        children[edge["parent"]].append(edge)
    seen: set[str] = set()
    reached: list[dict] = []
    stack = [root]
    while stack:
        for edge in children.get(stack.pop(), []):
            if edge["child"] not in seen:
                seen.add(edge["child"])
                reached.append(edge)
                stack.append(edge["child"])
    return reached


def entity_tables() -> dict[str, str]:
    table_of: dict[str, str] = {}
    for path in backend_tree.main_kotlin():
        for match in scope_guard.ENTITY_TABLE.finditer(path.read_text(encoding="utf-8")):
            table_of[match.group(2)] = match.group(1)
    return table_of


def erased_by(files: list[tuple[pathlib.Path, str]], table_of: dict[str, str], errors: list[str]) -> set[str]:
    """The tables the `fn` body of each file deletes, resolved through `@Table(name = …)`."""
    tables: set[str] = set()
    for path, fn in files:
        text = path.read_text(encoding="utf-8")
        for entity in sorted(set(scope_guard.ERASED_ENTITY.findall(text.split(fn, 1)[1]))):
            if entity in table_of:
                tables.add(table_of[entity])
            else:
                errors.append(f"{path.relative_to(ROOT)} deletes `{entity}`, but no `@Table(name = …)` maps it to a table.")
    return tables


def main() -> int:
    errors: list[str] = []
    if not backend_tree.migrations():
        print(f"ACCOUNT ERASURE ARITY VALIDATION FAILED:\n  - no migration under {backend_tree.BACKEND}; re-point the guard.")
        return 1

    edges, columns = schema()
    table_of = entity_tables()

    account_files: list[tuple[pathlib.Path, str]] = []
    tenant_files: list[tuple[pathlib.Path, str]] = []
    fate_sources: list[pathlib.Path] = []
    for path in backend_tree.main_kotlin():
        text = path.read_text(encoding="utf-8")
        if (ACCOUNT_PURGE.search(text) or ACCOUNT_ERASER.search(text)) and ACCOUNT_FN in text:
            account_files.append((path, ACCOUNT_FN))
            fate_sources.append(path)
        if TENANT_PURGE.search(text) and TENANT_FN in text:
            tenant_files.append((path, TENANT_FN))
    if not any(ACCOUNT_PURGE.search(p.read_text(encoding="utf-8")) for p, _ in account_files):
        errors.append(
            "no `class AccountPurge` with a `fun eraseAccount` in backend main Kotlin: nothing erases an account "
            "(ADR 0101). Every table of the account closure below is reported uncovered for that reason."
        )
    if not tenant_files:
        errors.append(
            "no `class TenantPurge` with a `fun eraseTenant` in backend main Kotlin: nothing erases an active tenant "
            "(ADR 0101 decision 10). Every table of the tenant closure below is reported uncovered for that reason."
        )

    # 1. The account closure.
    account_reached = closure(edges, ACCOUNT_ROOT)
    account_erased = erased_by(account_files, table_of, errors)
    for edge in sorted(account_reached, key=lambda e: e["child"]):
        if edge["child"] not in account_erased and not edge["cascade"]:
            errors.append(
                f"`{edge['child']}` (FK -> {edge['parent']}, {edge['loc']}) is reachable from {ACCOUNT_ROOT} but is "
                f"neither deleted by AccountPurge.eraseAccount or an AccountEraser nor `ON DELETE CASCADE`: its key "
                f"refuses the account row, and the purge rolls back."
            )

    # 2. The tenant closure, the scope erasure's part delegated while eraseTenant calls it.
    delegates = any(SCOPE_DELEGATION in path.read_text(encoding="utf-8").split(TENANT_FN, 1)[1] for path, _ in tenant_files)
    scope_owned = {SCOPE_ROOT} | {e["child"] for e in closure(edges, SCOPE_ROOT)}
    tenant_erased = erased_by(tenant_files, table_of, errors)
    if tenant_files and not delegates:
        errors.append(
            f"TenantPurge.eraseTenant does not call `{SCOPE_DELEGATION}`: the tenant's scopes and everything below "
            f"them would block the tenant row, and the scope erasure is what reclaims their blobs."
        )
    for edge in sorted(closure(edges, TENANT_ROOT), key=lambda e: e["child"]):
        covered = edge["child"] in tenant_erased or edge["cascade"] or (delegates and edge["child"] in scope_owned)
        if not covered:
            errors.append(
                f"`{edge['child']}` (FK -> {edge['parent']}, {edge['loc']}) is reachable from {TENANT_ROOT} but is "
                f"neither deleted by TenantPurge.eraseTenant, nor the scope erasure's, nor `ON DELETE CASCADE`."
            )

    # 3. The second angle: account ids without a key, classified in the erasers' code.
    keyed_into_account = {ACCOUNT_ROOT} | {e["child"] for e in account_reached}
    fk_columns = {(e["child"], e["column"]) for e in edges if e["parent"] in keyed_into_account}
    fates: dict[tuple[str, str], str | None] = {}
    for path in fate_sources:
        for match in FATE.finditer(path.read_text(encoding="utf-8")):
            key = (match.group(1), match.group(2))
            reason = match.group(3)
            if reason is not None and not reason.strip():
                errors.append(f"{path.relative_to(ROOT)}: `{key[0]}.{key[1]}` is kept with an empty reason.")
            fates[key] = reason
    for key in sorted(fates):
        if key not in columns:
            errors.append(f"the erasers classify `{key[0]}.{key[1]}`, which no migration declares: a stale entry.")
    for (table, column), loc in sorted(columns.items()):
        if not says_account(column) or (table, column) in fk_columns:
            continue
        if (table, column) not in fates:
            errors.append(
                f"`{table}.{column}` ({loc}) names an account without a foreign key and is classified nowhere: add "
                f"`\"{table}.{column}\" to ERASED` or `to kept(\"why\")` to AccountPurge or the AccountEraser of the "
                f"module that owns it (ADR 0101 decision 3)."
            )

    if errors:
        print("ACCOUNT ERASURE ARITY VALIDATION FAILED:")
        for error in errors:
            print(f"  - {error}")
        return 1
    kept = sum(1 for r in fates.values() if r is not None)
    print(
        f"OK: {len(account_reached)} table(s) below {ACCOUNT_ROOT} and the tables below {TENANT_ROOT} are erased or "
        f"cascade; {len(fates)} account-id column(s) without a key classified ({kept} kept, {len(fates) - kept} erased)."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
