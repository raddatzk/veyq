# UC: Review a case

- **ID:** uc.moderation.review-a-case
- **Primary actor:** A **moderator** holding the instance moderation key, in web/'s moderation surface (`/moderation`)
  and in the share viewer's moderation mode on the reported tenant's host
- **Secondary actor:** the reporters (their reports are what is reviewed); the Server Owner (sees the case's proposals)
- **Goal:** See what was reported exactly as it was when it was reported, read why, and close the case or move it on —
  dismiss it, extend its hold for a concrete reason, or bar a reporter who misuses reporting
  ([ADR 0093](../../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
  §§ 3–5, 11; [ADR 0099](../../../decisions/0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md)
  § 6).
- **Preconditions:** The moderator unlocked the surface and holds the epochs the case's reports are sealed to
  ([uc.moderation.serve-as-moderator](uc.moderation.serve-as-moderator.md)); at least one report opened a case
  ([uc.moderation.report](uc.moderation.report.md)).
- **Postconditions (success):** The moderator read the reports and the held snapshot; the server logged one *opened*
  event per fetch of the report shares and holds no plaintext of either. A dismissed case is closed and its hold ended; an
  extended hold has its new end and a sealed reason in the log; a barred reporter's further reports are refused.

## Main flow

1. **The queue.** With a key proof the surface lists the instance's open and decided cases, across every tenant, in
   clear fields only: the category, the item's kind, when it was first and last reported, how many reports, the
   tenant's domain, the state and the hold's end. The urgent categories come first, then the oldest.
2. **A case.** Opening one fetches its details with a fresh proof over that case: each report's time, category and
   sealed part — opened in the browser with the epoch's key into the reason and the context, and the contact sealed
   apart, until it goes six months after the decision was told (ADR 0093 § 11) — the case's
   measures and proposals, its hold, and its log, whose sealed notes open the same way.
3. **Review.** *Review* navigates to `https://<tenant domain>/s/<case id>#moderation` on the reported tenant's host. The
   share viewer sees the moderation fragment and switches to moderation mode: it signs the moderator in and unlocks as
   the surface does — the vault opens only to open the moderation grants, no member shell, no scope grant — and keeps
   only the moderation keys.
4. With a proof it fetches the case's report shares and the held snapshot's core rows (the server logs *opened*), opens
   each report with its epoch's key, opens each report share with that report's `K_report`, and takes the keys it
   holds: a node's `K_node` and its name's and meta's `K_field`, a medium's `K_blob`s and field keys, a link's
   `K_share` and its name's and note's field keys.
5. It renders the snapshot **with the guest viewer, read-only**, over moderation reads that answer the guest viewer's own
   shapes — the subtree, an application's rows under it (Memories' media, Depot's files), the bytes of a held blob — and
   never a guest endpoint or a scope client: a node as the level view or the Memory view, a medium in the lightbox, an
   upload link as its name and note. The reports' details sit beside it.
6. **Dismiss.** The moderator dismisses the case in the surface, with an optional note sealed to the moderation key. The
   case closes, its hold ends at once — the snapshot is dropped and the pins released, so the sweep reclaims whatever was
   deleted meanwhile — and the case with its records and its log is kept until 31 December of the third year after the
   close ([en.moderation.log](../enablers/en.moderation.log.md) § Retention).

## Alternate / exception flows

- **A1 — Extend the hold.** While a hold is alive the moderator extends it with a concrete reason (an authority's
  request, an objection, a lawyer's letter, a lawsuit, an Art. 18 report in progress), sealed to the moderation key, and
  a new end at most six months from now and after the current end. The log records the reason and the end; without a
  new reason the hold ends at that date.
- **A2 — Bar a reporter.** From a report in the case the moderator bars its reporter: the reporting member's account,
  or the link a guest reported through. Every further report from that account or through that link is refused
  (`reporter_barred`) until the bar is lifted; the reports already made stay.
- **A3 — The original is gone.** The share, the node or the space was deleted after the report: the review reads the
  held rows and the pinned objects all the same.
- **A4 — The hold has ended** (a decided case six months on, an extension that lapsed, a child-sexual-abuse case's
  handover): the details and the log stay until the case's records go, the review finds nothing held and says so.
- **A5 — A medium.** A reported medium has no node key in its report share: the review shows it in the lightbox, read
  from its `K_blob`s, with its metadata where its field keys opened it.
- **A6 — An upload link.** The review shows the link's name and note; its guests' uploads were not reported.
- **A7 — Not a moderator, or no key.** Every read and decision above is refused without the `MODERATOR` role and a
  fresh proof over that operation and that case.

## Quality notes

- **Operator-blind.** The server serves ciphertext it holds and learns which case was opened, by whom, and when; every
  free text — a dismissal's note, an extension's reason — is sealed in the browser to the moderation key's current epoch,
  bound to the case (`moderation-note` [epoch, caseId]).
- **The page that renders reported content holds the moderation keys and nothing of the vault**, on the reported tenant's
  host (ADR 0099 § 6), and calls no guest endpoint: a moderator's review never spends a link's budget, never needs its
  password, and outlives its revocation.
- **A block never hides evidence from the moderator**: the review reads the pinned objects, whatever a measure refuses
  everyone else.

## User impact

The people who run an instance see what was reported exactly as the reporter saw it, even after it was changed or
deleted, and can close an unfounded report at once.

## Related

- [ADR 0093](../../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md),
  [ADR 0099](../../../decisions/0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md)
- [uc.moderation.take-a-measure](uc.moderation.take-a-measure.md), [uc.moderation.report](uc.moderation.report.md),
  [uc.moderation.serve-as-moderator](uc.moderation.serve-as-moderator.md),
  [en.moderation.hold](../enablers/en.moderation.hold.md), [en.moderation.log](../enablers/en.moderation.log.md)
- Docs site: [Moderate an instance](../../../../site/src/content/docs/account/moderate.md),
  [Moderation (Run)](../../../../site/src/content/docs/run/moderation.md),
  [What the server sees](../../../../site/src/content/docs/security/what-the-server-sees.md)
