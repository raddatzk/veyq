---
id: cr-contribution-drop-into-its-bucket
title: A drop on a Memory's Contributions view goes into the bucket it landed on — web and apps alike
status: in-progress
satisfies: ["uc.memory.buckets"]
adrs: ["0034", "0154"]
invariants:
  - "an upload is filed only within its uploader's own root; nothing moves across owners (ADR 0034 point 6)"
  - "the dropped files themselves are uploaded; a drop never opens the picker"
---

## Goal

Inbox #1065. The web's Beiträge drop (`MemoryBuckets.vue` `onDrop`) kept the photos and videos and emitted
`uploadBucket`, which `MemoryDetail.vue` answered with `triggerUpload()`: the picker opened, the dropped files and the
bucket were lost. The apps uploaded a drop on their Contributions view into the gallery (`DropTarget.Gallery`), not into
the bucket shown. Coordinator decision (2026-11-22): one behaviour everywhere — the dropped media go into the bucket the
drop landed on; what is no photo or video is left out as the web's bucket upload leaves it out.

## Phases

1. **Web** — the drop hands its files and bucket up (`dropFiles`); the detail uploads them and files each medium by a
   move once it is in. Tests: `MemoryBuckets.test.ts` (the drop's hand-up), `MemoryDetail.buckets.test.ts` (no picker,
   the move).
2. **Core and apps** — `Contributions.intake`/`upload`, `DropTarget.Bucket`, `MediaUploads.upload`'s filer; Android,
   Apple, Linux and Windows send a drop on the Contributions view to the bucket (`DropOn.Buckets` over the bridge).
   Tests: `ContributionsTest`, `MediaUploadsTest`, `BucketDropTest`.
3. **Harvest** — ADR 0034 point 7, ADR 0154's As built, `en.memory.buckets`, the inbox row; this CR deleted.

## Decision log

- **An upload into a bucket is an upload and a move.** `completeMedia` names no bucket (a medium lands in its uploader's
  primary, ADR 0034 point 4), so a sub-bucket is filled by `moveBucketMedia` once each medium is in — the move the
  organizer already makes. A target bucket on the completion would make it atomic but is a contract and backend change
  under the media writes' one lock order; not worth it for a filing a later move can repeat.
- **Only the caller's own root takes a drop (and the bucket's upload button).** The server moves within one owner's root
  only, and an upload's owner is the caller: a drop onto another uploader's bucket is refused and said
  (`memory_buckets_drop_not_own`), and the upload button shows only on the caller's own buckets — before, it uploaded into
  the caller's primary whichever bucket was shown.
- **The caller's primary (virtual or not) needs no move**; a sub-bucket does.
- **Offline, a sub-bucket takes no drop** (`OFFLINE_READ_ONLY`): the move is a write, online only (ADR 0140 decision 7);
  the primary still queues offline where the account queues (ADR 0153).
- **A failed move leaves the medium in the primary and is said**: the web's `memory.buckets.moveFailed` toast, the core's
  `Contributions.State.refused` as a curation's refusal.
- **Non-media**: the web keeps filtering silently (now by `isSupportedMediaFile`, the picker's rule, so Safari's typeless
  HEIC counts); the apps say `MEMORY_NOT_MEDIA` as their gallery drop does. The gap is #1081.
