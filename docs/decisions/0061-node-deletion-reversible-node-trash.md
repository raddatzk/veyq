# ADR 0061 — Deleting a Memory is reversible: a hidden node trash, filtered on read

- Status: Accepted
- Built: yes
- Date: 2026-08-17
- Model: [en.platform.node-tree](../features/platform/enablers/en.platform.node-tree.md)
- Replaced in part: decision 7 by ADR 0062 (built)

## Context

Deleting a node (a Memory, a Chapter, a folder) was **immediate and irreversible**, and it reclaimed only part of
what it removed: the subtree's node rows and its standalone **cover** blobs, and nothing else — the subtree's media,
derivative and bucket rows survived, pointing at a node that no longer existed. They were then unreachable *and*
unreclaimable: every read filters on scope and node, they were not trashed so the media trash purge never saw them,
and their blobs stayed legitimately referenced so no unreferenced-blob predicate could reach them.

The forces that meet here:

- **The crosscutting policy already fixes the shape of destruction.** The
  [security model](../crosscutting/security-model.md) § *Deletion & erasure* mandates **soft-delete → trash →
  retention window → scheduled hard-purge**, and states that destruction always goes **trash → purge**, never a
  one-step "delete forever" of another member's live content. A node delete is exactly that case: media carry an
  owner, and buckets are per-owner, so in a shared scope one member's node delete destroys another member's live
  uploads in one step.
- **The bigger deletion is already reversible.** Deleting a whole group enters a grace period with a restore
  ([ADR 0014](0014-group-scopes-and-governance.md) §6). A group being recoverable for weeks while a single
  Chapter inside it is destroyed instantly is not a defensible product story.

## Decision

**1. A node delete is a soft-delete.** A node carries a nullable deletion instant, and a delete marks the doomed
subtree instead of removing it. Nothing is destroyed at the moment of the user's action, so the media,
derivatives, buckets and blobs under it are untouched and a restore is cheap.

**2. A soft-deleted node is hidden, not shown as pending.** Every user-facing and guest-facing read carries
`deletedAt is null`. This is the one point where the group-deletion precedent does **not** transfer: a space's
soft-delete deliberately *surfaces* the marker (the scope list returns pending-deletion scopes and the client
renders them), which is right for a space you are still working in and wrong for a Memory the user believes they
deleted. The closer template is the **media trash** — a deletion instant with filter-on-read — and that is the one
we follow.

**3. The node trash is per-scope, and that is what makes it work at all.** Trashed *media* live in a tab on
their Memory; a trashed *node* cannot, because its own Memory is what was deleted. The node trash therefore
hangs off the space, reachable without the node — which is also why soft-deleting a node's media into the
per-Memory trash was never a real option (§ Rejected (b)). This settles the trash grain for **nodes**.

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
a slug token at all (it is an HMAC of the slug in the parent's slot, keyed under the parent's content key — `K_SC`
at the scope root, [ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md) Decision 2). Only the root:
its descendants' names live under a parent that is trashed too, where nothing can claim them while it stays so, so
they keep their tokens and come back valid (freeing every node would return each descendant holding no name,
beside which a same-named sibling could be created). **Restore carries a
freshly computed `slugToken` from the client**, and a collision with a node created in the meantime is an
ordinary disambiguation, the same one a create already handles — which means the restore must also be able to
carry a **re-sealed `encryptedName`**, in the *same* request. The retry is "put it back under a different name",
and a name sent apart from the token derived from it leaves the node live, briefly, carrying a slug its own name
does not produce; a failure in between leaves it that way for good.

**6. The purge is a scheduled job, and the blobs must outlive the grace window.** A node purge job hard-erases
subtrees whose deletion is older than the retention window: media, derivatives, buckets, cover blobs and the store
objects. The cover-blob reclaim belongs to it, never to the delete — reclaiming covers at soft-delete time would make
a restore return a Memory whose cover is gone. `purgeAfter` is **derived**
at the resource boundary from `deleted_at` plus the configured retention, not stored, matching both existing
soft-delete precedents.

**7. Row-then-object ordering, per item.** Core's purge hands the subtree to each application's own eraser
([ADR 0079](0079-veyq-is-a-platform-memories-is-its-first-application.md); the `NodeSubtreeEraser` seam,
[Applications](../apps/README.md)), and each erases its rows per item through its shared per-item body — Memories'
per-media purge: rows in FK order inside the transaction, and the store objects recorded for reclaim in that same
transaction ([ADR 0062](0062-store-object-reclaim-is-a-durable-work-list.md)) — never by the set-based shape of the
scope erase. That one is the exception because it erases *by scope* and its final scope-row delete lets the
database cascade the rest. A node subtree is not a scope — neighbouring nodes, buckets and the scope row all
survive — so the FK-ordered per-item body is the correct shape, and a set-based copy would be one more copy of
logic that already exists.

## Consequences

- **Deletion stops being destruction.** A mis-click is recoverable for the retention window, and the operator
  stops paying for bytes no user can reach — the leak closes because the purge now owns the whole subtree, not
  just its covers.
- **The policy is honoured rather than amended.** Node deletion joins the one mechanism the security model
  already mandates, instead of becoming a second, one-step destruction path beside it.
- **The cost is a read-path sweep:** every read that reaches a node gains the predicate, the bucket operations
  included.
- **Four reads must deliberately *not* filter, and each is a trap** — slug uniqueness, the delete's own subtree
  traversal (which re-stamps descendants, so a subtree ages out on one marker), the delete's own scope binding, and
  the scope hard-erase. Each one's failure mode is in
  [en.platform.node-tree § Delete](../features/platform/enablers/en.platform.node-tree.md#delete--soft-delete-trash-purge). A
  re-stamped descendant that was an earlier delete's root holds that delete's freed token, so the restore of the
  folder around it leaves it — and its subtree — in the trash, a trash root of its own restorable with a fresh token
  (returning it would bring back a node with no name, beside a sibling that may hold its old one).
- **A restore can fail, and that is correct.** If the name was taken in the meantime the client disambiguates,
  exactly as it does when creating. A restore that silently renamed, or one that refused forever, would both be
  worse.
- **Only the trash's own roots restore.** Clearing a trashed node whose parent is still trashed would leave a
  live node hanging under a dead one — present in no listing at all, because the client assembles the tree by
  `parentId` and the parent is filtered out. It is the reason the trash lists roots only, enforced on the write
  side as well as the read side.
- **Operator-blindness is unchanged.** The added state is a lifecycle timestamp (`deleted_at`, to the millisecond) and a
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
- Enablers: [en.platform.node-tree](../features/platform/enablers/en.platform.node-tree.md) (the node lifecycle),
  [en.memory.media](../features/memory/enablers/en.memory.media.md) (what the purge reclaims)
- Cross-cutting: [security model](../crosscutting/security-model.md) § *Deletion & erasure* — the mechanism this
  decision joins rather than amends
- Decisions: [ADR 0014](0014-group-scopes-and-governance.md) §6 (the group grace period),
  [ADR 0012](0012-content-key-hierarchy.md) and [ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)
  (the node keys, and why the server cannot compute a slug token),
  [ADR 0013](0013-blob-storage-presigned-direct-to-store.md) (the blob store the purge reclaims from),
  [ADR 0033](0033-live-media-updates-revision-polling.md) (the revision reconcile the stale comment belongs to)
