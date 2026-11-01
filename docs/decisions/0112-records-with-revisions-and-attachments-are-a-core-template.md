# ADR 0112 — Records with revisions and attachments are a core template an application instantiates

- Status: Proposed
- Built: no
- Date: 2026-11-01
- Model: [Applications](../apps/README.md)

## Context

[ADR 0108](0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md) built Expenses' entries as sealed
revisions and its receipts as blobs kept with them, all inside `:apps:expenses`, and left the core piece for later: *a
generic "record with attachments" primitive in core waits for a third concrete user* (its Consequences, lines 184-185).
Inventory ([docs/apps/inventory.md](../apps/inventory.md)) is that user, and it needs the same two things again.

**What Expenses built** (measured in the tree at `532a71ab6`):

- **Records.** One row per revision in `veyq_expenses_revision`
  (`src/backend/apps/expenses/src/main/resources/db/migration/V202610242200__expenses_revisions.sql:19-45`): a
  client-minted record id and its revision, unique together; server-visible `tombstone` and `erasure` flags; the sealed
  body and the wrapped `K_rev`; the padded size class; exactly one writer column of two (`ck_expenses_revision_writer`, a
  member's user id or a link identity); the list position core's `items.bump` returned. `PotRevisionStore.append`
  (`…/expenses/pot/PotRevisionStore.kt:105-137`) is the compare-and-set — `n + 1` only on `n`, else `Stale` (the wire's
  `409 stale_revision`) — under a lock order written down at `:78-90` and held by `PotAppendRaceSliceTest`; `erase`
  (`:145-162`) is the member's erasure with its erasure-ledger line; `PotBodies` (`:20-26`) admits exactly four sealed
  lengths, one per size class. Nothing in it reads a body.
- **Receipts.** `veyq_expenses_receipt` (`V202610250946__expenses_receipts.sql:18-35`) is the fourth `veyq_blob` arm
  (`scripts/validate_blob_reference_arity.py`'s docstring pins four by entity, schema, foreign key and the live
  share predicate). Core's node attachments could not hold them, being one blob per named slot of a node
  (`ux_attachment_node_slot`, `V202610041200__core_baseline.sql:485`).
- **The seams.** Expenses implements 17 of the 22 seams `ModuleCompositionTest.seams()` pins
  (`src/backend/src/test/kotlin/me/raddatz/veyq/app/ModuleCompositionTest.kt:109-132`); 15 of them are record or receipt
  mechanics, not Expenses' domain: `UploadCompletion`, `MemberBlobRoles`, `ShareGuestWrites`, `UploadAdmission` and
  `SharedSubtreeContributor` (`…/receipt/ReceiptBlobSeams.kt`), `ReportableItems`, `HoldSnapshotContributor`,
  `UploaderContent` and `HeldUploaders` (`…/moderation/PotModeration.kt:42-129`), `NodeRekeyParticipant`,
  `NodeSubtreeEraser`, `ScopeEraser`, `AccountEraser`, `GuestIdentityUsage` and `ApplicationErasures` (`…/pot/`). Only
  `NodeKindRules` and `ShareRoots` are about the Pot.
- **The web half.** `src/web/src/features/expenses/` seals a revision under two purposes of its own, `expense-entry-key`
  and `expense-entry` (`src/packages/crypto/src/seal.ts:110,157`, vectors in `contracts/crypto/vectors/v1.json`), and
  keeps the codec, the opened revisions, the heads graph and its check, the paged read and the append, the open Pot's
  session with its epoch retry, the receipts' transport, the re-key and the report keys in eight modules:
  `domain/{codec,revisions,heads,potApi}.ts`, `potSession.ts`, `receipts.ts`, `rekey.ts`, `report.ts`. Only the page loop
  and the poll tick are core's (`src/web/src/core/items/itemsLive.ts`).

**What copying would cost.** `find src/backend/apps/expenses/src/main -type f \( -name '*.kt' -o -name '*.sql' \) | xargs wc -l`:
2543 lines in 22 Kotlin and 2 SQL files, of which 1619 are the record and receipt mechanics (`pot/PotRevisionStore`,
`PotRevisionEntity`, `PotNodeRekeyParticipant`, the four erasers and usages, `receipt/*`, `PotModeration`) and 836 the
handlers and resources over them; its tests are 1730 lines in 12 files. On the web the eight modules are 1408 lines and
their tests 1083 (`wc -l` over the files named). Contract: 8 paths of `contracts/api/expenses/expenses.openapi.yaml`
(1766 lines) are revisions, erasures, re-key, receipts and the moderation read. Inventory would copy most of the
backend's ~4.3k lines and the web's ~2.5k, and every guard that names an arm or a sealed column
(`validate_blob_reference_arity`, `validate_node_rekey_arity`, `validate_scope_erasure_arity`,
`validate_account_erasure_arity`, `validate_measure_target_erasure`) would gain a second copy to watch — or a fifth blob
arm, which every reclaim and share predicate must then enumerate.

**What Inventory needs beyond Expenses** (inventory.md § On the platform): pictures per item; a **writer check** —
an item's revision `n + 1` only from the writer of its revision 1, a loan entry's only from its own writer; an owner's
items **gone when the owner leaves** the space, is removed or erased, while that member's loan entries on others' items
stay; the server's time of a record's first revision as the tie-break for two reservations; no links.

**Three constraints.** (a) ADR 0079 decision 3: *as much as can be core's is core's*, but a block holding an
application's data *is a typed template the application instantiates with its own table and rules, never the generic
record store this ADR rejects* — rejected because it would make the server *a database of blobs with no shape*, give up
per-kind authorization, quota and trash rules, and push every integrity rule into the clients
([ADR 0079](0079-veyq-is-a-platform-memories-is-its-first-application.md) Consequences). (b) A web core module
imports no client generated from an application's spec ([Applications § Guards](../apps/README.md#guards),
`validate_web_core_imports_no_applications.py`), so core code on the web cannot call Expenses' operations. (c) The seam
that runs when a membership ends may delete only rows keyed by (user, scope), take no lock beyond the caller's scope
lock and call no blob store (`src/backend/core/src/main/kotlin/me/raddatz/veyq/core/scope/MembershipEraser.kt:14-17`);
an account's erasure deletes its memberships directly (`…/core/account/erasure/AccountPurge.kt:171`), after the
applications' `AccountEraser`s ran (`:146`), so no membership seam sees it at all.

