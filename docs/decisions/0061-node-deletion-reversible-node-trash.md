# ADR 0061 — Deleting a Memory is reversible: a hidden node trash, filtered on read

- Status: Accepted
- Date: 2026-08-17

## Context

Deleting a node (a Memory, a Chapter, a folder) is today **immediate and irreversible**, and it reclaims only
part of what it removes. `NodesStore.deleteSubtree` deletes the doomed subtree's node rows and its standalone
**cover** blobs, and nothing else — the subtree's `veyq_media`, `veyq_media_derivative` and `veyq_bucket` rows
survive, pointing at a `node_id` that no longer exists. They are then unreachable *and* unreclaimable: every
read filters on `scopeId + nodeId`, they carry `deleted_at is null` so the media trash purge never sees them,
and their blobs stay legitimately referenced so no unreferenced-blob predicate can reach them.

Three forces meet here, and none of them had been written down:

- **The crosscutting policy already fixes the shape of destruction.** The
  [security model](../crosscutting/security-model.md) § *Deletion & erasure* mandates **soft-delete → trash →
  retention window → scheduled hard-purge**, and states that destruction always goes **trash → purge**, never a
  one-step "delete forever" of another member's live content. A node delete is exactly that case: media carry an
  owner, and buckets are per-owner, so in a shared scope one member's node delete destroys another member's live
  uploads in one step.
- **No refined state said what a node delete does to its media.** There was no use-case for node deletion at all
  — it existed as a clause in [uc.memory.browse](../features/memory/use-cases/uc.memory.browse.md) and
  [uc.memory.chapters](../features/memory/use-cases/uc.memory.chapters.md), and
  [en.memory.tree](../features/memory/enablers/en.memory.tree.md) did not use the word *delete*. This is a
  cross-feature gap, not doc drift: the node-delete work and the media-reclaim work never met, so the doc↔code
  delta was empty while the defect was real.
- **The bigger deletion is already reversible.** Deleting a whole group enters a grace period with a restore
  ([ADR 0014](0014-group-scopes-and-governance.md) §6). A group being recoverable for weeks while a single
  Chapter inside it is destroyed instantly is not a defensible product story.

## Decision

**1. A node delete is a soft-delete.** `veyq_node` gains a nullable `deleted_at`; `deleteSubtree` marks the
doomed subtree instead of removing it. Nothing is destroyed at the moment of the user's action, so the media,
derivatives, buckets and blobs under it are untouched and a restore is cheap.

**2. A soft-deleted node is hidden, not shown as pending.** Every user-facing and guest-facing read carries
`deletedAt is null`. This is the one point where the group-deletion precedent does **not** transfer:
`ScopeEntity`'s soft-delete deliberately *surfaces* the marker (`listMine` returns pending-deletion scopes and
the client renders them), which is right for a space you are still working in and wrong for a Memory the user
believes they deleted. The closer template is the **media trash** — `MediaEntity`'s `deleted_at` with
filter-on-read — and that is the one we follow.

**3. The node trash is per-scope, and that is what makes it work at all.** Trashed *media* live in a tab on
their Memory; a trashed *node* cannot, because its own Memory is what was deleted. The node trash therefore
hangs off the space, reachable without the node — which is also why soft-deleting a node's media into the
per-Memory trash was never a real option (§ Rejected (b)). This settles the trash grain for **nodes only**; the
media trash's grain is a separate open question and is not decided here.

**4. Deletion takes the subtree out of public shares immediately.** Both share reads carry the predicate — the
anonymous resolve *and* the blob authorization. Filtering only the listing leaves a Memory that is invisible in
the share and still fully downloadable by anyone holding a `blobId`, for the entire retention window. This is the
same coupling the trashed-media rule already carries, and it is the sharpest hazard in the change: with
`deleted_at` set but the share reads unfiltered, a share on a deleted Memory goes from returning an **empty**
subtree (today, because the node row is gone) to returning the **full sealed subtree plus presigned blobs** — a
confidentiality regression on an unauthenticated path, introduced by the very column meant to make deletion safer.

