---
id: cr-rotated-member-re-grants-itself
title: A tenant owner decides whether a member who secures their account keeps their shared spaces' current key without anyone confirming
status: planned
satisfies: ["uc.account.rotate-keys", "uc.spaces.complete-queued-rotation"]
adrs: ["0060", "0028", "0014", "0007", "0025", "0109"]
invariants:
  - the switch is server-visible policy metadata (a tenant row, a scope row) and nothing else; no key, no trust judgment
  - a self-grant in a shared scope gives the new identity only the scope's CURRENT epoch, which the account's old identity already held; nothing added after the covering rotation reaches the new identity without a human accepting it (ADR 0028 Decision 2)
  - other members' clients and workers behave exactly as before - a changed key is still reviewed by a human
  - a group's setting is the group owner's, may only tighten the tenant's default, and is never served to the tenant owner (ADR 0014)
  - the publish refuses a self-grant where the effective setting is "confirm", and refuses to omit one where it is "self"
---

## Goal

Owner decision 3 of 2026-10-27, split out of `changes/cr-secure-the-account.md`, **which lands first**. That CR makes
the publish re-grant the current epoch of every scope the rotator is **alone** in. This one lets a **tenant owner**
decide whether the same holds for **shared** scopes (TENANT, GROUP) of a member who secures their account:
- **"self"** (the default for a new tenant): the member's client re-grants itself the scope's current epoch in the
  publish. It keeps reading everything up to the rotation, and only what is added afterwards waits for someone there to
  confirm the new key;
- **"confirm"** (today's behaviour, ADR 0060 Decision 4): the member is outside the scope until someone there confirms.

The tenant owner sets it for the tenant scope and as the default for groups. A group owner may only tighten it for
their group.

**Read Q1 and Q2 first.** A finding in this refine contradicts part of decision 3. And it shows that "confirm" protects
nothing "self" exposes, which would let this CR shrink to its first decision and its docs.

**Size:** four phases (contracts, backend, web, docs). It is its own CR because it is independent of the mechanism: a
setting, its storage, its enforcement in the publish, and two settings UIs.

## Context & constraints

Refined 2026-10-27 on `worktree-agent-a0be6f96549d75e39` at `c75f0616c`, measured, with nothing changed. It builds on
the plan read, the self-grant writer and its server-side signature check, all introduced by `cr-secure-the-account`.

### The finding: a rotator cannot commit a shared scope's new epoch for the other members

Decision 3 says a rotator who is an **owner** commits the shared scope's new epoch themselves under "self". The client
code refuses that, for every other member, until each one has accepted the rotator's new key. The chain:
1. **The commit signs every member's grant with the rotator's new identity.** `commitRotation` grants each admitted
   member whose pin is not `changed` (`src/web/src/features/spaces/scopes.ts:638-649`), signed by the committer. The
   server stores the committer's **current** key with each grant (`ScopesStore.kt:442`). After the publish that key is
   the new identity's.
2. **Each recipient verifies its own grant against its pin of the granter.** On a shared scope, a recipient checks the
   signature under the granter's served key and then runs the TOFU gate on that key. A `changed` key is refused as
   `granter_key_changed` (`src/web/src/features/spaces/grantTrust.ts:75-83`; ADR 0028 Decision 6).
3. **Every member who pinned the rotator holds the OLD key**, until they accept the change (ADR 0028 Decision 2).
4. **So the commit locks the other members out.** A new epoch committed by the rotator is refused by each of them, and
   they read the scope as awaiting access until each one accepts the rotator's new key.

That is the "key changed" review, moved from one person waiting on the members to every member waiting on themselves.
Decision 3's other half, a queued owner job where the rotator is a plain member, is unaffected: another owner commits,
and their key is unchanged. Q1 is the question this leaves.

### What a self-grant of a shared scope gives, and to whom

The self-grant hands the new identity `E_n`, the scope's epoch at the publish (and, through the chain links, every
older one). Every holder of what securing the account requires already reads `E_n`:
- **The device-link attacker of ADR 0060** holds the old Master Key and the old identity, and therefore `E_n`. They
  cannot secure the account: the Secret-Key proof refuses them (`RotationHandler.kt:165-174`).
- **The holder of the password and the Secret Key** opens the `PASSWORD` floor, then the old identity, then `E_n`.

