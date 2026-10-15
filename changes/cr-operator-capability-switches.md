---
id: cr-operator-capability-switches
title: The Server Owner switches capabilities in admin-web and the CLI; a device switch acts at once
status: planned
satisfies: ["uc.admin.switch-capabilities"]
adrs: ["0088", "0089", "0032", "0005", "0065"]
invariants:
  - "Operator-blind E2EE everywhere: a switch is instance configuration and carries no member data; withholding a wrap reveals nothing the server did not already hold"
  - "Self-hostable by default: a switch must never lock a member out (the PASSWORD wrap is always served) and must never delete what a member stored"
  - "API-first: the catalog contracts/platform/capabilities.json is the one source of the ids AND their EN/DE texts; the backend and every client follow it by generation, never by a hand-kept copy"
---

## Goal

`cr-capability-register-recut` deleted the boot-time list `veyq.capabilities.disabled`. Since then no built
instance can switch an entry of the capability register off: the only production `CapabilitySwitches`
(`NoCapabilitySwitchedOff`) answers that nothing is off. This CR gives the Server Owner the switch. It lives in
the admin console and in its CLI, goes through the Admin API, is stored in the database and takes effect without
a restart (ADR 0088 decision 12, ADR 0089). Every entry describes itself in English and German, written once in
the catalog (decision 13). A **device switch acts at once** (decision 5, owner 2026-10-15): switching off
`core.remember-device` or `core.remember-device.passwordless` withholds those wraps from the key material without
deleting them. Inbox #443 asked for this, and **it must land before V1**.

## Context & constraints

- **Use-case:** [uc.admin.switch-capabilities](../docs/features/admin/use-cases/uc.admin.switch-capabilities.md).
- **Decisions:** [ADR 0088](../docs/decisions/0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md)
  decisions 2, 3, 5, 12 and 13; [ADR 0089](../docs/decisions/0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md)
  (instance settings live in admin-web, the environment only bootstraps); ADR 0032 §4; ADR 0005 (the wrap
  methods); ADR 0065 (admin-web is a pure client of the Admin API, its CLI in the same package).
- **The precedent to copy:** the store setting (`cr-store-connection-in-admin-web`, done). Re-derive each of these
  locators before use:
  - the contract: `contracts/api/instance.openapi.yaml` `/api/instance/store`, `x-veyq-channel: admin`,
    `x-veyq-concurrency`
  - the resource: `InstanceResource.kt` (class-level `@RolesAllowed(SERVER_OWNER)`)
  - the handler: `StoreHandler.set()`, which invalidates the cache only **after commit**
  - the in-process cache: `StoreSettings` in `blobstore/StoreSetting.kt`, whose KDoc carries the one-process
    argument; the chart runs `replicas: 1` with `Recreate`
  - the table: added by **editing the baseline** together with its line in `scripts/applied_migrations.sha256`,
    allowed until the first release (`backend/CLAUDE.md` § migrations)
  - admin-web: `features/instance/`, the Server nav in `AdminConsoleView.vue`, `src/i18n/index.ts` (EN/DE key
    sets held equal by `i18n.test.ts`)
  - the CLI: the `COMMANDS` table in `src/cli/commands.ts`, tests in `cli.test.ts`
  - the probe: `OperatorBlindProbeTest`'s Server-Owner bucket
- **What exists:**
  - `CapabilityRegistry` asks `CapabilitySwitches.switchedOff()` on every `isActive` and does the AND along the
    path.
  - The seven ids are hand-pinned in `CapabilityIds.kt` and held to the catalog by a drift test.
  - The catalog entries carry `serves`, `source` and a dev-facing English `description`, but no user-facing text.
  - `validate_capabilities.py` never reads `description`.
  - The test seam `SwitchableCapabilities` is a `@Alternative` in `:core`'s testFixtures.
