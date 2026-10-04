#!/usr/bin/env python3
"""Guard that a node subtree's hard-erasure reaches every row keyed on the node (uc.memory.delete-node, ADR 0061
Decision 6) — the node-side twin of `validate_scope_erasure_arity`.

**What it asserts.** Every entity in backend main Kotlin carrying a `@Column(... "node_id" ...)` is a row hanging
off a node, and each must be reached by the trash purge before `NodeSubtreePurge` deletes the node rows. The set is
**derived**, not listed: the entities are found by their column literal, so a fifth `node_id` table is red until
the purge reaches it. An entity is reached when

  - the `erase` body of a class implementing `NodeSubtreeEraser` deletes it (`XEntity.delete(`/`deleteById(`), or
  - it is one of the `HANDLED` entities below, each reached through a named shared body instead of a literal
    delete — the marker must appear where the table says, and the body it names must itself still delete the
    entity (`MediaStore.purgeMediaRows` → `MediaEntity.deleteById`), so neither half can go quiet alone.

At least one `NodeSubtreeEraser` implementation must exist at all; zero is red on its own.

**The defect that bought it.** A review forgery (2026-10-04, ADR 0079 phase 1, step 6): with
`MediaNodeSubtreeEraser.kt` (then `features/media`, now `memories/media`) deleted, `validate_scope_erasure_arity`, `validate_blob_reference_arity`
and `validate_store_reclaim_arity` all stayed green, and `NodeSubtreePurge` would then delete the node rows with
nothing erasing their media, derivative and bucket rows or reclaiming their blobs. Unlike the scope side there is
**no foreign key onto `veyq_node`**, so nothing at runtime refuses either: the orphans are silent — inbox #131,
the defect the purge was built to close, back in one deleted file.

**Its limit.** It reads **text, not a running purge**. (a) It proves each node-keyed entity is *named* by an
eraser's `erase` body (everything after that file's first `override fun erase`), never that the delete is reached,
ordered or keyed on the right ids — `NodeSubtreeEraserOrderSliceTest` and the purge slice tests, which execute it,
hold that half. (b) Eraser discovery is the `class … : NodeSubtreeEraser` declaration line, the same conservative
regex as the scope guard's: a primary constructor carrying `=` or `{` (a default argument, a lambda) or an
eraser reached through an abstract base class is not discovered, so the guard is red although the eraser exists.
(c) A node-keyed column not named `node_id` (a `memory_id`, a `root_node_id`) is outside the derived set entirely.
"""
from __future__ import annotations

import pathlib
import re
import sys

import backend_tree  # where the backend's sources live — resolved by glob, never spelled here

ROOT = pathlib.Path(__file__).resolve().parent.parent
PURGE = backend_tree.source_file("com.veyq.core.content.NodeSubtreePurge")

ERASER_CLASS = re.compile(r"^\s*(?:\w+\s+)*class\s+\w+[^{=]*:\s*(?:[\w.,\s]*,\s*)?NodeSubtreeEraser\b", re.MULTILINE)
ERASER_FN = "override fun erase("
ERASED_ENTITY = re.compile(r"(\w+Entity)\.(?:delete|deleteById)\(")
NODE_COLUMN = re.compile(r'@Column\([^)]*"node_id"')
ENTITY_CLASS = re.compile(r"^\s*(?:open\s+)?class\s+(\w+)\s*:")

# Node-keyed entities reached through a named body rather than a literal delete in an eraser. Each entry: where the
# marker must appear ("eraser" = some eraser's `erase` body, else a file by its qualified name), the marker, and — where the
# marker is a call into a shared body — that body's file, function and the delete it must still contain.
HANDLED = {
    # Media go one at a time through the FK-ordered body (derivative -> media -> blob, chunk-key reclaim).
    "MediaEntity": ("eraser", "purgeMediaRows(",
                    ("com.veyq.memories.media.MediaStore", "fun purgeMediaRows(", "MediaEntity.deleteById(")),
    # A link on an erased Memory is tombstoned, not deleted (ADR 0029 §7): its id stays held.
    "ShareEntity": ("com.veyq.core.content.NodeSubtreePurge", "sharing.tombstoneAllOn(nodeIds)", None),
}


