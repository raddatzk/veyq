---
id: cr-shared-imports-no-features
title: The backend's shared/ imports no feature; web's shared UI lives in shared/ui
status: planned
satisfies: []
adrs: ["0007"]
invariants: []
---

## Goal

Inbox #173. The rule *"`shared/` must not depend on a feature"* is prose, and four imports break it in the
backend:
- `shared/auth/SessionTokenIdentityProvider` imports `features.account.login.SessionService`;
- `shared/tenant/TenantHostResolver`, `TenantIngressProjector` and `TenantSuspensionFilter` import
  `features.admin.tenant.TenantEntity`.

In web, two components shared across features sit outside `shared/ui`: `components/ImageCropper.vue` (used by
memory and spaces) and `components/app/TenantAvatar.vue` (used by the shell and by spaces).

After this CR:
- `TenantEntity` lives in `shared/tenant`;
- `shared/auth` depends on a one-method interface that `SessionService` implements;
- both components live in `shared/ui`;
- a guard keeps backend `shared/` free of feature imports.

The scope is deliberately lean.

## Context & constraints

- **Refined state:**
  - [developer-styleguide](../docs/crosscutting/developer-styleguide.md) § cross-cutting code: *"`shared/` must
    not depend on a feature"* and *"share neutral rules through small interfaces"*.
  - [ui-web-styleguide](../docs/crosscutting/ui-web-styleguide.md) § Components, *Where a component lives*
    (written in this refine).
  - [ADR 0007](../docs/decisions/0007-membership-role-grant-relation.md): *"The relation is a shared
    primitive"*, which is the shape this follows (Decision 1).
- **Measured on** `main` at `54fe415`, in worktree `claude/beautiful-yonath-d0124b`, plus the refine commit.
  Re-run the locators.
- **Settled with the owner, 2026-09-23 (do not reopen):**
  - The two-way cycles *between* features are **not** chased. Several are plain entity reads, and
    `ScopePurge`'s fan-in is deliberate and guarded.
  - `withRefresh` belongs to #168.
  - `SecretKeyField`'s import from `features/account/emergencyKit` stays, and the web styleguide names it and
    says why.
  - `TenantAvatar` is in scope.
