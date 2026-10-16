# UC: A cover for any node — an image of the owner's choosing, core's feature

- **ID:** uc.platform.node-cover
- **Primary actor:** A member who may write the space (a member with write access on the scope, keys unlocked).
- **Goal:** Give a **folder**, a **drop box** or a **Memory** a **cover** — an image the member uploads directly — shown as the
  node's leading visual in the level list and tiles, in the node's header and in the sidebar's Favorites, with the kind's icon as
  the default. Core's feature (inbox #411): the action, **the dialog (choose, crop, remove)**, the derive, the upload, the write and
  the display belong to the platform; a kind only **opts in** (`NodeKindRules.takesCover` on the server, `cover` on the web descriptor).
- **Preconditions:** The scope is unlocked (the epoch keys are loaded), not pending deletion, the member may write it, and the
  node's kind opted in (FOLDER — core's own, `DEPOT_DROPBOX`, `MEMORY`, `CHAPTER`; **not** `FILE`: a file's own thumbnail is a
  different idea, it needs the client to understand many formats). The node is not a guest's identity folder.
- **Postconditions (success):** The image is the node's own blob in core's `COVER` attachment slot — not a child, not a file in
  the folder — and the node's sealed `wrappedMeta` names it; both landed in ONE write. The node goes to the trash with its
  cover, counts against the quota like any attachment and is freed with the node's purge.

## Main flow

1. In the row's **⋯** menu (or the node's header) the member chooses **Cover** — labelled **Set cover** where the node has none,
   **Change cover** where it has one. It is **ONE action** (one id `cover`, the image icon, in the header and in the row menu
   beside the favorite heart) gated by `coverRights` (fail closed); there is **no separate Remove entry** — removal lives
   in the dialog.
2. Core's **cover dialog** (`core/node/cover/components/CoverDialog.vue`) opens — the same one for a folder, a drop box and a
   Memory. It offers **Choose an image** (a hidden `image/*` input: an image, not empty, up to **40 MB**, otherwise a clear
   message inside the dialog and nothing is read), the shared **16:9 crop** (drag to pan, zoom 1–3×), **Remove cover** (only where
   the node has one — a cover that could not be opened can still be removed), **Cancel** and **Save cover**. Opened on a node
   that has a cover, it shows that image with its **stored crop restored**, so the member can re-frame it without uploading
   again.
3. **Save** emits what was decided and core writes it (`useNodeCover.save`): a **new image** is derived with the existing
   pipeline — one JPEG, long edge 2048, quality 0.85, exactly what Memories' covers always used — and sealed as the node's own
   blob: a fresh `K_blob` wrapped under the **node's content key**, bound to blob, node and role `COVER`
   ([ADR 0072](../../../decisions/0072-a-seal-binds-its-object.md)), the blob meta `{type: image/jpeg}` under `K_blob`. The upload
   runs as a job of the core upload queue (progress and failure show in the tray; a quota refusal says so).
4. One request hands the open upload **and** the node's re-sealed `wrappedMeta` (the `cover` field `{ source, crop }`, revision-
   checked, [ADR 0077](../../../decisions/0077-a-write-carries-the-version-it-read.md)): `setNodeCover` attaches the upload,
   freeing in the same transaction the blob it replaces (`expectedBlobId`) and compare-and-setting the meta. Either both land or
   neither does. A **re-crop** of the current cover is a plain meta write (the stored image, a moved crop); a **photo** the
   application supplied (A1) is a meta write naming it.
