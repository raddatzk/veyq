# ADR 0102 — A Memory's banner is its own attachment, framed by a focal point, and its video is a small copy the editor's device makes

- Status: Proposed 2026-10-18. The product shape is the owner's (2026-10-18) and not reopened here; decision 1 carries
  `cr-memory-banner`'s owner question Q1, decision 3 Q2, decision 4 Q4 and Q5, and § Consequences Q3, each as its
  recommendation; it becomes Accepted with the owner's answers. Nothing is built.
- Date: 2026-10-18

Provenance: **(M)** measured in this worktree — the command is named here or in `cr-memory-banner`'s grounding table;
**(R)** remembered, measured by the CR before it binds.

## Context

The owner asked on 2026-10-18 for a medium shown **above the menu bar** of a Memory, **independent of its cover**. Its
product shape is decided:

- a fixed frame — about 3:1 on a wide screen, 16:9 on a phone, at most about 35 % of the viewport's height — that
  scrolls away while the menu bar sticks;
- a landscape medium **fills** the frame around a **focal point** the editor sets; a portrait one (image or video) is
  **fit** by default — the whole medium centred, the sides filled by a blurred, enlarged copy of it — and can be switched
  to fill;
- a video shows a poster with a play button, **never autoplays**, and plays **a small derivative, not the original**;
- the source is **one of the Memory's media or an upload only for it**, which never appears in the gallery;
- whoever may edit the Memory sets it; a link's guest sees it as a member does (share view = member view).

Five things make this a decision rather than a field:

1. **The word "header" is taken.** The row with a node's title, cover tile and actions is core's `NodeHeader`
   (M: `web/src/core/node/components/NodeHeader.vue`), and [uc.memory.cover](../features/memory/use-cases/uc.memory.cover.md)
   already says the cover shows in the Memory's "detail header". [ADR 0086](0086-one-word-per-meaning.md) gives a meaning
   one word.
2. **The cover's crop does not carry over.** A cover is one image and a 16:9 **crop**
   (M: `web/src/core/node/cover/components/CoverDialog.vue` `:aspect="16 / 9"`). This frame changes its aspect with the
   viewport, so a stored rectangle is wrong in one of them; what survives an aspect change is a **point** kept in view.
3. **There is no small video.** A gallery video has one derivative, a JPEG first-frame poster serving as thumbnail and
   preview, and playback streams the **original** (M: `web/src/shared/image/mediaDeriver.ts` `deriveVideoPoster`;
   [en.memory.derivatives](../features/memory/enablers/en.memory.derivatives.md) § The three tiers).
   [ADR 0047](0047-inline-video-playback-range-streaming.md) chose the original because no transcode enabler existed;
   nothing in `web/` transcodes today (M: `grep -rni 'webcodecs\|VideoEncoder\|ffmpeg\|MediaRecorder' web/src
   web/package.json` → nothing). The server may not make one: it never holds plaintext.
4. **An upload that is not a medium** must still be erased with the Memory and the account, held when reported, counted
   against the quota and claimed by the store's reconcile — every path a `veyq_media` row walks, without being one.
5. **Which medium it is must stay sealed** (operator-blind). A reference the client resolves on every open — fetching
   one medium's preview each time, where the grid fetches thumbnails — would name that medium by its access pattern; a
   copy re-sealed byte for byte would name it by its size.

Core already owns what a cover's upload uses: **node attachments** — `veyq_node_attachment`, one blob per node **slot**,
the slot an application's role token, written only by core's `AttachmentStore`, and walked by core's erasers, the quota,
the hold, the reclaim and the reconcile (M: grounding table; [ADR 0079](0079-veyq-is-a-platform-memories-is-its-first-application.md),
"Core's attachments").

## Decision

### 1. It is called the **banner**

Product word **banner** (EN and DE: *Banner*), in the interface, the docs and the code (`banner`, slots `BANNER` /
`BANNER_POSTER`). The *header* stays the title row; the *cover* stays the Memory's face on cards, pins and favorites.
(`cr-memory-banner` Q1 — the owner said "header"; this is the recommendation.)

### 2. It is a `banner` field in the Memory's sealed meta

