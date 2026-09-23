# Enabler: The Memory tree (encrypted folder/Memory nodes)

- **ID:** en.memory.tree
- **Type:** **Enabler** — structural foundation, no standalone end-user goal
  ([features/README → use-case vs enabler vs ADR vs change-request](../../README.md#use-case-vs-enabler-vs-adr-vs-change-request)).
- **Provides:** the **encrypted node tree** inside a scope — **folders** (organization only) and **Memories**
  (the content unit, nested **Chapters**) — with **encrypted names**, **blinded slug tokens** for permalinks,
  and a **per-Memory content key `K_mem`** born on creation. Tree + names + keys only.
- **Serves (use-cases):** [uc.memory.browse](../use-cases/uc.memory.browse.md) (tree navigation + node CRUD),
  [uc.memory.gallery](../use-cases/uc.memory.gallery.md), [uc.memory.chapters](../use-cases/uc.memory.chapters.md),
  [uc.memory.delete-node](../use-cases/uc.memory.delete-node.md) (the node lifecycle — delete, trash, restore, purge).
  Media, the sealed node-meta (date / place), journal and sharing build on it **indirectly**, through
  [en.memory.media](en.memory.media.md) / [en.memory.node-meta](en.memory.node-meta.md). Creating an empty named
  node is not itself a user goal; it is the substrate those carry.
- **Depends on:** [en.spaces.personal-scope](../../spaces/enablers/en.spaces.personal-scope.md) (content lives
  in a scope) → and through it `uc.account.unlock` (the identity + scope key).
- **Preconditions:** An **unlocked** vault (identity → scope key → `K_SC` available for the active scope); a
  scope to write in (e.g. the [personal scope](../../spaces/enablers/en.spaces.personal-scope.md)).
- **Postconditions (success):** The node exists server-side as opaque ciphertext (encrypted name, `parentId`,
  `kind`, ordering, `slugToken`); a **Memory** additionally has a fresh random **`K_mem`** wrapped under
  `K_SC`. The server learned **no name** and holds **no content key**.

## Node model

A **node** has an opaque id, a `parentId` (or the scope root), a `kind`, an **encrypted name**, an
**`orderIndex`**, and a **`slugToken`**:

- **FOLDER** — organization only; **no content key**.
- **MEMORY** — the content unit; **owns a random `K_mem`** (wrapped under `K_SC`). Its media/journal live under
  `K_mem`.
- **CHAPTER** — a section **inside a Memory**, itself a content unit: it **owns its own random `K_mem`**, wrapped
  under its **parent Memory's `K_mem`** (recursive `K_mem`, one level deeper — *not* the parent's key, *not* a
  separate `K_chapter` type).

**Recursive `K_mem` (chapters — [ADR 0012](../../../decisions/0012-content-key-hierarchy.md)).** Every
content-bearing node (MEMORY *and* CHAPTER) carries its own random `K_mem`, wrapped under the **content key of
its parent** — a top-level Memory under `K_SC`, a chapter under its parent Memory's `K_mem` (same `content-key`
purpose, bound to the node the key belongs to — [ADR 0072](../../../decisions/0072-a-seal-binds-its-object.md)).
A media's **`K_blob` is wrapped under the `K_mem` of its *direct* parent node** (chapter media
under the chapter's key). Consequence of this layout: every content node is a **sharing unit**, and a move is a
single **re-wrap, never a data re-encrypt** — the *decision + rationale* (why recursive, not "shares the
parent's key") is ADR 0012.

Names, slugs and dedup key off **`K_SC`** (navigation), so one scope key decrypts the whole tree's structure;
a Memory's *content* needs its `K_mem` ([ADR 0012](../../../decisions/0012-content-key-hierarchy.md)).

## Main flow — create a folder / a Memory

1. The client picks a name; it computes the **slug token** `HMAC/HKDF(K_SC, parentId ‖ slugify(name))` (blind;
   the server can't reverse it) and **encrypts the name** under `K_SC`.
2. **If a Memory:** generate a fresh random **`K_mem`**, wrap it under `K_SC` (`symWrap(K_SC, K_mem)`).
3. POST the node: `{ parentId, kind, encryptedName, slugToken, orderIndex, wrappedContentKey? }` — all
   opaque to the server. The server enforces `UNIQUE(parentId, slugToken)` (slug uniqueness per parent) and
   the structural rules (no cycle; no folder inside a Memory), returns the node id.
4. The client renders the tree by **decrypting names with `K_SC`**; opening a Memory's content (later slices)
   unwraps its `K_mem` with `K_SC`.

```mermaid
---
title: Create a Memory (operator-blind)
---
sequenceDiagram
  actor U as Member<br>(unlocked, holds K_SC)
  participant C as Client
  participant S as Server<br>(blind)
  U->>C: new Memory "Italien 2026"
  C->>C: nodeId = random UUID #59; K_mem = random(32)
  C->>C: wrappedContentKey = seal(K_SC, content-key of scope, nodeId, epoch, K_mem)
  C->>C: encryptedName = seal(K_SC, node-name of scope, nodeId, epoch, name) #59; slugToken = HMAC(K_SC, parentId‖slug)
  C->>S: POST node { id:nodeId, kind:MEMORY, parentId, encryptedName, slugToken, wrappedContentKey }
  S->>S: refuse a taken id, enforce UNIQUE(parentId, slugToken) + structural rules
  S-->>C: created
  Note right of S: stores ciphertext + opaque tokens only — no name, no K_mem
```

## Organize — rename / move / reorder

- **Rename:** re-seal the name + **recompute the slug token** (its parent didn't change) → update the node.
- **Move (within the scope):** set a new `parentId`, **recompute the slug token** for the new parent, with
  cycle / containment checks. **No cross-scope move** — that is a copy + re-encrypt under the target scope's
  `K_SC`/`K_mem` (a separate use-case), never a tree move.
- **Reorder:** update `orderIndex` among siblings.

## Delete — soft-delete, trash, purge

A node delete is **reversible** and takes the **whole subtree** with it
([ADR 0061](../../../decisions/0061-node-deletion-reversible-node-trash.md),
[uc.memory.delete-node](../use-cases/uc.memory.delete-node.md)). It is the same
**soft-delete → trash → retention → scheduled hard-purge** mechanism the
[security model](../../../crosscutting/security-model.md) mandates for every destruction — a node is not an
exception to it.

- **Delete:** mark the node and every descendant `deletedAt = now` in one transaction. **Nothing is destroyed** —
  the subtree's media, derivatives, buckets and blobs are untouched, which is what makes a restore complete. An
  already-trashed *root* is a no-op; an already-trashed *descendant* is **re-stamped**, so the whole subtree ages
  out on one timestamp and the purge can never reach half of it.
- **The slug is freed at delete**, so the name is immediately reusable: the deleted node's `slugToken` is replaced
  by its own id. `UNIQUE(parentId, slugToken)` is a plain unique index and the schema is held to the H2 ∩ MariaDB
  subset, so a partial index over live rows is not available. The server **cannot** recompute a slug token (it is
  a blinded MAC under `K_SC`), so the original is not kept — **restore carries a freshly client-computed token**,
  and a collision is the ordinary disambiguation a create already handles.
- **Hidden, not marked.** Every user- and guest-facing read carries `deletedAt is null`. Four reads deliberately
  do **not**: slug uniqueness (it must keep seeing the row, or validation passes and the database rejects the
  insert afterwards), the delete's own subtree traversal (or re-deleting a partially deleted subtree stops early),
  the delete's own scope binding (or a re-delete 404s instead of being the promised no-op — the *update* path
  keeps the filtering lookup, so these are two lookups of one node, not one), and the whole-space erase (which
  must remove trashed rows too).
- **Restore takes a trash ROOT only**, and may re-seal the name in the same write — that is the taken-name retry.
  Clearing a trashed node under a trashed parent would leave a live node hanging under a dead one, which no
  listing can show: the client assembles the tree by `parentId`, and the parent is filtered out.
- **Both share reads carry the predicate** — the anonymous resolve *and* the blob authorization. With `deletedAt`
  set but these unfiltered, a share on a deleted Memory serves the full sealed subtree and presigns every blob
  under it. The row surviving is exactly what makes this dangerous.
- **Purge:** a scheduled job hard-erases expired subtrees — media, derivative, bucket and blob rows in FK order,
  then the store objects, then the node rows. The **cover-blob reclaim belongs here, not in the delete**, or a
  restore returns a Memory whose cover is already gone.

## Alternate / exception flows

- **E1 — slug collision** (same parent already has that slug): the server rejects on `UNIQUE(parentId,
  slugToken)`; the client disambiguates (append a counter) and retries.
- **E2 — illegal structure:** a folder inside a Memory, or a cycle on move → rejected (structural invariant).
- **E3 — vault locked / no scope key:** without `K_SC` the client can neither name nor key a node → the
  action is unavailable until unlock + the scope is open.
- **E4 — not a member of the scope:** the server has no grant for the caller → the scope (and its tree) is
  not reachable.

## Quality notes

- **Operator-blind.** The server stores ciphertext names + opaque `parentId`/`kind`/`orderIndex`/`slugToken`
  + the `K_mem` wrap; it never sees a name or a content key. It learns only the **shape** of the tree.
- **`K_mem` is the share grain** ([ADR 0012](../../../decisions/0012-content-key-hierarchy.md)): every content
  node — a Memory **or a chapter** — is born with a random `K_mem` so it can later be shared (an extra wrap of
  `K_mem`) without exposing `K_SC`, the parent, or any other node. Folders, being keyless, are never shared —
  you share a Memory or a chapter.
- **Blinded permalinks.** The slug token gives human-readable, unique-per-parent permalinks without the
  operator ever seeing or reversing a name (keyed MAC under `K_SC`).
- **Scope of this slice.** Tree + names + `K_mem` only. **Media (`K_blob` + chunked AEAD), journal, places &
  tracks, sharing, and rich curation** are their own slices on top of this.

> **No end-user section.** As an enabler this has no standalone "what this means for you" story — its
> end-user value surfaces through the use-cases it *serves* (add media / journal / organize / share), whose
> bilingual sections carry the wiki text.

## Related

- Feature: [Memory](../_index.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md) (key hierarchy, blinded tokens),
  [Scopes & Tenancy](../../../crosscutting/scopes-and-tenancy.md) (navigation contract; content in a scope),
  [Account & Key Model](../../account/enablers/en.account.key-model.md)
- Decisions: [ADR 0012](../../../decisions/0012-content-key-hierarchy.md) (`K_SC`/`K_mem`/`K_blob`),
  [ADR 0017](../../../decisions/0017-crypto-primitives-and-post-quantum-strategy.md) (vector-pinned content crypto)
- Related: [en.spaces.personal-scope](../../spaces/enablers/en.spaces.personal-scope.md) (the enabler this
  builds on — where the first tree lives), and the planned add-media / journal / share-a-Memory use-cases
  ([Memory feature](../_index.md)) this serves
