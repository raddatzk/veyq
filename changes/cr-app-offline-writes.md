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
- **Order:** after [cr-app-offline-pots-and-space-media](cr-app-offline-pots-and-space-media.md) (a Pot must open offline
  before an entry can be added to it). [cr-app-background-sync](cr-app-background-sync.md) builds on this one's sender.
- **Where what runs:** as cr-app-offline-pots-and-space-media § Context; live cases against the dev backend as a
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

- [ ] **1 core — the outbox** (W2, W6) — the `OUTBOX` kind, the codec, the index, the free-space floor, the header
  re-wrap at a rotation. Tests: an intent round-trips sealed; the directory grepped for a queued note and a queued
  blob's plaintext marker finds nothing; "Clear cache" and eviction leave it; a refused refresh wipes it; a rotation keeps
  it. · Done when: `:core:jvmTest :core:macosArm64Test` green. **Bites:** (a) the outbox in the evictable tier → the
  eviction test red; (b) the rotation's wipe left in place → the rotation test red; (c) a queued blob written as
  plaintext frames → the grep test red.
- [ ] **2 core — `Queued`, the sender, the outcomes** (W1, W3) — the closed list over the core node writes first (folder
  create, rename, trash). Tests per outcome with a played server: sent; `stale_epoch` re-sealed and sent under the new
  key (the body opens under the new node key, not the old); `stale_placement` held, not rebuilt; `404` refused; a
  transport failure leaves it waiting; `id_taken` with the content there is sent, without it held. · Done when: the
  same checks green. **Bites:** (a) the sender sending the sealed body it queued instead of re-sealing → the re-key test
  red (the body opens only under the old key); (b) the rename taking `NodePlacement`'s rebuild → the held test red;
  (c) a never-queue operation added to `Queued` → the derivation test red.
- [ ] **3 core — Memories** — Memory/Chapter create, media add (sealed at once, tiers derived at queue time), capture
  time (a same-field change held, another field merged), media trash, a mark as add/remove. Tests: media into a Chapter
  made offline wait for it; a medium's `K_blob` wrapped at send under the node's key after a re-key while offline;
  the picked file let go once sealed. · Done when: the same checks green; one live case (throwaway) of a queued Chapter
  with two photos sent. **Bite:** the wrap made at queue time → the re-key test red.
- [ ] **4 core — Depot and Expenses** — a new file and trash; a Pot entry add/edit/delete with receipts (receipts
  completed before the revision, as online; the heads the person saw). Tests: a new entry sent; an edit meeting a newer
  revision held with both versions; a delete meeting one held; `quota_exceeded` refused with *Save a copy*. · Done when:
  the same checks green; one live case of a queued entry with a receipt sent. **Bite:** the heads taken fresh at send →
  the heads test red.
- [ ] **5 core — the overlay, the tray, the notices** (W4, W5) — the models' queued rows, the tray's items and actions,
  the banner's count, the reconnect's notice, the asks before a wipe. Tests: each model's state with a queued write
  offline; the tray's actions. · Done when: the same checks green. **Bite:** the per-model offline checks left beside
  `queues` → a model test that expects a queued medium offline red.
- [ ] **6 bridge** — the queue's items, states and actions on the wire; the save dialog's path for *Save a copy*. · Done
  when: `:bridge:jvmTest` green. **Bite:** an item carrying a queued note's text beyond its label, or a key →
  `SecretFreeSchemaTest` red.
- [ ] **7 Android** — the enabled controls offline, the waiting marks, the tray's list and actions, the wipe asks.
  · Done when: `:android:assembleDebug`, the guidelines and words guards green.
- [ ] **8 Apple** — the same. · Done when: both Apple builds green.
- [ ] **9 Linux** — the same. · Done when: `python3 linux/run.py test` and `build` green.
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

## Definition of done

Phases 1–12 checked; every bite seen red and green; the passes run as throwaways and erased, Apple's named open; the Use
pages EN+DE; every guard but guard_gaps green; `git diff --stat` over `src/backend/`, `src/web/` and `contracts/` empty.

## Retrospective

_(filled at the end of the implementation)_