So "self" gives the new identity nothing its holder could not already read. What still needs a human, in both settings,
is everything after the covering rotation: the covering commit skips the changed key (`scopes.ts:648`), and grant
delivery declines it until a human accepts (`grantDeliveryWorker.ts:96-98`; ADR 0028 Decision 2). That is Q2.

### Where things live today

- **The tenant owner is the OWNER of the tenant root scope** (`MembershipStore.kt:46-51`). Several owners are allowed
  on any scope (ADR 0007).
- **A tenant owner's settings.** They are columns on `veyq_tenant` (`TenantEntity.kt:25-66`) or tables of their own
  (`veyq_tenant_mail_settings`). They are served by owner-gated operations: `getTenantMailSettings` and
  `updateTenantMailSettings` (`contracts/api/tenants.openapi.yaml:1289`, `:1341`), gated by `ScopeAccess`, then
  `isTenantOwner`, then `403 not_tenant_owner` (`TenantCapabilitySwitchHandler.kt:101-135`, `TenantStore.kt:202`).
  The UI is the tenant category of the settings hub, whose owner-only rail entries sit at `SettingsHubView.vue:180-186`.
- **The capability register's "tighten only" requirement** (ADR 0088 decision 8, `CapabilityRegistry.kt:96-133`) has
  instance and tenant levels, with no group level. Each requirement hangs on a capability that can be switched off, and
  securing an account must never be switchable off. It is not the home (Alternatives).
- **A group has no settings storage.** Its state is columns on `veyq_scope` (`ScopeEntity.kt:49-97`), and its owner's
  operations are rename, icon, delete and role (`ScopesHandler.kt:313`, `:495-509`, `:590-634`). The UI is
  `GroupMembersPanel.vue` (role `:217`, rename `:313-330`), routed for owned groups (`SettingsHubView.vue:188-201`).
- **ADR 0014 §4: the tenant owner has "nothing" with respect to groups.** They are not surfaced to them
  (`docs/decisions/0014-group-scopes-and-governance.md:60-66`).
- **The state of a queued rotation** (`CompromiseRotationPolicy.kt:47-58`, served at `account.openapi.yaml:687`):
  1. `secured` when the rotator's grant is behind or absent;
  2. otherwise by the job: `done`, `failed`, `stalled` or `waiting`.

  With a self-grant at `E_n`, the rotator's grant is current until the covering commit and behind after it. So the
  derivation holds unchanged: `waiting` or `stalled` before the commit, `secured` (or `rekeying`) after it.
- **ADR 0109 point 7:** "The rotator of an account rotation never runs it in a shared scope: they hold no current grant
  there." Under "self" they hold one until the covering commit. The sentence is restated, and the behaviour stays:
  the rotator is excluded from the job (`TasksStore.kt:436-439`).

## Decisions this CR takes

1. **Under the effective setting "self", the shared scope's current-epoch self-grant rides in the publish**, exactly
   like a sole-member one, through `cr-secure-the-account`'s writer, verification and set equality.
   - The plan read marks such a scope `regrant: true`.
   - The server's expected set becomes the sole-member scopes plus every shared scope whose effective setting is
     "self" and where the caller holds a grant at the current epoch.
   - A self-grant where the effective setting is "confirm", or a missing one where it is "self", is
     `409 rotation_plan_changed`. The setting may have changed since the plan was read, so the client re-reads.
   - **No `veyq_rotation_regrant` row is written for a shared scope.** Its new epoch is the queued job's, and its state
     is the queued state (Context). The publish still originates the job **with the rotator's exclusion**, as today
     (decision 3).
2. **The rotator never commits a shared scope's new epoch** (decided only if Q1 answers so; the alternative is in Q1).
   - The queued job and its exclusion stay as they are, and `rotateScope` is unchanged.
   - Where the rotator is a plain member, this is decision 3's "a queued owner job", word for word.
   - Where the rotator is an owner and other owners exist, another owner commits.
   - **E4** (the rotator is the only owner) still waits for the new epoch, and reads `stalled` as today. But under
     "self" the rotator keeps reading what is there, which is the half of the stall that "self" dissolves.
   - What still stalls is the closing of the old identity's reach over what is added. Promoting a co-owner remains the
     remedy, and the entry screen keeps listing those spaces by name.
