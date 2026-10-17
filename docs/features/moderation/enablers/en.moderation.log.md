# Enabler: The moderation log

- **ID:** en.moderation.log
- **Type:** **Enabler** — structural foundation, no standalone end-user goal
  ([features/README → use-case vs enabler vs ADR vs change-request](../../README.md#use-case-vs-enabler-vs-adr-vs-change-request)).
  A **model** enabler: a table and its retention.
- **Status:** **built for the instance events** (cr-moderators-and-the-moderation-key, 2026-10-17: `ModerationLog`,
  `ModerationPurge`); a case's events come with the report — decided in [ADR 0093](../../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
  § 11 and [ADR 0099](../../../decisions/0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md) § 4.
- **Provides:** accountability within a team and to the affected: who did what, when, kept as long as it can be
  contested and no longer.
- **Serves (use-cases):** [uc.moderation.appoint-moderators](../use-cases/uc.moderation.appoint-moderators.md),
  [uc.moderation.serve-as-moderator](../use-cases/uc.moderation.serve-as-moderator.md); later every case event
  (ADR 0093 § 11).
- **Depends on:** [en.moderation.moderation-key](en.moderation.moderation-key.md) (free text seals to it).

## The table

`veyq_moderation_event`, **append-only**: one row per event, written in the same transaction as its cause. In the
clear: the event, its time, the moderator account it concerns, the account that acted, the case id (null for an
instance event), the epoch, a reason category. Sealed to the moderation key: every free text. No content, no key. Not
tamper-evident: the operator owns the database, and the log is for accountability, not a control.

**Never in a log line** ([ADR 0092](../../../decisions/0092-logs-are-operational-and-name-no-person.md) § 1): an
operational log names a case id at most, never an account, an address or a sealed text.

## Instance events (no case id)

| Event | Concerns | Acted |
|---|---|---|
| `APPOINTED` | the appointee | the Server Owner |
| `ACCEPTED` | the moderator | the moderator |
| `DELIVERED` (epoch) | the recipient | the delivering holder |
| `REMOVED` / `STEPPED_DOWN` | the moderator | the Server Owner / the moderator |
| `IDENTITY_ROTATED` | the moderator | the moderator |
| `EPOCH_MINTED` (epoch, reason) | the minter | the minter |

## Retention

An instance event is kept to **31 December of the third year after the later of the event and its moderator's
removal** (ADR 0093 § 11, ADR 0099 § 4). While the moderator it concerns is on the roster its expiry is open
(`expires_at` null); the removal sets it on every open event of that account, and an event written about an account
that is no moderator (a mint by a Server Owner who stepped down) gets it at once. A scheduled purge deletes what has
expired; it is the log's reader for `EveryStoredTimeHasAReaderTest`. A case's events get their date when the case
closes (ADR 0093 § 11; not built).

## Related

- [ADR 0093](../../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) § 11,
  [ADR 0099](../../../decisions/0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md) § 4,
  [ADR 0092](../../../decisions/0092-logs-are-operational-and-name-no-person.md)
- Docs site: [Moderate an instance](../../../../site/src/content/docs/account/moderate.md),
  [What the server sees](../../../../site/src/content/docs/security/what-the-server-sees.md)
