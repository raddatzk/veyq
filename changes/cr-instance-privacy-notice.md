---
id: cr-instance-privacy-notice
title: A fixed instance privacy notice, public on every tenant host, filled with the instance's values
status: in-progress
satisfies: ["uc.admin.privacy-notice", "uc.admin.operator-details"]
adrs: ["0035", "0093", "0056", "0101", "0088", "0089", "0087"]
invariants:
  - the notice is fixed text shipped with a release; the Server Owner sets values, never prose (ADR 0035 decision 8, as decision 6)
  - public operational content, served without a session; reading it is never recorded and never a gate
  - it states only what the running instance does - a passage follows the switch or the value that makes it true
---

## Goal

Build [ADR 0035](../docs/decisions/0035-server-terms-of-service.md) decision 8: the GDPR Art. 13 information the
Server Owner owes members, reporters and guests, which the legal audit of 2026-10-18 found missing — veyq has no
privacy notice, the operator section carries only the guest-write record's part, and the space template's privacy
clause is the space owner's and deletable (`web/src/features/tenant/termsTemplate.ts`, clause 2). A fixed DE and EN
text in `contracts/`, rendered with the instance's values, served at a public address on every tenant host and linked
from every place that collects — the invite acceptance (Art. 13 applies at registration), the report dialog, a guest's
first write, the login and share pages and the signed-in app's footer. It covers the account and its address, the
session cookies and the browser's storage, the terms acceptance history, the moderation data about a person and a
reporter's contact, the mail relays, the object store, the breached-password check, the backups with B, the account
erasure and what outlives it, the guest-write record, and the rights. By the owner rule that a legal duty wins, the
duty is not open; where the law is (joint control, the breached-password default, layered information), the notice
says what veyq builds and the question stays a named blocker.

## Context & constraints

