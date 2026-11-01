#!/usr/bin/env python3
"""Guard the number of places that reference a `veyq_blob` row — and that the one predicate over them sees all of them (ADR 0013).

**What it asserts.** Exactly **four arms** point at a blob row, and this pins each by name from four
independent keys:

  - *entity* — the Kotlin `@Column` declarations: `MediaEntity.blob_id`, `MediaDerivativeEntity.blob_id`,
    `NodeAttachmentEntity.blob_id`, `ApplicationBlobEntity.blob_id` (core's blob service for the blobs an application
    stores — Expenses' receipts, ADR 0112 — the arm every application to come stores through, so the count stays four).
  - *schema* — the SQL declarations of the same four columns, keyed `<table>.<column>` by the statement that
    declares them (`CREATE TABLE` body or `ALTER TABLE … ADD COLUMN`), with a later `DROP TABLE` replayed (its table's
    columns and keys go with it — `veyq_expenses_receipt`, the receipt arm before the service, dropped by ADR 0112
    decision 5): `veyq_media.blob_id`, `veyq_media_derivative.blob_id`, `veyq_node_attachment.blob_id`,
    `veyq_application_blob.blob_id`. Keyed on the table, not the migration file: one baseline declares several tables,
    so a file key would fold two arms into one name and stop telling them apart. A column is read on its own line, so a
    declaration must keep `blob_id UUID` (or `VARCHAR`) there.
  - *backstop* — which of those arms a foreign key refuses to orphan: **all four**, `veyq_media.fk_media_blob`,
    `veyq_media_derivative.fk_derivative_blob`, `veyq_node_attachment.fk_attachment_blob` and
    `veyq_application_blob.fk_application_blob_blob`. This one is asserted by name deliberately. An arm *gaining* a
    foreign key is a real change in what the database guarantees, and it should turn this guard red and be recorded,
    not land silently as an improvement nobody notices — the attachment arm was recorded so (cr-files-application
    P3-S3), and the receipt arm (cr-expenses-receipts) and the service's that replaced it (ADR 0112), each born with
    its key.
  - *live twin* — that `SharingStore.subtreeBlobIds` plus every `SharedSubtreeContributor.authorizedBlobIds` (the
    media and derivative arms in Memories' contributor, the attachment arm and the service's in core's)
    enumerate all four.

The first three keys pin how many arms **exist**. Only the fourth pins that the predicate already running over
them in production **enumerates** them — and that is the one carrying the invariant, because the three
declaration keys are completely unmoved by dropping an arm from the consumer.

The gap between the schema key and the backstop key is the reason this was built, and it named an arm that is gone:
`veyq_node.cover_blob_id`, a plain nullable column with **no foreign key**, which the database silently let orphan a
standalone cover — user content, reclaimed irreversibly. Since inbox #341 a Memory's cover is a `COVER` attachment
(`veyq_node_attachment`), so every arm today is foreign-key backed, and a new arm *without* one is exactly what the
backstop key turns red. Any reclaim predicate over `veyq_blob` that enumerates fewer arms than exist still deletes
live data where a key does not refuse it, so the arity stays pinned.

**The defect that bought it.** No incident — nothing has shipped a reclaim job. It was built with
cr-blob-reference-arity-guard, whose refinement set out to *build* the long-promised sweep over blob rows
nothing points at, and measured the set that sweep would act on: **empty by construction**, because every row is
created in the same transaction as its reference and no delete or update path removes a reference while
leaving the blob. So the sweep would have shipped a job whose Definition of done is "finds nothing", carrying
its entire blast radius on the one arm the database does not protect. What was worth building instead is the
precondition any such sweep needs — this guard — and it protects a **live** twin of the same predicate today,
`SharingStore.subtreeBlobIds`, which decides which blobs a share serves.

**Its limit.** Three, and none is closable by making this grep cleverer:

  (a) It counts **declaration sites**. A reference column not named `*blob_id` escapes the entity and schema
      keys entirely, as does a `@Column(` whose name literal sits on a later line — no such site exists today
      (`grep -rn --include='*.kt' '@Column($' src/backend/src/main/kotlin` → 0), which is what makes the
      one-line-per-annotation assumption safe rather than lucky.
  (b) The live-twin key reads **text in one named function**. It therefore sees neither a *second* consumer
      written later — a new reclaim predicate elsewhere gets no protection from this guard at all — nor an arm
      that is named in the body but unreachable (dead branch, always-empty query). It proves the arm is
      *mentioned*, not that it is *reached*. The markers are literal spellings: a contributor that collects
      the media arm as `ids += m.blobId` instead of `ids.add(m.blobId)` enumerates it and is still red
      (conservative, never a false green) — re-spell the marker, do not loosen it to a bare `blobId`.
  (c) A `*blob_id` column declared a **non-reference** (`NOT_REFERENCE_*`, each with its reason) is trusted on
      that reason. The list is checked for staleness, not for truth: were such a column ever to start pointing at
      a live blob row, this guard would not notice. It names one column today, the upload session's minted id.
  (d) A `DROP TABLE` is replayed only on its own line, naming one table (`DROP TABLE [IF EXISTS] <name>;`), as every
      statement here is written; one spelled otherwise is not replayed, so the dropped table's arm stays counted —
      red as an extra arm, never a false green. A table renamed (`RENAME TO`) is not replayed either: none exists.

Two naive shapes were reached for first and are recorded so nobody reaches for them again — both are the
easier thing to type and both are silently wrong. Anchoring the entity key on `@Column(name = ` assumes the
column name is the annotation's first argument, spaced exactly the way every site happens to spell it today,
so an annotation that puts `length` first and drops the spaces slips straight past it; and matching
`REFERENCES` case-sensitively misses a lowercase constraint, which is not hypothetical — this tree carries a
live lowercase `references veyq_membership`. Measured against a real fourth arm forged into a copy of the
tree, that pair reports **3 and 2 — unchanged**, i.e. a green run over a tree that has an unguarded fourth
arm in it, which is the exact failure this guard exists to prevent. The keys above went to **4 / 4 / 3** on
the same forgery, and named the arm in all three.
"""
from __future__ import annotations

