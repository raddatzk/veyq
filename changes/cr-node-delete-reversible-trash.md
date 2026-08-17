---
id: cr-node-delete-reversible-trash
title: Deleting a Memory becomes reversible — subtree soft-delete, a per-space trash, and a purge that reclaims all of it
status: planned
satisfies: [uc.memory.delete-node]
adrs: ["0061"]
invariants:
  - Operator-blind E2EE — the added state is a lifecycle timestamp plus a slug token swapped for an opaque id; no name, no key, no content
  - A public share must never serve content the user deleted — both share reads carry the predicate, or neither is safe
  - Self-hostable by default — the schema stays inside the H2 ∩ MariaDB subset (no filtered indexes)
---

## Goal

Deleting a node today is immediate, irreversible, and reclaims only part of what it removes:
`NodesStore.deleteSubtree` deletes the subtree's node rows and its standalone cover blobs, and leaves the
subtree's `veyq_media`, `veyq_media_derivative` and `veyq_bucket` rows behind, pointing at a `node_id` that no
longer exists — unreachable by every read and unreclaimable by every purge (inbox #131). This CR replaces that
with the mechanism the security model already mandates for every destruction: the delete **soft-deletes the whole
subtree**, the subtree disappears from every read including both public-share paths, it is restorable from a
**per-space trash** for a retention window, and a **scheduled purge worker** then hard-erases all of it — nodes,
media, derivatives, buckets, blobs and store objects. The leak closes because the purge finally owns the whole
subtree instead of just its covers.

## Context & constraints

- Use-case: [uc.memory.delete-node](../docs/features/memory/use-cases/uc.memory.delete-node.md) — the flow, its
  exception paths, and the three reads that must deliberately *not* filter.
- Decision: [ADR 0061](../docs/decisions/0061-node-deletion-reversible-node-trash.md) — including the rejected
  alternatives, so this CR does not re-litigate them.
- Enabler: [en.memory.tree](../docs/features/memory/enablers/en.memory.tree.md) § *Delete — soft-delete, trash,
  purge* (the node lifecycle this builds).
- Policy: [security model](../docs/crosscutting/security-model.md) § *Deletion & erasure* — the reversible/
  irreversible split this joins rather than amends.
- Contracts: `contracts/api/content.openapi.yaml` (node delete lives here; the restore + trash listing join it).
- **The trash grain question is NOT this CR's.** ADR 0061 Decision 3 settles the grain for *nodes* only. The
  crosscutting doc's "per-scope trash" wording versus the built per-Memory *media* trash is inbox #133 and stays
  open — do not "harmonize" `security-model.md` while working here.

### Two exemplars disagree — this one wins

Two soft-deletes already exist and they take **opposite** approaches to reads. Naming both without saying which
wins is what the seventh tell is about, so:

- **`MediaEntity` wins — filter-on-read.** Field `media/Entities.kt:138`, migration `V34__media_trash.sql:12`,
  and six `deletedAt is null` predicates carried by its reads (`grep -c 'deletedAt is null'
  backend/src/main/kotlin/com/veyq/app/features/media/MediaStore.kt` → **6**). Copy this.
- **`ScopeEntity` loses — surface-on-read.** `ScopesStore.kt:194` plumbs `deletedAt` into the row it returns and
  **no listing filters on it**; the client renders pending-deletion scopes. Its soft-delete/restore/worklist trio
  (`ScopesStore.kt:287`, `:293`, `:298`) is still the right shape to copy for the *write* side and the purge
  worklist — but **not** its read posture. Taking that half by accident is precisely how a deleted Memory stays
  visible.
- For the purge body, **`MediaStore.purgeMediaRows` (`MediaStore.kt:262`) wins over `ScopePurge.eraseScope`** —
  rows in FK order inside the transaction, store objects best-effort after commit. `ScopePurge` is set-based and
  objects-first because it erases a whole scope and lets the scope-row delete cascade the rest; a node subtree is
  not a scope, and its own docstring (`ScopePurge.kt:27-29`) says a per-media copy is the wrong shape *there*, not
  here.

### The hazard this CR exists around

With `deleted_at` set but the two share reads unfiltered, a share on a deleted Memory changes from returning an
**empty** subtree (today, because `nodeById[it]` misses on a row that is gone — `SharingStore.kt:225`) to
returning the **full sealed subtree plus presigned blobs**, unauthenticated. The column added to make deletion
safer is what would introduce the regression. Both reads — the resolve listing (`SharingStore.kt:225`) and the
blob authorization (`SharingStore.kt:271`), seeded by `SharingStore.kt:280` — must gain it together.

## Phases

- [ ] **1 contracts** — `contracts/api/content.openapi.yaml`: add **restore** (carrying the client-computed
      `slugToken`) and the **space trash listing** (soft-deleted subtree roots with `deletedAt` + derived
      `purgeAfter`); node delete keeps its shape. No `sharing`/`media` contract change — those surfaces only gain
      predicates. · **Done when:** the generated client/DTOs build on both surfaces and `python3
      scripts/validate_contracts_common.py` is green.
- [ ] **2 backend** — in order:
      (a) migration `V47__node_soft_delete.sql` — `ALTER TABLE veyq_node ADD COLUMN deleted_at TIMESTAMP NULL;`
      mirroring `V26__scope_soft_delete.sql`. **Do not touch `ux_node_parent_slug`** (`V10__nodes.sql:23`) — the
      slug is freed by the write, not by the schema (MariaDB has no filtered index).
      (b) `NodesStore.deleteSubtree` — mark the subtree instead of deleting it, and set each doomed node's
      `slugToken` to its own id. **Move the cover-blob reclaim out** (`NodesStore.kt:133-142`) into the purge
      worker; reclaiming covers at delete time makes a restore return a Memory whose cover is gone.
      (c) the read predicates — **11 call-sites in 3 files** (`NodesStore`, `MediaStore`, `SharingStore`), and
      close the two handlers that reach a node without the gate (`MediaHandler.renameBucket`, `deleteBucket`).
      **Three reads must NOT get it:** slug uniqueness (`NodesStore.kt:61`), the delete's own traversal
      (`NodesStore.kt:123`), and `ScopePurge.eraseScope`'s node delete (`ScopePurge.kt:68`).
      (d) restore + the trash listing (subtree roots only), `purgeAfter` derived at the resource boundary from
      config — **not** a column.
      (e) `NodeSubtreePurge` — a new `@Scheduled` worker over `deletedAt < cutoff`, routed through
      `MediaStore.purgeMediaRows`, also erasing bucket rows and the cover blobs from (b).
      (f) fix the now-wrong comment at `NodesStore.kt:143-146` — its premise ("delete removes the node rows
      themselves … no open gallery can poll a deleted node") stops holding; the conclusion it supports (no
      *surviving* node's gallery set changes) still does.
      · **Done when:** `./gradlew check` green, including the bite-tests below.
- [ ] **3 web** — the **space Trash** surface + restore (recompute and send `slugToken`; handle the taken-name
      rejection as the create path already does). Hiding needs **no** web change — `onDeleteDone` already
      refetches the whole tree (`web/src/views/MemoryHomeView.vue:159-163`), so a filtered listing is sufficient;
      do not add optimistic removal. · **Done when:** `npm run check` green.
- [ ] **4 coverage** — flip `docs/coverage/features/memory/use-cases/uc.memory.delete-node.yaml` from
      `status: planned` + `gaps:` to real refs for all eight criteria. · **Done when:**
      `python3 scripts/validate_coverage.py` green.

## Bite-tests — write these RED first

Per [§ bite-proof a guard](README.md): each of these must be shown failing before the fix, or it proves nothing.

1. **The share pair, independently.** A share on a soft-deleted Memory must return no nodes **and** must refuse
   the blob presign. Remove *either* predicate and exactly that assertion must redden while the other's control
   stays green — one predicate silently covering for the other is the failure mode this pair exists to prevent.
2. **The slug is free immediately.** Delete a Memory, create a new one with the same name in the same parent, in
   the same test. Without the `slugToken := id` write this fails on `ux_node_parent_slug`.
3. **Restore into a taken name is a clean rejection, not a 500.** Delete, re-create the name, then restore — it
   must surface the same disambiguation a create does.
4. **Filtering `slugTaken` is the trap, so pin it.** A test that creates a node whose slug matches a
   *soft-deleted* sibling must still be rejected cleanly — if that read ever learns the predicate, validation
   passes and the database throws afterwards.
5. **Idempotent re-delete.** Deleting a subtree whose descendants are already soft-deleted must still complete —
   the traversal must not skip them.
6. **The purge reclaims all three arms.** After the window, media, derivative, bucket and blob rows and the store
   objects are gone — observed through the `%test` store seam, not asserted from the DB alone.

## Definition of done

`./gradlew check` and `npm run check` green; `for f in scripts/validate_*.py; do python3 "$f"; done` green;
`docs/coverage/.../uc.memory.delete-node.yaml` carrying real refs for all eight criteria with no remaining `gaps`.
The six bite-tests above were each observed **red before green**. The structural invariant worth promoting past
this CR is bite-test 1 — *"a soft-deleted node leaves both share paths"* is the same class of cross-feature
obligation that already shipped broken once for trashed media, so it stays a named slice test rather than an
inline assertion, and the CR notes in its retro whether a guard would have been cheaper.

## Locators

Every command below was run at `main` @ `194be48` while writing this CR; re-run before trusting a number.

```
grep -rn --include='*.kt' -E 'NodeEntity\.(find|findById|count|stream)' backend/src/main/kotlin | wc -l   # 14 direct reads
grep -rn --include='*.kt' -E '(MediaEntity|BucketEntity|ShareEntity|FavoritesEntity|BlobEntity|MediaDerivativeEntity|UploadSessionEntity)\.(find|count|update|delete)\(' backend/src/main/kotlin | grep -c nodeId   # 11 indirect joins
grep -rl --include='*.kt' -E 'NodeEntity\.(find|findById|count|stream)' backend/src/main/kotlin | sort -u  # 3 files
grep -n 'ux_node_parent_slug' backend/src/main/resources/db/migration/V10__nodes.sql                      # :23
grep -n 'NodeEntity.find' backend/src/main/kotlin/com/veyq/app/features/sharing/SharingStore.kt           # :72 :225 :271 :280
grep -c 'deletedAt is null' backend/src/main/kotlin/com/veyq/app/features/media/MediaStore.kt             # 6 — the exemplar that wins
grep -n 'deletedAt' backend/src/main/kotlin/com/veyq/app/features/spaces/scope/ScopesStore.kt             # no read filter — the exemplar that loses
grep -n 'fun purgeMediaRows' backend/src/main/kotlin/com/veyq/app/features/media/MediaStore.kt            # :262 — the shared purge body
```

**Read the first two as lists, not counts.** Of the 14 direct reads, **9** take the predicate and **5** must not;
of the 11 joins, **2** need it directly and **9** inherit it — but only if every `MediaHandler` call-site routes
through the node gate, which `renameBucket` and `deleteBucket` do not. Net: **11 call-sites**, 3 files, plus those
two handlers and one migration.

## Retrospective

<!-- filled at implement time -->
