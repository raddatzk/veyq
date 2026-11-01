# UC: Memory banner — an image or a video above the Memory, shown whole on every screen

- **ID:** uc.memory.banner
- **Status:** **built** ([ADR 0102](../../../decisions/0102-a-memorys-banner-is-its-own-attachment-framed-by-a-focal-point.md),
  accepted). A video banner plays its original until the small playback copy exists
  ([en.memory.playback-derivative](../enablers/en.memory.playback-derivative.md), planned with the client worker queue,
  inbox #23). It is set only by an upload in its dialog, an upload equal to a gallery file shares its stored object, and
  it has no description. Since 2026-11-01 (owner) it is always shown whole in a frame of the screen's, with nothing to
  frame in the dialog: no focal point, no fit / fill, no re-frame. Its browser pass is lane L1's
  `tests/e2e/tests/features/memories/memory-banner.spec.ts`.
- **Primary actor:** A member with an **unlocked vault** who may write the Memory's scope (the cover's right,
  `memoryRights.setBanner` = `setCover`) and has a Memory or a Chapter open. **Secondary:** anyone who opens it — a member,
  or a guest of a `READ` or `READ_WRITE` link, who sees the banner exactly as a member does.
- **Goal:** Put **one image or one video** above the Memory's menu bar — a **banner**, independent of the cover —
  uploaded in its own dialog, shown whole on a wide screen and on a phone, with the server never learning a pixel of
  it.
- **Preconditions:** The Memory's `K_node` is held and its `wrappedMeta` opens
  ([en.memory.node-meta](../enablers/en.memory.node-meta.md)); the scope is not pending deletion.
- **Postconditions (success):** The Memory's sealed `wrappedMeta.banner` names the banner's attachments and the file's
  SHA-256 (with `frame: 'fit'` and a centred `focal`, kept for the shape, never drawn), one revision later; the node
  holds the banner's bytes in core's attachment slots `BANNER` (and `BANNER_POSTER` for a video), written in the same
  transaction as the meta. Every viewer sees the banner above the
  menu bar. The gallery is unchanged.

## What a banner is

A banner is **one medium in the node's own attachments**, shown whole:

