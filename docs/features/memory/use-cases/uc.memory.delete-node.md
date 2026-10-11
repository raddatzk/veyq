# UC: Delete a Memory or Chapter — remove a whole subtree into a recoverable space trash

- **ID:** uc.memory.delete-node
- **Primary actor:** A member with an **unlocked vault** browsing a space who wants to **remove** a Memory, a
  Chapter or a folder — together with everything inside it.
- **Goal:** Take a node and its whole subtree out of the tree — it **disappears from the tree and from any public
  share immediately**, sits in the **space's trash** for a retention window, and is **hard-purged** (nodes, media,
  derivatives, buckets, blobs and store objects) by a scheduled system job afterwards. **Operator-blind** — the
  server sets a lifecycle timestamp and, at purge, removes opaque rows and blob objects; it never sees a name or a
  key.
- **Preconditions:** The vault is unlocked and the scope is open (`K_SC` is held); the caller **belongs to the
  scope**, and the scope is not itself pending deletion. The node tree exists
  ([en.memory.tree](../enablers/en.memory.tree.md)) and the media data layer the purge reclaims from
  ([en.memory.media](../enablers/en.memory.media.md)).
- **Postconditions (success):** The node and every descendant are gone from the tree on the next load, gone from
  the anonymous share resolve, and their blobs are no longer authorized for a presign. The subtree sits in the
  **space's Trash**, restorable, until the retention window elapses; then a scheduled job **irreversibly purges**
  it. Nothing under the subtree was destroyed at the moment of the delete.

## What deletion is here

Deletion is the same **soft-delete → trash → retention window → scheduled hard-purge** flow the
[security model](../../../crosscutting/security-model.md) mandates for every destruction, applied to a whole
subtree instead of one item ([ADR 0061](../../../decisions/0061-node-deletion-reversible-node-trash.md)). The two
steps carry **different authority**: deleting is reversible, so any member of the scope may do it; purging is
irreversible and belongs to the scheduled job. A member never destroys another member's live content in one step
— which a node delete would otherwise do, because the media inside a Memory carry an owner and its buckets are
per-owner.

The trash for nodes hangs off the **space**, not off the node. It has to: a trashed *medium* lives in a tab on its
Memory, but a trashed *Memory* has no Memory to hang from. This is the difference that made the obvious cheaper
design impossible ([ADR 0061](../../../decisions/0061-node-deletion-reversible-node-trash.md) § Rejected (b)).

## Main flow

1. In the tree the user deletes a node — the existing delete affordance, with its confirmation naming what goes
   with it (a Memory takes its Chapters and all their photos).
2. The client calls **delete** → the server marks the node **and its whole subtree** soft-deleted (`deleted_at =
   now`) in one transaction. Nothing else is touched: media, derivatives, buckets and every blob stay exactly as
   they were, which is what makes the restore cheap and complete.
