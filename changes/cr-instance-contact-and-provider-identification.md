---
id: cr-instance-contact-and-provider-identification
title: The instance contact and the provider identification, set in admin-web and shown publicly
status: planned
satisfies: []            # phase 0 writes the use-case; see § Use-cases
adrs: ["0093", "0035", "0097", "0089", "0065", "0050"]
invariants:
  - operator-blind E2EE (these are public operational values, never content)
  - self-hostable by default (a household instance may leave every value empty and is warned, never refused)
---

## Goal

Give the Server Owner one place, admin-web and `veyq-admin`, to set the values a host has to publish about
itself: the **instance contact** (ADR 0093 § 11: the single point of contact for users and authorities and the TCO
contact point, with its languages; a private notification address; the public address statements and disclosures
name) and the **provider identification** (ADR 0035 decision 7: name, address for service, email, and for a legal
person its representative), plus the **competent data-protection supervisory authority** that ADR 0035 decision 6
renders. The provider identification appears on the login and share pages; everything else is consumed by the later
moderation CRs and the operator section of the terms (`cr-operator-section-of-the-terms`).

This is first in the moderation work order because three later CRs name the contact (a guest's objection,
`cr-statement-of-reasons-and-objection`; the TCO contact point, `cr-authority-orders-and-art18-reports`; the new-case
mail's second recipient, `cr-report-and-hold`) and it depends on nothing of them. It can run in parallel with
`cr-moderators-and-the-moderation-key`.

## Context & constraints

- Decisions: [ADR 0093](../docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
  § 11 (last bullet: the contact, its public status, admin-web's warning), [ADR 0035](../docs/decisions/0035-server-terms-of-service.md)
  decision 7 (the identification, where it appears, the warning) and decision 6 (the supervisory authority is one of
  the values the operator section renders), [ADR 0089](../docs/decisions/0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md)
  (an instance setting lives in the database, set in admin-web, never in the environment),
  [ADR 0097](../docs/decisions/0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md) decisions 3 and 5
  (instance mail; its code confirmation), [ADR 0065](../docs/decisions/0065-admin-ui-cli-separate-service.md) (the
  admin channel; admin-web's four clients).
- Published, public data: the contact's public address, its languages, the identification and the supervisory
  authority are served pre-auth like the terms (ADR 0035 § Consequences). The **notification address is not public**:
  it is where moderation notices go (ADR 0097 decision 3), so it is served only on the admin channel.
- **The sibling that renders these values in fixed text** is `cr-operator-section-of-the-terms` (planned, last in
  the order). This CR renders only the identification on the login and share pages, which ADR 0035 decision 7 asks
  for independently of the operator section.

### Measured substrate (worktree at `61aa7756`, clean)

| Fact | Locator |
|---|---|
| Instance settings are singleton rows with their own migration | `grep -n 'CREATE TABLE' backend/core/src/main/resources/db/migration/V2026101*.sql` |
| The code-confirmation pattern to copy (8 digits, 1 h, 5 attempts, stored hashed, keyed to the address it was sent to) | `grep -n 'CODE_TTL\|MAX_ATTEMPTS\|fun newCode\|fun codeLive' backend/core/src/main/kotlin/me/raddatz/veyq/core/admin/instance/InstanceMailSettingsHandler.kt` |
| The one sender of instance mail | `grep -n 'fun sendCode\|fun mailPending' backend/core/src/main/kotlin/me/raddatz/veyq/core/admin/instance/InstanceNoticeMailer.kt` |
| Server-Owner operations: `instance.openapi.yaml`, class-gated `@RolesAllowed(SERVER_OWNER)` | `grep -n 'RolesAllowed' backend/core/src/main/kotlin/me/raddatz/veyq/core/admin/instance/InstanceResource.kt` |
| admin-web's per-panel console (no routes) and its warning idiom (`<Message severity="warn">` per panel) | `grep -n 'serverPanel\|InstanceMailPanel' admin-web/src/views/AdminConsoleView.vue`; `grep -rn 'severity="warn"' admin-web/src/features/instance` |
| A typed-back code is console-only in the CLI | `sed -n 10,16p admin-web/src/cli/commands.ts` |
| Where the login and share pages render | `grep -n "path: '/login'\|path: '/s/:shareId'" web/src/router.ts`; footer: `grep -n 'footer' web/src/views/ShareView.vue` |
| A new time column needs a reader | `grep -n 'veyq_instance_mail_settings' backend/src/test/kotlin/me/raddatz/veyq/core/persistence/EveryStoredTimeHasAReaderTest.kt` |
| A new operation must be probed or excluded | `grep -n 'Server-Owner route' backend/src/test/kotlin/me/raddatz/veyq/core/http/OperatorBlindProbeTest.kt` |

## Decisions this CR takes (each with the line that settles it)

1. **Three addresses, not one.** The contact point (public, with languages), the public address that statements and
   disclosures name (public; may equal the contact point), and the notification address (admin channel only). ADR 0093
   § 11 lists all three; only the first two are "public, in the operator section".
2. **Server-Owner operations go on `instance.openapi.yaml`** under a new tag `instance-operator`, admin-channel marked.
   A new tag generates a new interface, so the contracts phase stays green on the backend (`changes/README.md` § Phases
   only above real size, "an operation on a NEW tag ends green"). admin-web keeps its four clients.
3. **The public read is one unauthenticated operation** in a new core spec `operator.openapi.yaml`, Host-independent
   (the values are instance-wide): web generates a client for it. It returns only the public values.
4. **Empty is allowed everywhere; admin-web warns.** While `core.public-shares` is on and no contact point is set
   (ADR 0093 § 11), and while the identification is empty (ADR 0035 decision 7). Never a refusal.

## Open question (owner)

**Decided (Q1).** Owner 2026-10-17 ("Ja so übernehmen"): every recommendation in this section is taken, and implementation has the owner's go in the order of inbox #462. The legal blockers named here stay open; they are not owner decisions.

**Q1 — How is the contact's notification address confirmed?** ADR 0097 decision 5 covers the Server Owner's address
(code typed into admin-web) and a moderator's (code typed into the moderation surface); the instance contact's
notification address has no rule, yet ADR 0097 decision 3 mails moderation notices to it.
- (a) The same mailed one-time code, sent through instance mail when the address is saved, typed back into admin-web
  (the Server-Owner flow, unchanged in shape); notices reach it only once confirmed; a changed address asks again.
- (b) No confirmation: the Server Owner typed it, the Server Owner answers for it.
- (c) Confirm by a link.
- **Recommendation: (a).** It is the existing mechanism (`InstanceMailConfirmation`), and an unconfirmed address that
  silently drops the TCO hour's mail is the failure that matters most here. (c) fails for the reason decision 5 gives
  (admin-web is localhost-only). On (a), ADR 0097 decision 5 gains one sentence, edited in place in phase 0.
  The code mail is sent only while instance mail is configured and confirmed; before that admin-web says so.

## Use-cases

None exists for this (`ls docs/features/admin/use-cases`). Phase 0 writes `uc.admin.operator-details` (the Server Owner
sets, confirms and clears the values; the warnings; where each value appears), with a `status: planned` coverage file,
and links it from `docs/features/admin/_index.md`. After phase 0 the front matter's `satisfies:` names it.

## Phases

- [ ] **0 docs** — `uc.admin.operator-details` + its planned coverage YAML; ADR 0097 decision 5 gains the contact's
  confirmation (per Q1); `docs/features/admin/_index.md` lists it. · Done when: `python3 scripts/validate_feature_format.py`,
  `validate_coverage.py`, `validate_doc_links.py`, `validate_changes.py` green; `satisfies:` here names the use-case.
- [ ] **1 contracts** — tag `instance-operator` on `instance.openapi.yaml` (get, put, clear, confirm the notification
  code; admin-channel marked; bounds per field with `x-field-extra-annotation`); new spec `operator.openapi.yaml` with
  the public read; both classified in `OperatorBlindProbeTest`. · Done when: `npm run generate:api` in `web/` and
  `admin-web/`, the contracts guards (`validate_request_bounds.py`, `validate_id_contract_format.py`) and
  `./gradlew check` green (two new tags end green, except the probe test until classified in this phase).
- [ ] **2 backend** — a singleton table `veyq_operator_details` (new migration, pinned in
  `scripts/applied_migrations.sha256`), the handler with the confirmation rule shared with `InstanceMailConfirmation`
  (one rule, not a copy), the code mail through `InstanceNoticeMailer`; rate-limit posture on each operation
  (`@RateLimit(USER)` on the confirm, as `confirmInstanceMail` has); readers in `EveryStoredTimeHasAReaderTest`.
  · Done when: `./gradlew check` green; a slice test per rule: a wrong code spends an attempt, a changed address
  unconfirms without a write, the public read never carries the notification address (asserted on the raw JSON).
  **Bites:** drop the address comparison from the shared confirmation rule → the changed-address test is red; add the
  notification address to the public mapper → the raw-JSON test is red.
- [ ] **3 admin-web + CLI** — Server → Operator panel (the three addresses, languages, identification, supervisory
  authority, the code entry as `InstanceMailPanel` has it) and the two warnings; `veyq-admin operator show|set|clear`
  (no code entry, per `commands.ts:12-13`). · Done when: `npm run check` in `admin-web/` green, a panel test per
  warning (shown with public links on and no contact; gone once set; identification warning independent), the CLI
  test for each command; the admin container build (`docker build -f admin-web/Dockerfile .` — Docker is the
  implementer's, not this refine's) or the CR states it did not run. **Bite:** invert the public-links condition →
  the warning test is red.
- [ ] **4 web** — the identification on the login page and the share page (a footer link opening the values, EN+DE
  via `web/src/i18n/index.ts`), fail-open on a failed read like the share's terms fetch (`ShareView.vue` `loadTerms`).
  · Done when: `npm run check` in `web/` green; a guest-mode mount proves the read is the public client
  (`src/test/guestMode.ts` `guardedClients`); a browser pass on a dev instance with and without values set.
- [ ] **5 docs + coverage** — Run page: the settings and their warnings (`site/src/content/docs/run/configuration.md`
  or `run/moderation.md`, whichever names admin settings today: `git grep -ln 'Server → Mail' site/src/content/docs`);
  coverage refs replace the planned gaps. · Done when: `validate_coverage.py`, `validate_wiki_sources.py`, `npm run
  check` in `site/` green.

## Definition of done

Every phase's Done-when; `docs/coverage/` for `uc.admin.operator-details` with refs on `backend`, `web` (and
`admin-web` only if `validate_coverage.py` no longer lists it as awaiting a first run); every
`scripts/validate_*.py` except `validate_guard_gaps.py` green (baseline at refine: all green,
`for f in scripts/validate_*.py; do python3 "$f"; done` on `61aa7756`). The site pages that speak for the settings
updated. No structural invariant is introduced, so no new guard.

Implementation has the owner's go (2026-10-17), in the order of inbox #462; a legal blocker named above still blocks the part it names.

## Retrospective

_Filled at implementation._
