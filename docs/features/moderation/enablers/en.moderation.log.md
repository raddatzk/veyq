# Enabler: The moderation log

- **ID:** en.moderation.log
- **Type:** **Enabler** — structural foundation, no standalone end-user goal
  ([features/README → use-case vs enabler vs ADR vs change-request](../../README.md#use-case-vs-enabler-vs-adr-vs-change-request)).
  A **model** enabler: a table and its retention.
- **Status:** **built** — the instance events (cr-moderators-and-the-moderation-key, 2026-10-17: `ModerationLog`,
  `ModerationPurge`), a report's (cr-report-and-hold), the review's and every decision's
  (cr-moderation-review-and-measures: `CaseDecisions`, `SignInBlocks`, `CaseReviewResource`) and the statement's and the
  objection's (cr-statement-of-reasons-and-objection: `CaseDecisions`, `Statements`, `StatementMail`) — decided in
  [ADR 0093](../../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
  § 11 and [ADR 0099](../../../decisions/0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md) § 4.
- **Provides:** accountability within a team and to the affected: who did what, when, kept as long as it can be
  contested and no longer.
- **Serves (use-cases):** [uc.moderation.appoint-moderators](../use-cases/uc.moderation.appoint-moderators.md),
  [uc.moderation.serve-as-moderator](../use-cases/uc.moderation.serve-as-moderator.md),
  [uc.moderation.report](../use-cases/uc.moderation.report.md),
  [uc.moderation.review-a-case](../use-cases/uc.moderation.review-a-case.md),
  [uc.moderation.take-a-measure](../use-cases/uc.moderation.take-a-measure.md),
  [uc.moderation.object-to-a-measure](../use-cases/uc.moderation.object-to-a-measure.md).
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

## Case events (with a case id)

| Event | Written when | Names |
|---|---|---|
| `RECEIVED` / `REPORT_ATTACHED` | a report opens the case / joins it | the reporting member (none for a guest) |
| `OPENED` | the review served the case's report shares | the moderator |
| `DISMISSED` | a moderator dismissed the case | the moderator; the note sealed |
| `MEASURE_TAKEN` / `MEASURE_LIFTED` | a measure was taken / lifted (a reporter's bar is lifted as one) | who acted; the measure's kind in `reason`, its target in `target_id`; the note sealed |
| `PROPOSED` | a moderator proposed a Server Owner's step | the moderator; the step and its target; the note sealed |
| `PROPOSAL_TAKEN` / `PROPOSAL_DECLINED` | the Server Owner took the step (a sign-in block, a suspension) or declined it | the Server Owner; the step and its target |
| `HOLD_EXTENDED` | a moderator extended the hold | the moderator; the new end in `hold_until`; the reason sealed |
| `HOLD_ENDED` | a dismissal, the last lift or the cleanup ended the hold | — |
| `REPORTER_BARRED` | a moderator barred a reporter | the moderator; the bar's kind and target (the account or the link); the note sealed |
| `STATEMENT_SENT` | a measure's statement of reasons reached a member | the moderator; the ground in `reason`, the statement in `target_id` |
| `DECISION_SENT` | a reporter was mailed the decision on their report | the moderator; `DISMISSED` or `DECIDED`, the report in `target_id` |
| `OBJECTION_RECEIVED` | an affected member objected (it reopens a closed case) | the objector; the measure's kind, the objection in `target_id` |
| `OBJECTION_DECIDED` / `OBJECTION_SELF_REVIEWED` | another moderator decided the objection / the one who took the measure did, being the only one | the decider; `UPHELD` or `LIFTED`, the objection in `target_id`; the note sealed |

A member's sign-in block taken on no case is `MEASURE_TAKEN` / `MEASURE_LIFTED` with no case id, dated as the Server
Owner's instance events are. Every sealed text is sealed in the moderator's browser to the current epoch, bound to the
case (`moderation-note` [epoch, caseId]).

## Retention

An instance event is kept to **31 December of the third year after the later of the event and its moderator's
removal** (ADR 0093 § 11, ADR 0099 § 4). While the moderator it concerns is on the roster its expiry is open
(`expires_at` null); the removal sets it on every open event of that account, and an event written about an account
that is no moderator (a mint by a Server Owner who stepped down) gets it at once. A scheduled purge deletes what has
expired; it is the log's reader for `EveryStoredTimeHasAReaderTest`. A case's events stay open while the case is,
and get **31 December of the third year after the later of the event and the case's close** when it closes (a
dismissal, the lift of its last measure, or a decided case whose hold the cleanup ended — never while an objection
waits); an event about a case already closed is dated as it is written (`ModerationLog.closeCase`, `appendCase`), and an
objection that reopens a closed case undates its events again (`ModerationLog.reopenCase`).

## Related

- [ADR 0093](../../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) § 11,
  [ADR 0099](../../../decisions/0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md) § 4,
  [ADR 0092](../../../decisions/0092-logs-are-operational-and-name-no-person.md)
- Docs site: [Moderate an instance](../../../../site/src/content/docs/account/moderate.md),
  [Moderation (Run)](../../../../site/src/content/docs/run/moderation.md),
  [What the server sees](../../../../site/src/content/docs/security/what-the-server-sees.md)
