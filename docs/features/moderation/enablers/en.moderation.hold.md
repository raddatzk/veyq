# Enabler: The hold

- **ID:** en.moderation.hold
- **Type:** **Enabler** — structural foundation, no standalone end-user goal
  ([features/README → use-case vs enabler vs ADR vs change-request](../../README.md#use-case-vs-enabler-vs-adr-vs-change-request)).
  A **model** enabler: tables, a seam, one skip in the reclaim sweep, and the ends.
- **Status:** **planned** — decided in [ADR 0093](../../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
  § 4; built by cr-report-and-hold.
- **Provides:** a copy of what was reported, taken when it was reported, that no delete, overwrite or revoke reaches —
  the evidence a moderator opens with the keys a report hands over.
- **Serves (use-cases):** [uc.moderation.report](../use-cases/uc.moderation.report.md); later the review and an
  authority's order (ADR 0093 §§ 3, 12).
- **Depends on:** [ADR 0062](../../../decisions/0062-store-object-reclaim-is-a-durable-work-list.md) (every store object
  passes `StoreReclaimSweep`), [en.moderation.moderation-key](en.moderation.moderation-key.md).

No `## User impact`: an enabler has no standalone end-user story.

## What a report hands over

A report share holds, sealed under the report's `K_report` and addressed by its item (`report-share` [itemKind, itemId],
`report-link` [shareId]), a list of keys, each tagged with its role
([`report.ts`](../../../../packages/crypto/src/report.ts)):

| Item | Keys | Role tags |
|---|---|---|
| a node (folder, Memory, chapter, Depot file) | `K_node`, the `K_field` of its name (the name is sealed under the **parent's** key, so `K_node` does not open it), the `K_field` of its meta | `NODE`, `NODE_NAME`, `NODE_META` |
| a link's root node, reported by its guest | as a node, but the root's name is the link's: the `share-memory-name` `K_field` instead of `NODE_NAME` | `NODE`, `SHARE_MEMORY_NAME`, `NODE_META` |
| an application's item on a node — a Memories medium | the `K_blob` of every blob it has (original and derivatives, each with its blob id), its `media-meta` and `media-exif` `K_field`s (a guest's lightbox has no EXIF to hand over) | `BLOB`, `MEDIA_META`, `MEDIA_EXIF` |
| an upload (`WRITE_ONLY`) link | its `K_share` (it opens no content) and its name's and note's `K_field`s | `SHARE`, `SHARE_MEMORY_NAME`, `SHARE_NOTE` |

A `K_field` opens that one revision of that one field
([en.platform.field-key](../../platform/enablers/en.platform.field-key.md)); a `K_node` opens the node and every
descendant whose key is wrapped under it, which is why a node report holds the subtree.

## The tables

All core's, none with a foreign key into a space, a node, a share or a blob — so erasing the reported space, node or
link leaves them standing (ADR 0093 § 4: the report share reads from the snapshot).

| Table | Rows | In the clear |
|---|---|---|
| `veyq_moderation_report` | one per report | epoch, received at, category, the reporter's account (a member) or link (a guest); the sealed part |
| `veyq_moderation_case` | one per reported item | item kind and id, the space, category (the most urgent of its reports), state; `open_key` is the item while the case is open, unique — the one-case-per-item rule held by the database |
| `veyq_report_share` | one per report and case | the report share, opaque |
| `veyq_hold` | one per case at a time | its trigger (`CASE_OPEN`) and `expires_at` |
| `veyq_hold_row` | one per copied row | source table and row id, the row as JSON — ciphertext and metadata, as stored |
| `veyq_hold_pin` | one per pinned store object prefix | storage key, chunk count, `expires_at` (the hold's) |

## The snapshot

Taken in the report's own transaction, through the seam `HoldSnapshotContributor` (phase-ordered, as
`NodeSubtreeEraser` is; core names no application table, ADR 0079):

1. Core resolves the item: a node to its live subtree; an application's item through `ReportableItems`, which the
   owning application implements (Memories: a medium to its node); a link to its share row.
2. Each contributor copies its rows and names the blobs and guest identities they refer to: core the subtree's node
   and attachment rows (phase 200); Memories the subtree's or the item's media, derivative and bucket rows (phase 100).
3. Core copies the named blob rows and pins their storage keys, the named share-identity rows (a guest identity is
   deleted once nothing names it), the space's epoch rows (the wraps a held key chain needs), and for a link its share
   row.

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
| Case dismissed, measure taken, lifted | at once / six months after the statement / with the lift | cr-moderation-review-and-measures, cr-statement-of-reasons-and-objection |
| Child sexual abuse material, an authority's order | ADR 0093 § 4's table | cr-authority-orders-and-art18-reports |

A scheduled purge (`HoldPurge`, which also runs at its first trigger after startup) deletes an expired hold with its
snapshot and pins; the sweep catches up on its own.

## Related

- [ADR 0093](../../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) §§ 1, 4,
  [ADR 0062](../../../decisions/0062-store-object-reclaim-is-a-durable-work-list.md),
  [ADR 0056](../../../decisions/0056-instance-durable-state-and-cold-logical-backup.md) (a backup's store leg lists the
  pinned objects too: `scripts/instance_backup.py`'s `blob_index`),
  [ADR 0079](../../../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md)
- [uc.moderation.report](../use-cases/uc.moderation.report.md)
