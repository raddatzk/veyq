---
id: cr-tenant-suspend-request-gate
title: Tenant suspension — immediate request-boundary lockout
status: planned
satisfies: [uc.admin.suspend-tenant]
adrs: ["0066"]
invariants: [operator-blind everywhere]
---

## Goal

A Server Owner can suspend an `ACTIVE` tenant and reactivate a `SUSPENDED` one, reversibly, without ever
touching a key, grant, or content row. While `SUSPENDED`, **every** request resolving to that tenant's domain
is refused `403 tenant_suspended` at the request boundary — before authentication is evaluated — regardless of
whether the caller holds an otherwise-valid session. This is the "deactivate" event
[ADR 0063](../docs/decisions/0063-database-driven-tenant-ingress.md)'s (separately refined) ingress projector
will react to; this CR does not build the projector.

## Context & constraints

- [ADR 0066](../docs/decisions/0066-tenant-suspension.md) — the decision this CR implements: a request-level
  gate keyed on `Host`, no session revoke (sessions are account-global, not tenant-scoped — a revoke would
  collaterally sign a multi-tenant member out of an unaffected tenant too).
- [uc.admin.suspend-tenant](../docs/features/admin/use-cases/uc.admin.suspend-tenant.md) — the flow + exception
  cases (A1/A2 wrong-state 409s, E1 the Server Owner's own membership is not exempt).
- [contracts/api/tenants.openapi.yaml](../contracts/api/tenants.openapi.yaml) — `TenantStatus` enum, the
  Server-Owner-gated tenant-admin operation shapes to mirror.
- Invariant at risk: **operator-blind everywhere** ([CLAUDE.md](../CLAUDE.md)). Both transitions touch only the
  `status` column — no phase in this CR may read or write a scope key, a grant, or a content row.

## Decisions

- **`TenantStatus` gains `SUSPENDED`**: `[PENDING, ACTIVE, SUSPENDED]`. Two new Server-Owner-only operations,
  `POST /api/tenants/{tenantId}/suspend` and `POST /api/tenants/{tenantId}/reactivate`, mirroring `revokeTenant`'s
  shape (`contracts/api/tenants.openapi.yaml:522-525` for the operation shape, `TenantHandler.kt:179-185` for the
  handler shape) — 204 on success, 409 on wrong current status (`ACTIVE` required to suspend, `SUSPENDED`
  required to reactivate), 404 unknown tenant, 403 non-owner.
- **The status transition is a single guarded `UPDATE`, not read-then-write**: `TenantStore.suspend(tenantId)` /
  `reactivate(tenantId)` run `UPDATE ... SET status = ?1 WHERE id = ?2 AND status = ?3` and return whether a row
  changed — the same idiom `TenantStore.activate` already uses (`TenantStore.kt:94`), so a race between two
  concurrent suspend calls can't double-apply or silently overwrite a concurrent reactivate.
- **A new shared `TenantHostResolver`** (`backend/src/main/kotlin/com/veyq/app/shared/tenant/TenantHostResolver.kt`),
  extracted from `ScopesResource.activeHost()` (`ScopesResource.kt:223-226`) + `ScopesStore.findTenantIdByDomain`
  (`ScopesStore.kt:87-89`) — one canonical Host→tenant resolution (`docs/ai/README.md` rule 2), used by both the
  refactored `ScopesResource` (behavior-preserving) and the new gate below. This is the only touch to
  `ScopesResource`/`ScopesStore` in this CR.
- **A new `@Provider ContainerRequestFilter`, `TenantSuspensionFilter`** (same package/shape as
  `RateLimitFilters.kt`, `Priorities.AUTHENTICATION`): resolves the tenant via `TenantHostResolver`; if it
  resolves to a `SUSPENDED` tenant, `abortWith` the new `Failures.tenantSuspended` (`403`, `FORBIDDEN`, added to
  `backend/src/main/kotlin/com/veyq/app/shared/http/Failures.kt` next to `tenantNotFound` following its exact
  pattern). A `Host` that resolves to no tenant (bootstrap domain, unknown domain) passes through unchanged —
  this filter only ever narrows, never widens, existing behavior.
- **No session-revoke, deliberately** (ADR 0066) — do not add one under time pressure at implement time; a
  member's session staying technically valid against a suspended tenant is not a defect, the gate already
  refuses it.
- **Web**: `TenantDetailPanel.vue` gets a Suspend/Reactivate action plus a `SUSPENDED` status display, mirroring
  the existing `isPending` computed (`TenantDetailPanel.vue:37`).

## Phases

- [x] 1 contracts — `tenants.openapi.yaml`: `TenantStatus` enum → add `SUSPENDED`; add `suspendTenant` +
      `reactivateTenant` operations per the Decisions shape above ·
      Done when: `npm run generate:api` regenerates cleanly and `npm run check` (web, contracts lint) is green.
- [ ] 2 backend — `TenantEntity.SUSPENDED` const; `TenantStore.suspend`/`reactivate`; `TenantHandler`/
      `TenantsResource` wiring (Server-Owner-gated, same pattern as `revoke`); extract `TenantHostResolver`
      and refactor `ScopesResource` onto it (behavior-preserving — the existing `ScopesSliceTest`s must stay
      green unmodified); add `TenantSuspensionFilter` + `Failures.tenantSuspended` ·
      Done when: `./gradlew check` is green, including a new `TenantSuspendSliceTest` covering: suspend/reactivate
      happy path, both wrong-state 409s, non-owner 403, unknown-tenant 404, the gate returning
      `403 tenant_suspended` on an otherwise-`401`-would-be anonymous request AND on an otherwise-valid
      authenticated request against a `SUSPENDED` tenant's domain, and — the property this CR's refine corrected
      — a member of a **second, unaffected** tenant reaching that second tenant successfully on the same
      session while the first is suspended.
- [ ] 3 web — `TenantDetailPanel.vue` suspend/reactivate action + `SUSPENDED` display ·
      Done when: `npm run check` green, `TenantDetailPanel.test.ts` covers the new action + status display.
- [ ] 4 coverage — fill `docs/coverage/features/admin/use-cases/uc.admin.suspend-tenant.yaml` with real test
      refs, drop `status: planned` and `gaps` ·
      Done when: `python3 scripts/validate_coverage.py` is green.

## Bite tests

- **`suspended-blocks-every-request` — live defect.** Written against the untouched tree it is red (no gate
  exists yet); write the filter, watch it go green. Assert the response is `403 tenant_suspended` **by body**,
  not merely "not 2xx" — an unrelated 403 (e.g. a stale non-owner check) would pass a weaker assertion.
- **`suspend-does-not-affect-other-tenants` — the property this refine found and the reason the session-revoke
  was rejected.** Two tenants, one member of both, one suspended: assert the request against the *other*
  tenant's domain still succeeds on the same session. This is a live defect only if a future implementer
  reaches for a session-revoke anyway (e.g. copying `SessionResource.revokeSessionFamily`'s shape without
  reading this CR) — keep the test regardless, as the permanent guard against that regression.
- **`TenantSuspensionFilter` ordering** — run it before `TenantSuspendSliceTest`'s authenticated-request case:
  hit a normally-`401`-for-anonymous endpoint, anonymously, against a `SUSPENDED` tenant's domain, and assert
  `403 tenant_suspended` — not `401` — proving the gate runs ahead of authentication rather than behind it.

## Definition of done

Tests green across contracts/backend/web (`./gradlew check`, `npm run check`), the full trust-chain guard glob
green (`for f in scripts/validate_*.py; do python3 "$f"; done`), and `docs/coverage/` updated for
`uc.admin.suspend-tenant` with every acceptance criterion covered or explicitly gapped. No new structural
invariant is introduced beyond what `TenantSuspensionFilter` itself is (a guard by construction, not a
one-shot check to promote).

## Retrospective

_Filled at implementation close._
