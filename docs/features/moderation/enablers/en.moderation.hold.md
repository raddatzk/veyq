# Enabler: The hold

- **ID:** en.moderation.hold
- **Type:** **Enabler** — structural foundation, no standalone end-user goal
  ([features/README → use-case vs enabler vs ADR vs change-request](../../README.md#use-case-vs-enabler-vs-adr-vs-change-request)).
  A **model** enabler: tables, a seam, one skip in the reclaim sweep, and the ends.
- **Status:** **built** for a case's holds — decided in [ADR 0093](../../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
  § 4, built by cr-report-and-hold (2026-10-17): `core/moderation/hold` (`Holds`, `HoldSnapshot`, `CoreHoldSnapshot`),
  Memories' `MediaHoldSnapshot`, `StoreReclaimStore.due`'s skip; the reconcile's claim query and the backup's blob index
  read the pins too. The ends of a dismissed, decided, lifted or extended case and the review's reads of the snapshot
  (`CaseSnapshots`, `CaseReviewResource`, Memories' `CaseMediaResource`, Depot's `CaseFilesResource`) by
  cr-moderation-review-and-measures; an authority's order's ends and the floor by cr-authority-orders-and-art18-reports (`AuthorityOrderHandler`);
  a child-sexual-abuse case's bound and its end at the handover by the moderation-retention follow-up of inbox #462
  (2026-10-18: `Holds.moveEnd`'s ceiling, `Holds.capAt`, `CaseDecisions.handoverIn`).
- **Provides:** a copy of what was reported, taken when it was reported, that no delete, overwrite or revoke reaches —
  the evidence a moderator opens with the keys a report hands over.
- **Serves (use-cases):** [uc.moderation.report](../use-cases/uc.moderation.report.md),
  [uc.moderation.review-a-case](../use-cases/uc.moderation.review-a-case.md),
  [uc.moderation.take-a-measure](../use-cases/uc.moderation.take-a-measure.md),
  [uc.moderation.authority-order](../use-cases/uc.moderation.authority-order.md) (ADR 0093
  § 12).
- **Depends on:** [ADR 0062](../../../decisions/0062-store-object-reclaim-is-a-durable-work-list.md) (every store object
  passes `StoreReclaimSweep`), [en.moderation.moderation-key](en.moderation.moderation-key.md).

No `## User impact`: an enabler has no standalone end-user story.

## What a report hands over

A report share holds, sealed under the report's `K_report` and addressed by its item (`report-share` [itemKind, itemId],
`report-link` [shareId]), a list of keys, each tagged with its role
([`report.ts`](../../../../src/packages/crypto/src/report.ts)):

| Item | Keys | Role tags |
|---|---|---|
| a node (folder, Memory, chapter, Depot file) | `K_node`, the `K_field` of its name (the name is sealed under the **parent's** key, so `K_node` does not open it), the `K_field` of its meta | `NODE`, `NODE_NAME`, `NODE_META` |
| a node's name on its own (item `NAME`, its node's id) | the `K_field` of that revision of the name, nothing else | `NODE_NAME` |
| a link's root node, reported by its guest | as a node, but the root's name is the link's: the `share-memory-name` `K_field` instead of `NODE_NAME` | `NODE`, `LINK_NAME`, `NODE_META` |
| an application's item on a node — a Memories medium | the `K_blob` of every blob it has (original and derivatives, each with its blob id), its `media-meta` and `media-exif` `K_field`s (a guest's lightbox has no EXIF to hand over) | `BLOB`, `MEDIA_META`, `MEDIA_EXIF` |
| an upload (`WRITE_ONLY`) link | its `K_share` (it opens no content) and its name's and note's `K_field`s | `SHARE`, `LINK_NAME`, `LINK_NOTE` |

A `K_field` opens that one revision of that one field
([en.platform.field-key](../../platform/enablers/en.platform.field-key.md)); a `K_node` opens the node and every
descendant whose key is wrapped under it, which is why a node report holds the subtree.

## The tables

All core's, none with a foreign key into a space, a node, a share or a blob — so erasing the reported space, node or
link leaves them standing (ADR 0093 § 4: the report share reads from the snapshot). The hold's three tables end by
the table below; the report, the case and the report share go with the case's log ([en.moderation.log](en.moderation.log.md)
§ Retention).

