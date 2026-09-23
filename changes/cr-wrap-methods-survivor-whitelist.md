---
id: cr-wrap-methods-survivor-whitelist
title: Password change and reset keep only the cold wraps, via one wrap-method classification
status: planned
satisfies: [uc.account.change-password, uc.account.recover-access, en.account.key-model]
adrs: ["0054", "0060", "0005"]
invariants: [operator-blind E2EE]
---

## Goal

Inbox #172. The three flows that rewrite credentials disagree on which wraps they delete:
- **Password change** (`PasswordChangeStore.applyChange`) and **reset** (`RecoveryStore.applyReset`) delete a
  *blacklist*, `{DEVICE, DEVICE_KEY}`.
- **Rotation** (`RotationStore.applyRotation`) keeps a *whitelist* of survivors, `{PASSWORD, RECOVERY}`, and
  its own KDoc explains why: a blacklist "would silently leave a new method holding the old Master Key".

The contract already names five methods, `PASSKEY` among them. So the day a passkey wrap can be written, it
would survive a reset with the old Master Key. That is latent today only because `AddWrapRequest.method` is
restricted to `[DEVICE, DEVICE_KEY]` (`account.openapi.yaml`, `AddWrapRequest`).

After this CR all three flows use the survivor rule, read from one classification in `shared/identity`, and
no store spells a wrap method of its own. For today's methods, nothing observable changes.

## Context & constraints