def node_keyed_entities() -> list[str]:
    found: list[str] = []
    for path in backend_tree.main_kotlin():
        enclosing = None
        for line in path.read_text(encoding="utf-8").splitlines():
            if klass := ENTITY_CLASS.match(line):
                enclosing = klass.group(1)
            if NODE_COLUMN.search(line) and enclosing:
                found.append(enclosing)
    return sorted(set(found))


def _fn_text(text: str, fn: str) -> str | None:
    """From `fn`'s declaration to the next member-level declaration — enough to read one short body."""
    if fn not in text:
        return None
    rest = text.split(fn, 1)[1]
    end = re.search(r"\n    (?:@|fun |override |private |internal |val |var )", rest)
    return rest[: end.start()] if end else rest


def main() -> int:
    errors: list[str] = []
    if not PURGE.is_file():
        print(
            "NODE SUBTREE ERASURE VALIDATION FAILED:\n"
            f"  - missing {PURGE.relative_to(ROOT)}. This guard reads the purge and its erasers directly; a moved "
            f"purge must be re-pointed, not silently skipped."
        )
        return 1

    erasers: list[str] = []
    bodies = ""
    for path in backend_tree.main_kotlin():
        text = path.read_text(encoding="utf-8")
        if ERASER_CLASS.search(text) and ERASER_FN in text:
            erasers.append(path.stem)
            bodies += text.split(ERASER_FN, 1)[1]
    if not erasers:
        errors.append(
            "no class implementing `NodeSubtreeEraser` with an `override fun erase(` was found under backend main "
            "Kotlin. The media, bucket and item-favorite rows of an erased subtree are deleted by those; with none, "
            "NodeSubtreePurge deletes the node rows and leaves every row keyed on them orphaned — silently, as no "
            "foreign key points at veyq_node."
        )
    deleted = set(ERASED_ENTITY.findall(bodies))

    entities = node_keyed_entities()
    if not entities:
        errors.append(
            "no entity with a `node_id` @Column was found. Either the column was renamed — and this guard is asking "
            "about nothing — or the annotation shape drifted. An empty set is vacuously green, so it is an error."
        )
    for stale in sorted(set(HANDLED) - set(entities)):
        errors.append(f"`{stale}` is listed in HANDLED but no longer carries a `node_id` column — drop the entry.")

    for entity in entities:
        if entity in deleted:
            continue
        if entity not in HANDLED:
            errors.append(
                f"`{entity}` carries a `node_id` column but no NodeSubtreeEraser's `erase` deletes it. The trash "
                f"purge then deletes the node rows and leaves `{entity}` rows pointing at a node that no longer "
                f"exists — unreachable by every read and unreclaimed by every purge (inbox #131). Delete it in the "
                f"NodeSubtreeEraser of the package that owns it."
            )
            continue
        where, marker, body = HANDLED[entity]
        home = None if where == "eraser" else backend_tree.source_file(where)
        text = bodies if home is None else home.read_text(encoding="utf-8") if home.is_file() else ""
        if marker not in text:
            site = "any NodeSubtreeEraser's `erase` body" if where == "eraser" else where
            errors.append(
                f"`{entity}` carries a `node_id` column and is reached through `{marker}` in {site}, which is gone. "
                f"Nothing else erases it, so the node rows go and `{entity}` rows stay behind as silent orphans."
            )
            continue
        if body:
            body_file, body_fn, body_delete = body
            body_path = backend_tree.source_file(body_file)
            fn = _fn_text(body_path.read_text(encoding="utf-8"), body_fn) if body_path.is_file() else None
            if fn is None or body_delete not in fn:
                errors.append(
                    f"`{entity}` is reached through `{marker}`, but `{body_fn}` in {body_file} no longer contains "
                    f"`{body_delete}` (or is gone). The call is still there; the delete it stood for is not."
                )

    if errors:
        print("NODE SUBTREE ERASURE VALIDATION FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1
    print(
        f"OK: all {len(entities)} node-keyed entities ({', '.join(entities)}) are reached by the node subtree "
        f"erasure ({len(erasers)} NodeSubtreeEraser: {', '.join(erasers)})."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