| Table | Rows | In the clear |
|---|---|---|
| `veyq_moderation_report` | one per report | epoch, received at, category, the reporter's account (a member) or link (a guest); the sealed part, and the contact sealed apart (`sealed_contact`, `moderation-report-contact` [epoch]) until six months after the decision was told |
| `veyq_moderation_case` | one per reported item | item kind and id, the space, category (the most urgent of its reports), state; `open_key` is the item while the case is open, unique — the one-case-per-item rule held by the database |
| `veyq_report_share` | one per report and case | the report share, opaque |
| `veyq_hold` | one per case at a time | its trigger, `expires_at`, an order's `floor_until`, a child-sexual-abuse case's `ceiling_until` |
| `veyq_hold_row` | one per copied row | source table and row id, the row as JSON — ciphertext and metadata, as stored |
| `veyq_hold_pin` | one per pinned store object prefix | storage key, chunk count, `expires_at` (the hold's) |

## The snapshot

Taken in the report's own transaction, through the seam `HoldSnapshotContributor` (phase-ordered, as
`NodeSubtreeEraser` is; core names no application table, ADR 0079):

1. Core resolves the item: a node to its live subtree; a name to its node's one row (`HoldTarget.Name`: the name, its
   placement and its editor, and the guest-write records of that name — nothing below it, no blob, no pin); an
   application's item through `ReportableItems`, which the owning application implements (Memories: a medium to its
   node); a link to its share row.
2. Each contributor copies its rows and names the blobs and guest identities they refer to: core the subtree's node
   and attachment rows (phase 200); Memories the subtree's or the item's media, derivative and bucket rows (phase 100).
3. Core copies the named blob rows and pins their storage keys, the named share-identity rows (a guest identity is
   deleted once nothing names it), the space's epoch rows (the wraps a held key chain needs), and for a link its share
   row.

**A hold widens** (`Holds.widen`) when a moderator blocks everything from one uploader
([uc.moderation.take-a-measure](../use-cases/uc.moderation.take-a-measure.md) A8): the same contributors copy the
uploader's items of the space (`HoldTarget.Uploads`: Depot's files with their attachments, Memories' media with their
derivatives and buckets, as the seam `UploaderContent` lists them) into the case's hold alive, and their objects are
pinned to its end — "later-held" by the hold's own rules, so they move, extend and end with it. A row or a pin the hold
already holds is not taken twice; where no hold is alive a new one is taken, ended as a measure's.

## The pin and the sweep

