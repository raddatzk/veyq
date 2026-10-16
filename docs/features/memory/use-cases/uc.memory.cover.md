# UC: Memory cover — a chosen, cropped cover shown everywhere

- **ID:** uc.memory.cover
- **Primary actor:** A member with an **unlocked vault** who has a Memory open and wants to give it a **cover**.
- **Goal:** Curate a Memory's **cover** — pick **one image** and **crop** it — **sealed under a field key derived from the Memory's `K_node`**
  and shown identically wherever the Memory appears: its **browse card**, its **detail header**, its **map pin**
  ([uc.memory.place](uc.memory.place.md)) and its entry in the **sidebar's Favorites**. **A cover is core's since inbox
   #411** ([uc.platform.node-cover](../../platform/use-cases/uc.platform.node-cover.md)): the action ("Set cover" /
  "Change cover" — in the page's header and in the Memory's row menu), the **dialog** (choose, crop, re-crop, Remove), the upload,
  the one-request write and the display are the platform's, the same for a folder and a drop box; what stays the Memory's is
  its **second source**, one of its own photos, which it adds to core's dialog through the kind's `cover.extraSource`
  (`memoryPhotoSource` in `memoryCover.ts`) and the gallery's **Set as cover** that feeds it. The sidebar showed the cover until the Favorites section
  became core's and drew each kind's icon (#409); #411 restored it, for every kind that has one.
  **Operator-blind** — the server never sees which image, or the crop.
- **Preconditions:** The vault is unlocked and the Memory is open (its **`K_node`** is held). The node already carries
  the sealed `wrappedMeta` blob ([en.memory.node-meta](../enablers/en.memory.node-meta.md)); this adds a `cover`
  sub-object beside the `date`/`location`.
- **Postconditions (success):** The Memory shows its chosen cover everywhere it appears — browse card, detail
  header, map pin; the user can re-crop, change, or remove it. The server stored only the **opaque `wrappedMeta`** blob + the unchanged node shape.

## What a cover is

A cover is **one image plus a crop**, deliberately chosen — **there is no auto-cover** (a cover never appears from
nothing; unset ⇒ a placeholder shows, like a location is never auto-created). The image source is:

- **an existing Memory media** — referenced by `mediaId` (this use-case's first slice, cr-0072); or
- **a brand-new uploaded image** that is **not** added to the Memory's media — a **standalone cover blob** committed
  to the node with **no `veyq_media` row** (so it never appears in the gallery), sealed under a `K_blob` wrapped by
  `K_node` and served over the existing presigned transport (cr-0073).

The **crop is normalized `{ x, y, width, height }` in 0..1** against the source image (the pre-E2EE design source's
`CoverCrop`), so **one stored crop renders identically at any size** — a rail tile, a small card, a larger detail
header, or a map pin — with no per-surface crop and no cropped-image upload. The whole `cover { source, crop }` is sealed in the
node's `wrappedMeta` (under a field key derived from `K_node`); the display just expands the decrypted thumbnail inside an `overflow:hidden` box
and offsets it.

## Main flow

1. The cover image comes from one of two entry points — **there is no modal media picker** (browsing existing
   photos stays in the gallery, which scales to any count):
   - **A Memory photo — framed in the cropper:** open a photo in the **gallery lightbox** and choose
     **"Set as cover"**. This opens the **crop editor seeded on that photo** (its already-decrypted thumbnail is
     the crop source — the exact tier the cover renders from), with the **lightbox staying open underneath** (its
     keyboard suspended) so the user keeps browsing after saving. The user frames the 16:9 crop and saves; if the
     photo is **already the cover**, its stored crop is pre-loaded to fine-tune. The button marks the current cover.
   - **A brand-new image, or re-cropping the current cover:** the header's **Cover** action (or the cover tile) opens core's
     dialog — **"Choose an image"** (an `<input type=file accept=image/*>`), or the current cover pre-loaded to re-frame.
     **Remove cover** is a button of the same dialog, offered where a cover exists.
2. In the editor a **16:9 crop viewport** (filling the dialog width) shows the source — a **Memory photo picked in
   the lightbox**, an **uploaded image**, or the **current cover re-framed**; the user **drag-pans** and **zooms**
   (1–3×) to frame it. Save normalizes the pan/zoom back to the `0..1` crop rectangle.
3. On save, the client seals `cover { source, crop }` — `source` = the media `mediaId` or the uploaded blob ref —
   into the node's `wrappedMeta` (read-modify-write — the sibling date/location are preserved) and sends it with
   core's write: a new image is `setNodeCover` (the open upload AND the meta that names it, one transaction), a switch away
   from an uploaded cover or its removal is `removeNodeCover` (the blob freed in the same write), anything else a plain
   `updateNode`. The blob step is decided from the cover the write replaced
   ([ADR 0077](../../../decisions/0077-a-write-carries-the-version-it-read.md) point 3), read from the blob the server accepted
   the write over, not from the editor's copy; a cover another writer replaced meanwhile is a `cover_stale` conflict with
   nothing written, so the old cover is never left half-replaced. The detail header re-renders; the browse card, the map pin
   and the sidebar pick it up.
4. **Everywhere the Memory shows**, its cover is rendered from the same sealed crop over the chosen media's
   decrypted thumbnail (or the uploaded cover blob): the **browse card**, the **detail header**, the **map pin** and the
   **sidebar's Favorites** tile. **Remove** clears the cover → the
   placeholder returns on every one of them. The
   thumbnail itself is decrypted **once per Memory per scope** and shared by all three; a cover changed on one
   surface is re-resolved for the rest because the cache is keyed on the node's sealed meta, not on its id.

## Alternate / exception flows

- **E1 — no cover set:** every one of those places shows the **placeholder** (the soft-accent images tile).
  Deliberate — no auto-pick. No surface branches on it: they all render the same component, whose own
  placeholder is what appears.
- **E2 — the chosen media was deleted:** the `cover.mediaId` dangles → best-effort fall back to the placeholder
  (never a broken card, pin or rail tile); the user can pick a new cover.
- **E3 — vault locked / Memory not open:** without the node's `K_node` the `wrappedMeta` can't be unsealed → the
  cover is unavailable until unlock + open.
- **E4 — malformed / wrong-key `wrappedMeta` or crop:** best-effort — a bad crop normalizes to the full frame; an
  unopenable blob shows the placeholder. Never a throw.

## Quality notes

- **Operator-blind.** The server holds the node shape + the **opaque `wrappedMeta`**; never the chosen image id or
  the crop. The cover is sealed in `wrappedMeta` and unsealed only on device. (Envelope + node-level blindness:
  [en.memory.node-meta](../enablers/en.memory.node-meta.md).)
- **One normalized crop, one image, everywhere.** A single `0..1` crop drives the card, the detail header, the
  map pin through one shared display formula — a Memory reads as itself wherever
  it appears, with no re-cropping and no extra blob for the existing-media case. **One decrypt, too:** a single
  store resolves a Memory's cover for every surface, so adding another surface costs a read, not a decrypt — and
  the surface that shows it needs no access to the key chain that opened it.
- **No auto-cover (deliberate).** A cover is a curation choice, mirroring the location: it is set on purpose, and
  its absence is an honest placeholder rather than a guessed photo. (This retires the cr-0071 stopgap where the map
  pin auto-picked the media nearest the place.)
- **Reuse the existing UI/UX — never invent.** The picker + hand-rolled pan/zoom crop + the crop-display formula
  are existing components (core's `CoverDialog` with the shared `ImageCropper`, `MemoryCoverImage`, `CoverCrop`);
  only the data layer is swapped to the contracts client + `K_node` sealing. No crop library is added.

## User impact

A chosen, cropped cover gives each Memory a recognizable face on its card, its page and its map pin, while the server
never sees which picture you picked.

## Related

- Feature: [Memory](../_index.md)
- Enabler: [en.memory.node-meta](../enablers/en.memory.node-meta.md) (the sealed `wrappedMeta` envelope this fills)
- Use-cases: [uc.memory.date](uc.memory.date.md) + [uc.memory.place](uc.memory.place.md) (the sibling curated
  sub-objects on the same `wrappedMeta`), [uc.memory.gallery](uc.memory.gallery.md) (the media tiers the cover is
  chosen from + displayed with), [uc.memory.browse](uc.memory.browse.md) (the card + `updateNode` this folds into)
- Decisions: [ADR 0012](../../../decisions/0012-content-key-hierarchy.md) (K_node seals node content)
- Core's feature: [uc.platform.node-cover](../../platform/use-cases/uc.platform.node-cover.md) (inbox #411 — Memories' two
  cover endpoints became core's `setNodeCover` / `removeNodeCover`; the crop model, the store and the writer moved to
  `core/node/cover/`; the follow-up of 2026-10-12 moved the dialog there too — the Memory keeps its photo source and the lightbox
  flow that feeds it, nothing else)
- Built: the existing-media source (web, cr-0072) and the **new-upload** source (a standalone cover blob —
  contract + backend + web, cr-0073). cr-0073 generalized the cr-0072 **`mediaId`-only** `cover` shape into a
  discriminated **`source` (media | upload blob ref)** — with a backward-compatible `normalizeMemoryCover` so
  existing `{ mediaId, crop }` seals still open — and branched `loadNodeCover` onto a blob-fetch path; the display
  (`MemoryCoverImage` + `coverImageStyle`) and the crop editor are reused unchanged. cr-0109 changed the lightbox
  **"Set as cover"** from a one-click full-frame set into **opening the crop editor seeded on that photo** (the
  lightbox stays open beneath the modal, its keyboard suspended) so the 16:9 crop is framed at pick time — the
  editor gained a third `media` crop source alongside `upload`/`keep`.
- End-user view (docs site): [Choose a cover](../../../../site/src/content/docs/memories/set-a-cover.mdx)
