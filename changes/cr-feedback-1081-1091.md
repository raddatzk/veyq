---
id: cr-feedback-1081-1091
title: What a Beiträge drop leaves out is counted on the web; a declined naming at an upload says nothing on the desktops
status: in-progress
satisfies: []
adrs: ["0034", "0154"]
invariants:
  - "a declined naming is told from a refusal by the core's answer alone (AskedUpload), never by a platform reading the guest's session or the link's role"
---
# CR: what a Beiträge drop leaves out is counted, a declined naming says nothing (inbox #1081, #1091)

## Goal
- **#1081** — the web's drop onto a Memory's Beiträge/Contributions view says how many dropped files it left out for
  being no photo or video, as every app's drop does (`memory_not_media`, `MediaUploads.notTaken`); the sentence a twin in
  `src/mobile/words/web.json`.
- **#1091** — a guest who declines the naming at an upload is not told the upload failed: the core's asking uploads
  answer a typed `AskedUpload` (queued, declined, refused), the bridge answers a decline `NOT_CHOSEN` (as
  `Received.Replace` does), and the desktops stay quiet on it.

## Phases
1. Web: `MemoryBuckets.vue` `onDrop` counts what `isSupportedMediaFile` leaves out (`memory.buckets.dropNotMedia`), test.
2. Core: `sharing/AskedUpload`; `GuestLevel`, `GuestDropBox`, `GuestUploader`, `GalleryModel.uploadAsking` answer it;
   bridge `Received.Upload` and `Memory.Upload` answer `NOT_CHOSEN` on a decline; Linux and Windows quiet on it; Android
   and Apple read `.queued`. Tests: `GuestDoorsTest`, `ReceivedBridgeTest`.
3. Harvest: ADR 0034 point 7, ADR 0154 § As built; inbox rows; this CR deleted.

## Decision log
- The typed answer is a small value (`AskedUpload(queued, declined)`) over an enum: every caller needs the count, and
  a platform reads `declined` alone to stay quiet — an enum plus a count would be two values for one answer.
- `GalleryModel.uploadAsking` reads `Door.named() == false` as declined: it is reached only where `takesUploads` holds,
  so a door that writes, and a member's door is always named; changing `Door.named` to a typed answer would touch
  Expenses' `PotDoor` for no case that exists.
- `GuestNaming.request()` null is read through `AskedUpload.unnamed(access)`: declined on a link that writes (the guest
  declined, or its name was not taken — said by the naming itself), refused on a READ link.
- `Memory.Upload` declined answers `NOT_CHOSEN` too: before it answered `Queued(0, memory_not_media(n))`, saying every
  declined photo was "no photo or video" — the same defect in the gallery's call.
- `Received.Replace` keeps its Boolean `replaceAsking`: the bridge already answers both cases `NOT_CHOSEN` and every host
  is quiet on it there; a refused replace is offered only where `FileRights` lets it, so nothing failing goes unsaid.
- The web's gallery picker (`collectPickedFiles`) is left as it is: its `accept` offers photos and videos alone, so what
  it filters is an edge (a picker ignoring `accept`); #1081's case is the drop.
