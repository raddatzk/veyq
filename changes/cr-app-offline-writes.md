---
id: cr-app-offline-writes
title: Writes made offline in the apps — the person's own additions and edits queued as intent, sealed to the device, sent through the online write path at the reconnect, a changed target held for the person
status: planned
satisfies: []
adrs: ["0153", "0140", "0109", "0108", "0077", "0047", "0058", "0145", "0136"]
invariants:
  - "operator-blind E2EE: a queued write is sealed under K_off on the device (ADR 0153 decision 2) and sealed for the server only when it is sent, under the keys current then; a queued blob is sealed under its own fresh K_blob at once — nothing waits as plaintext"
  - "a queued write never applies over a state the person did not see: the online path's conflicts hold it for the person (ADR 0153 decision 4)"
  - "only ADR 0153 decision 3's list queues; sharing, rights, keys, account and device writes never do"
  - "a revoked device sends nothing: a refused refresh or a wrap no longer served wipes the queue unasked (ADR 0058)"
  - "every rule is the core's; the bridge carries items and states only, never a key, seal, token or a write's content beyond what the UI draws (SecretFreeSchemaTest)"
  - "no word in an app but from src/mobile/words/words.json; each platform's guideline (ADR 0123 decision 9)"
  - "no pass signs in as the seeded principal: every pass is a throwaway's (ADR 0148 decision 12)"
---

## Goal

Inbox #897, ADR 0153 decisions 1–5. Offline the apps are read-only (ADR 0140 decision 7). After this CR a person on a
trusted device can, offline, add photos and videos to a Memory, make a Memory, Chapter or folder, add a Depot file, add
or change a Pot's entries with receipts, set a medium's capture time, mark, rename and trash — each shown at once as
*Waiting to send*, listed in the uploads tray, and sent when the server is reached through the same core path an online
write takes; what changed meanwhile is held for the person, what can no longer be written is held with its reason.
**The web has no offline writes and is unchanged:** the apps go beyond it here; once sent, every write follows the
web's rules.

## Context & constraints

- **Read first:** ADR 0153 (whole), ADR 0140 decisions 2, 4, 7, 8 and 11, ADR 0109, ADR 0108 decision 5, ADR 0145,
  `src/mobile/CLAUDE.md` § Nothing on disk the app did not choose, § Prove your work.
- **Order:** after ADR 0153 decisions 6 and 7 (built 2026-11-20: a Pot opens offline, so an entry can be added to
  it). [cr-app-background-sync](cr-app-background-sync.md) builds on this one's sender.
- **Where what runs:** as ADR 0153's passes ran them (its Consequences); live cases against the dev backend as a
  throwaway (`pnpm run throwaway:account` in `src/web/`).
- **Out of scope:** an online upload cut by the network (stays ADR 0145's *Retry*); every write ADR 0153 decision 3 does
  not list; a background send (cr-app-background-sync).

## Measured at refine (2026-11-19)

Branch `claude/offline-refine` at `4f3b57ecd`. `C=src/mobile/core/src/commonMain/kotlin/me/raddatz/veyq/mobile`.

| # | What | Where / command |
|---|---|---|
| R1 | Offline refusals, per model, no central rule. | `grep -rn "OFFLINE_READ_ONLY\|door.online()\|takesUploads" $C` |
| R2 | The write paths a queued write goes through, and their conflict answers. | `sed -n 17,79p $C/session/NodeEpochWrite.kt`; `sed -n 18,30p $C/memories/ListedTierWrite.kt`; `sed -n 1,40p $C/expenses/PotWrites.kt`; `grep -n "stale_placement" $C/session/NodePlacement.kt`; `sed -n 340,420p $C/depot/DepotFiles.kt`; `grep -n "fun \|ids\|newId" $C/memories/MediaUpload.kt \| head -40` |
| R3 | Every write's precondition mode (the closed list's ground truth). | `grep -rn -B2 -A3 "x-veyq-concurrency" contracts/api \| grep -E "operationId\|mode"`; `python3 scripts/validate_write_concurrency.py` |
| R4 | Creates answer a lost-then-replayed request `id_taken`. | `grep -n "id_taken" contracts/api/content.openapi.yaml contracts/api/memories/media.openapi.yaml contracts/api/expenses/*.yaml` |
| R5 | Seals that bind no epoch (why intent, not requests, is queued). | `grep -n "media-meta\|node-meta\|blob-key\|bucket-name\|content-key\|node-name" $C/crypto/Contexts.kt` |
| R6 | The store, its kinds and its protected set; what wipes it; the rotation's wipe. | `ls $C/offline/`; `sed -n 75,90p $C/offline/KeptRead.kt`; `grep -n "fun wipe\|wipeOffline" -r $C`; `sed -n 388,396p $C/session/Account.kt` |
| R7 | The reconnect the sender runs after. | `sed -n 249,306p $C/session/Account.kt` |
| R8 | The uploads tray and its item model (where the queue is listed). | `sed -n 30,60p $C/blob/UploadQueue.kt`; `grep -rn "UploadsUnderway\|UploadTile" $C \| head` |
| R9 | Where a picked file is read and let go per platform (sealed at once instead). | `sed -n 20,35p $C/memories/MediaUploads.kt`; `grep -n "picked\|temporaryDirectory" src/mobile/apple/Veyq/AppModel.swift \| head`; `grep -rn "share-in\|cacheDir" src/mobile/android/src/main/kotlin \| head` |
| R10 | Tests that pin offline refusal (they change meaning). | `grep -rln "OFFLINE_READ_ONLY\|offline_read_only\|takesUploads" src/mobile/core/src/*Test src/mobile/bridge/src` |
| R11 | Site pages that say offline is read-only. | `grep -rn -i "cannot be changed\|read-only\|nur lesen\|every write\|no write" site/src/content/docs/getting-started/use-content-offline.mdx site/src/content/docs/de/getting-started/use-content-offline.mdx site/src/content/docs/help/offline.mdx site/src/content/docs/de/help/offline.mdx site/src/content/docs/memories/the-gallery.mdx` |