3. **What the switch does NOT change** (owner decision, restated):
   - the other members' clients still see "key changed";
   - their workers still decline wraps to the new key until a human accepts it (ADR 0028 Decision 2);
   - #560 (acceptance by a Secret-Key-proof signature) is where that is meant to go, not here.
4. **Where the switch lives.**
   - **Tenant.** A column `veyq_tenant.rotated_member_regrant`, `SELF` or `CONFIRM`, `NOT NULL DEFAULT 'SELF'`.
     There is no release, and every dev database is recreated, so there are no existing rows to migrate.
     - Read and changed by the tenant owner through `getTenantRotationPolicy` and `updateTenantRotationPolicy`
       (`tenants.openapi.yaml`, owner-gated exactly as the mail settings are).
     - The effective value is served member-readable on `MyTenant` (`rotatedMemberRegrant`). A group owner needs it to
       show whether their group can still be tightened, and a rotating member's entry screen needs it per space.
   - **Group.** A column `veyq_scope.rotated_member_regrant_confirm`, `BOOLEAN NOT NULL DEFAULT FALSE`, meaningful for
     `GROUP` only.
     - Set by a group owner through `setGroupRotationPolicy` (`scopes.openapi.yaml`, gated as rename is: `ScopeAccess`,
       then `isScopeOwner`, then `403 not_scope_owner`).
     - Served on `ScopeSummary` to the group's members only, as `rotatedMemberRegrant` (effective) and
       `rotatedMemberRegrantConfirmOwn`.
     - Never served to the tenant owner as such, and no tenant operation reads it (ADR 0014 §4).
   - **The effective value:**
     - for the TENANT scope: the tenant's;
     - for a GROUP: `CONFIRM` if the tenant says `CONFIRM` **or** the group's flag is set, otherwise `SELF`;
     - for a PERSONAL scope: never asked, because it is always sole-member.
   - **Enforcement lives in one place: the publish's set computation**, under each scope's row lock, reading both
     columns in that transaction. `rotateScope` needs nothing, because decision 2 keeps the rotator out of the commit.
   - **Operator-blind.** The server learns one policy bit per tenant and one per group. It already enforces policy of
     this class (the capability requirements) and needs it to refuse. No key and no trust state.
