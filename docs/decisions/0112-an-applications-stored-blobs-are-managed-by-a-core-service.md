# ADR 0112 — An application's stored blobs are managed by a core service

- Status: Proposed
- Built: no
- Date: 2026-11-01
- Model: [Applications](../apps/README.md)

## Context

[ADR 0079](0079-veyq-is-a-platform-memories-is-its-first-application.md) decision 3 draws the line between core and an
application: what is around a node is core's, what is behind it the application's and shared with none — except data
management, which is core's (owner, 2026-11-01). This record decides what that exception is.

**What Expenses built** (measured in the tree at `532a71ab6`). [ADR 0108](0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md)
keeps a Pot's entries as sealed revisions in `veyq_expenses_revision` and its receipts as blobs kept with an entry in
`veyq_expenses_receipt` (`src/backend/apps/expenses/src/main/resources/db/migration/V202610250946__expenses_receipts.sql:18-35`):
the receipt's client-minted id, its scope, Pot and entry, the blob, the writer (a member or a link identity, exactly one),
created and dropped. It is the fourth `veyq_blob` arm (`scripts/validate_blob_reference_arity.py`'s docstring pins four by
entity, schema, foreign key and the live share predicate); core's node attachments could not hold it, being one blob per
named slot of a node (`ux_attachment_node_slot`, `V202610041200__core_baseline.sql:485`). Around that table Expenses
carries every platform duty a stored blob has: the completion, the member roles and the guest upload, the link's usage,
the share's blob predicate (`…/expenses/receipt/ReceiptBlobSeams.kt`), the retention purge with its two-layer cutoff
(`PotReceiptPurge.kt`), and a receipt half in the report's reach, the hold, "everything from this uploader"
(`…/moderation/PotModeration.kt:42-129`), the re-key and the four erasers (`…/pot/`). The receipt package alone is 811
lines in six files (`wc -l src/backend/apps/expenses/src/main/kotlin/me/raddatz/veyq/expenses/receipt/*.kt`), the web's
transport another 263 (`src/web/src/features/expenses/receipts.ts`).

**Why now.** Inventory ([docs/apps/inventory.md](../apps/inventory.md)) needs pictures per item, the third application to
store blobs of its own. Each of those duties is a legal or a platform one — erasure (GDPR), a report's hold (DSA),
reclaim of what nothing names, quota — and an arm one of them misses deletes or serves live data, which is why the arity
guard exists. Copied per application, every copy is one more place to miss.

## Decision