import pathlib
import re
import sys

import backend_tree  # where the backend's sources live — resolved by glob, never spelled here

ROOT = pathlib.Path(__file__).resolve().parent.parent
SHARING_STORE = backend_tree.source_file("me.raddatz.veyq.core.sharing.SharingStore")

# `@Column(... "<something>blob_id" ...)` — keyed on the column-name LITERAL, not on the Kotlin property, so a
# fourth arm cannot hide behind a differently-spelled property name. `[^)]*` keeps it to one annotation.
ENTITY_COLUMN = re.compile(r'@Column\([^)]*"([a-z_]*blob_id)"')
ENTITY_CLASS = re.compile(r"^\s*(?:open\s+)?class\s+(\w+)\s*:")

# The SQL side of the same columns: a CREATE TABLE body line, or a later ADD COLUMN.
SQL_COLUMN = re.compile(r"^\s+([a-z_]*blob_id)\s+(?:UUID|VARCHAR)\b|ADD COLUMN\s+([a-z_]*blob_id)\b", re.IGNORECASE)
# The table a line belongs to: the open CREATE TABLE body, or the ALTER TABLE statement the line starts.
SQL_TABLE = re.compile(r"\b(?:CREATE|ALTER)\s+TABLE\s+(\w+)", re.IGNORECASE)
# A later migration dropping a table: its columns and keys go with it (limit (d) says how it must be spelled).
SQL_DROP_TABLE = re.compile(r"\bDROP\s+TABLE\s+(?:IF\s+EXISTS\s+)?(\w+)\s*;", re.IGNORECASE)

# Case-insensitive on purpose: this tree already spells one `references` in lowercase.
SQL_BLOB_FK = re.compile(r"references\s+veyq_blob\b", re.IGNORECASE)
SQL_CONSTRAINT_NAME = re.compile(r"constraint\s+(\w+)", re.IGNORECASE)

EXPECTED_ENTITY_ARMS = [
    "ApplicationBlobEntity.blob_id",
    "MediaDerivativeEntity.blob_id",
    "MediaEntity.blob_id",
    "NodeAttachmentEntity.blob_id",
]
EXPECTED_SCHEMA_ARMS = [
    "veyq_application_blob.blob_id",
    "veyq_media.blob_id",
    "veyq_media_derivative.blob_id",
    "veyq_node_attachment.blob_id",
]
EXPECTED_FK_ARMS = [
    "veyq_application_blob.fk_application_blob_blob",
    "veyq_media.fk_media_blob",
    "veyq_media_derivative.fk_derivative_blob",
    "veyq_node_attachment.fk_attachment_blob",
]

