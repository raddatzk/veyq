# Feature: Moderation

Acting on what is reported, as a host: who moderates, the key a report is sealed to, the queue and every decision, and
the log that keeps them accountable. A **core** feature
([ADR 0093](../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
§ Decision: node, share, report and hold are platform terms, and every application needs them).

- **Cross-cutting:** [Security Model](../../crosscutting/security-model.md) (share-boundary Trust & Safety: the server
  stores the roster, the published key and sealed grants, never a moderation private key).
- **Decisions:** [ADR 0099](../../decisions/0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md)
  (moderators the Server Owner appoints share one instance moderation key, in epochs; review in src/web/'s moderation
  surface without a vault), [ADR 0093](../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
  (reports, holds, measures, the moderation log), [ADR 0097](../../decisions/0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md)
  decisions 3, 5 and 6 (instance mail to moderators, their address confirmed by a code).
- **Actors:** the **Server Owner** (appoints, removes, mints the key, in admin-web or `veyq-admin`), a **moderator**
  (an existing account the Server Owner appointed, in src/web/'s moderation surface), a **reporter** (a member or a guest of
  a link, in the share they reported from).

## Use-cases

- ★ [Appoint moderators and keep the moderation key](use-cases/uc.moderation.appoint-moderators.md) — **built
  (backend + admin-web)**; coverage stays `planned` while admin-web's job has never run
  (ADR 0099 §§ 1, 3–5): first-run setup makes the Server Owner the first moderator; they appoint existing accounts by address, remove them and
  rotates the key.
- ★ [Serve as a moderator](use-cases/uc.moderation.serve-as-moderator.md) — **built (backend + web)** (ADR 0099 §§ 1, 3, 6, 7): an
  appointee accepts, confirms their address, receives the key from a holder, delivers it to the next newcomer, and can
  step down.
- ★ [Report what you see](use-cases/uc.moderation.report.md) — **built (backend + web)** (ADR 0093 §§ 1–2, 4–5): a
  member or a guest reports up to 100 items; one case per item, a hold on each, moderators mailed.
- ★ [Review a case](use-cases/uc.moderation.review-a-case.md) — **built (backend + web)** (ADR 0093 §§ 3–5, 11): the
  queue, a case's reports and log, the held snapshot in the share viewer's moderation mode, dismissing, extending a
  hold, barring a reporter.
- ★ [Take a measure](use-cases/uc.moderation.take-a-measure.md) — **built (backend + web + admin-web)** (ADR 0093 § 6,
  ADR 0099 § 6): block the share, the content or a guest uploader; propose the Server Owner's steps, which they take or
  decline in admin-web or `veyq-admin`; lift.
- ★ [Object to a measure](use-cases/uc.moderation.object-to-a-measure.md) — **built (backend + web)** (ADR 0093 §§ 4, 7, 14, ADR 0099
  § 5): the statement of reasons reaches the affected in veyq and by mail, an affected member objects once, another
  moderator decides; a reporter is told the decision.
- ★ [Act on an authority's order](use-cases/uc.moderation.authority-order.md) — **built (backend + web + admin-web)** (ADR 0093 §§ 4, 7, 12,
  ADR 0099 § 6): a removal order blocks the share and its content by id at once, an order opens a case with a hold and
  no report, the Annex texts come out to copy, and the authority may defer the statement.
- ★ [Report to an authority](use-cases/uc.moderation.report-to-an-authority.md) — **built (backend + web)** (ADR 0093 §§ 11, 13): the
  export of clear fields for an Art. 18 report, the handover as a log event — which ends a child-sexual-abuse case's
  hold — and the child-sexual-abuse warning.

## Enablers

- [en.moderation.moderation-key](enablers/en.moderation.moderation-key.md) — **built** (ADR 0099 § 2: per epoch a
  32-byte `M_n`, its public bundle, a grant per moderator, and the key proof every moderation call carries).
- [en.moderation.hold](enablers/en.moderation.hold.md) — **built** for a case's holds (ADR 0093 § 4: a snapshot of
  the reported rows and a pin on their store objects, which the reclaim sweep skips; the ends of an open, dismissed,
  decided or extended case, and an authority's order's, whose end is the hold's floor; a child-sexual-abuse case's,
  bounded to 90 days after the report and ended by the handover).
- [en.moderation.log](enablers/en.moderation.log.md) — **built** (ADR 0093 § 11: an append-only table, structured
  fields in the clear and free text sealed to the moderation key; the instance and case events and their retention).
- [en.moderation.guest-write-record](enablers/en.moderation.guest-write-record.md) — **built** (ADR 0093 § 10: a
  guest's write keeps its client address, port and time for N days, as evidence a hold copies).
