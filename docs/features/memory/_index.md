# Feature: Memory

The product center: a **Memory** (photos, media, a journal, places & tracks — added in later slices),
organized in an **encrypted tree** of **folders** (organization only) and **Memories** (nested into
**Chapters**). Everything content lives inside a [scope](../spaces/_index.md) and is **end-to-end encrypted**
— the server stores only ciphertext, opaque node ids/parents/kinds/ordering/timestamps, and blinded slug
tokens; it never sees a name or a content key.

- **Cross-cutting:** [Security Model](../../crosscutting/security-model.md) (the key hierarchy + sharing),
  [Scopes & Tenancy](../../crosscutting/scopes-and-tenancy.md) (the navigation contract; content lives in a
  scope), [Account & Key Model](../account/enablers/en.account.key-model.md).
- **Decisions:** [ADR 0012](../../decisions/0012-content-key-hierarchy.md) (per-Memory content key `K_mem`,
  random + many-wrapped — the unit of sharing; `K_SC` for navigation; `K_blob` per blob),
  [ADR 0017](../../decisions/0017-crypto-primitives-and-post-quantum-strategy.md) (the vector-pinned content crypto).
- **Product language (hard rule):** Memory / Chapter. Folders **organize only**. Never "Album" / "Explorer".

## Key model (ADR 0012, in one line)

`scope key (grant) → K_SC` (per scope: names + slug + dedup) `→ K_mem` (per Memory, random: chapter/journal/
media + sensitive metadata; **the share grain**) `→ K_blob` (per media blob). A folder carries a random key of its own
too, and a Memory inside it wraps under the folder's key instead of `K_SC` ([ADR 0080](../../decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)).

## Enablers

- ★ [en.memory.tree](enablers/en.memory.tree.md) — **refined** (the encrypted node tree: FOLDER/MEMORY/CHAPTER,
  encrypted names, blinded slugs, per-Memory `K_mem` birth/open — the substrate the use-cases below build on;
  [ADR 0012](../../decisions/0012-content-key-hierarchy.md))
- ★ [en.memory.media](enablers/en.memory.media.md) — **refined** (the encrypted media data layer: a blob sealed
  as chunked AEAD under a random `K_blob` wrapped under `K_mem`, transferred direct-to-store via presigned URLs,
  attached to a node; **no content dedup** — operator-blind trade-off;
  [ADR 0013](../../decisions/0013-blob-storage-presigned-direct-to-store.md),
  [ADR 0012](../../decisions/0012-content-key-hierarchy.md))
- ★ [en.memory.favorites](enablers/en.memory.favorites.md) — **built** (pin a Memory: the per-(user, scope)
  pinned-id set sealed under `K_SC` as one opaque blob — operator-blind, no plaintext favorite flag)
