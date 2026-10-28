# ADR 0051 — Declarative rate-limit posture per endpoint

- Status: Accepted
- Built: yes
- Date: 2026-07-27
- Model: [cc.security.rate-limit](../crosscutting/cc.security.rate-limit.md)

## Context

[ADR 0008](0008-centralized-http-auth.md) gave **authentication** a declaration and a fail-closed default: every
endpoint carries `@PermitAll` / `@Authenticated` / `@RolesAllowed`, and
`quarkus.security.jaxrs.deny-unannotated-endpoints=true` denies anything that forgot to say. Whether an endpoint
is **rate-limited** has no such statement. The forces:

- **Four mechanisms, keyed four ways, no common vocabulary.** A per-IP `@RateLimited` name-bound filter
  (`AuthRateLimitFilter`), a per-share window (`ShareRateLimiter`), a per-share only-on-wrong attempt counter
  (`SharePasswordAttemptLimiter`), and a per-(tenant,recipient) invite bound (`InviteMailRateLimiter`). Three of
  the four are invoked by hand inside a handler. The authoritative set is the source, never a doc —
  `grep -rln "RateLimit\|rateLimiter\|passwordAttemptLimiter\|inviteRateLimiter" backend/src/main --include="*.kt"`.
- **Nothing forces a *new* endpoint to state a posture.** Auth fails closed; rate limiting fails **open** — an
  endpoint added without a limiter ships unthrottled and looks exactly like one that was decided to be
  unthrottled. The two are indistinguishable in the tree, which is the actual defect: absence of a limiter
  carries no information.
- **The gap this already cost.** The missing tenant-invite throttle was found by **review**, not by a check, and
  the existing `RateLimitedEndpointsTest` is a three-endpoint spot-check whose own docstring names the remedy it
  could not build: *"A future declarative auth+rate-limit **posture** annotation + guard is the right home."*
- **Un-throttled public writes exist today.** Several `@PermitAll` guest operations on the sharing surface have
  no limiter behind them. Finding them is what a mandatory declaration does; closing them is the security work
  it exposes.
- **The asymmetry that shapes the decision.** Auth can be enforced uniformly at the boundary because the SPI
  resolves identity there. Rate limiting cannot: the **domain** keys (a `shareId`, a recipient address, an owner,
  a tenant) are only known **behind** the fine-grained authorization guard in the handler — the second of the two
  layers in [cc.security.authorization](../crosscutting/cc.security.authorization.md). A boundary filter has no
  access to them, so a single enforcing annotation for all keys is not buildable.

## Decision

**Every HTTP endpoint declares a rate-limit posture, and a reflection sweep makes the declaration mandatory.**

- **Vocabulary.** `@RateLimit(key = IP | USER | SHARE | RECIPIENT | OWNER | TENANT)`, or
  `@RateLimitExempt(reason = "…")` with a non-blank reason. Class-level and inheritable, mirroring how
  `@Authenticated` is placed today; a method-level annotation wins.
- **The annotation carries the key axis only — never the numbers.** Limit values stay in `VeyqConfig`
  (`veyq.ratelimit.*`, `veyq.share.*.rate.*`), tunable per instance ([invariant] self-hostable). This
  indirection is what lets the limiter *core* change without touching a single annotation — and it has since
  been collected on: the fixed window → token-bucket swap ([ADR 0052](0052-token-bucket-rate-limit-core.md))
  replaced every bound's algorithm with `grep -rn "@RateLimit(key" backend/src/main` byte-identical before and
  after.
- **Enforcement and declaration are not the same thing, and the split is by key.**
  - `IP` and `USER` are **runtime-enforced** by a generalized boundary filter. `IP` keys on the client's IP pre-auth, as
    [ADR 0094](0094-a-clients-address-is-the-hop-the-network-vouches-for.md) resolves it; `USER` keys on the resolved `SecurityIdentity` principal.
  - `SHARE`, `RECIPIENT`, `OWNER`, `TENANT` are **declaration markers**. Enforcement stays in the handler,
    behind the fine-grained guard, because that is the first point the key exists.
- **`USER` runs after authorization, not with `IP`.** Under
  `quarkus.http.auth.proactive=false` ([ADR 0008](0008-centralized-http-auth.md), lazy authentication) there is
  no resolved identity at `Priorities.AUTHENTICATION`, where the per-IP filter sits. `USER` is therefore a
  second, later-priority filter — not a branch of the first.
