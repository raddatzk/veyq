# ADR 0042 — HTTP failure projection: one shared envelope, self-describing failure variants

- Status: Accepted
- Built: yes
- Date: 2026-07-14
- Model: [developer-styleguide](../crosscutting/developer-styleguide.md)

## Context

Backend operations return rich sealed `…Outcome` types across every feature. Before this decision each `…Resource`
mapped its Outcome's failure variants to an HTTP response with a `when(outcome)` ladder **and constructed the error
body itself**. Two duplications resulted:

1. **Envelope construction was copy-pasted in several idioms.** The generated wire contract `ErrorResponse{code}`
   (required `code`, optional `message`; single-sourced in `_common`,
   [ADR 0038](0038-contracts-shared-components-bundling.md)) was built through per-resource `error(status, code)`
   helpers, named per-code helpers (`forbidden`/`notFound`/`conflict`), fully inline `Response.status(…)` calls, and
   the central auth and rate-limit providers.
2. **The variant → (code, status) projection was re-derived per resource — and it had drifted.** The same semantic
   "**caller is not a member of the scope**" was a **404** (`scope_not_found`) in content, media and favorites but a
   **403** (`not_scope_member`) in scope epochs, the member list and task enqueue. (That inventory named the symptom,
   not the defect — see the guard-ordering rule below for what actually leaked.)

The sealed variants themselves are **not** duplication to delete — they are the typed domain vocabulary that
gives compile-time exhaustiveness, self-documenting handler signatures, and typed handler tests
(`outcome is SlugTaken`). The problem was the **un-centralized projection to HTTP**, not the variant count; the
uniform `ErrorResponse{code}` body was one shape built several ways, not several body schemas.

## Decision

Centralize the HTTP failure projection in the existing `core/http/` leaf, **keeping the sealed `…Outcome` types
intact**:

- **A semantic status bucket owns the one status table.** `core/http/` holds the single `bucket → HTTP status`
  mapping, for the Outcome failures and for the central providers and non-Outcome error sites alike. The status
  literal is defined **once**, never per resource.
- **Failure variants self-describe their projection.** Every failure variant carries its stable `code` + its
  bucket via a shared `HttpFailure` interface (`val code`, `val status`). Recurring failures (e.g.
  `scope_not_found`, `not_key_holder`, `slug_taken`) are declared **once** in a shared `Failures` catalog and
  referenced, so the wire `code` + bucket for a given semantic failure has a single source of truth. **Drift
  becomes structurally impossible**: two sites cannot disagree on the status of "not a member" because the
  (code, bucket) lives on the one catalog entry, not in two `when` arms.
- **One envelope writer.** `core/http/` exposes the single function that renders
  `HttpFailure → Response.status(bucket).entity(ErrorResponse(code))`. It is the **only** place `ErrorResponse`
  is constructed — every resource **and** the central providers (the auth `ExceptionMapper`s, the rate-limit
  filter) render through it. Each resource `when` collapses to `is <Success> → 2xx …; is HttpFailure →
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

The boundary is **one** call, and every scope-, tenant-, share- and job-addressed operation makes it inline before
anything else. What it answers, and in which order — a stranger's 404, then the states only a member may learn
(suspension, a left tenant, a deleting scope) — is
[uc.spaces.scope-access](../features/spaces/use-cases/uc.spaces.scope-access.md)'s; an unaccepted proposal
([ADR 0014](0014-group-scopes-and-governance.md) §5) is no membership for it. The key-holder check comes next, and
every existence, kind, role or capability check after that.

This applies to *scope-addressed* operations, where the caller names an id they may have guessed. It must **not**
be applied to paths a non-member reaches legitimately by **possessing an unguessable token** — the `@PermitAll`
share-guest ops and tenant `preview`/`register`/`accept`. Those callers already know the resource exists, so
their 403s are correct. They are still not members, so they learn nothing of a scope's deletion or a tenant's
suspension either: while either holds, the token answers exactly as a revoked or expired one does.

**The residual 403 is `not_key_holder`**: "accepted member, but not yet a key-holder" — never an unaccepted
proposal, which the boundary already answered with 404. A code that gates on the grant does not name membership:
the old `not_scope_member` did, and told a member whose grant was still pending (ADR 0020) they were "not a member".

## Consequences

- **The wire `code` becomes an attribute of the failure variant / shared catalog** — a small bleed of a boundary
  concern (the client-localization key) toward the domain result type. Accepted deliberately: the `code` *is* the
  failure's stable identity, and colocating it is exactly what makes the projection single-sourced and
  drift-proof. Generated request/response **models** still stay at the resource boundary (styleguide §2); only
  the stable error `code` string travels with the failure.
- **Sealed Outcomes, exhaustiveness, and handler tests are untouched by the projection**: it keeps the status +
  code of every case, and the regression guard is the resource/HTTP-level status + code tests.
- **Enforcement guard (permanent), envelope:** a static check holds `ErrorResponse` to the one writer, so no
  resource builds an envelope or keeps an envelope helper of its own.
- **Enforcement guard (permanent), ordering:** a test fires every scope-, tenant-, share- and job-addressed operation
  as a non-member against a real id and an invented one and asserts identical answers
  ([cc.security.authorization § Verified by](../crosscutting/cc.security.authorization.md#verified-by)). A **grep is
  explicitly not the right guard here** and must not replace it: guard *order* is not greppable, whereas the test
  fails on any future operation that probes before it gates. Its operation set is **derived from the contract**, not
  listed, so an operation nobody classified is red on the day it lands.
- **Rejected — full collapse to `Either<Failure, Success>`.** Delete the sealed
  Outcomes; a failure is just `Failure(code, httpStatus)`. Maximal LOC reduction, but discards compile-time
  exhaustiveness and turns typed handler tests stringly-typed (`failure.code == "slug_taken"`) — plausibly
  **negative** net value. Rejected.
- **Rejected — envelope-only dedup.** Extract just the `error()` helper to `core/http/`, leave each resource's
  variant → status `when`. Smallest change, but leaves the projection re-derived per resource and does nothing
  about the drift. Rejected as half the value.