5. The member's tree is patched with the new meta; every surface that draws the node (the level's row or tile, the header, the
   sidebar's Favorites) re-resolves: the cover store is keyed on the sealed meta, so the new image appears and the old object URL
   is revoked at once. **The crop is applied everywhere** by one display formula (`coverImageStyle`, drawn by `NodeCoverImage`; the
   Memory's own tile reads the same formula), so a cover framed in the dialog looks the same in a row, a tile, the header and the
   sidebar; a cover without a stored crop shows a centred 16:9 fit.
6. **Remove cover** (in the dialog) is `removeNodeCover`: the blob is freed and the meta re-sealed without the cover, again one write.

## Alternate / exception flows

- **A1 — a Memory adds one source to the same dialog:** the Memory's page opens core's dialog from its header action, its cover
  tile and the gallery lightbox's **Set as cover**; the lightbox hands the picked photo (its decrypted thumbnail) to the dialog,
  which frames it at once. The seam is the kind's `cover.extraSource` (`hintKey`: the line pointing at the gallery, in the
  application's own i18n namespace, which core resolves without reading; `seal(id, crop)`: the stored value; `idOf(cover)`: which
  photo a stored cover already is, so only that photo's stored crop is restored). A cover chosen from the Memory's own photos is a
  second *source* in the same sealed field that core carries untouched and the Memory's `cover.load` reads. Core's dialog, crop and
  write are the only ones — the Memory has no dialog of its own.
- **E1 — a peer changed the cover meanwhile:** `409 cover_stale` — nothing was attached, the old cover is intact, the upload was
  spent and freed; the member is told and may try again. A meta that moved is `409 stale_revision`: nothing written, the upload
  still open, the client re-applies its change on the current meta and sends the same upload again.
- **E2 — the upload or the write fails, or is cancelled:** the **old cover stays, exactly as it was** (the blob step and the
  meta are one transaction); an upload no write ever attached is an unfinished upload like any other, which the server's
  stale-session purge reclaims. Nothing is half-done.
- **E3 — an image the browser cannot decode:** refused (`cover_unreadable`), never sealed as a cover nothing could draw.
- **E4 — a cover that fails to load or has no reader:** the node shows its kind's **icon**, silently.
- **E5 — a share guest:** sees the covers of the nodes in its shared subtree (the node's `wrappedMeta` it already gets, the
  attachment through the share's gated blob route) and gets **no action**: no guest role sets a cover in this step, the server has
  no guest operation for it, and the right says so first. **Share view = member view**, with the function removed.
- **E6 — a pending deletion, locked keys, a guest's identity folder, a kind that did not opt in:** no action is offered; the
  server refuses the rest (`scope_deleting`, `illegal_target`).

## Quality notes

- **Operator-blind.** The server holds ciphertext chunks, the opaque attachment row (node, slot, blob id), sizes and the sealed
  meta — never the image, the crop or which source a cover has. The cover meta is the same `node-meta` seal a Memory's date and
  place use; the blob wrap and blob meta are the seals Memories' covers always used, so **every Memory cover already stored
  stays readable byte for byte** (`coverModel.test.ts` pins the exact shape and key order).
- **No half state.** The slot and the meta that names it are written in one transaction (`NodeCoverStore`): a refused meta write
  rolls the attach back with it, a refused slot compare writes nothing. There is no window in which the meta names a blob that
  is gone, and no second call to forget.
- **Removal is real, with honest limits.** A replaced or removed cover's blob row goes in the same transaction as the swap and a
  `veyq_store_reclaim` record per storage key; the sweep, the only deleter ([ADR 0062](../../../decisions/0062-store-object-reclaim-is-a-durable-work-list.md)),
  deletes the objects up to one not-before-plus-interval later, and nothing readable references them in between. The client
  revokes the old object URL at once; covers never touch Cache Storage, IndexedDB or the service worker. **What the owner
  cannot erase:** copies already loaded into other viewers' browsers, and presigned URLs until they expire.
- **Core names no application kind.** The opt-in is a seam on each kind's own rules; the tests assert the matrix across core and
  every application.

## User impact

You can give a folder, a drop box or a Memory a picture of your own so you recognise it at a glance in lists, on its
page and in your favorites.

## Related

- Feature: [Platform](../_index.md)
- End-user view (docs site): [Choose a cover](../../../../site/src/content/docs/memories/set-a-cover.mdx)
- End-user view (docs site): [Give a folder a cover](../../../../site/src/content/docs/spaces/give-a-folder-a-cover.mdx)
- Use-cases: [uc.memory.cover](../../memory/use-cases/uc.memory.cover.md) (a Memory's own second source and its gallery flow),
  [uc.depot.drop-box](../../depot/use-cases/uc.depot.drop-box.md) (a drop box carries one),
  [uc.memory.delete-node](../../memory/use-cases/uc.memory.delete-node.md) (the trash keeps a cover for a restore, the purge frees it)
- Enabler: [en.memory.node-meta](../../memory/enablers/en.memory.node-meta.md) (the sealed `wrappedMeta` envelope the cover field lives in)
- Decisions: [ADR 0079](../../../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md) (core's attachments),
  [ADR 0072](../../../decisions/0072-a-seal-binds-its-object.md), [ADR 0077](../../../decisions/0077-a-write-carries-the-version-it-read.md),
  [ADR 0062](../../../decisions/0062-store-object-reclaim-is-a-durable-work-list.md)
