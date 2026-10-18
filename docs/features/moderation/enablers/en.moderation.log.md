# Enabler: The moderation log

- **ID:** en.moderation.log
- **Type:** **Enabler** — structural foundation, no standalone end-user goal
  ([features/README → use-case vs enabler vs ADR vs change-request](../../README.md#use-case-vs-enabler-vs-adr-vs-change-request)).
  A **model** enabler: a table and its retention.
- **Status:** **built** — the instance events (cr-moderators-and-the-moderation-key, 2026-10-17: `ModerationLog`,
  `ModerationPurge`), a report's (cr-report-and-hold), the review's and every decision's
  (cr-moderation-review-and-measures: `CaseDecisions`, `SignInBlocks`, `CaseReviewResource`) and the statement's and the
  objection's (cr-statement-of-reasons-and-objection: `CaseDecisions`, `Statements`, `StatementMail`) and an
  authority's (cr-authority-orders-and-art18-reports: `AuthorityOrderHandler`, `StatementDeferrals`, `CaseDecisions`), and
  the retention of every case's records with it (the moderation-retention follow-up of inbox #462, 2026-10-18:
  `ModerationRecordsPurge`) — decided in
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
| `RECEIPT_SENT` | a reporter's receipt went out (one event on each case of the report) | the report in `target_id` |
| `DECISION_SENT` | a reporter was mailed the decision on their report | the moderator; `DISMISSED` or `DECIDED`, the report in `target_id` |
| `OBJECTION_RECEIVED` | an affected member objected (it reopens a closed case) | the objector; the measure's kind, the objection in `target_id` |
| `OBJECTION_DECIDED` / `OBJECTION_SELF_REVIEWED` | another moderator decided the objection / the one who took the measure did, being the only one | the decider; `UPHELD` or `LIFTED`, the objection in `target_id`; the note sealed |
| `ORDER_RECEIVED` | an authority's order was entered and opened this case | who entered it (a moderator or the Server Owner); the kind in `reason`, the order in `target_id` — its reference is sealed on the order's row (`veyq_authority_order`, `moderation-order` [epoch, orderId]) |
| `STATEMENT_DEFERRED` | an authority's deferral held a statement back, or was renewed | who entered it; the statement in `target_id`, the end in `hold_until`; the authority's request sealed — none where it came with an order, whose reference it is |
| `HANDED_OVER` / `INFORMATION_GIVEN` | the case was reported to an authority / information was given to one | the moderator; the reference sealed. On a child-sexual-abuse case it ends the hold (a `HOLD_ENDED` follows); on any other it changes no hold |

An order whose share the server does not know opens no case: its `ORDER_RECEIVED` is written with no case id, dated
from its entry like the order's row (31 December of the third year after), whoever entered it.

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
objection that reopens a closed case undates its events again (`ModerationLog.reopenCase`). An undecided case stays
open — a moderator still owes its decision — so its events stay undated.

**One purge path for a case** (`ModerationRecordsPurge`, run by `ModerationPurge`; ADR 0093 § 11): the log's own purge
deletes only instance events; a case's events go **with the case**, once every one of them is past its date, together
with every record of it — the case row, its reports' sealed parts and reporter fields and its report shares (they wrap
`K_report`; a report spanning several cases goes with the last), its measures and proposals, statements and their
recipients, deferrals, objections and the orders that opened it. **Never while a measure of it is in force** (deleting
the row would lift a block nobody lifted, and a standing measure needs its statement while it can be contested), nor
while a proposal of it waits; a lift is an event of its own date, which moves the case's. An order that opened no case
goes with its `ORDER_RECEIVED` on that event's date. The purge takes the case row first, as every decision does
(`ModerationRecordsRaceSliceTest`).

**The reporter's contact** is sealed apart from the report (`veyq_moderation_report.sealed_contact`,
`moderation-report-contact` [epoch]) and is nulled six months after the decision on the report was told
(`DECISION_SENT`) — where it could not be told, six months after the case's decision — and never while a case of the
report is open; `RECEIPT_SENT` and `DECISION_SENT` stay as the proof (DSA Art. 16(4)–(5)).

## Related

- [ADR 0093](../../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) § 11,
  [ADR 0099](../../../decisions/0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md) § 4,
  [ADR 0092](../../../decisions/0092-logs-are-operational-and-name-no-person.md)
- Docs site: [Moderate an instance](../../../../site/src/content/docs/account/moderate.md),
  [Moderation (Run)](../../../../site/src/content/docs/run/moderation.md),
  [What the server sees](../../../../site/src/content/docs/security/what-the-server-sees.md)
