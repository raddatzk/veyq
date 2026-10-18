# UC: Report to an authority

- **ID:** uc.moderation.report-to-an-authority
- **Primary actor:** A **moderator** in web/'s moderation surface, with the key proof
- **Secondary actor:** the **authority** — in Germany the BKA as the central office for Art. 18 DSA reports, the police
- **Goal:** Report a suspected offence against life or safety, child sexual abuse included, with the clear fields the
  authority asks for and never a copy of the content; and handle child sexual abuse material without making it
  available again
  ([ADR 0093](../../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
  §§ 4, 11, 13; [ADR 0099](../../../decisions/0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md)
  § 6).
- **Preconditions:** A case exists (a report, or an authority's order).
- **Postconditions (success):** The moderator has a text block to copy into the authority's form; the handover is an
  event in the case's log. No file of the reported content was produced.

## Main flow

1. **The export.** The case offers "Prepare a report to an authority". The server answers, behind the key proof, the
   clear fields it holds and nothing it would have to open: the case id, the category, every time in UTC (the reports
   received, the measures taken), the uploaders the held rows name — a member's account id, a guest's share identity
   id — and every held guest-write record (address, source port where one was recorded, the time it was observed,
   and the share identity), with the time zone stated. The surface renders it as a text block to copy: never a file
   download, never content.
2. **Where it goes** (ADR 0093 § 13): in Germany to the BKA through its portal, registered ahead; without a
   registration a criminal complaint with the police; in acute danger, 110. The Run docs carry the emergency page.
3. **The handover is logged.** "Reported to an authority" and "Information given to an authority" are events of the
   case, each with the reference sealed to the moderation key (`moderation-note`).
4. **Child sexual abuse material gets its own flow.** Before a case of that category opens, the surface shows a
   full-page warning — do not download, forward or screenshot; review only as far as needed; block the content; inform
   the police or the BKA — and the decision panel preselects "Block the content". A human still decides. The warning
   is a **draft** until a qualified legal review.

## Alternate / exception flows

- **A1 — Nothing held** (the hold ended): the export carries the case's own fields and no held record.
- **A2 — No address recorded** (the guest-write record was off, or the resolver yielded none): the export lists none.
- **A3 — The hold's end at the handover is not built.** ADR 0093 § 4 ends a child-sexual-abuse case's hold at the
  confirmed handover; whether keeping it until then is covered for a non-commercial operator (§ 184b(5) StGB) is open
  (ADR 0093 § Legal basis, Still open 1), so a handover changes no hold. The Run docs advise contacting the police at
  once and keeping nothing beyond the hold.

## Quality notes

- **Operator-blind:** the export carries only what the server already holds in the clear — held metadata and the
  guest-write record; nothing is decrypted, and whatever needs plaintext stays in the moderator's browser.
- **No file of reported content, ever** (ADR 0093 § 13): no download, no screenshot, no mail of content.
- **The port's worth** behind further NAT is open (ADR 0093 § Legal basis, Still open 4): the export carries the
  recorded port as a field and says nothing about what it is worth.

## User impact

When something on the instance must go to the police, the operator can report it with the details the authority needs,
without ever making a copy of it.

## Related

- [ADR 0093](../../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md),
  [ADR 0094](../../../decisions/0094-a-clients-address-is-the-hop-the-network-vouches-for.md)
- [uc.moderation.review-a-case](uc.moderation.review-a-case.md), [uc.moderation.take-a-measure](uc.moderation.take-a-measure.md),
  [uc.moderation.authority-order](uc.moderation.authority-order.md),
  [en.moderation.guest-write-record](../enablers/en.moderation.guest-write-record.md),
  [en.moderation.log](../enablers/en.moderation.log.md)
- Docs site: [Moderation (Run)](../../../../site/src/content/docs/run/moderation.md)
