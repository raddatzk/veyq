# UC: Memory banner — an image or a video above the Memory, framed once for every screen

- **ID:** uc.memory.banner
- **Status:** **planned** — refined 2026-10-18 from the owner's request of that day
  ([ADR 0102](../../../decisions/0102-a-memorys-banner-is-its-own-attachment-framed-by-a-focal-point.md), proposed); the
  work order is `changes/cr-memory-banner.md`. Nothing is built.
- **Primary actor:** A member with an **unlocked vault** who may write the Memory's scope (the cover's right,
  `memoryRights.setCover`) and has a Memory or a Chapter open. **Secondary:** anyone who opens it — a member, or a guest of a
  `READ` or `READ_WRITE` link, who sees the banner exactly as a member does.
- **Goal:** Put **one image or one video** above the Memory's menu bar — a **banner**, independent of the cover — chosen
  from the Memory's own media or uploaded only for this, framed once so it looks right on a wide screen and on a phone,
  with the server never learning which medium it is or how it is framed.
- **Preconditions:** The Memory's `K_node` is held and its `wrappedMeta` opens
  ([en.memory.node-meta](../enablers/en.memory.node-meta.md)); the scope is not pending deletion. For a video, the browser
  can encode H.264 ([en.memory.playback-derivative](../enablers/en.memory.playback-derivative.md)).
- **Postconditions (success):** The Memory's sealed `wrappedMeta.banner` names the banner's attachments with its frame,
  focal point and description, one revision later; the node holds the banner's bytes in core's attachment slots `BANNER`
  (and `BANNER_POSTER` for a video), written in the same transaction as the meta. Every viewer sees the banner above the
  menu bar. The gallery is unchanged.

## What a banner is

A banner is **one medium in its own copy** plus how to frame it:

- **The medium** — an image, or a video **shown as a poster with a play button** that plays a **small copy** made on the
  editor's device (long edge 1280, H.264), never the original and never on its own.
- **The source** — **one of the Memory's media** (an image or a video of its gallery; the banner is made from its
  original, and the gallery keeps the medium untouched) **or a file uploaded only for the banner**, which never appears
  in the gallery, the item count, the live list or the trash.
- **The frame** — **fill**: the medium covers the frame, kept around a **focal point** the editor sets; or **fit**: the whole
  medium centred, the sides filled by a blurred, enlarged copy of it. A landscape or square medium fills; a portrait one
  (taller than wide) fits by default and may be switched to fill.
- **A description** (optional, at most 200 characters) — read by screen readers in place of the picture.

The **frame on screen** is fixed by the screen, not by the medium: about **3:1** where the Memory's column is wide,
**16:9** on a narrow one (a phone), never taller than about **35 %** of the viewport. The banner sits above the Memory's
header row (title, cover tile, date, place, actions) and **scrolls away**; the Memory's **tab row** (Gallery, Chapters,
Contributions, Trash) **sticks** below the app's top bar.

## Main flow

1. The member chooses **Banner** — *Add banner* where the Memory has none, *Change banner* where it has one — among the
   header row's actions, or **Use as banner** in the gallery's large view of an image or a video.
2. The **banner dialog** opens: **Choose an image or a video** (a file input for `image/*` and `video/*`), or the medium the
   lightbox handed over, or the current banner to re-frame. An image above 40 MB (the cover's bound) or a video longer than
   2 minutes is refused inside the dialog with a message, and nothing is read further.
3. The dialog shows the medium with the **focal point** as a handle the member drags (a click moves it too), and beside it
   **two previews** — the wide frame and the phone frame — that follow the point live. A portrait medium starts in **fit**
   with its blurred sides; a **Fill / Fit** switch is offered for it. A **description** field sits below.
4. **Save banner**:
   - **A new medium** is prepared on the device: an image is derived to one JPEG (long edge 2048, quality 0.85); a video is
     transcoded to its small copy (the dialog shows progress and can be cancelled) and its first frame taken as the poster.
     A gallery medium is derived **from its original** — downloaded and decrypted on the device, as a download is. Each
     result is sealed under a fresh `K_blob` wrapped under the Memory's `K_node` and uploaded through the core upload queue
     (the tray shows it; a quota refusal says so).
   - One request, `setMemoryBanner`, hands the upload(s) and the re-sealed `wrappedMeta` (revision-checked): the server
     attaches `BANNER` (and `BANNER_POSTER`, or frees a poster an image replaces), freeing the blobs replaced, and writes the
     meta — all or nothing.
   - **Only the framing changed** (focal point, fill/fit, description): a plain meta write, no upload.
5. The Memory re-renders: the banner store is keyed on the sealed meta, so the new banner appears and the old object URL is
   revoked at once. Everyone else sees it on their next tree refresh.
6. **Remove banner** (in the dialog, where a banner exists) is `removeMemoryBanner`: both slots freed and the meta re-sealed
   without `banner`, in one write.
7. **Viewing:** the banner draws from the node's own attachments, before the gallery list arrives (its frame keeps its
   space, so nothing below it jumps). An image shows at once. A video shows its poster and a **play button**; a click plays
   the small copy with controls, streamed through the media service worker; nothing ever starts on its own.

