---
id: cr-operating-mode-retires
title: No operating mode — one form of every fixed text, nothing required, and the guest-write record kept wherever a guest may write
status: planned
satisfies: ["uc.admin.operator-details", "uc.admin.setup-server", "uc.admin.privacy-notice", "uc.admin.tenant-terms", "uc.account.accept-terms", "uc.sharing.share-accept", "uc.sharing.share-contributor", "uc.sharing.share-uploader", "uc.depot.drop-box", "uc.moderation.object-to-a-measure"]
adrs: ["0093", "0035", "0088"]
invariants:
  - the report path, the hold, the measures, the moderators, the order path and the Art. 18 export stay on every instance, unchanged
  - the guest-write record keeps what ADR 0093 § 10 lists and nothing more; N (1 to 30, default 7), the two-limit purge, the hold's copy, the Art. 21 objection and the backup without its rows are unchanged
  - nothing a Server Owner may set becomes required, and no setup, save or publish is ever refused over an empty value
  - a tenant still only narrows: guest writes switched off in a tenant means no record there
---

## Goal

The operating mode goes (owner 2026-10-21). Today a Server Owner picks *private* or *club, organisation or company*
at setup; the mode shortens the operator section of the terms and the statement of reasons, decides whether admin-web
warns about empty operator values, and, as a preset, switches the guest-write record off in *private*. After this CR
every instance serves **one** form of each fixed text — today's full form — with every empty value shown as "not
stated by this operator"; nothing is required and nothing warns; and the guest-write record has no switch: a row is
written whenever a link guest writes, so `contribute` and `drop-box` decide where it is kept. What the Server Owner
needs to judge which values they owe becomes information at setup, in the operator settings and in the Run docs.

The decision and its reasons are refined state already: ADR 0093 §§ 7, 10, 11, 14 and its alternatives, ADR 0035
decisions 6–8, ADR 0088 decision 14 (edited 2026-10-21). This CR builds it and brings use-cases, coverage, the
enabler, the security model and the site along. It is mostly deletion.

## Context & constraints

- **Pre-release** (`scripts/applied_migrations.sha256`: `release: none`; ADR 0088 decision 10 allows the catalog's last
  cut). The capability id is removed, not deprecated. Applied migrations are never edited, so the schema changes are
  **new** migrations with their pins: the instance switch row of the removed id deleted; `operating_mode` dropped from
  the operator details (`V202610171752__core_operator_details.sql`); `variant` dropped from the accepted-terms row
  with its UNIQUE constraint rebuilt on the pair (`V202610181600__core_terms_operator_section.sql`).
- **Trap 1: a removed id reads as off.** `CapabilityRegistry.isActive` answers false for an id it does not advertise.
  The privacy notice's record passages carry `"switch": "core.public-shares.guest-write-record"`; the key goes with
  the id, or both passages vanish silently.
- **Trap 2: everyone accepts again.** The operator section loses its variants and its revision moves; every member
  and guest of every instance meets the gate once. Pre-release, so expected; the acceptance becomes a **pair**
  (space revision, section revision): `AcceptTermsRequest.variant` leaves the wire, and the guest device keeps two
  pairs instead of four triples (`src/web/src/core/terms/operatorSection.ts`, `resolvePublicShare.ts`).
- **Trap 3: the full statement refuses what the reduced one allowed.** `statement_facts_required` and
  `statement_provision_required` (`CaseDecisions.kt`, `StepStatements.kt`, and `CaseDetail.vue` on the client) now
  apply on every instance; the *optional* labels and the reduced hint (`provisionOptional`, `factsOptional`,
  `factsHintReduced`) go.
- **Trap 4: a DSA-only right must not be promised.** The one sentence that grants the reader a right only the DSA
  gives is the reporter redress's complaint to the Digital Services Coordinator (`statement-of-reasons.json`, Art. 53):
  it gains *where the DSA reaches this instance* in both languages (ADR 0093 § 7). The other DSA/TCO lines describe
  what the instance does (Art. 18 reports, the hour for an order, the contact point) and stay as they are; the privacy
  notice's legal-basis lines naming the DSA and TCO stay for the qualified review the whole text already waits on.
- **What the Server Owner is told** is ADR 0093 § 14's second paragraph, once: at setup (where the mode choice was,
  with no tick and no choice), in admin-web's operator settings beside the values, and on the Run page. The CLI's
  `mode` commands go without a replacement; `operator show` prints the values with their gaps.
- **The guest page needs no new code.** `GuestWriteRecordNotice.vue` renders on `share.guestWriteRecordDays`; the
  server stops nulling it for the switch.
- **Theme presets are not this CR's.** `preset` also names PrimeVue's theme preset (`main.ts`, the styleguide, the
  contrast guard).

### Locators (run at refine 2026-10-21 against `fec24b6c` plus this refine's ADR edits; rerun at implement)

```bash
# angle 1: identifiers
git grep -nE 'OperatingMode|operatingMode|operating_mode|SectionVariant|OperatorSectionVariant|StatementForm|statementForm|REDUCED|fullForm|fullOnly|reduced\b|operatorWarnings|needsAcceptance|operatingModeNotice|statement_(facts|provision)_required|"full": true|"part": "full"|/operator/mode|mode (show|set)|factsHintReduced|provisionOptional|warn(Contact|Identification|Privacy)|guest-write-record|GUEST_WRITE_RECORD|CapabilityPresets|presetChanges|\bmodes\b|switchHint' -- contracts src scripts tests ':!**/generated/**'
# angle 2: prose, in docs, site, i18n and tests
git grep -niE 'operating[ -]mode|Betriebsart|private mode|\*private\*|reduced (variant|form|section|notice|statement)|full (variant|form|section|notice)|both variants|either (operating )?mode|per mode|organisation mode|club, organisation or company|Verein, Organisation|required means warned|(on|auf) (a|einem) privat|most servers|meisten Servern|preset' -- docs site contracts 'src/*/src/i18n' tests ':!docs/decisions'
```

