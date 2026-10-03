# Enabler: Media uploader buckets (`owner_key` + `K_mem`-sealed names, virtual primary + sub-buckets)

- **ID:** en.memory.buckets
- **Type:** **Enabler** — structural foundation, no standalone end-user goal
  ([features/README → use-case vs enabler vs ADR vs change-request](../../README.md#use-case-vs-enabler-vs-adr-vs-change-request)).
- **Provides:** the **bucket data layer** for a Memory — a per-**uploader** container within a Memory node, keyed
  by an opaque **`owner_key`**, with a **lazy, virtual primary** bucket per uploader + optional **one-level
  sub-buckets**, names **sealed under `K_mem`**, and media attached through a nullable **`bucket_id`**. It is the
  **attribution + organization substrate** (who contributed a medium, and into which of *their* containers); the
  *experience* — the "Beiträge" grouping tab, the sub-bucket management UI — is the use-cases on top.
- **Serves (use-cases):** [uc.memory.buckets](../use-cases/uc.memory.buckets.md) (the "Beiträge" per-uploader
  **primary grouping**) and, as a later slice, **sub-bucket management** (create/rename/move/delete folders). It
  also **grounds the grouping** the contributor share earmarked
  ([uc.sharing.share-contributor](../../sharing/use-cases/uc.sharing.share-contributor.md), ADR 0030).
- **Depends on:** [en.memory.media](en.memory.media.md) (the media rows a bucket groups + the upload/attach path),
  [en.memory.tree](en.memory.tree.md) (a **Memory** owns a `K_mem` — the key the sealed names wrap under), and the
  anonymous **share identity** ([ADR 0030](../../../decisions/0030-contributor-public-share.md) / cr-0101) for the
  guest `owner_key`.
- **Preconditions:** an **unlocked** vault; an **open Memory** whose `K_mem` is unwrapped (bucket names + any
  sub-bucket names wrap under it — except an UPLOADER guest's primary, whose name is under that guest's own key,
  [ADR 0031](../../../decisions/0031-uploader-public-share.md) point 7); media with an uploader (a member or a share
  contributor).
- **Postconditions (success):** each medium carries a **denormalized `created_by`** (the uploader's `owner_key`)
  and a **`bucket_id`** (`null` = the uploader's primary, resolved lazily). Buckets exist as **rows only when
  materialized** (lazy). The server holds **opaque `owner_key`s + the bucket structure + sealed names** — **no
  cleartext name, no content**.

## The bucket model (ADR 0034)

- **`owner_key`** — **one opaque string** identifying the uploader: `user:{userId}` for a **member**,
  `share:{shareIdentityId}` for a **contributor** (reusing the existing cr-0101 identity as the grouping key). It
  is **operator-visible** (like membership and the existing `share_identity_id` stamp) and lets the server
  **scope** each medium and bucket to an opaque **`owner_key`** without knowing identity semantics — collapsing
  the fragile pre-E2EE two-column `uploader_user_id`/`share_identity_id` XOR into one field. (It scopes *moves*
  and grouping; it is **not** the authz gate — sub-bucket curation is member-gated, see below.)
- **`veyq_bucket`** (a row per materialized bucket):

  ```
  id, node_id,
  owner_key,                 -- "user:{userId}" | "share:{shareIdentityId}"
  parent_bucket_id?,         -- null = primary; else exactly ONE level under a primary
  sealed_name? + suite_id,   -- AEAD under K_mem; primary's derived name is sealed too; sub-buckets carry their own
  is_primary
  -- One primary per (node_id, owner_key) -- content-blind (owner_key is not ciphertext); held by the node
  -- row's lock, not an index (V35 carries only a plain ix_bucket_node_owner). See "Lifecycle" below.
  -- Sub-bucket names are NOT unique either way (ciphertext, the server can't compare them).
  ```
- **`veyq_media` gains:** **`bucket_id`** (nullable FK, **`ON DELETE SET NULL`**, `null` = the owner's primary) and
  **`created_by`** (the `owner_key`, copied onto the medium **on attach** — denormalized so grouping and
  move-authorization never depend on the bucket row).

## Lifecycle

- **Virtual-until-upload primary.** A primary bucket materializes **lazily** — a find-or-create keyed on
  `(node_id, owner_key)` on the uploader's **first upload** (or first sub-bucket). An uploader with no media has
  **no row** and does not appear. Its name is **derived** (member = the per-tenant username; contributor = the
  identity's sealed name) and **sealed under `K_mem`** so the grouping view is uniform + operator-blind; the
  primary is **not renameable**. Exactly **one primary per `(node, owner_key)`**: the find-or-create runs under
  the node row's lock, so two concurrent first uploads share one
  ([ADR 0034](../../../decisions/0034-media-uploader-buckets.md) §4).
- **Sub-buckets.** **One level** under a primary (the parent must be a primary), **owner inherited** from the
  parent, a **`K_mem`-sealed** user-chosen name. **No reparenting**, no arbitrary nesting.
- **Reparent-on-delete.** Deleting a sub-bucket sets its media **`bucket_id = null`** (they fall back to the
  owner's primary) — media are **never lost**. **Primaries are not deletable.** *(This reverses the pre-E2EE
  code, which hard-deleted a sub-bucket's media — see ADR 0034.)*
- **Move.** Media move (bulk) **within a single owner-root** — the target bucket must share the source media's
  `owner_key`; there is **no cross-owner move**. Any member may do it, even re-bucketing a contributor's media
  within that contributor's root (see authz below).

## Ownership & operator-blindness

- **Member-gated sub-bucket authz (server-enforced, cr-0123).** Create / rename / delete a **sub-bucket** and
  **move media** are gated to **scope membership** only — **any member** may curate **any owner-root's**
  sub-buckets (its own *or* a contributor's), but **never a primary** (primaries are neither created, renamed,
  nor deleted through these ops) and **never across owners** (a move stays within one owner-root). **Delete
  media** and **edit capture time** stay **any key-holder** (unchanged from the media data layer). The server
  enforces on membership + the opaque `owner_key`'s structural role, never on content. *(cr-0123 deliberately
  relaxed the original owner-only gate to member — so a curator can tidy a contributor's uploads; a reader must
  not infer the stronger `owner_key == caller` guarantee, which the code does not give.)*
- **What the operator sees** ([security-model](../../../crosscutting/security-model.md)): the opaque `owner_key`s,
  the **bucket structure** (node, parentage, `is_primary`, per-bucket counts), and `bucket_id`/`created_by` on
  media — **coarse operational metadata**, consistent with membership + the cr-0101 contributor stamp. **What it
  never sees:** the **sealed names** (`sealed_name`) or any content. A personal space with no share stays wholly
  confidential — its only owner_key is the account owner's.

## Related

- **Feature:** [memory](../_index.md)
- **Serves:** [uc.memory.buckets](../use-cases/uc.memory.buckets.md) (primary grouping) + later sub-bucket
  management; grounds [uc.sharing.share-contributor](../../sharing/use-cases/uc.sharing.share-contributor.md).
- **Depends on:** [en.memory.media](en.memory.media.md), [en.memory.tree](en.memory.tree.md),
  [ADR 0030](../../../decisions/0030-contributor-public-share.md) (the guest identity).
- **Decisions:** [ADR 0034](../../../decisions/0034-media-uploader-buckets.md) (the bucket model + the
  reparent/authz rules).