- ★ [en.memory.derivatives](enablers/en.memory.derivatives.md) — **refined** (per-item **thumbnail / preview /
  original** tiers — grid = thumbnail, lightbox = preview, download = original; client-generated encrypted
  blobs, **per-blob `K_blob`**, role-tagged on the media record (`original` + `derivatives[{kind, blob}]`), no
  backfill, first-frame video poster. **Built before the gallery** — where
  [ADR 0013](../../decisions/0013-blob-storage-presigned-direct-to-store.md)'s deferred thumbnails/previews land)
- ★ [en.memory.node-meta](enablers/en.memory.node-meta.md) — **partly built** (the sealed, client-versioned
  `wrappedMeta` blob on a content node — one opaque envelope for a Memory's node-level metadata, sealed under
  `K_mem`, threaded through `createNode` / partial `updateNode` / `listNodes`, materialized so a card/map reads
  it without loading media. Envelope + `date` sub-object shipped cr-0067; `location` sub-object planned)
- ★ [en.memory.buckets](enablers/en.memory.buckets.md) — **refined** (the bucket data layer: a per-uploader
  container within a Memory, a real `owner_key`-keyed `veyq_bucket` table — `user:{id}` / `share:{id}` — with a
  lazy virtual primary + one-level sub-buckets, names sealed under `K_mem`, media linked via a nullable `bucket_id`
  + denormalized `created_by`; reparent-on-delete + member-gated sub-bucket authz (cr-0123);
  [ADR 0034](../../decisions/0034-media-uploader-buckets.md))

## Use-cases (on the tree + media enablers)

- Browse & organize the Memory tree — the shell + folder/Memory browse + create/rename/delete
  ([uc.memory.browse](use-cases/uc.memory.browse.md) — **done**)
- [uc.memory.upload](use-cases/uc.memory.upload.md) — **built (web)** (cr-0058): add media to a Memory — the web
  upload flow + global uploads tray on [en.memory.media](enablers/en.memory.media.md) — `K_blob` + chunked AEAD,
  direct-to-store; the upload also generates the [derivatives](enablers/en.memory.derivatives.md).
  **Resumable upload is the open slice** (a dropped large upload restarts from zero)
- [uc.memory.gallery](use-cases/uc.memory.gallery.md) — **built (web)** (cr-0049): open a Memory — thumbnail grid
  + lightbox preview + download original + header/favorite, on
  [en.memory.derivatives](enablers/en.memory.derivatives.md). Extended by cr-inline-video-playback Slice 2
  ([ADR 0047](../../decisions/0047-inline-video-playback-range-streaming.md), service-worker range streaming).
  **Open in the lightbox:** no rescale on device rotation, no zoom
- [uc.memory.chapters](use-cases/uc.memory.chapters.md) — **refined** (nested Memories inside a Memory — each
  chapter a full Memory with its own recursive `K_mem`, wrapped under the parent's;
  [ADR 0012](../../decisions/0012-content-key-hierarchy.md))
- [uc.memory.date](use-cases/uc.memory.date.md) — **done** (cr-0067): a curatable date (auto/single/range/ongoing),
  sealed on the node's `wrappedMeta`; browse card range + date-sort
- [uc.memory.place](use-cases/uc.memory.place.md) — **planned**: one named location on the same `wrappedMeta`
  (medoid position + typed/reverse-geocoded name), a tenant-wide map; reverse-only, veyq-blind geocoding (ADR 0016)
- [uc.memory.cover](use-cases/uc.memory.cover.md) — **planned** (cr-0072): a deliberately chosen, cropped cover
  (`{ mediaId, crop }` on the same `wrappedMeta`), shown on the browse card, the detail header, the map pin and
  the sidebar's Favorites entry — one decrypt per Memory per scope, shared by all four
- [uc.memory.concurrent-curation](use-cases/uc.memory.concurrent-curation.md) — **built**
  ([ADR 0077](../../decisions/0077-a-write-carries-the-version-it-read.md)): two members curate one Memory at once —
  every write of a sealed meta blob (a Memory's date/place/cover, a photo's date/place name/type) states the
  `metaVersion` it read, a stale one answers `409 stale_version` with the current blob, and the writer re-applies
  only its own change; a blob that does not open is never rebuilt from empty
- [uc.memory.live-updates](use-cases/uc.memory.live-updates.md) — **built (web)** (cr-0107, ADR 0033): an open
  gallery reflects others' media adds/removes live; a per-node `media_revision` counter → conditional-GET `304` +
  keyset delta + id-set delete-reconcile (gated by a `delete_revision` counter), short-polled + visibility-gated)
- [uc.memory.delete-media](use-cases/uc.memory.delete-media.md) — **built (web)** (remove a photo into a recoverable
  per-Memory trash: soft-delete → Trash tab → 30-day retention → scheduled hard-purge; **any member** may
  trash/restore, audited `deleted_by`; lightbox-only delete + Delete-key culling; reuses the cr-0107
  `delete_revision` reconcile + the cr-0102 blob-reclaim; **cr-0110 + cr-0111** (member-expedited purge + Trash-tab
  redesign on the shared grid + the upload/delete reconcile fixes))
- [uc.memory.delete-node](use-cases/uc.memory.delete-node.md) — **refined** ([ADR 0061](../../decisions/0061-node-deletion-reversible-node-trash.md)):
  delete a Memory/Chapter and its whole subtree into a recoverable **space** trash — soft-delete → hidden from
  every read (both public-share paths included) → retention → scheduled hard-purge of nodes + media + derivatives
  + buckets + blobs. Replaces today's immediate hard delete, which reclaimed only the cover blobs and orphaned the
  rest (inbox #131). The trash hangs off the **space**, not the node: a trashed Memory has no Memory to hang from
- [uc.memory.buckets](use-cases/uc.memory.buckets.md) — **built (web)** (cr-0113, curation authz relaxed to
  member-gated by cr-0123) ([ADR 0034](../../decisions/0034-media-uploader-buckets.md),
  on [en.memory.buckets](enablers/en.memory.buckets.md)): group a Memory's media by **who contributed it** — the
  attribution axis, orthogonal to Chapters; a real **`owner_key`-keyed bucket table** (lazy virtual primary +
  one-level sub-buckets, `K_mem`-sealed names, reparent-on-delete, member-gated sub-bucket authz).
  **cr-0113** shipped the full data layer + backend + the primary-grouping **"Beiträge"** tab (reusing the
  gallery grid); the **sub-bucket management UI** is the next slice, camera-filter/batch later
- Memory journal — markdown sealed under `K_mem`
- Tracks (GPX) — sealed under `K_mem` (the sealed *location* is now [uc.memory.place](use-cases/uc.memory.place.md))
- Share a Memory — wrap its `K_mem` to a per-share key (least-privilege, ADR 0012) → the
  [Sharing](../sharing/_index.md) feature ([uc.sharing.share-public-viewer](../sharing/use-cases/uc.sharing.share-public-viewer.md),
  **refined**, [ADR 0029](../../decisions/0029-public-viewer-share.md))
