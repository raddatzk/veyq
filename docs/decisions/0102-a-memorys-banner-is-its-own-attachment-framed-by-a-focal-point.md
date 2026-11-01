# ADR 0102 — A Memory's banner is its own attachment, framed by a focal point

- Status: Accepted
- Built: partly (not built: decision 4's small playback copy, in decision 2's later slot)
- Date: 2026-10-18
- Model: [Application: Memories](../apps/memories.md)

## Context

The product asks for a medium shown **above the menu bar** of a Memory, **independent of its cover**. Its
product shape is the owner's:

- a fixed frame — **16:9** on a wide screen, **1:1** on a phone, at most **60 %** of the viewport's height — that
  scrolls away while the menu bar sticks;
- the medium is **always shown whole**, never cropped: where it does not fill the frame — left and right for a narrower
  one, above and below for a wider one — a blurred, enlarged copy of it fills the rest;
- a video shows a poster with a play button and **never autoplays**;
- the source is **a file uploaded only for it** in its own dialog, which never appears in the gallery;
- whoever may edit the Memory sets it; a link's guest sees it as a member does (share view = member view).

Five things make this a decision rather than a field:

1. **The word "header" is taken.** The row with a node's title, cover tile and actions is core's node header, and
   [uc.memory.cover](../features/memory/use-cases/uc.memory.cover.md)
   says the cover shows in the Memory's "detail header". [ADR 0086](0086-one-word-per-meaning.md) gives a meaning one word.
2. **The cover's crop does not carry over.** A cover is one image and a 16:9 **crop**. This frame changes its aspect with the
   viewport, so a stored rectangle is wrong in one of them; so the banner makes no cut at all: the whole
   medium shows in either aspect.
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

A `banner` sub-object of the node's sealed meta, beside the date, the place, the cover and the participants: the
`node-meta` field, sealed under its own field key per write
([ADR 0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) § 9), its last editor
recorded on the node (§ 8), written with a compare-and-set on the revision it read
([ADR 0077](0077-a-write-carries-the-version-it-read.md)). It is Memories' plaintext; core stores it and never opens it
(ADR 0079, "The node's payload slot and content key are core's"). No new seal purpose, column or field group. Its shape
is [en.memory.node-meta](../features/memory/enablers/en.memory.node-meta.md) § The banner: it names the slots'
attachments (each a blob reference without owner or role, which are the node's and the slot's, ADR 0072), the frame,
the focal point and the digest of the file the banner was made from — so a gallery upload of a video banner's file
shares its stored object (decision 3), and a gallery medium with that digest is the banner's picture (decision 7). The
worst-case meta, a video banner beside everything else the meta holds, stays under the meta's one cap.

**`frame` and `focal` are kept, never drawn.** The banner always shows the whole medium
(decision 6), so no reader draws either. Both stay in the sealed shape - read, kept by every writer, carried by the
export - so neither the meta's shape, the contract, the export nor an older client changes; this editor writes
`frame: 'fit'` and `focal` the centre, which an older client draws as the whole medium too. A reader is best-effort, so
an older or newer client's banner never breaks a Memory.

**The later slot.** The small playback copy (decision 4) will fill a third slot, `BANNER_PLAYBACK`, and add a `playback`
ref beside `blob`, which keeps meaning the uploaded original: a reader that knows `playback` plays it, one that does not
plays `blob`, and nothing that exists moves.

### 3. Every banner's bytes are the Memory's own attachment — an equal gallery file shares its stored object

