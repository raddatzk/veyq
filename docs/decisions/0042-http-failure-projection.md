# ADR 0042 — HTTP failure projection: one shared envelope, self-describing failure variants

- Status: Accepted
- Date: 2026-07-14

## Context

Backend operations return rich sealed `…Outcome` types — **68 types, ~145 failure variants** across
content/media/sharing/scopes/tenant/tasks/account. Each `…Resource` (16 of them) maps its Outcome's failure
variants to an HTTP response with a `when(outcome)` ladder **and constructs the error body itself**. Two
duplications result (audit finding **P3b**, [inbox #72](../inbox.md)):

1. **Envelope construction is copy-pasted in 4 idioms across 17 sites.** The generated wire contract
   `ErrorResponse{code}` (required `code`, optional `message`; identical in all 9 specs, guarded by
   [ADR 0038](0038-contracts-shared-components-bundling.md))
   is built via: an overloaded `private fun error(status, code)` (5 resources), named per-code helpers
   (`forbidden`/`notFound`/`conflict`, 2 resources), fully-inline `Response.status(…).entity(ErrorResponse(code=…))`
   (8 resources + `tasks`), and the 2 central providers `shared/auth/AuthExceptionMappers` +
   `shared/ratelimit/AuthRateLimitFilter`.
2. **The variant → (code, status) projection is re-derived per resource — and it has drifted.** The same
   semantic "**caller is not a member of the scope**" was a **404** (`scope_not_found`) in
   content/media/favorites but a **403** (`not_scope_member`) in `scopes.epochs`, `scopes.listMembers` and
   `tasks.enqueue`. `ConsentRequired` → 400 is an outlier next to sibling 422 policy-violations. (That inventory
   named the symptom, not the defect — see the guard-ordering rule below for what actually leaked, and where.)

The sealed variants themselves are **not** duplication to delete — they are the typed domain vocabulary that
gives compile-time exhaustiveness, self-documenting handler signatures, and typed handler tests
(`outcome is SlugTaken`). The problem is the **un-centralized projection to HTTP**, not the variant count. (The
finding's "collapse ~70 variants" was imprecise on two counts: it is 68 Outcome *types* / ~145 *variants*, and
the value is in **keeping** them; the uniform `ErrorResponse{code}` body is one shape built four ways, not four
body schemas.)

## Decision

Centralize the HTTP failure projection in the existing `shared/http/` leaf, **keeping the sealed `…Outcome` types
intact**:

- **A semantic status bucket owns the one status table.** `shared/http/` holds the single `bucket → HTTP status`
  mapping for the buckets the codebase uses — the Outcome-failure statuses (BAD_REQUEST → 400, FORBIDDEN → 403,
  NOT_FOUND → 404, CONFLICT → 409, UNPROCESSABLE → 422, RATE_LIMITED → 429) plus the tail the central providers +
  the non-Outcome error sites emit (UNAUTHORIZED → 401 auth mapper, GONE → 410 dead invite, SERVER_ERROR → 500
  internal inconsistency, SERVICE_UNAVAILABLE → 503 store cannot presign). The status literal is defined **once**,
  never per resource.
- **Failure variants self-describe their projection.** Every failure variant carries its stable `code` + its
  bucket via a shared `HttpFailure` interface (`val code`, `val status`). Recurring failures (e.g.
  `scope_not_found`, `not_key_holder`, `slug_taken`) are declared **once** in a shared `Failures` catalog and
  referenced, so the wire `code` + bucket for a given semantic failure has a single source of truth. **Drift
  becomes structurally impossible**: two sites cannot disagree on the status of "not a member" because the
  (code, bucket) lives on the one catalog entry, not in two `when` arms.
- **One envelope writer.** `shared/http/` exposes the single function that renders
  `HttpFailure → Response.status(bucket).entity(ErrorResponse(code))`. It is the **only** place `ErrorResponse`
  is constructed — the 4 resource idioms **and** the 2 central providers (auth `ExceptionMapper`s, rate-limit
  filter) all render through it. Each resource `when` collapses to `is <Success> → 2xx …; is HttpFailure →
  writer(outcome)`, which stays **exhaustive** (every non-success sealed subtype implements `HttpFailure`).

**Operator-blind guard ordering (the drift target).** A non-member probing a `scopeId` must not distinguish
"exists, you're forbidden" (403) from "no such scope" (404), or the response **discloses scope existence** across
the membership boundary — a confidentiality leak (cf. the cross-scope IDOR class).

What leaks is the **order of the guards**, not a variant's status tag. Existence is disclosed when an operation
probes *existence* (or scope **kind**) **before** the membership guard, so the two branches answer differently.
Whether the second branch is tagged FORBIDDEN or NOT_FOUND is secondary; what matters is that a non-member and a
bogus id are **indistinguishable**. The rule:

> **The membership boundary is the first guard on every scope-addressed operation.** Capability checks
> (grant-holder, owner) run *after* it and may answer 403 — a member legitimately knows the scope exists.

The boundary is **one** call, `ScopeAccess.check(caller, scope, READ | WRITE)` in `shared/membership`, and every
scope-, tenant-, share- and job-addressed operation makes it inline before anything else
([uc.spaces.scope-access](../features/spaces/use-cases/uc.spaces.scope-access.md) owns the full rule). It
answers the states only a member may learn *after* membership, in a fixed order:

    ScopeAccess.check(caller, scopeId, mode)
      no accepted membership        → the operation's 404   // strangers, bogus ids AND unaccepted proposals
      tenant SUSPENDED              → 403 tenant_suspended  // reads too (ADR 0066)
      WRITE, caller left the tenant → the operation's 404
      WRITE, scope being deleted    → 409 scope_deleting
    if (!holdsGrant(caller, scopeId)) return NotKeyHolder  // 403 — member without the key
    // …existence / kind / role / capability checks only from here on

An **unaccepted proposal** (`pendingConsent`, [ADR 0014](0014-group-scopes-and-governance.md) §5) is not a
membership for this boundary: the proposed member answers their own invitation and gets a stranger's 404
everywhere else. A predicate that counts proposals (a re-add's idempotency check) is a different question and
carries a different name.

This applies to *scope-addressed* operations, where the caller names an id they may have guessed. It must **not**
be applied to paths a non-member reaches legitimately by **possessing an unguessable token** — the `@PermitAll`
share-guest ops and tenant `preview`/`register`/`accept`. Those callers already know the resource exists, so
their 403s are correct. They are still not members, so they learn nothing of a scope's deletion or a tenant's
suspension either: while either holds, the token answers exactly as a revoked or expired one does.

The projection refactor was deliberately **behaviour-preserving** and only made the drift *visible*;
the ordering fix also carries the enforcement guard below.

**`not_scope_member` was a misnomer**, retired by cr-0162. The sites named it gated on `holdsGrant`, not on
membership, so a member whose grant was still pending (ADR 0020) was told they were "not a member". With the
boundary guard first, the residual 403 means precisely "accepted member, but not yet a key-holder" →
**`not_key_holder`** — never an unaccepted proposal, which the boundary already answered with 404.

## Consequences

- **The wire `code` becomes an attribute of the failure variant / shared catalog** — a small bleed of a boundary
  concern (the client-localization key) toward the domain result type. Accepted deliberately: the `code` *is* the
  failure's stable identity, and colocating it is exactly what makes the projection single-sourced and
  drift-proof. Generated request/response **models** still stay at the resource boundary (styleguide §2); only
  the stable error `code` string travels with the failure.
- **Sealed Outcomes, exhaustiveness, and handler tests are untouched by the projection refactor** — cr-0161 kept
  the status + code of every existing case, including the drifted 403s, which cr-0162 then corrected. The
  regression guard is the existing resource/HTTP-level status + code tests.
- **Enforcement guard (permanent), envelope:** `ErrorResponse(` is constructed in **exactly one** `shared/http/`
  file; no `…Resource` builds an error envelope directly and no per-resource
  `error`/`forbidden`/`notFound`/`conflict` envelope helper remains. Promoted from cr-0161's one-shot proof grep
  to a CI check (per [changes/README.md](../../changes/README.md)).
- **Enforcement guard (permanent), ordering:** `OperatorBlindProbeTest` fires every scope-, tenant-, share- and
  job-addressed operation as a **non-member** against both a real id and an invented one and asserts the two
  answers are identical in status and `code`; it fires the same operations as an unaccepted proposal (the same
  404), as a member of a suspended tenant (403) and as a member of a deleting group (a write 409s, a read is not
  refused by the gate). A **grep is explicitly not the right guard here** and must not replace it: guard *order*
  is not greppable, whereas the test fails on any future operation that probes before it gates. Its operation
  set is **derived from the contract** (the generated API interfaces), not listed: every operation is either
  probed or named on a reasoned exclusion list, so an operation nobody classified is red on the day it lands.
- **Styleguide §13** gains a short pointer ("HTTP failure projection → `shared/http/`; membership-gated absence
  → 404").
- **Rejected — full collapse to `Either<Failure, Success>` (the finding's literal form).** Delete the sealed
  Outcomes; a failure is just `Failure(code, httpStatus)`. Maximal LOC reduction, but discards compile-time
  exhaustiveness and turns typed handler tests stringly-typed (`failure.code == "slug_taken"`) — plausibly
  **negative** net value. Rejected.
- **Rejected — envelope-only dedup.** Extract just the `error()` helper to `shared/http/`, leave each resource's
  variant → status `when`. Smallest change, but leaves the projection re-derived per resource and does nothing
  about the drift. Rejected as half the value.
