# UC: Act on an authority's order

- **ID:** uc.moderation.authority-order
- **Primary actor:** A **moderator** in web/'s moderation surface, with the key proof — or the **Server Owner** in
  admin-web or `veyq-admin`, without a key
- **Secondary actor:** the **authority** that issued the order (in Germany the BKA for a terrorist-content removal
  order), the **affected** members (told by the statement of reasons, unless the authority defers it)
- **Goal:** Block what a removal order names within the hour, keep what an order asks to be kept for as long as it
  says, and answer the authority with the forms it expects
  ([ADR 0093](../../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
  §§ 4, 7, 12, 14; [ADR 0099](../../../decisions/0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md)
  § 6).
- **Preconditions:** Moderation is on (an epoch of the instance moderation key exists). The order names a share by its
  URL or id.
- **Postconditions (success):** The order is a row with its reference sealed to the moderation key; a removal order's
  share and the node it is rooted at are blocked by id, and a case holds what they reach until the order's end; the
  Annex texts are on screen to copy. The server never saw the share's key or the reference in the clear.

## Main flow

1. **What goes in.** The kind — a terrorist-content **removal** order, a European **preservation** order, or a
   **request for information** — the share URL or id, the authority's reference, and the time the order was received
   at the contact point. The client keeps only the id from a pasted URL: the fragment, which carries the share's key,
   never leaves the browser or the CLI. The client mints the order's id and seals the reference to the moderation
   key's current public half (`moderation-order` [epoch, orderId]), so a retry within the hour is the same order.
2. **Two doors, one handler.** A moderator enters it in the moderation surface (`enterModerationOrder`, the role and
   the key proof on the public listener); the Server Owner in admin-web or `veyq-admin order remove|preserve|inform`
   (`enterInstanceModerationOrder`, the admin channel, no key). The public listener answers the Server Owner's
   operation as unknown.
3. **A removal order blocks at once**, with no review of its own (the authority has reviewed): the share (it answers
   every guest as an unknown link) and the node it is rooted at with everything below it (every byte read answers
   `451 blocked_by_operator`, members included). A block already in force on either stays and counts; the order's block
   time is when both were in force, in UTC to the second.
4. **A case, with a hold and no report.** The order opens a case of its own — decided, since the authority decided —
   whose item is the share's root node; its hold copies the subtree and the share as a report's would. The hold ends
   six months after the block for a removal order, 60 days after receipt for a preservation order, and for a request
   for information after the days it names, else six months. That end is the hold's **floor**: no extension, lift or
   later decision ends it sooner (ADR 0093 § 4). An extension (an authority's further request) moves it later, as on
   any case.
5. **The statement of reasons** (ADR 0093 § 7): every block the order took carries one with the basis `ORDER`, the
   ground terrorist content and the provision *Regulation (EU) 2021/784, Art. 3 (removal order)*, to the recipients a
   moderator's block would reach; the order is its facts. **A guest uploader is told too** (TCO Art. 11(1), ADR 0093
   § 12): a guest identity whose upload the removal order blocked asks, on the link it came through and with the
   contributor token its client holds, and is answered — even though the link itself is blocked — that it was removed
   on an authority's order received on `<date>`, and that the reasons or a copy of the order are given on request
   from the instance contact (Art. 11(2)). Nothing else is served to it; another identity's token, or an upload a
   moderator's measure blocked, gets the link's ordinary unknown answer.
6. **The deferral is the authority's switch** (TCO Art. 11(3)): entered with the removal order — an end at most six
   weeks ahead, the order's reference as its reason — the statement is held and the case shows when it goes out. Once
   the end has passed, the hourly cleanup sends it; a moderator may enter one renewal before then, at most six weeks
   from its entry, its reason sealed (`moderation-note`). A moderator's own measure can carry a deferral in the same
   way when an authority asked for one. A deferred statement's hold counts from its sending. While the deferral runs
   the guest's notice is held back exactly as the statement is.
7. **What comes out.** For a removal order, the text for the feedback form (TCO Annex II): the reference, the action
   taken and the block time in UTC to the second. One template, `contracts/moderation/authority-order-forms.json`,
   rendered by admin-web (and its CLI) and web/; a **draft** until a qualified legal review.
8. **Information handed over** to an authority in answer to an order is an event in the case's log, its reference
   sealed (uc.moderation.report-to-an-authority); it changes no hold of an order's case, whose end the order sets.

## Alternate / exception flows

- **A1 — Unknown share.** A share id the server does not know is recorded all the same, with no case and no block —
  kept with its event until 31 December of the third year after it was entered (ADR 0093 § 11);
  out comes a draft of the obstacle form (TCO Annex III): the content could not be found on this instance.
- **A2 — A preservation order or a request for information** blocks nothing; the case and its hold are the same.
- **A3 — The same order twice** (the same client-minted id): refused, `order_exists`; nothing changes.
- **A4 — The hour cannot be kept.** veyq cannot make an operator read their mail; the Run docs recommend forwarding
  the contact to a phone and say that an operator who cannot keep the hour turns public links off (`core.public-shares`).
- **A5 — A renewal after the first** or a deferral of a statement already sent is refused (`deferral_renewed`,
  `statement_already_sent`); one longer than six weeks is refused (`deferral_out_of_bounds`).
- **A6 — Moderation off** (no epoch): nothing can be sealed, the order is refused (`moderation_off`).

## Quality notes

- **Operator-blind:** an order needs no key — it acts on ids. The reference is sealed in the client; the server never
  holds a share's key, and the order path reads no content.
- **No review, no report:** the authority reviewed; the order is not a report and takes no reporter's path.
- **A time-bound authority link to a hold snapshot is not built and must not be** (ADR 0093 § Legal basis, Still
  open 2): it would be a making-available under § 184b(1) no. 2 StGB.

## User impact

When an authority orders something removed, the instance can block it within the hour and answer the authority, without
anyone opening the content.

## Related

- [ADR 0093](../../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md),
  [ADR 0099](../../../decisions/0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md),
  [ADR 0065](../../../decisions/0065-admin-ui-cli-separate-service.md)
- [uc.moderation.take-a-measure](uc.moderation.take-a-measure.md),
  [uc.moderation.object-to-a-measure](uc.moderation.object-to-a-measure.md),
  [uc.moderation.report-to-an-authority](uc.moderation.report-to-an-authority.md),
  [en.moderation.hold](../enablers/en.moderation.hold.md), [en.moderation.log](../enablers/en.moderation.log.md)
- Docs site: [Moderation (Run)](../../../../site/src/content/docs/run/moderation.md),
  [Moderate an instance](../../../../site/src/content/docs/account/moderate.md),
  [What a guest sees](../../../../site/src/content/docs/sharing/what-guests-see.mdx),
  [Security roadmap](../../../../site/src/content/docs/security/roadmap.md)
