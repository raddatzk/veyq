# UC: Object to a measure

- **ID:** uc.moderation.object-to-a-measure
- **Primary actor:** A **member affected** by a measure — the creator of a blocked link (or of the link a blocked guest
  came through), an uploader of blocked content, an owner of the space blocked content lies in — in src/web/; a **guest
  uploader** of blocked content reads its statement on the link it came through
- **Secondary actor:** the **moderators** (another one decides the objection), a **reporter** (told the decision on their
  report)
- **Goal:** Learn why something of yours was blocked, and contest it once, with someone other than the moderator who
  decided reviewing it
  ([ADR 0093](../../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
  §§ 4, 7, 14; [ADR 0099](../../../decisions/0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md)
  § 5; [ADR 0097](../../../decisions/0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md) decision 3).
- **Preconditions:** A moderator took a measure on a case ([uc.moderation.take-a-measure](uc.moderation.take-a-measure.md)).
- **Postconditions (success):** Each affected member holds the statement of reasons in veyq and, where instance mail
  is set up, a mail saying it is there; an objection is stored sealed to the moderation key, has reopened the case, and
  once decided carries its answer; every step is an event in the moderation log. The server never held the facts or
  the objection in the clear.

## Main flow

1. **The statement is sent with the measure.** The decision panel asks for the ground — one of the report categories,
   the law or the terms —, the provision of the law or the clause of the terms it relies on (a short reference in the
   clear, "§ 86a StGB", "Space terms, clause 4"), and the facts, which explain why the content falls under it. The moderator's browser seals the facts for the log to
   the moderation key, and once more for the affected, under a fresh key of their own bound to the statement, whose id
   the browser mints; that key alone it wraps to each affected member's identity key, which the server lists for that
   measure before it is taken (ADR 0093 § 7). The server records the statement with the measure, in one transaction,
   and refuses the measure when the wrapped keys do not name exactly the members it would tell
   (`statement_recipients_changed`). The list is whole or refused, never cut: every wrap travels in the measure's one
   request, a fixed 1.6 KB a member whatever the facts' length, so with facts a step reaches at most 6,000 members
   (`StatementRecipients.MAX_SEALED`), and past that it is refused by name (`statement_recipients_too_many`). Every
   statement requires the facts, so such a step cannot be taken at all — the wall that remains; only paging the upload
   removes it.
2. **Who is told.** The creator of a blocked link, or of the link a blocked guest came through; the uploaders the held
   rows name (a blocked guest: that guest); and, on a content block, every owner of the space. A guest has no account
   and no address: it is told on its link (A8), never by mail, and nothing is *sent* to it — where it is the only one
   named, the statement counts as one nobody can be sent (step 6).
3. **What it says** — one template in `contracts/moderation/statement-of-reasons.json`, rendered by the server for the
   mail and by src/web/ on the placeholder: the measure and its reach (in the words of the reader's own relation to it,
   which their recipient row records: a blocked link's creator reads "a link you created", a member whose upload it
   reaches "a link to something you uploaded", anyone else neutral words; inbox #821), why the reader gets it, the
   facts, whether a report or an order led to it, that no automated means decided, the ground and the provision or clause it relies on, that
   the measure applies wherever the instance is reached and lasts until it is lifted (DSA Art. 17(3)(a)), the
   objection, the contact address — "not stated by this operator" where none is set — and the courts. It names the
   host, never the moderator.
4. **One form on every instance** (ADR 0093 § 14): the facts and the provision are required of every statement
   (`statement_facts_required`, `statement_provision_required`); there is no reduced form.
5. **In veyq and by mail.** The member sees the statement on the placeholder of the blocked item or node and on the
   blocked link in their share list, the facts opened with their own identity key. Instance mail (ADR 0097 decision 3)
   tells each one's account address that a statement is there: the template's clear parts, never the facts; a send that
   fails is retried by the hourly cleanup for seven days.
6. **The hold.** The measure's hold ends six months after the statement is sent; where no member is named, six months
   after the decision — when a guest it names can read it on its link (ADR 0093 § 4).
7. **The reporter.** The deciding moderator's browser opens each report's sealed contact and sends the address once,
   beside the dismissal or the measure; the server mails the decision — dismissed, or a measure taken — from memory, at
   most once per report, and logs that it did. The decision carries the reporter's **redress** (DSA Art. 16(5)), one
   fixed passage of the template: that they may report again with more information, the instance contact (or its gap),
   that they may go to court, and — where the DSA applies to the instance, in the sentence's own words, since veyq
   cannot know whether it does — that they may complain to the Digital Services Coordinator of the Member State where
   they live or are established (DSA Art. 53). The sealed contact goes six months after that — or, where no decision
   could be told, six months after the case's decision; the "decision sent" event stays (ADR 0093 § 11).
8. **The objection.** An affected member objects once per measure, in free text, on the statement: sealed in their
   browser to the moderation key's current epoch (`moderation-objection` [epoch, statementId]). It reopens the case — a
   closed case is decided again and back in the queue, its events undated — and the moderators are mailed that an
   objection arrived.
9. **Another moderator decides it.** In the moderation surface the case shows the objection, opened with the moderation
   key, and who may decide it. The server refuses a decision from the account that took the measure while another
   accepted moderator holds the current epoch (`objection_needs_another_moderator`); the other upholds the measure or
   lifts it. With one moderator the decider reviews it, the log marks it as their own review, and the answer says so.
10. **The answer.** The objector sees the outcome on the statement — upheld or lifted, and whether the moderator who
    decided reviewed it alone — and is mailed that it is there.

## Alternate / exception flows

- **A1 — A second objection** on the same measure is refused (`objection_exists`): then the courts (ADR 0093 § 7).
- **A2 — A lifted measure** takes no objection (`measure_not_in_force`); an objection already open on it is still decided.
- **A3 — Not affected.** A member who is no recipient of the statement neither reads it nor objects (`statement_not_found`).
- **A4 — An objection decided twice**, by two moderators at once: the first decision stands, the second is refused
  (`objection_decided`).
- **A5 — No instance mail.** The statement is in veyq all the same; admin-web already warns that mail is missing.
- **A6 — No contact address** (the Server Owner may leave it empty): the statement shows "not stated by this operator" where the contact goes.
- **A7 — The Server Owner's steps over content** (a member's sign-in block, a tenant taken offline) carry a statement
  in the same template and form (DSA Art. 17(1)(c), (d)), entered with the step in admin-web or `veyq-admin`: always
  for a step from a case's proposal, and for a step without a case when the Server Owner says it is over content
  (a suspension for any other reason carries none). A blocked member cannot sign in and a tenant offline cannot be
  entered, so it goes **by mail only** — to the blocked member's account address, or to each owner of the tenant —
  **with the facts**, the Server Owner's own words, which the server sees as it sends them and keeps only sealed to
  the moderation key for the log (owner Q24–Q25). The objection goes to the instance contact the statement names;
  the Server Owner lifts with the existing unblock or reactivate. The mail goes once, after the step committed: a send
  that fails is not retried, because the server keeps no clear copy of the facts. **So the step's answer reports what
  the mail reached** (owner 2026-10-21, `statementDelivery`): that it reached everyone, or every address it did not
  reach — the instance mail is not set up, or the send failed. admin-web says so where it stays on screen and keeps the
  Server Owner's words in view, so they tell those through the instance contact, and warns before the step while no
  instance mail is set up; `veyq-admin` prints it and warns. Nobody to tell (a tenant without an owner, an account erased
  before the send) is said as such, and so is an answer the server cannot give. The step stands either way: it is never
  refused for want of mail.
- **A8 — A guest uploader** (ADR 0093 § 7, inbox #813). A guest identity among the statement's recipients reads it on
  the link it came through, by the contributor token its client holds — the read that tells it of a removal order
  ([uc.moderation.authority-order](uc.moderation.authority-order.md) step 5), on the blocked link's page, on a blocked
  node's or item's placeholder and — every one, since a drop page has no placeholder — on a drop page, whether or not
  its link still lives; only where the device holds the guest's session, and afresh for each identity it holds —
  even while the link is blocked, only while the measure stands, and nothing while an authority's deferral holds the
  statement back. It reads the clear fields in the template's guest words: the measure (as the uploader, never as the
  link's creator; a blocked link is "a link to something you uploaded", true whether or not it is the link the guest
  came through, inbox #821), what led to it (the report that opened the case, else their own finding, inbox #820), the
  ground and the provision, the date the measure was taken, the scope, the courts. **Not the
  facts**: they are sealed under a key wrapped to members' identity keys, and a guest identity has none, so the facts
  passage says the instance contact gives them on request; the objection goes to the instance contact too. Where the
  instance names no contact, both passages say so in sentences of their own rather than ask the guest to write to the
  gap. Another
  identity of the link, an unknown token and the token on another link get the link's ordinary unknown answer.

## Quality notes

- **Operator-blind**: the facts of a moderator's measure reach the affected sealed under a key wrapped to their
  identity keys, the objection reaches the moderators sealed to the moderation key; the server holds both as
  ciphertext and mails neither
  (ADR 0049). A guest uploader's read (A8) serves only clear fields the server already holds beside the measure, to the
  identity its recipient row names, over § 12's request: the server learns nothing it did not. The one exception is the Server Owner's own step (A7): its facts are the Server Owner's own words, which
  the server reads as it mails them — a consented disclosure by their author (ADR 0049 decision 2) — and stores only
  sealed.
- **Separation of duties binds a team, not the host** (ADR 0099 § 5): the Server Owner owns the roster.
- **A case closes** only when its last hold ended and no objection is open (ADR 0093 § 11).

## User impact

If something of yours is blocked, you are told why and can say why you disagree — and someone other than the moderator
who blocked it looks at it again.

## Related

- [ADR 0093](../../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md),
  [ADR 0099](../../../decisions/0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md),
  [ADR 0097](../../../decisions/0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md),
  [ADR 0049](../../../decisions/0049-email-delivery-boundary.md)
- [uc.moderation.take-a-measure](uc.moderation.take-a-measure.md), [uc.moderation.review-a-case](uc.moderation.review-a-case.md),
  [en.moderation.log](../enablers/en.moderation.log.md), [en.moderation.hold](../enablers/en.moderation.hold.md)
- Docs site: [Moderation (Run)](../../../../site/src/content/docs/run/moderation.md),
  [Moderate an instance](../../../../site/src/content/docs/account/moderate.md),
  [Report something](../../../../site/src/content/docs/help/report-something.mdx),
  [What a guest sees](../../../../site/src/content/docs/sharing/what-guests-see.mdx)