A `banner` sub-object of the node's `wrappedMeta`, beside `date`, `location`, `cover` and `participants`: the `node-meta`
field, sealed under its own `K_field` per write ([ADR 0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
§ 9), its last editor on the node's `meta_editor_*` (§ 8), written with a compare-and-set on `metaRevision`
([ADR 0077](0077-a-write-carries-the-version-it-read.md)). It is Memories' plaintext; core stores it and never opens it
(ADR 0079, "The node's payload slot and content key are core's"). No new seal purpose, column or field group.

```
banner?: { medium: 'image' | 'video',
           blob:   BlobRef,              // the BANNER attachment: the image (JPEG) or the small video (MP4)
           poster?: BlobRef,             // the BANNER_POSTER attachment: a video's first frame (JPEG); video only
           origin?: { mediaId },         // the gallery medium it was made from; absent for an own upload
           frame:  'fill' | 'fit',       // 'fit': the whole medium, the sides a blurred enlargement of it
           focal:  { x, y },             // 0..1 of the medium; what 'fill' keeps in view
           alt?:   string }              // the editor's description, at most 200 characters
```

`BlobRef` is the cover's (`web/src/core/blob/types.ts`) without the served `wrappedBlobMeta`: a banner's types are fixed
(`image/jpeg`, `video/mp4`) and the meta's size is bounded (`maxLength: 8192`, shared with the cover and 100
participants — the CR measures the worst case). A **focal point**, not a crop: the display sets `object-fit: cover` with
an `object-position` derived from it, so one point frames the medium in 3:1 and 16:9 alike. `frame` is decided once in
the editor — `fill` for a landscape or square medium, `fit` for a portrait one (height > width), switchable for a
portrait one only — and stored, so a reader never re-derives it.

### 3. Every banner's bytes are the Memory's own attachment — a gallery medium is copied

| Slot | Holds | Present |
|---|---|---|
| `BANNER` | the image — one JPEG, long edge 2048, quality 0.85 (core's cover derive, `COVER_SPEC`) — **or** the small video (decision 4) | always |
| `BANNER_POSTER` | a video's poster: its first frame as a JPEG (the gallery poster's derive, `deriveVideoPoster`) | a video only |

Each is a fresh `K_blob` wrapped under the Memory's `K_node`, bound to blob, node and role
([ADR 0072](0072-a-seal-binds-its-object.md)), its `blob-meta` `{type}` under `K_blob` — a cover attachment's seal. Both
roles are Memories' (`MemoryBlobRoles`), member-only: a guest's upload start refuses them
(`ShareGuestWrites.admitsGuestRole`). The original is never stored for a banner.

**A gallery medium is copied, never referenced.** Choosing one runs the same derive as an upload, **from the medium's
original** decrypted on the editor's device — never the preview's bytes re-sealed, whose size would name the medium. The
meta's `origin.mediaId` is sealed and serves only the interface ("this is the banner" in the lightbox; the delete
dialog's notice, decision 7). (`cr-memory-banner` Q2.)

- **One storage form, one lifecycle.** Whatever the source, the banner is the node's attachment: trashed and restored
  with the Memory, purged with it, counted in the quota, held on a report of the Memory, swept by the reclaim, claimed by
  the reconcile through its `veyq_blob` rows — paths core already runs for every attachment.