- **Two statically decidable cross-rules**, asserted by the sweep because they encode a real confusion:
  `key = IP ⟹ @PermitAll` (an IP bound on an authenticated endpoint is the wrong axis — it throttles a
  legitimate active user and the identity is the better key), and `key = USER ⟹ @Authenticated` (there is no
  principal to key on otherwise).
- **The guard is a reflection sweep in the `DefaultDenyTest` family** — every JAX-RS endpoint carries one of the
  two annotations, `reason` is non-blank, and both cross-rules hold — run by `./gradlew check`.

**What a green sweep proves, and where it stops.** Green means every endpoint **declares** a posture. It does
**not** mean the domain-keyed limiters **fire** — that is proven per endpoint by a runtime `429` test, and by
nothing else. This boundary is recorded in the guard-gap map (`scripts/validate_guard_gaps.py` + the
`trust-chain` skill) rather than left as a reassuring word here, because a marker annotation that looks like an
enforcing one is exactly the misreading a later change would make.

**Rejected — one enforcing annotation for all six keys.** It cannot be built: the domain keys live behind the
handler's authorization guard, and hoisting them to the boundary would mean re-resolving share/tenant state
before authz — duplicating the guard and inverting the two-layer model
([cc.security.authorization](../crosscutting/cc.security.authorization.md)). Accepting a weaker marker for four
of six keys, and **naming** that weakness, beats a uniform annotation that silently does nothing on four of them.

**Rejected — declaring the posture in the OpenAPI spec and generating the annotation.** This is
[ADR 0008](0008-centralized-http-auth.md)'s rejected path and it binds here for the same reason: posture is an
enforcement **policy**, not contract shape, and it does not belong in a published spec. Independently verified
as unbuildable without a template fork — the bundled `kotlin-server`/`jaxrs-spec` templates (7.21.0) carry no
operation-level extra-annotation hook at all; only `x-tags` exists.

**Rejected — a `scripts/validate_*.py` guard.** No Python guard parses Kotlin, and a regex would be wrong here
rather than merely crude: an endpoint's annotations are split across the **generated `…Api` interface** and the
hand-written implementing `…Resource`, so the posture of a method is only knowable by resolving that
inheritance. Reflection does it; a text scan cannot. This is the trade-off the trust chain accepts knowingly —
the sweep runs in `./gradlew check`, not in the buildless
[`trust-chain.yml`](../../.github/workflows/trust-chain.yml) glob, so a **docs-only** change never executes it.

## Consequences

- **The fail-open default is gone.** A new endpoint that says nothing about throttling fails the build, the way
  one that says nothing about auth already fails the request. Absence of a limiter now carries information: it
  means someone wrote a reason.
- **The un-throttled public surface becomes visible, and closing it is real work** — new limiters (on the
  mutation-verified `TokenBucket` core, [ADR 0052](0052-token-bucket-rate-limit-core.md) +
  [ADR 0045](0045-mutation-testing-trust-chain-unit-layer.md), never a re-implemented one — a rule
  `scripts/validate_ratelimit_core.py` now enforces) with conservative values, or an honest exemption. The risk runs both ways: a limit set
  too tight locks out a legitimate bulk contributor, which is why the values are config and the defaults are
  deliberately loose.
- **Operator-blind is untouched.** Every limiter key — IP, `userId`, `shareId`, recipient address (already
  plaintext per [ADR 0049](0049-email-delivery-boundary.md)), owner, tenant — is server-visible operational
  metadata the backend already holds. No key may be derived from content; that is a hard boundary on any future
  key added to the enum.
- **Cost: two filters where there was one, and four annotations that do not enforce.** Accepted, because the
  alternative to a marker is not an enforcer — it is silence.
- **The sweep has a named blind spot: test-scope resources.** `DefaultDenyTest`'s `UnannotatedProbeResource` is a
  deliberately annotation-free `@Path` class, and under `@QuarkusTest` it is deployed like any other. The sweep
  scopes to the application's own resource packages; annotating the probe to satisfy it would destroy the
  default-deny proof. The exclusion is part of the gap-map entry, not a silent filter.
