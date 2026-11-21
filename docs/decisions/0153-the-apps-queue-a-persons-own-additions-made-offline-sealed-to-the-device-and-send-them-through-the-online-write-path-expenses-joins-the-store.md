# ADR 0153 — The apps queue a person's own additions and edits made offline, sealed to the device, and send them through the online write path; Expenses joins the store; a login-level device syncs in the background
- Status: Accepted (owner 2026-11-19, the CRs' owner questions answered as recommended)
- Built: decisions 1–7 on every app (2026-11-20), passed on Android, Linux and Windows (§ Consequences); the Mac's and
  the iPhone's passes are open ([native-apps](../crosscutting/native-apps.md) § Open). Decision 8 built (2026-11-20):
  passed on Android (§ Consequences), on iOS built and compiled, its run on an iPhone the owner's (native-apps § Open)
- Date: 2026-11-19
- Model: [native-apps](../crosscutting/native-apps.md)

## Context

ADR 0140 made the apps read offline and left three things out, each an inbox row: writes made offline and sent later
(#897, its decision 7: "offline is read-only; no write is queued"), a sync of pins while the app is not running (#896,
its decision 9's last sentence), and Expenses offline (#898). Search offline over a whole space's photos (#956) leans on
the same store. **The web keeps nothing and writes nothing offline** (`src/web/src/sw/mediaSw.ts`' header forbids Cache
Storage and IndexedDB; `src/web/src/features/expenses/potSession.ts:7`: "offline is out of the first cut"), so here the
apps go beyond the web on purpose; the rules a write follows once sent stay the web's.

Measured 2026-11-19 on `claude/offline-refine` at `4f3b57ecd` (commands from `src/mobile/` unless named; `C` =
`core/src/commonMain/kotlin/me/raddatz/veyq/mobile`):

- **Every write states what it read; none states an `If-Match`.** Preconditions are body fields — `nodeEpoch`/`epoch`/
  `parentEpoch`, `metaRevision`, `placementRevision`, `expectedBlobId`, a Pot record's `revision` — and every conflict is
  a `409` with a code (`stale_epoch`, `node_rekey_pending`, `stale_revision`, `stale_placement`, `stale_file`, `id_taken`,
  `slug_taken`); each write declares its mode under `x-veyq-concurrency`, held by `scripts/validate_write_concurrency.py`
  (`grep -rn "If-Match" contracts src/backend src/mobile/core/src | grep -v If-None-Match` from the repository root → no
  hit). ADR 0033's and ADR 0081's revisions are read validators, never write preconditions.
- **A re-key strands a sealed request, and the server cannot tell.** A node re-key raises the node's epoch and re-wraps
  every `K_blob` under the new node key, never re-encrypting a blob ([ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md));
  `NodeEpochGate` refuses a stated epoch that is not the stored one (`grep -n "stale_epoch\|node_rekey_pending"
  src/backend/core/src/main/kotlin/me/raddatz/veyq/core/node/rekey/NodeEpochGate.kt`, repository root). But the
  `media-meta`, `node-meta`, `blob-key` and `bucket-name` seals bind no epoch (`grep -n "media-meta\|node-meta\|blob-key\|bucket-name"
  $C/crypto/Contexts.kt`), so a body sealed under the old key and sent with a fresh epoch would be accepted and read by
  nobody. Every online writer already re-seals on each attempt (`sed -n 17,79p $C/session/NodeEpochWrite.kt`).
- **Ids are the client's; two creates are not idempotent:** `createNode` and `completeMedia` answer a replay whose first
  answer was lost with `id_taken` (`grep -n "id_taken" contracts/api/content.openapi.yaml contracts/api/memories/media.openapi.yaml`).
- **A Pot is an append-only log of sealed revisions** ([ADR 0108](0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md)):
  `appendPotRevisions` takes 1–256 items all or none, each stating `revision` n+1 and `nodeEpoch`, and names the heads the
  writer saw; a stale one is `409 stale_revision`, which the web and the core answer by loading the newer version into
  the editor (`grep -n "stale" ../web/src/features/expenses/potSession.ts core/src/commonMain/kotlin/me/raddatz/veyq/mobile/expenses/PotSession.kt`).
  The ledger is read by `listPotRevisions` with a `ledgerRevision` `ETag` and a `since` cursor, directly, not as a kept
  read (`sed -n 110,135p $C/expenses/PotDoor.kt`); its receipts' listing happens to pass through
  `KeptRead.APPLICATION_BLOBS` (`sed -n 78,85p $C/blob/ApplicationBlobs.kt`), their frames are fetched with `keep = false`.
  `AppKinds.pins.pinnable` names Memory, Chapter, folder and file only (`sed -n 336,356p $C/AppKinds.kt`).
- **Offline refusals are per model:** `Msg.OFFLINE_READ_ONLY` in `ShellModel`, `GalleryModel`, `MusicModel` and
  `TrackList`, Depot's `FileModel` through `door.online()`, and nothing in Expenses, whose writes fail by the
  `Unreachable` its `respond` throws (`grep -rn "OFFLINE_READ_ONLY\|door.online()" $C`).
- **The one upload queue is in memory** (`blob/UploadQueue.kt`: no `DeviceStore`, no `DeviceFiles`; one automatic retry,
  then the person's), and the store is wiped at a Master Key rotation this device makes (`sed -n 388,396p
  $C/session/Account.kt`) and whenever its header does not open (ADR 0140 decision 2).
- **What opens the Master Key without the person, per platform.** The login level's unlock key: Android, a Keystore AES
  key with no user-authentication parameters (`sed -n 78,96p android/src/main/kotlin/me/raddatz/veyq/mobile/android/KeystoreDeviceKeys.kt`),
  its wrap copy and refresh token in credential-encrypted preferences — readable after the first unlock since boot,
  locked screen or not (remembered: Android's file-based encryption); Apple, a Keychain item
  `kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly` (`sed -n 44,58p apple/Veyq/Core/KeychainStore.swift`) — readable only
  while the device is unlocked (remembered: Apple's data-protection classes); Windows DPAPI and Linux's Secret Service,
  while the person's desktop session is signed in. The presence level asks the person on every platform, and the password
  trust needs the password. No process opens an account in the background today: `PinWorker` and `ContinuedWork` hold the
  process for a running account and "open no account" (`sed -n 24,30p android/src/main/kotlin/me/raddatz/veyq/mobile/android/OfflineWork.kt`).
- **What the systems grant:** WorkManager's periodic work runs at most every 15 minutes
  (`PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS = 900000`, `javap -constants` over `work-runtime-2.12.0.aar`'s
  `classes.jar`); iOS's `BGAppRefreshTaskRequest` launches the app "briefly" at the system's choice and
  `BGProcessingTaskRequest` "when conditions are favorable", optionally on external power and network, neither on macOS
  (`grep -n "API_AVAILABLE\|briefly\|favorable\|requiresExternalPower" BGTaskRequest.h` in the iOS SDK's
  `BackgroundTasks.framework`). A worker started in the background cannot promote itself to a foreground service on
  Android 12 and later (remembered; measured in its phase before it is relied on).

## Decision

1. **A queue of the person's own writes, kept as their intent, sealed to the device, re-sealed for the server when it
   is sent.** A queued write is the person's input — the fields they set, the ids the client minted, the revision or
   placement they saw — never a server request already sealed. When it is sent it runs through **the same core write
   path an online write takes** (`NodeEpochWrite`, `ListedTierWrite`, `PotWrites`, `MediaUpload`, `DepotFiles`), which
   reads the current keys and seals under them, so a re-key or a scope rotation while offline costs nothing: the key the
   write is sealed under is the one current when it is sent. A sealed request would be refused after a re-key at best,
   and accepted unreadable at worst (§ Context).
   - **A queued blob is the exception, because its key outlives a re-key:** an added photo, video, file or receipt is
     sealed at once, frame by frame, under a fresh `K_blob` (as `MediaUpload` does online, its tiers derived by the
     platform's `Deriver` at the same moment), and only `K_blob`'s wrap under the node's key is made when it is sent.
     So nothing waits on the device as plaintext: the picked file is read once and let go as an online upload lets it
     go (`MediaUploads`' `left`).
2. **Where the queue lives: the account's offline store, as entries of its own.** Each queued write is an entry of kind
   `OUTBOX` in ADR 0140's store — sealed under `K_off`, named opaquely, its `K_blob` frames as a blob file — so it is
   protected exactly as kept content is, and ADR 0140 decision 1's list of what may be kept gains *the person's own
   unsent writes, sealed*. Outbox entries are never evicted, never swept by the read sweep, counted apart on Settings'
   "Storage on this device", and refused below ADR 0140's 5 % free-space floor with a notice. **No store, no queue:** a
   device that keeps no trust keeps offline read-only, as today.
   - **What wipes it:** every wipe of ADR 0140 decision 11. Signing out, "Stop trusting" and "Remove all offline content"
     with writes waiting **ask first**, naming how many will be lost. A refused refresh and a wrap the server no longer
     serves wipe them **without asking**: a revoked device must not send writes, and that is the escalation's point
     (ADR 0058).
   - **A Master Key rotation keeps them where this device holds both keys:** the rotation it makes itself re-wraps the
     store's header under the new key instead of wiping (it holds both); a rotation made elsewhere, met by an account
     opened offline under the old key, re-wraps at the reconnect before anything else. Otherwise the header does not
     open and the waiting writes go with the store — the price of a rare, deliberate act, stated on the Use page.
3. **What may queue: additions and the person's own edits that state what they read.** A closed list in the core
   (`offline/Queued`, beside `KeptRead`), each kind with the same refusals online has:

   | Application | Queues | Never queues |
   |---|---|---|
   | Core (nodes) | create a folder; trash a node; rename (stating its `placementRevision`) | move, restore, purge; every write of sharing, membership, rights, keys, account and device |
   | Memories | create a Memory or Chapter; add photos and videos; a medium's capture time; trash a medium; a mark (as an add or a remove) | a Memory's facts, participants, banner, music and tracks; contributions (buckets); a re-key |
   | Depot | add a file (a new node); trash | replace a file's content |
   | Expenses | add, edit and delete an entry, with its receipts | a Pot's settings; erase a record |

   Every write the list does not name stays refused offline with ADR 0140's notice, now in one place: the core asks
   `Queued` before a model offers a control, so the per-model checks become one rule.
4. **Sending: in order, at the reconnect, through the online path; what has changed meanwhile is never overwritten
   unseen.** After `reconnect` succeeds the core sends the queue oldest first, one at a time; a write that depends on
   another (media into a Chapter made offline) waits for it, and a write held for the person holds only what depends on
   it. Each write ends as one of:
   - **Sent.** `id_taken` on a create is read as *maybe sent already*: the core re-reads the listing, and the id there
     with the person's content is sent; anything else is held as a conflict.
   - **Re-sealed and sent** — a `stale_epoch` or `node_rekey_pending` takes `NodeEpochWrite`'s path, as online.
   - **Held for the person (a conflict)** — wherever the online path would apply the person's change over a newer state
     the person never saw: a Pot record edited or deleted meanwhile (`stale_revision`), a rename meeting a moved
     placement (`stale_placement`; online rebuilds and re-applies), a capture time changed by someone else in the same
     field (`stale_revision` on that field; a different field merges as online). The person opens it and decides with the
     online conflict's own screen: the Pot editor with the newer version named beside theirs, the rename dialog
     with the current name.
   - **Refused** — the target is gone or no longer the person's to write (`403`, `404`, a trashed node, a lost
     membership or right, `quota_exceeded`, an application switched off): held with the reason, offered *Discard* and,
     for a queued blob, *Save a copy* to a place the person chooses (plaintext only where the person saves it, ADR 0047).
   Nothing is retried for ever: a transport failure leaves the write waiting for the next reconnect; any other error is
   held as refused.
5. **What the person sees.** A queued write shows where it was made at once — a tile, a row, an entry marked *Waiting to
   send* —; the banner says "Offline — content as of {time}" and, while writes wait, their count; **the uploads tray
   (`UploadQueue`'s, every app) lists the queue**, each item with *Discard*, a conflict with *Open*, a refused one with
   its reason, *Discard* and *Save a copy*. After a reconnect, a notice names how many were sent and how many need the
   person. A queued Pot entry counts in the Pot's balances marked as not sent. The words are the owner's, in
   `src/mobile/words/words.json`.
6. **Expenses joins the store** (#898). The ledger becomes a kept read, `KeptRead.POT_REVISIONS`, the whole ledger read
   without `since`, conditional on its `ledgerRevision`, kept and replaced as `MemoryMedia` keeps `listNodeMedia`
   (`sed -n 112,140p $C/memories/MemoryMedia.kt`) — never merged; the online poll's deltas stay as they are, and the kept
   copy is replaced by a whole read at each online open of the Pot and each pin sync. A receipt's frames are kept when
   it is shown (`keep = true`), as a Depot file's are. A Pot is pinnable (`AppKinds.pins`: its ledger, its receipts and
   its cover); the sweep reaches its ledger and receipts below the Pot node (`AccountOffline.belowNode`).
7. **A space's media join the kept reads, so search finds them offline** (#956): `listScopeMedia` becomes
   `KeptRead.SCOPE_MEDIA` per scope, in the automatic tier only, conditional on its `ETag`, replaced and never merged
   (a medium trashed elsewhere is gone with the next answer), swept with its scope. Offline, search reads it as online
   ([ADR 0146](0146-the-apps-search-with-the-cores-port-of-the-webs-search-both-answer-to-one-set-of-vectors.md)); a
   hit whose tile is not kept is a tile without a picture.
8. **Background sync: a login-level account, opened by the system's schedule, never by lowering what opens it** (#896,
   confirmed by the owner 2026-11-19). On a device trusted at the **login level** (`DEVICE_KEY`, `LOGIN`), with the person's
   *Sync in the background* on, a scheduled run opens the account exactly as an app start at that level does — no
   question, the device's sign-in being the authentication (ADR 0123) —, refreshes the pins (ADR 0140 decision 9), sends
   the queue (decision 4), and closes the account, the Master Key zeroed, before it ends. *As built (§ Consequences):*
   the queue first, then the pins.
   - **Never at the presence level and never with the password trust:** those ask the person, and a background run has
     nobody to ask. Their Settings say why the switch is not there.
   - **Never by changing a key's protection:** iOS keeps the login key's `WhenPasscodeSet` class, so a scheduled run on
     a locked iPhone finds the key closed and ends having done nothing; it syncs only when the system wakes the app while
     the phone is unlocked. Moving the key to `AfterFirstUnlock` would let a run on a locked phone read it, and is
     rejected (§ Alternatives).
   - **Android:** a unique periodic WorkManager work, at the system's minimum of 15 minutes or a longer one, constrained
     to a network and not low battery, which finishes within the time a background worker has (no foreground promotion
     from the background). **iOS:** `BGAppRefreshTask` (`BGTaskSchedulerPermittedIdentifiers`). **The desktops:** none —
     they sync while they run, and a scheduled task registered at login is a standing change the person did not ask for.
   - **Every family stays one:** a run resumes with the refresh token as an app start does, so it opens no new session
     family; a refused refresh ends the record and wipes the store, as at an app start.

## Consequences

- **ADR 0140's decision 7 is replaced** (offline is no longer read-only where decision 3 queues), and the last sentence
  of its decision 9 by decision 8; its decision 1's list of what may be kept gains decision 2's outbox entries;
  `src/mobile/CLAUDE.md` § Nothing on disk the app did not choose names them.
- **What the operator learns:** a burst of writes at a reconnect, stamped with the time they reach it, not the time they
  were made (a Pot entry carries its own date sealed); with decision 8, requests at times the person did not open the
  app. [What the server sees](../../site/src/content/docs/security/what-the-server-sees.md) says both.
- **What a thief of the device learns beyond ADR 0140's window:** the person's unsent writes, at the same trust level
  that opens the store — the same residual, now covering what the person made offline as well as what they viewed
  ([Known limitations](../../site/src/content/docs/security/known-limitations.md#the-apps-offline-store),
  [Threat model](../../site/src/content/docs/security/threat-model.md)).
- **The window of decision 8:** the Master Key is in memory in a process the person did not start, for the length of a
  run. It holds no key a login-level start would not hold, and nothing outside the process can start a run.
- **No contract, backend or web change:** every write and read exists. The core gains `Queued`, the outbox, the sender
  and two kept reads; the bridge carries the queue's items and states, never a key, seal or token
  (`SecretFreeSchemaTest`).
- **Left as before:** an online upload the network cuts still fails and waits for the person's *Retry* (ADR 0145); it
  does not move into the queue.
- **As built, decision 8** (2026-11-20). One core entry, `BackgroundRun.run(deadline)` (`session/BackgroundRun.kt`): for
  each account this device remembers at the login level (`DEVICE_KEY`, `LOGIN`) whose *Sync in the background* is on
  (`ThisDevice.backgroundSync`, on by default, an off-list per account on the device, cleared when the record is
  forgotten), the kept wrap is first tried under the login key — a key the system keeps closed now (a locked iPhone) ends
  the run as `KEY_CLOSED` having sent nothing —, then the start's own online resume (`VeyqAccount.resumeOnline`, never
  its offline twin: no server, nothing opened), **the queue sent, then the pins synced** within the deadline, and the
  account closed (`retire`: the Master Key, the identity and every scope key it opened zeroed; the kept session stays). An
  account already open in the process is used and left open, never opened twice. **The order is reversed** from the
  decision's text: a pin's fetch can take longer than a run's time, and the person's own writes waiting behind it would
  wait to the next run, while a queue is small and sent between checks of the deadline. A host only schedules
  (`ThisDevice.backgroundWanted`) and hands the time: **Android** (`BackgroundWork.kt`) one unique periodic work
  `veyq-background-sync` (`UPDATE`, 15 minutes, a network, the battery not low), its `SyncWorker` running the core with 9
  of the worker's 10 minutes, no foreground promotion; **iOS** (`BackgroundSync.swift`) one `BGAppRefreshTaskRequest`
  `app.veyq.background-sync` (permitted in the Info.plist, `UIBackgroundModes` `fetch`), requested again at each run and
  each start, the run given 25 of the refresh's about 30 seconds through `AppleBackground` (a job the expiration handler
  cancels; the task completed once the account is closed); a locked Keychain (`errSecInteractionNotAllowed`) is a closed
  key in `AppleDeviceKeys`, never a missing one, so no new unlock key is stored over it and no kept session is taken for
  gone. The desktops get no switch and no reason (`ThisDevice.backgroundRuns` false; the bridge carries neither). Settings'
  *Storage on this device* draws the switch on the phones, or why it is missing at the presence level and with the
  password trust. Tests: `BackgroundRunTest`, `BackgroundSyncWireTest`, `AppleBackgroundTest`.
- **The pass (decision 8).** **Android** (2026-11-20, a throwaway API 36 AVD with a PIN, deleted after; a throwaway account
  with a member, erased after): rebooted, unlocked once, locked (`dumpsys trust`: `deviceLocked=1`), the app's process
  started by WorkManager's boot receiver with no activity — the forced job logged `background run: DONE`: the Keystore
  login key opens after a reboot and the first unlock with the screen locked. A Memory pinned; offline a Memory queued; a
  photo added to the pinned Memory from a second session; rebooted, unlocked once, locked, the network back; the forced
  job sent the queued Memory (`DONE, sent 1`) and kept the added photo — opened afterwards in airplane mode, the pinned
  Memory showed both photos as of the run's time. How a pass forces the job is a trap in
  [`src/mobile/CLAUDE.md`](../../src/mobile/CLAUDE.md) § Traps (WorkManager's namespace, the 15 minutes' deferral,
  force-stop cancelling the jobs, the standby bucket). **iOS:** both Apple builds green, never run — the Simulator runs no
  app refresh and no simulator boots on the build Mac; the run on an iPhone is the owner's ([native-apps](../crosscutting/native-apps.md)
  § Open). **Not run:** a run on low battery or without a network (WorkManager's constraints), a run that ends an account
  (`BackgroundRunTest` holds it).
- **What the docs site says (decision 8).** [Use content offline](../../site/src/content/docs/getting-started/use-content-offline.mdx)
  (EN and DE): the switch, why it is missing, how often the system runs it; [What the server sees](../../site/src/content/docs/security/what-the-server-sees.md):
  requests at times the app was closed; [Threat model](../../site/src/content/docs/security/threat-model.md): the run's
  window on a stolen device.
- **As built, decisions 6 and 7** (2026-11-20). `TenantPotDoor.whole` is the kept read: a read with no cursor — the
  Pot's open, a re-open, a pin's sync and **the re-read after each own write** (`PotSession.readWhole`; a write's delta
  re-read kept nothing, so an entry written after the open was missing offline, #1038) — while the live poll's deltas
  stay unkept. The sweep reaches `POT_REVISIONS` below a Pot node from the first keep (without it the sweep after the
  first keep drops the ledger). A Pot shown offline refuses every write (`offline_read_only`) and offers "Available
  offline" only to turn it off: a pin cannot be sized and confirmed before its first byte (ADR 0140 decision 9). The
  banner's time is the open page's own kept time (`TenantSession.pagesOffline`), else the lists'. A receipt just
  attached is not kept until shown (decision 6's rule); its listing is current, a kept read on every read.
  `SCOPE_MEDIA` is reached by the sweep from `MY_SCOPES`, never pinned, never protected; search falls back to *Photos
  are searched once you are online* only where a space's listing is not kept (ADR 0146 decision 11 replaced in part).
  Tests: `OfflinePotTest`, `OfflinePotPinsTest`, `OfflineBannerTest`, `DispatchTest`, `SecretFreeSchemaTest`.
- **The passes (decisions 6 and 7).** Each a throwaway account with a member, erased after; two Pots made in the app — one
  with an entry whose receipt was shown and one whose receipt never was, viewed; one pinned —, a Memory with one photo
  never opened in the run; the network cut, the app closed, started and unlocked offline. **Android** (2026-11-20, a
  throwaway API 36 AVD, deleted after; airplane mode): both Pots open read-only (no *Add entry*, no Record, Settings
  without changes), each banner its own time; the shown receipt opens, the unshown one says "Not available offline";
  search finds the never-opened Memory's photo; after *Clear cache* and a restart the viewed Pot says "Not available
  offline", the pinned one opens; the app's data grepped for every title, receipt marker and name: no hit. **Linux**
  (2026-11-20, `run.py run` under Broadway, the server cut by an unserved `VEYQ_LIVE` port): the same, the store (41
  files) grepped for the titles, file names and the PNGs' markers: no hit; the viewed Pot showed "No entries yet." as of
  its first open, its entries written after it (#1038, fixed since in core). **Windows** (2026-11-20, an unpackaged
  debug build on the VM, the tunnel and forwarder killed): the same, the store (33 files) and what was written under
  `%LOCALAPPDATA%\veyq`, `%TEMP%` and `INetCache` grepped: no hit; signed out offline, the account's store gone. **Not
  run:** the Flatpak and the MSIX; sign-out offline on Android and Linux; a never-kept Pot opened offline on Linux and
  Windows; the "searched once online" line on any desktop (every listing was kept). **The Mac's click pass** (the
  coordinator's; both Apple builds green, `xcodebuild … CODE_SIGNING_ALLOWED=NO`, macOS and the iOS Simulator SDK): a Pot
  viewed and one pinned through the header's *Available offline*; offline both open under the shell's banner with its
  time, no *Add entry*, no Record, Settings without edits; a shown receipt opens, an unshown one and a never-kept Pot say
  "Not available offline"; search finds a never-opened Memory's photo; the store grepped. **The iPhone's** is the
  owner's, on a device (no simulator boots on the build Mac, #928).
- **What the docs site says (decisions 6 and 7).** [Use content offline](../../site/src/content/docs/getting-started/use-content-offline.mdx)
  (EN and DE): a Pot kept when viewed or made available offline, read-only offline, a receipt never looked at not kept,
  each space's photo list kept for search; [Search all your spaces](../../site/src/content/docs/spaces/search.mdx): offline
  search over every space whose list was read; [Expenses](../../site/src/content/docs/expenses/index.mdx): a Pot offline;
  [Offline or unreachable](../../site/src/content/docs/help/offline.mdx): a Pot's entries among what is shown.

- **As built, decisions 1–5** (2026-11-20). The core's `offline/`: `Outbox` (kinds `OUTBOX` and `OUTBOX_FRAMES` in the
  store's protected set, an order entry, the floor whatever the limit), `Queued` (the closed list, each value naming its
  operations — a test derives them from the senders and fails on one of the never column), `OutboxSender` (oldest first,
  a dependent after its parent, held and refused writes kept and skipped) after each reconnect and at an online open with
  writes waiting, each application's queue side and sender (`QueuedNodes`, `QueuedMemories`, `QueuedDepot`,
  `QueuedExpenses`), and `QueuedTray`/`QueuedShown` (the tray, its *Open* as a typed `QueuedOpen`, the banner's count, the
  reconnect's notices, the ask before a loss, for any account); the models' overlays (`ShellModel.queuedHere` and
  `createsHere` offline, `GalleryModel`'s waiting tiles, `PotView`'s *Not sent* and `balancesUnsent`, the held entry's
  editor `PotModel.heldEditor`). The online paths gained only what a queued write states: the id minted at queue time on
  every attempt, the placement or heads seen, a medium's frames sealed ahead. The bridge carries items and states alone
  (`Watched.QueuedTray`, `Queued.*`, `Editor.OpenHeld`/`DiscardMine`; `SecretFreeSchemaTest`). Where it differs from the
  text above: a Master Key rotation — this device's own, or one met at `resume` — carries the outbox under a **fresh**
  `K_off` and lets the reopen's sweep drop every file of the old key, instead of re-wrapping the old header (ADR 0148's
  Alternatives rejected keeping an old `K_off`); a refused receipt refuses its whole entry, so the person decides with
  *Save a copy* or *Discard*; a sibling's name met at the send holds a create or rename (`name_taken`), and a re-key still
  pending leaves a write waiting; the outbox's keys name no tenant or scope, so a suspended tenant's sweep leaves its
  writes to be refused on send rather than lost unsaid. Not shown in place before they are sent (#1040): a capture time
  (the grid keeps its order), a rename, and a Memory or chapter made offline, which opens nothing until sent; a trash
  offline offers no Undo (the tray's *Discard* is it). Tests: [`src/mobile/CLAUDE.md`](../../src/mobile/CLAUDE.md) § Prove
  your work names them.
- **The passes (decisions 1–5).** Each a throwaway with a member, erased after. **Android** (2026-11-20, a throwaway API 36
  AVD in airplane mode, deleted after; the member on the web): offline a folder and a Memory made, an entry with a receipt
  added and another edited, two photos added to a kept Memory — rows, tiles and entries *Waiting to send* / *Not sent*,
  the balances' line, the banner and the tray counting; the app's data (60 files) grepped for the titles and the note: no
  hit; back online all sent, the receipt and the photos opening on the web; the edited entry, changed by the member
  meanwhile, held, its *Open* naming who and when with *Discard mine*; sign-out with a write waiting asked first.
  **Linux** (Broadway, the server cut by its own forwarder): offline New offered only New folder, Upload files and New
  Memory; a folder, a Memory and an entry with a receipt queued — banner, row, tray, *Not sent*, the balances' line; Stop
  trusting asked first; reconnected, all three sent; a second run's queued Memory dropped by the tray's *Discard*.
  **Windows** (the VM, an unpackaged debug build): a folder queued — banner, row, tray —, Manage's sign-out asked first,
  sent at the reconnect. **Live** (`LiveQueuedTest` against the dev backend): a chapter, two photos and an entry with a
  receipt queued offline and sent; it found that `VeyqAccount.retire` logged out the family a device later unlocked
  offline would resume with (fixed: a remembered account's kept session stays). **Not run on any app:** a medium's capture
  time, a node re-keyed while offline (`QueuedTest`, `QueuedMemoriesTest` hold the re-seal), *Save a copy* of a refused
  write and a held rename's *Open* (tests only); on the desktops a photo queued, the held entry's editor and the store's
  grep; on Windows a Pot entry. **Apple:** both builds green, nothing clicked — an ad-hoc build keeps no Keychain trust,
  so no store and nothing queues; the Mac's pass waits for the owner's signed build and the iPhone's is the owner's
  ([native-apps](../crosscutting/native-apps.md) § Open holds the list); `AppleQueuedTest` holds what Swift reads.

## Alternatives considered

- **Queue the sealed requests** (cheaper to send) — a re-key while offline makes each one stale, and a body re-sent
  under a fresh epoch is accepted and read by nobody (§ Context): the server cannot tell.
- **Last writer wins for queued edits** (send everything, let the newer arrival stand) — a person's offline edit of a
  Pot entry would overwrite a co-member's correction they never saw; the online path refuses exactly that.
- **Merge offline edits automatically** (a CRDT per application) — only the journal is one (ADR 0133); Expenses' log
  already keeps every revision, and a held conflict costs one screen the online path already has.
- **Every write queues** — sharing, rights, rotations and account writes change who can read what; done hours late,
  against a state the device no longer knows, they surprise the people they touch.
- **A queue in its own store, under a key of its own** — two stores double the wipe paths and the key's lifecycle; the
  outbox needs exactly the store's protection and wipes.
- **The picked files kept in place until the reconnect** (no copy) — a picker's grant may lapse with the process
  (Android's photo picker, iOS's `PhotosPicker` copy in `tmp`), and on iOS that copy is plaintext; sealing at once
  costs the same read the upload would do.
- **The login key moved to `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` on iOS** so a locked phone can sync — a
  phone stolen after its first unlock would hold the Master Key's key readable to anyone who reaches the Keychain of a
  locked device; the login level promised the device's own unlock, not its boot.
- **A background run at the presence level** that asks later — it cannot send or sync without the key, and a
  notification that asks for the face to sync is a prompt the person did not start.
- **Background runs on the desktops** (a scheduled task, a systemd timer, a launch agent) — a standing change outside
  the app, for devices that sync whenever they run.