## Decision

**1. A record type is declared by its application; the template is core's.** An application declares each record type
it keeps — a server-visible, open token like a node kind or a blob role (`EXPENSES_ENTRY`; Inventory's `INVENTORY_ITEM`
and `INVENTORY_LOAN`) — as one CDI bean core collects (`RecordTypes`, fail-closed like every seam): the node kind its
records live in, its **own table** (an entity of the application's that extends core's mapped columns of a revision
row), its writer policy (decision 3), what a departing writer leaves (decision 4), whether a member may erase a record,
and its attachment roles with their caps (decision 5). Core owns everything else. *Why typed, not a store:* nothing is
stored but a revision of a declared type in a live node of the declaring application's kind, so authorization (the
kind's share roles, the application's switch, the writer policy, a moderator's block), quota (the role's caps, the
link's usage) and trash (records live and die with their node) stay per kind, and the server holds every integrity
rule it can see (decisions 2 and 3) — the three reasons ADR 0079 rejected the store, each answered. *Why the
application's own table:* ADR 0079 decision 6, and one application's volume and indexes stay out of another's; a core
migration names no application table, so the application's migration creates it from the template's column set, which
a core test fixture checks against the entity. A record type no application declares, or two, is refused at startup;
a request for one is a `404`.

**2. Core owns the write and the read.** Everything `PotRevisionStore` does moves into a core store generic over the
declared entity, unchanged in behaviour: the compare-and-set (`409 stale_revision`), the tombstone, the member's
erasure with its erasure-ledger line, one ladder of size classes for every type (512 B, 2 KiB, 8 KiB, 16 KiB of
padded plaintext; a type may cap lower, never add a class — so a size class says the same in every application), the
writer columns, the lock order, the node's epoch gate, the moderator's block (every revision but a tombstone refused,
ADR 0108 decision 6), the guest gate and the guest-write record
([ADR 0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) § 10) for a type whose
kind has share roles, the application switch, the position from core's bump, and the paged list with its ETag
([Applications § How an application publishes a revisioned list](../apps/README.md#how-an-application-publishes-a-revisioned-list)).
Record ids and attachment ids share one space, a reportable item's id: an id another node's record or any attachment
holds is `id_taken`, as today.

**3. A writer policy, enforced by the server from what it already sees.** `ANY` (Expenses: whoever may write writes
every record, ADR 0108 decision 6) or `FIRST_WRITER`: revision `n + 1`, a tombstone included, only from the principal —
the member's user id or the link identity, `PrincipalKey` — that wrote revision 1, else `403 not_record_writer`; an
erasure under `FIRST_WRITER` is that writer's too. The server compares two opaque ids it stores on every row already
and reads no body. *Why server-side:* a check only clients make is one a modified client skips, and the server can
make it without learning anything; the client still reads the writer of each revision, so a history that breaks the
policy cannot pass unseen either.

**4. What a departing writer leaves: `KEEP` or `DROP`.** `KEEP` (Expenses' entries, Inventory's loans) leaves every
record as it is, the writer shown as a former member ([ADR 0101](0101-an-account-is-erased-on-request-and-only-what-the-law-keeps-stays.md)).
`DROP` (Inventory's items) removes the records whose revision 1 a member wrote once that member's membership of the
scope ends — leaving, removal, the tenant cut, or an account's erasure. It is allowed only with `FIRST_WRITER` (so
every row of such a record names that member, and the rows are the (user, scope)-keyed shape the membership seam
admits) and only on a kind with no share roles (so every writer is a member, whose id every member's read carries).
**In the membership's own transaction** core's `MembershipEraser` — and, for an account's erasure, core's
`AccountEraser`, which runs while the memberships still exist — writes one **departure** line (user, scope, time) and
nothing else: no blob store, no node lock, as the seam requires. From that commit every read leaves out the records of a
`DROP` type whose first writer departed after writing them (a member who comes back starts with nothing of theirs).
**Core's record purge** then erases them, their attachments' blobs through the reclaim list
([ADR 0062](0062-store-object-reclaim-is-a-durable-work-list.md)) and the content blocks that name them, under the writers' lock order, with the bump that drops them from open lists, on its next run — no trash
retention, since nothing restores them. A hold that copied them keeps its copy (ADR 0093). *The ledger check stays
whole:* core's head (decision 7) names the writer of the revision it points at, so a client accepts a missing named
revision only where its sealed head names a writer who is no member of the space now; the server cannot hide a current
member's record by calling it departed.

**5. Record attachments are one core arm: the receipt arm, generalised.** `veyq_record_attachment` holds (attachment id,
scope, node, record id, role, blob, writer, created, dropped): any number of blobs per record, each in a role its type
declares, with the role's size cap (the `MemberBlobRoles.maxBytes` seam core's upload start already asks) and a count
cap per record (Expenses' 100 receipts). The attachment id is client-minted; the `K_blob` stays wrapped by `blob-key`
under the node's key, bound to (blob, node, role) as a receipt's is today — no new blob purpose. A revision that no
longer keeps an attachment names it (`droppedAttachments`, today's `droppedReceipts`), and core's record purge erases an
attachment once the node trash retention has passed since its drop, since its record's tombstone, or since its
completion where its record never got a revision — `PotReceiptPurge`'s rule, unchanged. **Core implements every blob
seam over it**, for every declared role: the completion (`UploadCompletion`, accepting each kind a record type with
attachments names), the member roles, the guest upload (`ShareGuestWrites`), the link's usage (`UploadAdmission`), the
share's blob predicate (`SharedSubtreeContributor`), the report's reach and its block (`ReportableItems`: a record and an
attachment are each an `ITEM`, a record's report carries every `K_rev` and every attachment's `K_blob`, above the
codec's 256 keys the node instead — ADR 0108 decision 6), the hold (`HoldSnapshotContributor`), "everything from this
uploader" (`UploaderContent`, `HeldUploaders`), the re-key (`NodeRekeyParticipant`, every `K_rev` and every
attachment's `K_blob` of the node, [ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md) decision 3), the
erasers (subtree, scope, account, guest identity, erasure-ledger re-apply), the purge and the reclaim. A record's
attachments are never a node's attachments, so covers keep `veyq_node_attachment` and its one-per-slot rule.

**6. The wire is core's: record-type routes, application-neutral schemas.** Core's spec gets the operations — a
member's list, append, erasure, re-key stage and commit, attachments list and completion, their link counterparts
under `/api/shares/{shareId}/…`, and the moderator's case read — addressed by scope (or share), node and record type
(`…/nodes/{nodeId}/records/{recordType}/revisions`), each refusing a type the node's kind does not declare.
*Why core's and not each application's:* the behaviour is the template's, which [ADR 0079](0079-veyq-is-a-platform-memories-is-its-first-application.md)'s
*Contracts by owner* (Consequences) puts in core's spec, and constraint (b) leaves no other way for a core web module to call them —
an application injecting its own transport into core's session would be the copy again, one adapter per application. The
record type in a path is a constant per application, as `/pots/` is today, so the access log learns nothing new
([ADR 0092](0092-logs-are-operational-and-name-no-person.md)). A type's sealed format stays the application's contract
(`contracts/<application>/`); core's spec says whose a body is and names none.

**7. Web: `src/web/src/core/records/`, the application supplies its format.** Core's modules take over the eight of
Context: the codec, the opened revisions, the heads graph and its check (erasure-covered and, decision 4,
departure-covered), the paged read and the append with the stale and epoch retries, the session composable a kind's
page opens for a member and a guest alike (one write door, the guest's naming flow), the attachments' transport,
reading and download, the re-key part of a kind's `rekeyContent`, and the report keys. An application hands its record
type a **format**: encode and decode its plaintext, and say where in it the heads, the erasure mark and the kept
attachment ids sit. A new type uses core's envelope, `contracts/records/revision.v1.schema.json` — `{ v, heads,
erasure?, attachments, content }`, each head (record id, revision, SHA-256 of the sealed body, writer) — with its
`content` by the application's own versioned schema (ADR 0079 decision 7). Sealed under two new, additive purposes,
`record-revision-key` and the field purpose `record-revision`, both bound to (scope, node, **record type** — which names its
application —, record id, revision), with their vectors in `contracts/crypto/vectors/v1.json`
([ADR 0072](0072-a-seal-binds-its-object.md): a new variant plus its vector). Binding the type is what ADR 0079 decision
9 asks of a new application's seals, and it keeps a revision of one type from opening as another's in the same node
(Inventory's item and loan). **Expenses keeps `expense-entry-key`, `expense-entry` and its entry format v1 unchanged**
— the documented exemption, as Memories' keys are: re-sealing would move every body's digest that later heads name,
which is rewriting the history ADR 0108 decision 4 forbids even a re-key to touch. Its adapter maps its `receipts` to
the kept attachments and its `kind: "erasure"` to the mark.

**8. What stays the application's.** Its record types and what they mean; its plaintext format, its validation and its
vectors (Expenses' entry schema, rounding and ledger); everything computed from opened records (balances, settle-up,
overviews; Inventory's holder, due dates, overlaps); its rights function, which may only narrow core's and decides
which of a record's functions a session offers; its export content (`exportContent`/`importContent`, core's records
read helping, [ADR 0082](0082-a-node-and-everything-under-it-exports-as-a-plaintext-zip-and-imports-under-the-importer.md));
its screens; what it serves beside the records (Expenses' guest read of the rate table's address).

**9. Expenses moves first, with no change in behaviour, and the arm count stays four.** Expenses becomes the template's
first instance: `EXPENSES_ENTRY`, `ANY`, `KEEP`, a member's erasure, role `EXPENSES_RECEIPT` capped at 10 MiB and 100
per record. Its table stays its own (`veyq_expenses_revision`, its columns renamed to the template's — `entry_id` to
`record_id`); its receipts' rows move into the core arm by an Expenses migration (an `INSERT … SELECT` into
`veyq_record_attachment`, then `veyq_expenses_receipt` dropped — an application's migration may name core's tables, a
core migration never an application's). No ciphertext moves: every body, every wrap, every `K_blob` and every digest
stays byte-identical, because the table keeps its rows and the receipt wrap was never bound to its row. The fourth arm
is then `RecordAttachmentEntity.blob_id` / `veyq_record_attachment.blob_id`, so `validate_blob_reference_arity`
re-points it and still counts four. Every current Expenses test passes against the moved code with only its imports and
paths changed; that, not a new test, is the move's proof. `FIRST_WRITER` and `DROP` are built with Inventory, their
first user. Whether the move ships as that real migration or a reset of the dev data is the owner's
(§ Open questions); the technical answer is the migration, which costs a column rename and one copy and rehearses the upgrade the
first live instance will need.

**10. The operator sees nothing new.** Per revision: its record type (one value per Expenses Pot, which the node kind
already names), record id, time, size class, writer, tombstone, erasure; per attachment: size, role, writer, times;
per departure: who left the scope and when, which the membership row already told it. A writer refusal tells the
writer what the server already knew. Inventory's two types make visible which records are items and which loans, the
fact its doc already lists as the operator's ([docs/apps/inventory.md § What the operator sees](../apps/inventory.md#what-the-operator-sees)).

## Consequences

- **Inventory's backend becomes a declaration**: its node kind's rules, two record types, its table's migration and
  the attachment role — no store, no seam implementation of its own beyond `NodeKindRules`; its web half its format,
  its derived views, its rights and its screens.
- **Expenses shrinks** to its domain: the 15 mechanics seams, `PotRevisionStore`, the receipt package and
  `PotModeration` leave `:apps:expenses`; its 8 record paths leave its spec for core's; on the web the eight modules
  leave `features/expenses` for `core/records`.
- **Guards follow the move**: `validate_blob_reference_arity` (the arm's names), `validate_node_rekey_arity`,
  `validate_scope_erasure_arity`, `validate_account_erasure_arity` and `validate_measure_target_erasure` read the
  template's columns once for every declared table instead of Expenses' by name; `OperatorBlindProbeTest` and
  `ApplicationSwitchSweepTest` list core's record operations; `ModuleCompositionTest` counts one new core seam.
- **Costs accepted**: a core store generic over application entities (a JPQL entity name per type, not one table), the
  Expenses exemption in crypto and format, kept for the Pot's life, and one more server-visible token per type.
- **On acceptance**, ADR 0108 gets `- Replaced in part:` for decision 3's table mechanics and decision 7's receipt table,
  and [Applications](../apps/README.md) takes the template's current picture beside the revisioned list.

## What the server sees

Unchanged for Expenses; for every application as decision 10 lists. It reads no body, no attachment and no sealed
head; the writer check and the departure compare ids it stores already.

## Rejected alternatives

- **Copy per application** (Inventory copies Expenses). ~4.3k backend and ~2.5k web lines twice, the lock order and the
  purge's two-layer cutoff re-proven per copy, every arity guard watching a second set of names — and ADR 0079 decision
  3's test answered *yes, the next application needs it the same way*, which is the case it puts in core.
- **A generic, schemaless record store** ("put a sealed record under a key"). ADR 0079's rejection holds: no per-kind
  authorization, quota or trash, and integrity left to clients. The template differs exactly there (decision 1): no
  record without a declared type of a declaring application's kind, every server-checkable rule checked.
- **Attachments as nodes** (a child node per picture or receipt). A node is tree structure with a name, a placement
  revision and a key of its own; a hundred receipts would be a hundred tree rows every level view and every move
  reads, and a guest adding one is an application node a guest creates, which ADR 0108 decision 2 refuses.
- **A fifth blob arm per application** (Inventory's own picture table). Every arm is one more name the reclaim
  predicate, the share's blob predicate, the subtree eraser and the hold must enumerate, and the arity guard exists
  because an arm one of them misses deletes or serves live data; one core arm keeps the count fixed for every
  application to come.
- **Core's node attachments widened** to many blobs per slot. Their one-per-slot rule is what a cover's compare-and-set
  replace rests on (`AttachmentStore`), and a record's attachment is bound to a record and dropped by its revisions, a
  node's slot to neither.
- **One core revision table for every application**, with a type column. Simpler for core, against ADR 0079 decision 6
  and the owner's "own table", and one application's history would share indexes and erasure scans with every other's.
- **Routes per application, core taking an injected transport.** Keeps contracts by application, but every application
  writes the same adapter and its operations stay copies of one behaviour (decision 6).
- **The writer check in the client only.** Free, and void against a modified client; the server sees the ids anyway.
- **Moving Expenses onto the new purposes.** Removes the exemption, but every digest the heads name would change: a
  rewrite of history by the code that is meant to make one detectable.

## Open questions for the owner

1. **Expenses' existing data at the move.** No instance is live. Keep every Pot on the dev instances by the real
   migration (decision 9, the recommendation), or accept a destructive reset of Expenses' data there?
2. **Others' loan entries about a departed owner's item.** Under decision 4 an owner's items go when they leave, and
   their own loans on others' items stay; but the entries other members wrote about the gone item stay too, sealed and
   shown nowhere, because the server cannot tell which item a loan is about. Accept that (the operator keeps not
   learning which item a loan is about), or make that link server-visible so they go with the item?