5. **The UI.**
   - **Tenant owner.** A rail entry "Security" in the tenant category, owner-only beside Mail and Storage
     (`SettingsHubView.vue:180-186`), holding one choice: "When a member secures their account, they keep what is
     already here (recommended)" or "… someone here confirms it's them first".
   - **Group owner.** In `GroupMembersPanel.vue`, one checkbox: "Someone here confirms a member who secured their
     account before they see this group again". It is disabled and checked, with a one-line reason, when the tenant
     says "confirm".
   - **The rotating member.** The entry screen of `cr-secure-the-account` says, per shared space, which of the two
     holds there (Q3's copy). The result screen's queued rows keep their states, with a hint that says the member still
     reads what was there.
6. **The durable docs** (Phase 4, text in § Proposed durable text):
   - ADR 0060 Decision 4 and a Consequences bullet carrying the tradeoff;
   - ADR 0109 point 7;
   - the use case's step 7 and E4;
   - `uc.spaces.complete-queued-rotation`;
   - the tenant settings use case: the Inventory names it, and if none fits, Phase 4 writes `uc.tenant.rotation-policy`
     with `status: planned` coverage;
   - the site: Use EN and DE (the tenant owner's settings page, and Secure your account) and Security.

### Not in this CR

- #560, #561, and TOFU at first contact.
- An instance-level (Server Owner) override. Nothing asks for one. It would be the capability register's level, if
  ever.
- Any change to how other members review a changed key.

## Inventory

| What | Where | Locator |
|---|---|---|
| The tenant's columns and owner-gated settings ops | `TenantEntity.kt`, `tenants.openapi.yaml`, the mail-settings handler | `grep -n "operationId: getTenantMailSettings\|operationId: updateTenantMailSettings" contracts/api/tenants.openapi.yaml` |
| The member-readable tenant row | `MyTenant` in `scopes.openapi.yaml`, `ScopesStore.myTenants` | `grep -n "MyTenant:" contracts/api/scopes.openapi.yaml; grep -n "fun myTenants" src/backend/core/src/main/kotlin/me/raddatz/veyq/core/spaces/scope/ScopesStore.kt` |
| A group owner's operations and `ScopeSummary` | `ScopesHandler.kt`, `scopes.openapi.yaml` | `grep -n "fun rename\|fun changeRole\|isScopeOwner" src/backend/core/src/main/kotlin/me/raddatz/veyq/core/spaces/scope/ScopesHandler.kt` |
| The publish's set computation (from `cr-secure-the-account`) | `RotationHandler.kt` | `grep -n "memberCountsByScopeFor\|originateScopeRotationExcluding" src/backend/core/src/main/kotlin/me/raddatz/veyq/core/account/rotation/RotationHandler.kt` |
| The plan read | `account.openapi.yaml` | `grep -n "operationId: getAccountRotationPlan" contracts/api/account.openapi.yaml` (exists once the sibling's Phase 1 landed) |
| The UIs | `SettingsHubView.vue`, `GroupMembersPanel.vue`, the tenant panels | `git grep -n "TenantMailSettingsPanel\|GroupMembersPanel" -- src/web/src/views/SettingsHubView.vue` |
| The copy | `src/web/src/i18n/index.ts` | `grep -n "factSharedSpaces\|queuedHint" src/web/src/i18n/index.ts` |
| Docs that say the rotator is outside every shared scope | `docs/`, `site/` | `git grep -n -i "outside every shared space\|outside every shared scope\|draußen" -- docs site/src/content/docs src/web/src/i18n/index.ts` |
| The tenant settings use cases | `docs/features/` | `git grep -ln "getTenantMailSettings\|updateTenantMailSettings" -- docs/features` |

## Phases

### Phase 1: contracts

- The two tenant operations.
- `MyTenant.rotatedMemberRegrant`.
- `setGroupRotationPolicy`.
- `ScopeSummary`'s two fields.
- The plan's `regrant` description widened to shared scopes.
- Each new property carries its visibility and bounds.
- `OperatorBlindProbeTest` lines.

**Done when:** `npm run generate:api` runs, and the trust-chain guards are green. The expected red is stated, as in the
sibling CR's Phase 1.

### Phase 2: backend

- The migration and its pin.
- The ops.
- The effective value.
- The publish's set computation widened to shared scopes, under their row locks, in the existing ascending pass.

**Reproducing tests first, red as Phase 1 left it**, in `RotationSliceTest`:
- **`aSharedScopeUnderSelfIsRegrantedAndStillQueued`.** After the publish, the caller's grant on the tenant scope is
  at the current epoch, under the new key, **and** a rotation job with the caller's exclusion exists.
  - Control on the same predicate: with the tenant set to `CONFIRM`, the grant is untouched and the job exists.
- **`aSelfGrantUnderConfirmIsRefused`** and **`aMissingSelfGrantUnderSelfIsRefused`:** `409 rotation_plan_changed`,
  nothing written. Each has a passing control that differs in that one input.
- **`aGroupTightensTheTenantsSelf`:** the group flag set, under a tenant at `SELF`, means a self-grant there is
  refused. **`aGroupCannotLoosenTheTenantsConfirm`:** the group flag cleared, under a tenant at `CONFIRM`, still means
  no self-grant.
- **`theCoveringCommitLeavesTheSelfGrantBehind`.** Another owner commits, the caller's grant is behind, and the state
  is `secured`. The derivation is unchanged.
- **The gates.**
  - `updateTenantRotationPolicy` by a non-owner member is `403 not_tenant_owner`.
  - `setGroupRotationPolicy` by a non-owner is `403 not_scope_owner`.
  - A tenant owner who is not in the group gets `404`, indistinguishable, as ADR 0042 answers.

**Bite** each: drop the shared scopes from the set, ignore the group flag, or OR in the wrong direction. Each named
test goes red.

**Done when:** `./gradlew check` is green, and the migration and erasure guards are green.

### Phase 3: web

- The tenant panel.
- The group checkbox.
- The plan's shared `regrant` scopes opened with the old identity and self-granted in `rotateKeys`.
- The entry screen's per-space line and the result hints.

The tests:
- **`rotateKeys.test.ts`:** a shared scope with `regrant: true` is self-granted, verifies under the new key, and still
  appears among the queued ones. Its control: with `regrant: false`, no self-grant.
- **The panels:** the group checkbox is disabled and checked under a `CONFIRM` tenant.

**Done when:**
- `npm run check` is green;
- **browser pass** (as in the sibling CR, a throwaway two-member tenant):
  - member B secures their account under `SELF`, and still opens the tenant space's existing content;
  - owner A's worker commits, and B no longer reads what A adds until A accepts B's key;
  - then B does.

### Phase 4: docs and site

Decision 6, with the text below.

**Done when:** the trust-chain guards are green, and `npm run check` in `site/` is green.

## Proposed durable text

**ADR 0060, Decision 4**, appended:
> **What a tenant may relax is the rotator's access to what is already there, never their re-admission to what follows.**
> A tenant owner decides, for the tenant scope and as the default for groups, whether a member who secures their
> account re-grants itself each shared scope's current epoch in the same publish ("self", the default for a new tenant)
> or waits for someone there ("confirm"). A group owner may only tighten it. Either way the rotator never commits a
> shared scope's new epoch: every member's client would refuse a grant signed by a key it has not accepted (ADR 0028
> Decision 6). The rotator stays excluded from that rotation, and is re-admitted to what is added after it only through
> a human's acceptance.

**ADR 0060, § Consequences, the tradeoff:**
> Under "self", whoever holds the password and the Secret Key and secures the account first keeps the shared spaces'
> current key under their new identity, without anyone confirming it. They read that much anyway through the old
> identity, which the same two secrets open. What they cannot get without a human is anything added after the covering
> rotation.

**ADR 0109, point 7:** "they hold no current grant there" becomes "they are excluded from that scope's rotation, and
once it commits they hold no current grant there".

**The use case, step 7**, appended:
> Where the tenant's setting is "self", the publish also re-grants the scope's current epoch to the new identity, so
> the holder keeps reading what is there while the rotation waits.

**E4** gains:
> Under "self" the holder keeps reading the space; what stalls is closing it to the old key.

## Definition of done

- Under "self", a member who secures their account still reads every shared space's existing content, and reads what
  is added after the covering rotation only after a human accepts their key.
- Under "confirm", today's behaviour holds exactly.
- A group owner can tighten and cannot loosen.
- The tenant owner never learns a group's setting.
- The tests above were red first. The ADRs, use cases, coverage and site (EN and DE) say so.
- This CR is deleted after its Retrospective's findings have moved to where they apply.

## Open questions (owner)

**Q1: This contradicts decision 3. Who commits a shared scope's new epoch under "self", where the rotator is an
owner?**
- **What decision 3 says:** the rotator commits it.
- **What the code does** (§ The finding): every other member refuses a grant from the rotator's new key as
  `granter_key_changed` until they accept that key. So a rotator's commit locks the whole scope's membership out of the
  new epoch.
- **Recommendation: another owner commits, as today** (decision 2).
  - Where the rotator is the only owner (E4), the new epoch waits, and promoting a co-owner stays the remedy. The
    rotator keeps reading meanwhile.
  - **The alternative:** the only-owner rotator commits, and every member then sees the changed-key notice and must
    accept before reading what is added. That trades the old key's reach over new content against every member's
    availability. It becomes viable without that cost only with #560, where each member's client accepts the new key by
    a Secret-Key-proof signature.

**Q2: Keep the switch at all?**
- **What the finding shows** (§ What a self-grant gives): "confirm" protects nothing that "self" exposes. Everyone able
  to secure the account already reads the current epoch through the old identity.
- **Recommendation: drop the switch.** Re-grant every scope the rotator holds at its current epoch, sole and shared
  alike, with no setting. This CR then shrinks to decision 1 without a switch, a few tests and the docs. Decisions 4
  and 5 and the two UIs fall away. It would fit as a sixth phase of `cr-secure-the-account`.
- **If the switch is kept** (for example, so that a tenant can make every return pass a human), this CR stands as
  written.

**Q3: The member's per-space line, if the switch is kept. Proposed:**
- EN "self": "You keep what is already here. Someone here confirms it's you before you see what is added after this."
- DE "self": "Was hier schon ist, behältst du. Jemand hier bestätigt, dass du es bist, bevor du siehst, was danach
  dazukommt."
- EN "confirm": "You can't open this space until someone here confirms it's you."
- DE "confirm": "Du kannst diesen Bereich erst wieder öffnen, wenn jemand hier bestätigt, dass du es bist."
- **Recommendation: take these.**
- **Reason:** they follow `cr-secure-the-account`'s `factSharedSpaces` and today's `factSharedSpaces`
  (`i18n/index.ts:283`).

## Retrospective

*(filled at implement time)*