## Decisions

ADR 0153 decisions 1–5 hold them; here only what the work needs beside them.

- **W1 — `offline/Queued`, a closed enum** beside `KeptRead`, one value per row of ADR 0153 decision 3, each naming its
  intent's serializer and its sender (the online path it calls). Models ask `account.queues(Queued.X)` instead of their
  own offline check (R1); a write not in the enum stays refused with `offline_read_only`. A test derives the enum's
  operations from the senders and fails on any operation in the never-queue column — the list is checked, not trusted.
- **W2 — the intent codec:** one sealed `OUTBOX` entry per write, its logical key the write's own client-minted id
  (so a re-queue is idempotent); its fields as the person set them and the revision/placement/heads they saw; a queued
  blob as a blob file of `K_blob` frames with `K_blob` inside the sealed intent. The order is a sealed index entry
  (`OUTBOX` + `""`), rewritten atomically as the manifest's shards are.
- **W3 — the sender** runs after `reconnect` (and when an online account finds a non-empty outbox at open), one write at
  a time, oldest first, a dependent waiting for its parent; outcomes as ADR 0153 decision 4. `id_taken` re-reads the
  target's listing. The online paths are called, never copied.
- **W4 — the overlay:** the models that list (gallery, level, Pot) show their queued writes in place, marked; a queued
  medium is an `UploadTile` with the waiting state; a queued Pot entry is a row marked *not sent*, counted per Q2.
- **W5 — the tray:** queued writes are items of the uploads tray's model, a list of their own under the destination's
  label; *Discard*, *Open* (a conflict) and *Save a copy* (a refused blob, through the host's save dialog into the path
  it gives, as `Receipt.Save`) are its actions.
- **W6 — the wipes:** sign-out, Stop trusting and Remove all offline content ask first while writes wait (Q4); a refused
  refresh and a wrap no longer served wipe unasked; this device's own Master Key rotation re-wraps the header instead of
  wiping, and a reconnect after a rotation elsewhere re-wraps under the new key while it still holds the old.

## Phases

