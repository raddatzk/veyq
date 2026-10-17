---
id: cr-statement-of-reasons-and-objection
title: The affected are told why, and object in the app; another moderator reviews the objection
status: planned
satisfies: []            # phase 0 writes the use-case; see § Use-cases
adrs: ["0093", "0099", "0097", "0048", "0049"]
invariants:
  - operator-blind E2EE (an objection's text is sealed to the moderation key)
  - statements name the host, never the moderator (ADR 0099 § Consequences, out of scope)
  - separation of duties binds a team, not the host (ADR 0099 § 5)
---

## Goal

Build ADR 0093 § 7 with ADR 0099 § 5: after a measure, a **statement of reasons** (DSA Art. 17: the measure and its
reach, the facts, report or order, no automated decision, the ground, the objection and the courts) reaches the share's
creator, the uploader and, on a content block, the scope owner — on the placeholder in the app and by mail. A reporter
is told a dismissal or a decision. The affected **object in the app**, once per measure, in free text sealed to the
moderation key; the objection reopens the case; **another accepted moderator decides it** — the server refuses the
decider while one exists, and with a single moderator the decider reviews it and the answer says so. The hold of a
measure then ends six months after its statement is sent. The deferral of a statement on an authority's request is
`cr-authority-orders-and-art18-reports`' (it needs the authority's reference and the order path).

## Context & constraints

- Entry conditions: `cr-moderation-review-and-measures` **done** (blocks, placeholders, the case's measures);
  `cr-instance-contact-and-provider-identification` **done** (a statement names the public contact address, and a guest
  objects through the contact, ADR 0093 § 7). **Blocked on Q11** (below) for its mail phase.
- Decisions: [ADR 0093](../docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
  § 7 (recipients, content, objection rules), § 4 (the measure's hold end counts from the statement's sending);
  [ADR 0099](../docs/decisions/0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md) § 5;
  [ADR 0097](../docs/decisions/0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md) decision 3 (who
  instance mail may reach); [ADR 0048](../docs/decisions/0048-provider-neutral-smtp-mail-transport.md) (tenant mail,
  sent in the tenant's identity); [ADR 0049](../docs/decisions/0049-email-delivery-boundary.md) (no user data in mail).

### Measured substrate (worktree at `61aa7756`, clean)

| Fact | Locator |
|---|---|
| Instance mail may reach the Server Owner, moderators and the contact only | `grep -n 'only' docs/decisions/0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md \| sed -n 1,6p` (decision 3) |
| Tenant mail sends as the tenant (`TenantMailSender`) | `grep -rn 'class TenantMailSender' backend/core/src/main` |
| A member's address is the account's; a guest has none | `grep -n 'email ' backend/core/src/main/resources/db/migration/V202610041200__core_baseline.sql \| head -3`; `sed -n 444,451p backend/core/src/main/resources/db/migration/V202610041200__core_baseline.sql` |
| The share's creator and the uploader are server-known (`owner_account`, the attachment's and medium's uploader) | `grep -n 'owner_account\|uploader_user_id' backend/core/src/main/resources/db/migration/V202610041200__core_baseline.sql`; `grep -n 'created_by\|share_identity_id' backend/apps/memories/src/main/resources/db/migration/V202610041201__memories_baseline.sql` |

## Decisions this CR takes

1. **The statement is rendered from fixed text plus the case's clear fields** (measure, reach, report or order, ground,
   date), in EN and DE, by the server for the mail and by the client for the placeholder — one template in `contracts/`
   so the two cannot drift. The moderator's free-text facts are written in the decision panel and sealed; they travel
   to the affected per Q12.
2. **One objection per measure**, sealed to the current epoch, refused once one exists (then the courts, § 7); it
   reopens the case and is a log event. The decision on it is refused from the account that took the measure while
   another accepted moderator holds the key; with one moderator it is accepted and logged as the decider's own review.
3. **The hold end of a measure moves** to six months after the statement is sent; where nobody can be told (a guest
   uploader with no contact), from the decision (ADR 0093 § 4).

## Open questions (owner)

- **Q11 — Through which channel do statements and reporters' decisions go?** ADR 0093 § 7 sends the statement to the
  creator, the uploader and the scope owner, and § 2 the decision to a reporter; ADR 0097 decision 3 lets instance mail
  reach only the Server Owner, moderators and the contact; tenant mail would speak in the tenant's name for the host's
  decision.
  - (a) Instance mail, widened by one decision in ADR 0097: "the affected and reporters, for moderation statements".
  - (b) In the app only: the placeholder carries the statement, a member sees a notice at the next sign-in; no mail.
  - (c) Tenant mail.
  - **Recommendation: (a) plus the in-app statement of (b).** The duty is the host's, so the host's channel; (b) alone
    misses an affected member who never signs in again within the objection's window; (c) names the wrong sender.
    Without a configured instance mail, (b) is what remains, and admin-web already warns that mail is missing.
- **Q12 — Do the moderator's free-text facts reach the affected?** They are sealed to the moderation key, the
  affected cannot open them. **Recommendation:** the moderator's client seals the facts a second time to each
  affected member's identity public key (a guest gets them only through the contact); the mail says "the statement is
  in veyq" and carries no facts (ADR 0049). Alternative: the facts are plain text in the mail and the placeholder —
  simpler, but the operator then stores what the moderator wrote about a user's content in the clear.

## Use-cases

Phase 0 writes `uc.moderation.object-to-a-measure` (receive the statement, object, the second moderator's review, the
answer), with planned coverage, and adds the statement's flow to `uc.moderation.take-a-measure`.

## Phases

- [ ] **0 docs** — the use-case, the flow added, planned coverage; ADR 0097 decision 3 per Q11. · Done when: the
  feature-format, coverage, doc-link and change guards green.
- [ ] **1 contracts + crypto** — the statement template; the objection (submit: an affected member with a bearer; a
  guest has no in-app objection, ADR 0093 § 7), its decision on the moderation spec; the facts' seal per Q12 (a new
  `sealTo` purpose with its vector if (recommended)). · Done when: `npm run generate:api`, the contracts guards,
  `npm run check` and the mutation gate in `packages/crypto/` for any new purpose.
- [ ] **2 backend** — statements recorded and sent; objection rules of decision 2; hold ends of decision 3; log
  events (statement sent, objection received, objection decided with the reviewer). · Done when: `./gradlew check` and
  `run_backend_races_on_mariadb.py` green (two moderators decide one objection). **Bites, with controls:** the decider
  decides the objection while a second accepted moderator exists → refused, the second → accepted; the decider alone
  → accepted and logged as their own review; a second objection on one measure → refused, the first → accepted.
- [ ] **3 web** — the statement on the placeholder, the objection form there (once), the objection in the moderation
  surface with who may decide it, the answer; EN+DE. · Done when: `npm run check` green; a browser pass with two
  moderators and with one.
- [ ] **4 mail** — per Q11; the mail carries no user data beyond the recipient's own address (ADR 0049). · Done when:
  `./gradlew check` green; a test that the mail body carries no case facts (bite: interpolate the facts → red).
- [ ] **5 docs + coverage** — `run/moderation.md` (statements, objection, a one-person instance), a Use page section
  "If something of yours was blocked" (EN+DE), coverage refs. · Done when: coverage, wiki-sources and the site check
  green.

## Definition of done

Every phase's Done-when; coverage with `backend` and `web` refs; every `scripts/validate_*.py` except
`validate_guard_gaps.py` green.

Implementation waits for the owner's go.

## Retrospective

_Filled at implementation._