**5. The slug is freed at soft-delete, and restore re-supplies it.** `UNIQUE(parent_id, slug_token)` is a plain
unique index, and prod is MariaDB, so a partial index filtered on `deleted_at IS NULL` is not available. A
soft-deleted node would otherwise hold its name hostage for the whole window. So the soft-delete sets the
deleted **root's** `slug_token` to its own id — unique by construction, and blind, since the server may not compute
a slug token at all (it is `HMAC(K_SC, parentId ‖ slug)`, [ADR 0012](0012-content-key-hierarchy.md)). Only the root:
its descendants' names live under a parent that is trashed too, where nothing can claim them while it stays so, so
they keep their tokens and come back valid (corrected 2026-10-06, inbox #357 — freeing every node returned each
descendant holding no name, beside which a same-named sibling could be created). **Restore carries a
freshly computed `slugToken` from the client**, and a collision with a node created in the meantime is an
ordinary disambiguation, the same one a create already handles — which means the restore must also be able to
carry a **re-sealed `encryptedName`**, in the *same* request. The retry is "put it back under a different name",
and a name sent apart from the token derived from it leaves the node live, briefly, carrying a slug its own name
does not produce; a failure in between leaves it that way for good.

**6. The purge is a scheduled job, and the blobs must outlive the grace window.** A new node purge worker
hard-erases subtrees whose `deleted_at` is older than the retention window: media, derivatives, buckets, cover
blobs and the store objects. The cover-blob reclaim currently inside `deleteSubtree` **moves into it** — reclaiming
covers at soft-delete time would make a restore return a Memory whose cover is gone. `purgeAfter` is **derived**
at the resource boundary from `deleted_at` plus the configured retention, not stored, matching both existing
soft-delete precedents.

**7. Row-then-object ordering, via the shared per-media body.** The purge routes through
`MediaStore.purgeMediaRows` (rows in FK order inside the transaction, and the store objects recorded for reclaim
in that same transaction — [ADR 0062](0062-store-object-reclaim-is-a-durable-work-list.md) replaced the
best-effort post-commit delete this decision originally specified, and `ScopePurge.eraseScope` lost its
objects-first exception at the same time) rather than the set-based shape of `ScopePurge.eraseScope`.
`ScopePurge`'s own docstring says why it is the exception: it erases set-based *by scope*, and its final
scope-row delete lets the database cascade the rest. A node subtree is not a scope — neighbouring nodes, buckets and the scope row all survive — so the
FK-ordered shared body is the correct shape, and a set-based copy would be the fourth copy that docstring warns
against.

## Consequences

- **Deletion stops being destruction.** A mis-click is recoverable for the retention window, and the operator
  stops paying for bytes no user can reach — the leak closes because the purge now owns the whole subtree, not
  just its covers.
- **The policy is honoured rather than amended.** Node deletion joins the one mechanism the security model
  already mandates, instead of becoming a second, one-step destruction path beside it.
- **The cost is a read-path sweep, and it is bounded and measured:** 11 call-sites across three stores
  (`NodesStore`, `MediaStore`, `SharingStore`) gain the predicate, out of 25 that touch a node. Two bucket
  handlers (`renameBucket`, `deleteBucket`) reach a node without passing the gate and must be closed explicitly.
- **Four reads must deliberately *not* filter, and each is a trap.** The slug-uniqueness check must keep seeing
  soft-deleted rows or validation passes and the database then rejects the insert — a clean rejection turned into
  a 500. The subtree traversal inside the delete itself must reach descendants regardless of their own state and
  **re-stamp** them: a subtree that ages out on two timestamps has its inner rows purged first, and the folder
  around them is still sitting restorable in the trash when it happens. The delete's own scope binding is the
  fourth, and it was found by building rather than by reasoning — gate it on liveness and re-deleting a trashed
  subtree answers 404 instead of the no-op the flow promises, while a foreign node must keep answering the same
  non-enumerable 404; so the delete takes a lookup that sees trashed rows and the *update* path keeps the one
  that does not. The scope hard-erase must keep removing **all** node rows including soft-deleted ones.
