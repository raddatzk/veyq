---
id: cr-release-notices
title: An instance looks for new releases and tells its Server Owner — console notices and instance mail
status: planned
satisfies: ["uc.admin.learn-of-new-releases"]
adrs: ["0097", "0096", "0085", "0048", "0049", "0050", "0065", "0089"]
invariants:
  - "Operator-blind: the check sends nothing about the instance's content or users; the notice mail carries no user data (ADR 0049)"
  - "Self-hostable by default: the check can be switched off and pointed at a mirror, and nothing waits on it"
  - "ADR 0048: tenant mail is untouched — instance mail never sends a tenant's mail and a tenant's settings never send a notice"
  - "ADR 0050: the instance mail password is sealed under the config-secret key and its fingerprint joins the instance-wide key state"
---

## Goal

Build [ADR 0097](../docs/decisions/0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md): a stamped
instance reads the published releases once a day (on by default, switchable, a mirror possible), the admin console
shows what is newer — a release of the running major, a planned new major with the date the running major leaves the
client window, an unscheduled one as urgent — and, once the Server Owner has saved and confirmed instance mail
settings, each notice is mailed once, a dated one with an `.ics` event.

Implementation waits for the owner's go on this CR.

## Context & constraints

- **Use-case:** [uc.admin.learn-of-new-releases](../docs/features/admin/use-cases/uc.admin.learn-of-new-releases.md)
  (coverage `planned`; this CR fills it).
- **Entry condition: [`cr-veyqctl`](cr-veyqctl.md) phase 1 has landed** — the mirror layout (`releases.json`, per-tag
  `manifest.json` with `unscheduled`) is what the check reads, and it is defined there. Until it lands, this CR cites it
  as planned (§ *a sibling CR still in flight*).
- **Owner decision before phase 1 — the confirmation is a code, not a link.** ADR 0097 decision 5 says "confirmation
  link", but the admin console is reachable on localhost only ([ADR 0065](../docs/decisions/0065-admin-ui-cli-separate-service.md)),
  and the instance has no address of its own (only tenants do — `uc.admin.setup-server`, "No instance name either").
  A link opened on a phone reaches nothing. Proposed: the test mail carries a short one-time code the Server Owner
  types into Server → Mail. If the owner agrees, phase 4 edits ADR 0097 decision 5 and the use-case's step 3 in place.
- **Who checks: a stamped release only.** An unstamped build (`veyq.release.version` empty —
  `grep -n "veyq.release.version" backend/core/src/main/resources/application.properties`) and a `-SNAPSHOT` are on no
  release line and check nothing. That keeps every test lane quiet by construction (local images and quarkusDev are
  unstamped), which is what ADR 0097's "offline runs" consequence needs, without a test-only switch.
- **The model to mirror, measured** (Explore ledger 2026-10-16, re-run the locators): tenant mail settings —
  `contracts/api/tenants.openapi.yaml` (`getTenantMailSettings`, `updateTenantMailSettings`, schemas
  `TenantMailSettings`/`TenantMailSettingsUpdate`), `TenantMailSettingsHandler.kt` (`update` seals at :125-126),
  `TenantMailSender.kt` (`deliver` builds a `MailClient` per send, `setText` only — **no attachment support exists**:
  `grep -rln "MailAttachment\|text/calendar" backend` → none), `TenantMailSealedFingerprints.kt` (a sealed column needs
  its own `SealedValueFingerprints` bean, `ConfigSecretKeyGuard.kt:20-24`). Instance settings live in
  `contracts/api/instance.openapi.yaml` + `InstanceResource.kt` (class-level `@RolesAllowed(SERVER_OWNER)` and
  `@RateLimitExempt`) + `admin-web/src/features/instance/*Panel.vue` wired in `AdminConsoleView.vue`. Periodic work is
  `@Scheduled(every = "{veyq.<x>.every}")`, halted in tests (`ScheduledJobsHaltedInTestTest`). Outbound HTTP is the
  JDK `HttpClient` (`GarageStoreProvisioner.kt:19`: "no new dependency"). The one-time-token pattern is
  `core/crypto/Tokens.kt` (`random`, `hash`) with the `EmailConfirmationStore` mint/consume shape.
