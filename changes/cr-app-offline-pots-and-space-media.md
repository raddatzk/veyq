---
id: cr-app-offline-pots-and-space-media
title: A Pot's ledger and receipts and a space's media join the apps' offline store — a Pot readable and pinnable offline, search over every kept space offline
status: planned
satisfies: []
adrs: ["0153", "0140", "0108", "0146", "0136"]
invariants:
  - "operator-blind E2EE: only ciphertext as the server sent it is kept, sealed again under K_off (ADR 0140 decisions 1 and 2); never a token or a URL (KeptReadsTest)"
  - "replace, never merge (ADR 0140 decision 5): a kept ledger or space listing is the server's last whole answer"
  - "every rule is the core's; the bridge carries states and sizes only (SecretFreeSchemaTest)"
  - "no word in an app but from src/mobile/words/words.json; each platform's guideline (ADR 0123 decision 9)"
  - "no pass signs in as the seeded principal: every pass is a throwaway's (ADR 0148 decision 12)"
---

## Goal

Inbox #898 and #956, ADR 0153 decisions 6 and 7. Offline today a Pot does not open (its ledger is no kept read) and
search finds no photo outside the open Memory (`listScopeMedia` is no kept read). After this CR a Pot viewed or pinned
opens offline with its entries and the receipts that were shown, a Pot can be made *Available offline*, and search
offline finds every medium of a space whose listing was read online. Read-only: writing offline is
[cr-app-offline-writes](cr-app-offline-writes.md), which builds on this one. The web keeps nothing offline and is
unchanged; this is the apps going beyond it (ADR 0153 § Context).

## Context & constraints

- **Read first:** ADR 0153 decisions 6–7, ADR 0140 decisions 5, 6, 8, 9 and its Consequences' "As built", ADR 0108,
  `src/mobile/CLAUDE.md` § Nothing on disk the app did not choose, § Prove your work.
