# UC: Memory cover — a chosen, cropped cover shown everywhere

- **ID:** uc.memory.cover
- **Primary actor:** A member with an **unlocked vault** who has a Memory open and wants to give it a **cover**.
- **Goal:** Curate a Memory's **cover** — upload **one image** and **crop** it — **sealed under a field key derived from the
  Memory's `K_node`** and shown identically wherever the Memory appears: its **browse card**, its **detail header**, its
  **map pin** ([uc.memory.place](uc.memory.place.md)) and its entry in the **sidebar's Favorites**. **A cover is core's since
  inbox #411** ([uc.platform.node-cover](../../platform/use-cases/uc.platform.node-cover.md)): the action ("Set cover" /
  "Change cover" — in the page's header and in the Memory's row menu), the **dialog** (choose, crop, re-crop, Remove), the
  upload, the one-request write and the display are the platform's, the same for a folder and a drop box. A cover is set
  **only by an upload in that dialog** (owner 2026-10-19: set casually from the gallery, members overwrote each other's
  choice unnoticed). What stays the Memory's is one seam, `cover.findStored` (`storedMatch.ts`): an uploaded image that
  equals a photo of the Memory's gallery is not stored again — the cover shares that photo's PREVIEW
  ([ADR 0013](../../../decisions/0013-blob-storage-presigned-direct-to-store.md) § 5). **Operator-blind** for the crop and the
  image; where the cover shares a photo's stored object, the operator can tell which photo it is (accepted, inbox #505).
- **Preconditions:** The vault is unlocked and the Memory is open (its **`K_node`** is held). The node already carries
  the sealed `wrappedMeta` blob ([en.memory.node-meta](../enablers/en.memory.node-meta.md)); this adds a `cover`
  sub-object beside the `date`/`location`.
- **Postconditions (success):** The Memory shows its chosen cover everywhere it appears — browse card, detail
  header, map pin; the user can re-crop, change, or remove it. The server stored only the **opaque `wrappedMeta`** blob +
  the node's `COVER` attachment.

## What a cover is

A cover is **one uploaded image plus a crop**, deliberately chosen — **there is no auto-cover** (a cover never appears
from nothing; unset ⇒ a placeholder shows, like a location is never auto-created). The image is the node's own **`COVER`
attachment**, with **no `veyq_media` row** (it never appears in the gallery): a JPEG derived on the device at the PREVIEW
tier's spec (long edge 2048, quality 0.85), sealed under a `K_blob` wrapped by `K_node`. Where the picked file is the
original of a photo the Memory's gallery already holds (same sealed size, same SHA-256 in its `blob-meta`), the attachment
is a **second blob row on that photo's PREVIEW object** — the same picture at the same spec, nothing uploaded; the stored
object goes only with the last row naming it, so deleting the photo keeps the cover, and the reverse.

The **crop is normalized `{ x, y, width, height }` in 0..1** against the source image, so **one stored crop renders
identically at any size** — a rail tile, a small card, a larger detail header, or a map pin — with no per-surface crop and
no cropped-image upload. The whole `cover { source: { kind: 'upload', ref }, crop }` is sealed in the node's `wrappedMeta`
(under a field key derived from `K_node`); the display expands the decrypted image inside an `overflow:hidden` box and
offsets it.

## Main flow

1. The header's **Cover** action (or the cover tile) opens core's dialog — **"Choose an image"** (an
   `<input type=file accept=image/*>`), or the current cover pre-loaded to re-frame. **Remove cover** is a button of the
   same dialog, offered where a cover exists. The gallery offers no way in.
2. In the editor a **16:9 crop viewport** (filling the dialog width) shows the source — an **uploaded image** or the
   **current cover re-framed**; the user **drag-pans** and **zooms** (1–3×) to frame it. Save normalizes the pan/zoom back
   to the `0..1` crop rectangle.
3. On save of a new image, the client asks the kind where an equal picture is stored (`findStoredCover`: the Memory's
   media list, the size first, the digest only for a photo of the same size). A match is a **shared row** (`SharedBlob`:
   the PREVIEW's `K_blob` wrapped again bound to the node and `COVER`); none uploads the derived JPEG. Either way the client
   seals `cover { source, crop }` into the node's `wrappedMeta` (read-modify-write — the sibling date/location are
   preserved) and sends it with core's write: `setNodeCover` (the open upload, or the shared row, AND the meta that names
   it, one transaction), its removal `removeNodeCover` (the blob freed in the same write), a re-crop a plain `updateNode`.
   The blob step is decided from the cover the write replaced
   ([ADR 0077](../../../decisions/0077-a-write-carries-the-version-it-read.md) point 3); a cover another writer replaced
   meanwhile is a `cover_stale` conflict with nothing written. The detail header re-renders; the browse card, the map pin
   and the sidebar pick it up.