- **It renders before the gallery**: the meta names the attachments' refs, so the banner draws without the media list,
  which a large Chapter takes long to load (inbox #488–#490).
- **The server never learns which medium.** It sees a new blob of unrelated size. The one residual is timing: choosing a
  gallery medium downloads its original once, just before the banner's upload.
- **Cost:** the bytes are stored twice (about 0.5 MB for an image, a few MB for a video), and deleting the gallery medium
  does **not** remove the banner; a report of that medium does not reach the copy (§ What the server sees, last point).

### 4. The small video is made on the editor's device, at the moment the banner is chosen

A client-side transcode — the browser's **WebCodecs** (`VideoDecoder`, `VideoEncoder`, `AudioDecoder`, `AudioEncoder`)
and an actively maintained, vendor-neutral MP4 muxer — turns the chosen video (an upload's file, or a gallery original
decrypted as a download is) into a small H.264 MP4 with its sound: long edge 1280, at most 2 Mbit/s video; audio and its
codec are what the CR's spike settles. Its home is the enabler
[en.memory.playback-derivative](../features/memory/enablers/en.memory.playback-derivative.md). The original never leaves
the device unsealed, and the server never transcodes.

- **Only the banner uses it.** The gallery keeps ADR 0047's original playback; a small tier for gallery videos would be
  that ADR's to decide, with this enabler under it ([inbox](../inbox.md) #493).
- **A bounded length.** A video longer than **2 minutes** is refused for a banner, with a message (`cr-memory-banner` Q4).
- **No original, ever.** Where a browser cannot encode (no `VideoEncoder`, or no H.264 configuration it supports), a
  video banner is refused on that device with a message saying so, which offers the video's first frame as an image
  banner instead (`cr-memory-banner` Q5).
- **Playback** is ADR 0047's Service-Worker range shim over the `BANNER` attachment's chunks — the path Depot's file page
  already takes for an attachment (M: `web/src/features/depot/useFileViewer.ts` calls `registerPlayback`); its accepted
  seek-leak applies. No autoplay: the poster and a play button until the viewer clicks; then the video plays with its
  controls.

### 5. The write is Memories' own operation, built from core's pieces

`setMemoryBanner` and `removeMemoryBanner`, in `contracts/api/memories/` — an application's behaviour lives in its own
directory (ADR 0079, "Contracts by owner"). As core's `setNodeCover`, one request hands the open upload(s) **and** the
re-sealed `wrappedMeta`, and the server, **in one transaction**, attaches `BANNER` (and `BANNER_POSTER`, or detaches a
poster an image replaces) through `AttachmentStore`, each compared against the blob it expects to replace, and
compare-and-sets the meta — so the meta never names bytes that are gone, and no bytes outlive the meta that names them.
`removeMemoryBanner` detaches both and writes the meta without `banner`. A re-frame (focal point, fit or fill, the
description) is a plain meta write.

Memories' operation rather than a slot parameter on core's cover operation: the banner is the Memories application's
feature, and core may not name it (`validate_kernel_imports_no_slices.py`). If a second application wants one, it is lifted
into core as the cover was (inbox #411); the attachment half already is core's.

**Who:** a member who may write the scope — exactly who may set the cover (`memoryRights` `setCover`). No link role gets a
banner operation, as none gets one for the cover, the date or the place ([Memories' role matrix](../apps/memories.md#what-a-guest-may-do--the-role-matrix));
a guest sees the banner through the share's gated attachment route (`AttachmentSharedSubtreeContributor`). A `MEMORY` and
a `CHAPTER` both carry one: they render the same view.

### 6. The client draws it, and nothing else processes it

The banner is drawn by the Memory's own view above core's `NodeHeader`, the same component for a member and a guest;
rights only remove the action. `fit`'s side fill is a second `<img>` of the same decrypted image (a video: its poster),
enlarged and blurred by CSS; no blurred copy is made or stored. The description is the image's `alt` (a video's
accessible name); without one the banner is decorative (`alt=""`), since the title follows it.

### 7. What the interface says about the copy

Deleting a gallery medium that is the banner's origin says, in the delete confirmation, that the banner keeps its own copy,
and offers to remove the banner in the same step. Nothing else ties the two.

## What the server sees

- That a Memory **has** a banner: one or two `veyq_node_attachment` rows in `BANNER` / `BANNER_POSTER`, their blob ids,
  sizes, the uploader's opaque `user:{id}` (no guest writes one) and the times.
- That it is **a video**: `BANNER_POSTER` exists only for one, and the size says so. Accepted: the same reader already sees
  every medium's size and count, and hiding it would cost a decoy blob per image banner.
- That the node's meta changed and who wrote it last (`meta_editor_*`) — not that the banner was the part that changed.
- When it plays: ADR 0047's per-chunk fetch pattern, as for any played video.
- When a gallery medium is chosen: one download of its original just before the banner's upload (decision 3).
- **Never:** which medium it came from, the focal point, fit or fill, the description, a pixel or a frame. Because it does
  not know, **a report of the gallery medium does not reach the banner** made from it; the banner is reported with its
  Memory (a `NODE` report, whose hold copies the Memory's attachment rows and pins their blobs).

## Consequences

- **Every existing attachment path covers the banner** — trash, purge, account and tenant erasure, quota, the hold's
  snapshot and pin, the reclaim, the reconcile, the backup's blob index — with no new table naming a storage key, so no
  claim list, backup index or e2e decoy changes. `cr-memory-banner` adds a test per path naming the new slots, because
  "core walks every attachment" is a statement about today's code.
- **The export and the copy carry the banner** ([ADR 0082](0082-a-node-and-everything-under-it-exports-as-a-plaintext-zip-and-imports-under-the-importer.md)):
  Memories' content gains a `banner` (its files, frame, focal point, description, and the origin as a media index), and a
  copy moves its blobs by frame as it moves an uploaded cover.
- **A new client dependency** (an MP4 muxer) enters `web/` under the no-deprecated-dependency rule (`CLAUDE.md`).
- **The editor's device pays**: a transcode of two minutes of video takes CPU and memory in a tab; the length bound is
  why.
- **The sticky row** is the Memory's tab row; the shell's top bar is sticky already (M: `web/src/styles/layout.css`), so
  the row's offset is the shell's (`--app-topbar-height`, or the compact header's), and 0 on a share page, which has no
  top bar (`cr-memory-banner` Q3).

## Rejected alternatives

- **A reference, as the cover's `mediaId`.** Names the medium to the operator by its access pattern on every open, renders
  only after the media list, and a video would still need bytes of its own; a small tier added to the referenced medium
  would name it too (only banner videos would get one).
- **A medium hidden from the gallery** (a flag, a hidden bucket). Carries the original, the live list, the export, the
  counters and the trash of a medium; every reader of `veyq_media` would have to learn to hide it — the cross-feature
  defect class `docs/ai/README.md` names (trash × sharing).
- **A small tier on every gallery video, made at upload.** Slows every video upload for a feature few use, needs a backfill
  and widens `MediaDerivativeKind` — ADR 0047's to revisit.
- **The cover's crop rectangle.** Wrong in one of the two aspects (Context 2).
- **A slot parameter on core's `setNodeCover`.** Puts a Memories feature into core's contract before a second application
  needs it.
- **Transcode on the server or in a worker agent.** Breaks operator-blindness; inbox #23's agent is out of V1 and holds no
  key.
- **The word "header".** Collides with the title row and the cover's "detail header" (Context 1).
