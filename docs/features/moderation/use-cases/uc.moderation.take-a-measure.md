# UC: Take a measure

- **ID:** uc.moderation.take-a-measure
- **Primary actor:** A **moderator** in web/'s moderation surface; for the steps beyond what was reported, the **Server
  Owner** in admin-web or `veyq-admin`
- **Secondary actor:** the share's creator, the uploader, every member and guest who reaches the item (they see the
  effect)
- **Goal:** Stop what was reported from being served, in the smallest step that does it, reversibly and on the record
  ([ADR 0093](../../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
  §§ 4, 6; [ADR 0099](../../../decisions/0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md)
  § 6, decided with the owner 2).
- **Preconditions:** An open or decided case the moderator reviewed ([uc.moderation.review-a-case](uc.moderation.review-a-case.md)).
- **Postconditions (success):** A measure is a row naming its kind, its target id, the case, who took it and when; it
  deletes nothing and holds until it is lifted, or until its target is erased (A7). A moderator's step carries its statement of reasons to the affected
  ([uc.moderation.object-to-a-measure](uc.moderation.object-to-a-measure.md)). The case is decided and its hold ends six
  months after the statement is sent. Every step and every lift is an event in the moderation log.

## Main flow

1. The case's decision panel offers each step with its reach stated, for the targets the case knows: the reported item,
   the links that reach it or that its reports came through, and its uploaders as the held rows name them.
2. **Block the share.** The moderator blocks a link. Every guest path of it answers exactly as an unknown link (`404
   share_not_found`); its creator's share list shows it stopped with the reason *blocked by the operator*, and they can
   neither reactivate it nor re-issue it while the block stands.
3. **Block the content.** The moderator blocks the reported node or medium. Every byte of it — a node's blob and every
   blob below it, a medium's original and derivatives — is refused to everyone, members included, as `451
   blocked_by_operator`; every listing that names it keeps it and marks it blocked, so members and guests see the
   placeholder *Blocked by the operator* with no reason. The uploader can still delete it; replacing a blocked file's
   content and restoring a blocked item from the trash are refused. A gallery open on a blocked medium turns its tile,
   and an open lightbox on it, to the placeholder on its next live poll and revokes what it had decrypted of it; a lift
   shows it again ([uc.memory.live-updates](../../memory/use-cases/uc.memory.live-updates.md) E8).
4. **Block a guest uploader.** The moderator blocks a guest identity the held rows name as an uploader: its token is
   refused on every write, and the link it came through is blocked with it (a guest could mint a new identity on the same
   link). The link's creator may re-issue the link: the new link is a member's new decision.
5. **Propose the Server Owner's steps.** For a member uploader the moderator proposes blocking their sign-in; for the
   tenant, taking it offline. The case shows the proposal; the Server Owner sees it in admin-web (`veyq-admin moderation
   proposals`) and takes it — a member's sign-in block, which ends every session of the account at once and refuses
   every sign-in until lifted (`veyq-admin account block|unblock`); or the tenant's suspension
   ([uc.admin.suspend-tenant](../../admin/use-cases/uc.admin.suspend-tenant.md)) — or declines it. A step from a
   proposal is over content and carries its statement of reasons, entered with it — ground, provision, facts —, by
   mail to the member or to the tenant's owners ([uc.moderation.object-to-a-measure](uc.moderation.object-to-a-measure.md)
   A7); a step without a case carries one when the Server Owner marks it as over content.
6. Each step takes an optional note sealed to the moderation key. A moderator's step (2–4) also takes its **statement
   of reasons** — the ground, the provision or clause it relies on, and the facts sealed to the moderation key and to
   each affected member's identity key —
   sent with it ([uc.moderation.object-to-a-measure](uc.moderation.object-to-a-measure.md) steps 1–6); a reporter who
   left a contact is told the decision. The case is *decided*, and its hold now ends six months after the statement is
   sent (from the decision where nobody can be told).
7. **Lift.** Any moderator lifts a measure of a case; the Server Owner lifts a sign-in block. A lift records when it was
   lifted and writes its log event, and the target serves again unchanged. Lifting a case's last measure ends its hold.

## Alternate / exception flows

- **A1 — A target the case does not know.** A share, an item or an identity outside the case is refused
  (`moderation_target_unknown`); a step that does not fit the item (a content block on an upload link) the same.
- **A2 — The same step twice.** A measure already in force on that target is not taken again
  (`moderation_measure_exists`).
- **A3 — A moderator reaches for a Server Owner's step.** Blocking a member's sign-in and listing proposals are
  operations of the admin channel: on the public listener they answer as an unknown route, and a moderator's session
  is no Server Owner's.
- **A4 — The Server Owner's own account.** Its sign-in cannot be blocked (`cannot_block_server_owner`).
- **A5 — A copy escapes.** A copy a member made is a new blob under a new key; a block reaches only the reported one.
- **A6 — A guest of a blocked link** sees an unknown link, exactly as after a revoke; a member keeps every other
  function of the share list.
- **A7 — Its target is erased.** When what a measure stands on is erased — a link deleted (its tombstone), a node or a
  medium purged from the trash, a guest identity dropped with the last thing it named, a space, an account or a tenant
  purged — the measure ends in the erasure's transaction, lifted by no one and logged so, and its case's records run to
  their date (ADR 0093 § 11) instead of standing on nothing forever (GDPR Art. 5(1)(e)). Its hold keeps its own end. A
  measure or a bar taken on a target already erased is taken and ends at once, never refused; the decision, its statement
  and the hold stay. Wherever a lifted measure is shown — the case, the affected's statement, the Art. 18 export — such
  a one reads "ended when the content was erased" (de: „endete mit dem Löschen“), not "lifted". A trashed node or
  medium still stands: only its purge erases it.

## Quality notes

- **Blocking deletes nothing**: every measure is a row that a lift closes, and the content, the link and the account stand
  unchanged behind it.
- **Measures are clear metadata**: the server knows which id is blocked, since it must refuse it; the notes are sealed.
- **Every step hangs on ids, never on a report or a key**, so an authority's order takes the same steps.
- **A member's view and a guest's are one view** (inbox #401): the placeholder is a row state both see, not a guest-only
  screen.

## User impact

What was reported stops being shown — the link, the item or the person who uploaded it — without deleting anything, and
everyone sees that it was the operator who blocked it.

## Related

- [ADR 0093](../../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md),
  [ADR 0099](../../../decisions/0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md),
  [ADR 0066](../../../decisions/0066-tenant-suspension.md)
- [uc.moderation.review-a-case](uc.moderation.review-a-case.md),
  [uc.sharing.share-public-viewer](../../sharing/use-cases/uc.sharing.share-public-viewer.md),
  [uc.memory.gallery](../../memory/use-cases/uc.memory.gallery.md), [uc.depot.view-file](../../depot/use-cases/uc.depot.view-file.md),
  [uc.account.login](../../account/use-cases/uc.account.login.md),
  [uc.admin.suspend-tenant](../../admin/use-cases/uc.admin.suspend-tenant.md), [en.moderation.log](../enablers/en.moderation.log.md),
  [en.moderation.hold](../enablers/en.moderation.hold.md)
- Docs site: [Moderation (Run)](../../../../site/src/content/docs/run/moderation.md),
  [Moderate an instance](../../../../site/src/content/docs/account/moderate.md),
  [Report something](../../../../site/src/content/docs/help/report-something.mdx) (§ Blocked by the operator),
  [What the server sees](../../../../site/src/content/docs/security/what-the-server-sees.md)
