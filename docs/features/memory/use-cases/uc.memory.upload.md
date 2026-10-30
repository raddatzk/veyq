# UC: Upload media into a Memory (web)

- **ID:** uc.memory.upload
- **Primary actor:** Account holder browsing a Memory on the **web** client.
- **Goal:** Add photos/videos to the open Memory — sealed on-device and uploaded direct-to-store — with a
  **global uploads tray** (a TopBar button + popup) that shows every in-flight upload's progress and lets the
  user cancel, while they keep navigating.
- **Preconditions:** Signed in + unlocked; a Memory is open (its `K_node` is available); the media data layer +
  blob store exist (en.memory.media, cr-0028/0029) and derivatives are generated on-device (cr-0045).
- **Postconditions (success):** Each picked file is sealed under a fresh `K_blob` (wrapped to `K_node`), its
  chunks `PUT` straight to the store, and one logical media (original + role-tagged derivatives) is attached to
  the Memory node. The gallery refreshes to show it. Operator-blind throughout — the server sees only ciphertext
  + opaque sizes/counts.

## Main flow

1. In the open Memory's gallery the user picks files (a hidden `<input type=file multiple accept=image/*,video/*>`
   behind an **Upload** affordance).
2. Each file is enqueued into the **global upload queue** as a batch against the current `{scopeId, nodeId}` +
   the Memory key. The TopBar **uploads** button shows a spinner + an active-file count badge, **and** the picked
   photos appear **immediately as tiles in that Memory's gallery grid** — with a **local preview** (the user's
   own image, before it is sealed, never uploaded in the clear: a photo drawn on the device at the gallery thumbnail's
   size, or a video's first frame at the same size, one at a time, for the tiles near the viewport first, inbox #785 and
   #786) and a per-tile progress /
   error(+retry) state (cr-0074). A tile appears **at its capture-time place**, not at the front: the sort key is
   derived on device before sealing, from the same rule the sealed tier will carry, so the tile is already where
   it will stay. [uc.memory.gallery](uc.memory.gallery.md) owns that ordering rule.
