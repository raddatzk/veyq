# UC: Memory banner — an image or a video above the Memory, framed once for every screen

- **ID:** uc.memory.banner
- **Status:** **built** ([ADR 0102](../../../decisions/0102-a-memorys-banner-is-its-own-attachment-framed-by-a-focal-point.md),
  accepted). A video banner plays its copied original until the small playback copy exists
  ([en.memory.playback-derivative](../enablers/en.memory.playback-derivative.md), planned with the client worker queue,
  inbox #23). Its browser pass is written as lane L1's `e2e/tests/features/memory-banner.spec.ts` and not yet run.
- **Primary actor:** A member with an **unlocked vault** who may write the Memory's scope (the cover's right,
  `memoryRights.setBanner` = `setCover`) and has a Memory or a Chapter open. **Secondary:** anyone who opens it — a member,
  or a guest of a `READ` or `READ_WRITE` link, who sees the banner exactly as a member does.
- **Goal:** Put **one image or one video** above the Memory's menu bar — a **banner**, independent of the cover — chosen
  from the Memory's own media or uploaded only for this, framed once so it looks right on a wide screen and on a phone,
  with the server never learning its frame, its description or a pixel of it.
- **Preconditions:** The Memory's `K_node` is held and its `wrappedMeta` opens
  ([en.memory.node-meta](../enablers/en.memory.node-meta.md)); the scope is not pending deletion.
- **Postconditions (success):** The Memory's sealed `wrappedMeta.banner` names the banner's attachments with its frame,
  focal point and description, one revision later; the node holds the banner's bytes in core's attachment slots `BANNER`
  (and `BANNER_POSTER` for a video), written in the same transaction as the meta. Every viewer sees the banner above the
  menu bar. The gallery is unchanged.

## What a banner is

A banner is **one medium in its own copy** plus how to frame it:

- **The medium** — an image, or a video **shown as a poster with a play button** that plays only when clicked.
- **The source** — **one of the Memory's media** (an image or a video of its gallery: an image is derived from its
  original, a video is copied as it is with the poster its upload made; the gallery keeps the medium untouched) **or a
  file uploaded only for the banner**, which never appears in the gallery, the item count, the live list or the trash.
- **The frame** — **fill**: the medium covers the frame, kept around a **focal point** the editor sets; or **fit**: the whole
  medium centred, the sides filled by a blurred, enlarged copy of it. A landscape or square medium fills; a portrait one
  (taller than wide) fits by default and may be switched to fill.
- **A description** (optional, at most 200 characters) — read by screen readers in place of the picture.

The **frame on screen** is fixed by the screen, not by the medium: **3:1** where the Memory's column is wide, **16:9** on a
narrow one (720 px and below — a phone), never taller than **35 %** of the viewport. The banner sits above the Memory's
header row (title, cover tile, date, place, actions) and **scrolls away**; the Memory's **tab row** (Gallery, Chapters,
Contributions, Trash) **sticks** below the app's top bar (the compact header on a phone, the top of the page on a share).

## Main flow

1. The member chooses **Add banner** (or **Change banner** where the Memory has one) among the header row's actions, or
   **Use as banner** in the gallery's large view of an image or a video.