- **The window date** follows [ADR 0085](../docs/decisions/0085-one-major-moved-on-a-yearly-train.md) decision 5 — the
  running major stays in a store-shipped client's window until the second train after the one that made it
  non-current — computed from the release dates the check reads. The use-case's honesty note holds: until a
  store-shipped client exists, the date is when the window *would* close.
- **Guards and sweeps a new operation meets** (`changes/README.md` § contracts phase): `OperatorBlindProbeTest`
  (every new operation classified — the Server-Owner exclusion list, `OperatorBlindProbeTest.kt:264-276`),
  `AdminChannelPostureSweepTest` (`x-veyq-channel: admin` on each), `RateLimitPostureSweepTest` (InstanceResource's
  class-level exemption reason must still be true of the new operations), `validate_field_visibility.py`,
  `validate_write_concurrency.py`, `validate_request_bounds.py`, `validate_maxlength_storage.py`,
  `validate_migration_versions.py` (+ `scripts/applied_migrations.sha256`).
- **Mutation scopes:** grep the three manifests for every file this CR edits (`grep -n -e TenantMailSender -e Tokens
  backend/core/pitest.scope.json web/stryker.mutate.json packages/crypto/stryker.mutate.json`); a file in a scope keeps
  its I/O next door.

### Inventory — the locators

| What | Locator |
|---|---|
| Instance operations and their sweeps | `grep -n "operationId" contracts/api/instance.openapi.yaml`; `grep -n "getInstanceRelease" backend/src/test/kotlin/me/raddatz/veyq/core/http/OperatorBlindProbeTest.kt` |
| The tenant mail model | `grep -rn -i "mailsettings" contracts/api backend/core/src/main --include=*.kt --include=*.yaml -l` |
| Every caller of the sender (a reshape of `deliver` reaches them all) | `grep -rln "TenantMailSender" backend --include=*.kt` |
| Scheduled jobs and their properties | `grep -rn "@Scheduled" backend --include=*.kt`; `grep -n "\.every=" backend/core/src/main/resources/application.properties` |
| The newest migration | `ls backend/core/src/main/resources/db/migration \| tail -1` |
| admin-web's server panels and strings | `grep -n "serverPanel" admin-web/src/views/AdminConsoleView.vue`; `grep -n "release" admin-web/src/i18n/index.ts` |
| Pages that speak for mail and releases | `git grep -ln -e "0048-provider" -e "0097-an-instance" -e "identify-instance-release" -- site/src/content/docs` |

## Phases