3. The queue puts **one file at a time on the wire**: each file is sealed on-device (with its
   [derivatives](../enablers/en.memory.derivatives.md)) and sent **direct-to-store**, then attached to the
   Memory node — the seal → start → PUT → attach choreography is
   [en.memory.media](../enablers/en.memory.media.md)'s. While one file is on the wire, the **next** file runs its
   on-device stage — its type, its digest, its tiers and its EXIF read, which touch no key and send nothing — so it is
   ready when the wire is free (inbox #220). Only one file is prepared ahead, and every key use, seal and request of a
   file still follows the previous file's completion. Per-file + per-list **progress** advance as it uploads.
   **Each tile names the stage its file is actually in** — *queued*, *preparing*, *uploading* — because they are
   three different waits and in two of them no byte has left the device yet. A tile that said *"uploading"*
   while the device was still decoding is what made a large batch read as hung. A file prepared ahead says
   *preparing* until its first byte is sent, never back to *queued*.
4. Opening the uploads popup shows **one list per destination** (owner decision 2026-10-14): every batch enqueued
   for the same target (the batch's `key`) whose list is still open is drawn as ONE list, under one title, with ONE
   progress bar over all its files. A batch enqueued later for that target joins the open list and the bar recomputes
   over the larger set (so it can go down); a list the user closed is not reopened, a later batch starts a new one.
   Different targets stay separate lists. Each file shows the same status (queued / preparing / uploading / done /
   failed / cancelled) and a **cancel** control while it is queued/uploading. The grouping is the tray's: the queue
   keeps its batches, with their own completion hooks.
5. As each file finishes, its optimistic tile **reconciles into the real decrypted thumbnail** (matched by the
   returned `mediaId`) with no empty-flash and no duplicate: the tile's **content** changes, its **position**
   does not. The user can **close** (acknowledge) a finished list in the tray, which closes every batch in it.

## Alternate / exception flows

- **E1 — transient failure** (aborted/failed request): the file is re-queued for one automatic retry; a
  hard error marks **that file** failed (the user retries it explicitly); the rest of its batch keeps uploading —
  one refused photo does not hold back the others (owner decision 2026-10-14, inbox #426).
- **E2 — cancel:** cancelling a queued file drops it; cancelling an in-flight file aborts its PUTs and stops
  before `complete` (no media is attached). The abandoned upload session is reclaimed by the store-side GC.
- **E3 — Safari's Photos picker.** Files chosen from the Photos gallery arrive with an **empty MIME type**, and
  Safari **populates `input.files` late / across repeated picks**. The picker therefore (a) accepts a
  file by **extension** when its `type` is empty, (b) **re-reads** the input over a short retry window and
  **merges** (never replaces) by a `name:size:lastModified:type` signature, and (c) infers a `mediaType` from
  the extension so derivation can still run (HEIC/MOV); a file whose extension names nothing either has its type
  sniffed from its first bytes at upload. That type is what the original's sealed blob meta declares, beside the
  file's name and modified time (inbox #397) and its **SHA-256** (base64url), read once more, frame by frame, before the
  file is planned: by it a later upload of the same file into the Memory's cover or banner dialog shares the stored
  object instead of storing it again, and a member's upload of a file equal to the node's **video banner** shares the
  banner's object as its ORIGINAL (its posters derived and uploaded as always; `completeMedia`'s `originalShared`;
  [ADR 0013](../../../decisions/0013-blob-storage-presigned-direct-to-store.md) § 5). A guest's upload is never shared.
  **The window never delays the files already in hand** —
  each re-read enqueues only what is genuinely new, as an additional batch; gating the first enqueue on it made
  every browser pay for the one this workaround exists for. Underivable bytes upload **original-only** (the
  gallery shows an icon).
- **E4 — underivable media:** if on-device derivation fails (codec the browser can't decode), the original is
  uploaded without derivatives rather than failing the upload.
- **E5 — no space left:** a file that would take the tenant over its storage quota is refused at `start`, before a
  byte is sent — original and derivatives together. It is marked **failed** with that reason and is neither retried
  automatically nor offered a retry, since a retry cannot help; trash counts until it is emptied. A cover refused
  the same way says the same
  ([uc.admin.tenant-storage-quota](../../admin/use-cases/uc.admin.tenant-storage-quota.md)).

## Quality notes

- **Operator-blind, unchanged.** The client seals every chunk under a random `K_blob` (wrapped to `K_node`) and
  PUTs raw sealed bytes; the server stores ciphertext + opaque sizes/counts (ADR 0013). No plaintext, key, or
  filename reaches the server in the clear.
- **The tray is global + non-blocking.** Uploads continue while the user browses other Memories; the queue is a
  module singleton, the popup lives in the app shell.
- **One file on the wire, one prepared ahead.** One file at a time on the wire keeps the byte budget simple; the next
  file's on-device stage overlaps it because each stage is mostly worker and wire time, about as long as the other
  (inbox #220's reading). Depth 1 keeps one original decoded at a time and one tier set waiting. Within a file the chunks
  stream — read, sealed and sent a few at a time — so a large video costs the device a few frames of memory,
  not the file twice, and its size is bounded by the server's ceiling rather than by how fast the uplink is
  ([en.memory.media](../enablers/en.memory.media.md)).

- **Picking a batch decodes nothing at once.** A picked photo's tile takes its aspect ratio from the size its file's
  header names (`src/web/src/shared/image/imageHeader.ts`; for a format it does not read, an image element that is never
  drawn), never from a decode, so the only decodes are the on-device stage's, one file ahead (inbox #558). Read in the
  profile's S2 (50 photos of 5.2 MB, 2026-10-29): decoding every picked photo for its size had put the tab's renderer
  at 2.30–2.79 GiB at throttle 4 within 1.6 s of the pick, against 1.0–1.1 GiB for the rest of the upload; now its peak
  is the upload's own, 1.14–1.27 GiB.
- **A tile's preview is tile-sized, and drawn only near the viewport.** A tile used to show the picked file itself, so
  every tile near the viewport held a decode of the whole photo (48.8 MB for 12 megapixels) while the batch uploaded: in
  S2 the renderer sat at 1.03–1.04 GiB (median of the window; peak 1.11–1.18 GiB) against 0.24 GiB before the pick, and
  0.44–0.46 GiB in a build that showed no preview at all, so the previews held about 0.6 GiB (inbox #785, 2026-10-29).
  Now the grid announces the tiles it mounts, nearest first, as it does for thumbnails
  ([ADR 0105](../../../decisions/0105-a-gallery-renders-what-is-near-the-viewport-and-opens-its-listing-in-a-worker.md)),
  and the media Worker draws each one's photo at the thumbnail tier's size, one at a time
  (`src/web/src/features/memories/useOptimisticTiles.ts`): 0.49–0.53 GiB, peak 0.61–0.67 GiB, in the same 10.4–11.6 s
  (13.0–13.4 s at throttle 4). The price is that a tile's picture arrives a moment after the tile (about 0.2 s per
  tile while the batch is being prepared: 12 of the 24 mounted tiles had theirs 2.5 s after the pick).
- **A video's tile picture is drawn in the same queue.** A picked video's tile used to get its first frame at once, at
  the lightbox's 2048 px: every picked video opened a `<video>` decoder in the same moment. Read in the profile's S2v
  (20 clips of 1080p, H.264 at 16 Mbit/s, 6 s, 12.5 MB each, three runs each without the CPU profiler, 2026-10-29),
  with the page's media players counted (CDP's `Media` domain): 34–41 players alive and 18–20 decoding at once, the
  renderer peaking at 1.51–1.72 GiB at throttle 1 (1.75–1.78 GiB at 4) within a second of the pick (2.4 s at 4) and
  holding a median of 1.02–1.08 GiB, the upload taking 8.7–9.1 s (14.3–14.7 s). A build whose tiles drew no poster
  peaked at 0.94–0.96 GiB in 8.1–8.3 s (10.8–10.9 s): the posters cost the renderer 0.6–0.8 GiB at the pick and the
  upload 3.5–3.9 s at throttle 4, the 20 large frames being drawn and encoded on the page's main thread. Now a video's
  first frame is drawn at the thumbnail tier's size, in the photos' queue — near the viewport, one at a time
  (`src/web/src/features/memories/useOptimisticTiles.ts`): 2 players decode at once (the tile's and the upload's own),
  the peak is the upload's own at its end, 1.09–1.12 GiB, the median 0.84–0.89 GiB, and the upload takes 8.3–8.8 s
  (12.4–12.5 s). The pictures come one after another: the first 0.3 s after the pick (0.6 s at throttle 4, against
  0.7–0.9 s and 2.8–3.3 s before), the twelfth at 1.5–1.6 s (3.9 s, against 1.1 s and 4.1–4.6 s), and a tile whose
  file is uploaded before its turn shows the listed thumbnail with no poster of its own (3 of 20 at throttle 1, 5 at
  4).
- **A picked video's size is read from its container's header.** A tile's aspect ratio and the upload's sealed size used
  to come from a `<video>` per file that loaded its metadata, every picked one at once and each again at its upload: 40
  of S2v's 75–77 media players, 22 alive at once at the pick, each living 0.1–0.45 s (median; up to 2.4 s at throttle
  4). Now `src/web/src/shared/image/videoHeader.ts` reads it from slices of the file, as photos are read (an MP4 or
  QuickTime file's video `tkhd`, turned by its matrix as a player shows it; a WebM or Matroska file's Video element), and
  a `<video>` loads the metadata only where that header does not read, let go of once it answered (inbox #793). S2v,
  three runs each without the CPU profiler, every series started at a 1-minute load of 3.66–3.95 (2026-10-29): 35–37
  players, 2 alive at once, the upload 12.0–12.3 s at throttle 4 against 12.8–13.5 s (8.1–8.8 s against 8.3–9.1 s at
  throttle 1, within the runs' spread), the first tile picture 0.5 s after the pick at throttle 4 against 0.6 s.
  Only dropping the old probe's source once its metadata was in shortened each player's life to 0.05–0.18 s but kept the
  22 at once and the time (12.5–13.1 s). The renderer's memory is unchanged by either, a median of 0.85–0.89 GiB and a peak of 1.07–1.13 GiB:
  what it holds above the 0.71–0.72 GiB of a build that draws no tile poster (peak 0.92–0.94 GiB, the upload 10.6–10.8 s
  at throttle 4) is those posters' draws onto the canvas, not their decodes: a build that decodes and presents each
  tile's frame and never draws it holds 0.70–0.71 GiB, one that draws it and never encodes it 0.87–0.88 GiB. The step is
  there within the first ~2 s, while the 17 tile posters are made, and stays to the end. Neither `preload = 'metadata'`,
  one reused `<video>`, a player given only the file's first 2 MiB, one poster player at a time, emptied canvases nor
  low smoothing moves it; drawing an `ImageBitmap` of the frame, closed after the encode, takes off about 0.04 GiB
  (S2v, 2026-10-30, every series at a 1-minute load of 4.3–9.8; inbox #795). So a tile no longer draws its video: it
  scales down the PREVIEW-tier poster its upload stores, drawn once for both (`videoPosterOf`). Three series each against
  the build before, every one started under a load of 3: 21 players instead of 37, the peak 0.95–0.97 GiB against
  1.08–1.12 GiB, the upload 11.1–11.4 s against 12.4–13.4 s at throttle 4 (8.1–8.8 s against 8.1–9.1 s at throttle 1),
  the first tile picture about 0.1 s later. The median fell only to 0.84–0.86 GiB (0.82–0.84 at throttle 4). What holds
  it above a build with no video tile picture (0.69–0.72 GiB) is the real frame, in two parts (two series of three runs
  per build, interleaved, all under the load gate): the tiles asking for their posters ahead of the upload, about
  0.08 GiB of median, which piles each poster draw's residue up early and leaves the end level where the floor's is
  (0.88–0.90 GiB); and the worker's decode of each 2048 px poster to scale it, about 0.04–0.06 GiB, still there at the
  end (0.94 against 0.88 GiB). Showing the picture costs nothing measurable: the scaled posters made and never shown hold
  what the shipped build holds, a constant 8×8 JPEG shown on every tile holds the floor, and the play badge moves nothing.
  So the floor is reached only by a video tile with no frame of its own, which is the product's choice and not taken: the
  peak differs by 0.04 GiB. A tile that asks only once its upload leaves `queued` lowers the median by 0.09 GiB and raises
  the peak by 0.03–0.05 GiB, so it is not taken either. A tile wanted after its upload took the poster drew the clip a
  second time (21–22 players for 20 clips); it now waits for its listed thumbnail (`tileVideoPosterOf`). The one lever
  left unmeasured is encoding the tile's 512 px JPEG from the canvas the poster draw already holds, instead of the
  worker decoding the 2048 px JPEG: at most the 0.04–0.06 GiB end-level part, not the peak (S2v, 2026-10-30; inbox #795).

## User impact

You add photos and videos to a Memory in the background and can follow or cancel each upload, with everything
encrypted on your device before it leaves.

## Related

- Feature: [Memory](../_index.md); enabler [en.memory.media](../enablers/en.memory.media.md) (the data layer).
- Use-cases: [browse](uc.memory.browse.md), [gallery](uc.memory.gallery.md) (where the upload affordance lives).
- Decisions: [ADR 0013](../../../decisions/0013-blob-storage-presigned-direct-to-store.md) (blob store + transport;
  windowed presigning; resuming **across a page reload** deferred).
- End-user view (docs site): [Add photos and videos](../../../../site/src/content/docs/memories/add-photos-and-videos.mdx)
