---
id: cr-expenses-receipts
title: Expenses — receipts kept with an entry
status: planned
satisfies: ["uc.expenses.attach-receipts"]
adrs: ["0108", "0109", "0093", "0013", "0073", "0061"]
invariants:
  - operator-blind — the server sees a receipt's size and which entry holds it, never its type or content
  - a receipt's K_blob is wrapped under the Pot's key and bound to blob, node and role; no new blob purpose
  - no guest action erases a receipt at once; a dropped one goes only after the trash retention
  - core never calls an application; Expenses joins the blob seams, never the reverse, and imports no other application
  - every text follows docs/crosscutting/ui-web-styleguide.md § Copy
---

## Goal

The second of Expenses' three CRs (owner 2026-10-23): images and PDFs of at most 10 MB kept with an entry, any number
per entry, in Expenses' own table and filled through core's `UploadCompletion`
([ADR 0108](../docs/decisions/0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md) decision 7,
[uc.expenses.attach-receipts](../docs/features/expenses/use-cases/uc.expenses.attach-receipts.md)).

## Entry condition

`cr-expenses-ledger` has landed: `test -f changes/cr-expenses-ledger.md && echo "ledger CR still open"`. Its revision
format already lists the receipts an entry holds, and its Pot page has their place; this CR re-reads both before the
first edit. Independent of `cr-expenses-exchange-rates`; the two may run in either order, not side by side in the same
files (`src/web/src/features/expenses/i18n.ts`, the editor).

## Context & constraints

- **The binding is core's.** A receipt's wrap is the existing `blob-key` seal with context (scopeId, ownerId = the Pot's
  node id, blobId, role) under the role token `EXPENSES_RECEIPT` (16 characters, the column's limit). Expenses'
  completion calls `UploadSessionEntity.boundTo(ownerId, role)` itself, as `MediaUploadCompletion` does, because it
  does not use `AttachmentStore` (core's attachments hold one blob per named slot, `ux_attachment_node_slot`).
- **The retention is core's node retention** (`veyq.node.trash.retention`), as for the ledger's deleted entries; no
  key of Expenses' own.
- **Measured at refine (329b92cf):** `validate_blob_reference_arity.py` pins exactly three `veyq_blob` arms by name
  (`MediaDerivativeEntity`, `MediaEntity`, `NodeAttachmentEntity`); the one store delete is `StoreReclaimSweep`, every
  purge enqueues into `veyq_store_reclaim` (`validate_store_reclaim_arity.py`).

## Decisions this CR takes

1. **The receipt table** holds receipt id, entry id, blob id, writer, `node_id`, `scope_id`, time. A receipt is
   attached by its completion and listed by the entry's revisions; a revision that drops one names it, which stamps the
   row's drop time.
2. **The purge** reclaims a dropped receipt and every receipt of a deleted entry once the retention has passed since
   the drop or the tombstone, through the reclaim queue; a restore within it clears the stamp.
3. **The re-key** re-wraps each receipt's `K_blob` in Expenses' participant, beside the revisions' `K_rev` wraps
   (ADR 0109 decision 3).
4. **A report of an entry carries its receipts' `K_blob`s; a receipt is also an `ITEM` on its own** (ADR 0108 decision 6);
   the hold pins each receipt's blob.
5. **A content block reaches a receipt, and an entry's.** The ledger refuses nothing on a blocked entry: an entry has no
   blob and its list carries no block mark (`cr-expenses-ledger` phase 2). A moderator's content block of a receipt, or
   of the entry that holds it, answers `451 blocked_by_operator` for its bytes, member and guest alike, and the list
   marks it, as Memories' media do (`ShareMediaWire.media`'s `blockedByOperator`); the web draws "Blocked by the
   operator". A block of the Pot's node is inbox #542's, not this CR's.
6. **Past the retention, no restore.** `uc.expenses.entry-history` E2 is built here, with the purge: a deleted entry
   past `veyq.node.trash.retention` is no longer offered a restore, so the read carries when its receipts go (the
   tombstone's time plus the retention, derived at the resource boundary as `content.openapi.yaml`'s purge time is), and
   its criterion (`past-the-retention-a-line-without-receipts`, which `cr-expenses-ledger` left out of the coverage for
   want of a test) comes back under that use-case beside its test.
7. **Rendering through a shared viewer.** An image renders in the client; a PDF through Depot's page renderer, which
   this CR lifts from `features/depot/components/PdfPages.vue` into `shared/` — built: `shared/pdf/` (an application may not import another,
   and the reuse rule forbids a second renderer); anything else is a download.

## Inventory

| Area | What changes | Locator |
|---|---|---|
| Blob seams | `UploadCompletion`, `UploadAdmission`, `MemberBlobRoles`, `ShareGuestWrites` for `EXPENSES_POT` | `grep -rln "UploadCompletion\|UploadAdmission\|MemberBlobRoles\|ShareGuestWrites" src/backend/apps --include='*.kt' \| grep -v /test/` |
| Blob arms | a fourth arm and its live twin | `python3 scripts/validate_blob_reference_arity.py`; `grep -rn "subtreeBlobIds" src/backend --include='*.kt'` |
| Purge | through the reclaim queue | `grep -rn "veyq_store_reclaim\|purgeTrashedBefore" src/backend --include='*.kt' \| grep -v /test/`; `python3 scripts/validate_store_reclaim_arity.py` |
| Composition test | the upload owner map | `grep -n "upload" src/backend/src/test/kotlin/me/raddatz/veyq/app/ModuleCompositionTest.kt` |
| Moderation | receipts in the entry's report and on their own; blob pins | `grep -rn "snapshot.blob(" src/backend --include='*.kt' \| grep -v /test/` |
| Link limit | receipts count | `grep -rn "storageLimit\|storage_limit" src/backend/core/src/main --include='*.kt' -l` |
| PDF viewer lift | `PdfPages.vue` to `shared/` | `grep -rn "PdfPages" src/web/src` |
| Site | the receipts paragraph | `git grep -ln "uc.expenses.record-entries" -- site/src/content/docs` |

## Phases

1. **Contracts.** ✅ **Built 2026-10-25**, landed with phase 2 (§ Retrospective). Receipt completion and drop (member and guest twins), the receipts in the member and guest reads,
   the 10 MB cap, the re-key stage's receipt part (the ledger's stage operation gains it). **Done when:**
   `npm run generate:api` runs; the contracts guards green; `OperatorBlindProbeTest` classifies the new operations.
