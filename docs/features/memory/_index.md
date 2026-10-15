# Feature: Memory

The product center: a **Memory** (photos, media, a journal, places & tracks — added in later slices),
organized in an **encrypted tree** of **folders** (organization only) and **Memories** (nested into
**Chapters**). Everything content lives inside a [scope](../spaces/_index.md) and is **end-to-end encrypted**
— the server stores only ciphertext, opaque node ids/parents/kinds/ordering/timestamps, and blinded slug
tokens; it never sees a name or a content key.

- **Cross-cutting:** [Security Model](../../crosscutting/security-model.md) (the key hierarchy + sharing),
  [Scopes & Tenancy](../../crosscutting/scopes-and-tenancy.md) (the navigation contract; content lives in a
  scope), [Account & Key Model](../account/enablers/en.account.key-model.md).
- **Decisions:** [ADR 0012](../../decisions/0012-content-key-hierarchy.md) (per-Memory content key `K_node`,
  random + many-wrapped — the unit of sharing; `K_SC` for navigation; `K_blob` per blob),
  [ADR 0017](../../decisions/0017-crypto-primitives-and-post-quantum-strategy.md) (the vector-pinned content crypto).
- **Product language (hard rule):** Memory / Chapter. Folders **organize only**. Never "Album" / "Explorer".

## Key model (ADR 0012, in one line)

`scope key (grant) → K_SC` (per scope: the scope-root entries' names + slugs, dedup) `→ K_node` (per Memory, random:
chapter/journal/media + sensitive metadata + the names and slugs of the nodes directly below it; **the share grain**)
`→ K_blob` (per media blob). A folder carries a random key of its own too, and a Memory inside it wraps its key and
seals its name under the folder's key instead of `K_SC` ([ADR 0080](../../decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)).

## Enablers

- ★ [en.memory.tree](enablers/en.memory.tree.md) — **built (web + backend)** (the encrypted node tree: FOLDER/MEMORY/CHAPTER,
  encrypted names, blinded slugs, per-Memory `K_node` birth/open — the substrate the use-cases below build on;
  [ADR 0012](../../decisions/0012-content-key-hierarchy.md))
- ★ [en.memory.media](enablers/en.memory.media.md) — **built (web + backend)** (the encrypted media data layer: a blob sealed
  as chunked AEAD under a random `K_blob` wrapped under `K_node`, transferred direct-to-store via presigned URLs,
  attached to a node; **no content dedup** — operator-blind trade-off;
  [ADR 0013](../../decisions/0013-blob-storage-presigned-direct-to-store.md),
  [ADR 0012](../../decisions/0012-content-key-hierarchy.md))
- ★ [en.memory.favorites](enablers/en.memory.favorites.md) — **built** (pin a node of any kind - core's since inbox #409 -
  and mark a Memory's items: the per-(user, scope) pinned-id set sealed under the master key as one opaque blob —
  operator-blind, no plaintext favorite flag)