**1. Core keeps one service for the blobs an application stores: the receipt arm, generalised.**
`veyq_application_blob` holds (blob id, scope, node, owner, role, blob, writer, created, kept, released): any number of
blobs per node, each in a role its application declares, each held by an **owner** — an application-side id, opaque to
core (Expenses' entry id), which core stores and compares and never resolves. The blob id is client-minted; the
`K_blob` stays wrapped by `blob-key` under the node's key, bound to (blob, node, role) as a receipt's is today — no new
purpose, and the crypto suite stays core's toolbox, from which the application picks what it seals. **The service carries
every platform duty over these blobs**, by implementing the seams core already has: the completion (`UploadCompletion`),
the guest upload (`ShareGuestWrites`), the link's usage (`UploadAdmission`), the share's blob predicate
(`SharedSubtreeContributor`), a blob's report and block (`ReportableItems`: a blob is an `ITEM`), the hold
(`HoldSnapshotContributor`, [ADR 0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)),
"everything from this uploader" (`UploaderContent`, `HeldUploaders`), the re-key of every wrap
(`NodeRekeyParticipant`, [ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md) decision 3), the erasers
(subtree, scope, account, guest identity), the retention purge, the reclaim of their objects
([ADR 0062](0062-store-object-reclaim-is-a-durable-work-list.md)) and the reading of their bytes an application's export
names ([ADR 0082](0082-a-node-and-everything-under-it-exports-as-a-plaintext-zip-and-imports-under-the-importer.md)).
`veyq_application_blob.blob_id` is the fourth arm, so the arm count stays four for every application to come.

**2. What an application implements: its roles, and which of its rows keeps a blob.** It declares its roles with their
caps (the `MemberBlobRoles` seam, plus a count per owner — Expenses' 100 receipts — and whether a link guest may add one)
and answers, at a completion, whether the owner it names may hold a blob in that node (Expenses: the entry is no other
Pot's, else `id_taken`). In its own write's transaction it tells the service which blobs an owner now **keeps** and
which it **released** — Expenses keeps what a revision lists, releases what `droppedReceipts` names and everything of an
entry its tombstone ends, and keeps them again on a restore. The service's purge erases a blob once the node trash
retention has passed since its release, or since its completion where nothing ever kept it: `PotReceiptPurge`'s rule,
which reads the application's revisions today and reads only the service's own rows then, so the purge takes no
application lock and judges each row again under its own. A report of the application's own record asks the service for
its owner's blob wraps, and blocks them with it.

**3. The wire and the web transport are core's.** Core's spec gets the service's operations — a member's completion
(role, owner), a node's list (id, owner, role, size, writer, times, its purge time), their link counterparts under
`/api/shares/{shareId}/…`, and the moderator's case read of a blob; the download is core's already. `receipts.ts`'s
transport moves into core's blob transport on the web. The owner in a request is an opaque id, as the entry id in
today's receipt routes is, so the access log learns nothing new ([ADR 0092](0092-logs-are-operational-and-name-no-person.md)).

**4. Everything behind the Pot stays Expenses'.** Its revision table and store, the compare-and-set, the heads graph and
its check, the codec, the entry format and its envelope, the purposes `expense-entry-key` and `expense-entry` with their
vectors, the erasure of a participant, its own halves of the report, the hold and the re-key over its revisions, its
receipt rules and screens — none of it moves, none of it changes. A revision's sealed `receipts` list keeps naming the
same client-minted ids, which are now the service's.

**5. Expenses moves its receipts onto the service, and its data is discarded, not migrated.** No instance is live, and
the owner chose a reset of Expenses' data on the dev instances over a migration. Core's migration creates
`veyq_application_blob`; Expenses' drops `veyq_expenses_receipt`. **That migration deletes no row: it refuses to run while
the table holds one.** The reset before it is a manual step the move's CR documents for each dev instance — every Pot
deleted and purged from its space's trash (`purgeNode`, [ADR 0061](0061-node-deletion-reversible-node-trash.md) decision 6),
or the dev instance reset whole with its store volume. The purge is today's erasure, one erase with two triggers: the
revisions, the receipts, their blob rows with the store objects on the reclaim list, the Pots' links and guest
identities, the measures on them; a hold keeps its copy. *Why not in the migration:* deleting in SQL would be a second
copy of that erasure — a reclaim row only for an object's last blob row, the measures that end with the object, the
guest-write records, the links — that no arity guard watches; a bare `DROP` would leave the receipts' blob rows with no
arm and their objects in the store, found only by the bucket reconcile, and only once the Server Owner confirmed the
bucket exclusive. The refusal makes a forgotten reset a failed start, not a leak. Expenses' receipt tests move to the
service's operations; its other tests pass unchanged.

**6. The operator sees nothing new.** Per blob: size, role, owner, writer, times and its kept or released state — what a
receipt row shows today, its entry id now named the owner.

## Consequences

- **Expenses shrinks** by the receipt package and the receipt halves of `PotModeration`, the re-key and the erasers;
  its receipt paths leave its spec for core's.
- **Inventory stores its pictures through the service** once refined, and adds no arm and no blob seam of its own.
- **Memories' media and derivatives keep their two arms** for now, their duties carried by Memories' own
  implementations of the same seams; moving them onto the service is the boundary's next step and its own record, since
  a derivative's rows are not a receipt's.
- **Guards follow the move**: `validate_blob_reference_arity` re-points its fourth arm to
  `ApplicationBlobEntity.blob_id` / `veyq_application_blob.blob_id`; `validate_node_rekey_arity`,
  `validate_scope_erasure_arity`, `validate_account_erasure_arity` and `validate_measure_target_erasure` name the
  service's table where they named the receipts'; `ModuleCompositionTest` counts the seams Expenses no longer implements.
- **Costs accepted**: an owner id core stores without understanding, a keep/release call the application must make in
  every write that changes what a record keeps, and every Pot on the dev instances gone at the move.
- **On acceptance**, ADR 0108 gets `- Replaced in part:` for decision 7's receipt table, and
  [Applications](../apps/README.md) takes the service's current picture.

## What the server sees

Unchanged: decision 6. It reads no blob and no sealed body; the owner is an id it already held as a receipt's entry.

## Left for Inventory

Inventory owns its records — items, loan entries, their revisions, who may write them, what goes when an owner leaves,
which item an entry belongs to — in its own tables, as Expenses does; its pictures are blobs it registers with this
service ([docs/apps/inventory.md](../apps/inventory.md)).

## Rejected alternatives

- **A core template for an application's records** (revisions, the compare-and-set, a writer policy, a departure policy,
  core routes and a web module every application instantiates). Rejected by the owner: what is behind a node belongs to
  the application and is not shared (ADR 0079 decision 3). A helper library that owns nothing stays allowed.
- **Copy the blob seams per application** (Inventory copies Expenses' receipt package). The duties are the platform's
  and the law's; re-implemented per application, each copy is one more erasure, hold and reclaim to get right and one
  more set of names every arity guard must watch.
- **A fifth blob arm per application** (Inventory's own picture table). Every arm is one more name the reclaim
  predicate, the share's blob predicate, the subtree eraser and the hold must enumerate; one service arm keeps the count
  fixed.
- **Core's node attachments widened** to many blobs per slot. Their one-per-slot rule is what a cover's compare-and-set
  replace rests on (`AttachmentStore`), and an application's blob is held by its owner and released by it, a node's slot
  by neither.
- **Migrating Expenses' receipts** (an `INSERT … SELECT` into the service's table, every blob byte-identical). It would
  rehearse an upgrade, but no instance is live, and the owner judged a reset simpler than a migration for data nobody
  keeps.