2. The **banner dialog** opens on the current banner to re-frame, the medium the lightbox handed over, or nothing yet:
   **Choose an image or a video** picks a file (`image/*`, `video/*`). An image above 40 MB (the cover's bound) or a file
   the browser cannot read is refused inside the dialog with a message, and nothing is read further. A video's first
   frame is decoded on the device to frame it by; a video has no length limit.
3. The dialog shows the medium with the **point that stays in view** as a handle (a click or a drag moves it, so do the
   arrow keys), and below it **two previews** — the wide screen and the phone — that follow the point live. A portrait
   medium starts in **Fit** with its blurred sides; a **Fit / Fill** switch is offered for it. A **Description** field
   sits below.
4. **Save banner**:
   - **A new medium** is prepared on the device: an image is derived to one JPEG (long edge 2048, quality 0.85); a video
     is taken as it is, with its first frame as the poster. A gallery image is derived **from its original**, decrypted on
     the device; a gallery video's original is read by frame and re-sealed as it is, and its poster is the one its upload
     made. Each result is sealed under a fresh `K_blob` wrapped under the Memory's `K_node` and uploaded direct-to-store;
     the dialog shows the progress and **Cancel** stops it.
   - One request, `setMemoryBanner`, hands the upload(s) and the re-sealed `wrappedMeta` (revision-checked): the server
     compares both slots, attaches `BANNER` (and `BANNER_POSTER`, or frees a poster an image replaces), freeing the blobs
     replaced, and writes the meta — all or nothing.
   - **Only the framing changed** (focal point, fill/fit, description): a plain meta write, no upload.
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

- **A1 — the lightbox:** *Use as banner* on a gallery image or video opens the dialog on that medium (the lightbox stays
  open beneath it, its keys suspended, as for *Set as cover*). The button reads *Current banner* on the medium the banner
  was made from (`origin.mediaId`).
- **A2 — deleting the medium the banner was made from:** the delete asks first, says that the banner keeps its own copy and
  offers **Remove the banner too**; declined, nothing is deleted; accepted, the medium goes to the trash as any other.
  Nothing else ties the two: a banner whose origin is gone simply stays.
- **E1 — a peer changed the banner meanwhile:** `409 banner_stale` — nothing written, the old banner intact, the uploads
  still open (the stale-session purge reclaims them); the dialog says so and the member may try again. A meta that moved
  is `409 stale_revision`: nothing written, the uploads still open; the client re-applies its change on the current meta
  and sends the same uploads again — or, where the peer changed the banner itself, the dialog says so.
- **E2 — the upload or the write fails, or is cancelled:** the old banner stays exactly as it was; an upload no write
  attached is reclaimed by the stale-session purge.
- **E3 — the browser cannot decode a frame of a picked video:** the video is refused on that device with a message; no
  video banner where nothing could be drawn before it plays. A gallery video whose upload made no poster is refused the
  same way.
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
  `user:{id}`) and the opaque `wrappedMeta` — never the focal point, fit or fill, the description, or a pixel, and no fetch
  on opening the Memory names a medium. It can tell that a banner exists and whether it is a video (the poster slot, the
  size). **A copy can name its medium by its size:** a video banner from the gallery is that original re-sealed as it is,
  and an image banner made in the browser that uploaded the medium may have its preview's size. Choosing a gallery medium
  also costs one download of its original, observable as timing. Details: [ADR 0102](../../../decisions/0102-a-memorys-banner-is-its-own-attachment-framed-by-a-focal-point.md)
  § What the server sees.
- **No half state.** Both slots and the meta that names them are one transaction; a stale slot or a refused meta write
  writes nothing.
- **No autoplay, no motion of its own.** Nothing moves until the viewer clicks; the blur is a static picture, so
  reduced-motion and data-saver settings need nothing from the banner.
- **One point, every screen.** One stored focal point frames the medium in 3:1 and in 16:9 through one display formula;
  no per-screen framing is stored.
- **Removal is real, with the cover's honest limits:** a replaced or removed banner's blobs go in the same transaction with a
  reclaim record per storage key; copies already loaded in other viewers' browsers and unexpired presigned URLs are beyond
  the owner's reach.
- **Reported with its Memory.** A banner is reported by reporting the Memory; a report of the gallery medium it was made
  from does not reach the banner.
- **A long video costs its size twice** (the gallery's original and the banner's copy) until the small playback copy
  replaces the banner's bytes.

## User impact

A Memory can open with a wide picture or a film of the moment — framed once, right on every screen — without the server
learning how you framed it or what it shows.

## Related

- Feature: [Memory](../_index.md)
- End-user view (docs site): [Add a banner](../../../../site/src/content/docs/memories/add-a-banner.mdx)
- Decision: [ADR 0102](../../../decisions/0102-a-memorys-banner-is-its-own-attachment-framed-by-a-focal-point.md)
- Enablers: [en.memory.playback-derivative](../enablers/en.memory.playback-derivative.md) (the small video, planned),
  [en.memory.node-meta](../enablers/en.memory.node-meta.md) (the sealed `wrappedMeta` the `banner` field lives in)
- Use-cases: [uc.memory.cover](uc.memory.cover.md) (the sibling it is independent of, and whose pattern — one write for the
  attachment and the meta — it reuses), [uc.platform.node-cover](../../platform/use-cases/uc.platform.node-cover.md),
  [uc.memory.gallery](uc.memory.gallery.md) (the lightbox entry point and the video playback it shares),
  [uc.memory.concurrent-curation](uc.memory.concurrent-curation.md) (the revision-checked meta write)
- Decisions it rests on: [ADR 0047](../../../decisions/0047-inline-video-playback-range-streaming.md) (playback),
  [ADR 0072](../../../decisions/0072-a-seal-binds-its-object.md), [ADR 0077](../../../decisions/0077-a-write-carries-the-version-it-read.md),
  [ADR 0079](../../../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md) (core's attachments),
  [ADR 0093](../../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) §§ 8–9