- Decisions: ADR 0035 decisions 6–8; [ADR 0093](../docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
  §§ 4, 10, 11, 14 and Still open 6, 7, 10; [ADR 0056](../docs/decisions/0056-instance-durable-state-and-cold-logical-backup.md)
  decision 8 (B); [ADR 0101](../docs/decisions/0101-an-account-is-erased-on-request-and-only-what-the-law-keeps-stays.md);
  [ADR 0088](../docs/decisions/0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md) (the switches a
  passage follows); [ADR 0089](../docs/decisions/0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md)
  (new values are instance settings); [ADR 0087](../docs/decisions/0087-published-docs-are-a-starlight-site-docs-is-for-development.md).
- **Entry conditions** (the notice describes what they build; written earlier it would promise it):
  `cr-operator-section-of-the-terms` **done** — its text file, renderer and placeholders are the pattern, and if it
  serves its section at a public address and links it from the login page, the notice is that route's sibling and the
  same link row; **re-read it on main before phase 1** (it was in implementation when this was written, on its own
  branch: `contracts/terms/operator-section.json`). `cr-account-erasure` and `cr-backup-erasure-and-retention` **done**
  (erasure, B). The reporter's sealed contact purge (ADR 0093 § 11, six months after the decision is told; open from
  inbox #462) **built**, or the notice states that period as not yet enforced — never as if it were. Or the owner
  decides it ships describing only what is built, as Q14 decided against for the operator section.
- **Two CRs that write the same page name each other and split it by section** (`changes/README.md`): the operator
  section keeps the notice path, the measures and the authorities; this notice takes every Art. 13 passage (Q27).
- **Neighbour, not this CR's:** what the server's logs keep is ADR 0092's, and `cr-logging` builds the rest of it (the
  audit's minimal fix — no address in the mail, invite and recovery lines, every log rotated by size — is built, inbox
  #481, #482). The notice's log passage states what runs when it ships: with `cr-logging` landed, "ids only, rotated by
  size"; before it, also the two paths that ADR 0092's status names (an unexpected error's URI and text, a database
  error's message), as Run → Operations § Logs states them. This CR writes no log code; `cr-logging` writes no notice
  text.

### Measured substrate (worktree at `a1898747`, clean)

| Processing the notice must state | Where it happens |
|---|---|
| The account: address, keys, confirmation | `sed -n 29,40p backend/core/src/main/resources/db/migration/V202610041200__core_baseline.sql`; `grep -n 'operationId: .*[Ee]mail' contracts/api/account.openapi.yaml` |
| Session cookies (names, lifetime) and the token tables | `grep -n 'veyq_refresh\|Max-Age\|SameSite' backend/core/src/main/kotlin/me/raddatz/veyq/core/account/login/SessionCookie.kt`; `grep -n 'TTL' backend/core/src/main/kotlin/me/raddatz/veyq/core/account/login/SessionService.kt` |
| The browser's own storage (vault, trusted device, guest identity) | `grep -rn "const DB\|DB_NAME\|localStorage\.\(get\|set\)Item" web/src/core web/src/features/account \| grep -v test` |
| The terms acceptance history | `grep -n 'veyq_tenant_terms_acceptance' -A10 backend/core/src/main/resources/db/migration/V202610041200__core_baseline.sql` |
| Moderation data about a person; a reporter's account and sealed contact | `grep -n 'reporter_\|sealed_report' backend/core/src/main/resources/db/migration/V202610171935__core_moderation_reports.sql`; `grep -n 'CREATE TABLE' backend/core/src/main/resources/db/migration/V2026101[78]*moderation*.sql` |
| The mail relays: a space's (its owner's) and the instance's | `grep -rn 'class TenantMailSender\|class InstanceNoticeMailer' backend/core/src/main/kotlin` |
| The object store, bundled or external, and who a browser fetches from | `grep -n 'signing-mode\|bundled-store' backend/core/src/main/resources/application.properties` |
| The breached-password check: on by default, its switch, what crosses | `grep -n 'breached-password' backend/core/src/main/resources/db/migration/V202610161210__core_external_service.sql contracts/platform/capabilities.json`; `sed -n 1,10p web/src/shared/pwned.ts` |
| Other browser-side requests (map tiles, geocoding: opt-in) | `grep -rn 'openstreetmap' backend/core/src/main/resources/db/migration/V202610161210__core_external_service.sql` |
| The guest-write record's first layer, with no link yet | `sed -n 1,25p web/src/core/sharing/GuestWriteRecordNotice.vue` |
| The values that exist, and their public read | `grep -n 'Column' backend/core/src/main/kotlin/me/raddatz/veyq/core/admin/operator/OperatorDetailsStore.kt`; `grep -n 'operationId: getOperatorDetails' -B2 -A8 contracts/api/operator.openapi.yaml` |
| No privacy notice exists; the template's clause is deletable | `grep -rni 'privacy notice\|Datenschutzerkl' web/src admin-web/src site/src/content/docs` (none); `grep -n '^## 2' web/src/features/tenant/termsTemplate.ts` |
| Where the footer is mounted today (not in the app shell) | `grep -rn 'OperatorIdentification' web/src --include='*.vue' -l` |
| Where Art. 13 collection happens: invite acceptance, report dialog, guest write | `grep -n "path:" web/src/router.ts \| grep -i 'invite\|join'`; `ls web/src/core/report/ReportDialog.vue`; `grep -rn 'GuestWriteRecordNotice' web/src --include='*.vue' -l` |
| Pages that speak for privacy | `ls site/src/content/docs/privacy site/src/content/docs/de/privacy` |

## Decisions this CR takes

1. **The text is `contracts/terms/privacy-notice.json`**, in the operator section's format (a revision, `draft` until a
   qualified review, passages marked `both`, `full` or bound to a switch), rendered by a sibling of the operator
   section's renderer sharing its placeholder machinery; the backend serves it rendered from one public operation
   (`security: []`, rate-limited, classified in `OperatorBlindProbeTest`), per tenant host and locale.