- **The web needs no change to hide anything.** The delete handler already refetches the whole tree rather than
  splicing locally, so a filtered listing is enough. What the web does owe is the new surface: the space's trash
  and its restore affordance.
- **An existing code comment becomes wrong on the day this lands.** `NodesStore`'s cr-0107 note argues that no
  revision bump is needed because "delete removes the node rows themselves … no open gallery can poll a deleted
  node". Under a soft-delete the rows and their counters remain, so either the media listing gates on the node's
  state or the delete must bump. The reasoning it supports — that no *surviving* node's gallery set changes —
  still holds.
- **A restore can fail, and that is correct.** If the name was taken in the meantime the client disambiguates,
  exactly as it does when creating. A restore that silently renamed, or one that refused forever, would both be
  worse.
- **Only the trash's own roots restore.** Clearing a trashed node whose parent is still trashed would leave a
  live node hanging under a dead one — present in no listing at all, because the client assembles the tree by
  `parentId` and the parent is filtered out. It is the reason the trash lists roots only, enforced on the write
  side as well as the read side.
- **Operator-blindness is unchanged.** The added state is a lifecycle timestamp (`deleted_at`, to the second) and a
  slug token swapped for an opaque id — operational metadata of the kind already disclosed for the media trash.
  No name, no key, no content.

### Rejected alternatives

- **(a) Hard-purge the subtree's media with the node** (mirroring `ScopePurge.eraseScope`). Cheap, and it does
  close the leak — but it breaks the security model's trash→purge rule in precisely the case that rule names, and
  it makes the system *more* destructive than it is today: the media rows currently survive, unreachable but
  intact, and this would delete them for good in a path with no confirmation, no undo and nothing written for the
  user. Choosing it would have meant amending a crosscutting policy first, not writing a change-request.
- **(b) Soft-delete the subtree's media into the per-Memory trash.** The trash is a tab on the Memory, so a trash
  whose Memory has been deleted has no surface at all — the items would be exactly as unreachable as they are
  today, with more machinery. It is a second feature (a trash reachable without its node), not a smaller slice of
  this one; Decision 3 is what that feature would have had to build anyway.
- **(c) A visible pending-deletion state on the node** (mirroring group deletion, Decision 2). It is the cheaper
  build and it has a real precedent, but a public share would keep serving a Memory the user just deleted for the
  whole window, and the tree accumulates visible clutter in the product's central surface. Grace-period
  visibility suits a space you keep working in, not a Memory you removed.
- **(d) A partial unique index on `(parent_id, slug_token)` filtered to live rows** — the standard answer, and
  unavailable: the schema is deliberately held to the H2 ∩ MariaDB subset and MariaDB has no filtered indexes.
  Decision 5 exists because of this constraint, not by preference.
- **(e) Storing `purge_after` as a column.** Both existing soft-deletes derive it from the timestamp plus the
  configured retention at the boundary. A stored copy would freeze the window at deletion time and drift from the
  operator's configuration.

## Related

- Use-cases: [uc.memory.delete-node](../features/memory/use-cases/uc.memory.delete-node.md) (the flow this
  decides), [uc.memory.delete-media](../features/memory/use-cases/uc.memory.delete-media.md) (the media trash
  this follows), [uc.spaces.delete-group](../features/spaces/use-cases/uc.spaces.delete-group.md) (the
  precedent one level up, and where it stops transferring)
- Enablers: [en.memory.tree](../features/memory/enablers/en.memory.tree.md) (the node lifecycle),
  [en.memory.media](../features/memory/enablers/en.memory.media.md) (what the purge reclaims)
- Cross-cutting: [security model](../crosscutting/security-model.md) § *Deletion & erasure* — the mechanism this
  decision joins rather than amends
- Decisions: [ADR 0014](0014-group-scopes-and-governance.md) §6 (the group grace period),
  [ADR 0012](0012-content-key-hierarchy.md) (`K_SC`/`K_mem`, and why the server cannot compute a slug token),
  [ADR 0013](0013-blob-storage-presigned-direct-to-store.md) (the blob store the purge reclaims from),
  [ADR 0033](0033-live-media-updates-revision-polling.md) (the revision reconcile the stale comment belongs to)
