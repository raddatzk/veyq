---
id: cr-app-offline-folder
title: A folder made offline entered before it is sent — an empty level taking what is made in it
status: in-progress
satisfies: []
adrs: ["0153"]
invariants:
  - "nothing new crosses the bridge but ids, names and core's words (SecretFreeSchemaTest)"
  - "a level of a folder made offline asks the server nothing for it until the folder is sent"
  - "a write into a folder made offline waits for its create (QueuedWrite.after) and is sent after it"
---

## Goal

Inbox #1095: a folder made offline shows in its level marked *Waiting to send*, but its row enters nothing — Home's level
and path are the tree's, which does not hold it. #1040 opened a Memory or chapter made offline as an empty page; do the
same for a folder: entering it shows an empty level holding only what is queued into it, and New and uploads in it queue
against the not-yet-created parent, sent in order after it.

## Plan (one phase)

- `QueuedItem.folder` (a folder made offline, not refused): the `FolderRef` its row enters through `ShellModel.enter`.
  Home's level of it is the tree's (empty) beside `queuedAt` of it (what waits in it); New and uploads already queue
  under any parent and wait for one made offline (`QueuedWrite.after`).
- `ShellModel` keeps the folders and Memories made offline (`madeUnsent`, from the outbox watch #1040 added): a relocated
  path (a rename, a trash) is read over the tree with them; a path folder neither listed nor waiting any more (discarded,
  refused) is left for the nearest level still there, the tree read anew first; the tray names such a folder as the
  place its writes go.
- Apps: a level's queued folder row enters it, on Android, Apple, Linux and Windows.

Done when: `QueuedOverlayTest`'s three new cases green and bitten; `:core:jvmTest :composition:jvmTest :bridge:jvmTest
:android:assembleDebug`, the Apple build, the Linux build and the Windows build green where reachable; the click passes
listed as owed in #1095's row.

## Decision log

- **A folder made offline is entered as a level of its own**, not refused: Home's shell already enters any `FolderRef`
  (`Shell.enter` appends it to the path) and its level is the tree's children of it — none — beside the queued items under
  it (`queuedAt`), so the level is exactly what waits there. No new read path, no server call.
- **The row carries the folder (`QueuedItem.folder`)** as #1040's row carries the page: core decides what a queued row
  does; a platform only enters what it is handed. A refused create enters nothing (the tray's *Discard* is its answer); a
  held one is entered.
- **Home leaves a folder whose create is gone without being sent** (discarded from the tray with what waited in it,
  refused at its send): a watch of the outbox cuts the path above it. The tree is read anew first (its cache forgotten):
  a create just sent leaves the outbox before the lists are read again, and Home must stay in a folder that became the
  server's (`a_folder_made_offline_is_sent_before_what_waits_in_it_and_home_stays_in_it` holds that).
- **A rename or a trash of a folder made offline on Home's path** follows its queued create: the path is read over the
  tree with the folders made offline (`treeWithUnsent`), so a rename shows the new name and a trash leaves it.
- **Online, a folder not sent yet offers no New and takes no upload** (held, or a reconnect's send not come yet): a
  create online would name a parent the server does not hold and be refused. Queuing online too was weighed and left —
  it would open a second, online queue path ADR 0153 decision 3 does not have, for a window of seconds or a hold the
  person resolves through its *Open*.
- **The tray names a folder or a Memory made offline as the place** what is made in it goes (`placeName` falls back to
  `madeUnsent`), so the uploads tray does not group them under nothing.