- **Where what runs** (from `src/mobile/`): `./gradlew :core:jvmTest :core:macosArm64Test :bridge:jvmTest`;
  `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :android:assembleDebug`; the Apple builds after
  `./gradlew :core:assembleVeyqCoreDebugXCFramework`; `python3 linux/run.py test|build`; `python3 windows/run.py build`
  on the VM. No iOS simulator boots on this Mac (#928).
- **Out of scope:** any write offline; a background sync.

## Measured at refine (2026-11-19)

Branch `claude/offline-refine` at `4f3b57ecd`. `C=src/mobile/core/src/commonMain/kotlin/me/raddatz/veyq/mobile`.

| # | What | Where / command |
|---|---|---|
| R1 | The ledger is read straight, not kept: `listPotRevisions` with `ifNoneMatch` (the `ledgerRevision`), `since`, `sinceDelete`; `revisionIds` on the first page drops what the Pot no longer has. | `sed -n 110,150p $C/expenses/PotDoor.kt`; `grep -n "operationId: listPotRevisions" -A30 contracts/api/expenses/*.yaml` |
| R2 | The receipts' listing already passes `KeptRead.APPLICATION_BLOBS` when no tag is sent; their bytes are `download` → `frames(keep = false)`; `PotExport` and `PotCopy` read receipts through `openReceipt` too. | `sed -n 78,85p $C/blob/ApplicationBlobs.kt`; `sed -n 298,320p $C/blob/ApplicationBlobs.kt`; `grep -rn "openReceipt" $C/expenses` |
| R3 | The pattern to copy: `MemoryMedia.list` keeps `listNodeMedia` conditional, replace-never-merge. | `sed -n 112,150p $C/memories/MemoryMedia.kt` |
| R4 | Pins: `PinKinds.pinnable` Memory, Chapter, folder, file; the KDoc "A Pot is not kept offline (#898)". | `sed -n 336,356p $C/AppKinds.kt` |
| R5 | The sweep's reach below a node and the listing kinds. | `grep -n "belowNode\|LISTINGS" $C/offline/AccountOffline.kt $C/offline/KeptRead.kt` |
| R6 | Search reads `listScopeMedia` straight, with an `ETag`, paged by `after`. | `sed -n 110,140p $C/memories/MemoriesSearch.kt`; `grep -n "operationId: listScopeMedia" -A25 contracts/api/memories/media.openapi.yaml` |
| R7 | The allowlist gate derives from the models. | `core/src/jvmTest/kotlin/me/raddatz/veyq/mobile/offline/KeptReadsTest.kt` |
| R8 | Tests that touch the Pot and search (the blast radius). | `grep -rln "PotSession\|PotDoor\|MemoriesSearch" src/mobile/core/src/*Test src/mobile/bridge/src` |
| R9 | Site pages on offline. | `grep -rli offline site/src/content/docs \| grep -E "use-content-offline\|help/offline\|expenses\|search\|known-limitations\|what-the-server-sees"` |

## Decisions

ADR 0153 decisions 6 and 7 hold them; here only what the work needs beside them.

- **D1 — `KeptRead.POT_REVISIONS`**, model the generated ledger page; `PotDoor.read` keeps the **whole** ledger (no
  `since`) through `account.kept(..., conditional = true)`; the poll's delta reads stay uncached and the Pot's open and
  each pin sync replace the kept copy whole. Offline the Pot opens from it, carrying `offline: asOf` as a gallery does;
  `PotModel` refuses every write offline with `offline_read_only` (as `GalleryModel`), until cr-app-offline-writes.
- **D2 — receipts kept when shown:** the receipt viewer's read keeps (`keep = true`); `PotExport` and `PotCopy` keep
  nothing (they read for another purpose). A receipt not kept says `offline_not_kept`.
- **D3 — a Pot pins:** `pinnable` gains the Pot's kind; `blobs()` lists its receipts and cover; the size question and
  progress are the pin's own. `belowNode` reaches `POT_REVISIONS` and the receipts' `APPLICATION_BLOBS` for a Pot node.
- **D4 — `KeptRead.SCOPE_MEDIA`** per scope, automatic tier only (never pinned, never `PROTECTED`), kept by
  `MemoriesSearch`'s read and reached by the sweep from `MY_SCOPES`. Offline search reads it; a hit's tile reads the
  frames that are kept.

## Phases

- [x] **1 core — the ledger and receipts** (D1, D2) — `POT_REVISIONS`, `PotDoor.read` kept, `PotModel` offline state and
  refusal, the receipt viewer's keep. Tests: a Pot read online then offline shows the same entries with `asOf`; a newer
  ledger replaces (an entry tombstoned on the server is gone from the kept copy); a `403`/`404` drops it; an export
  keeps no frame. · Done when: `:core:jvmTest :core:macosArm64Test` green, `KeptReadsTest` green with the new model.
  **Bites:** (a) the ledger merged instead of replaced → the tombstone test red; (b) `PotExport` reading with
  `keep = true` → the export test red; (c) the model carrying a token-named field (a test-only serializer) → `KeptReadsTest` red.
  **Built** (2026-11-19): `KeptRead.POT_REVISIONS`; `TenantPotDoor.whole` (a read with no cursor — the Pot's open, a
  re-open — is the kept whole read, `PotDoor.Read.offline`); `PotSession.offline`/`wentOffline`, `canWrite` and
  `canChangeSettings` false offline, every write `WriteOutcome.OFFLINE` (`Notices` → `offline_read_only`);
  `PotModel.State.offline` and `failure` (`offline_not_kept` / `pot_open_failed`), the reach watched (offline: read-only as
  of the last read; online: read anew, a failed open retried), `receiptFailure()`; `PotSession.openReceipt` keeps,
  `readReceipt(id, keep = false)` for `PotExport` and `PotCopy`. Tests `OfflinePotTest` (5) over `PlayedTenant`, which now
  plays a Pot's revisions and application blobs. Deviations: `belowNode` reaches `POT_REVISIONS` already here — without it
  the sweep after the first keep drops the ledger at once, so phase 1 cannot work without it (phase 2's bite (a) still
  bites it); bite (a)'s "tombstone" is an **erasure** (`eraseRecord`): a tombstone is an appended revision a merge carries
  too, an erased record is what a merge brings back. Bites (a) merge in `whole` → the erasure test red; (b) → the export
  test red (`[r2, r1]` kept); (c) a `LeakyPage(shareToken)` model → `KeptReadsTest` red; each restored with `/bin/cp -f`.
  `./gradlew :core:jvmTest :core:macosArm64Test`: macOS green; JVM 1353 tests, one red, `DeviceLinkTest.the_state_carries_no_secret`
  (untouched, load ~5), green re-run alone.
- [x] **2 core — a Pot pins; the space's media** (D3, D4) — `AppKinds.pins`, `belowNode`, `SCOPE_MEDIA` in
  `MemoriesSearch`, the sweep's reach. Tests: a pinned Pot fetches its ledger, receipts and cover and survives
  "Clear cache"; a Pot unpinned and not viewed is swept; search offline finds a medium of an unopened Memory from the kept
  space listing; a scope gone from `MY_SCOPES` drops its `SCOPE_MEDIA`. · Done when: the same checks green. **Bites:**
  (a) `belowNode` without the Pot branch → the pinned Pot's ledger swept, red; (b) `SCOPE_MEDIA` in `PROTECTED` → the
  "Clear cache" test red.
  **Built** (2026-11-20): `AppKinds.pins` takes `EXPENSES_POT`, its blobs `expenses/PotPins.pinned` (the whole ledger
  through `TenantPotDoor.whole`, every held receipt, not dropped or blocked; the cover is core's `coverOf`);
  `KeptRead.SCOPE_MEDIA` reached by the sweep from `MY_SCOPES` (not in `LISTINGS`, never pinned, never `PROTECTED`);
  `VeyqAccount.Read.revision` (the kept or fetched answer's) so `MemoriesSearch.scopeMediaDocuments` keeps its "unchanged:
  null" over the kept read; `SearchModel` asks the applications offline too and falls back to "searched once online"
  (`search_offline_photos`) only where the listing is not kept — ADR 0146 decision 11's "offline no listing is read" is
  replaced here (the harvest names it; the word's note still cites decision 11). Tests `OfflinePotPinsTest` (4); the
  "swept" Pot is dropped by Clear cache after its unpin (an unpinned Pot that still exists is reached, so the sweep keeps
  it in the automatic tier). Bites (a) the `POT_REVISIONS` line out of `belowNode` → both Pot pin tests red ("Clear
  cache dropped the pinned Pot's ledger"); (b) `SCOPE_MEDIA` in `PROTECTED` → the Clear-cache test red; each restored with
  `/bin/cp -f`. `./gradlew :core:jvmTest :core:macosArm64Test` green.
- [x] **3 bridge** — the Pot's offline state and the toggle's state on the wire. · Done when: `:bridge:jvmTest` green.
  **Bite:** a state carrying a sealed body → `SecretFreeSchemaTest` red.
  **Built** (2026-11-20): `Update.Pot` carries `PotModel.State` with its new `offline` (`OfflineAsOf`) and a `failure`
  word (`MsgKey`: `offline_not_kept` / `pot_open_failed`, null where it opened); `Receipt.Open` and `Receipt.Save` refuse
  `NOT_KEPT_OFFLINE` (a new `Answer.Why`) where the Pot is shown offline and the receipt is not kept. The header's
  *Available offline* is the existing `HeaderShown.offline` (`OfflineToggle`), offered for a Pot now that it is pinnable —
  no new wire. Tests: `DispatchTest.aPotShownOfflineCrossesAsItsTimeAndStateAndRefusesItsWrites`; `SecretFreeSchemaTest`
  pins `PotModel.State`'s fields. Bite: `sealedBody` on `PotModel.State` → `SecretFreeSchemaTest` red twice (the field
  list and "named like a secret"), restored with `/bin/cp -f`. `./gradlew :bridge:jvmTest :bridge:generateBindings` green
  (the generated `bridge.rs`/`Bridge.g.cs` are not committed; Linux matches `Update::Pot { state, .. }` and Windows
  `is Update.Pot`, so neither breaks — not built here).
  **Follow-up** (2026-11-20, from phase 4's pass): (1) the banner's time is core's — `TenantSession.pagesOffline`
  (`PagesOffline`) holds each page's own kept time offline (`PotModel` and `GalleryModel` through its door report it), and
  `ShellModel.State.banner` (the bridge's `ShellShown.offlineBanner`, meaning only, no wire change) names the open page's
  where it has one, else the lists'; Android's own rule in `ShellScreen.kt` dropped. (2) Offline "Available offline" is
  offered only where the node is kept already (turning it off): a pin cannot be sized and confirmed before its first byte
  nor fetch (ADR 0140 decision 9) and nothing is queued (decision 7) — `NodeHeader.rights(offlineOn)`, `toggleOffline`
  refuses `offline_read_only`, a size asked online is dropped. Tests `OfflineBannerTest` (3), `OfflineScreensTest`'s
  `offline_a_pin_is_not_offered_nor_made_…`; bites: the banner without the page's time → both page tests red; the
  toggle offered offline regardless → two header tests red; each restored with `/bin/cp -f`.
- [x] **4 Android** — the Pot's header *Available offline*, its offline banner and disabled writes. · Done when:
  `:android:assembleDebug`, the guidelines and words guards green.
  **Built** (2026-11-20): the header's *Available offline* needed no Android code (core's plan offers it for a Pot now,
  `NodeHeader.kt` draws it); `PotScreen.kt` says `PotModel.State.failure` where no Pot opened (`offline_not_kept` muted,
  `pot_open_failed` as a failure), `EntryReceipts.kt`'s viewer `AppModel.receiptFailure()` (core's `receiptFailure`);
  the write controls were already core's (`canWrite`, `canChangeSettings`, `restorable`, the editor's `editable`).
  Deviation: one banner, not two — `ShellScreen.kt`'s banner on an open Pot shown offline names the Pot's own kept time
  (`PotModel.State.offline`, where it has one), else the shell's lists' (a gallery's own `offline` is drawn by no
  platform; the shell's time alone mis-dated the Pot). `:android:assembleDebug`, `validate_mobile_platform_guidelines.py`,
  `validate_mobile_words.py` green. Pass (throwaway with a member, AVD API 36 deleted after, airplane mode, the app
  restarted and unlocked offline): Pot *J1 Trip* (two entries, one receipt viewed) and *J1 Flat* pinned ("On this device")
  → offline both open read-only (no *Add entry*, no Record, Settings without changes, the menu only *Available offline*),
  each banner its own time (12:27 / 12:29); the shown receipt opens, the unshown says "Not available offline"; search
  finds *Lighthouse.jpg* of a Memory never opened; after Clear cache and a restart *J1 Trip* says "Not available
  offline", the pinned *J1 Flat* opens; the app's data pulled and grepped for every title, receipt marker and name: no hit.
- [ ] **5 Apple** — the same. · Done when: both Apple builds green.
- [ ] **6 Linux** — the same. · Done when: `python3 linux/run.py test` and `build` green.
- [x] **7 Windows** — the same. · Done when: `python3 windows/run.py build` green on the VM.
  **Built** (2026-11-20): `PotPage` says `Update.Pot`'s `failure` word where the Pot did not open (offline
  `offline_not_kept`, else `pot_open_failed`); `ReceiptViewerDialog` says `offline_not_kept` for `Receipt.Open`'s
  `Refused(NotKeptOffline)` (`receipt-not-kept`), not "unreadable"; the header's *Available offline* is the generic
  `NodeHeader` toggle, now offered for a Pot (`HeaderPlanTest` pins it drawn for `EXPENSES_POT`, never the page's). The
  "as of" line is the shell's `offline-banner`, as over a gallery — no Pot-own banner (its time is the shell's lists'
  until core's rule moves it, the coordinator's change); writes are off through the core (`canWrite`/`canChangeSettings`:
  no New, no Record, the editor read-only "Entry", Settings without edits). `VEYQ_WINDOWS_DIR='F:\veyq-j1w'
  VEYQ_WINDOWS_TASKS=j1w python3 windows/run.py build` green (Veyq.Tests 84, Bridge tests, PdfWebTest). Bite: `IsDrawn`
  refusing `offline` for `EXPENSES_POT` (on the VM's copy only) → `HeaderPlanTest` red. *Pass* (unpackaged debug build,
  a `--tenant tw-win` throwaway with a member, erased; English): two Pots made in the app — Trip (Taxi with a receipt
  shown, Dinner with one never shown) viewed, Flat (Rent with a receipt never shown) pinned ("Kept") — and a Memory
  with one photo, found by search online. The server cut as ADR 0140's Windows pass did (other sessions' tunnels hold
  the VM's 8080): the app signed in to `tw-win.localhost:18480` over its own `run.py run` tunnel to a forwarder on the
  Mac, both killed by PID (`Invoke-WebRequest` on the VM: no connection); the window closed, the app started again
  offline (*Stay signed in*). Both Pots opened with their entries under "Offline — content as of …", Add entry gone,
  Overview without Record, Settings without edits; Taxi's shown receipt and Flat's pin-fetched one opened, Dinner's said
  "Not available offline"; search found the Memory's photo (never opened in that run) with no "searched once online"
  line. The store (33 files) and every file written under `%LOCALAPPDATA%\veyq`, `%TEMP%` and `INetCache` in the last
  45 min grepped for the entries' titles and the receipts' and photo's PNG markers: no hit (the same read finds the marker
  in the plain file). Signed out offline: the account's store directory gone. Not run: the MSIX, the "searched once
  online" line (every listing was kept), a Pot opened offline that was never kept (`pot-failed`'s offline word).
- [ ] **8 the passes** — § Passes; results into ADR 0153's Consequences.
- [ ] **9 harvest** — ADR 0153's `Built:` names decisions 6 and 7; `native-apps.md`'s offline paragraph; the Use pages
  EN+DE: `getting-started/use-content-offline.mdx` (a Pot), `expenses/index.mdx` or `record-an-expense.mdx` (offline),
  `spaces/search.mdx` (offline); `help/offline.mdx`; `security/what-the-server-sees.md` (a pinned Pot's fetch); inbox
  #898 and #956 deleted. · Done when: every guard but guard_gaps green.

## Passes

Android, Linux, Windows as ADR 0140's (a throwaway, the network cut, the app restarted and unlocked offline): a Pot with
two entries and a receipt viewed and a second Pot pinned → offline both open, the shown receipt opens, the unshown one
says not kept; search offline finds a photo of a Memory never opened in this run; the store grepped for an entry's
note and the receipt's marker (no hit). Apple's are the owner's (named open).

## Owner questions

None: what is kept is decided (ADR 0140 decision 5, owner 2026-11-18: any answer the app can read may be kept sealed),
and the cost is bounded by the automatic tier's limit.

## Definition of done

Phases 1–9 checked; every bite seen red and green; the passes run as throwaways and erased, Apple's named open; the Use
pages EN+DE; every guard but guard_gaps green; `git diff --stat` over `src/backend/`, `src/web/` and `contracts/` empty.

## Retrospective

_(filled at the end of the implementation)_
