---
id: cr-authority-orders-and-art18-reports
title: An authority's order blocks by id within the hour; Art. 18 reports and the child-sexual-abuse-material flow
status: in-progress
satisfies: [uc.moderation.authority-order, uc.moderation.report-to-an-authority]
adrs: ["0093", "0099", "0065", "0088"]
invariants:
  - operator-blind E2EE (an order needs no key; the export carries clear fields only, never a content file)
  - a human decides (the CSAM flow proposes "Block the content", it does not take it)
  - no file or screenshot of reported content is ever produced by veyq (ADR 0093 § 13)
---

## Goal

Build ADR 0093 §§ 12 and 13 and § 7's deferral: an **authority's order** (a TCO removal order naming a share URL or
id, a European Preservation Order, a request for information) is entered by a moderator in the moderation surface
with the key proof, or by the Server Owner in admin-web and `veyq-admin` without a key; a removal order **blocks the
share and the content it reaches by id at once** and opens a case with a **hold without a report** whose end the order
sets; out come the text for the TCO feedback form (Annex II, the block's time in UTC to the second) and a draft for the
obstacle form (Annex III). A preservation order or an information request opens the same hold without a block. An
authority's request to **defer the statement** (TCO Art. 11(3), reference sealed, at most six weeks per request,
reminded at its end) holds back `cr-statement-of-reasons-and-objection`'s statement. For **Art. 18 reports** the
moderation surface exports the clear fields the BKA form asks for, never a content file; for **child sexual abuse
material** it warns before the report opens and proposes "Block the content" first, and the hold ends at the
confirmed handover.

## Context & constraints

