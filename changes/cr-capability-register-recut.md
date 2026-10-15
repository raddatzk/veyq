---
id: cr-capability-register-recut
title: Re-cut the four built capability ids into ADR 0088's register tree, before V1
status: planned
satisfies: ["uc.platform.capability-discovery"]
adrs: ["0088", "0032", "0059", "0079"]
invariants:
  - "Operator-blind E2EE everywhere: the manifest stays identical for every caller and carries no identity; every new gate refuses on a handle the server already sees (a share role, a wrap method, an endpoint, a node kind)"
  - "API-first: contracts/platform/capabilities.json is the source of truth; the backend CapabilityIds and the web constants follow it, pinned by their conformance tests"
  - "Production cannot reach the test seam: between this CR and inbox #443 no id can be switched off in a built instance, and nothing outside src/test may change that"
---

## Goal

The catalog has four flat ids. [ADR 0088](../docs/decisions/0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md)
replaces them with a tree of path ids. Decision 10 allows one cut before V1, because no client has been released.
This CR makes that cut, at the **instance (operator) level only**. It produces seven entries:

| new id | was | what the server refuses when it is off |
|---|---|---|
| `core.public-shares` | `sharing.public-links` (READ half) | `createShare`/`reactivate`/`reissue` of a `READ` share |
| `core.public-shares.contribute` | `sharing.public-links` (READ_WRITE half) | the same for `READ_WRITE` |
| `core.public-shares.drop-box` | `sharing.uploader-links` | the same for `WRITE_ONLY`, **and** creating a `FILES_DROPBOX` node (#440 gap 2) |
| `core.group-space` | `spaces.groups` | `createGroup` only (#440 gap 1 is resolved by ADR 0088 decision 5, see D5) |
| `core.remember-device` | `account.device-trust` (DEVICE half) | `addWrap(DEVICE)` |
| `core.remember-device.passwordless` | `account.device-trust` (DEVICE_KEY half) | `addWrap(DEVICE_KEY)` |
| `core.device-linking` | — (new; pairing is ungated today) | every `/api/pairing/*` endpoint |

An entry is active only if it and every ancestor entry are active: the AND along the path (decision 2). The server
computes this. The client reads leaf ids and never ANDs them itself (decision 3). Afterwards the old ids exist
nowhere in the tree except in this CR, and the catalog becomes strictly append-only from V1 on.

## Context & constraints

- [ADR 0088](../docs/decisions/0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md) holds the
  decisions. The register is at `:76-100`, decision 5 (what "off" does) at `:45-48`, decision 11 (linking ≠ remembering)
  at `:62-66`, and the mapping plus the two gaps at `:139-150`.
- [ADR 0032](../docs/decisions/0032-capability-discovery-version-gating.md) §2, §4, §5 and §6: the wire shape `[{ id }]`
  does not change, `422 capability_disabled` is the refusal, and every `serves` resolves to a built use-case.
- [ADR 0059](../docs/decisions/0059-device-linking.md): a linked device enrols only a `DEVICE_KEY` wrap, so with
  `passwordless` off it gets a session and no stored key (ADR 0088 decision 11).
- [uc.platform.capability-discovery](../docs/features/platform/use-cases/uc.platform.capability-discovery.md) and its
  coverage file `docs/coverage/features/platform/use-cases/uc.platform.capability-discovery.yaml`.
- **Out of scope**, each its own later CR (inbox #439 steps 2–4): the post-auth tenant set and the tenant settings UI
  (`+`), requirements, external services, every `<application>.*` entry, and `download-originals`. Also out of
  scope: share switches reaching existing links (#442, D11), the Server Owner's switches in admin-web and the CLI with
  decision 13's self-describing entries (#443, D3, D12), and configuration as code (#444).
- **A sibling in flight touches the same web files.** At refine time the shared tree held uncommitted edits by another
  session (`git status --short` showed `web/src/features/account/pairing/{SignInWithCodeCard.vue,JoinDeviceCard.test.ts,
  SignInWithCodeCard.test.ts,pairing.ts}` and `web/src/views/TenantJoinView.vue`, apparently
  `cr-hybrid-kem-is-x-wing`, done and deleted since). Phase 3 edits the pairing UI. So it starts only once
  `git status --short web/src/features/account/pairing web/src/views` is clean, or it waits.
- Substrate of every measurement below: `main` at `31282362`. The tree was clean in the files this CR touches.

## Decisions

Each decision carries the line that settles it.

- **D1 — the catalog.** There are seven entries (the table above). `source: operator-policy` stays on each. The tenant
  mark (`+`) is not added: it arrives with the tenant CR, which owns its semantics. `serves`:
  - `core.public-shares` → `uc.sharing.share-public-viewer`
  - `.contribute` → `uc.sharing.share-contributor`
  - `.drop-box` → `uc.sharing.share-uploader`
  - `core.group-space` → the five group use-cases it names today
  - `core.remember-device` → `uc.account.trust-device` and `uc.account.remember-this-device`
  - `.passwordless` → `uc.account.remember-this-device`
  - `core.device-linking` → `uc.account.onboard-device`

  Its coverage YAML has no `status:` line, so the guard reads it as built:
  `grep -n '^status' docs/coverage/features/account/use-cases/uc.account.onboard-device.yaml` → no output. The
  catalog's `note` is rewritten: it describes a tree, AND along the path, a strict append-only rule from V1, and one cut
  before it (ADR 0088 decision 10). It stops restating what the registry and the conformance tests do.
- **D2 — AND along the path, in the registry.** `isActive(id)` holds when `id` is advertised by this build and
  neither `id` nor any ancestor entry is in the switched-off set (D3). An ancestor is each dotted prefix that is itself a
  catalog entry; `core` is not an entry. `active`, which the manifest serves (`SetupHandler.kt:59`), is the
  advertised list filtered by `isActive`, in `ADVERTISED` order. So disabling `core.public-shares` drops all three
  share ids from the manifest and makes all three gates refuse. Today's computation is exact membership with no
  prefix logic (`CapabilityRegistry.kt:21-28`).
- **D3 — `veyq.capabilities.disabled` is deleted. Until #443 every built id is active, and only tests can switch one
  off** (owner, 2026-10-15). ADR 0088 decision 12 moves the Server Owner's switches to the Admin API as database state
  (inbox #443). Moving the boot-time list to the new ids and then deleting it in #443 would be double work for a
  mechanism no operator can reach. Measured: `grep -rn "VEYQ_CAPABILITIES\|capabilities.disabled" infra/ charts/ .run/`
  → no output. So `infra/.env.example`, `infra/docker-compose.prod.yml` and the Helm values never pass it through,
  and the release is pre-V1. So:
  - **Main.** `Capabilities` leaves `VeyqConfig` (`VeyqConfig.kt:34,100-103`), and its comment block leaves
    `application.properties` (`:44-49`). SmallRye then refuses a stray `veyq.capabilities.*` key at boot (SRCFG00050,
    `VeyqConfig.kt:25-27`). The registry reads the switched-off set from a `CapabilitySwitches` interface in main. Its
    one production bean answers "nothing is off", and #443 replaces that bean with the database-backed one. The
    registry asks on every call and caches nothing, which is the shape #443's switching without a restart needs anyway.
  - **Test seam, `src/test` only.** One `@Alternative` implementation holds a mutable set. It is enabled per test
    profile (`QuarkusTestProfile.getEnabledAlternatives()`), never by `@Priority`, because `@Priority` would enable it in
    every test. The precedent for an alternative is `AppShellAbsentSliceTest.kt:29`. A test that sets the set restores
    it in the same edit (`changes/README.md` § shared singleton state). It is never a config property.
  - **The leak bite.** A slice test in the default profile, with no alternative enabled, asserts two things: the wired
    `CapabilitySwitches` is the production bean, and `/api/setup` lists every `ADVERTISED` id. It is bitten by copying
    the test alternative into `src/main` with `@Priority` on a detached worktree. Then it goes red, because the default
    profile now gets the leaked switches.
  - **The gap is accepted.** Between this CR and #443 no built instance can switch anything off. That costs nothing
    real today, because no operator could set the list (the command above). **#443 must land before V1**, and its
    inbox row says so.
- **D4 — the share gates map role → leaf.** `capabilityFor(role)` (`SharingHandler.kt:710-712`) becomes `READ` →
  `core.public-shares`, `READ_WRITE` → `.contribute`, `WRITE_ONLY` → `.drop-box`. The three call sites stay where they
  are: create `:263`, reactivate `:450`, reissue `:501`. One behaviour changes on purpose: `WRITE_ONLY` now also needs
  `core.public-shares`, through D2, where today it is independent of public links. That is the tree, not a side effect.
- **D5 — `core.group-space` stays gated at creation only. #440 gap 1 is a doc fault, not a code fault.** ADR 0088
  decision 5 says a feature switch refuses only what is new, and that what exists stays readable and shareable. So
  rename, add member, set role, remove, delete, restore and accept/decline on an existing group must keep working
  while the switch is off. They are ungated today, and that is correct (`grep -c isActive …/spaces/scope/ScopesHandler.kt`
  → 1, at `createGroup` `:281`). The catalog description and ADR 0032 §5's sentence "gated narrower than its `serves`
  (inbox #440)" are reworded to state the feature-switch rule. The gate gets its first test (it has none today: the
  backend grep in § Inventory finds no `createGroup` case).
- **D6 — the wrap gate splits by method.** `addWrap` (`KeyMaterialHandler.kt:72`) gates on the method it is handed.
  `DEVICE` → `core.remember-device`. `DEVICE_KEY` → `core.remember-device.passwordless`, which by D2 also needs its
  parent. Today both methods are refused or allowed together, and nothing can refuse only `DEVICE_KEY`. Unlock with an
  existing wrap and `deleteWrap` (`:83`) stay ungated: that is a feature switch refusing only what is new.
- **D7 — `core.device-linking` gates every `/api/pairing/*` endpoint.** These are open, answer, delegate, seal and
  collect (`PairingResource.kt:50,58,67,82,90`; spec `contracts/api/auth.openapi.yaml:487,523,563,617,664`). The
  register names the handle as `/api/pairing/*`. A pairing is a transient exchange, not existing data, so "only what is
  new" means the whole exchange. Gating every endpoint, and not only `open`, also stops a pairing in flight. That
  matters once #443 switches without a restart. The gate is
  independent of `remember-device` (decision 11). The two combine only on the receiving device: there, the "stay
  signed in" choice needs `.passwordless` (`useGrantReceiver.ts:40`).
- **D8 — the drop-box creation gate is the application's kind rule, asked by core.** Core cannot name `FILES_DROPBOX`.
  Core's `NodeKindRules` gains a hook that answers "which capability does creating this kind need" (default none).
  Files answers `core.public-shares.drop-box` for `FILES_DROPBOX` (`FilesNodeKindRules.kt:29`). `NodesHandler.createInScope`
  asks it beside `parentAllows` (`:180`) and returns `422 capability_disabled`. Both creation paths go through
  `createInScope`: the member path (`NodesHandler.kt:116`) and the guest path (`ShareNodesHandler.kt:144`), so one
  check covers both. A copy of a drop box is a plain folder (`docs/apps/files.md:328`). No `depot.*` entry is added, so
  the hook names a core id. When the CR that adds application entries lands, it re-points the hook. It also owns a
  limit this CR leaves open: inheritance from a core entry, like `depot.public-shares` from `core.public-shares`, is not
  a path prefix, so D2's AND does not reach it.
- **D9 — the web reads leaves and does no AND.** `Access` (`web/src/core/access.ts:44-47`) carries one boolean per
  leaf instead of `publicLinks`/`uploaderLinks`: public shares, contribute, drop box. Each is filled from one
  `hasCapability` in the host (`MemoryHomeView.vue:140-141`). The client-side AND `publicLinks && uploaderLinks`
  (`features/memory/capabilities.ts:102`) goes. The matrices change as follows:
  - Memory `share` needs public shares, and `shareUpload` needs drop box.
  - The folder (`core/access.ts:229`) and the file (`files/capabilities.ts:84`) need public shares to share.
  - The drop box's `share` (`files/capabilities.ts:95`) needs **drop box**, not public shares.
  - `createDropBox` (`files/capabilities.ts:71`) needs drop box (#440 gap 2).

  `READ_WRITE` is offered only where contribute is on. Today `ShareCreateDialog.vue:120-121` and
  `FolderShareDialog.vue:237-238` offer it unconditionally. The right comes from the capability function and reaches
  the dialog as a prop, never as an inline `hasCapability` read (web `CLAUDE.md`, "never a … `hasCapability` read
  inline"). The new ids gate the following:
  - **Remember device.** The "remember this device" toggle needs `.passwordless`. The DEVICE-wrap auto-enrol on the
    toggle-off path needs `core.remember-device` (`LoginView.vue:62-66,126-135`). `ChangePasswordPanel.vue:40` follows
    the same split.
  - **Device linking.** `core.device-linking` hides "sign in with code" (`LoginView.vue:441-448`), the join card
    (`:350`) and Settings' unconditional `pairing` entry and `AddDevicePanel` (`SettingsHubView.vue:107,219`).
- **D10 — the guard learns the tree.** `validate_capabilities.py` adds two checks:
  - every id's root segment is `core`;
  - every id with three or more segments has its parent in the catalog, because D2's AND has nothing to AND with
    otherwise.

  `core`-only is a deliberate list of one: the CR that adds the first `<application>.*` entry widens it and names the
  source the application set derives from. Its docstring gains the two fields it lacks (`changes/README.md` § what a
  guard's module docstring must carry): the defect that bought it, and its limit. The limit: the guard does not prove
  that code gates an id or that the constants match. Those are `CapabilityRegistryTest` and `capabilities.test.ts`.

- **D11 — share switches do not reach existing links yet. Inbox #442 does that.** ADR 0088 decision 5 says a share
  switch acts at once, on existing links too. Built today: only create, reactivate and reissue are gated
  (`grep -rln Capabilit backend/core/src/main/kotlin/me/raddatz/veyq/core/sharing/` → `SharingHandler.kt` only, its
  `isActive` calls at `:263,450,501`). The owner made this its own CR (#442, decided there: a guest of a switched-off
  link gets the same 404-shaped unavailable page as an expired one). So this CR's catalog descriptions state what is
  built: new links of the role are refused, and existing ones keep working until #442. They point at #442.
- **D12 — the catalog's text fields stay as they are. Decision 13's shape belongs to #443.** ADR 0088 decision 13 gives
  every switchable entry a purpose and an effect-when-off, in EN and DE, served by the API. This CR rewrites every
  entry anyway, so laying the field down now looks cheap, but it is not. The field would have no reader until #443,
  and #443 decides what it is (which endpoint serves it, whether a tenant owner sees the same text, how the effect
  on existing data reads once #442 has changed it). A bilingual text with no renderer is a claim nothing backs, and #442
  would rewrite every share entry's "effect" text again. So the entries keep today's single English `description`, and
  #443 replaces it with decision 13's fields.

## Inventory

Each command runs from the repo root (zsh: quote `--include` globs). `EXCL="--exclude-dir=node_modules
--exclude-dir=build --exclude-dir=dist --exclude-dir=.git"`. Two angles cover the four things being retired:

- the literal ids: `grep -rnE $EXCL "sharing\.public-links|sharing\.uploader-links|spaces\.groups|account\.device-trust" .`
- the symbols: `grep -rnE $EXCL "\b(SHARING_PUBLIC_LINKS|SHARING_UPLOADER_LINKS|SPACES_GROUPS|ACCOUNT_DEVICE_TRUST)\b|publicLinks|uploaderLinks" .`

At refine they agreed on every code file and differed only in what each can see (comments, prose). Both must return
nothing outside `changes/` when the CR is done. That is a set condition, so run them as `grep -rlE …` and compare the
file lists.

| surface | what changes | locator |
|---|---|---|
| contracts | the catalog (D1). The OpenAPI prose and examples that name ids: `setup`, `sharing`, `scopes`, `account`. `setup.openapi.yaml:130`, which says the manifest is the catalog "minus the veyq.capabilities.disabled config list", now says: minus the switched-off ids (none until #443). `422 capability_disabled` responses/examples on the five pairing operations (D7) and on `createNode` (D8; `createNode`'s `422` at `content.openapi.yaml:85` today names only `illegal_structure`) | the two angles above, over `contracts/`; `grep -n "operationId" contracts/api/auth.openapi.yaml \| grep -i pair` |
| guard | `scripts/validate_capabilities.py` (D10). No other guard anchors on the catalog or the ids | `grep -rln "capabilities.json\|capability_disabled\|platform-capabilities" scripts/` (at refine: only a comment in `validate_coverage.py`, plus `chart_meta_check.py`'s unrelated Kubernetes `capabilities`) |
| backend main | `CapabilityIds`, `CapabilityRegistry` (D2, D3), `SharingHandler.capabilityFor` (D4), `KeyMaterialHandler.addWrap` (D6), the pairing handler (D7), `NodeKindRules` + `FilesNodeKindRules` + `NodesHandler.createInScope` (D8), the KDoc ids in `ScopesHandler.kt:44`/`KeyMaterialHandler.kt:19`. Deleted (D3): `VeyqConfig.Capabilities` and the `application.properties:44-49` block. Added: `CapabilitySwitches` and its production bean | `grep -rn --exclude-dir=build "isActive(\|capabilities\.active\|CapabilityRegistry\|CapabilityIds\." backend/*/src/main backend/apps/*/src/main`; `grep -rn "createInScope(" backend/core/src/main backend/apps/*/src/main`; for the property: `grep -rn --exclude-dir=build --exclude-dir=node_modules "capabilities.disabled\|VEYQ_CAPABILITIES\|fun capabilities()" .`, which must return nothing outside `changes/` and `docs/decisions/` at the end |
| backend test | `CapabilityRegistryTest`, `CapabilityManifestSliceTest`, `CapabilityDisabledSliceTest`, `apps/memories ReactivateShareCapabilitySliceTest`. No opt-in suite seeds through these gates | `grep -rln "capability_disabled\|CapabilityDisabled\|veyq.capabilities\|CapabilityIds" backend --include='*.kt' \| grep /src/test`; `grep -rln EnabledIfEnvironmentVariable backend/*/src/test backend/apps/*/src/test \| xargs grep -l "shares\|scopes/groups\|wraps\|pairing"` (empty at refine) |
| web main | `features/platform/capabilities.ts`, `core/access.ts`, `views/MemoryHomeView.vue`, `features/memory/capabilities.ts`, `features/files/capabilities.ts`, the two share dialogs, `AppSidebar.vue`, `SettingsHubView.vue`, `LoginView.vue`, `ChangePasswordPanel.vue`, `useGrantReceiver.ts` (D9). `admin-web` reads no capability | `grep -rnE "hasCapability\|useCapabilities\|publicLinks\|uploaderLinks" web/src \| grep -v '\.test\.'`; `grep -rn "pairing\|AddDevicePanel\|JoinDeviceCard\|SignInWithCode" web/src/views` |
| web test | the matrix tests, `access.test.ts`, `LevelView.test.ts`, `MemoryDetail.test.ts`, `FileDetail.test.ts`, the three `MemoryHomeView*.test.ts`, `ShareCreateDialog.test.ts`, and the `hasCapability` mocks in `LoginView.test.ts`, `useGrantReceiver.test.ts`, `ChangePasswordPanel.policy.test.ts`, `JoinDeviceCard.test.ts`, `SignInWithCodeCard.test.ts` (each only ever answers one way). The shared fixture `web/src/test/models.ts:97` | `grep -rlnE "hasCapability\|capabilityState\|useCapabilities\|SHARING_\|SPACES_GROUPS\|ACCOUNT_DEVICE_TRUST\|publicLinks\|uploaderLinks\|capable\(" web/src \| grep '\.test\.\|test/'` |
| docs | ADR 0032 §2/§5 (the "four flat ids, to be cut" sentence and the mapping table: once cut, the table is history and is deleted, and the §5 table lists the seven), ADR 0088 § Consequences ("Built today", the two gaps), ADR 0029:58, ADR 0040:74, the use-case doc (its restated id list at `:49-50` is deleted and points at the catalog, as its own sentence says it should), `docs/features/platform/_index.md:22`, `uc.sharing.share-shared-scope.md:12`, `uc.account.trust-device.md:13,132`, the matrices `docs/apps/memories.md:144` and `docs/apps/files.md:330-332`, and inbox #439 (step 1) and #440 | the literal-id angle over `docs/`; `grep -n "public links\|uploader links" docs/apps/*.md` |
| mutation manifests | none of the edited files is in a scope (`grep -nE "access\|capabilit\|Login\|pairing\|Sharing\|Scopes\|KeyMaterial\|NodesHandler\|NodeKindRules" web/stryker.mutate.json backend/core/pitest.scope.json packages/crypto/stryker.mutate.json` → only `features/tasks/capabilityTags.ts`, which is unrelated) | — |

Spot-check before trusting the table: D5 leans hardest on "only `createGroup` is gated". Re-run
`grep -n isActive backend/core/src/main/kotlin/me/raddatz/veyq/core/spaces/scope/ScopesHandler.kt` first.

## Phases

- [x] **1 contracts — the catalog, its guard, the OpenAPI prose.** Order: the guard first, red on the live defect,
  then the catalog, green.
  1. On a detached worktree of `HEAD` (`git worktree add --detach <dir> HEAD`), write D10's two checks. Run them
     against the **untouched** catalog. They must go red, once per old id, on the root rule (the live defect, no
     injection needed).
  2. Re-cut the catalog (D1). The guard goes green.
  3. Bite the parent rule by snapshot-and-restore on that worktree: delete `core.public-shares` from a copy → red,
     naming both children; restore → green. Bite the root rule: add `sharing.public-links` back → red.
  4. Update the OpenAPI prose and examples, and add the `422` responses (D7, D8). A response added to an existing
     operation should change no generated signature. Predict it, then confirm with `npm run generate:api` and
     `npm run check` in `web/` and `admin-web/`, and with `./gradlew check`. If something does go red, name it.
  · **Done when:** both bites recorded with their outputs, `python3 scripts/validate_capabilities.py` green, and the
  contracts guards green. Find them with `ls scripts/validate_*.py | grep -iE "contract|openapi|capabilit"`; at refine
that listed `validate_contracts_common.py`, `validate_id_contract_format.py` and `validate_capabilities.py`.
  Backend and web are red only at the conformance tests that still hold old ids. Those fail because they compare the
  code against the new catalog; name the failing tests.
- [x] **2 backend — ids, registry, gates.** Covers D2, D3, D4, D5's test, D6, D7. **D8 moved to phase 2b**
  (coordinator, 2026-10-15). While this phase ran, `cr-files-becomes-depot` was mid-rename, with uncommitted edits in
  `backend/apps/depot/**`, D8's site. Two writers never share files, and everything else in this phase is core. Before
  each commit, check `git status --short` on the exact paths staged; never stage one that carries a neighbour's edit.
  Every negative carries a positive
  control on the same predicate and anchors on `422` with `code == capability_disabled`, never on "it failed":
  - **Path AND.** `core.public-shares` off: all three share ids are absent from `/api/setup`, `createShare(WRITE_ONLY)`
    → 422, and `core.group-space` is still present (control).
  - **Child only.** `.contribute` off: `READ_WRITE` → 422, `READ` passes the gate (control, the existing
    `404 node_not_found` pattern of `CapabilityDisabledSliceTest`).
  - **Wrap.** `.passwordless` off: `addWrap(DEVICE_KEY)` → 422, `addWrap(DEVICE)` → 201 (control).
  - **Group.** `core.group-space` off: `createGroup` → 422, and rename of an existing group → 2xx (control, which also
    pins D5's feature-switch rule).
  - **Pairing.** `core.device-linking` off: each pairing endpoint → 422, and `/api/setup` → 200 (control).
  - **D3.** The registry, handed switches that name a parent (`core.public-shares`), refuses its children. This is the
    unit half of the path-AND bite; its control is a sibling that stays active. Also the leak bite from D3, recorded
    with its red output.

  Every off-switch test reaches its state through the test seam (D3), never through a config override. That includes
  the existing `CapabilityDisabledSliceTest` and `apps/memories ReactivateShareCapabilitySliceTest`, whose
  `getConfigOverrides()` go, and `CapabilityRegistryTest`'s `testVeyqConfig(...)` construction.
  - **Reissue.** Its gate (`SharingHandler.kt:501`) has no test today. One case, with its control.

  The conformance test (`CapabilityRegistryTest`) stays one-directional (code ⊆ catalog), because a catalog id may be
  reserved ahead of code (ADR 0032 §6). · **Done when:** `./gradlew check` green, and the backend half of the two
  inventory angles returns nothing.
- [x] **2b backend — D8, the drop-box creation gate.** It starts once `git status --short backend/apps/depot
  backend/core/src/main/kotlin/me/raddatz/veyq/core/node backend/core/src/main/kotlin/me/raddatz/veyq/core/content`
  is clean, i.e. once `cr-files-becomes-depot` has committed its backend. D8's locators are re-derived against that
  result. Measured after its `f92bc78c`: the rule is `backend/apps/depot/…/DepotNodeKindRules.kt` and the kind is
  `DEPOT_DROPBOX` (`:29`). The `FilesNodeKindRules.kt:29` and `FILES_DROPBOX` that D8 names were true at refine.
  Core's hook, `createInScope`'s check and Depot's answer land together, so the hook's bite lands with its one use:
  - `.drop-box` off: `createNode(DEPOT_DROPBOX)` → 422 `capability_disabled`;
  - `createNode(FOLDER)` at the same parent → created (control);
  - bitten by removing Depot's answer: the 422 test goes red.

  `createNode`'s 422 text already names `DEPOT_DROPBOX` (fixed at the phase split). · **Done when:**
  `./gradlew check` green.
  Built: the hook is `NodeKindRules.capabilityToCreate` (default null), Depot's answer is in `DepotNodeKindRules`, the
  test is `apps/depot DropBoxCapabilitySliceTest` (plus the parent's path AND and an existing drop box renamed while the
  switch is off). Bite: on a detached worktree, Depot's override deleted → 3 of 12 red (both slice negatives
  `Expected status code <422> but was <201>`, and `DepotNodeKindRulesTest`'s pin), the rename control green; restored.
  The guest path maps the refusal to a new `CreateShareNodeOutcome.CapabilityDisabled`, unreachable today (no kind a
  guest may create names a capability); whether `createShareNode`'s contract names it is handed back.
- [ ] **3 web — leaves, matrices, gates (D9).** Start only when the sibling's pairing files are committed
  (§ Context) **and** `cr-files-becomes-depot` has committed its web rename of `web/src/features/files`, which D9
  edits. Re-derive D9's Files/Depot paths against that result. The matrix tests gain the rows this CR adds: drop box on/off on `createDropBox` and on the drop box's
  `share`, and contribute on/off on the offered roles. Each `hasCapability` mock that only ever answers one way gets
  its other answer: the hidden "stay signed in" branch in `JoinDeviceCard`/`SignInWithCodeCard`, the hidden pairing
  entry points, and the toggle-off DEVICE-wrap path in `LoginView`. These unit and mount tests are the only proof
  of the off states. A built backend can no longer switch an id off (D3), and the test seam is not reachable from a
  running instance. **Browser pass, on state only**, against the dev backend:
  - Files' "New" menu offers a drop box, and the drop box's links open.
  - A Memory offers both share and uploader.
  - The share dialogs offer `READ` and `READ_WRITE`.
  - The login page offers "sign in with code" and "remember this device".
  - Settings has its add-device entry.

  That proves the rename reached every gate, because a missed constant hides its feature. The off-state browser pass
  moves to #443's Definition of done, the first CR that can switch an id off in a running instance.
  · **Done when:** `npm run generate:api && npm run check` green in `web/`, the web half of the two
  angles returns nothing, and the browser pass is recorded.
- [ ] **4 docs + coverage.** Edit the docs rows of the inventory in place (current state, no addendum). In the
  coverage file:
  - `operator-disable-enforced` cites phase 2's slice tests, one per gate, for "a switched-off id is omitted and
    refused". Its *operator* half (a Server Owner switches it) is gapped with the reason: not switchable in a built
    instance until #443 (D3). Its text and the YAML header comment (`:9`, which names the config list) drop the config
    list.
  - `sharing-first-consumer` names the new ids and the roles they gate.
  - `catalog-serves-usecases` loses its hand count. "4 capabilities over 12 use-cases" was already wrong (the catalog
    serves 10): point at the catalog.
  - A new criterion `register-path-and` covers D2 (the slice test from phase 2 plus `CapabilityRegistryTest`).
  - A new criterion `feature-switch-refuses-only-new` covers D5 and D6 (the group-rename and DEVICE controls are its
    refs).

  ADR 0032 §4's "Built today" sentence (`:89`, the config list read at boot) now says that no instance-wide switch is
  built until #443. The use case's E1 says the same. Inbox: #439 records step 1 as done and points at the next step. #440 closes and says that gap 1 was resolved as a
  doc fault (D5). #442 and #443 are already open, and #443's row was brought in line with D3 at refine. ·
  **Done when:** `python3 scripts/validate_coverage.py` and `python3 scripts/validate_capabilities.py` green, and the
  literal-id angle over `docs/` returns nothing.

## Definition of done

- `./gradlew check` green in `backend/`, and `npm run generate:api && npm run check` green in `web/` and `admin-web/`.
  These were **not measured at refine**, so the implementer runs each once on `HEAD` before the first edit and records
  the exit codes as the baseline.
- The trust-chain glob is green over the committed tree: `for f in scripts/validate_*.py; do python3 "$f"; done`. At refine
  it ran on a detached worktree of `4928118d` (this CR's own commit): 57 guards, every one `rc=0`. Re-run it the same
  way on `HEAD` before the first edit. Use `ls scripts/validate_*.py | wc -l` for the count, not this number.
- Both inventory angles return no file outside `changes/`.
- The structural rule this CR establishes (the catalog is a tree under `core`, and a child needs its parent) is
  promoted to a permanent guard, D10. Its bites are recorded in phase 1.
- `docs/coverage/` is updated for `uc.platform.capability-discovery`, and every new criterion has a ref.

## Retrospective

(filled at implement close)

- **Phase 1, execution: staging by explicit path does not make a commit yours.** `git commit` takes the whole index,
  and a parallel session had staged its Files → Depot renames. The pre-commit `git status --short` showed them, but
  the commit was chained with `;`, so it ran anyway and swept 25 of the neighbour's renames into this CR's commit.
  It was rewritten at once (`git reset --soft <own first commit>`, then `git commit --only -- <six paths>`), and the
  neighbour's staged set was confirmed back in the index. Next time: commit with `git commit --only -- <paths>`, or
  gate the commit on `git diff --cached --name-only` equalling the paths you staged, joined by `&&`.
- **Phase 1, planning: a second sibling was in flight and § Context names only one.** `cr-files-becomes-depot`
  (ADR 0086) renames `:apps:files`, `FilesNodeKindRules.kt` (D8's locator) and the stored kind `FILES_DROPBOX` →
  `DEPOT_DROPBOX`. Within minutes of phase 1's commit it had already rewritten the drop-box entry's text in the
  working tree. Phase 2 re-derives D8's locators after that CR lands. Next time: at implement start, `ls changes/`
  and grep each live sibling CR for the files this CR's locators name.
- **Phase 2, planning: "src/test only" could not hold one seam for two projects.** D3 put the test alternative in
  `src/test`, but `apps/memories ReactivateShareCapabilitySliceTest` needs the same seam, and one `@Alternative`
  in two `src/test` trees is two. It went to `:core`'s test fixtures (`SwitchableCapabilities` +
  `CapabilitySwitchesProfile`), the precedent being `FakePresigningBlobStore` + `FakePresignProfile`; test fixtures
  never reach the fast-jar, so the invariant (production cannot reach the seam) holds and the leak bite proves the
  default profile still wires `NoCapabilitySwitchedOff`. Next time: a CR that places a seam names every project whose
  tests consume it, and picks the source set from that list.
- **Phase 2, planning: a test rename is a coverage edit, whatever phase owns the coverage prose.** Phase 4 owns the
  coverage file, but four of its refs named test methods this phase renamed, so `validate_coverage.py` would have
  been red at phase 2's close. The refs were re-pointed here (names only; the texts stay phase 4's). Next time: a
  phase that renames or deletes a test greps `docs/coverage/` for `<Class>.<method>` and carries the refs.
- **Phase 2, execution: one bite lever reached a test it was not batched for.** Run A's "READ_WRITE back on the
  parent" lever also reddened the reissue test (it switches `.contribute` off on a READ_WRITE link). The reissue
  gate's own lever had been put in a separate run for exactly that reason, so nothing was left unproven, but the
  prediction listed one red too few. Next time: before batching, list per lever every test whose fixture passes
  through the changed mapping, not only the test written for it.
- **Phase 2b, execution: the bite's restore hung on the known `cp` prompt.** The restore step was a bare `cp` over the
  bitten file, which this Mac aliases to `cp -i`; the background run waited at `overwrite …? (y/n)` until it was killed,
  and the bitten file stayed in place until restored by hand. The trap was already in the local machine notes. Next
  time: every snapshot-and-restore in a script uses `/bin/cp -f`, and the restore is checked (`grep` for the restored
  line) before the result is read.
- **Phase 2b, planning: "one check covers both" left the guest's answer open.** D8 says the member and guest paths
  share `createInScope`, so one check covers both. That holds, but the guest path maps every outcome of
  `createInScope` into its own type, and `createShareNode`'s contract names no `capability_disabled`. Next time: a
  decision that adds an outcome to a shared function lists every caller's outcome mapping and the contract of each.
