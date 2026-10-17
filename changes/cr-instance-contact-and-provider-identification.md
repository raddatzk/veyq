---
id: cr-instance-contact-and-provider-identification
title: The operating mode, the instance contact and the provider identification, set in admin-web
status: in-progress
satisfies: [uc.admin.operator-details, uc.admin.setup-server]
adrs: ["0093", "0035", "0088", "0097", "0089", "0065", "0050"]
invariants:
  - operator-blind E2EE (these are public operational values, never content)
  - self-hostable by default (every value may stay empty: a club instance is warned, a private one is not, nobody is refused)
  - the mode is the operator's own assessment: it never switches the report path or the moderators off (ADR 0093 § 14)
---

## Goal

Build the instance's **operating mode** (ADR 0093 § 14, ADR 0088 decision 14): the Server Owner chooses *private
(family / circle of friends)* or *club, organisation or company* at first-run setup in admin-web and changes it later
in admin-web and `veyq-admin`; the choice carries the notice below; it is a **preset** that sets the instance-level
switches the catalog marks per mode, each of which stays individually switchable; and it decides, beyond switches,
whether the contact and the identification are required (warned while empty) or optional. Later CRs read it: the
operator section's variant (`cr-operator-section-of-the-terms`), the statement's form
(`cr-statement-of-reasons-and-objection`), and the first entry the preset sets (`cr-guest-write-record`).

And give the Server Owner one place, admin-web and `veyq-admin`, to set the values a host has to publish about
itself: the **instance contact** (ADR 0093 § 11: the single point of contact for users and authorities and the TCO
contact point, with its languages; a private notification address; the public address statements and disclosures
name) and the **provider identification** (ADR 0035 decision 7: name, address for service, email, and for a legal
person its representative), plus the **competent data-protection supervisory authority** that ADR 0035 decision 6
renders. The provider identification appears on the login and share pages; everything else is consumed by the later
moderation CRs and the operator section of the terms (`cr-operator-section-of-the-terms`).