- ★ [en.memory.derivatives](enablers/en.memory.derivatives.md) — **built (web + backend)** (per-item **thumbnail / preview /
  original** tiers — grid = thumbnail, lightbox = preview, download = original; client-generated encrypted
  blobs, **per-blob `K_blob`**, role-tagged on the media record (`original` + `derivatives[{kind, blob}]`), no
  backfill, first-frame video poster. **Built before the gallery** — where
  [ADR 0013](../../decisions/0013-blob-storage-presigned-direct-to-store.md)'s deferred thumbnails/previews land)
- ★ [en.memory.node-meta](enablers/en.memory.node-meta.md) — **built** (the sealed, client-versioned
  `wrappedMeta` blob on a content node — one opaque envelope for a Memory's node-level metadata, sealed under
  `K_node`, threaded through `createNode` / partial `updateNode` / `listNodes`, materialized so a card/map reads
  it without loading media. The envelope + `date` shipped with cr-0067, then `location` (cr-0069), `cover` (cr-0072) and
  `participants` (ADR 0083); the envelope itself now lives in core, `web/src/core/node/nodeMeta.ts`, inbox #411)
- ★ [en.memory.buckets](enablers/en.memory.buckets.md) — **built (web + backend)** (the bucket data layer: a per-uploader
  container within a Memory, a real `owner_key`-keyed `veyq_bucket` table — `user:{id}` / `share:{id}` — with a
  lazy virtual primary + one-level sub-buckets, names sealed under `K_node`, media linked via a nullable `bucket_id`
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
  Zoom and a slideshow came with cr-lightbox-zoom-slideshow (inbox #90, #91); rescaling on device rotation was dropped
  (inbox #89, the owner's call)
- [uc.memory.chapters](use-cases/uc.memory.chapters.md) — **built (web)** (cr-0060; nested Memories inside a Memory — each
  chapter a full Memory with its own recursive `K_node`, wrapped under the parent's;
  [ADR 0012](../../decisions/0012-content-key-hierarchy.md))
- [uc.memory.date](use-cases/uc.memory.date.md) — **done** (cr-0067): a curatable date (auto/single/range/ongoing),
  sealed on the node's `wrappedMeta`; browse card range + date-sort
- [uc.memory.place](use-cases/uc.memory.place.md) — **built (web)** (cr-0069, the map cr-0071; the backend stores only
  the opaque `wrappedMeta`): one named location on the same `wrappedMeta` (medoid position + typed/reverse-geocoded name),
  and the places map — every located Memory in the current folder's subtree, the whole space at its root;
  reverse-only, veyq-blind geocoding (ADR 0016)
- [uc.memory.cover](use-cases/uc.memory.cover.md) — **built (web; backend for the uploaded cover)** (cr-0072,
  cr-0073, cr-0109): a deliberately chosen, cropped cover (a `source` — one of the Memory's photos or an uploaded
  image — plus a crop, on the same `wrappedMeta`), shown on the browse card, the detail header and the map pin
  — one decrypt per Memory per scope, shared by all three (the sidebar's Favorites entry showed it too until core took the
  Favorites section over, inbox #409; a cover for any node is inbox #411)
- [uc.memory.concurrent-curation](use-cases/uc.memory.concurrent-curation.md) — **built**
  ([ADR 0077](../../decisions/0077-a-write-carries-the-version-it-read.md)): two members curate one Memory at once —
  every write of a sealed meta blob (a Memory's date/place/cover, a photo's date/place name/type) states the
  `metaVersion` it read, a stale one answers `409 stale_version` with the current blob, and the writer re-applies
  only its own change; a blob that does not open is never rebuilt from empty
- [uc.memory.live-updates](use-cases/uc.memory.live-updates.md) — **built (web)** (cr-0107, ADR 0033): an open
  gallery reflects others' media adds/removes live; core's per-node item revision (ADR 0033) → conditional-GET `304` +
  keyset delta + id-set delete-reconcile (gated by a `delete_revision` counter), short-polled + visibility-gated)
- [uc.memory.delete-media](use-cases/uc.memory.delete-media.md) — **built (web)** (remove a photo into a recoverable
  per-Memory trash: soft-delete → Trash tab → 30-day retention → scheduled hard-purge; **any member** may
  trash/restore, audited `deleted_by`; lightbox-only delete + Delete-key culling; reuses the cr-0107
  `delete_revision` reconcile + the cr-0102 blob-reclaim; **cr-0110 + cr-0111** (member-expedited purge + Trash-tab
  redesign on the shared grid + the upload/delete reconcile fixes))
- [uc.memory.delete-node](use-cases/uc.memory.delete-node.md) — **built (web + backend)** ([ADR 0061](../../decisions/0061-node-deletion-reversible-node-trash.md)):
  delete a Memory/Chapter and its whole subtree into a recoverable **space** trash — soft-delete → hidden from
  every read (both public-share paths included) → retention → scheduled hard-purge of nodes + media + derivatives
  + buckets + blobs. It replaced the immediate hard delete, which reclaimed only the cover blobs and orphaned the
  rest (inbox #131). The trash hangs off the **space**, not the node: a trashed Memory has no Memory to hang from
- [uc.memory.move-node](use-cases/uc.memory.move-node.md) — **built (web)** (inbox #358,
  [ADR 0080](../../decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md) decision 3): "Move
  to…" for any node — core's dialog, a member through `moveNode`, a `READ_WRITE` folder guest through its link; one
  re-wrap per move keeps the subtree readable; a drop-box guest's folder moved out by a member ends its identity
- [uc.memory.participants](use-cases/uc.memory.participants.md) — **built (web)** (inbox #20,
  [ADR 0083](../../decisions/0083-memory-participants-are-a-sealed-list-in-the-memorys-metadata.md)): who was there — a
  sealed `participants` list in the Memory's `wrappedMeta` (no server-visible relation), shown in the header for members
  only (the interface shows a guest none; the list holds opaque ids only), a member profile at `#people/<userId>` listing the person's Memories, uploader names in the
  lightbox
- [uc.memory.buckets](use-cases/uc.memory.buckets.md) — **built (web)** (cr-0113, curation authz relaxed to
  member-gated by cr-0123) ([ADR 0034](../../decisions/0034-media-uploader-buckets.md),
  on [en.memory.buckets](enablers/en.memory.buckets.md)): group a Memory's media by **who contributed it** — the
  attribution axis, orthogonal to Chapters; a real **`owner_key`-keyed bucket table** (lazy virtual primary +
  one-level sub-buckets, `K_node`-sealed names, reparent-on-delete, member-gated sub-bucket authz).
  **cr-0113** shipped the full data layer + backend + the primary-grouping **"Beiträge"** tab (reusing the
  gallery grid); **cr-0122** the sub-bucket management UI (create / rename / delete, and a Move / Delete selection
  bar); a camera filter is not built
- Memory journal — planned: markdown sealed under `K_node`
- Tracks (GPX) — planned: sealed under `K_node` (the sealed *location* is now [uc.memory.place](use-cases/uc.memory.place.md))
- Share a Memory — wrap its `K_node` to a per-share key (least-privilege, ADR 0012) → the
  [Sharing](../sharing/_index.md) feature ([uc.sharing.share-public-viewer](../sharing/use-cases/uc.sharing.share-public-viewer.md),
  **built**, [ADR 0029](../../decisions/0029-public-viewer-share.md))
