# Concern: What the server keeps about a person, and for how long

- **ID:** cc.privacy.retention
- **Type:** **Cross-cutting concern** — the retention register: every datum the server keeps that names a person or
  can identify one, where it lives, on which basis, how long, what erases it, and whether a backup holds it. It is the
  current picture of the topic *Privacy: retention, erasure, logs*; it decides nothing, and each row names the ADR
  that did.
- **Status:** built as stated, measured in code (`src/backend`, `src/tools/instance_backup.py`, `src/tools/veyqctl.py`);
  what is decided and not built is under [§ Decided, not built](#decided-not-built), and the defects the measurement
  found under [§ Known gaps](#known-gaps). The legal research behind the bases and periods is the
  [legal register](legal-register.md) — research, not legal advice.
- **Read by:** the Run area's [Data protection](../../site/src/content/docs/run/data-protection.md) page (the
  operator's record of processing) and the instance privacy notice
  ([ADR 0035](../decisions/0035-server-terms-of-service.md) decision 8).

Content is end-to-end encrypted and out of this register: the server holds its ciphertext, never a person's words
or pictures ([security-model](security-model.md)). What follows is what the server can read.

## The rules every row follows

- **Kept for a purpose, to a date the server computes, then deleted by a job** (GDPR Art. 5(1)(e)). Every stored time
  has a reader that acts on it (`EveryStoredTimeHasAReaderTest`).
- **Logs are operational and name no person**: no address, no email address, no User-Agent, no client-supplied string;
  pseudonymous ids only ([ADR 0092](../decisions/0092-logs-are-operational-and-name-no-person.md) §§ 1, 3). Evidence
  never rides a log; it lives in the guest-write record and the moderation records below.
- **An account's erasure deletes what is its own and keeps only what the law needs**, under the opaque id and to a
  date ([ADR 0101](../decisions/0101-an-account-is-erased-on-request-and-only-what-the-law-keeps-stays.md)). It runs
  30 days after the request (`veyq.scope.delete.retention`), by `AccountPurge`
  (`veyq.account.erasure.purge.every`, hourly).
- **A tenant's erasure** (`TenantPurge`, hourly) purges every scope of it, then its profiles, invites and the terms
  acceptances no moderation case keeps (as an account's erasure keeps them), then the tenant row, whose foreign keys take
  its mail settings and switches with it.
- **A backup holds everything the database held when it was taken**, except the guest-write record's rows; it lives at
  most B days, and an erasure made after it is re-applied when it is restored ([§ Backups](#backups-and-the-erasure-ledger)).

## The register

Basis is GDPR Art. 6(1): (b) the contract (running the account a person asked for), (c) a legal obligation, (f) a
legitimate interest. "Backup" says whether the instance backup's database dump holds the datum; every capture is
pruned at B days.

### Account and sign-in

| Datum | Home | Basis | Kept | Erased by | Backup | Decided in |
|---|---|---|---|---|---|---|
| The account's address, its confirmation time, its public keys and wrapped keys | `veyq_user` (`email`, `email_confirmed_at`), `veyq_master_key_wrap`, the identity-pin store, `veyq_device_register` | (b) | while the account lives | `AccountPurge` | yes | [0003](../decisions/0003-operator-blind-password-auth.md), [0074](../decisions/0074-address-control-is-recorded-where-nothing-else-proves-it.md) decision 1 |
| A login challenge, naming the address | `veyq_login_challenge` | (b) | 5 minutes | `SessionPurge` (hourly) | yes, until it expires | 0003 |
| A session: its family, created and last-used times, token hashes | `veyq_session_family` and its tokens | (b) | access token 15 minutes, refresh token 30 days; a family goes once it has no token | `SessionPurge` | yes | [0004](../decisions/0004-session-tokens.md) |
| A recovery request: the address it was mailed to, a token hash | `veyq_account_recovery` | (b) | valid 1 hour; deleted 7 days after it was issued | `RecoveryPurge` | yes | [0054](../decisions/0054-password-recovery-two-factor-release.md) |
| An address confirmation: the address, a token hash | `veyq_email_confirmation` | (b) | valid 24 hours; deleted 7 days after it was issued | `EmailConfirmationPurge` | yes | 0074 decision 4 |
| A pending address change: the new address, a token hash | `veyq_email_change` | (b) | deleted once expired (24 hours) | `EmailConfirmationPurge` | yes | 0074 decision 8 |
| A device pairing | `veyq_device_pairing` | (b) | valid 10 minutes; deleted a day after it was issued | `PairingPurge` (every 5 minutes) | yes | [0059](../decisions/0059-device-linking.md) |
| An erasure request: requested at and by, mailed at | `veyq_account_erasure` | (c) Art. 17 | until the purge runs, or the request is stopped | `AccountPurge` | yes | 0101 |

### Tenants and membership

| Datum | Home | Basis | Kept | Erased by | Backup | Decided in |
|---|---|---|---|---|---|---|
| A tenant invite: the address it went to | `veyq_tenant_invite` | (b) | valid 7 days; a settled row goes 30 days after its expiry, also one to an address its account has since changed | `TenantInvitePurge`; an erased account's current address at once | yes | [0006](../decisions/0006-cold-tenant-scope-birth.md) |
| The address a tenant's owner invite named | `veyq_tenant.owner_email` | (b) | while the tenant lives; it follows its account's address change (`EmailChangeHandler` → `TenantStore.followOwnerAddress`) | blanked when the account holding that address is erased; the tenant's erasure | yes | 0101 decision 4 |
| A member's tenant profile: username, sealed name and picture | `veyq_tenant_profile` | (b) | while account and tenant live | `AccountPurge`; `TenantPurge` | yes | [0023](../decisions/0023-per-tenant-member-identity.md), [0084](../decisions/0084-a-members-profile-picture-is-a-sealed-attribute-of-their-tenant-profile.md) |
| A membership: who added and who granted, when | `veyq_membership` | (b) | while the membership lives; `added_by` and the granter in another member's row outlive their account | `ScopePurge` | yes | [0007](../decisions/0007-membership-role-grant-relation.md) |
| The terms acceptance history: one row per accepted (space revision, section revision), its time | `veyq_tenant_terms_acceptance` | (f) evidence of what a member accepted | while account and tenant live; after the account's or the tenant's erasure only while a moderation case names the account (a step over content on a tenant names its owners at the time of the step), to that case's log date or a step on no case's own (`TermsEvidence`) | `AccountPurge`, `TenantPurge`, and `AccountPurge`'s sweep of kept rows | yes | [0035](../decisions/0035-server-terms-of-service.md) decision 4, 0101 decision 6 |
| A guest's accepted terms | the guest's own browser (`veyq:terms-accepted:<tenant>`, the last two pairs) | — | the browser's | the browser | no | 0035 decision 3 |

### Who wrote what

The server records the **last editor** of a sealed field and the **uploader** of a medium or file as an opaque
`user:{id}` or `share:{identityId}` ([en.platform.field-editor](../features/platform/enablers/en.platform.field-editor.md),
[ADR 0093](../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) § 8):
basis (f), so a report can name who wrote what. These live as long as the object they describe; the object's own
erasure (trash after 30 days, `NodeSubtreePurge`, Memories' `MediaTrashPurge`, Expenses' `PotReceiptPurge`,
`ScopePurge`) takes them. **An account's erasure keeps them** as a former member's opaque id — the shared object is
not the erased person's alone. The same holds for a link's creator (`veyq_share.owner_account`), kept on the link's
tombstone until its scope is erased, and a guest's share identity (`veyq_share_identity`), dropped when its link is a
tombstone and nothing names it. Decided in [0034](../decisions/0034-media-uploader-buckets.md),
[0083](../decisions/0083-memory-participants-are-a-sealed-list-in-the-memorys-metadata.md) decision 8, 0093 § 8,
0101.

### Moderation

The mechanism is in the four moderation enablers; this table holds their periods.

| Datum | Home | Basis | Kept | Erased by | Backup | Decided in |
|---|---|---|---|---|---|---|
| A guest's client address, source port and time, per write | `veyq_guest_write_record` | (f) tracing a reported guest link's misuse | N days (instance setting, 7 by default, 1 to 30): a row goes at the earlier of its write time plus N as N stood then and plus N as it stands now | `GuestWriteRecordPurge`, at startup and hourly; the identity's or object's erasure; the guest's objection (Art. 21) on the admin channel | **no** — the dump keeps the schema only | 0093 § 10, 0092 § 2, [en.moderation.guest-write-record](../features/moderation/enablers/en.moderation.guest-write-record.md) |
| A hold: copies of the reported rows (attribution and guest-write records included) and pins on their store objects | `veyq_hold`, `veyq_hold_row`, `veyq_hold_pin` | (c) and (f): a host's duty to act on and preserve, and evidence | by its trigger: open case 90 days; dismissed at once; six months after a measure's statement; child sexual abuse material until the handover, at most 90 days; an authority's order its own floor; an extension at most six months per reason | `Holds.purgeExpired` in `ModerationPurge` (hourly) | yes, until it ends; an ended hold is ended again after a restore | 0093 § 4, [en.moderation.hold](../features/moderation/enablers/en.moderation.hold.md) |
| A report: the reporting member's account or the link, the time; sealed text | `veyq_moderation_report` | (c) DSA Art. 16, (f) | with its last case | `ModerationRecordsPurge` | yes | 0093 §§ 1, 11 |
| A reporter's contact, sealed to the moderation key | `veyq_moderation_report.sealed_contact` | (c) DSA Art. 16(4)–(5) | six months after the decision was told, or after the decision where it could not be; never while a case of the report is open | `ModerationRecordsPurge` | yes | 0093 § 11 |
| A case and every record of it: measures and who took and lifted them, proposals, statements and their recipients (`mailed_at`), objections, deferrals, orders, its events | `veyq_moderation_case`, `veyq_moderation_block`, statements, objections, orders, `veyq_moderation_event` | (c) DSA Art. 17, (f) defence of claims | to 31 December of the third year after the case closes; never while a measure stands or a proposal waits; an order or a Server Owner's step on no case to 31 December of the third year after its entry or lift | `ModerationRecordsPurge` | yes | 0093 § 11, [en.moderation.log](../features/moderation/enablers/en.moderation.log.md) |
| An instance event: a moderator appointed, accepted, delivered the key, removed; an epoch minted | `veyq_moderation_event` (no case id) | (f) accountability within a team | to 31 December of the third year after the later of the event and the moderator's removal | `ModerationLog.purgeExpired` in `ModerationPurge` | yes | 0093 § 11, [0099](../decisions/0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md) § 4 |
| The moderator roster: appointed by and at, accepted and removed at, the confirmed notification address | `veyq_moderator` | (f) | the address is cleared at removal; the row stays until the account is erased | `AccountPurge` | yes | 0099 §§ 1, 7 |
| A key-proof challenge | `veyq_moderation_challenge` | (b) | 5 minutes | `ModerationPurge` | yes, until it expires | 0099 § 2 |

### Instance and operator

| Datum | Home | Basis | Kept | Erased by | Backup | Decided in |
|---|---|---|---|---|---|---|
| The provider identification, the instance contact and its notification address, the privacy notice's recipients | `veyq_operator_details`, `veyq_operator_recipient` | (c) § 5 DDG, § 18 MStV, DSA Art. 11–12, GDPR Art. 13 | until the Server Owner changes it | overwrite only | yes | 0035 decisions 7–8, 0093 § 11 |
| The instance mail settings' owner and confirmed address | `veyq_instance_mail_settings` | (b) | until changed | overwrite only | yes | [0097](../decisions/0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md) |

### Not in the database

| Datum | Home | Basis | Kept | Erased by | Backup | Decided in |
|---|---|---|---|---|---|---|
| Rate-limit buckets keyed on an address, an account id, an email address (invite, recovery, a reporter's receipt) or a share id | process heap | (f) defence against abuse | while the bucket refills (a minute for an address, an hour for a mail), then dropped by the next pass | `TokenBucketSweep`, every minute; a restart | no | [0094](../decisions/0094-a-clients-address-is-the-hop-the-network-vouches-for.md) decision 3, 0092 § 5 |
| The app's log: pseudonymous ids, domains, counts, an error id | stdout, then the container runtime | (f) operating and securing the service | by size: Compose `json-file`, 10 MB × 3 per service; Helm the kubelet's, 10 MiB × 5 by default; gone with a container's recreation, which every upgrade does | rotation | no | 0092 §§ 3, 5 |
| The admin console's access log: time, method, path, status, size — no address, no User-Agent; its error log names the hop | admin nginx's stdout | (f) operating the administration surface | as the app's log | rotation | no | 0092 § 5 |
| Garage at `warn`, MariaDB's startup and errors, Traefik at `ERROR` with no access log | their stdout | (f) | as the app's log | rotation | no | 0092 § 5 |
| A gateway's or Traefik's access log, a log shipper | the operator's own | the operator's | the operator's | the operator's | — | 0092 § 5: the operator's own processing |
| The erasure ledger: per hard erasure its kind, opaque id and time, and every moderation block, lift, case change and hold end | `erasures.log` beside the config-secret key, and its outbox `veyq_erasure_outbox` | (c) Art. 17, Art. 5(1)(e) | lines older than B | `ErasureLedgerFile`'s trim, at startup (`ErasureLedgerStart`) and hourly while the instance runs (`veyq.erasure-ledger.purge.every`); the outbox's sent rows by its sweep (every minute) | outside the three legs; carried beside the key | [0056](../decisions/0056-instance-durable-state-and-cold-logical-backup.md) decision 8 |

## Backups and the erasure ledger

**B** is an instance setting, days, 30 by default (7 to 365), set in admin-web and `veyq-admin`; the capture tooling
reads it from the database it captures, and an unreadable value means 30. Every capture veyq's tooling wrote —
`veyqctl`'s under its home, the cluster gate's under its claim, an object pool's ledger entries and their objects — is
deleted once it is older than B, the newest included: `veyqctl` prunes at every `backup`, `upgrade` and `start` and
offers `veyqctl backup prune` for a daily timer; the gate prunes at every start, and the chart runs the same prune
daily as a CronJob on the gate's image and claim, reading B from the database and writing only that claim.
`veyqctl status` warns while no capture younger than B exists. `backup.keep` stays the bound on space. A copy the
operator moved off the host is theirs to delete by B, with access to it only to restore it; the Run docs say so.

**What a capture holds:** the whole database except the guest-write record's rows (schema only), and the store's
objects as an index of `veyq_blob` and the hold pins — so a held object is in it. Logs are in none.

**The erasure ledger** keeps an erasure from coming back with a capture. It lives outside the three legs, in the
directory the config-secret key lives in (Compose's named volume, the cluster's kept claim), so neither a restore nor a
rollback touches it. It holds one line per hard erasure that no restored row would trigger again by itself — an erased
account or tenant (a revoked pending one too), a purged scope, node or medium, a deleted link, a reclaimed storage key,
an ended hold — as kind, opaque id and time. The kinds are a sealed set the re-applier covers, so a kind without its
re-applier does not compile; an application contributes its own (Memories: a medium) through a core interface. A purge
that re-fires on its own restored row (a trash past its retention, a guest-write record) needs no line. An erasure
writes its line in its own transaction to the outbox, and a sweep appends it to the file after the commit — at least
once, harmless since re-applying is idempotent. **At every start, before the instance serves,** the application reads
the file and erases, through that kind's own eraser, every listed object whose row exists; no restore needs to be
detected. A line that cannot be applied fails the start closed. The restore's object check counts a key the ledger
lists as erased, never as missing (`veyqctl restore` and `rollback` hand the file over with `--erasures`; the
cluster's restore Job reads it from the claim it already mounts). A restored row that still names an erased key with
no line of its own is counted in the start's log line and served as a missing object is.

**Moderation survives a restore.** The same outbox carries every block and lift of a measure (item kind and target,
link, space and case, who took it, and its basis), every change of a case (item, space, category, state, opening time
and its retention date) and every end of a hold (`HOLD_ENDED`). At start, per item the **last** line wins in both
directions: an item whose last line is a block and that the restored database does not block is blocked again, with
what the block did besides its row (a member's sessions end; a tenant set offline or pending deletion again); one whose
last line is a lift is lifted. A case the database lacks comes back as a stub, `origin = RESTORED`, with no report and
nothing sealed — closed on its retention date if it had closed, open and owed a decision otherwise. A case in an earlier
state moves forward to its last line's state, never back. A hold whose line says it ended ends again, its snapshot and
pins with it. A case's sealed parts, and what decided it, are lost with a capture older than them.

**Limits, stated:** a restore onto a new host re-applies only the ledger it is given, so the Run docs list the file
beside the key among what to carry; erasures made in the last sweep interval (a minute) before a crash that also lost
the file are not re-applied. Whether ciphertext of reported content kept in a capture until B is possession under
§ 184b StGB is a question the law leaves open, not pursued (ADR 0093 § Legal basis, Still open 11).

## Decided, not built

- **A case's records kept longer while proceedings are pending**, as a logged extension
  ([ADR 0093](../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) § 11);
  a hold's extension is built.
- **A child-sexual-abuse hold until the handover past 90 days** — not decided either (ADR 0093 § Legal basis, Still
  open 1, not pursued).

## Known gaps

Found measuring this register; each is an inbox row.

- **No eraser of their own**, each going only with a larger erasure: a removed moderator's row (with the account), a
  link's tombstone (with its scope), finished job rows, an abandoned store-reclaim row, the operator's own details
  (overwrite only). A terms acceptance of a living member has no time bound, also for a tenant they left.
