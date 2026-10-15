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
- **D3, the state.** A table `veyq_capability_switch` holds one row per entry switched off (`id` primary key
  only), added in the core baseline with its pin line. The production `CapabilitySwitches` reads it
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
- [x] **2 backend: generator, state, API, withholding.** Done in the commits after d73b50db (generator; switches, API,
  withholding and the seam's retirement; the restore gate; the contract texts).
  - **Generator (D2).** buildSrc's `GenerateCapabilityRegister` (`OpenApiTasks.kt`, registered for `:core` beside
    `generateAdminChannelOperations`, catalog as `@InputFile`) writes `CapabilityIds` (constants + `ADVERTISED`, catalog
    order) and `CapabilityTexts.BY_ID` into `:core`'s `me.raddatz.veyq.core.platform.capabilities`, so no import moved.
    It refuses a non-dotted id, two ids naming one constant and a missing or blank text. The hand-pinned file and its
    one-way drift test are gone. `CapabilityRegistryTest.theRegisterIsEveryCatalogEntryInCatalogOrderWithItsWords`
    takes the drift test's coverage ref: it proves the build reads the catalog, not a copy (a generator that reversed
    the order was red there). **Web constants: phase 3/4's question, untouched.** The Dockerfile now copies
    `contracts/platform/capabilities.json` (it copied only `contracts/api`, so the image build would have had no
    register; not rebuilt here).
  - **State (D3).** `veyq_capability_switch (id VARCHAR(64) PK)` in the core
    baseline, pin line updated. `StoredCapabilitySwitches` caches the set, and its `invalidate()` after commit bumps a
    generation that the cached set is tagged with. A plain null-out, as `StoreSettings` does it, lets a reader that
    queried just before the commit re-cache the old set after the invalidate; the same window exists in
    `StoreSettings` and is left alone here. A write takes `VeyqInstance`'s row lock, so two first switch-offs of one
    entry cannot both insert. `NoCapabilitySwitchedOff` retired. The baseline first carried a `switched_off_at`
    nobody served; the owner dropped it (2026-10-15), with its pin line and its `EveryStoredTimeHasAReaderTest` entry.
  - **API (D4).** `CapabilitySwitchHandler` (commit → invalidate → read back, as `StoreHandler.set` does);
    `CapabilityRegistry.register()` gives `off` and `effective` from one read of the switches.
  - **Withholding (D5).** `KeyMaterialHandler.forUser`, where RECOVERY was already withheld.
  - **Seam (D9).** Before: 3 classes, 12 tests on `SwitchableCapabilities` + `CapabilitySwitchesProfile`
    (`CapabilityDisabledSliceTest` 7, `DropBoxCapabilitySliceTest` 3, `ReactivateShareCapabilitySliceTest` 2). After:
    0. All three run on the default profile through `CapabilitySwitchFixtures.with(...)`, which writes the row through
    `CapabilitySwitchStore` and invalidates; the Admin API path is `InstanceCapabilitiesSliceTest`'s. The seam and its
    profile are deleted. The leak bite stays in `CapabilityManifestSliceTest`, now asserting `StoredCapabilitySwitches`.
  - **Beyond the brief, because the table made it wrong.** `scripts/instance_backup.py`'s restore emptiness gate
    (`NOT_DATA`) now also skips `veyq_capability_switch`: a switch set on the new install before a restore is
    configuration, not data, and must not refuse the restore. Its selftest and one sentence of
    `site/.../run/backup-and-restore.md` follow.
  - **Bites, each red once by injection, restored byte for byte:**
    - cache ignores the store → `aSwitchOffLeavesTheManifest…` red;
    - switch-on never deletes → same test red;
    - no invalidate after the write → same test red;
    - `effective` without the AND → `aParentOffSwitchesItsChildrenOff…` red;
    - `off` following the parent → same test red;
    - unknown-id check removed → `anUnknownIdIsRefusedAndStoresNothing` red (500);
    - a row stored before the 404 → same test red (`[core]` stored);
    - `@PermitAll` on the list → `onlyTheServerOwnerReadsOrSwitches` red (200);
    - DEVICE not withheld → `rememberDeviceOff…` red;
    - DEVICE_KEY not withheld → `passwordlessOff…` red;
    - DEVICE deleted on read → `rememberDeviceOff…` red on the restore;
    - PASSWORD withheld → same test red;
    - the write lock removed → `twoSimultaneousSwitchOffs…` red (`[500, 200]`);
    - the generator reversing the order → `CapabilityRegistryTest` red;
    - catalog: an injected entry re-ran the task and appeared as `BITE_PROBE`, compiling with a `$` and a `"` in its
      text and `*/ /*` in its description; a blank `de` failed the build naming the entry.
  - **Measured:** `./gradlew check` green, 1596 tests (root 105, core 1069, memories 355 / 2 skipped, depot 67). The
    three switch classes are green on MariaDB 11.4 (`run_backend_races_on_mariadb.py --tests …`, 20 tests). The guard
    glob is green, 58 guards.
  - **For phase 3:** the wire is `InstanceCapability { id, title, purpose, whenOff, off, effective }` in catalog order,
    a parent before its children, so the tree is the id prefix. PUT answers the entry as it now stands and is
    idempotent, an unknown id is `404 capability_not_found`, and a missing `off` is `400 invalid_request`.
  - **For phase 4:** a withheld wrap is simply absent from `wraps` in the key material. Nothing tells the client why,
    so what it does with an absent wrap that matches its local device key is the measurement D6 asks for.
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
- [x] **3 admin-web + CLI: the panel and the commands (D7, D8).** Done in 634765d4.
  - **Built.** The facade `features/instance/capabilities.ts` holds the two calls and the one rule both surfaces
    share: `capabilityTree()` places each entry by its id path and names, for `off: false, effective: false`, the
    nearest ancestor whose own switch is off. The state follows the served `effective`; no AND is recomputed.
    `CapabilitiesPanel.vue` sits under Server beside Release, Store and Config-secret key. Switching off goes
    through `useConfirm` with the entry's `whenOff` as the message; switching on asks nothing (the suspend /
    reactivate split). A row re-renders from the PUT's answer, and a switched entry with descendants re-reads the
    list, because their `effective` is the server's. The 13 `capabilities.*` keys are UI chrome only.
  - **CLI.** `capabilities list | off <id> | on <id>`, `--lang en|de` over `LC_ALL`, then `LANG`. Two generic
    additions to the table make that possible: `Command.operand` (one positional; `splitCommand` matches an operand
    command on its own words, so a stray word on any other command still reads as unknown) and `Flag.oneOf`
    (checked before sign-in). The three commands print text through `Command.render` rather than the JSON
    document, since their output is the served words for a person. The state is a fixed token (`on`, `off`,
    `off through <id>`), as `config-secret` prints `MISMATCH`.
  - **Deviation.** `capabilities off` prints `whenOff` from the PUT's answer, so after the switch rather than
    before it. That needs one call instead of two, the text is the server's word for the entry it actually
    switched, and an unknown id prints nothing but the refusal (`HTTP 404 capability_not_found`, exit 1).
  - **Bites, each red once by injection and restored byte for byte (`git status --porcelain` unchanged):**
    - state ignoring `effective` → the tree, the nearest-ancestor, German, switch-on and re-read cases red, and the
      CLI list;
    - the farthest off ancestor instead of the nearest → the nearest-ancestor case, German, and the CLI list red;
    - `whenOff` always English → the German confirmation red;
    - titles always English → both German cases red;
    - cancel still switching → the cancel case red;
    - switching on asks too → switch-on, re-read and refusal cases red;
    - the PUT's answer not adopted → both switch cases red;
    - no re-read after switching a parent → the re-read case red;
    - `capability_not_found` not mapped → the refusal case red;
    - the console's nav not rendering the panel → `AdminConsoleView`'s new case red;
    - CLI: `LC_ALL` ignored, the `--lang` override ignored, `--lang` values unchecked, `off` printing no `whenOff`,
      `off` sending on, the refusal swallowed, the operand not split off the words, and the tree not indented,
      each red in its own case.
  - **Measured:** `npm run check` green in admin-web, 28 files and 215 tests (21 new: 10 panel, 10 CLI, 1 console).
    The guard glob is green.
  - **Web constants (D2's open half): not generated; they keep their conformance test.** Generating them would
    need a generator script, a hook in web's `generate:api`, and a `COPY` of the catalog into the root
    `Dockerfile`'s web stage, which copies only `contracts/api`. Then 14 importing files either move or go through
    a re-export. The gain would be small. The pin is one-directional on purpose: ADR 0032 §6 lets the catalog
    reserve an id ahead of any UI, and a generator would emit a constant for every id. The test reads the
    catalog live on every `npm run check`, while generated code goes stale between generations, which `check`
    does not rerun. And each pinned constant's KDoc names the UI it gates, which a generated one cannot. The
    words are never needed in `web/`: only admin-web shows them, and it shows them as served.
  - **For phase 4:** nothing in admin-web depends on it.
  - **For phase 5:** the panel is Server → Capabilities (DE "Funktionen"). The CLI in the container is
    `veyq-admin capabilities off <id>`, and `capabilities list` shows which ancestor an entry is off through.
  - `npm run generate:api` in admin-web regenerates the existing instance client. This is not a fifth spec.
  - Tests: the panel's tree and the three states, the confirmation showing `whenOff` in both locales, the CLI's
    commands, its locale choice and its exit codes.
  - **Done when:** `npm run check` in `admin-web/` is green.
- [x] **4 web: the withheld wrap (D6).** Done in 9e1710cf; the measurement in 300d5bf3.
  - **Measured (2026-10-15, before any change; the six suites on it green, 54 tests):**
    - **The unlock modules already keep the local key on a missing wrap.** `unlockWithDevice`
      (`web/src/features/account/unlock/unlock.ts:177`) looks its DEVICE wrap up by the trust record's `wrapId` and
      at `:190` returns `false` without touching the record (`unlock.device.test.ts` "falls back … without
      destroying the local key"). `restoreDeviceKey` (`rememberMe/deviceKeyWrap.ts:131`) does the same for
      DEVICE_KEY at `:139`, and `restoreVaultFromCache` (`unlock.ts:154`, `:160`) then lands on the sign-in screen
      (`deviceKeyWrap.test.ts` "restore returns null (keeps the local key) …"). No error is shown on either path.
      `uc.account.trust-device` E1 says this, not "cleans up locally": only an explicit untrust or a re-trust
      cleans up.
    - **The fallback is password and Secret Key for both tiers.** A trusted device's sign-in hides the Secret-Key
      field (`LoginView.vue:107`, from the local trust record alone), and when `unlockWithDevice` answers `false`
      it reveals the field without an error (`:176-181`), so the member submits a second time. A remembered device
      has no trust record (one wrap per device: `enrolConvenienceWrap` drops the DEVICE wrap before it enrols
      DEVICE_KEY, `:135`, and the reverse at `:141`), so its field shows from the start. Both then go through
      `unlockMasterKey` (`unlock.ts:104`), the PASSWORD floor.
    - **The defect is the enrolment after that fallback, not the fallback.** `submit` runs
      `enrolConvenienceWrap()` after every floor unlock (`LoginView.vue:185`). With
      `core.remember-device.passwordless` off and `core.remember-device` on, the toggle is hidden, so `remember` is
      `false` and `:140-142` run: `clearDeviceKey()` deletes the withheld DEVICE_KEY wrap on the server
      (`deleteWrap` stays allowed while the switch is off, `KeyMaterialHandler.capabilityFor`'s KDoc) and drops the
      local remember-me key, then `trustThisDevice` enrols a DEVICE wrap. Switching back on then restores nothing:
      the wrap and the key are gone. With `core.remember-device` off, `deviceWrapSupported` is false and nothing is
      replaced, so the DEVICE case keeps its record and only pays the second submit.
    - **Revoke, untrust and a withheld wrap.** An explicit forget on this device (`DeviceTrustControl.vue:65-66`,
      `signOut.ts:31`) deletes the local record itself, so the client never has to infer it. A wrap revoked from
      another device (ADR 0058) and a withheld one look the same in the key material: both are absent, nothing
      says why. The pre-auth capability manifest is the one thing that tells them apart, and only one way: with
      the switch on, an absent wrap was removed; with it off, it may be withheld or also removed. Keeping the
      local key in that second case is harmless (it opens nothing without its wrap), so the switch decides.
  - **Built (`LoginView.vue` only; the unlock modules needed no change).**
    - `enrolConvenienceWrap` no longer replaces a withheld tier: with `core.remember-device.passwordless` off and a
      DEVICE_KEY record for the entered email (`rememberedWhileOff()`, read from `rememberedWrapId`), the toggle-off
      branch caches the session vault but neither clears the DEVICE_KEY wrap nor enrols a DEVICE one. The skip is
      keyed on the switch, not the record: with the switch on, an absent wrap was removed and the sign-in replaces
      the stale record as before (uc.account.remember-this-device E2).
    - `resolveDeviceState` asks a trusted device for the Secret Key up front while `core.remember-device` is off,
      instead of letting `unlockWithDevice` fail over to it after a first submit. The race (a manifest read before
      a switch-off) still lands on the old reveal at `:176-181`.
    - **The explanatory line is built**, as a one-liner in the existing idiom: the Secret-Key field's hint reads
      `unlock.deviceHeldBack` instead of `unlock.secretKeyHint` (which says "first time on this device … you won't
      need it here again", false here) when the device holds a record whose switch is off. EN + DE.
    - Untouched on purpose: the session vault (sessionStorage, the "off" tier) is still cached on the fallback
      sign-in with `core.remember-device` off, as before this CR (`LoginView.test.ts`'s existing
      "remember-device off" case pins it). It is no server wrap, so no switch withholds it; whether
      `core.remember-device` should also stop it is a question for the owner, not this phase.
  - **Deviation from D6's wording.** Without `DEVICE_KEY` the fallback is password **and Secret Key**, not the
    password: measured above, and confirmed by phase 1's `whenOff` text. A2 of the use-case still says otherwise;
    phase 6 corrects it.
  - **Bites.** Live defect first: on the unfixed view, the passwordless case was red at `clearDeviceKey`
    (called once), and the trusted case at the Secret-Key field (absent). Then each red once by injection, restored
    byte for byte from a snapshot, `git status --porcelain` unchanged over the run:
    - withheld DEVICE_KEY replaced by DEVICE again (guard removed) → "passwordless off: a remembered device …" red;
    - the trusted device asking no Secret Key up front → "remember-device off: a trusted device …" red;
    - no hint (`heldBack = false`) → both of those red, each run on its own;
    - the skip keyed on the record, not the switch → "control: with its switch on …" red;
    - the device re-enrolling after its wrap is served again → "remember-device on again …" red;
    - `restoreDeviceKey` dropping the record on a withheld wrap → `deviceKeyWrap.test.ts` "a withheld wrap keeps
      the record …" red;
    - `unlockWithDevice` clearing the trust on a withheld wrap → `unlock.device.test.ts` "a withheld DEVICE wrap
      falls back …" red;
    - `clearDeviceKey` keeping the local key → "an explicit forget still clears a record whose wrap is withheld" red;
    - `untrustThisDevice` keeping the local key → `trustDevice.test.ts` "untrust removes the server wrap and the
      local key" red.
  - **Measured:** `npm run check` in `web/` green, 343 files / 3952 tests (31 skipped), 7 new (4 in
    `LoginView.test.ts`, 2 in `deviceKeyWrap.test.ts`, 1 in `unlock.device.test.ts`). The guard glob, run on a
    clean worktree of this commit (the shared tree carries a neighbour's uncommitted `site/` move, which reddens
    `validate_wiki_sources.py`), is green, 58 guards.
  - **For phase 5:** a trusted device (DEVICE) is a sign-in with "Stay signed in" off, then a browser close; a
    remembered device (DEVICE_KEY) is a sign-in with it on. Switching off then shows the Secret-Key field with
    the `unlock.deviceHeldBack` hint, the sign-in with password and Secret Key works, and the device's row in
    the sessions view still says trusted / remembered. Switching on again: the trusted device signs in with the
    password alone, and the remembered one opens at a restart with no prompt. A reload inside the same browser
    session opens from the session vault and shows neither, so close the browser between steps.
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