# Columns named `*blob_id` that are NOT a reference to a veyq_blob row, each with the reason it cannot be one. They
# are dropped from the entity and schema keys, and must still exist (a stale entry is an error), so this list can
# only ever name a column that is really there. Keep it short: every entry is a place a real arm could hide.
#   - The upload session's `blob_id` is the id a blob WILL be stored under (ADR 0072: the client mints it and the
#     `K_blob` wrap is sealed bound to it). The session row is deleted in the transaction that births the blob row,
#     and `start` refuses an id a blob already holds, so it never points at an existing veyq_blob row.
NOT_REFERENCE_ENTITY = ["UploadSessionEntity.blob_id"]
NOT_REFERENCE_SCHEMA = ["veyq_upload_session.blob_id"]

# The four arms as the live twin spells them in its bodies. Each is the accessor that arm is READ by, so removing the
# arm removes the marker. The attachment arm (a Memory's cover among its rows, inbox #341) is core's own contributor
# (`AttachmentSharedSubtreeContributor`), and its marker is the entity it reads: that contributor is the only body
# that names it. The application blob arm is core's blob service's (`ApplicationBlobSharedSubtreeContributor`, ADR
# 0112), its marker the accessor it adds by.
LIVE_TWIN_FN = "fun subtreeBlobIds"
# Where the media, derivative and attachment arms live: every implementation of the interface,
# found by its declaration, contributes its `authorizedBlobIds` body to the live twin.
CONTRIBUTOR_IMPL = ": SharedSubtreeContributor"
CONTRIBUTOR_FN = "override fun authorizedBlobIds"
EXPECTED_LIVE_TWIN_ARMS = {
    "attachment": "NodeAttachmentEntity",
    "media": "ids.add(m.blobId)",
    "derivative": "MediaDerivativeEntity",
    "application blob": "ids.add(row.blobId)",
}


def entity_arms() -> list[str]:
    """Every `*blob_id` @Column in main Kotlin, named as `<EnclosingEntity>.<column>`."""
    found: list[str] = []
    for path in backend_tree.main_kotlin():
        enclosing = "<no enclosing class>"
        for line in path.read_text(encoding="utf-8").splitlines():
            klass = ENTITY_CLASS.match(line)
            if klass:
                enclosing = klass.group(1)
            column = ENTITY_COLUMN.search(line)
            if column:
                found.append(f"{enclosing}.{column.group(1)}")
    return sorted(found)


def _sql_lines():
    """Every migration line with its `--` comment stripped, paired with the table it belongs to (or None), and the
    table it drops (or None) — in version order, so a drop follows what it drops."""
    for path in backend_tree.migrations():
        table = None
        for lineno, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            line = raw.split("--", 1)[0]
            opened = SQL_TABLE.search(line)
            if opened:
                table = opened.group(1).lower()
            dropped = SQL_DROP_TABLE.search(line)
            yield path, lineno, line, table, dropped.group(1).lower() if dropped else None
            if ";" in line:
                table = None


def _live(found: list[tuple[str, str]]) -> list[str]:
    """The `<table>.<name>` labels of [found] (table, label) pairs — the dropped ones already taken out."""
    return sorted(f"{table}.{label}" for table, label in found)


def schema_arms() -> list[str]:
    """Every `*blob_id` column declared by a migration and not dropped since, named as `<table>.<column>`."""
    found: list[tuple[str, str]] = []
    for _path, _lineno, line, table, dropped in _sql_lines():
        if dropped:
            found = [f for f in found if f[0] != dropped]
            continue
        m = SQL_COLUMN.search(line)
        if m:
            found.append((table, m.group(1) or m.group(2)))
    return _live(found)


def fk_arms() -> list[str]:
    """Every foreign key into veyq_blob not dropped with its table, named `<table>.<constraint>` (or by its own text
    where it has no name)."""
    found: list[tuple[str, str]] = []
    for path, lineno, line, table, dropped in _sql_lines():
        if dropped:
            found = [f for f in found if f[0] != dropped]
            continue
        if not SQL_BLOB_FK.search(line):
            continue
        named = SQL_CONSTRAINT_NAME.search(line)
        label = named.group(1) if named else f"{path.name}:L{lineno} {line.strip()}"
        found.append((table, label))
    return _live(found)