2. **A passage bound to a switch renders exactly while it is on**: the guest-write record
   (`core.public-shares.guest-write-record`), the breached-password check (`core.breached-password`, with the service
   address the instance actually uses — a mirror replaces HIBP's name), the map and geocoding services.
3. **New instance values**, set in admin-web's operator panel and `veyq-admin operator set`: a list of **recipients**
   (name, what they do, country) and an optional **data-protection officer** contact. admin-web pre-fills suggested rows
   from what the instance knows (an external store's host, the instance mail relay's host) for the Server Owner to
   confirm; nothing is published unconfirmed. N and B come from their own settings.
4. **A space's mail relay is named generically**: the space's owner chose it, and the notice says so and points to them —
   the per-space relay is not rendered (it is the space owner's configuration, and who controls it is Still open 6).
5. **The links**: the provider identification's footer gains a "Privacy" link and is mounted in the app shell too, so
   the notice is one click from every screen (this also puts the identification in the signed-in app); the invite
   acceptance, the report dialog's contact field and the guest-write record's first layer link to the passage that
   concerns them.

## Open questions (owner) and legal items

**Decided (Q26–Q28).** Owner 2026-10-18, one by one: all three as recommended. **Added the same day** (from the guest-write-record objection tool, `veyq-admin guest-write-record forget`): a guest's own view shows a copyable **guest id for an objection** in its session line, so a guest can name the identity whose rows the Server Owner erases (GDPR Art. 21(1) with 12(2)); the id is the share identity's random id and reveals nothing else. An objection erases the rows so far and does not stop later writes from being recorded — the notice says so (owner: keep it so).

- **Q26 — Shown in the *private* mode too?** **Recommendation: yes.** Every passage is a fact of the software, true on
  every instance; the household exemption only makes it not owed. In *private* an unset value is absent and nothing
  warns (ADR 0035 decision 8).
- **Q27 — The guest-write record's Art. 13 passage: here, or in the operator section where it is built?**
  **Recommendation: here** — one home for every Art. 13 passage; the operator section keeps a one-line pointer and the
  first layer links to the notice. It reopens no gate: the notice has none, and the operator section's revision moves
  once, with the pointer.
- **Q28 — Recipients as a list the Server Owner keeps, or fixed slots** (host, store, mail)? **Recommendation: a list**,
  with the suggested rows of decision 3; fixed slots miss a backup host or a second relay.
- **Legal (blockers, named, not guessed):** who is the controller of a member's account data — the Server Owner alone
  or jointly with each tenant owner (Art. 26; ADR 0093 Still open 6) — the notice names the Server Owner until answered;
  the breached-password check on by default (Art. 6(1)(f), a third-country transfer, Art. 25(2); Still open 7) — the
  notice states it as it runs; whether a first-layer sentence plus this link meets Art. 13 on a guest page, and what
  Art. 14 asks towards a third party a report names (Still open 10). Every text is a draft until a qualified review
  (`draft: true` renders the banner, as the operator section does).

## Phases

- [x] **0 docs** — `uc.admin.privacy-notice` and `uc.admin.operator-details` (the two new values) corrected to the
  owner's answers; ADR 0035 decision 6 or 8 moved where Q27 goes. · Done when: feature-format, coverage, doc-link,
  doc-citation, change guards green.
- [x] **1 contracts** — the text file (DE and EN, drafts from the inventory above and the 2026-10-16 research), the
  public read, the recipients and officer values on the operator operations. · Done when: `npm run generate:api`; the
  contracts guards; `npm run check` in `web/` and `admin-web/` once, naming the typed lines the operator request's new
  fields redden (`grep -rln 'supervisoryAuthority' web/src admin-web/src backend/core/src/test e2e`).
- [x] **2 backend** — the renderer, the switch-bound passages, the public read per host and locale, the values. · Done
  when: `./gradlew check` green. **Bites, with controls:** the record's switch off → its passage absent (control: on →
  present, with N); `core.breached-password` with a mirror → the mirror's host named, HIBP not (control: default →
  HIBP); *private* with no recipients → the passage absent (control: *organisation* → "not stated by this operator");
  the read answers without a session and writes nothing (`grep` the request log for an address → none).
- [ ] **3 web + admin-web** — the notice's page (public route on every tenant host), the footer's link and its mount in
  the app shell, the links at the invite acceptance, the report dialog and the first layer; admin-web's recipients and
  officer fields with the suggested rows; the CLI flags. · Done when: `npm run check` green in both; a browser pass of
  the notice signed out, from the invite page, from the report dialog and from a guest's first write, in DE and EN and
  in both modes.
- [ ] **4 docs** — ADR 0035's status (decision 8 built); the security model's sentence on what is disclosed where;
  site: `privacy/is-my-data-safe.md` and its DE twin (point to the instance's own notice for what *this* instance
  keeps), `run/first-run.mdx` or `run/moderation.md` (the values the notice renders and the Server Owner's duty to keep
  them true; the record of processing, processor agreements, a breach and an access request — the audit's D items —
  are **already written** on `run/data-protection.md`, inbox #485: point there and keep its tables in step), coverage refs
  for the three use-cases. · Done when: coverage, wiki-sources and the site check green.

## Definition of done

Every phase's Done-when; `uc.admin.privacy-notice` covered (`status: planned` dropped) on `backend` and `web`,
`admin-web` for the values; every `scripts/validate_*.py` except `validate_guard_gaps.py` green. Implementation waits
for the owner's go; a legal blocker above blocks only the passage it names, which ships marked as a draft.

## Retrospective

_Filled at implementation._