`StoreReclaimStore.due` — the one query every store deletion passes (`validate_store_reclaim_arity.py` holds that every
deleted row's storage key reaches the reclaim list) — leaves out a row whose storage key a pin holds with `expires_at`
in the future. So `defer` is never called on a held key and its `attempts` stay as they were: a skip is not a failed
attempt (ADR 0093 § 4). Every other path keeps running — deletes, trash, overwrites, revokes, a guest's take-back,
a space's erasure — so the hold is invisible by construction, and the tenant's quota falls as the user expects; the
operator pays for the held bytes. When the last pin of a key has expired, its reclaim row is due as it was.

## The ends

| Hold | Ends | Set by |
|---|---|---|
| Case open | 90 days after the report that took it | this enabler (cr-report-and-hold) |
| Case dismissed | at once | cr-moderation-review-and-measures (`CaseDecisions`) |
| Measure taken | six months (`Holds.SIX_MONTHS`, 183 days) after the measure's statement of reasons was sent; after the decision where nobody could be told (no member among its recipients) | cr-moderation-review-and-measures; cr-statement-of-reasons-and-objection (`CaseDecisions.decide`) |
| The case's last measure lifted | at once, and the case closes | cr-moderation-review-and-measures |
| Extended by a moderator | the end the extension names: after the current end, at most six months from now, with a sealed reason | cr-moderation-review-and-measures |
| An authority's order (its own case, no report) | six months after the block (removal), 60 days after receipt (preservation), the days named or else six months after receipt (information) — and that end is the hold's **floor** (`veyq_hold.floor_until`): `Holds.moveEnd` and `Holds.endNow` never end it sooner, so a lift or a decision leaves it to its floor and the case closes only then | cr-authority-orders-and-art18-reports (`AuthorityOrderHandler`, `Holds`) |
| A statement an authority deferred | six months past the deferral's end while it holds; six months after the sending once it went out | cr-authority-orders-and-art18-reports (`StatementDeferrals`) |
| Child sexual abuse material | at the recorded handover to an authority (`HANDED_OVER` or `INFORMATION_GIVEN`), at the latest 90 days (`Holds.CHILD_SEXUAL_ABUSE`) after the report that took the hold or made the case one of this category — its `ceiling_until`: a measure or a statement moves the end at most to it, and a report that makes a decided case one of this category brings a later end back to it. A logged extension may pass it, the handover still ends it; an order's floor always wins (§ 184b(5) StGB: keeping it serves only the report). A hold that runs until the handover past 90 days is not built (ADR 0093 § Legal basis, Still open 1, not pursued) | the moderation-retention follow-up of #462 (`Holds`, `ReportHandler`, `CaseDecisions.handoverIn`) |

**Extending a hold.** A moderator extends a case's hold with a concrete reason, logged as `HOLD_EXTENDED` with the new
end: beside the sealed reason its **type is in the clear** — `AUTHORITY_REFERENCE`, `ART18_REPORT_IN_PROGRESS`,
`OBJECTION`, `LAWYERS_LETTER`, `LAWSUIT` — metadata only. On a child-sexual-abuse case only the first two are accepted
(`CSAM_EXTENSION_REASONS`), and any other is refused `409 extension_reason_not_allowed`; on every other case the type is
optional. No extension ends a hold before an authority's floor.

A hold's rows are copied as the database returns them, but a time column as the instant it is (ISO-8601, UTC,
`HoldSnapshot.text`): a time column holds a UTC wall clock with no zone of its own, and the Art. 18 export read a guest's
write two hours off on a machine in Berlin while that text was read in the JVM's zone.

The moderation cleanup (`ModerationPurge`, hourly, which also runs at its first trigger after startup) deletes a hold
past its end with its snapshot and pins (`Holds.purgeExpired`); the sweep catches up on its own — already from the pin's
end, since the skip asks for a pin whose end is still ahead.

## Related

- [ADR 0093](../../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) §§ 1, 4,
  [ADR 0062](../../../decisions/0062-store-object-reclaim-is-a-durable-work-list.md),
  [ADR 0056](../../../decisions/0056-instance-durable-state-and-cold-logical-backup.md) (a backup's store leg lists the
  pinned objects too: `src/tools/instance_backup.py`'s `blob_index`),
  [ADR 0079](../../../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md)
- [uc.moderation.report](../use-cases/uc.moderation.report.md)
- [cc.privacy.retention](../../../crosscutting/cc.privacy.retention.md) — the hold's ends beside every other period
- [en.moderation.guest-write-record](en.moderation.guest-write-record.md) — the record rows a hold copies
- Docs site: [Report something](../../../../site/src/content/docs/help/report-something.mdx),
  [Moderation (Run)](../../../../site/src/content/docs/run/moderation.md),
  [What the server sees](../../../../site/src/content/docs/security/what-the-server-sees.md)