This is first in the moderation work order because three later CRs name the contact (a guest's objection,
`cr-statement-of-reasons-and-objection`; the TCO contact point, `cr-authority-orders-and-art18-reports`; the new-case
mail's second recipient, `cr-report-and-hold`), three read the mode (named above), and it depends on nothing of them. It can run in parallel with
`cr-moderators-and-the-moderation-key`.

## Context & constraints

- Decisions: [ADR 0093](../docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
  § 11 (last bullet: the contact, its public status, admin-web's warning) and § 14 (the operating mode, what it
  decides per section, the notice), [ADR 0088](../docs/decisions/0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md)
  decision 14 (the mode is a preset, not a level), [ADR 0035](../docs/decisions/0035-server-terms-of-service.md)
  decision 7 (the identification, where it appears, the warning) and decision 6 (the supervisory authority is one of
  the values the operator section renders), [ADR 0089](../docs/decisions/0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md)
  (an instance setting lives in the database, set in admin-web, never in the environment),
  [ADR 0097](../docs/decisions/0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md) decisions 3 and 5
  (instance mail; its code confirmation), [ADR 0065](../docs/decisions/0065-admin-ui-cli-separate-service.md) (the
  admin channel; admin-web's four clients).
- Published, public data: the contact's public address, its languages, the identification and the supervisory
  authority are served pre-auth like the terms (ADR 0035 § Consequences). The **notification address is not public**:
  it is where moderation notices go (ADR 0097 decision 3), so it is served only on the admin channel.
- **The preset's first entry is not in the catalog yet.** `core.public-shares.guest-write-record` comes with
  `cr-guest-write-record` (planned; `grep -n 'guest-write-record' contracts/platform/capabilities.json` finds
  nothing). So this CR builds the preset's mechanism and its guard rule against a test register, and the bite "the
  guest-write switch defaults off in private" is **run by that CR** (its phase 2), which adds the entry's `modes` and
  applies the current mode's value in its migration (ADR 0088 decision 14: an entry that gains a preset later takes
  the current mode's value once).
- **Where moderation starts is not this CR's.** ADR 0099 § 3 keeps the report action off until the Server Owner
  mints epoch 1 (`grep -n 'Epoch 1 is minted' docs/decisions/0099-*.md`); the mode neither mints it nor switches
  reporting (ADR 0093 § 14's first row), and `cr-moderators-and-the-moderation-key` is not touched here.
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
| The setup post (pre-auth, admin channel, existing tag `setup`) the mode rides | `grep -n 'operationId: createServerOwner\|CreateServerOwnerRequest:' contracts/api/setup.openapi.yaml`; the flow: `grep -rn 'createServerOwner' admin-web/src/views/SetupView.vue admin-web/src/features/setup` |
| Instance switches are one row per switched-off entry; the write the preset reuses | `grep -n 'veyq_capability_switch' backend/core/src/main/resources/db/migration/*.sql`; `grep -rn 'class StoredCapabilitySwitches' backend/core/src/main` |
| The catalog generator and the guard the `modes` field joins | `grep -rln 'GenerateCapabilityRegister' backend/buildSrc`; `grep -n 'TEXT_FIELDS =' scripts/validate_capabilities.py` |
| admin-web's DE strings use *du* and "Tenant" | `grep -n 'bis du ihn wieder online nimmst' admin-web/src/i18n/index.ts` |

## Decisions this CR takes (each with the line that settles it)

1. **Three addresses, not one.** The contact point (public, with languages), the public address that statements and
   disclosures name (public; may equal the contact point), and the notification address (admin channel only). ADR 0093
   § 11 lists all three; only the first two are "public, in the operator section".
2. **Server-Owner operations go on `instance.openapi.yaml`** under a new tag `instance-operator`, admin-channel marked.
   A new tag generates a new interface, so the contracts phase stays green on the backend (`changes/README.md` § Phases
   only above real size, "an operation on a NEW tag ends green"). admin-web keeps its four clients.
3. **The public read is one unauthenticated operation** in a new core spec `operator.openapi.yaml`, Host-independent
   (the values are instance-wide): web generates a client for it. It returns only the public values.
4. **Empty is allowed everywhere; in the *club, organisation or company* mode admin-web warns.** While
   `core.public-shares` is on and no contact point is set (ADR 0093 § 11), and while the identification is empty
   (ADR 0035 decision 7). Never a refusal (ADR 0093 § 14: required means warned). In *private* neither warning shows.
5. **The mode is two values, `PRIVATE` and `ORGANISATION`**, stored as `operating_mode` on this CR's singleton
   (nullable; `NULL` reads as `ORGANISATION`, ADR 0093 § 14 "until one is chosen"). No time column, so no new reader
   in `EveryStoredTimeHasAReaderTest`. It is served on the admin channel only; nothing public needs it, since the
   backend renders what depends on it.
6. **First-run setup asks it.** admin-web's setup flow gains a step after the Emergency Kit's confirmation and
   before the post; `CreateServerOwnerRequest` gains a required `operatingMode`, so no instance completes setup
   without one. `uc.admin.setup-server` gains the step (phase 0).
7. **Changing it later** is a Server-Owner operation under the new tag (get, set). admin-web's Server → Operating
   mode panel shows the notice, then the switches the preset will change (from the catalog's `modes` against the
   current state), then sets. `veyq-admin mode show|set <private|organisation>` prints the same notice and refuses
   `set private` without `--accept-notice`.
8. **The preset is catalog data.** An entry may carry `modes: { "private": bool, "organisation": bool }`, allowed
   only on an entry whose `levels` holds `instance`; `validate_capabilities.py` checks both keys present and
   boolean, and the generator emits `CapabilityPresets`. Applying a mode writes or deletes the instance-level row
   (`StoredCapabilitySwitches`) for each entry that carries `modes`, in one transaction with the mode, and never
   touches a tenant's rows or an entry without `modes`.
9. **The notice is fixed text in admin-web's i18n** (EN+DE below), shown in the setup step, the panel and the CLI.
   Choosing *private* needs a tick in admin-web; the server does not record the tick (it would be ceremony, not
   evidence).

### The notice (draft; legal review before release, ADR 0093 § Legal basis)

> **EN.** Choose how this instance is run. This is your own assessment, not veyq's, and not legal advice.
> **Private (family, circle of friends):** veyq leaves off what only the GDPR, the Digital Services Act and the EU
> regulation on terrorist content ask of a host: recording a guest's address on upload, a required imprint and
> contact point, and the formal parts of the terms and of a statement of reasons. Reporting, blocking and moderators
> stay on: criminal law applies to every operator, and an operator who learns of illegal content must act.
> The GDPR's household exemption covers only a purely personal or family activity. It ends once a link is shared
> publicly (CJEU *Lindqvist*, C-101/01; *Buivids*, C-345/17). Whether the Digital Services Act and the terrorist-content
> regulation reach an unpaid private instance is legally uncertain. If you share links publicly, or run veyq for a
> club, an organisation or a company, you are probably not exempt: choose **Club, organisation or company**.
> Nothing is removed; you can change this at any time under Server → Operating mode.
> ☐ I have read this and choose *Private* on my own responsibility.
>
> **DE.** Wähle, wie diese Instanz betrieben wird. Das ist deine eigene Einschätzung, nicht die von veyq, und keine
> Rechtsberatung. **Privat (Familie, Freundeskreis):** veyq lässt weg, was nur die DSGVO, der Digital Services Act und
> die EU-Verordnung über terroristische Online-Inhalte von einem Hosting-Anbieter verlangen: das Festhalten der
> Adresse eines Gastes beim Hochladen, ein verpflichtendes Impressum und eine Kontaktstelle sowie die förmlichen Teile
> der Nutzungsbedingungen und einer Begründung. Melden, Sperren und Moderation bleiben an: Das Strafrecht gilt für alle
> Betreibenden, und wer von rechtswidrigen Inhalten erfährt, muss handeln.
> Die Haushaltsausnahme der DSGVO gilt nur für ausschließlich persönliche oder familiäre Tätigkeiten. Sie endet,
> sobald ein Link öffentlich geteilt wird (EuGH *Lindqvist*, C-101/01; *Buivids*, C-345/17). Ob der Digital Services
> Act und die Verordnung über terroristische Online-Inhalte eine unentgeltliche private Instanz erfassen, ist
> rechtlich unsicher. Wenn du Links öffentlich teilst oder veyq für einen Verein, eine Organisation oder ein
> Unternehmen betreibst, bist du wahrscheinlich nicht ausgenommen: Wähle dann **Verein, Organisation oder
> Unternehmen**. Nichts wird entfernt; du kannst das jederzeit unter Server → Betriebsart ändern.
> ☐ Ich habe das gelesen und wähle *Privat* in eigener Verantwortung.

## Open question (owner)

**Decided (Q1).** Owner 2026-10-17 ("Ja so übernehmen"): every recommendation in this section is taken, and implementation has the owner's go in the order of inbox #462. The legal blockers named here stay open; they are not owner decisions.

**Decided (the operating mode).** Owner 2026-10-17 ("Ja"): decisions 4–9 above, recorded in ADR 0093 § 14 and ADR 0088
decision 14. The notice's wording is a draft under the same legal review as every text of ADR 0093.

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
chooses and changes the operating mode, sees the notice and the preset's changes; sets, confirms and clears the values;
the warnings per mode; where each value appears), with a `status: planned` coverage file, and links it from
`docs/features/admin/_index.md`; and adds the mode step to `uc.admin.setup-server`'s main flow (a new criterion there,
planned). After phase 0 the front matter's `satisfies:` names both.

## Phases

- [x] **0 docs** — `uc.admin.operator-details` + its planned coverage YAML; the mode step in `uc.admin.setup-server`;
  ADR 0097 decision 5 gains the contact's confirmation (per Q1); `docs/features/admin/_index.md` lists it. · Done when: `python3 scripts/validate_feature_format.py`,
  `validate_coverage.py`, `validate_doc_links.py`, `validate_changes.py` green; `satisfies:` here names the use-case.
- [x] **1 contracts** — tag `instance-operator` on `instance.openapi.yaml` (get, put, clear, confirm the notification
  code; get and set the mode; admin-channel marked; bounds per field with `x-field-extra-annotation`); new spec
  `operator.openapi.yaml` with the public read; both classified in `OperatorBlindProbeTest`; `operatingMode` required
  on `CreateServerOwnerRequest`; the catalog's `modes` field, its rule in `validate_capabilities.py` (bite by
  snapshot-and-restore: a `modes` on an entry without `instance` in `levels`, and one missing a key → red each) and
  in the generator (`CapabilityPresets`). · Done when: `npm run generate:api` in `web/` and `admin-web/`, the
  contracts guards (`validate_request_bounds.py`, `validate_id_contract_format.py`, `validate_capabilities.py`) and
  `./gradlew check` green (two new tags end green, except the probe test until classified in this phase). **Predicted
  red:** the required `operatingMode` reddens admin-web's typed `createServerOwner` call until phase 3 (and the
  backend's setup handler only if it builds the request; name the lines `npm run check` and the build report).
- [x] **2 backend** — a singleton table `veyq_operator_details` (new migration, pinned in
  `scripts/applied_migrations.sha256`), the handler with the confirmation rule shared with `InstanceMailConfirmation`
  (one rule, not a copy), the code mail through `InstanceNoticeMailer`; rate-limit posture on each operation
  (`@RateLimit(USER)` on the confirm, as `confirmInstanceMail` has); readers in `EveryStoredTimeHasAReaderTest`.
  · Done when: `./gradlew check` green; a slice test per rule: a wrong code spends an attempt, a changed address
  unconfirms without a write, the public read never carries the notification address (asserted on the raw JSON);
  setup without `operatingMode` → `400`, with it → stored; an instance with `operating_mode` `NULL` reads as
  `ORGANISATION`; applying a preset (against a `CapabilityPresets` built in the test, as `CapabilityRegistryTest`
  builds its own register) writes the instance row of an entry whose private value is off, deletes it on
  *organisation*, leaves a tenant's row and an entry without `modes` untouched. **Bites:** drop the address comparison
  from the shared confirmation rule → the changed-address test is red; add the notification address to the public
  mapper → the raw-JSON test is red; make the apply step write the tenant table too → the tenant-untouched test is
  red; skip the apply step on a mode change → the organisation-deletes-the-row test is red. *The guest-write switch
  defaults off in private* is `cr-guest-write-record`'s bite (§ Context).
- [ ] **3 admin-web + CLI** — the setup step (the two modes, the notice, the tick for *private*); Server → Operating
  mode (the notice, the preset's changes listed before setting); Server → Operator panel (the three addresses,
  languages, identification, supervisory authority, the code entry as `InstanceMailPanel` has it) and the two
  warnings, both only in the *organisation* mode; `veyq-admin operator show|set|clear` (no code entry, per
  `commands.ts:12-13`) and `veyq-admin mode show|set` (`--accept-notice` for *private*). · Done when: `npm run check`
  in `admin-web/` green; a panel test per warning in each mode — *organisation*: shown with public links on and no
  contact, gone once set, the identification warning independent; *private*: neither shows with every value empty
  and public links on (the Impressum warning never shows in private); the setup step cannot post *private* without
  the tick; the CLI test for each command; the admin container build (`docker build -f admin-web/Dockerfile .` —
  Docker is the implementer's, not this refine's) or the CR states it did not run; a browser pass of the setup step
  and the mode panel in EN and DE. **Bites:** invert the public-links condition → the warning test is red; drop the
  mode condition from the warnings → the private test is red.
- [ ] **4 web** — the identification on the login page and the share page (a footer link opening the values, EN+DE
  via `web/src/i18n/index.ts`), fail-open on a failed read like the share's terms fetch (`ShareView.vue` `loadTerms`).
  · Done when: `npm run check` in `web/` green; a guest-mode mount proves the read is the public client
  (`src/test/guestMode.ts` `guardedClients`); a browser pass on a dev instance with and without values set.
- [ ] **5 docs + coverage** — Run page: the operating mode (what each mode sets, the notice's content, that it is
  the operator's own assessment), the settings and their warnings per mode (`site/src/content/docs/run/configuration.md`
  or `run/moderation.md`, whichever names admin settings today: `git grep -ln 'Server → Mail' site/src/content/docs`);
  coverage refs replace the planned gaps. · Done when: `validate_coverage.py`, `validate_wiki_sources.py`, `npm run
  check` in `site/` green.

## Definition of done

Every phase's Done-when; `docs/coverage/` for `uc.admin.operator-details` and the mode step of `uc.admin.setup-server` with refs on `backend`, `web` (and
`admin-web` only if `validate_coverage.py` no longer lists it as awaiting a first run); every
`scripts/validate_*.py` except `validate_guard_gaps.py` green (baseline at refine: all green,
`for f in scripts/validate_*.py; do python3 "$f"; done` on `61aa7756`). The site pages that speak for the settings
updated. The one structural rule the CR adds — a preset only on an instance-level entry, with both modes — is
`validate_capabilities.py`'s, bitten in phase 1; no other guard.

Implementation has the owner's go (2026-10-17), in the order of inbox #462; a legal blocker named above still blocks the part it names.

## Retrospective

_Filled at implementation._
