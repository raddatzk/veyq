---
id: cr-account-sessions-list-and-revoke
title: See every active session and end any of them remotely
status: planned
satisfies: [uc.account.sessions]
adrs: ["0003", "0004", "0026", "0040", "0044", "0051"]
invariants:
  - Operator-blind — no device name, user-agent or client label may reach the server, in any field
  - A session is auth, not unlock — listing and revoking must never require an unlocked vault
  - API-first — contracts/ is the source of truth; DTOs are generated, never hand-written
---

## Goal

An account holder can see **every** session of their account and end any of them from the device they are
holding — the remote "sign out" that does not exist today, where the only way to end a session is to be the
device that owns it. This is slice A of the device & session manager (`docs/inbox.md` #111): the half that
needs no new decision, because [ADR 0004](../docs/decisions/0004-session-tokens.md) already fixed the design
and the session families already carry everything the list needs. It ships **unnamed** — session names come
from a Master-Key-sealed device register that is a later slice, and this work is deliberately built to
degrade to ids + coarse dates and stay fully usable.

## Context & constraints

- Use-case: [uc.account.sessions](../docs/features/account/use-cases/uc.account.sessions.md) — the behaviour,
  the two-layer split, and the exception flows. Do not restate it here; if something is missing, fix it there.
- Decisions: [ADR 0004](../docs/decisions/0004-session-tokens.md) (families, rotation, reuse detection,
  revocation, and why **no name may be stored on a family**), [ADR 0044](../docs/decisions/0044-field-visibility-declared-at-the-field.md)
  (every new scalar declares `x-veyq-visibility`), [ADR 0051](../docs/decisions/0051-declarative-rate-limit-posture.md)
  (every endpoint declares a rate-limit posture), [ADR 0040](../docs/decisions/0040-coverage-gaps-and-http-not-a-surface.md)
  (a test no CI run executes makes no coverage claim — this repo has no remote, so the coverage file stays
  `status: planned` and this CR does **not** claim a green CI).
- Contract home: **`contracts/api/auth.openapi.yaml`**. `SessionFamily` already lives there
  (`:351-360`) and revoking is an auth-layer act, not an account-layer one.
- The existing machinery this builds on, all already present: `SessionFamilyEntity` (statuses `ACTIVE` /
  `REVOKED`) and its `created_at` + `last_used_at` columns, `SessionService.revokeByAccessToken`, and
  `SessionService.revokeAllForUser` — which today revokes **every** family including the caller's and is
  reachable only from recovery.

### Locators — run these, they are the inventory

```bash
# Every caller of the revoke APIs — the blast radius of adding an "except this family" variant.
# Measured at refine: SessionResource:29 (logout), RecoveryHandler:120 (recovery), + the two definitions.
grep -rn "revokeAllForUser\|revokeByAccessToken" backend/src --include='*.kt'

# The rate-limit posture to copy. Measured at refine: LoginResource:24 and TokenResource:24 are
# @RateLimit(key = RateLimitKey.IP); SessionResource:18 is @RateLimitExempt with a stated reason.
grep -rn "@RateLimit" backend/src/main/kotlin/com/veyq/app/features/account/login/

# Second angle on the same question, because the first only shows what is already annotated and
# says nothing about what the sweep will DEMAND: read the sweep's own predicate. It resolves @Path
# through implemented interfaces, so the generated …Api interface is what carries it.
sed -n '80,100p' backend/src/test/kotlin/com/veyq/app/shared/ratelimit/RateLimitPostureSweepTest.kt

# Where a settings tab is registered, for the web phase.
grep -n "id: '" web/src/views/SettingsHubView.vue
```

Regeneration after the contract changes: `npm run generate:api` in `web/` (it regenerates every client;
the auth one lands in `web/src/api/generated/auth/`), and the backend's JAX-RS interfaces come from
`backend/gradle/openapi.gradle.kts`.

## Phases

- [ ] **1 contracts** — `contracts/api/auth.openapi.yaml`: a list endpoint returning the account's active
  families, a revoke-one endpoint, and a revoke-all-others endpoint. Extend `SessionFamily` with the created
  and last-used timestamps and the current-session flag, each carrying its `x-veyq-visibility` class. **Also
  fix `SessionFamily.id`'s description** (`:359`) — it says the E2EE name is "attached later", which this
  work retires; a family never carries a name. `git status` first: a dirty shared spec is stop-and-confirm.
  · **Done when:** `python3 scripts/validate_field_visibility.py` and `python3 scripts/validate_contracts_common.py`
  are green and `npm run generate:api` produces a clean client.
- [ ] **2 backend** — implement the three endpoints over `SessionService`, resolving the caller from the
  presented token (never from a body field). `revokeAllForUser` gains an "except this family" variant rather
  than a second copy — check the locator's callers before changing its signature. Every new endpoint declares
  `@RateLimit(key = …)` or `@RateLimitExempt(reason = …)`; an authenticated, id-scoped, idempotent endpoint is
  a candidate for exempt **with a stated reason**, not silently. An unknown or foreign family id is a silent
  no-op with the same response as a real one. · **Done when:** `./gradlew check` green (the **full** run, not
  the touched class — the `%test` H2 mem DB is JVM-shared), including the posture sweep, with a slice test that
  covers each acceptance criterion in the coverage file **and its live control** (a targeted revoke leaves the
  caller refreshing; "everywhere else" does too).
- [ ] **3 web** — a Sessions tab in the settings hub beside `device`, listing families with their dates, the
  "this device" marker, per-row revoke and "sign out everywhere else". It must render and act **locked**:
  names are not part of this slice, so nothing here may wait on the vault. Revoking the caller's own family
  is treated as a logout. · **Done when:** `npm run check` green with unit tests for the list, the locked
  render (revoke still enabled), and the revoke-self path.
- [ ] **4 coverage** — `docs/coverage/features/account/use-cases/uc.account.sessions.yaml`: replace the
  `status: planned` gaps with refs **only for what a CI run has executed**. This repo has no remote, so the
  honest outcome of this CR is most likely that the file keeps `status: planned` with its gaps rewritten to
  name the suites that now exist and pass locally — the same posture as `uc.admin.identify-instance-release`.
  Say which it was. · **Done when:** `python3 scripts/validate_coverage.py` green.

## Definition of done

All three surfaces green (`./gradlew check`, `npm run check`), the whole guard glob green over the
**committed** tree (`for f in scripts/validate_*.py; do python3 "$f"; done`), and `docs/coverage/` updated per
phase 4. No new permanent guard is promoted: this CR rests on no new structural invariant — the one rule worth
enforcing mechanically, *"no scalar without a visibility class"*, is already
`scripts/validate_field_visibility.py`, and it covers the new fields for free.

**One thing to verify by hand, because no test can:** that nothing in the web phase sends a device name,
user-agent or label to the server. The absence of a field in the contract is what makes that true, and phase 1
is where it is decided.

## Retrospective

_To fill at implementation close — both directions, process included, ending with one concrete "next time"._