At refine the angles reached, besides the code named in the traps: the mode's handler, resources, setup and store
(`OperatingModeHandler.kt`, `OperatorResources.kt`, `OperatorDetailsHandler.kt`, `SetupHandler.kt`, `SetupStore.kt`),
the renderers (`OperatorSection*`, `FixedTextLines.kt`'s `reduced`, `PrivacyNotice*`'s `fullOnly`,
`StatementTemplate.kt`/`StatementMail.kt`/`Statements.kt`/`CaseViews.kt`), the codegen (`buildSrc/…/OpenApiTasks.kt`),
`validate_capabilities.py`'s `modes` rule, admin-web's mode UI, warnings, setup step, console and CLI, the web's
statement template and terms gate, the e2e setup and two specs, ten use-cases, six coverage files, the enabler,
`security-model.md`, `docs/inbox.md` #496 (scoped to "the club mode") and the site pages `run/moderation.md`,
`run/first-run.mdx`, `run/configuration.md`, `run/data-protection.md`, `account/moderate.md` (EN+DE),
`help/report-something.mdx` (EN+DE) and `security/what-the-server-sees.md`. Angle 2 also hits ADR history sentences
and PrimeVue; those stay.

## Decisions this CR takes

1. **One form per text**: the full one. `operator-section.json` and `statement-of-reasons.json` lose their
   `full`/`reduced` markers and the note's variant rules; `privacy-notice.json` loses `part` and the reduced-variant
   rules of `about` and `optional` (an empty value renders as `gap`, an `optional` line is still left out). Each
   moves its revision.
2. **The mode leaves storage, wire and UI**: the column, `/api/instance/operator/mode`, `OperatingMode`,
   `InstanceOperator.operatingMode`, the setup request's field, `OperatorSectionVariant`, `PrivacyNotice.variant`,
   `StatementForm`, the admin-web panel, choice and notice, the CLI's `mode`. A request field leaving makes both
   clients red until their phase (`changes/README.md` § Phases).
3. **No warnings**: `operatorWarnings` and every caller go; the operator panel shows each empty value as not stated,
   in admin-web's own words, beside § 14's information.
4. **The guest-write record has no switch**: the catalog entry with its `modes` (the only preset, so the preset
   codegen and `validate_capabilities.py`'s rule go too), the write path's capability check, the purge's
   "switched off ⇒ delete all", `disclosedDays`' null-when-off, the renderers' `recordOn`.
5. **The acceptance is a pair** (trap 2), on the wire, in the store, in the guest device's record.
6. **One test proves the mode is gone**: an instance set up with no mode serves the full section, the full notice with
   gaps, the record passage, and records a guest write; with a positive control that a set value replaces its gap.
   It replaces the mode-pinning tests the locator finds (the four of `OperatorDetailsSliceTest`, the variant tests of
   `TenantTermsOperatorSectionSliceTest`, the form tests of `StatementsSliceTest`, `StatementMailSliceTest`,
   `StatementTemplateConformanceTest`, the privacy-notice mode tests, `SetupServerSliceTest`'s mode refusal).

## Phases

- [x] **1 contracts**: decisions 1, 2 and 4 in `contracts/` (catalog, OpenAPI, the three fixed texts with trap 4's
  sentence), the `modes` rule out of `validate_capabilities.py`. · Done when: `generate:api` in both clients and the
  contracts guards green; the predicted reds — the backend's users of the removed generated types, both clients'
  setup request and `variant` readers — seen once by building.
- [ ] **2 backend**: decisions 2–6; the three migrations with their pins; the tests rewritten. · Done when:
  `./gradlew check` green. **Bite:** with the capability check put back into the write path, decision 6's
  "a guest write is recorded" goes red; with the `switch` key put back into the privacy notice, its record-passage
  assertion goes red; with a `variant` column left in the UNIQUE constraint, the accept-a-pair slice test goes red.
- [ ] **3 admin-web**: setup without the mode step and with § 14's information; the operator panel without warnings
  and with the gaps shown; the mode panel, choice, notice and CLI `mode` gone; `guestWriteRecord.switchHint` pointing at
  `contribute` / `drop-box`; the step-statement hints required. · Done when: `npm run check` in `src/admin-web/`
  green; setup, the operator panel and a step over content clicked in a browser.
- [ ] **4 web**: trap 3's client half, the terms gate on pairs, the tests that pinned variants or "switch off ⇒ no
  notice", the seeds (`instanceBirth.mts`, `e2eBootstrap.ts`). · Done when: `npm run check` in `src/web/` green; the
  terms gate and a moderator's statement clicked in a browser.
- [ ] **5 docs, site, coverage, e2e**: the use-cases, coverage files, enabler and security model the locator finds,
  ADR 0093's, 0035's and 0088's status lines to *built*, inbox #496 rescoped; the site pages above (Use pages EN and
  DE together); `first-run.setup.ts` without the mode and the two mode specs rewritten to the one form. Running L1
  waits for the V1 heavy stage. · Done when: the trust-chain guards green, `validate_guard_gaps.py --force` included
  (phase 1 changed a validator); the site check green; both locator angles return only ADR history and PrimeVue.

## Definition of done

Every phase's Done-when; no `operating_mode`, `variant` or removed-id row on any database after migration; on an
instance with every operator value empty, every fixed text renders whole with its gaps and a guest write is recorded.

## Retrospective

_Filled at implementation._