3. In the same step the server **frees the node's slug**, so the name is immediately reusable: the deleted node's
   `slugToken` is replaced by its own id — the deleted root's only: a descendant's name lives under a trashed
   parent, where nothing can claim it, so it keeps its token (inbox #357). The server cannot recompute a slug token — it is a blinded MAC under
   `K_SC` ([ADR 0012](../../../decisions/0012-content-key-hierarchy.md)) — so the original is not kept and the
   client supplies a fresh one on restore (step 6).
4. The subtree leaves every read at once: the tree listing, the media listings, bucket listings and cover
   operations, **and both public-share paths** — the anonymous resolve stops listing it *and* its blobs stop being
   authorized for a presign. Both, or neither: filtering only the listing would leave a Memory invisible in the
   share and still fully downloadable by anyone holding a `blobId`.
5. The space's **Trash** lists soft-deleted subtree roots with the date they were deleted and the date they will
   be purged (derived from the retention window, not stored). Only the root of each deleted subtree is listed —
   its descendants went with it and come back with it.
6. **Restore** returns the subtree to the tree in one step, and only from a trash **root** — a trashed node under
   a trashed parent comes back with that parent, never on its own. The client recomputes the node's `slugToken`
   for its parent and sends it with the restore; the server clears `deleted_at` across the subtree and writes the
   token back. Everything inside — photos, chapters, buckets, cover — returns intact, because none of it was ever
   removed. One exception: a descendant an earlier, narrower delete trashed had its own name freed then (step 3),
   and a sibling may hold it now; the server cannot re-derive its token, so it and its subtree **stay in the trash**,
   listed as a root again under the now-live parent, and restore on their own through this same step (A1 if the
   name is taken) (inbox #357).
7. After the **retention window** (operator-configurable, the same shape as the media trash), a **scheduled purge
   job** hard-erases each expired subtree: its media, derivative, bucket and blob rows in FK order and the store
   objects behind them, then the node rows. This step is **irreversible** and is **not** a member action.

## Alternate / exception flows

- **A1 — restore into a taken name.** A node created in the meantime already holds the slug the restore wants.
  The restore is rejected on the slug-uniqueness constraint and the client disambiguates and retries — the same
  path a create already takes, so the retry carries the **new name and its token together, in one request**: sent
  apart, the node is briefly live carrying a slug its own name does not produce, and permanently so if the second
  write fails. Silently renaming, or refusing the restore outright, would both be worse.
- **A2 — restore after purge.** Once the scheduled job has run the subtree is **gone** (a non-enumerable 404); the
  retention window is the only recovery path. There is no post-purge undo — that is the point of erasure.
- **E1 — the scope is itself pending deletion.** The delete is rejected as a conflict, like every other write
  during a space's grace window ([uc.spaces.delete-group](../../spaces/use-cases/uc.spaces.delete-group.md)).
- **E2 — not a member / vault locked.** The delete and restore are scope-membership-gated; a non-member gets a
  non-enumerable **404** (operator-blind), and a locked vault cannot open the space to reach the affordance.
- **E3 — concurrent / idempotent.** Deleting an already-deleted subtree is a no-op — the marker does not move,
  so a double delete cannot silently extend the window. Deleting a subtree that is only *partially* deleted is
  the opposite: it **re-stamps** every node in it, including descendants an earlier, narrower delete had already
  trashed, and the traversal deliberately does **not** skip them. A subtree ageing out on two timestamps has its
  inner rows purged first, and the restore then hands back an empty shell — that is what "stops early and strands
  the rest" costs, and it is observable against the purge rather than against the marker.
- **E4 — a share pointing at the deleted node.** While the node is trashed the share row survives and stays
  listed; it simply resolves to nothing, and starts working again on restore — trashing the Memory is not a revoke.
  **Erasing the Memory deletes its links** ([ADR 0029](../../../decisions/0029-public-viewer-share.md) §7): the
  purge tombstones every share on the erased nodes, so the link `404`s for good and its ciphertext goes, and the
  guest identities nothing references any more go with it.
- **E5 — an upload or a contribution lands mid-delete.** A contributor upload through a share targets the node
  directly; once the node is trashed those writes are refused on the same gate as every other write to it.

## Quality notes

- **Nothing is destroyed by the user's action.** The delete writes a timestamp and swaps a token. Every byte
  survives the whole window, which is what makes a restore complete rather than partial — and it is why the cover
  blobs are reclaimed by the **purge** and not by the delete: reclaiming them when the user deletes hands a restore
  back a Memory whose cover is already gone.
- **The dangerous half is the share path, not the tree.** Before this flow, a share on a deleted node resolved to
  an *empty* subtree only because the node row was gone. Once the row survives, the same code would serve the full sealed
  subtree and presign every blob under it — unauthenticated. The predicate on **both** share reads is what keeps
  this a fix rather than a regression.
- **Four reads must keep seeing deleted rows.** Slug uniqueness (or validation passes and the database rejects
  the insert afterwards — a clean rejection turned into a server error), the delete's own subtree traversal (E3),
  the delete's own scope binding (or a re-delete answers 404 instead of the no-op E3 promises, while a foreign
  node must keep answering the identical non-enumerable 404 — so delete and update need two different lookups of
  the same node), and the whole-space erase, which must remove every node row including the trashed ones.
- **Reuses the shared per-media purge body**, so the purge inherits the established FK order and writes the
  store objects to the reclaim list inside the same transaction, rather than growing a fourth copy of that logic
  ([ADR 0061](../../../decisions/0061-node-deletion-reversible-node-trash.md) Decision 7,
  [ADR 0062](../../../decisions/0062-store-object-reclaim-is-a-durable-work-list.md)). The bytes go on the next
  sweep tick; nothing readable references them in between.
- **Operator-blind, unchanged.** The added state is a lifecycle timestamp (to the second) plus a slug token replaced by
  an opaque id — the same class of operational metadata the media trash already discloses. No name, no key, no
  content.

## What this means for you / Was das für dich bedeutet

**EN —** Delete a memory or a chapter and it goes away right away — out of your space, and out of any share link
you handed out. But it is not gone for good yet: it waits in your space's trash, and you can put it back with
everything still in it — every photo, every chapter, the cover, exactly as it was. Only after the waiting period
does the system erase it for good, automatically. The name is free again the moment you delete, so you can
immediately make a new memory with the same title. Everything stays encrypted throughout: the server only notes
that something was deleted and later removes scrambled files it cannot read.

**DE —** Löschst du eine Erinnerung oder ein Kapitel, ist es sofort weg — aus deinem Space und aus jedem
Share-Link, den du weitergegeben hast. Endgültig ist es aber noch nicht: es wartet im Papierkorb deines Space, und
du kannst es vollständig zurückholen — jedes Foto, jedes Kapitel, das Titelbild, genau wie vorher. Erst nach
Ablauf der Frist löscht das System es automatisch für immer. Der Name ist sofort wieder frei, du kannst also
gleich eine neue Erinnerung mit demselben Titel anlegen. Alles bleibt dabei verschlüsselt: der Server merkt sich
nur, dass etwas gelöscht wurde, und entfernt später verschlüsselte Dateien, die er nicht lesen kann.

## Related

- End-user view (wiki): [Files — and finding your way around](../../../../wiki/en/files.md)
- Feature: [Memory](../_index.md)
- Enablers: [en.memory.tree](../enablers/en.memory.tree.md) (the node lifecycle this defines),
  [en.memory.media](../enablers/en.memory.media.md) (what the purge reclaims)
- Use-cases: [uc.memory.browse](uc.memory.browse.md) (the tree the delete acts on),
  [uc.memory.chapters](uc.memory.chapters.md) (a chapter is deleted the same way),
  [uc.memory.delete-media](uc.memory.delete-media.md) (the per-Memory media trash this parallels),
  [uc.spaces.delete-group](../../spaces/use-cases/uc.spaces.delete-group.md) (the same lifecycle one level up),
  [uc.sharing.share-public-viewer](../../sharing/use-cases/uc.sharing.share-public-viewer.md) (the guest paths
  the subtree must leave)
- Cross-cutting: [security model](../../../crosscutting/security-model.md) — *Deletion & erasure* (the
  reversible/irreversible split this flow implements)
- Decisions: [ADR 0061](../../../decisions/0061-node-deletion-reversible-node-trash.md) (this flow's decision and
  its rejected alternatives), [ADR 0012](../../../decisions/0012-content-key-hierarchy.md) (why the server cannot
  compute a slug token), [ADR 0013](../../../decisions/0013-blob-storage-presigned-direct-to-store.md) (the blob
  store the purge reclaims from), [ADR 0014](../../../decisions/0014-group-scopes-and-governance.md) §6 (the
  group grace period)
