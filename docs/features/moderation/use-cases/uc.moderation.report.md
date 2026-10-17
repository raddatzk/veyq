# UC: Report what you see

- **ID:** uc.moderation.report
- **Primary actor:** A **reporter** — a member of a space, or a guest of a link — in the space or share they reported from
- **Secondary actor:** every accepted moderator (mailed), the instance contact's notification address (mailed)
- **Goal:** Hand the instance's moderators exactly what the reporter sees as suspect, readable by them alone, kept for
  them however the item changes afterwards, without blocking anything
  ([ADR 0093](../../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
  §§ 1, 2, 4, 5; [ADR 0099](../../../decisions/0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md)
  §§ 2, 3, 7).
- **Preconditions:** The instance moderation key has an epoch (first-run setup mints epoch 1,
  [uc.moderation.appoint-moderators](uc.moderation.appoint-moderators.md)); the reporter sees the item — as a member of
  its space, or through a `READ` or `READ_WRITE` link; a guest of an upload (`WRITE_ONLY`) link reports the link.
- **Postconditions (success):** One open case per reported item, with this report attached; a hold on each item's rows
  and store objects ([en.moderation.hold](../enablers/en.moderation.hold.md)); every accepted moderator with a confirmed
  address mailed or queued for the day's bundle; the reporter shown a reference and the time, and mailed a receipt if
  they left an address. The server holds the category, the time and the ids in the clear, and nothing it can open.

## Main flow

1. The reporter selects up to 100 items and chooses **Report**: in a level's selection bar, in a row's or the header's
   actions, in Memories' lightbox for one medium, or — a guest of an upload link — on the link's page. The client
   reads the published key (`getModerationKey`, unauthenticated); while no epoch exists the action is not shown.
2. The dialog asks for a **category** (child sexual abuse material, terrorist content, a threat to life or safety, other
   illegal content, a breach of the terms), a **reason** (required), a **confirmation** that the statements are accurate
   and complete in good faith (required), and an **e-mail address** for the receipt and the decision (optional). On
   the first category it says that a report without a name is expected.
3. The client draws a fresh `K_report` and, per item, seals the keys that open it under `K_report` (a **report share**,
   [en.moderation.hold](../enablers/en.moderation.hold.md) § What a report hands over): a node's `K_node` with its
   name's and meta's `K_field`; a medium's `K_blob`s with its `media-meta` and `media-exif` `K_field`s; an upload link's
   `K_share` with its name's and note's `K_field`s. It seals `K_report`, the reason, the address and the context to the
   epoch's public half (`moderation-report` [epoch]).
4. It sends the category, the epoch, the items (kind, id, report share) and the sealed part — a member with their
   session to their space's report route, a guest with the link and its password token to the link's — and, when an
   address was given, that address once in the clear for the receipt.
5. The server checks the epoch is the current one and that every item lies inside what the reporter reaches: a
   member's space; a guest's link subtree (a node, or an application's item on a node, of it); for a `WRITE_ONLY` guest
   only the link itself.
6. In one transaction it stores the report, and per item opens a case — or attaches the report to the item's open case,
   raising the case's category to the more urgent of the two — and takes the item's **hold** where the case holds none
   alive: a snapshot of its rows and a pin on its store objects, ending 90 days after the report. It logs "received" or
   "report attached" per case.
7. After the commit it mails every accepted moderator with a confirmed address, and the instance contact's confirmed
   notification address: the category and "new case", nothing else — at once for the first three categories, in the
   day's bundle for the last two. It mails the receipt to the address it was given, keeps no copy of it, and answers
   the reference and the time, which the dialog shows.

## Alternate / exception flows

- **A1 — An item outside the reporter's reach** (another space, a node outside the link's subtree, a medium of another
  Memory, or anything but the link for a `WRITE_ONLY` guest). The whole report is refused; nothing is stored.
- **A2 — The same item again.** The report attaches to the open case: one case, two reports; the hold is not taken
  twice. A case whose hold ended takes a new one.
- **A3 — More than 100 items.** The dialog does not open; it offers to report the enclosing node instead.
- **A4 — The epoch changed** between reading the key and sending (a rotation). The server refuses with
  `moderation_epoch_changed`; the client reads the key again and seals anew.
- **A5 — Too many open reports.** A member with more open reports than the cap is refused until cases close; a guest
  has no account to count, so their bound is the rate limit per address and per link alone (owner decision Q8, the
  residual a link holder rotating addresses has).
- **A6 — The link is gone** (revoked, expired, its space deleting or its tenant suspended): the share's usual
  non-enumerable `404`; a wrong password token is `403`.
- **A7 — Nothing is blocked.** The item stays visible, editable and deletable by everyone who had it; a delete, an
  overwrite or a revoke after the report changes nothing a moderator will see
  ([en.moderation.hold](../enablers/en.moderation.hold.md)).

## Quality notes

- **Operator-blind.** In the clear: the category, the epoch, the time, each item's kind and id, and the reporter's
  account or link. Sealed: `K_report`, the reason, the address and the context; the report shares are opaque under
  `K_report`. A dump opens no report.
- **Invisible and inert.** A report share is a row of its own table: in no listing, revocable by nobody, outliving the
  original share and the tenant's suspension; it is never served to the client that minted it. Nobody but the
  moderators learns of a report: not the creator, the uploader, the space's owner or a member.
- **The receipt address** reaches the server once, in the clear, for one send (owner decision Q6), as every recipient of
  every mail does; it is bounded per address, and only its sealed copy is stored.
- **What is not built here:** a field reported on its own (a bucket's name): none of step 1's entry points reports a
  field without its object; and "everything by this uploader" (a guest's identity key): no entry point offers it. A
  guest's upload is reported as a medium, by its `K_blob`s; the drop key never leaves the client.

## User impact

Anyone who sees something illegal or against the terms can tell the people who run the instance, without telling the
person who shared it, and what they reported is kept for review even if it is deleted.

## Related

- [ADR 0093](../../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md),
  [ADR 0099](../../../decisions/0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md),
  [ADR 0097](../../../decisions/0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md) decision 3 (the
  receipt), [ADR 0051](../../../decisions/0051-declarative-rate-limit-posture.md) (the posture)
- [en.moderation.hold](../enablers/en.moderation.hold.md),
  [en.moderation.moderation-key](../enablers/en.moderation.moderation-key.md),
  [en.moderation.log](../enablers/en.moderation.log.md),
  [en.platform.field-key](../../platform/enablers/en.platform.field-key.md)