def _fn_body(lines: list[str], fn: str) -> list[str] | None:
    """The lines of the first function whose declaration line contains `fn`, up to its member-level `    }`."""
    for i, line in enumerate(lines):
        if fn not in line:
            continue
        body = [line]
        for following in lines[i + 1:]:
            body.append(following)
            if following == "    }":  # the function's own closing brace, at member indentation
                return body
        return body
    return None


def live_twin_body() -> list[str] | None:
    """The live twin: core's `subtreeBlobIds` body plus every contributor's `authorizedBlobIds` body, or None where
    the core function is gone. The media and derivative arms live in the implementations of `SharedSubtreeContributor`
    (ADR 0079 phase 1, step 7), so the check follows the arms to where they are."""
    if not SHARING_STORE.exists():
        return None
    body = _fn_body(SHARING_STORE.read_text(encoding="utf-8").splitlines(), LIVE_TWIN_FN)
    if body is None:
        return None
    for path in backend_tree.main_kotlin():
        text = path.read_text(encoding="utf-8")
        if CONTRIBUTOR_IMPL not in text:
            continue
        found = _fn_body(text.splitlines(), CONTRIBUTOR_FN)
        if found is not None:
            body += found
    return body


def compare(key: str, expected: list[str], actual: list[str], errors: list[str]) -> None:
    if expected == actual:
        return
    for extra in sorted(set(actual) - set(expected)):
        errors.append(
            f"{key}: unexpected reference arm `{extra}`. One more place referencing veyq_blob means every "
            f"reclaim predicate over blob rows is now short by one arm — including "
            f"SharingStore.subtreeBlobIds. Teach the consumers first, then add it here."
        )
    for missing in sorted(set(expected) - set(actual)):
        errors.append(
            f"{key}: reference arm `{missing}` is gone. If the arm was genuinely removed, drop it here and "
            f"from every predicate that enumerates it; do not delete this guard."
        )
    if set(expected) == set(actual):  # same names, different multiplicity
        errors.append(f"{key}: expected {expected}, found {actual}.")


def main() -> int:
    errors: list[str] = []

    if not backend_tree.main_roots() or not backend_tree.migration_roots():
        print(
            "BLOB REFERENCE ARITY VALIDATION FAILED:\n"
            f"  - missing tree: no {backend_tree.BACKEND}/**/src/main/kotlin or …/resources/db/migration. This guard reads backend sources directly; "
            f"a moved tree must be re-pointed, not silently skipped."
        )
        return 1

    entity, schema = entity_arms(), schema_arms()
    for key, declared, found in (("entity", NOT_REFERENCE_ENTITY, entity), ("schema", NOT_REFERENCE_SCHEMA, schema)):
        for stale in sorted(set(declared) - set(found)):
            errors.append(f"{key}: `{stale}` is declared a non-reference column but no longer exists — drop the entry.")
    compare("entity", EXPECTED_ENTITY_ARMS, [a for a in entity if a not in NOT_REFERENCE_ENTITY], errors)
    compare("schema", EXPECTED_SCHEMA_ARMS, [a for a in schema if a not in NOT_REFERENCE_SCHEMA], errors)
    compare("backstop", EXPECTED_FK_ARMS, fk_arms(), errors)

    body = live_twin_body()
    if body is None:
        errors.append(
            "live twin: SharingStore.subtreeBlobIds is missing. It is the one predicate enumerating the blob "
            "arms in production; if it was renamed, re-point this guard at the new name — a guard that goes "
            "quiet because its subject moved proves nothing."
        )
    else:
        text = "\n".join(body)
        for arm, marker in sorted(EXPECTED_LIVE_TWIN_ARMS.items()):
            if marker not in text:
                errors.append(
                    f"live twin: neither SharingStore.subtreeBlobIds nor any SharedSubtreeContributor.authorizedBlobIds "
                    f"enumerates the {arm} arm (`{marker}` is gone from those bodies). The declaration keys above are UNCHANGED by "
                    f"this — the arm still exists, the predicate just stopped seeing it, which is how a share "
                    f"silently stops serving a blob it should serve."
                )

    if errors:
        print("BLOB REFERENCE ARITY VALIDATION FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1

    print(
        f"OK: {len(EXPECTED_ENTITY_ARMS)} veyq_blob reference arms ({', '.join(EXPECTED_ENTITY_ARMS)}), "
        f"{len(EXPECTED_FK_ARMS)} of them foreign-key backed, "
        f"all {len(EXPECTED_LIVE_TWIN_ARMS)} enumerated by the live twin (subtreeBlobIds + the contributors)."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
