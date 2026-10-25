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
   this CR lifts from `features/depot/components/PdfPages.vue` into `shared/` (an application may not import another,
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

1. **Contracts.** Receipt completion and drop (member and guest twins), the receipts in the member and guest reads,
   the 10 MB cap, the re-key stage's receipt part (the ledger's stage operation gains it). **Done when:**
   `npm run generate:api` runs; the contracts guards green; `OperatorBlindProbeTest` classifies the new operations.
2. **Backend.** Decisions 1–4; the fourth blob arm. **Done when:** `./gradlew check` green; slice tests: an 11 MB
   receipt refused at its size (control: 10 MB lands); a session bound to another node or role refused (control: the
   right one lands); a dropped receipt and a deleted entry's receipts reclaimed after the retention and not before, a
   restored entry's kept (a short retention in the test profile); a guest's receipt counts against the link's limit and
   is recorded as a guest write; a blocked receipt's download is `451` for a member and a guest (control: unblocked, it
   presigns). `validate_blob_reference_arity.py` names four arms and is bitten (remove the arm from
   `subtreeBlobIds` → red); `validate_guard_gaps.py --force` green.
3. **Web.** Upload, list, open (decision 7, Depot's view unchanged), remove; guest uploads through core's guest
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

_Filled by the implementer._