- **The medium** — an image, or a video **shown as a poster with a play button** that plays only when clicked.
- **The source** — **a file uploaded only for the banner** in its dialog (set from the gallery in
  passing, it overwrote another member's choice unnoticed). It never appears in the gallery, the item count, the live list
  or the trash. Where the file equals a gallery medium's original, the banner **shares that medium's stored object**
  instead of storing it again: an image banner the medium's PREVIEW (the same spec of the same picture), a video banner
  its ORIGINAL with its PREVIEW as the poster. The other way round, a gallery upload of the banner's video shares the
  banner's object ([ADR 0013](../../../decisions/0013-blob-storage-presigned-direct-to-store.md) § 5). The object goes
  only with the last row naming it.
- **Always whole, never cropped** — the medium is contained in the frame; where it does not fill it — left and right
  for a medium narrower than the frame, above and below for a wider one — the same decrypted picture (a video: its
  poster - and while it plays, its current frame), enlarged and blurred, fills the rest. Nothing is made or stored for the
  blur. A stored banner's `frame` and
  focal point (the first build's fill around a point, or fit) are read and kept, never drawn: every banner shows whole.
- **No description** — the banner is decorative; the Memory's title follows it.

The **frame on screen** is fixed by the screen, not by the medium: **16:9** where the Memory's column is wide, **1:1** on a
narrow one (720 px and below — a phone), never taller than **60 %** of the viewport (owner 2026-11-01; first 3:1 / 16:9
and 35 %). The banner sits above the Memory's header row (title, cover tile, date, place, actions) and **scrolls away**;
the Memory's **tab row** (Gallery, Chapters, Contributions, Trash) **sticks** below the app's top bar (the compact header
on a phone, the top of the page on a share).

## Main flow

1. The member chooses **Add banner** (or **Change banner** where the Memory has one) among the header row's actions —
   the only way in; the gallery offers none.
2. The **banner dialog** opens on the current banner's previews, or nothing yet:
   **Choose an image or a video** picks a file (`image/*`, `video/*`). An image above 40 MB (the cover's bound) or a file
   the browser cannot read is refused inside the dialog with a message, and nothing is read further. A video's first
   frame is decoded on the device as its poster; a video has no length limit.
3. The dialog shows **two previews** of the picked file — the **wide screen** (16:9) and the **phone** (1:1) — each the
   whole medium with its blurred surround, as every viewer will see it. There is nothing to set: no point, no fit / fill.
4. **Save banner** (offered only once a new file is picked — a stored banner is never re-framed):
   - **The upload**: the file's SHA-256 is read once on the device (frame by frame). Where a gallery medium's original
     has the same sealed size and digest, each slot whose bytes the medium already stores is a **shared row** — the
     stored blob's `K_blob` wrapped again bound to the node and the slot, nothing uploaded. Otherwise the file is prepared
     on the device: an image is derived to one JPEG (long edge 2048, quality 0.85); a video is taken as it is (its
     `blob-meta` carrying the digest), with its first frame as the poster. Each upload is sealed under a fresh `K_blob`
     wrapped under the Memory's `K_node` and sent direct-to-store; the dialog shows the progress and **Cancel** stops it.
   - One request, `setMemoryBanner`, hands the upload(s) or shared row(s) and the re-sealed `wrappedMeta`
     (revision-checked, the banner's `sha256` the file's digest): the server compares both slots, attaches `BANNER` (and
     `BANNER_POSTER`, or frees a poster an image replaces), freeing the blobs replaced, and writes the meta — all or
     nothing. A shared row whose source the node does not hold, or a blocked one, is `shared_source_invalid`.
5. The Memory re-renders: the banner's picture is keyed on the refs the sealed meta names, so the new banner appears and
   the old picture's object URL is revoked at once (a date or place edit reads nothing again). Everyone else sees it on
   their next tree refresh.
6. **Remove banner** (in the dialog, where a banner exists) is `removeMemoryBanner`: both slots freed and the meta re-sealed
   without `banner`, in one write.
7. **Viewing:** the banner draws from the node's own attachments, before and without the gallery list (its frame keeps its
   space, so nothing below it jumps). An image shows at once. A video shows its poster and a **play button**; a click plays
   it with controls, streamed through the media service worker (decrypted whole first where there is none); nothing ever
   starts on its own.

## Alternate / exception flows

- **A1 — a gallery upload of the banner's video:** the new medium's ORIGINAL is a row on the `BANNER` object (matched by
  the banner's sealed `sha256` and its size); its posters are derived and uploaded as always. An image banner is never
  matched: it holds a derived JPEG, not the file.
- **A2 — deleting the medium that is the banner's picture** (its original's digest is the banner's `sha256`): the delete
  asks first, says that the banner stays and offers **Remove the banner too**; declined, nothing is deleted; accepted, the
  medium goes to the trash as any other, and a shared object stays while the banner names it. Nothing else ties the two.
  A banner written before the digest (an older `origin`) asks nothing.
- **E1 — a peer changed the banner meanwhile:** `409 banner_stale` — nothing written, the old banner intact, the uploads
  still open (the stale-session purge reclaims them); the dialog says so and the member may try again. A meta that moved
  is `409 stale_revision`: nothing written, the uploads still open; the client re-applies its change on the current meta
  and sends the same uploads again — or, where the peer changed the banner itself, the dialog says so.
- **E2 — the upload or the write fails, or is cancelled:** the old banner stays exactly as it was; an upload no write
  attached is reclaimed by the stale-session purge.
- **E3 — the browser cannot decode a frame of a picked video:** the video is refused on that device with a message; no
  video banner where nothing could be drawn before it plays. A picked video equal to a gallery medium with a poster takes
  that poster and decodes nothing.
- **E4 — a file the browser cannot read:** refused (`banner_unreadable`), never sealed as a banner nothing could draw.
- **E5 — the banner fails to load, or the meta does not open:** no banner is drawn and the header row moves up; never a
  broken frame, never a throw. A banner whose shape this client does not know is ignored and preserved.
- **E6 — a guest:** sees the banner (the node's `wrappedMeta` it already gets, the attachments through the share's gated
  blob route) and is offered no action; the server has no guest operation for it, and a guest's upload start refuses both
  banner roles. **Share view = member view**, with the function removed.
- **E7 — locked keys, a pending deletion, a member without write:** no action is offered; the server refuses the rest
  as it refuses a cover (`scope_deleting`; a caller without write is answered as for a node it cannot see).
- **E8 — the Memory is trashed, restored or purged:** the banner goes and comes back with it, and its blobs are freed with
  the purge. Erasing the account that owns a personal scope, or deleting the tenant, frees them with the scope.

## Quality notes

- **Operator-blind, with one residual.** The server holds the attachment rows (node, slot, blob id, size, the member's
  `user:{id}`) and the opaque `wrappedMeta` — never the focal point, fit or fill, or a pixel, and no fetch on opening the
  Memory names a medium. It can tell that a banner exists and whether it is a video (the poster slot, the size). **It can
  tell which gallery medium the banner shows** where they share a stored object (the shared row names its source), and
  even unshared by size (a video banner is the file as it is; an image banner is derived at the preview's spec) — accepted
  by the owner for image and video (inbox #505). Details: [ADR 0102](../../../decisions/0102-a-memorys-banner-is-its-own-attachment-framed-by-a-focal-point.md)
  § What the server sees.
- **No half state.** Both slots and the meta that names them are one transaction; a stale slot or a refused meta write
  writes nothing.
- **No autoplay, no motion of its own.** Nothing moves until the viewer clicks. A playing video's blur follows it from a
  canvas 32 px across (one tiny draw per frame, no second decode or read); paused it holds still, and under
  `prefers-reduced-motion` it stays the poster. Data-saver settings need nothing from the banner.
- **Whole, every screen.** The medium is contained in the 16:9 and in the 1:1 frame alike, so nothing of it is ever
  cut away and nothing per screen is stored; the dialog's two previews are the same component the page draws.
- **Removal is real, with the cover's honest limits:** a replaced or removed banner's blobs go in the same transaction with a
  reclaim record per storage key; copies already loaded in other viewers' browsers and unexpired presigned URLs are beyond
  the owner's reach.
- **Reported with its Memory.** A banner is reported by reporting the Memory; a content block on a gallery medium
  reaches a banner row on the same stored object, and keeps refusing it after the medium is deleted, until the banner is
  removed or the block lifted (ADR 0093 § 6).
- **An equal file is stored once.** A video that is both the banner and a gallery medium costs its size once; a banner
  file the gallery does not hold costs its own size.

## User impact

A Memory can open with a picture or a film of the moment — always whole, on a wide screen and on a phone, nothing to
frame — without the server learning what it shows.

## Related

- Feature: [Memory](../_index.md)
- End-user view (docs site): [Add a banner](../../../../site/src/content/docs/memories/add-a-banner.mdx)
- Decision: [ADR 0102](../../../decisions/0102-a-memorys-banner-is-its-own-attachment-framed-by-a-focal-point.md)
- Enablers: [en.memory.playback-derivative](../enablers/en.memory.playback-derivative.md) (the small video, planned),
  [en.memory.node-meta](../enablers/en.memory.node-meta.md) (the sealed `wrappedMeta` the `banner` field lives in)
- Use-cases: [uc.memory.cover](uc.memory.cover.md) (the sibling it is independent of, and whose pattern — one write for the
  attachment and the meta — it reuses), [uc.platform.node-cover](../../platform/use-cases/uc.platform.node-cover.md),
  [uc.memory.gallery](uc.memory.gallery.md) (the video playback it shares), [uc.memory.upload](uc.memory.upload.md)
  (the original's digest a match reads),
  [uc.memory.concurrent-curation](uc.memory.concurrent-curation.md) (the revision-checked meta write)
- Decisions it rests on: [ADR 0047](../../../decisions/0047-inline-video-playback-range-streaming.md) (playback),
  [ADR 0072](../../../decisions/0072-a-seal-binds-its-object.md), [ADR 0077](../../../decisions/0077-a-write-carries-the-version-it-read.md),
  [ADR 0079](../../../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md) (core's attachments),
  [ADR 0093](../../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) §§ 8–9