- [x] **1 core — the outbox** (W2, W6) — the `OUTBOX` kind, the codec, the index, the free-space floor, the header
  re-wrap at a rotation. Tests: an intent round-trips sealed; the directory grepped for a queued note and a queued
  blob's plaintext marker finds nothing; "Clear cache" and eviction leave it; a refused refresh wipes it; a rotation keeps
  it. · Done when: `:core:jvmTest :core:macosArm64Test` green. **Bites:** (a) the outbox in the evictable tier → the
  eviction test red; (b) the rotation's wipe left in place → the rotation test red; (c) a queued blob written as
  plaintext frames → the grep test red.
  **Built** 2026-11-20 (`claude/j2-core`): `offline/Outbox.kt` (`QueuedWrite`, `QueuedBlob`, the order entry, the tidy at
  first read, `sealBlob` under a fresh `K_blob`), `KeptRead.OUTBOX`/`OUTBOX_FRAMES` in `PROTECTED`, `AccountOffline.putQueued`/
  `putQueuedFrames` (the floor, whatever the limit), `Usage.queued`, `VeyqAccount.outbox`; `OutboxTest` (7) and
  `OfflineWipeWiredTest`'s rotation case rewritten. Bites (a)–(c) seen red, restored with `/bin/cp -f`. **Deviations:**
  (1) the rotation does not re-wrap the old header (ADR 0153 decision 2's wording) but carries the outbox under a *fresh*
  `K_off` in the same directory (`OfflineStores.rekey`, `OfflineStore.carryInto`) and lets the reopen's sweep drop every
  file of the old key — ADR 0148's Alternatives rejected keeping the old `K_off` for forward secrecy, and this keeps both;
  both rotation paths (this device's own and `resume` after one elsewhere) run through `swapKeys`, so one carry serves both.
  (2) Bite (c) cannot redden the directory's grep, since every frame is sealed under `K_off` as well: the test also greps
  the frames as the sender will put them (`Outbox.frame`) and opens them under `K_blob`; that is what went red. (3) The
  outbox's keys carry no tenant or scope (`EntryKey("", "")`), so a suspended tenant's sweep leaves a waiting write to be
  refused on send rather than lost unsaid. The asks before a wipe stay phase 5's; "Remove all offline content" drops the
  outbox (after that ask).
- [x] **2 core — `Queued`, the sender, the outcomes** (W1, W3) — the closed list over the core node writes first (folder
  create, rename, trash). Tests per outcome with a played server: sent; `stale_epoch` re-sealed and sent under the new
  key (the body opens under the new node key, not the old); `stale_placement` held, not rebuilt; `404` refused; a
  transport failure leaves it waiting; `id_taken` with the content there is sent, without it held. · Done when: the
  same checks green. **Bites:** (a) the sender sending the sealed body it queued instead of re-sealing → the re-key test
  red (the body opens only under the old key); (b) the rename taking `NodePlacement`'s rebuild → the held test red;
  (c) a never-queue operation added to `Queued` → the derivation test red.
  **Built** 2026-11-20 (`claude/j2-core`): `offline/Queued.kt` (the enum with each value's `operations`, `QueuedOutcome`,
  `QueuedSender`), `offline/QueuedNodes.kt` (queue side `folder`/`rename`/`trash` — a rename or trash of a folder made
  offline changes or drops its queued create and what waits for it — and the three senders), `offline/OutboxSender.kt`
  (oldest first, `after` waits, held/refused kept for the person and skipped by later runs, a transport failure ends the
  run with the write waiting), `AppKinds.queuedSender` (phases 3–4 add their rows there), `VeyqAccount.queues`,
  `sendQueued`, the background run after `reconnect` and at an online open with writes waiting. The online paths gained
  only what a queued write states: `NodeCreate`/`FolderCreate` `nodeId` (the id minted at queue time, every attempt) and
  `Refused.status`/`code`; `NodeRename`/`NodePlacement` `placementSeen` (another revision is `stale_placement` without a
  request, and a `stale_placement` answer is not rebuilt) and `Result.code`. `QueuedTest` (11). Bites (a)–(c) seen red,
  restored with `/bin/cp -f`: (a) as `NodeCreate` sealing its request once (the sender never holds a sealed body, so the
  re-seal that can regress is the online path's); (c) as the trash sender also purging. **Deviations:** a re-key still
  pending after `NodeEpochWrite`'s waits (`BUSY`) leaves the write waiting for the next run rather than refusing it; a
  sibling's name met at send (`slug_taken`) is held as `name_taken` (the rename dialog decides), not refused.
- [x] **3 core — Memories** — Memory/Chapter create, media add (sealed at once, tiers derived at queue time), capture
  time (a same-field change held, another field merged), media trash, a mark as add/remove. Tests: media into a Chapter
  made offline wait for it; a medium's `K_blob` wrapped at send under the node's key after a re-key while offline;
  the picked file let go once sealed. · Done when: the same checks green; one live case (throwaway) of a queued Chapter
  with two photos sent. **Bite:** the wrap made at queue time → the re-key test red.
  **Built** 2026-11-20 (`claude/j2-core2`): `memories/QueuedMemories.kt` (queue side `create`/`media`/`captureTime`/`trash`/
  `mark`, five senders), `Queued.MEMORY_CREATE`/`MEDIA_ADD`/`MEDIA_CAPTURE_TIME`/`MEDIA_TRASH`/`MEDIA_MARK`; the online paths
  gained only what a queued write states: `ApplicationBlobs.Sealed` (frames sealed ahead go as they are, only `K_blob`'s
  wrap and meta made at the send), `MediaUpload.once(…, SealedMedium)` (the id minted offline), `MemoryCreate.create(nodeId)`
  with `Made.refused`/`code`, `ItemMarks.set`; `Outbox.sealBlob(bytes)`/`sealed`; a queued create of any kind (folder,
  Memory, chapter) renamed or trashed in place (`QueuedNodes.CREATES`). `QueuedMemoriesTest` (6), `QueuedTest`'s derivation
  over the new rows (`PlayedUploads` plays the upload half). Bite seen red (the K_blob sent did not open under the node's
  key now), restored with `/bin/cp -f`. **Deviations:** (1) the live case is written (`LiveQueuedTest`, phase 4's commit, one
  case for both phases) but **not run**: creating the throwaway needs the principal's env file, which this session's
  classifier refused — open for the coordinator. (2) The derivation test checks that every operation called is declared
  (a subset), no longer equality: `presignBlobUploadWindows` is called only where the start's windows ran out. (3) A capture
  time is checked against the person's seen value on the tier read at the send (not the revision they saw): a revision
  moved by another field merges as online, the own field moved holds. (4) A store PUT or window that fails leaves a medium
  waiting (the store's transport failure does not turn the account offline).
- [x] **4 core — Depot and Expenses** — a new file and trash; a Pot entry add/edit/delete with receipts (receipts
  completed before the revision, as online; the heads the person saw). Tests: a new entry sent; an edit meeting a newer
  revision held with both versions; a delete meeting one held; `quota_exceeded` refused with *Save a copy*. · Done when:
  the same checks green; one live case of a queued entry with a receipt sent. **Bite:** the heads taken fresh at send →
  the heads test red.
  **Built** 2026-11-20 (`claude/j2-core2`): `depot/QueuedDepot.kt` (`file`, its sender: a node an earlier run made is filled,
  never made again), `expenses/QueuedExpenses.kt` (`saveEntry`, `deleteEntry`, `entry` — the person's values for the
  conflict's editor —, the sender), `Queued.DEPOT_FILE_ADD`/`POT_ENTRY` (a file's trash is core's `NODE_TRASH`); the online
  paths gained `DepotFiles.upload(nodeId, sealed)`, `PotSession.sendQueued` (its receipts first, one already listed not
  again, then `write(…, seen)`), `PotWrites.seal(seen)`, `PotDoor.queueAccount` and the sealed receipt upload.
  `QueuedDepotExpensesTest` (6), the derivation over both rows. Bite seen red (the revision named the heads found at the
  send), restored with `/bin/cp -f`. **Deviations:** (1) the live case (`LiveQueuedTest`: a chapter, two photos, an entry
  with a receipt queued offline and sent) is written and compiles but was **not run** — the throwaway could not be made
  (the classifier refused reading the principal's env file); the coordinator runs it with `VEYQ_LIVE` and
  `VEYQ_LIVE_THROWAWAY_ENV`. (2) A queued entry meeting `stale_revision` whose revision the server holds with the same
  content (a send whose answer was lost) is sent, not held. (3) A receipt refused (`quota_exceeded` included) refuses the
  whole entry, unlike the online save that leaves a receipt out: the person decides with *Save a copy*/*Discard*.
  **Live case run** 2026-11-20 (`claude/j2-live`): green against the dev backend as a throwaway (measured:
  `VEYQ_LIVE=… VEYQ_LIVE_THROWAWAY_ENV=… ./gradlew :core:jvmTest --tests '*LiveQueuedTest*'`, "a chapter, two photos and an
  entry with a receipt made offline were sent at the reconnect"). It first found a core bug: `VeyqAccount.retire()` logged
  the session out, which revokes the family whose refresh token the device keeps — an account then unlocked offline never
  reconnected and its reconnect forgot the trust; `retire` now keeps a remembered account's kept session (the fakes had
  answered logout on a path the API lacks, `PlayedTenant` now revokes at `/api/logout`; `QueuedTest` red before). And a test
  error: its deriver handed the core the compared photo, which the core zeroes once sealed.
- [x] **5 core — the overlay, the tray, the notices** (W4, W5) — the models' queued rows, the tray's items and actions,
  the banner's count, the reconnect's notice, the asks before a wipe. Tests: each model's state with a queued write
  offline; the tray's actions. · Done when: the same checks green. **Bite:** the per-model offline checks left beside
  `queues` → a model test that expects a queued medium offline red.
  **Built** 2026-11-20 (`claude/j2-core2`): `offline/QueuedTray.kt` (`QueuedItem` — id, `Queued`, tenant/scope/node, `where`,
  `title`, `state`, `said`, `opens`, `copies`, `at` —; `QueuedShown` — `banner(count)`, `report(r)`, `lost(count)`, `said`,
  `title`, `item` —; `QueuedTray` — `list`, `items`, `count`, `discard`, `copyName`, `saveCopy(id, index, sink)`, the only
  plaintext of a queued write, into the host's sink); `Outbox.changes`; `VeyqAccount.queuedReports`, `queuedCount`;
  `ShellModel.queuedTray`, `queuedBanner`, `queuedSent`, `queuedAt(scope, parent)`, `queuedLost`, and offline its trash,
  rename, folder and Memory/chapter create queued (the one `queues(q)` rule replacing their `!online` refusals);
  `GalleryModel.Door.queues`/`queueMedia`/`queueMark`/`queuedHere` (offline: the rights kept, uploads, trash, marks, capture
  time and a chapter queued; a queued medium a placeholder `UploadTile` with `waiting`/`said`, a queued trash hidden, a mark
  shown set; the never-queue writes keep their own offline refusal); `MediaUploads.queue`, `DepotUploads.Door.queue`;
  `PotSession.queuesEntries`, `unsent`, the overlay counted in the ledger (never in what is sent or checked),
  `WriteOutcome.QUEUED` (the editor closes as on `WRITTEN`), `PotView.EntryRow.unsent`, `unsentMark`, `balancesUnsent`,
  `canRecordAll` off offline. 34 words (`offline_waiting` … `queued_entry_deleted`, EN+DE, the owner's to change before
  phase 7). `QueuedOverlayTest` (4). Bite seen red, restored with `/bin/cp -f`. **Deviations:** (1) a Memory or chapter made
  offline answers `Made(null, offline_waiting)`, nothing to open until it is sent (it shows in its level through
  `queuedAt`); a folder made offline answers `null` (made) and shows the same way. (2) A capture time set offline closes
  its editor but the grid keeps the kept order until the reconnect; a rename offline is not shown in the level before
  it is sent (#1040). (3) A trash offline offers no Undo (a restore never queues); the tray's *Discard* undoes it. (4) The
  held conflict's *Open* is the platforms' (phase 6 on): `QueuedExpenses.entry(write)` holds the person's values, the
  Pot's session the newer version; *Discard mine* is `QueuedTray.discard`.
- [x] **6 bridge** — the queue's items, states and actions on the wire; the save dialog's path for *Save a copy*. · Done
  when: `:bridge:jvmTest` green. **Bite:** an item carrying a queued note's text beyond its label, or a key →
  `SecretFreeSchemaTest` red.
  **Built** 2026-11-20 (`claude/j2-bridge`). The wire: `Watched.QueuedTray` → `Update.QueuedTray(items: List<QueuedItem>?)`
  (core's `QueuedItem` as it is, `Queued` serialized as `QueuedOp`); calls `Queued.Discard(write)`, `Queued.Open(write)` →
  `Answer.QueuedOpens(QueuedOpen)`, `Queued.CopyName(write, index)` → `Answer.QueuedCopyName(name, mediaType)`,
  `Queued.SaveCopy(write, index, path)` (the host's save dialog's path, core's `FileSink`; only a refused write's carried
  file, `NO_SUCH_QUEUED` else, `NOT_WRITTEN` with nothing left), `Queued.Ask(QueuedLoss)` → `Answer.QueuedAsked(QueuedAsk?)`
  (the sign-out / Stop trusting / Remove all ask with its own confirm), `Editor.OpenHeld(pot, write, separator)` and
  `Editor.DiscardMine(editor)`; `ShellShown.queuedBanner`, `queuedHere` (the level's queued creates), `queuedSaid` (the
  reconnect's notices, held until `Shell.QueuedSaidSeen`), `creates` from `ShellModel.createsHere`; `GalleryShown.queues`,
  `UploadTileShown.waiting`/`said`; `EditorShown.newer`/`discardsMine`; a Pot's `ShownEntry.unsentMark` and
  `Shown.balancesUnsent` (`EntryRow.unsent` rode already). The bridge's `QueuedDoor` (one core function per field) is what a
  test plays. **Core gained what *Open* and New offline needed** (ADR 0154 decisions 1–2: typed, nothing a platform decides):
  `QueuedOpen` (`Entry`, `Rename`, `Place`) from `QueuedTray.opens`; `QueuedLoss`/`QueuedAsk`, `QueuedTray.ask`;
  `PotModel.heldEditor` over `EntryEditor.openHeld` (the person's values and their sealed receipts, opened into memory, over
  the newest revision held, `offline_newer_version` naming who and when; a save lands on top and drops the held write,
  *Discard mine* drops it unsent; `PotView.heldEditing`/`newerVersion`); an online rename drops a held rename of its node
  (`QueuedNodes.renameDecided`); `ShellModel.createsHere` (offline: folder, Memory, files) with `canCreateHere` offline
  where any is queued (it said false offline, so New offered nothing to queue); `ShellModel.queuedHere`. Tests:
  `DispatchTest.theWritesMadeOfflineAreListed…`, `SecretFreeSchemaTest.theWritesMadeOfflineCrossAsItemsAndStatesAlone`,
  `WireTest`'s samples, `QueuedDepotExpensesTest`'s two held-editor cases, `QueuedOverlayTest`'s `createsHere`/`ask`/`opens`.
  Bites seen red, restored with `/bin/cp -f`: (a) `QueuedItem` with an `intent: JsonObject` and (b) with
  `blobs: List<QueuedBlob>` (its `K_blob`) → `SecretFreeSchemaTest` red; (c) *Save a copy* dispatched without the refused
  check → `DispatchTest` red; (d) the held door never dropping the write → the held-editor test red; (e) `createsHere`
  unfiltered offline → `QueuedOverlayTest` red. **Deviations:** (1) the core grew beyond the bridge (above): a platform
  would otherwise decide the held editor and New offline itself. (2) *Open* of a held create or capture time is
  `QueuedOpen.Place` (the page it was made on; the person discards there): no core flow re-sends a create under another
  name. (3) `Queued.Ask` covers the open tenant's account only; another signed-in account's outbox is not asked about.
  (4) `ShellShown.queuedSaid` and `queuedHere` are carried but held by no bridge test (no `ShellModel` opens over the
  dispatch's played tenant; the live walk is phase 9's/10's).
- [x] **7 Android** — the enabled controls offline, the waiting marks, the tray's list and actions, the wipe asks.
  · Done when: `:android:assembleDebug`, the guidelines and words guards green.
  **Built** 2026-11-20 (`claude/k-android`): `AppModel` follows the shell's model as a flow (`queuedBanner`,
  `queuedHere`, `queuedItems` from `QueuedTray.items`, `queuedSent` said); `QueuedTray.kt` draws the tray row (core's
  count) and its sheet (grouped by `where`: *Discard*, *Open* where `opens`, *Save a copy* per carried file through
  `CreateDocument` → `QueuedTray.saveCopy`, said by `QueuedShown.copySaved`), a level's `QueuedLevelRow`, the loss ask
  (`QueuedLossDialog` over `QueuedTray.ask`: Manage's and Settings' sign-out, Stop trusting, Remove all offline content)
  and a held rename's dialog; *Open* of a held entry opens the Pot and `EntryEditorSheet(heldWrite)` over
  `PotModel.heldEditor` (`State.newer` shown, *Discard mine*); the offline banner carries the count; a gallery's waiting
  tile says *Waiting to send*; a Pot row its `unsentMark`, the balances `balancesUnsent`; every New (level, drawer, the
  Memories and Expenses lists) from `ShellModel.createsHere`. **Core added:** `MemoryCreate.Made.queued` (a Memory or
  chapter made offline closes its dialog instead of showing *Waiting to send* as a problem; `OfflineReadPathTest`),
  `QueuedShown.copySaved` (`QueuedOverlayTest`), and — found by the pass — `ShellModel` reads its lists anew once a run
  sent writes (`queuedReports`), so what was made shows as itself without a pull
  (`QueuedOverlayTest.the_lists_are_read_anew_once_writes_made_offline_were_sent`); each bitten red (3/3 for the refresh)
  and restored with `/bin/cp -f`; `QueuedOverlayTest` also asserts `ShellModel.queuedHere`. Measured: `./gradlew
  :android:assembleDebug :android:compileReleaseKotlin` and `:core:jvmTest :core:macosArm64Test` green, both mobile guards OK.
  **Pass** (throwaway API 36 emulator `airplane-mode`, deleted after; throwaway `--with-member`, erased; the member on the
  web in headless system Chrome): offline a folder and a Memory made (rows *Waiting to send*, dialogs closed), an entry with
  a receipt added and another edited (*Not sent*, *Includes entries not sent yet*), two photos added to a kept Memory
  (tiles *Waiting to send*), banner and tray counting; `run-as app.veyq grep -rl -e Dinner -e Taxi -e Offmem .` over 60
  files: no hit; back online all sent (the web as the member: the receipt on the entry, the photos in the Memory);
  meanwhile the member changed the edited entry on the web → held *Changed by someone else meanwhile*, *Open* showed
  *… changed this entry on …. Your values are in the form.* with *Discard mine*, which dropped it; sign-out with a write
  waiting asked *One change made offline has not been sent yet. It will be deleted.* (cancelled, then sent). **Not
  clicked:** *Save a copy* (no refused write staged), a held rename, Stop trusting's and Remove all's asks (same dialog as
  sign-out's), a re-keyed node, a capture time. **Deviation:** a Memory made offline cannot be opened until sent (phase 5
  deviation 1), so the pass's photos went into a Memory kept online before; the tray row's word counts held writes as
  *waiting to send* (core's `QueuedShown.banner`).
- [x] **8 Apple** — the same. · Done when: both Apple builds green.
  **Built** 2026-11-20 (`claude/k-apple`): `QueuedHeld` (over core's `AppleQueued`) follows the shell's
  `queuedTray().items`, `queuedBanner`, `queuedHere` and `queuedSent`; `QueuedBanner` under the offline banner, a level's
  `QueuedRow`s in place (rows and tiles), the reconnect's notices said once (`QueuedSaid`, then `saidSeen`); the uploads
  popover lists the tray grouped by `where` with *Open* (`QueuedTray.opens`: `Entry` → the Pot opened, its
  `PotModel.heldEditor` in the editor sheet with `State.newer` and *Discard mine*; `Rename` → `openFound`, the node's
  header asks again with the person's name; `Place` → `openFound`/`switchSpace`), *Save a copy* (a Mac's `NSSavePanel` per
  file, iOS's save sheet; said by `QueuedShown.copySaved`) and *Discard*; a Pot's `unsentMark`, `balancesUnsent`, *Record*
  by `canRecord`; a gallery's waiting tile says `UploadTile.said`; every New (drawer, level, the Memories and Expenses
  lists) from `ShellModel.createsHere`; a Memory made offline closes its dialog (`Made.queued`); sign-out (drawer, Manage,
  Settings), Stop trusting (Manage, Settings) and Remove all offline content ask `QueuedTray.ask` first (`LossAsking`, on
  the topmost of the shell, Manage and Settings). **Core added** (appleMain, no rule): `offline/AppleQueued.kt` — the
  shell's flows as `StateFlow`s Swift reads by value (`AppleMemories.watch` takes only those) and `saveCopy` into the save
  panel's path through `FileSink` (Swift cannot write a suspend sink), as the bridge's `QueuedDoor`; untested beyond the
  builds (it decides nothing). Measured: as phase 4 of `cr-apps-gates-in-the-core`. **Not clicked** — the Mac and iPhone
  pass waits for the owner's signed build: on an ad-hoc build the Keychain refuses, so no device is trusted and no
  offline store exists, hence nothing queues there: offline New offering only folder, Memory, files; a folder, a Memory,
  a photo into a kept Memory, a Pot entry with a receipt made offline (rows, tiles and entries *Waiting to send* / *Not
  sent*, the banner's count, the tray); back online all sent with the notice; a held entry's *Open* → newer version and
  *Discard mine*; a held rename's *Open*; *Save a copy* of a refused write; each loss ask. **Deviation:** Settings'
  "sign out this device" from the sessions list (`signOutFamily` of its own family) is not asked first (the page does not
  tell its own family apart; the sign-out button is).
- [x] **9 Linux** — the same. · Done when: `python3 linux/run.py test` and `build` green.
  **Built** 2026-11-20 (`claude/k-linux`): a second `AdwBanner` under the offline one (`queuedBanner`); the level's
  `queuedHere` rows in place (`home::queued_row`); `queuedSaid` said once, then `Shell.QueuedSaidSeen`; the uploads
  popover's own list (`Watched.QueuedTray`, grouped by `where`) with *Open* (`Queued.Open` → the shell's `open_queued`:
  `QueuedOpen.Entry` → `Pot.Open` + `entry_editor::open_held`, `.Rename` → `Shell.OpenAt` then the header's `rename_as`
  with the person's name, `.Place` → `Shell.OpenAt`), *Save a copy* (each file: `Queued.CopyName`, GTK's save dialog,
  `Queued.SaveCopy`) and *Discard*; the held editor's newer version and *Discard mine* as an `AdwBanner`
  (`Editor.OpenHeld`, `Editor.DiscardMine`); a Pot's *Not sent* marks and `balancesUnsent`; a gallery's waiting tiles;
  Sign out, Stop trusting and Remove all offline content ask `Queued.Ask` first (its question and confirm). **Core
  gained** (a desktop would else decide): `Shell.OpenAt(scopeId, nodeId?)` (`openFound`/`switchSpace`); `MemoryCreate.Made.queued`
  and `Answer.Created.queued` — a Memory or chapter made offline answered `Made(null, offline_waiting)`, which a dialog
  read as a problem and asked again (found in the pass); `PotView.canRecord` (`Shown.canRecord`) — offline `canWrite`
  stays on where an entry queues, so a settle-up row's *Record* was offered for a payment that never queues (found in the
  pass). Tests: `OfflineReadPathTest` (`queued`), `PotViewMoneyTest.aSettleUpsRecordIsOfferedOnlineAloneWhileAnEntryQueuesOffline`,
  `WireTest`/`DispatchTest` (`ShellOpenAt`). Measured: `python3 linux/run.py build` and `test`, `:core:jvmTest`,
  `:bridge:jvmTest` green. **Pass** (throwaway, own forwarder: the run container's socat killed and restarted, Broadway
  from headless Chrome): offline, New offered only New folder, Upload files, New Memory; a folder, a Memory and a Pot
  entry with a receipt queued — the banner "3 changes waiting to send", the folder's row "Waiting to send" in its level,
  the tray listing all three with *Discard*, the entry "Not sent" and the balances "Includes entries not sent yet"; Stop
  trusting asked "3 changes made offline have not been sent yet. They will be deleted." (cancelled); reconnected, a
  refresh sent all three (stderr `queued said: offline_sent`, tray empty, folder, Memory and Pot on Home). **Not passed:**
  a photo added offline, a medium's time, the held edit's conflict editor and *Discard mine*, *Save a copy* (no refused
  write made), the store grep, the re-key. After the two core fixes, a second run (same throwaway, offline again): New
  Memory closed its dialog saying "Waiting to send" (stderr `new memory: queued`), the Pot's Settle up showed no *Record*,
  and the tray's *Discard* dropped the queued Memory (`Queued.Discard: Done`, tray empty). Throwaway erased, its home
  volume removed.
- [ ] **10 Windows** — the same. · Done when: `python3 windows/run.py build` green on the VM.
- [ ] **11 the passes** — § Passes; results into ADR 0153's Consequences.
- [ ] **12 harvest** — ADR 0153's `Built:` and `Status: Accepted` once the owner's questions are answered; ADR 0140's
  decision 7 pointed at it (already in its `Replaced in part`); `native-apps.md`; `src/mobile/CLAUDE.md` § Nothing on disk
  names the outbox; the Use pages EN+DE (R11, `expenses/record-an-expense.mdx`, `memories/the-gallery.mdx`'s "Offline a
  corrected time … cannot be changed"); `help/offline.mdx`; `security/known-limitations.md` (the unsent writes in the
  stolen-device residual), `threat-model.md`, `what-the-server-sees.md` (a burst at the reconnect); inbox #897 deleted.
  · Done when: every guard but guard_gaps green.

## Passes

Android, Linux and Windows as ADR 0140's, each a throwaway, erased after: offline, a Chapter made, two photos added to
it, a Pot entry with a receipt added, a medium's time set; the store grepped for the entry's note and the photos'
markers (no hit); meanwhile, from the web as a second throwaway member, the same Pot entry edited and the node re-keyed
(a link closed below it); back online: the Chapter and photos sent and opening on the web, the entry held with both
versions, resolved; the re-keyed node's new medium opening on the web (sealed under the new key). Then sign-out with a
write waiting asks. Apple's are the owner's (named open).

## Owner questions

1. **Which writes queue (ADR 0153 decision 3).** *Recommendation:* the table as written — additions and the person's
   own edits that state what they read; never sharing, rights, keys, account, move, restore, a file's replacement, a
   Memory's facts/participants/banner/music, buckets, Pot settings. Wider lists need conflict rules that hide surprises
   from other people.
2. **A queued Pot entry in the balances.** *Recommendation:* counted, the entry marked *not sent* and the balance's line
   saying it includes unsent entries — the person travelling offline wants to see what they owe now; leaving them out
   makes the total disagree with the list.
3. **A queued edit meeting a newer version.** *Recommendation:* the online conflict's editor, the newer version shown
   and named (who, when), the person's queued values in the form, saving replaces it, *Discard mine* beside — never the
   web's online move of loading the other version over what the person typed, since here the typing was hours ago and
   not on screen.
4. **Signing out with writes waiting.** *Recommendation:* ask, naming the count: *Sign out anyway* deletes them,
   *Cancel* keeps them to send first. A refused refresh or a revoked device deletes them without asking (security).
5. **The words** (*Waiting to send*, the banner's count, the reconnect's notice, the held and refused reasons, *Save a
   copy*, the sign-out ask), EN and DE. *Recommendation:* drafted in phase 5 beside the existing `offline_*` words, the
   owner's to change before phase 7.

**Answered (owner 2026-11-19): every question as recommended.**

## Definition of done

Phases 1–12 checked; every bite seen red and green; the passes run as throwaways and erased, Apple's named open; the Use
pages EN+DE; every guard but guard_gaps green; `git diff --stat` over `src/backend/`, `src/web/` and `contracts/` empty.

## Retrospective

_(filled at the end of the implementation)_