| Slot | Holds | Present |
|---|---|---|
| `BANNER` | an image: one JPEG, long edge 2048, quality 0.85 (core's cover derive) — **or** a video, as it is | always |
| `BANNER_POSTER` | a video's poster: its first frame as a JPEG | a video only |

Each is a fresh `K_blob` wrapped under the Memory's `K_node`, bound to blob, node and role
([ADR 0072](0072-a-seal-binds-its-object.md)), its `blob-meta` `{type}` under `K_blob` — a cover attachment's seal. Both
roles are Memories' member-only: a guest's upload start refuses them.

**The banner is set only by a file picked in its dialog.** There is no gallery source: the
lightbox's *Use as banner* went, because set casually from the gallery a banner overwrote another member's choice
unnoticed. A picked image is derived (the JPEG above), a picked video is taken as it is with the first frame the editor's
browser decodes as its poster.

**An equal gallery file shares its stored object** ([ADR 0013](0013-blob-storage-presigned-direct-to-store.md) § 5).
The editor reads the picked file's SHA-256 once; where a gallery medium of the Memory has an original of the same sealed
size and digest (its `blob-meta` `sha256`), the slot is a **new blob row on that medium's stored object** - its `K_blob`
wrapped again bound to the node and the slot, nothing uploaded - only where the stored bytes are what the slot holds:

| Slot | An upload into the slot equal to a gallery original shares | A gallery upload equal to the slot shares it |
|---|---|---|
| `BANNER`, an image | the medium's **PREVIEW** (the same spec of the same picture) | no: the slot holds a derived JPEG, not the file |
| `BANNER`, a video | the medium's **ORIGINAL** | **yes**: the new medium's ORIGINAL is a row on the `BANNER` object; its posters are derived as always |
| `BANNER_POSTER` | the video medium's **PREVIEW** (its first-frame poster); a medium without one: the frame decoded here, uploaded | no |

A matched medium without the tier is no match (the upload runs as before). A medium uploaded before its original carried
a digest is never matched.

- **One storage form, one lifecycle.** Whatever the bytes are, the banner is the node's attachment: trashed and restored
  with the Memory, purged with it, counted in the quota (a shared object once), held on a report of the Memory, swept by
  the reclaim, claimed by the reconcile through its `veyq_blob` rows — paths core already runs for every attachment. A
  shared object goes only with the last row naming it: deleting the gallery medium leaves the banner's bytes, and the
  reverse.
- **It renders before the gallery**: the meta names the attachments' refs, so the banner draws without the media list.
- **No fetch names the medium**: the banner reads its own attachment on every open, never the medium's row.
- **Cost:** an unshared banner stores its bytes once more (about 0.5 MB for an image; a whole video's size for a video
  until decision 4's small copy exists); a shared one stores nothing more, and its row names the medium's object
  (§ What the server sees).

### 4. A video banner plays its original; the small copy is the client worker queue's

No transcoder runs in the browser. A video banner is the uploaded original (decision 3) played as a
gallery video plays: the poster and a play button until the viewer clicks, then ADR 0047's Service-Worker range shim over
the `BANNER` attachment's chunks — the path Depot's file page and the lightbox already take — with its accepted seek
leak; without a Service Worker the video is decrypted whole first, as the lightbox's fallback does. No autoplay.

- **No length limit:** the banner is the Memory's lead-in, and a whole holiday's video may be long. Nothing
  bounds it but the quota, and nothing holds it whole: the upload and the digest read it by frame.
- **A browser that cannot decode a frame** of a picked video offers no video banner, with a message saying so; the
  original is the banner only where a poster could be drawn for it.
- **The small playback copy** (H.264, long edge 1280, about 2 Mbit/s) is
  [en.memory.playback-derivative](../features/memory/enablers/en.memory.playback-derivative.md), made by a client worker
  node of the queue of [ADR 0021](0021-operator-blind-background-tasks.md) (inbox #23) — the same enabler a small tier for
  gallery videos would use (inbox #493). It fills the later slot of decision 2.

### 5. The write is Memories' own operation, built from core's pieces

`setMemoryBanner` and `removeMemoryBanner`, in Memories' own contract — an application's behaviour lives in its own
directory (ADR 0079, "Contracts by owner"). As core's `setNodeCover`, one request hands the open upload(s) - or, per
slot in place of one, a shared row - **and** the
re-sealed `wrappedMeta`, and the server, **in one transaction**, attaches `BANNER` (and `BANNER_POSTER`, or detaches a
poster an image replaces) through `AttachmentStore` and compare-and-sets the meta — so the meta never names bytes that are
gone, and no bytes outlive the meta that names them. A shared row's source must be a blob a live row of the same node
holds, not blocked. `removeMemoryBanner` detaches both and writes the meta without
`banner`. There is no re-frame: with nothing to frame, a new banner takes a new file.

**Unlike the cover, a stale slot writes nothing.** Both slots are compared first, under the scope row's lock every writer
of the attachment table takes; a miss is a `409` with what both slots hold, before anything is written, and the
uploads stay open (a cover's single-slot miss commits and spends its upload; with two slots, a miss on the second after
the first was swapped would leave an image and a poster the meta does not name). A stale meta is `409 stale_revision`
and rolls everything back, as the cover's does.

Memories' operation rather than a slot parameter on core's cover operation: the banner is the Memories application's
feature, and core may not name it (ADR 0079). If a second application wants one, it is lifted
into core as the cover was (inbox #411); the attachment half already is core's.

**Who:** a member who may write the scope — exactly who may set the cover. No
link role gets a banner operation, as none gets one for the cover, the date or the place
([Memories' role matrix](../apps/memories.md#what-a-guest-may-do--the-role-matrix)); a guest sees the banner through the
share's gated attachment route. A `MEMORY` and a `CHAPTER` both carry one: they
render the same view.

### 6. The client draws it, and nothing else processes it

The banner is drawn by the Memory's own view above core's node header, the same for a member and a guest; rights only
remove the action. **The frame is the screen's, not the medium's** (owner): 16:9 where the Memory's
column is wide, 1:1 where it is narrow (720 px and below - a phone), never taller than 60 % of the viewport. The medium
is contained in it, **always whole, never cropped**; where it leaves the frame - at the sides of a narrower medium, above
and below a wider one - the same decrypted picture (a video: its poster) is drawn a second time behind it, enlarged and
blurred by the display (`aria-hidden`). While a video plays, the blur follows it (owner): each frame the
browser presents is drawn onto a canvas 32 px across, which the display enlarges and blurs - no second decode, no second
read of the video; paused it holds the last frame, and under `prefers-reduced-motion` it stays the poster. No blurred copy
is made or stored, so nothing beyond the slots exists to erase, hold or count. The editor's dialog shows the picked file as both frames show it (wide 16:9, phone 1:1) and has nothing
to set: no point, no fit / fill. The banner has no description: it is decorative, since the Memory's title follows it.

### 7. What the interface says when the banner's picture is deleted

Deleting a gallery medium that is the banner's picture - its original's digest is the banner's `sha256` - asks first: the
confirmation says the banner stays (its stored bytes go only with the last row naming them) and offers to remove the
banner in the same step. Nothing else ties the two.

## What the server sees

Published for readers outside the project in [What the server sees](../../site/src/content/docs/security/what-the-server-sees.md).

- That a Memory **has** a banner: one or two `veyq_node_attachment` rows in `BANNER` / `BANNER_POSTER`, their blob ids,
  sizes, the uploader's opaque `user:{id}` (no guest writes one) and the times.
- That it is **a video**: `BANNER_POSTER` exists only for one, and the size says so. Accepted: the same reader already sees
  every medium's size and count, and hiding it would cost a decoy blob per image banner.
- **Which gallery medium the banner shows, where it shares its stored object.** A shared `BANNER` (or
  `BANNER_POSTER`) row names its source blob in the write and carries the source's storage key, so the operator can tell
  which medium's ORIGINAL or PREVIEW the banner is - and, the other way, which gallery video is the banner. Even
  unshared, a video banner is the file as it is, so its size can equal a gallery original's, and an image banner is
  derived with the preview tier's spec, so its size can equal that preview's. **Accepted for image and video** (owner,
  inbox #505): the same reader already sees both sizes, and the owner preferred the saved storage. The same
  holds for a cover sharing a medium's PREVIEW ([ADR 0079](0079-veyq-is-a-platform-memories-is-its-first-application.md),
  "Core's attachments").
- That the node's meta changed and who wrote it last (`meta_editor_*`) — not that the banner was the part that changed.
- When it plays: ADR 0047's per-chunk fetch pattern, as for any played video.
- **Never:** the focal point, fit or fill, a pixel or a frame, and no fetch on opening the Memory names a medium. A
  content block on a gallery medium reaches a banner row on the same stored object (the server serves neither), and
  hangs on that object when the medium is erased - the banner stays refused until its own removal or a moderator's lift
  ([ADR 0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) § 6); the
  banner is reported with its Memory (a `NODE` report, whose hold copies the Memory's attachment rows and pins their
  blobs).

## Consequences

- **Every existing attachment path covers the banner** — trash, purge, account and tenant erasure, quota, the hold's
  snapshot and pin, the reclaim, the reconcile, the backup's blob index — with no new table naming a storage key, so no
  claim list, backup index or e2e decoy changes. "Core walks every attachment" is a statement about the code, so the
  banner's coverage names the slots on each path.
- **The export and the copy carry the banner** ([ADR 0082](0082-a-node-and-everything-under-it-exports-as-a-plaintext-zip-and-imports-under-the-importer.md)):
  Memories' content gains a `banner` (its files, frame and focal point), an import uploads the files into the new node's
  slots as they are, and a copy moves the blobs by frame. Neither keeps a shared object shared: each blob becomes its
  own. A banner whose bytes do not read is left out, never a failed export or import.
- **No new dependency**: nothing transcodes; the poster of a picked video is the gallery's existing first-frame derive.
- **The page scrolls the window**, the banner with it, and the Memory's tab row sticks below the shell's own sticky top
  bar, as the gallery's windowing (ADR 0105) expects.

## Rejected alternatives

- **A reference, as the cover's former `mediaId`.** Names the medium to the operator by its access pattern on every open,
  renders only after the media list, and a video would still need bytes of its own. (A shared stored object names it once,
  at the write - accepted, § What the server sees.)
- **A second reference to one blob row** in place of a shared object. Needs a wrap per reference on every arm, breaks
  `ux_attachment_blob` and one reference per blob, and double-counts the link quota.
- **The gallery as a source** (the lightbox's *Use as banner*, the first build). Removed by the owner: members
  overwrote each other's banner in passing.
- **A medium hidden from the gallery** (a flag, a hidden bucket). Carries the original, the live list, the export, the
  counters and the trash of a medium; every reader of `veyq_media` would have to learn to hide it — the cross-feature
  defect class `docs/ai/README.md` names (trash × sharing).
- **A small tier on every gallery video, made at upload.** Slows every video upload for a feature few use, needs a backfill
  and widens `MediaDerivativeKind` — ADR 0047's to revisit (inbox #493).
- **A transcode in the editor's browser** (WebCodecs and an MP4 muxer). Planned first; dropped by the owner in favour of
  the client worker queue (decision 4), which serves the gallery too.
- **A transcode on the server.** Breaks operator-blindness.
- **The cover's crop rectangle.** Wrong in one of the two aspects (Context 2).
- **A focal point with a fit / fill switch** (the first build, decision 2). Replaced by the owner on 2026-11-01 by the whole
  medium with a blurred surround (Context, decision 6). A fill cuts part of the medium away in at least one of the two
  aspects; the whole medium leaves nothing to set.
- **A slot parameter on core's `setNodeCover`.** Puts a Memories feature into core's contract before a second application
  needs it.
- **The word "header".** Collides with the title row and the cover's "detail header" (Context 1).
