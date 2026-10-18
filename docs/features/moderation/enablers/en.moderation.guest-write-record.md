# Enabler: The guest-write record

- **ID:** en.moderation.guest-write-record
- **Type:** **Enabler** — structural foundation, no standalone end-user goal
  ([features/README → use-case vs enabler vs ADR vs change-request](../../README.md#use-case-vs-enabler-vs-adr-vs-change-request)).
  A **model** enabler: one table, the writes that fill it, a retention setting, a purge and a switch.
- **Status:** **built** — decided in [ADR 0093](../../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
  § 10 (with § 14's default per operating mode), the address from [ADR 0094](../../../decisions/0094-a-clients-address-is-the-hop-the-network-vouches-for.md);
  built by cr-guest-write-record (2026-10-17): `core/moderation/guestwrite` (`GuestWriteRecords`, the purge, the
  eraser, the hold's contributor, the retention's resource), `core/http/RequestClient`, the writes that call it, the
  instance backup's schema-only dump, admin-web's Server → Moderation and `veyq-admin guest-write-record`, and web's
  `GuestWriteRecordNotice`, whose link to the record passage of the operator section of the terms
  ([ADR 0035](../../../decisions/0035-server-terms-of-service.md) decision 6) is built (cr-operator-section-of-the-terms,
  2026-10-18); a guest's objection (one identity's rows deleted on the admin channel and in the CLI) came with a legal
  audit (2026-10-18). Open: the notice's wording (counsel).
- **Provides:** for every write a link's guest makes that enters it as the last editor of something
  ([en.platform.field-editor](../../platform/enablers/en.platform.field-editor.md)), the client address and source port
  the server saw and the time, kept for N days, so a hold can copy them as evidence when the written item is reported.
- **Serves (use-cases):** [uc.sharing.share-contributor](../../sharing/use-cases/uc.sharing.share-contributor.md),
  [uc.sharing.share-uploader](../../sharing/use-cases/uc.sharing.share-uploader.md),
  [uc.depot.drop-box](../../depot/use-cases/uc.depot.drop-box.md) (their alternate flow on the record); the hold
  ([en.moderation.hold](en.moderation.hold.md)) copies its rows.
- **Depends on:** core's client-address resolver (ADR 0094, `core/http/ClientAddress.kt`),
  [en.platform.field-editor](../../platform/enablers/en.platform.field-editor.md) (which writes enter a guest as editor),
  the capability register ([ADR 0088](../../../decisions/0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md)
  decisions 4 and 14) and the instance settings ([ADR 0089](../../../decisions/0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md)).

No `## User impact`: an enabler has no standalone end-user story.

## What is recorded

One row in `veyq_guest_write_record` per guest write that enters a guest as last editor, written in the write's own
transaction after it succeeded — a refused or rolled-back write records nothing, and a replay that wrote nothing
records nothing:

| Write | Operation | Object (`object_kind`, `object_id`) | `field_group` |
|---|---|---|---|
| a guest names itself | `createShareIdentity` | `IDENTITY`, the identity | `name` |
| a drop-box guest's folder is created | `ensureShareDropFolder` (only the call that creates it) | `NODE`, the folder | `name` |
| a node is created | `createShareNode` | `NODE`, the node | `name` |
| a node is renamed or moved | `updateShareNode` | `NODE`, the node | `name` |
| a medium is uploaded, with its meta | `completeShareMedia` (per attached item) | `ITEM`, the medium | none |
| a file's content is uploaded | `completeShareFile` | `NODE`, the file | none |

Each row holds the share identity (the authenticated writer, from its contributor token — or, for its own creation,
the identity created), the object, the client address of the one resolver (text form), its source port where the
resolver knows one (else none), `observed_at` (server time in milliseconds UTC) and `expires_at`. Nothing else: no
User-Agent, no content, no header. **Not recorded:** a member's write, any read, a guest's take-back or delete of its
own (`abandonShareNode`, `deleteShareMedia`: they remove, they enter no editor), the upload start and its windows (no
object exists yet; the commit records), a report (its own path, ADR 0093 § 1).

**The address is the resolver's, never a client's choice** (ADR 0094 decision 3: the rightmost `X-Forwarded-For` entry
no trusted proxy wrote, with the port that entry carries or the one configured source's). When the resolver yields no
address, nothing is recorded; when it yields no port, the address is recorded alone. Each guest-write resource
resolves it once per request and hands it down; `GuestWriteRecordedTest` holds that every resource operation taking a
guest's write credential does so or is listed as exempt with its reason.

## The switch, and its default per operating mode

`core.public-shares.guest-write-record` (operator only, `levels: ["instance"]`). Its catalog entry carries the preset
`modes: { "private": false, "organisation": true }` (ADR 0093 § 14, ADR 0088 decision 14): choosing a mode sets it,
and the Server Owner may set it on its own afterwards. The migration that adds the table applies the current mode
once: an instance whose mode is *private* gets the switch's off row, an *organisation* one and one with no mode chosen
none. Like every entry it is the AND along its path, so it is also off while public links are off.

Off: nothing is recorded, and the next purge deletes every live row; rows a hold copied stay in the hold.

## Retention

N days, an instance setting (admin-web's console, `veyq-admin`; ADR 0089): 7 by default, 1 to 30 (ADR 0092 § 2). A
row is written with `expires_at = observed_at + N` as N stands then. The purge deletes a row when
`expires_at <= now` or `observed_at + N_now <= now`: a shortening reaches every row at the next purge, a lengthening
only new ones, so no guest is kept longer than the page told them. It runs at startup (a restored backup's stale rows
go at boot) and then on `veyq.moderation.guest-write-record.purge.every`.

## Where a row goes

- **Its identity is erased:** the row goes with it (`fk_guest_write_record_identity`, cascading).
- **Its object is erased:** core deletes the rows of an erased node subtree (`GuestWriteRecordNodeSubtreeEraser`,
  phase 400 — a guest's take-back of its placeholder too); an application deletes the rows of its own items through
  core (`GuestWriteRecords.forget`) where it erases them (Memories: `MediaStore.purgeMediaRows`, which the retention
  purge, a guest's delete of its own and the subtree eraser share). A space's erasure needs no step of its own: every
  record of a space names an identity of one of its links, and `ShareScopeEraser` deletes those, cascading.
- **A hold is taken on its object:** the hold copies it (`GuestWriteRecordHoldSnapshot`, phase 900, after every
  contributor: every row whose object is a copied row or a named identity — UUIDs only, a share row's id is none — and
  for a reported link every row its guests wrote), and the copy follows the hold's end, not N.
- **Its guest objects** (GDPR Art. 21(1)): the Server Owner deletes every live row of one share identity on the admin
  channel (`eraseGuestWriteRecordsOfIdentity`, `veyq-admin guest-write-record forget <identity-id>`;
  `GuestWriteRecords.eraseIdentity`). What a hold copied stays until the hold ends, and the answer counts it so the
  guest can be told; a later write of the identity is recorded again. The guest page does not show a guest its
  identity id, so the id comes from a moderation case.
- **The instance backup** dumps the table's schema and leaves out its rows (ADR 0093 § 10): what is lost is at most
  30 days old and expiring anyway, and what was reported lives in the hold tables, which are backed up.

## The disclosure

While the switch is on, a link's resolve that lets its guest write (`READ_WRITE`, `WRITE_ONLY`, unlocked) carries
`guestWriteRecordDays` = N as it stands; otherwise it carries none (it is in the response's tag, so an open page learns
a change). The guest page shows one first-layer sentence with that N wherever the guest can write, before its first
write — core's `GuestNaming` in every shape: the naming dialog of a `READ_WRITE` link, the line that says whose session
the device holds, the drop pages of Memories and Depot — and the right to object (GDPR Art. 21) as a sentence of its
own. A `READ` guest sees nothing, because nothing is recorded. The sentence follows the switch, never the mode. Its
wording waits on counsel (ADR 0093 § Legal basis, Still open 4). The full GDPR Art. 13 text — controller and contact,
purpose, basis and legitimate interest, the data, N, what a hold keeps, the recipients, the rights and the supervisory
authority, with the right to object apart — is the record passage of the operator section of the terms
([ADR 0035](../../../decisions/0035-server-terms-of-service.md) decision 6), in every space's terms while the switch is
on; the sentence links it on the section's public page (`/operator-terms#guest-write-record`), in a tab of its own so a
share page keeps its key.

## Related

- [ADR 0093](../../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
  §§ 4, 10, 14; [ADR 0094](../../../decisions/0094-a-clients-address-is-the-hop-the-network-vouches-for.md);
  [ADR 0092](../../../decisions/0092-logs-are-operational-and-name-no-person.md) § 2;
  [ADR 0056](../../../decisions/0056-instance-durable-state-and-cold-logical-backup.md) (the dump leaves the rows out)
- [en.moderation.hold](en.moderation.hold.md), [en.platform.field-editor](../../platform/enablers/en.platform.field-editor.md)
- Docs site: [Moderation (Run)](../../../../site/src/content/docs/run/moderation.md),
  [Configuration](../../../../site/src/content/docs/run/configuration.md),
  [Backup and restore](../../../../site/src/content/docs/run/backup-and-restore.md),
  [What the server sees](../../../../site/src/content/docs/security/what-the-server-sees.md),
  [What a guest sees](../../../../site/src/content/docs/sharing/what-guests-see.mdx)
