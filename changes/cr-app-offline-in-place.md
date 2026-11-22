---
id: cr-app-offline-in-place
title: Writes made offline shown in place before they are sent — a capture time, a rename, a Memory or chapter made offline opening as its page
status: in-progress
satisfies: []
adrs: ["0153", "0140"]
invariants:
  - "nothing new crosses the bridge but ids, names and core's words (SecretFreeSchemaTest)"
  - "a write held for the person shows what the server holds, never the person's queued value (the held write's Open decides it)"
  - "a page of a node made offline asks the server nothing until the node is sent"
---

## Goal

Inbox #1040: ADR 0153 decision 5 says a queued write shows where it was made, at once. Three did not: (1) a capture time
set offline left the gallery in its kept order until the reconnect, (2) a rename made offline was not shown in its level,
(3) a Memory or chapter made offline opened nothing before it was sent (nor took media from its own page). Fix all three
in the core's overlays (`GalleryModel`, `ShellModel`, `QueuedShown`) with tests in `QueuedOverlayTest`, and draw them on
Android, Apple, Linux and Windows.

## Plan (one phase)

- `GalleryModel.QueuedHere` gains `times` (a capture time per medium) and `chapters` (the chapters made offline in the
  node, each with core's line); `GalleryModel.read` overlays the times and orders the grid anew (`MemoryMedia.order`);
  `State.chapterSaid` marks a chapter made offline.
- `ShellModel` keeps the waiting renames (a watch of the outbox) and builds its lists under them; an open page and the
  path follow at the rename.
- `QueuedItem.page` (a Memory or chapter made offline, not refused) opens through `ShellModel.open`; the gallery door
  answers such a node with an empty listing offline, never asking the server, and its scope's nodes carry the chapters
  made offline. The bridge carries `GalleryShown.chaptersUnsent`.
- Apps: a level's queued row opens its page; a chapter made offline shows core's line under its name.

Done when: `QueuedOverlayTest`'s three new cases green and bitten; `:core:jvmTest :apps:memories:jvmTest
:composition:jvmTest :bridge:jvmTest :android:assembleDebug compileKotlinJs compileTestKotlinJs`, the Linux build and the
Windows build green where reachable; the click passes listed as owed in #1040's row.

## Decision log

- **A node made offline opens as an empty page** (the row's open question): its page offline, empty but for the media
  queued into it, taking more media and chapters, rename and trash going to its queued create (`QueuedNodes` already
  folds both). Refusing it would leave a Memory made offline a dead row until the reconnect; an empty page costs no
  server read (the door answers it) and no new write path. A refused create opens nothing (the tray's *Discard* is its
  answer).
- **Only Memories and chapters open.** A folder made offline is not entered — Home's level of a folder not yet sent would
  need the shell's path over a node the tree does not hold; filed as #1095. A Depot file made offline opens nothing (its
  content is on this device only, sealed; *Save a copy* exists for a refused one).
- **A held write shows the server's value** (a held rename the current name, a held capture time the listed time): the
  held write's *Open* is where the person decides, and showing the queued value would hide the conflict it asks about.
- **The rename overlay is a watch of the outbox in `ShellModel`**, not a per-call patch: a *Discard* in the tray, a send
  and a hold each change the outbox, and each must change the name shown back.
- **The window between a reconnect and the send**: a page of a node made offline shows offline until its create is sent;
  a write made on it online in that window takes the online path and is refused (the node is not there yet). Seconds
  wide; left, said in the ADR.
- **A gallery open while its writes change in the tray** reads the overlay anew at its next read (a reload, a revision of
  the lists, a write on the page), not at each outbox change: as before for trashes and marks.
