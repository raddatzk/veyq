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
- **Current picture:** this page is the *Moderation and legal duties* topic's current-picture doc
  ([decisions § Topics](../../decisions/README.md#topics)): § The current picture holds what is true now, whole; the
  enablers hold each mechanism, the use-cases each flow, the ADRs the why. The law behind it is the
  [legal register](../../crosscutting/legal-register.md), and how long each record is kept,
  [cc.privacy.retention](../../crosscutting/cc.privacy.retention.md). The Server Owner's view is the Run area's
  [Moderation](../../../site/src/content/docs/run/moderation.md) page.
- **Actors:** the **Server Owner** (appoints, removes, mints the key, in admin-web or `veyq-admin`), a **moderator**
  (an existing account the Server Owner appointed, in src/web/'s moderation surface), a **reporter** (a member or a guest of
  a link, in the share they reported from).

## The current picture

veyq gives every instance a host's tools, whoever runs it, and costs nothing until someone reports. The Server Owner
is the host for every tenant on the instance and carries the duties; veyq never decides which apply — every instance
serves the full fixed texts, and a value the Server Owner leaves empty shows as "not stated by this operator"
(ADR 0093 § 14).

- **Who moderates.** Moderators are existing accounts the Server Owner appoints, for the whole instance, the Server
  Owner among them from first-run setup. They share one instance moderation key in epochs; a removal mints a new epoch
  for those who remain, and old reports stay sealed to theirs ([en.moderation.moderation-key](enablers/en.moderation.moderation-key.md),
  ADR 0099 §§ 1–4).
- **A report.** Anyone who can see something can report it — a member, or a guest of the link they see it through —
  up to 100 items at once, from the selection bar, an item's menu or the lightbox. The reporter's client mints a report
  share holding exactly the keys of what is reported and seals it, the reporter's text and their contact to the
  moderation key; the server keeps the category, time and ids in the clear. Nothing is blocked by a report, nobody but
  the moderators learns of it, and they are mailed "new case" with no key, host or link (ADR 0093 §§ 1–2, 5).
- **The hold.** In the report's own transaction the server copies what was reported — ciphertext, attribution and
  guest-write records — into hold tables and pins its store objects, which the reclaim sweep then skips. Every delete
  and overwrite keeps running, so the hold is invisible, and it ends by its trigger: 90 days while open, at once on a
  dismissal, six months after a measure's statement, at the handover for child sexual abuse material, an order's own
  end ([en.moderation.hold](enablers/en.moderation.hold.md), ADR 0093 § 4).
- **Review.** A moderator opens the instance-wide queue in src/web/'s moderation surface and reviews a case on the
  reported tenant's host in the share viewer's moderation mode, which holds only the moderation key — never the vault
  (ADR 0093 § 3, ADR 0099 § 6).
- **Measures**, chosen when deciding, all reversible, all on ids: block the share, block the content (also every row on
  the same stored object), block a guest uploader with their link — a moderator's; block a member's sign-in, take a
  tenant offline — the Server Owner's, on a moderator's proposal. A step ends with its target's erasure
  (ADR 0093 § 6).
- **The affected are told.** A statement of reasons goes to the share's creator, the uploader and, on a content block,
  the scope owner, in the app and by mail; an affected member objects once in the app and another moderator decides;
  a reporter who left a contact gets a receipt and the decision. Only an authority defers a statement (ADR 0093 § 7,
  ADR 0099 § 5).
- **Authorities.** An order acts on ids, within the hour, with no key and no report: the share and its content blocked,
  a case opened with a hold that ends where the order says. An Art. 18 report goes to the BKA as an export of clear
  fields, never a content file; child sexual abuse material has its own warning, a proposed content block, and a hold
  that ends at the handover (ADR 0093 §§ 12–13).
- **The evidence.** A guest's write keeps its client address, source port and time for N days
  ([en.moderation.guest-write-record](enablers/en.moderation.guest-write-record.md)); every report, decision and
  key event is in an append-only log whose free text is sealed, kept to 31 December of the third year after its case
  closes ([en.moderation.log](enablers/en.moderation.log.md)). No log line is evidence (ADR 0092).
- **The instance contact** is the single point of contact for users and authorities and the TCO contact point; it is
  optional, and public in the operator section of the terms ([uc.admin.operator-details](../admin/use-cases/uc.admin.operator-details.md)).

## Decided, not built

- A field on its own other than a node's name as a report's item (inbox #516), and the reporter's handover of a guest's
  identity key (inbox #515) — ADR 0093 § 1.
- A case's records kept longer while proceedings are pending, as a logged extension — ADR 0093 § 11.
- The open legal questions (ADR 0093 § Legal basis, Still open, inbox #563–#576) — among them a hold until the handover
  past 90 days and an authority's link to a held copy — each answered by counsel before veyq is offered as a service.

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