4. **Everywhere the Memory shows**, its cover is rendered from the same sealed crop over the decrypted cover blob: the
   **browse card**, the **detail header**, the **map pin** and the **sidebar's Favorites** tile. **Remove** clears the cover
   → the placeholder returns on every one of them. The image is decrypted **once per Memory per scope** and shared by all;
   a cover changed on one surface is re-resolved for the rest because the cache is keyed on the node's sealed meta.

## Alternate / exception flows

- **E1 — no cover set:** every one of those places shows the **placeholder** (the soft-accent images tile).
  Deliberate — no auto-pick. No surface branches on it: they all render the same component, whose own
  placeholder is what appears.
- **E2 — a stored photo reference:** a cover an older client sealed as one of the Memory's photos
  (`{ kind: 'media', mediaId }`) is read as **no cover** — the placeholder — and kept untouched by every other meta writer
  until a new cover replaces it. Not migrated (no live data).
- **E3 — vault locked / Memory not open:** without the node's `K_node` the `wrappedMeta` can't be unsealed → the
  cover is unavailable until unlock + open.
- **E4 — malformed / wrong-key `wrappedMeta` or crop:** best-effort — a bad crop normalizes to the full frame; an
  unopenable blob shows the placeholder. Never a throw.
- **E5 — the gallery holds the picture but no digest for it:** a photo uploaded before its original carried a SHA-256 is
  never matched; the cover is uploaded as its own blob.

## Quality notes

- **Operator-blind.** The server holds the node shape, the **opaque `wrappedMeta`** and the `COVER` attachment; never the
  crop or a pixel. The match is the client's (a digest sealed under the photo's own `K_blob`). **Residual, accepted**
  (inbox #505): a shared cover row names the photo's PREVIEW blob, so the operator can tell which photo is the cover.
- **One normalized crop, one image, everywhere.** A single `0..1` crop drives the card, the detail header, the
  map pin through one shared display formula. **One decrypt, too:** a single store resolves a Memory's cover for every
  surface, and the surface that shows it needs no access to the key chain that opened it.
- **No auto-cover (deliberate).** A cover is a curation choice, mirroring the location: it is set on purpose, and
  its absence is an honest placeholder rather than a guessed photo.
- **Reuse the existing UI/UX — never invent.** The picker + hand-rolled pan/zoom crop + the crop-display formula
  are existing components (core's `CoverDialog` with the shared `ImageCropper`, `MemoryCoverImage`, `CoverCrop`).

## User impact

A chosen, cropped cover gives each Memory a recognizable face on its card, its page and its map pin, while the server
never sees the picture or its crop; a picture already in the gallery is not stored twice.

## Related

- Feature: [Memory](../_index.md)
- Enabler: [en.memory.node-meta](../enablers/en.memory.node-meta.md) (the sealed `wrappedMeta` envelope this fills)
- Sibling: [uc.memory.banner](uc.memory.banner.md) — the picture or video above the Memory, independent of the cover: its
  own attachment, shown whole rather than cropped, set the same way (an upload in its dialog, an equal gallery file shared)
- Use-cases: [uc.memory.date](uc.memory.date.md) + [uc.memory.place](uc.memory.place.md) (the sibling curated
  sub-objects on the same `wrappedMeta`), [uc.memory.upload](uc.memory.upload.md) (the original's digest a match reads),
  [uc.memory.browse](uc.memory.browse.md) (the card + `updateNode` this folds into)
- Decisions: [ADR 0012](../../../decisions/0012-content-key-hierarchy.md) (K_node seals node content),
  [ADR 0013](../../../decisions/0013-blob-storage-presigned-direct-to-store.md) § 5 (a stored object shared inside a node)
- Core's feature: [uc.platform.node-cover](../../platform/use-cases/uc.platform.node-cover.md) (inbox #411 — the cover
  endpoints, the crop model, the store, the writer and the dialog are core's; the Memory keeps `cover.findStored`)
- End-user view (docs site): [Choose a cover](../../../../site/src/content/docs/memories/set-a-cover.mdx)