- Entry conditions: `cr-moderation-review-and-measures` **done** (block rows by id, holds with a settable end);
  `cr-statement-of-reasons-and-objection` **done** (the statement the deferral holds back);
  `cr-guest-write-record` **done** (the export's address and port come from a held record);
  `cr-instance-contact-and-provider-identification` **done** (the TCO contact point and its languages).
- Decisions: [ADR 0093](../docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
  § 12 (the order path, in both surfaces, without review; the hour; preservation and information), § 13 (where an
  Art. 18 report goes; the export's fields; the CSAM warning, the proposed first measure, the hold's end), § 4 (the
  order rows of the hold-end table; "no extension ends a hold before the duration an authority's order sets"), § 7 (the
  deferral is the authority's switch, never the moderator's); [ADR 0099](../docs/decisions/0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md)
  § 6 ("two operations on one service, because the admin-channel marker names a door per operation",
  [ADR 0065](../docs/decisions/0065-admin-ui-cli-separate-service.md)).

### Measured substrate (worktree at `61aa7756`, clean)

| Fact | Locator |
|---|---|
| A share URL carries its id in the path and its key in the fragment (the order path parses the id, never needs the key) | `sed -n 26,30p web/src/core/sharing/shareLink.ts` |
| The admin-channel marker is per operation, derived at build time | `grep -n 'channelMarkers\|GenerateAdminChannelOperations' -r backend/buildSrc/src/main/kotlin` |
| The block rows and hold-end setter the order path reuses | planned in `cr-moderation-review-and-measures` decisions 1 and 7 (re-read at the merged tree) |
| The public-shares switch an operator who cannot keep the hour turns off | `grep -n '"core.public-shares": {' contracts/platform/capabilities.json` |

## Decisions this CR takes

1. **Orders are rows** (`veyq_authority_order`: kind, authority's reference sealed to the moderation key, received at
   as entered, block time, the hold it opened, entered by). A removal order with a share id the server does not know is
   recorded and answers "not found" in the Annex III draft; the hour's clock is the operator's, the record is the proof.
2. **Two operations per action**, one with the moderator's role and proof on the public listener, one with the Server
   Owner's role on the admin channel (ADR 0099 § 6); both call one handler.
3. **The deferral** is a row on the statement (reference sealed, end date ≤ six weeks from entry); at the end the
   moderation surface reminds and the statement goes out unless a renewal (one more, ≤ six weeks) was entered.
4. **The BKA export** is a text block (copy, never a file download of content): case id, times in UTC, the uploader's
   account id or share identity id, the address and port from a held record (time zone stated), the category.
5. **The CSAM flow** is the moderation surface's: a full-page warning before a case of that category opens (ADR 0093
   § 13's text), "Block the content" preselected, the handover as a log event that ends the hold.

## Legal blockers (named, not decided — ADR 0093 § Legal basis, Still open)

- **§ 184b(5) StGB for a non-commercial operator.** Whether keeping a CSAM hold until the handover is covered for a
  household operator is unsettled. It blocks **shipping** phase 4's hold-until-handover for every operator kind until
  counsel answers; until then the Run docs advise contacting the police at once and keeping nothing beyond the hold,
  as the ADR says. The hold itself is `cr-report-and-hold`'s and unchanged by this.
- **A time-bound authority link to a hold snapshot** is **not built** here and must not be: it is a making-available
  under § 184b(1) no. 2 and needs the police or a specialist first. The CR names it so nobody adds it as a convenience.
- **The recorded port's worth** (Still open 4) blocks the export's wording about the port, not the field.
- Every answer of § Legal basis "needs a qualified review before anything ships": this CR's texts (the warning, the
  Annex drafts, the Run emergency page) are drafts until then.

## Use-cases

Phase 0 writes `uc.moderation.authority-order` (removal, preservation, information; both surfaces; the deferral) and
`uc.moderation.report-to-an-authority` (Art. 18, the export, the CSAM flow), with planned coverage.

## Phases

- [x] **0 docs** — the two use-cases, planned coverage. · Done when: feature-format, coverage, doc-link, change guards
  green.
- [x] **1 contracts** — the order operations on both channels, the deferral, the export read, the handover event;
  classified in `OperatorBlindProbeTest`; the admin-channel sweep sees the Server Owner's twin marked. · Done when:
  `npm run generate:api` (web, admin-web), the contracts guards, `./gradlew check` (predicted red named).
- [ ] **2 backend** — decisions 1–4; the hold ends of § 4's order rows (six months after the block for TCO, never
  shorter; 60 + 30 days for a preservation order; the named duration, else six months); the deferral's reminder.
  · Done when: `./gradlew check` and `run_backend_races_on_mariadb.py` green. **Bites, with controls:** a removal order
  by the Server Owner on the admin port blocks the share and its content (the guest gets `404`, the member `451`) with no
  key in the request, while the same operation on the public port is `404`; an extension shorter than the order's
  duration is refused; the statement of a deferred case does not go out before its end and does after it.
- [ ] **3 admin-web + CLI** — the order form (share URL or id, reference, time of receipt) with the Annex II text and
  the Annex III draft to copy; `veyq-admin order remove|preserve|inform`. · Done when: `npm run check` in `admin-web/`
  green; a test that a pasted URL's fragment is dropped before the request leaves (bite: send it → red).
- [ ] **4 web: moderation surface** — the same order form for a moderator; the deferral entry and reminder; the export
  block; the CSAM warning and preselection (decision 5); the handover. EN+DE. · Done when: `npm run check` green; a
  browser pass of a removal order entered by a moderator and one by the Server Owner, each timed from submit to the
  guest's `404`.
- [ ] **5 docs + coverage** — `run/moderation.md` gains the emergency page ADR 0093 § Consequences lists (110, the BKA
  portal registered ahead, `TCO@bka.bund.de`, what never to do; forward the contact to a phone; turn public links off if
  the hour cannot be kept), and the table of which instances the DSA, the TCO Regulation and the GDPR reach by operator
  kind; coverage refs. · Done when: coverage, wiki-sources and the site check green.

## Definition of done

Every phase's Done-when; coverage with `backend`, `web` (and `admin-web` once admitted) refs; every
`scripts/validate_*.py` except `validate_guard_gaps.py` green; the legal blockers above restated in the CR's close as
still open or answered, with the answer's source.

Implementation has the owner's go (2026-10-17), in the order of inbox #462; a legal blocker named above still blocks the part it names.

## Retrospective

_Filled at implementation._