- **Sequencing: land this when no other large backend CR is in flight.**
  - 44 test files construct or import `TenantEntity`.
  - `cr-request-tenant-and-tenant-bound-recovery` reworks `shared/tenant` (`RequestTenant`,
    `TenantHostResolver`). It already says *"if #173 landed first, use its `TenantEntity`"*, so landing this
    first is the cheaper order.
  - **`cr-scope-access-gate` (#157) moves `TenantEntity` too**, in its phase 2 (its D11), the same way: a move,
    on the `MembershipEntity` precedent, together with `ScopeEntity`. **Whichever lands first does the move, and
    the other skips it.** If that phase landed first, Decision 1 is already done; verify it with
    `grep -rn 'package com.veyq.app.shared.tenant' backend/src/main/kotlin/com/veyq/app/shared/tenant/TenantEntity.kt`.
    Then this CR is Decisions 2–4 only, and the guard's untouched-tree red lists the `SessionService` import
    alone. That CR also touches `TenantSuspensionFilter`'s surroundings, and `cr-app-shell-served-by-backend`
    cites the filter. Both overlap textually only.
  - Before the first edit, `grep -ln 'TenantEntity\|TenantSuspensionFilter\|TenantHostResolver\|SessionService\|ImageCropper\|TenantAvatar' changes/cr-*.md`,
    and ask the owner if any of those CRs is `in-progress`.

## Decisions

1. **Move `TenantEntity` into `shared/tenant`, keeping its class name and mapping; do not add a second
   mapping.** Skip this if `cr-scope-access-gate` phase 2 already did it (see Context). This is the cr-0160
   shape, and that CR's D11 chose the same one:
   - `MembershipEntity` lives in `shared/membership`, and features still write it through their own stores.
   - *"query-only"* in ADR 0007 and in `MembershipEntity`'s KDoc describes the **shared module's surface**, not
     the entity.

   So here too `TenantStore` (`features/admin/tenant`) stays the only writer. Locator:
   `grep -rn 'TenantEntity()\|TenantEntity\.update\|TenantEntity\.delete' backend/src/main`.
   - Only `TenantEntity` moves. The other three entities in `features/admin/tenant/Entities.kt` stay.
   - **Rejected: a second, read-only mapping of `veyq_tenant` in `shared/`.** No entity in this codebase maps a
     table twice (`grep -rhn '@Table(name' backend/src/main | sed -E 's/.*name *= *"([^"]+)".*/\1/' | sort |
     uniq -d` → empty). Hibernate's default entity name, the simple class name, would clash. And two entity
     types over one row are two persistence-context identities, so a read after an unflushed write in the same
     transaction can be stale. That last point is inferred, not run; it did not need running to reject the
     shape.
   - `scripts/validate_tenant_ingress_triggers.py` anchors on the *name* `TenantEntity` inside `TenantStore.kt`
     and on the paths of `TenantStore`, `TenantHandler` and `TenantIngressProjector`. None of those moves, so it
     stays green. Run it.
2. **`shared/auth` gains `AccessTokenResolver`, one method: `fun resolveUserId(accessToken: String): String?`.**
   - That is the only call `SessionTokenIdentityProvider` makes (`SessionTokenIdentityProvider.kt:43`), and it
     is `SessionService.resolveUserId`'s existing signature.
   - `SessionService` implements it, and the provider injects the interface. It is the only bean of that type,
     so CDI resolution is unambiguous.
   - `SessionTokenIdentityProviderTest` keeps injecting the concrete `SessionService` for `issue()`.
3. **web: `components/ImageCropper.vue` and `components/app/TenantAvatar.vue` move to `shared/ui/`.**
   - `components/` then holds only `app/`, the shell.
   - `spaceIconImage.ts` imports the `CropRect` type from the `.vue` file, so it moves with it.
   - `GroupMembersPanel.test.ts` stubs `TenantAvatar` by component name. That stub is unaffected by the path,
     but re-run the test.
4. **The guard: `scripts/validate_shared_imports_no_features.py`.** It enforces that no
   `import com.veyq.app.features…` line exists under `backend/src/main/kotlin/com/veyq/app/shared/`. It is the
   one-shot proof, promoted (`changes/README.md` § *promote one-shot proofs*).
   - **Its limit**, for the docstring. Three things are **not** checked:
     - The **test** tree: five shared tests import features (`OperatorBlindProbeTest`, the rate-limit tests,
       `SessionTokenIdentityProviderTest`, `TenantIngressProjectorTest`), and integration tests may.
     - **KDoc links**: `StoreReclaim.kt` and `StoreReclaimSweep.kt` link to feature classes, which is
       documentation, not a dependency.
     - A **fully-qualified code reference** without an import, of which there are 0 at refine
       (`grep -rn 'com\.veyq\.app\.features' backend/src/main/kotlin/com/veyq/app/shared | grep -v ':import'`,
       whose only hits are the two KDoc links).
   - web gets no guard, because the known `SecretKeyField` inversion would make it red on the day it was born.
   - Add one pointer to the developer styleguide's sentence: the guard holds it.

## Inventory

- **The guard's own predicate, red on the untouched tree with the four imports:**
  `grep -rn '^import com.veyq.app.features' backend/src/main/kotlin/com/veyq/app/shared`.
- **Every `TenantEntity` site, main and test:** `grep -rln 'TenantEntity' backend/src`.
  - The ones that need a new import are the files importing `com.veyq.app.features.admin.tenant.TenantEntity`,
    plus same-package users in `features/admin/tenant` (main and test) that used it without one.
  - No wildcard imports of that package exist (`grep -rn 'import com.veyq.app.features.admin.tenant.\*'
    backend/src` → 0).
  - The compiler lists anything missed.
- **`TenantHostResolver.tenantFor` returns the entity.** Its one feature caller, `ScopesStore`, reads only
  `.id` (`grep -rn 'tenantFor(' backend/src`) and only needs the import.
- **web:** `grep -rn 'ImageCropper\|TenantAvatar' web/src` for the importers, and `find web/src/components -type
  f` to confirm `components/` ends up holding only `app/`.
- **Prose:** `grep -rn 'components/ImageCropper\|components/app/TenantAvatar\|admin/tenant/Entities\|admin\.tenant\.TenantEntity' docs changes scripts wiki`.
  At refine the only hits were the inbox rows.

## Phases

- [ ] 1 backend: Decisions 1, 2 and 4.
      1. Guard first: run it on the untouched tree and record its red output.
      2. Then the move and the interface.
      3. Then bite the guard by snapshot-and-restore: re-add one feature import to a `shared/` file, read its
         red line, and restore.

      · Done when: the full `./gradlew check` is green and the `@Test` count is unchanged (`grep -rc '@Test'
      backend/src/test --include='*.kt' | awk -F: '{s+=$2} END {print s}'`, taken before the first edit). The
      new guard and `validate_tenant_ingress_triggers.py` are green.
- [ ] 2 web: Decision 3. · Done when: `npm run check` in `web/` is green and `components/` holds only `app/`.
      Inbox #173 → done.

## Definition of done

- Before the first edit, run the baseline and paste it: `for f in scripts/validate_*.py; do python3 "$f";
  done`. Every guard it ran is green at the end, the new one included.
- The full `./gradlew check` and `npm run check` (`web/`) are green.
- The structural invariant has its permanent guard (Decision 4), and its bite is recorded in the retrospective.
- This file is deleted once done. No use-case is satisfied, so no coverage changes.

## Retrospective

_(filled at implement)_
