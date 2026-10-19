# ADR 0102 — A Memory's banner is its own attachment, framed by a focal point

- Status: Accepted 2026-10-19, built by `cr-memory-banner`. The product shape is the owner's (2026-10-18); the owner
  answered the five open questions on 2026-10-19 — the word *Banner* (decision 1), a copy, not a reference (decision 3),
  the tab row sticks (§ Consequences), no length limit for a video, and a browser that cannot decode a frame of a video
  offers no video banner (decision 4) — and the same day dropped the in-browser transcoder: a video banner plays its
  copied original until the client worker queue (inbox #23) makes the small copy (decision 4).
- Date: 2026-10-18

## Context

The owner asked on 2026-10-18 for a medium shown **above the menu bar** of a Memory, **independent of its cover**. Its
product shape is decided:

- a fixed frame — about 3:1 on a wide screen, 16:9 on a phone, at most about 35 % of the viewport's height — that
  scrolls away while the menu bar sticks;
- a landscape medium **fills** the frame around a **focal point** the editor sets; a portrait one (image or video) is
  **fit** by default — the whole medium centred, the sides filled by a blurred, enlarged copy of it — and can be switched
  to fill;
- a video shows a poster with a play button and **never autoplays**;
- the source is **one of the Memory's media or an upload only for it**, which never appears in the gallery;
- whoever may edit the Memory sets it; a link's guest sees it as a member does (share view = member view).

Five things make this a decision rather than a field:

1. **The word "header" is taken.** The row with a node's title, cover tile and actions is core's `NodeHeader`
   (`web/src/core/node/components/NodeHeader.vue`), and [uc.memory.cover](../features/memory/use-cases/uc.memory.cover.md)
   says the cover shows in the Memory's "detail header". [ADR 0086](0086-one-word-per-meaning.md) gives a meaning one word.
2. **The cover's crop does not carry over.** A cover is one image and a 16:9 **crop**
   (`web/src/core/node/cover/components/CoverDialog.vue` `:aspect="16 / 9"`). This frame changes its aspect with the
   viewport, so a stored rectangle is wrong in one of them; what survives an aspect change is a **point** kept in view.
3. **There is no small video.** A gallery video has one derivative, a JPEG first-frame poster serving as thumbnail and
   preview, and playback streams the **original** ([en.memory.derivatives](../features/memory/enablers/en.memory.derivatives.md)
   § The three tiers; [ADR 0047](0047-inline-video-playback-range-streaming.md)). The server may not make one: it never
   holds plaintext.
4. **An upload that is not a medium** must still be erased with the Memory and the account, held when reported, counted
   against the quota and claimed by the store's reconcile — every path a `veyq_media` row walks, without being one.
5. **Which medium it is should stay sealed** (operator-blind). A reference the client resolves on every open — fetching
   one medium's preview each time, where the grid fetches thumbnails — would name that medium by its access pattern.

Core already owns what a cover's upload uses: **node attachments** — `veyq_node_attachment`, one blob per node **slot**,
the slot an application's role token, written only by core's `AttachmentStore`, and walked by core's erasers, the quota,
the hold, the reclaim and the reconcile ([ADR 0079](0079-veyq-is-a-platform-memories-is-its-first-application.md),
"Core's attachments").

## Decision

### 1. It is called the **banner**

Product word **banner** (EN and DE: *Banner*), in the interface, the docs and the code (`banner`, slots `BANNER` /
`BANNER_POSTER`). The *header* stays the title row; the *cover* stays the Memory's face on cards, pins and favorites.

### 2. It is a `banner` field in the Memory's sealed meta

A `banner` sub-object of the node's `wrappedMeta`, beside `date`, `location`, `cover` and `participants`: the `node-meta`
field, sealed under its own `K_field` per write ([ADR 0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
§ 9), its last editor on the node's `meta_editor_*` (§ 8), written with a compare-and-set on `metaRevision`
([ADR 0077](0077-a-write-carries-the-version-it-read.md)). It is Memories' plaintext; core stores it and never opens it
(ADR 0079, "The node's payload slot and content key are core's"). No new seal purpose, column or field group.

```
banner?: { medium: 'image' | 'video',
           blob:   Ref,                  // the BANNER attachment: the image (JPEG) or the video as it was copied
           poster?: Ref,                 // the BANNER_POSTER attachment: a video's first frame (JPEG); video only
           type?:  string,               // a video's media type (the copied original's container); video only
           origin?: { mediaId },         // the gallery medium it was made from; absent for an own upload
           frame:  'fill' | 'fit',       // 'fit': the whole medium, the sides a blurred enlargement of it
           focal:  { x, y },             // 0..1 of the medium; what 'fill' keeps in view
           alt?:   string }              // the editor's description, at most 200 characters
Ref = { blobId, chunkCount, byteSize, wrappedBlobKey }
```

A `Ref` carries neither owner nor role — both are the node's and the slot's, taken from where the meta is read (ADR 0072)
— and not the served `wrappedBlobMeta`: an image and a poster are always JPEG, and a video's type is the one `type` field.
The meta's size is bounded (`maxLength: 8192`, shared with the cover, the place and 100 participants): the worst case —
those, a range date, a video banner with a 200-character description of 4-byte characters — seals at 8076
(`web/src/features/memory/banner/bannerModel.test.ts`, "the meta budget"). A **focal point**, not a crop: the display
sets `object-fit: cover` with an `object-position` derived from it, so one point frames the medium in 3:1 and 16:9 alike.
`frame` is decided once in the editor — `fill` for a landscape or square medium, `fit` for a portrait one (height >
width), switchable for a portrait one only — and stored, so a reader never re-derives it. A reader is best-effort: a
malformed focal point is the centre, an unknown frame fills, an unknown shape is no banner and is preserved by every
other meta writer.

**The later slot.** The small playback copy (decision 4) will fill a third slot, `BANNER_PLAYBACK`, and add a `playback`
ref beside `blob`, which keeps meaning the copied original: a reader that knows `playback` plays it, one that does not
plays `blob`, and nothing that exists moves.

### 3. Every banner's bytes are the Memory's own attachment — a gallery medium is copied

| Slot | Holds | Present |
|---|---|---|
| `BANNER` | an image: one JPEG, long edge 2048, quality 0.85 (core's cover derive, `COVER_SPEC`) — **or** a video, copied as it is | always |
| `BANNER_POSTER` | a video's poster: its first frame as a JPEG | a video only |

Each is a fresh `K_blob` wrapped under the Memory's `K_node`, bound to blob, node and role
([ADR 0072](0072-a-seal-binds-its-object.md)), its `blob-meta` `{type}` under `K_blob` — a cover attachment's seal. Both
roles are Memories' (`MemoryBlobRoles`), member-only: a guest's upload start refuses them
(`ShareGuestWrites.admitsGuestRole`).

**A gallery medium is copied, never referenced** (the owner's answer). An image is derived from the medium's
**original** decrypted on the editor's device — never the preview's bytes re-sealed. A video's original is read by frame
through its chunk provider and re-sealed as it is (never held whole), and its poster is the first-frame JPEG its own
upload made (the gallery's preview tier), re-sealed — nothing is decoded for it. A gallery video whose upload made no
poster (a browser that could not decode it then) has no frame to show before it plays and is refused as a banner. A
picked file is prepared the same way: an image derived, a video copied with the first frame the editor's browser
decodes. The meta's `origin.mediaId` is sealed and serves only the interface ("this is the banner" in the lightbox; the
delete confirmation, decision 7).

- **One storage form, one lifecycle.** Whatever the source, the banner is the node's attachment: trashed and restored
  with the Memory, purged with it, counted in the quota, held on a report of the Memory, swept by the reclaim, claimed by
  the reconcile through its `veyq_blob` rows — paths core already runs for every attachment.
- **It renders before the gallery**: the meta names the attachments' refs, so the banner draws without the media list.
- **No fetch names the medium**: the banner reads its own attachment on every open, never the medium's.
- **Cost:** the bytes are stored twice (about 0.5 MB for an image; a whole video's size for a video until decision 4's
  small copy exists), deleting the gallery medium does **not** remove the banner, and a report of that medium does not
  reach the copy (§ What the server sees).

### 4. A video banner plays its copied original; the small copy is the client worker queue's

No transcoder runs in the browser (the owner, 2026-10-19). A video banner is the copied original (decision 3) played as a
gallery video plays: the poster and a play button until the viewer clicks, then ADR 0047's Service-Worker range shim over
the `BANNER` attachment's chunks — the path Depot's file page and the lightbox already take — with its accepted seek
leak; without a Service Worker the copy is decrypted whole first, as the lightbox's fallback does. No autoplay.

- **No length limit** (the owner): the banner is the Memory's lead-in, and a whole holiday's video may be long. Nothing
  bounds the copy but the quota, and nothing holds it whole: the upload reads it by frame.
- **A browser that cannot decode a frame** of a picked video offers no video banner, with a message saying so; the
  original is the banner only where a poster could be drawn for it.
- **The small playback copy** (H.264, long edge 1280, about 2 Mbit/s) is
  [en.memory.playback-derivative](../features/memory/enablers/en.memory.playback-derivative.md), made by a client worker
  node of the queue of [ADR 0021](0021-operator-blind-background-tasks.md) (inbox #23) — the same enabler a small tier for
  gallery videos would use (inbox #493). It fills the later slot of decision 2.

### 5. The write is Memories' own operation, built from core's pieces

`setMemoryBanner` and `removeMemoryBanner`, in `contracts/api/memories/memory-banner.openapi.yaml` — an application's
behaviour lives in its own directory (ADR 0079, "Contracts by owner"). As core's `setNodeCover`, one request hands the open
upload(s) **and** the re-sealed `wrappedMeta`, and the server, **in one transaction**, attaches `BANNER` (and
`BANNER_POSTER`, or detaches a poster an image replaces) through `AttachmentStore` and compare-and-sets the meta — so the
meta never names bytes that are gone, and no bytes outlive the meta that names them. `removeMemoryBanner` detaches both
and writes the meta without `banner`. A re-frame (focal point, fit or fill, the description) is a plain meta write.

**Unlike the cover, a stale slot writes nothing.** Both slots are compared first, under the scope row's lock every writer
of the attachment table takes; a miss is `409 banner_stale` with what both slots hold, before anything is written, and the
uploads stay open (a cover's single-slot miss commits and spends its upload; with two slots, a miss on the second after
the first was swapped would leave an image and a poster the meta does not name). A stale meta is `409 stale_revision`
and rolls everything back, as the cover's does.

Memories' operation rather than a slot parameter on core's cover operation: the banner is the Memories application's
feature, and core may not name it (`validate_kernel_imports_no_slices.py`). If a second application wants one, it is lifted
into core as the cover was (inbox #411); the attachment half already is core's.

**Who:** a member who may write the scope — exactly who may set the cover (`memoryRights` `setBanner` = `setCover`). No
link role gets a banner operation, as none gets one for the cover, the date or the place
([Memories' role matrix](../apps/memories.md#what-a-guest-may-do--the-role-matrix)); a guest sees the banner through the
share's gated attachment route (`AttachmentSharedSubtreeContributor`). A `MEMORY` and a `CHAPTER` both carry one: they
render the same view.

### 6. The client draws it, and nothing else processes it

The banner is drawn by the Memory's own view above core's `NodeHeader`, the same component for a member and a guest;
rights only remove the action. `fit`'s side fill is a second `<img>` of the same decrypted picture (a video: its poster),
enlarged and blurred by CSS; no blurred copy is made or stored. The description is the image's `alt` (a video's
accessible name); without one the banner is decorative (`alt=""`), since the title follows it.

### 7. What the interface says about the copy

Deleting a gallery medium that is the banner's origin asks first: the confirmation says the banner keeps its own copy and
offers to remove the banner in the same step. Nothing else ties the two.

## What the server sees

Published for readers outside the project in [What the server sees](../../site/src/content/docs/security/what-the-server-sees.md).

- That a Memory **has** a banner: one or two `veyq_node_attachment` rows in `BANNER` / `BANNER_POSTER`, their blob ids,
  sizes, the uploader's opaque `user:{id}` (no guest writes one) and the times.
- That it is **a video**: `BANNER_POSTER` exists only for one, and the size says so. Accepted: the same reader already sees
  every medium's size and count, and hiding it would cost a decoy blob per image banner.
- **Which gallery medium a copy came from, by its size, where the bytes are the same.** A video banner made from a
  gallery video is that original re-sealed as it is, so its blob has the original's exact size (and its poster is the
  medium's preview, re-sealed: the preview's size); an image banner made in the browser that uploaded the medium is
  derived with the preview tier's own spec, so its size can equal that preview's. An operator who compares sizes can name
  the medium. Accepted until the small copy (decision 4) replaces the video's bytes; whether the copy should also be
  padded, or the image derived with a spec of its own, is open (inbox #505).
- That the node's meta changed and who wrote it last (`meta_editor_*`) — not that the banner was the part that changed.
- When it plays: ADR 0047's per-chunk fetch pattern, as for any played video.
- When a gallery medium is chosen: one download of its original (an image's, or a video's read by frame while it is
  copied) just before the banner's upload.
- **Never:** the focal point, fit or fill, the description, a pixel or a frame, and no fetch on opening the Memory names
  a medium. A report of the gallery medium does not reach the banner made from it; the banner is reported with its
  Memory (a `NODE` report, whose hold copies the Memory's attachment rows and pins their blobs).

## Consequences

- **Every existing attachment path covers the banner** — trash, purge, account and tenant erasure, quota, the hold's
  snapshot and pin, the reclaim, the reconcile, the backup's blob index — with no new table naming a storage key, so no
  claim list, backup index or e2e decoy changes. `MemoryBannerSliceTest` names the slots on each path, because "core
  walks every attachment" is a statement about today's code.
- **The export and the copy carry the banner** ([ADR 0082](0082-a-node-and-everything-under-it-exports-as-a-plaintext-zip-and-imports-under-the-importer.md)):
  Memories' content gains a `banner` (its files, frame, focal point, description, and the origin as a media index), an
  import uploads the files into the new node's slots as they are, and a copy moves the blobs by frame. A banner whose
  bytes do not read is left out, never a failed export or import.
- **No new dependency**: nothing transcodes; the poster of a picked video is the gallery's existing first-frame derive.
- **The sticky row** is the Memory's tab row; the shell's top bar is sticky already (`web/src/styles/layout.css`), so the
  row's offset is the shell's `--app-sticky-top` (the top bar, the compact header below 1020 px, 0 on a share page,
  which has no top bar). The page scrolls the window, and so does the gallery's windowing (ADR 0105), which keeps a tile
  the keyboard moves to below the sticky stack (`--media-grid-occluded-top`).

## Rejected alternatives

- **A reference, as the cover's `mediaId`.** Names the medium to the operator by its access pattern on every open, renders
  only after the media list, and a video would still need bytes of its own.
- **A medium hidden from the gallery** (a flag, a hidden bucket). Carries the original, the live list, the export, the
  counters and the trash of a medium; every reader of `veyq_media` would have to learn to hide it — the cross-feature
  defect class `docs/ai/README.md` names (trash × sharing).
- **A small tier on every gallery video, made at upload.** Slows every video upload for a feature few use, needs a backfill
  and widens `MediaDerivativeKind` — ADR 0047's to revisit (inbox #493).
- **A transcode in the editor's browser** (WebCodecs and an MP4 muxer). Planned first; dropped by the owner on 2026-10-19
  in favour of the client worker queue (decision 4), which serves the gallery too.
- **A transcode on the server.** Breaks operator-blindness.
- **The cover's crop rectangle.** Wrong in one of the two aspects (Context 2).
- **A slot parameter on core's `setNodeCover`.** Puts a Memories feature into core's contract before a second application
  needs it.
- **The word "header".** Collides with the title row and the cover's "detail header" (Context 1).