2. **Backend.** ✅ **Built 2026-10-25** (§ Retrospective). Decisions 1–4; the fourth blob arm. **Done when:** `./gradlew check` green; slice tests: an 11 MB
   receipt refused at its size (control: 10 MB lands); a session bound to another node or role refused (control: the
   right one lands); a dropped receipt and a deleted entry's receipts reclaimed after the retention and not before, a
   restored entry's kept (a short retention in the test profile); a guest's receipt counts against the link's limit and
   is recorded as a guest write; a blocked receipt's download is `451` for a member and a guest (control: unblocked, it
   presigns). `validate_blob_reference_arity.py` names four arms and is bitten (remove the arm from
   `subtreeBlobIds` → red); `validate_guard_gaps.py --force` green.
3. **Web.** ✅ **Built 2026-10-25**, the download, the import and the copy in a slice of their own (§ Retrospective, phase 3
   and its receipts-in-the-download slice). Upload, list, open (decision 7, Depot's view unchanged), remove; guest uploads through core's guest
   transport; the receipts in the download, the import and the copy; the report keys; a blocked receipt drawn as
   blocked; no restore past the retention (decision 6). Site: `expenses/record-an-expense`
   gains receipts (EN+DE). **Done when:** `npm run check` and `site` `npm run check` green; a **browser pass** on the
   ledger CR's instance: a JPEG and a PDF render, a `.txt` receipt is only offered for download, a member and a guest
   each add one, a removed one leaves the entry, a rotation leaves every receipt opening, Depot's PDF view still renders.
4. **Close.** `uc.expenses.attach-receipts`' coverage from `planned` to refs, and `uc.expenses.entry-history`'s
   `past-the-retention-a-line-without-receipts` back beside its test; the receipt half of `uc.expenses.create-pot`'s
   `a-deleted-pot-goes-to-the-trash-whole` (its criterion gap); ADR 0108 and docs/apps say what is built.
   **Done when:** every `scripts/validate_*.py` exits `0` (`validate_guard_gaps.py --force` too).

## Definition of done

Receipts are attached, opened, removed and reclaimed as the use-case says; the blob guards name the new arm and are
bitten; the site's Use page says it in EN and DE.

## Open questions (owner)

None open.

## Retrospective

**Entry condition, checked 2026-10-25 on `main` `e02db2d0`:** `changes/cr-expenses-ledger.md` is gone — the ledger has
landed. The revision format lists `receipts` by receipt id (`contracts/expenses/entry.v1.schema.json`, `maxItems` 100).

**Phases 1 and 2 (2026-10-25), one landing.** The contracts phase adds operations to the existing `expenses` and
`share-expenses` tags, so `PotsResource` / `SharePotsResource` stop compiling without the backend (changes/README.md):
both phases land together.

- **The wire.** `listPotReceipts` / `completePotReceipt` (member) and `listSharePotReceipts` / `completeSharePotReceipt`
  (guest) under `…/pots/{nodeId}/receipts`; `PotRevisionWrite.droppedReceipts` (optional) is the drop, on both append
  twins; `PotRevision` / `SharePotRevision` gain `purgeAfter` on a tombstone (decision 6's read); `PotRekeyPage.receipts`
  and `PotRekeyStage.receipts` (optional; `revisions` now `minItems: 0`, at least one value in all); the case listing
  gains `receipts`. Every new request field is optional, so the web compiles unchanged until phase 3.
- **The cap is the role's, in core.** `MemberBlobRoles.maxBytes(role)` (default none) is asked by core's
  `BlobUploadHandler.admissibility` on every start, member and guest: Expenses caps `EXPENSES_RECEIPT` at 10 MiB of
  plaintext = 10 486 880 sealed bytes (40 frames), `budget_exceeded` before any session or quota. A core change for an
  application's need, but a seam core asks, never an application core names.
- **A link's usage is every application's term summed** (`StorageQuota.linkUsage`): measured by the target's admission
  alone, a folder link holding both a Memory and a Pot would have counted its limit once per application — the open
  question the KDoc left for "the first second application". The target's admission still gates (fail closed). ADR 0073
  § 4 says so.
- **The purge reads the latest revision, it stores no tombstone stamp.** A receipt is due once the retention has passed
  since its drop (`dropped_at`), since its entry's latest revision if that is a tombstone, or since its completion if its
  entry never got a revision (an entry whose first revision never came — the completion accepts an entry with no revision
  yet, so this is the only way such a row ever goes). A restore is a later non-tombstone revision, so it needs no write
  to the receipts. `PotReceiptStore.purgeAfter` is the rule, served as each receipt's `purgeAfter` and judged by the
  purge. One transaction per scope, the scope row first, then revisions → receipts → node rows → blob rows.
- **A dropped receipt is never held again.** A revision cannot name it back; a client that wants it uploads it again.
- **A receipt id and a record id share the reportable-item id space** (`PotReportableItems.locate` answers both), so a
  completion refuses a record's id and an append of a new record a receipt's (`id_taken`).
- **Bites** (a script that applies one sabotage, runs the named test, restores — 16, each red): the cap removed from
  Expenses' roles, the cap check removed from core's start, the session's binding unchecked, a tombstone's restore
  ignored, the cutoff ignored (in both of its layers, the candidates' query and the re-judging under the locks: either
  alone still holds, so the single-layer sabotage stayed green), the link's receipts not counted, the guest write not recorded, an entry block not
  reaching its receipts, the receipt arm left out of the share's blob arms (the guest's download and
  `validate_blob_reference_arity.py`), a drop of another record's receipt, a receipt's wrap not required by the re-key,
  the hold not pinning a receipt's blob, a purged Pot not freeing its receipts' blobs, the restore race's re-judging under
  the locks, and the link usage summing the target's term only.
- **Measured:** `./gradlew check` green (root 205, `:core` 1671, `:apps:memories` 442, `:apps:depot` 83,
  `:apps:expenses` 31 tests); every quick `scripts/validate_*.py` 0 and `validate_guard_gaps.py --force` 0 (two guards
  changed: the blob arity names four arms, the kernel-import rule names `EXPENSES_RECEIPT`); `npm run generate:api`
  runs; `src/web` `npm run typecheck` 0 after one typed fixture gained `receipts: []`
  (`features/expenses/rekey.test.ts`: `PotRekeyPage.receipts` is a required response field), its 216 Expenses tests green.
- **Not run:** `scripts/run_backend_races_on_mariadb.py` (Docker; `PotReceiptRaceSliceTest` is new and runs there in the
  V1 heavy stage).

**For phase 3.** The member read is `GET /api/scopes/{s}/pots/{n}/receipts` (paged by `after` = the last receipt id,
`limit`, `more`, `cursor`; a first page carries an `ETag` digest, `If-None-Match` → `304`); the guest's is the same under
`/api/shares/{shareId}/pots/{n}/receipts` (no `writer`). Every receipt change moves the Pot's `ledgerRevision`, so the
client re-reads receipts when its revision poll answers `200`. Upload: core's `startBlobUploads` (guest:
`startShareBlobUploads` with `nodeId` = the Pot) with `role: EXPENSES_RECEIPT`, `ownerId` = the Pot's node id, the
`K_blob` wrapped under the Pot's key bound to (blob, Pot, role), the file's type and name in `wrappedBlobMeta`; windows as
usual; then `completePotReceipt {nodeEpoch, entryId, receiptId (client-minted), uploadId}` → `200 PotReceipt`. Errors:
`400 budget_exceeded` (verify), `404 upload_not_found` / `node_not_found`, `409 id_taken` (receipt id taken, or the entry
is another Pot's) / `stale_epoch` / `node_rekey_pending` (upload stays open), `422 upload_mismatch` /
`capability_disabled`; the start answers `budget_exceeded` per item over 10 MiB. Download: core's
`presignBlobDownloads` by `blobId` (a block: the item's `blocked_by_operator`), a guest's `resolveShareBlob` (a block:
`451`). Remove: the entry's next revision lists the receipts it holds and names the dropped ones in `droppedReceipts`.
Re-key: `listPotRekey` pages revisions first, then receipts (`{receiptId, wrappedBlobKey}`); stage them in
`PotRekeyStage.receipts`; a receipt missed is `rekey_parts_missing`. Report: an entry's report share carries its receipts'
`K_blob`s beside the `K_rev`s (the 32-key gap, ADR 0108 decision 6); a receipt is an `ITEM` by its receipt id.

**Phase 3 (2026-10-25), built on `main` `e11f9c1e`.** Two commits: the lift, then the receipts.

- **The lift (decision 7).** Depot's renderer is core's `src/web/src/shared/pdf/` — `pdfEngine.ts`, `pdfOptions.ts`,
  `pdfjsRuntime.ts` (still the one module importing pdf.js, reached only by the dynamic `import()`), `PdfPages.vue` and its
  stylesheet `pdf.css` (Depot's `depot.css` rule for rule, imported by `PdfPages.vue`, so whoever mounts it gets it);
  `PdfPages`' eight keys are core's `pdf.*`, the password prompt stays Depot's. Depot's view is unchanged (its suites green,
  `verify:pdf-worker` green); guard gap 90 now plants its dead rule in Expenses' sheet, as Depot has none left.
- **The wire** is `features/expenses/receipts.ts`: the upload through core's `planBlob` (owner = the Pot, role
  `EXPENSES_RECEIPT`, type, name and time sealed in `wrappedBlobMeta`) and the authed or guest pump, then the completion;
  the listing (first page on its tag); the bytes through `presignBlobDownloads` or the link's `resolveShareBlob`
  (`ReceiptSource`, one shape for a member, a guest and a moderator). The rules without network are
  `domain/receiptRules.ts`: what may be added (an image or a PDF by its claim — only the client can keep the type), how an
  opened one is drawn (by its BYTES: a raster image as `<img>` typed by the sniff, a PDF through `shared/pdf`, anything
  else — an SVG, a text that claims to be a PDF — only offered for download), which receipts a revision drops, and the
  restore cut-off.
- **The drop rule** (`receiptsToDrop`): a revision names every receipt of its entry the listing still holds and it does not
  keep that the revision it was written on held, that this writer uploaded, or that was completed before that revision — an
  orphan of a save that failed after its upload, which no later revision could hold and which would otherwise stay with a
  live entry for good. Another writer's receipt completed after it is left alone (it may be on its way into that writer's
  revision, which is then stale anyway). `sealPotWrites` refuses a revision that keeps a receipt it drops.
- **The editor** (`EntryEditor` + `EntryReceipts`): what is picked is uploaded with the save, before the revision that lists
  it; a new entry's id is minted when its dialog opens, so its receipts complete against it. A receipt the link's limit
  or the quota has no room for is left out and the entry saves without it, saying so (E2); any other failed upload stops
  the save, and what was uploaded is kept for the retry. After a stale save the receipts this dialog added stay in the
  form. Opened in place (`ReceiptView`), downloaded, reported on its own; a blocked one is listed as blocked with nothing to
  open. A protected PDF is not prompted for: it is offered for download.
- **The session** re-reads the receipt listing (on its own tag) whenever the revision read answered with a change; a failed
  receipt read leaves the ledger standing and is retried on the next read. `purgeAfter` is carried on each held revision;
  a deleted entry says until when it is restorable and, past it, offers no restore (decision 6).
- **Re-key** re-wraps each receipt's `K_blob` with core's `rewrapBlobKey`; the re-key listing names a receipt by id only,
  while its wrap is bound to the blob id, so the blob ids come from the receipt listing (read once, and once more for a
  receipt completed since); an unknown one fails the node.
- **Report**: an entry's share carries the version on screen's revision key, then its receipts' `K_blob`s (role
  `EXPENSES_RECEIPT`, ref = the blob id), then the older revisions, then any other receipt of it, to the cap of 32 (#540
  stays). A receipt is reported on its own as an `ITEM` by its receipt id with its `K_blob` alone. The case view
  (`PotEntryReview`) lists the hold's receipts of the reported entry or the reported receipt and opens each with its handed
  key from the hold (`presignCaseBlobs` through the review's `chunkUrls`); one whose key the share did not carry is listed
  as one the report cannot open.
- **Site:** `expenses/record-an-expense` (EN+DE) gains § Receipts and the restore's limit.
- **Bites** (a script that applies one sabotage, runs the named tests, restores — 30, each red): a READ guest offered the
  remove and the picker; the 10 MiB cap and the type claim gone at the pick; the upload after the revision; a new entry's
  receipt completed under another id; the drop rule naming nothing, and dropping another writer's newer upload; the
  drop-and-keep refusal and `droppedReceipts` gone from the wire; a blocked receipt offered its open; a blocked view reading
  anyway; a `451` not read as blocked; a restore past the retention; an earlier tombstone's time surviving a restore;
  `purgeAfter` not carried from the wire; a claimed type deciding the view; the re-key leaving receipts unstaged, binding the
  wrong owner, skipping an unknown receipt; an entry's report without its receipts, a receipt key without its blob ref; the
  case view showing every receipt of the case, opening one with another blob's key; the guest's upload through the
  member's door; the wrap bound to the scope, not the Pot; the meta not sealed; no re-read on a moved ledger, a re-read on
  every `304`; no room failing the save.
- **Measured:** see the commit messages (`src/web` `npm run check`, `tests/e2e` `npm run check`, `site` `npm run check`, the
  quick `scripts/validate_*.py`, `validate_guard_gaps.py --force` for the re-anchored gap 90).

**Phase 3's receipts-in-the-download slice (2026-10-25), built on `main` `519ca0d2`.** Phase 3 left the receipts out of
the download, the import and the copy (the export wrote `receipts: []`); this slice closes that gap.

- **The contract.** `contracts/export/veyq-subtree.schema.json`: `expensesEntry.receipts` (optional, `maxItems` 100) holds
  the receipts the entry's current revision holds, each an `expensesReceipt` — `id` (the one `entry.receipts` lists),
  `name` (optional, the sealed name, ≤ 1024) and `file`, a `payload` of at most 10 MiB. Exactly the listed ids, each once;
  one the download cannot read is left out of both. The content schema stays `expenses` 1: Expenses has not been released,
  so no archive of an older shape exists, and a 1 without `receipts` still imports.
- **The export** (`exportImport.ts`' `withReceipts`) reads the listing through the session's own door only where a live
  entry holds a receipt, and declares each held receipt as a payload with its frames (`CHUNK_SIZE`, the upload's), its size
  the exact plaintext (`receiptSize`), its name and type from the sealed blob meta opened on the device. A receipt the
  listing holds dropped, blocked or for another entry is left out of the entry's list with it, and its bytes are never
  read; its `K_blob` is unwrapped at the walk, so a row moved here from another Pot refuses it. ADR 0082's model holds:
  the plaintext reaches only the person's own save target, or in a copy the tab's frame window before it is sealed again.
  The ZIP's length needs nothing of Expenses: core's `zipLength` plans every declared payload, and the test pins it with
  the receipts in.
- **The import** splits `createPot` into `createPotNode` + `appendInBatches(…, beforeBatch)`: each batch's receipts are
  uploaded into the new Pot (`uploadReceipt`, a fresh `K_blob` bound to it) and completed against the entry's new id before
  that batch's append, under new ids minted in a map apart from the records' (an archive's receipt id equal to a record id
  still gets one of its own); the revision lists the new ids. An import that fails part-way trashes the Pot it made (it
  left an empty or half Pot before when the Pot was the import's top node, whose id core never learns). `validatePotContent`
  refuses a receipt over 10 MiB, one that claims no image or PDF (the picker's `claimsReceipt`), a list and a carried set
  that differ, a receipt carried twice, and a malformed one, before anything is written. `ReceiptFile` is now any
  `BlobSource` with a name and a time, so the import hands it the archive's or the copy's payload.
- **The copy** carries the receipts; `expenses.copyLoss` names only the history and the deleted entries (EN, DE, and the
  e2e labels). `uc.platform.copy-node`'s web gap ("no wire test copies a Pot through prepareCopy") is closed by the new suite.
- **Tests** (red before, green after): `exportImport.receipts.test.ts` — core's real walk, ZIP writer, import and copy with
  Expenses' registered hooks and core's real blob transport against a fake control plane and store: the ZIP's announced
  length equals its real length with the receipts; the import's balances equal and every receipt opens byte-identical
  under the new Pot, its name and type kept, its wrap bound to the new Pot only, each completed before the append that
  lists it; a copy the same; a blocked, a dropped and another entry's receipt left out unread; a failed upload trashes the
  Pot. `exportImport.test.ts` — the receipt rules of `validatePotContent` and their schema limits.
- **Bites** (a script that applies one sabotage, runs the two suites, restores — 18, each red): no receipt exported; a
  blocked, a dropped, another entry's receipt exported; the sealed name, the sealed type not carried; the payload declared
  at its sealed size; frames of another size; the receipts uploaded after their batch; the old receipt ids kept; the
  revision listing the old ids; no cleanup of a failed import; the 10 MiB cap, the claim, the listed set, a receipt carried
  twice, the name length not checked; the copy loss still naming the receipts.
- **Measured:** see the commit message.

**For phase 4.** The L1 additions are written and NOT run (`tests/e2e/tests/features/expenses-pot.spec.ts` steps 2b, 4b,
9b; the PDF builder moved to `tests/e2e/src/pdf.ts`, shared with `depot-styles.spec.ts`; labels and `addReceipts` /
`receiptRow` / `expectReceiptOpens` in `tests/e2e/src/expenses.ts`). The checklist the stack-lane pass proves, EN and DE:
- 2b: a `.txt` is refused at the picker; a JPEG is drawn as an image (decoded, `naturalWidth > 0`), a PDF as a drawn page
  (`.pdf-page canvas`), a text named `….pdf` only offered for download (no `img`, no `canvas`); removing it leaves the
  entry after the save; no figure moves, no ledger notice;
- 4b: the READ_WRITE guest keeps a JPEG with its own entry through the link and opens A's JPEG and PDF;
- 9b: after the rotation the receipts open for A and through the link;
- step 7 (NOT yet written into the spec — phase 4 adds and runs it): the downloaded ZIP holds `<Pot>/<receipt name>` for
  Museum's JPEG and PDF and the guest's JPEG, and not the removed false PDF; the imported Pot and the copy each show
  those receipts on their entries, the JPEGs opening as images, the PDF as a drawn page; the copy dialog's losses no
  longer name receipts;
- Depot's PDF view still renders: `depot-styles.spec.ts` (unchanged but for the shared builder) reads its text layer;
- the canary sweep: every receipt name and the PDF's line are `recordTyped`, so the sweep proves none reached the server
  readable;
- not driven in a browser: the blocked state, the restore cut-off, a receipt reported on its own and the case view's
  receipts (unit-tested in `PotDetail.receipts.test.ts` and `PotEntryReview.test.ts`; a block or a retention past its time
  needs a moderator measure or a short retention the lane does not set).
Phase 4 also moves `uc.expenses.attach-receipts`' coverage from `planned` to refs — web: `domain/receiptRules.test.ts`,
`receipts.wire.test.ts`, `components/ReceiptView.test.ts`, `components/PotDetail.receipts.test.ts`,
`components/PotDetail.guest.test.ts`, `components/PotEntryReview.test.ts`, `rekey.test.ts`, `report.test.ts`,
`potSession.receipts.test.ts`, `domain/potApi.wire.test.ts` — and writes `past-the-retention-a-line-without-receipts`
back beside `PotDetail.receipts.test.ts`' restore case and `receiptRules.test.ts`' `purgeAfterOf and restoreOffered`.