- [ ] **1 contracts.** In `instance.openapi.yaml`, all `x-veyq-channel: admin`, each field with `x-veyq-visibility`,
  each write with `x-veyq-concurrency`: `getInstanceReleaseNotices` (the check's state — enabled, source, last success
  — and the notices: kind `RELEASE | PLANNED_MAJOR | UNSCHEDULED_MAJOR | WINDOW_REMINDER`, release, date, found at);
  `setInstanceReleaseCheck` (enabled, source); `getInstanceMailSettings` / `updateInstanceMailSettings` (the tenant
  schema's transport fields, write-only password with `passwordSet`, `configSecretState`, and the confirmation state);
  `confirmInstanceMail` (the code). `npm run generate:api`; classify each new operation in `OperatorBlindProbeTest`.
  - **Done when:** `npm run generate:api` and the contract guards green. The operations sit on the existing `instance`
    tag, so the generated interface gains methods and `InstanceResource` stops compiling until phase 2 — that red is
    predicted; run `./gradlew check` once and record that it is exactly that red (§ *phases only above real size*).

- [ ] **2 backend: settings, the check, the notices.** A migration (instance mail settings singleton with sealed
  password, confirmed address and time, code hash and expiry; release-check settings defaulting to on with the GitHub
  source; notices unique on kind + release + date, with `mailed_at`); the sealed-fingerprint bean; the handlers in
  `core/admin/instance/`; `ReleaseCheck` as `@Scheduled(every = "{veyq.release-check.every}")` (default `24h`) using the
  JDK `HttpClient`, reading the mirror layout or the GitHub Releases API plus each candidate's `manifest.json`,
  comparing locally, writing notices idempotently, and logging a failure with nothing else waiting on it. A source
  reply is size-capped and parsed strictly; a malformed one is a failed check, never a notice.
  - **Tests:** a stub source (an in-test HTTP server) for each notice kind; an unstamped and a `-SNAPSHOT` build make
    no request (assert the stub saw none); switched off makes no request; the request carries no release (assert on
    what the stub received); a 500, a timeout and an oversized body each leave notices unchanged; the window date for a
    planned major against ADR 0085's rule with fixed dates.
  - **Done when:** `./gradlew check` green; `OperatorBlindProbeTest`, `AdminChannelPostureSweepTest`,
    `RateLimitPostureSweepTest` green; committed.

- [ ] **3 backend: instance mail.** Generalise the sender's transport so tenant and instance settings build the same
  `MailClient` (every caller from the locator keeps its behaviour — its tests are the check), add one attachment kind
  (`text/calendar`), and an `InstanceNoticeMailer`: the test mail with the code on save, the confirmation, and one mail
  per unmailed notice after a check, only with confirmed settings, only to the Server Owner's account address. The
  `.ics` is generated here (a `VEVENT` with `DTSTART;VALUE=DATE`, a stable `UID` per notice, no attendee).
  - **Tests:** against the dev Mailpit in the integration test, as tenant mail is tested; a notice mailed once across
    two checks; nothing mailed while unconfirmed; a changed address unconfirms; the `.ics` parses (one un-mocked parse,
    § *one un-mocked test runs before the mapping*); a tenant's settings never reach `InstanceNoticeMailer` and the
    instance settings never reach a tenant mailer (a test per direction); the config-secret key absent → no mail,
    state reported.
  - **Done when:** `./gradlew check` green; committed.

- [ ] **4 admin-web, docs, coverage.** Server → Release lists the notices (urgent styled as such) and the check's
  switch, source and last success; Server → Mail holds the settings, the send-and-confirm step and the code entry; the
  CLI's `release` prints the notices and `release check on|off` toggles (mail stays console-only, as tenant mail is,
  `admin-web/src/cli/commands.ts:8-9`). `admin-web/src/i18n/index.ts` EN and DE. ADR 0048 §4 edited in place ("no
  instance-wide secret for tenant mail"; instance notices per ADR 0097); ADR 0097 decision 5 per the owner's call on
  the code. Run pages: `run/storage-and-email.md` (instance mail), `run/operations.md` (the daily request and how to
  switch it off or mirror it), `run/configuration.md` if a key is added (`validate_run_config_reference.py` says). The
  coverage file loses `status: planned`, every criterion gets its backend and admin-web refs, `web` keeps its gap.
  - **Browser pass:** in a dev instance with a stub source and Mailpit — enable, see a notice, save mail, enter the code
    from Mailpit, see the notice mailed once with its `.ics`; switch off and see the panel say so.
  - **Done when:** `npm run check` in `admin-web/` and `site/` green; `python3 scripts/validate_coverage.py` green; the
    browser pass's evidence kept; committed.

## Definition of done

The use-case's criteria covered on backend and admin-web; the guards green (guard-gaps only if a guard changed — this
CR changes none); ADR 0048 §4 and ADR 0097 match what was built; the Run pages say what the instance asks the outside
and how to stop it. **Baseline** — before the first edit, on a detached worktree of `HEAD`:
`for f in scripts/validate_*.py; do [ "$f" = scripts/validate_guard_gaps.py ] && continue; python3 "$f" >/dev/null 2>&1 || echo "RED $f"; done`
and `./gradlew check` from `backend/`.

## Retrospective