- **Refined state this builds** (read it, don't restate it):
  - [en.account.key-model](../docs/features/account/enablers/en.account.key-model.md) § Adding / removing
    unlock methods, the paragraph *"A credential change keeps only the two cold wraps"*: the rule and its
    reason.
  - [uc.account.change-password](../docs/features/account/use-cases/uc.account.change-password.md) and
    [uc.account.recover-access](../docs/features/account/use-cases/uc.account.recover-access.md), postconditions
    and the apply step.
  - [ADR 0054](../docs/decisions/0054-password-recovery-two-factor-release.md) §3.
  - Rotation already obeys the rule ([ADR 0060](../docs/decisions/0060-key-rotation-after-compromise.md)
    Consequences, `RotationStore.kt` KDoc).
- **Measured on** `main` at `54fe415`, in worktree `claude/beautiful-yonath-d0124b`, plus the refine commit.
  Re-run the locators.
- **Wire shape does not change.** Phase 1 edits two descriptions.
- **Settled with the owner, 2026-09-23:** whitelist semantics everywhere; a method is deleted on password
  change and on reset unless it is explicitly declared a survivor. This decides how passkeys will behave; do
  not reopen it.
- **Eligible for same-session implementation** (small, behaviour-preserving for today's methods, gated by
  `./gradlew check` plus the bite in phase 2). It still needs the owner's explicit go.
- **Neighbours.**
  - `cr-request-tenant-and-tenant-bound-recovery` and `cr-recovery-commitment-stored-hashed` touch
    `RecoveryStore` and `RecoverySliceTest`; `cr-backend-test-fixtures` touches every `seedUser` in the three
    slice tests below.
  - All of these overlap textually only, and whichever lands second rebases.
  - `applyReset`'s transaction boundary and its four writes stay as they are. Only the delete predicate
    changes.

## Decisions

1. **One classification: `enum class WrapMethod` in `backend/.../shared/identity/WrapMethods.kt`.**
   - Every constant declares, **with no default value**:
     - whether it survives a credential change (`PASSWORD`, `RECOVERY`: yes; `DEVICE`, `DEVICE_KEY`,
       `PASSKEY`: no);
     - whether it is device-local, meaning the untrust endpoint may delete it and `addWrap` may create it
       (`DEVICE`, `DEVICE_KEY`).
   - No default is the structural half of *explicitly declared*. The next method cannot compile until someone
     states both properties.
   - `PASSKEY` is classified now, because the contract already carries it. It is a non-survivor, which is what
     rotation already does and what the default protects.
2. **The three flows delete `method not in survivors`.** That covers `PasswordChangeStore.applyChange`,
   `RecoveryStore.applyReset` and `RotationStore.applyRotation` (whose private `COLD_METHODS` and its reasoning
   KDoc move into `WrapMethods.kt`). Every other method-name site reads the enum as well:
   - the whitelist in `KeyMaterialStore.deleteWrap`;
   - the `RECOVERY` withholding in `KeyMaterialHandler.forUser`;
   - the `PASSWORD`/`RECOVERY` lookups in `RecoveryStore`.

   The column stays a `String`. Stores compare against the enum's names and never parse a stored value, so a
   row with an unknown method is simply a non-survivor.
3. **The enum is pinned to the contract.**
   - A unit test asserts that the enum's names equal the values of the generated `MasterKeyWrap` method enum
     (`com.veyq.app.generated.model.MasterKeyWrap`, generated from `account.openapi.yaml` `MasterKeyWrap.method`).
   - So a method added to the contract turns this test red until it is classified.
   - This is why the check is a Kotlin test and not a script: the compiler already links the generated enum.
4. **Descriptions follow (phase 1).** Two contract descriptions still enumerate *"every `DEVICE`/`DEVICE_KEY`
   wrap"*:
   - `account.openapi.yaml`, the change-password operation;
   - `auth.openapi.yaml`, the recovery-complete operation.

   **Edit** both to *"every wrap but `PASSWORD` and `RECOVERY`"*. The `AddWrapRequest` and untrust
   descriptions stay, since they describe the device-local set, which is correct. Locator:
   `grep -n 'DEVICE_KEY' contracts/api/*.yaml`.
5. **The guard.** `scripts/validate_wrap_method_vocabulary.py` enforces that **no string literal equal to a
   wrap method appears in `backend/src/main/kotlin` outside `shared/identity/WrapMethods.kt`**. It derives the
   method set from `contracts/api/account.openapi.yaml` `MasterKeyWrap.method.enum` and never lists it itself
   (`changes/README.md` § *a guard derives its anchor set*). A local list of wrap methods is the defect that
   bought it, and a new flow writing `listOf("DEVICE", "DEVICE_KEY")` would pass every test that exists today.

   **Its limit**, for the docstring: it sees literals, not meaning. The following are all invisible to it:
   - a method name built by concatenation;
   - a list typed in a test;
   - the web client's own handling of methods.

   A legitimate non-wrap use of a word like `"PASSWORD"` in backend main would be red. That is the price, and at
   refine there is none.

## Inventory

- **Every wrap-method literal in backend main.** This is also Decision 5's guard predicate, run on the
  untouched tree, where it is red with the sites below:
  - Locator: `grep -rnE '"(PASSWORD|RECOVERY|DEVICE|DEVICE_KEY|PASSKEY)"' backend/src/main/kotlin`.
  - At refine this found `KeyMaterialStore`, `KeyMaterialHandler`, `PasswordChangeStore`, `RecoveryStore` and
    `RotationStore`.
- **The tests to extend:**
  - `PasswordChangeSliceTest.deliberateChangeWithValidAttestationRewritesFloorDropsDeviceWrapsAndSparesTheCallersOwnSession`
  - `RecoverySliceTest.resetDropsDeviceKeyWrapsToo`
  - Template: `RotationSliceTest:59,73`, which seeds `PASSKEY` and asserts it is gone.
  - Locator: `grep -rn 'PASSKEY\|DropsDevice' backend/src/test`.
- **Coverage:**
  - `docs/coverage/features/account/use-cases/uc.account.change-password.yaml`, the criterion text *"Every
    DEVICE and DEVICE_KEY wrap is deleted…"*;
  - `uc.account.recover-access.yaml` `reset-is-atomic-and-drops-device-trust`.

## Phases

- [ ] 1 contracts: Decision 4, descriptions only. · Done when: `npm run generate:api` + `npm run check` in
      `web/` are green, the generated Kotlin is unchanged, and the trust-chain guards are green.
- [ ] 2 backend: **guard → red → fix → green.**
      1. Extend both slice tests first. Each seeds a `PASSKEY` wrap and asserts it is gone after the change or
         the reset.
      2. Confirm both are **red** on today's blacklist, and red on that assertion, not on anything else.
      3. Each also gets its positive control on the same predicate: the `RECOVERY` wrap survives, byte-for-byte.
         A whitelist that over-reaches and deletes the recovery path is the other way this can go wrong, and it
         would lock people out.
      4. Then Decisions 1–3.
      5. Then Decision 5's guard. Bite it by snapshot-and-restore: re-add `listOf("DEVICE", "DEVICE_KEY")` to
         one store and read the guard's red line naming that file.

      · Done when: the full `./gradlew check` is green, and the retrospective holds the red output of step 2
      and of the guard bite.
- [ ] 3 coverage + prose:
      - Edit both criterion texts to the survivor rule. Refs stay on the same test methods, which now carry the
        `PASSKEY` assertion.
      - Move the *why* from `RotationStore`'s KDoc to `WrapMethods.kt` and leave a pointer behind.
      - Add one line to `backend/CLAUDE.md` or the developer styleguide only if the guard's docstring cannot
        carry the rule. It should be able to.

      · Done when: every `scripts/validate_*.py` is green, including `validate_coverage.py`. Inbox #172 → done.

## Definition of done

- Before the first edit, run the baseline and paste its output: `for f in scripts/validate_*.py; do python3
  "$f"; done` and the `@Test` count (`grep -rc '@Test' backend/src/test --include='*.kt' | awk -F: '{s+=$2}
  END {print s}'`).
- The full `./gradlew check` and `npm run check` (`web/`) are green, and all trust-chain guards are green, the
  new one included.
- The `PASSKEY` assertions went red first, on the named assertion. The `RECOVERY` control is green before and
  after.
- Coverage is edited as in phase 3. This file is deleted after what it taught has moved to where it binds.

## Retrospective

_(filled at implement)_