- **Out of scope:** share switches reaching existing links (inbox #442, its own CR); the tenant level (inbox #439
  step 2); configuration as code (#444).

## Decisions

- **D1, the catalog carries the words.** Every entry gains `title`, `purpose` and `whenOff`, each `{ "en": …,
  "de": … }`. `whenOff` says what switching off does to **new** and to **existing** data, as **built**. Until
  #442, a share entry says "new links are refused; existing links keep working". The dev-facing `description`
  stays. `validate_capabilities.py` requires all three fields with both languages non-empty; bite it first.
- **D2, generated, not pinned.** A build task (beside the one that generates `AdminChannelOperations` from the
  specs, `backend/buildSrc/.../OpenApiTasks.kt`) generates `CapabilityIds` and a texts table from the catalog.
  The hand-pinned constants and their drift test retire. Their job is now the generator's. The web constants are
  generated the same way if that is cheap; if not, they keep their conformance test, and the CR says which.
- **D3, the state.** A table `veyq_capability_switch` holds one row per entry switched off (`id` primary key,
  `switched_off_at`), added in the core baseline with its pin line. The production `CapabilitySwitches` reads it
  through an in-process cache that the write invalidates after commit, as `StoreSettings` does.
  `NoCapabilitySwitchedOff` retires.
- **D4, the API.** Both live on the admin channel for the Server Owner, with 401/403 as for the store:
  - `GET /api/instance/capabilities` returns the register as a list of `{ id, title, purpose, whenOff (both
    languages), off, effective }`. `off` is the entry's own switch. `effective` is the result of the AND, so a
    child of a switched-off parent shows `off: false, effective: false`.
  - `PUT /api/instance/capabilities/{id}` takes `{ off: boolean }` and returns the entry's new state, last writer
    wins (`x-veyq-concurrency`). It is idempotent, and an unknown id is `404` and stores nothing.

  Per-entry writes, not the whole set: that suits the CLI and makes two Server-Owner tabs unable to overwrite
  each other's unrelated switches.
- **D5, a device switch withholds its wraps.** `GET /api/account/key-material` leaves out every `DEVICE` wrap
  while `core.remember-device` is effectively off, and every `DEVICE_KEY` wrap while
  `core.remember-device.passwordless` is effectively off (its parent included). It works the way the `RECOVERY`
  wrap is withheld today (`account.openapi.yaml`, the key-material description). Nothing is deleted. `PASSWORD`
  and every other method stay served. The contract text of `getKeyMaterial` says so.
- **D6, the web client meets a withheld wrap.** This has to be **measured first**: what the client does today when
  the server has no wrap matching its local device key (`uc.account.trust-device` says it cleans up locally).
  - **Wanted:** the client falls back to the unlock its wrap replaced. Without `DEVICE` that is password and
    Secret Key; without `DEVICE_KEY` it is the password.
  - **The client keeps its local device key and its stored wrap id**, so switching back on restores the device.
    If today's client deletes them, this CR changes that, and the measurement and the change are written here.
- **D7, the console.** It gets a **Server → Capabilities** panel showing the register as a tree, in the console's
  locale:
  - each entry shows its title and purpose and one of on / off / "off through *parent title*";
  - switching off asks for confirmation and shows `whenOff`;
  - a device switch gets the explicit line that members may need their Secret Key. That line is part of the
    entry's own `whenOff` text, not a client string.
- **D8, the CLI.** Three commands:
  - `capabilities list` prints the tree with the state;
  - `capabilities off <id>` prints `whenOff` and switches;
  - `capabilities on <id>` switches back.

  The locale comes from `LC_ALL`/`LANG` (`de*` → German, else English), and `--lang en|de` overrides it. An
  unknown id exits 1 with the backend's refusal.
- **D9, the test seam.** Measure how many tests use `SwitchableCapabilities`. Where a test can switch through the
  real store (a slice test calling the Admin API through `givenAdminChannel()`, or a fixture writing the row), it
  does that, and the alternative retires once it has no user. A seam that stays is kept to testFixtures, and the
  re-cut's leak bite stays.

## Phases

- [x] **1 contracts: catalog texts, the API, the key-material text.** Done in 3f5f0496 (guard + texts) and
  dc6572a0 (the API, the key-material text, the probe bucket).
  - **Bite:** `validate_capabilities.py` against the catalog without texts was red 21 times (`<id>: title must be
    an object with en and de`, three fields on each of seven entries); a blank `de` and a missing `en` were each
    red by injection; green after: `capability catalog OK: 7 capabilit(ies) serving 12 built use-case(s)`.
  - **Guard glob:** 58 guards green. `./gradlew check` is red only at `:core:compileKotlin`: `Class
    'InstanceResource' is not abstract and does not implement abstract members: fun listInstanceCapabilities():
    Response!, fun setInstanceCapability(capabilityId: String!, instanceCapabilityUpdate:
    InstanceCapabilityUpdate!): Response!`.
  - **Names for phase 2 and 3:** operations `listInstanceCapabilities`, `setInstanceCapability`; schemas
    `InstanceCapability`, `InstanceCapabilityText` (`{ en, de }`), `InstanceCapabilityUpdate`; 404 code
    `capability_not_found`; 400 `invalid_request` for a missing `off`.
  - **Deviations.** The path parameter is `{capabilityId}`, not `{id}`: `validate_id_contract_format.py` holds every
    id-named input to `format: uuid`, so it records `capabilityId` in `NOT_A_ROW_ID` (bitten: red without it).
    `off` is quoted in the YAML, because PyYAML reads a bare `off` as the boolean false (measured: the property came
    out as `"false"`). The key-material description did not yet say that `RECOVERY` is withheld (only
    `RotatedWrap` did), so it now says both. And the passwordless `whenOff` says such a device needs the
    password **and the Secret Key**, not the password alone as D6 has it: a device holds exactly one convenience
    wrap (`uc.account.remember-this-device` § Postconditions; `LoginView.enrolConvenienceWrap` drops the `DEVICE`
    wrap when it enrols `DEVICE_KEY`), so without its `DEVICE_KEY` only the `PASSWORD` floor is left. Phase 4
    measures this before it builds D6's fallback; if it changes the client so a passwordless device keeps a
    `DEVICE` wrap, the text changes with it.
  - Order: `validate_capabilities.py` first, seen red on the catalog without texts, then the texts for all seven
    entries (D1).
  - The two operations in `instance.openapi.yaml` (D4).
  - `getKeyMaterial`'s withholding text (D5).
  - The Server-Owner bucket in `OperatorBlindProbeTest` gains the two operations.
  - **Done when:** the guard's bite is recorded, `python3 scripts/validate_capabilities.py` is green, and the full
    `scripts/validate_*.py` glob is green. `./gradlew check` is red only where the generated `InstanceApi` gains
    methods (expected, changes/README.md § contracts phases).
- [ ] **2 backend: generator, state, API, withholding.**
  - The work: D2's generator, D3's table, switches and cache, D4's handler on `InstanceResource`, D5 in the
    key-material handler, D9.
  - Tests that must bite:
    - switch off → the manifest drops the id, and the gate answers 422;
    - switch on → restored;
    - a parent off → a child's `effective` is false while its own `off` survives;
    - an unknown id → 404, with no row written;
    - a non-owner → 403;
    - `DEVICE` and `DEVICE_KEY` withheld and restored, with `PASSWORD` always present;
    - a write invalidates the cache without a restart (a second read in the same test sees it).
  - **Done when:** `./gradlew check` is green and every bite is recorded red → green.
- [ ] **3 admin-web + CLI: the panel and the commands (D7, D8).**
  - `npm run generate:api` in admin-web regenerates the existing instance client. This is not a fifth spec.
  - Tests: the panel's tree and the three states, the confirmation showing `whenOff` in both locales, the CLI's
    commands, its locale choice and its exit codes.
  - **Done when:** `npm run check` in `admin-web/` is green.
- [ ] **4 web: the withheld wrap (D6).**
  - The measurement first, written here.
  - Then the fallback, with its tests: `DEVICE` withheld → password + Secret Key; `DEVICE_KEY` withheld →
    password; local key kept; switched on again → the device unlocks as before.
  - **Done when:** `npm run check` in `web/` is green.
- [ ] **5 browser pass, the off states the re-cut could not run.**
  - Set up by changes/README.md § the browser pass: a separate DB, the fast-jar under the e2e profile,
    `npm run bootstrap:user`. Never a second quarkusDev next to the owner's.
  - In admin-web, switch off `core.public-shares.drop-box`, then `core.device-linking`, then
    `core.remember-device.passwordless`, then `core.remember-device`.
  - In the web client, see each one's affordance gone, and a device remembered beforehand ask for what its wrap
    replaced. Switch everything back on and see each one return.
  - **Done when:** each step is recorded with what was seen.
- [ ] **6 docs + coverage.**
  - `uc.admin.switch-capabilities` coverage carries real refs. Admin-web suites go under the ADR 0040
    awaiting-a-first-run reason, as the store's coverage does.
  - ADR 0088 § Consequences and ADR 0032 §4 say the switch is built.
  - `docs/features/admin/_index.md` marks the row built.
  - The site's Run area: `site/src/content/docs/run/moderation.md` (it says the instance advertises all seven)
    describes the panel and the CLI.
  - Inbox #443 is done.
  - **Done when:** `python3 scripts/validate_coverage.py`, `validate_capabilities.py` and the full guard glob are
    green.

## Definition of done

`./gradlew check` and `npm run check` in `web/` and `admin-web/` are green. Every new acceptance criterion of
`uc.admin.switch-capabilities` is covered by a test or an explicit gap, and `python3 scripts/validate_coverage.py`
is green. The full `scripts/validate_*.py` glob is green. The generator (D2) is the permanent guard against drift
between the catalog and the code, so no one-shot check needs promoting. Then harvest per changes/README.md and
delete this file.

## Risks

- **A device switch can send every member to their Emergency Kit.** With `core.remember-device` off, a member who
  relied on their `DEVICE` wrap needs the Secret Key. That is the decision, not an accident (owner, 2026-10-15),
  so `whenOff` and the confirmation must say it in those words. Switching back on restores every device.
- **Two console tabs.** Per-entry writes keep them from clobbering unrelated switches. On the same entry the last
  writer wins, as for the store.

## Retrospective

(filled in when done)