## Alternate / exception flows

- **A1 — the lightbox:** *Use as banner* on a gallery image or video opens the dialog seeded with that medium (the lightbox
  stays open beneath it, its keys suspended, as for *Set as cover*). The button reads *Current banner* on the medium the
  banner was made from (`origin.mediaId`).
- **A2 — deleting the medium the banner was made from:** the delete confirmation says that the banner keeps its own copy and
  offers **Remove the banner too**; nothing else ties the two. A banner whose origin is gone simply stays.
- **E1 — a peer changed the banner meanwhile:** `409 banner_stale` — nothing attached, the old banner intact, the upload freed;
  the member is told and may try again. A meta that moved is `409 stale_revision`: nothing written, the upload still open;
  the client re-applies its change on the current meta and sends the same upload again.
- **E2 — the upload, the transcode or the write fails, or is cancelled:** the old banner stays exactly as it was; an upload
  no write attached is reclaimed by the stale-session purge.
- **E3 — the browser cannot encode a video:** a video is refused on that device with a message that offers its first
  frame as an image banner instead. The original is never used.
- **E4 — a file the browser cannot decode:** refused (`banner_unreadable`), never sealed as a banner nothing could draw.
- **E5 — the banner fails to load, or the meta does not open:** no banner is drawn and the header row moves up; never a
  broken frame, never a throw. A banner whose shape this client does not know is ignored and preserved.
- **E6 — a guest:** sees the banner (the node's `wrappedMeta` it already gets, the attachments through the share's gated
  blob route) and is offered no action; the server has no guest operation for it. **Share view = member view**, with the
  function removed.
- **E7 — locked keys, a pending deletion, a member without write:** no action is offered; the server refuses the rest
  as it refuses a cover (`scope_deleting`; a caller without write is answered as for a node it cannot see).
- **E8 — the Memory is trashed, restored or purged:** the banner goes and comes back with it, and its blobs are freed with
  the purge. Erasing the account that owns a personal scope, or deleting the tenant, frees them with the scope.

## Quality notes

- **Operator-blind.** The server holds the attachment rows (node, slot, blob id, size, the member's `user:{id}`) and the
  opaque `wrappedMeta` — never which medium a banner was made from, its focal point, fit or fill, the description, or a
  pixel. It can tell that a banner exists and whether it is a video (the poster slot, the size). A gallery medium is copied
  from its original, so no size or recurring fetch names it; choosing one costs one download of its original, observable
  as timing. Details: [ADR 0102](../../../decisions/0102-a-memorys-banner-is-its-own-attachment-framed-by-a-focal-point.md)
  § What the server sees.
- **No half state.** The slots and the meta that names them are one transaction; a refused meta write rolls the attach
  back.
- **No autoplay, no motion of its own.** Nothing moves until the viewer clicks; the blur is a static picture, so
  reduced-motion and data-saver settings need nothing from the banner.
- **One point, every screen.** One stored focal point frames the medium in 3:1 and in 16:9 through one display formula;
  no per-screen framing is stored.
- **Removal is real, with the cover's honest limits:** a replaced or removed banner's blobs go in the same transaction with a
  reclaim record per storage key; copies already loaded in other viewers' browsers and unexpired presigned URLs are beyond
  the owner's reach.
- **Reported with its Memory.** A banner is reported by reporting the Memory; because the server cannot know which gallery
  medium a banner was copied from, a report of that medium does not reach the banner.

## User impact

A Memory can open with a wide picture or a short film of the moment — framed once, right on every screen — without the
server learning which photo or video you picked.

## Related

- Feature: [Memory](../_index.md)
- Decision: [ADR 0102](../../../decisions/0102-a-memorys-banner-is-its-own-attachment-framed-by-a-focal-point.md)
- Enablers: [en.memory.playback-derivative](../enablers/en.memory.playback-derivative.md) (the small video),
  [en.memory.node-meta](../enablers/en.memory.node-meta.md) (the sealed `wrappedMeta` the `banner` field lives in)
- Use-cases: [uc.memory.cover](uc.memory.cover.md) (the sibling it is independent of, and whose pattern — one write for the
  attachment and the meta — it reuses), [uc.platform.node-cover](../../platform/use-cases/uc.platform.node-cover.md),
  [uc.memory.gallery](uc.memory.gallery.md) (the lightbox entry point and the video playback it shares),
  [uc.memory.concurrent-curation](uc.memory.concurrent-curation.md) (the revision-checked meta write)
- Decisions it rests on: [ADR 0047](../../../decisions/0047-inline-video-playback-range-streaming.md) (playback),
  [ADR 0072](../../../decisions/0072-a-seal-binds-its-object.md), [ADR 0077](../../../decisions/0077-a-write-carries-the-version-it-read.md),
  [ADR 0079](../../../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md) (core's attachments),
  [ADR 0093](../../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) §§ 8–9
