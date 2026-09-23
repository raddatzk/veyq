# Concern: Public rate limiting

- **ID:** cc.security.rate-limit
- **Type:** **Cross-cutting concern** — a horizontal security mechanism that protects many flows; it has no
  standalone end-user goal (not a use-case) and is not a feature substrate (not an enabler). It is recorded
  here so its **mechanism + its verification are traceable** from the threat model, rather than living as an
  orphan test next to the code (the gap a 2026-06 coverage audit found).
- **Status:** every HTTP endpoint now **declares** a rate-limit posture ([ADR 0051](../decisions/0051-declarative-rate-limit-posture.md)),
  enforced by a reflection sweep in `./gradlew check`; four bounds are shipped — per-IP, per-principal,
  per-share (uploads, identity creation, password attempts) and per-(tenant,recipient). Verification is
  **partial by design**, and the split is now explicit rather than incidental: the *declaration* is universal and
  guarded, the *enforcement* is proven per endpoint by runtime `429` tests, and where neither applies the
  endpoint carries a written exemption (§ Unbounded by decision).

## Protects against

**Online** brute-force / credential-stuffing / DoS against the **public** authentication surface — the
endpoints reachable without a session, where an attacker can hammer login or spin token churn. (Offline
brute-force of the password is a *separate* property, handled cryptographically — see
[security-model](security-model.md): Secret Key / 2SKD and the Ed25519 auth-key stance.)

## The posture convention (ADR 0051)

Every endpoint carries **`@RateLimit(key = …)`** or **`@RateLimitExempt(reason = …)`**, the way it already
carries an auth annotation. The rule this exists to fix: before it, an endpoint with no limiter and an endpoint
nobody had thought about looked **identical in the tree**, so absence of a limiter carried no information. The
`reason` is what makes them distinguishable — which is why a blank one fails the build.

The annotation carries the **key axis only, never the numbers**: values stay in `VeyqConfig` so a self-hosted
instance can tune them ([invariant] self-hostable), and so the limiter core can change without touching a single
annotation. Keys, and who enforces them:

| key | enforced by | keyed on |
|---|---|---|
| `IP` | `IpRateLimitFilter` (boundary, pre-auth) | client remote address — only valid on `@PermitAll` |
| `USER` | `UserRateLimitFilter` (boundary, post-authorization) | resolved principal — only valid on `@Authenticated` |
| `SHARE` / `RECIPIENT` / `OWNER` / `TENANT` | **the handler** | a domain value that only exists behind the fine-grained authz guard |

The domain keys are **markers, not enforcement** — a boundary filter cannot know them ([cc.security.authorization](cc.security.authorization.md),
the two-layer map). ADR 0051 records why accepting a named weakness beats a uniform annotation that would
silently do nothing on four of six keys.

**The authoritative set is the annotation in the source, never a copy here** — a hand-kept matrix drifts (as it
did: cr-0044 deleted a phantom `POST /api/token` row). When you need the exact set:
`grep -rn '@RateLimit(key' backend/src/main --include="*.kt"`.

## Mechanism

Every bound is a **token bucket** over the pure, mutation-verified `TokenBucket` arithmetic
([ADR 0052](../decisions/0052-token-bucket-rate-limit-core.md),
[ADR 0045](../decisions/0045-mutation-testing-trust-chain-unit-layer.md) §2) — no limiter re-implements it, and
that is **enforced**, not asserted: `scripts/validate_ratelimit_core.py` fails the build the moment rate-limit
state appears outside `backend/.../shared/ratelimit/`. `TokenBucketCounter` is the shared imperative shell
(concurrent map + clock + a bounded key set); the filters and the handler-side limiters drive it. With no token
left the request is rejected with **`429` + `rate_limited`** and a `Retry-After` — the wait for *one* token, not
for a slot edge.

Three properties worth knowing before tuning anything (ADR 0052 owns the reasoning):

- **The bucket is derived from the window config, so the numbers mean what they always meant**: capacity =
  `max-per-window`, refilled at `max-per-window / window-seconds` per second. Burst size and sustained rate are
  unchanged, and **no config key was added or renamed** — a rename would have left a tightened self-hosted limit
  silently on the default (`@ConfigMapping` ignores unknown keys). Separate burst/sustained dials are a later,
  additive, optional key.
- **Refill is gradual, so there is no hard wall**: an exhausted caller regains one unit every
  `window / max` seconds instead of waiting for a window to roll. Throughput is identical; the felt friction is
  not (see the password bound below, and ADR 0052 § Consequences for the one direction where that is a trade).
- **Time is monotonic** (`System.nanoTime()`), so a wall-clock/NTP jump cannot move a bucket.

All bounds are **in-memory and per-instance** (see *Known limits* below). Config lives under
`veyq.ratelimit.*` (`auth.*` = the per-IP bound, prefix kept for config compatibility; `user.*`, `invite.*`) and
`veyq.share.*` (`upload.rate.*`, `identity.rate.*`, `password.rate.*`).

## Governs (per-IP bound)

The unauthenticated surface: `LoginResource` (challenge/verify) and `TokenResource` (refresh) carry it
class-level; `SetupResource.createServerOwner` and `TenantsResource.previewTenantInvite` +
`.registerAndAcceptTenantInvite` carry it method-level — the bootstrap-secret and account-creation oracles.

**All of them share one bucket per IP**, deliberately: it bounds an attacker's total public-surface spend
instead of handing them a fresh budget per route. That shared bucket is also why the tenant-invite mint does
**not** use this key — an authenticated owner sits behind one address and would be counted against the same
bucket as anonymous login traffic, so its bound is per-(tenant,recipient) instead.

> The runtime tests below hit the class-level endpoints, proving mechanism **and** wiring in one. The three
> method-level account-creation endpoints are pinned instead by a **static** check that they carry `key = IP`
> specifically (`RateLimitedEndpointsTest`) — the universal sweep only proves they declare *something*.

## The guard — what green proves, and where it stops

`RateLimitPostureSweepTest` (in `./gradlew check`) enumerates every JAX-RS endpoint and asserts: a posture is
declared; an exemption's `reason` is non-blank; `key = IP` sits only on `@PermitAll`; `key = USER` only on
`@Authenticated`; and a **method-level `@PermitAll` declares its own posture** rather than inheriting one
written for the resource's authenticated surface — the hole a class-level default would otherwise leave open.

It enumerates **twice on different keys** (a walk of the compiled main classes, and the live CDI container's
`@Path` beans) and fails on disagreement, because a sweep that silently misses an endpoint is worse than no
sweep: it reads as coverage.

**Green means every endpoint *declares* a posture. It does not mean the domain-keyed limiters *fire*** — that
is proven only by the runtime `429` tests below. Two blind spots, both named rather than hidden: test-scope
resources are excluded (`DefaultDenyTest`'s probe must stay annotation-free), and the sweep is a JVM test, so
it cannot be pinned by `scripts/validate_guard_gaps.py` — see the `trust-chain` skill's gap-map.

## Unbounded by decision

Endpoints that carry `@RateLimitExempt` because **no available axis fits**, not because nobody looked. Recorded
here because the code's `reason` strings point at this section rather than each restating the argument.

- **The three anonymous guest reads** — `resolveShare`, `resolveShareBlob`, `getShareTerms`. Both keys are
  wrong here: `key = SHARE` caps a share's whole audience *collectively* (a Memory sent to 200 guests is the
  normal case, not the abuse case), and `key = IP` shares the single per-IP bucket with login, so one gallery
  view — dozens of blob presigns — would trip it. What **does** bind them: `SharePasswordAttemptLimiter` on the
  guessing axis and the non-enumerable 404 on discovery. What stays **open**: raw request volume by someone who
  already holds the link. Closing it needs a per-policy budget layer (a named `policy =` on the annotation), so
  the axis is right *and* the numbers are its own — deliberately out of scope of the posture work itself.
- **`MediaResource`** — presign minting is a real amplification axis and `key = USER` now exists. Held back
  because a per-user cap on the main upload and browse path has to be verified against real traffic first, or
  it throttles a legitimate bulk upload. This is the open item most likely to be taken next.
- **`TasksResource`** — high-frequency polling *is* the design ([ADR 0021](../decisions/0021-operator-blind-background-tasks.md));
  a per-user request cap would throttle the mechanism rather than an abuse of it. What a claim can consume is
  bounded by the lease and attempt machinery, not by request count.

The rest of the exemptions are ordinary: authenticated, membership- or owner-gated surfaces where the session
is attributable and revocable. Their reasons live at the annotation, which is the only place they stay true.

## Known limits (honest, defense-in-depth — not a hard guarantee)

**Every algorithmic weakness of the core belongs in this list, or it does not exist as far as this repo is
concerned.** That is an obligation, not a remark: the fixed window's **window-boundary burst** (a caller timing a
burst across the wall-clock slot edge landed 2× the stated cap within a second) was never written down here, and
that is exactly how it reached `ShareRateLimiter` — a new bound inherited a weakness nobody could read. The burst
is gone with the algorithm ([ADR 0052](../decisions/0052-token-bucket-rate-limit-core.md)); the obligation stays.

- **Per-instance / in-memory.** A bucket is not shared across replicas; behind a load balancer each
  instance counts independently.
- **Resets on restart.** A process restart clears every bucket — which, for a limiter, errs toward the caller.
- **Trusts the client IP.** Behind a proxy the key is the forwarded address, and Quarkus honours that header
  from any peer that reaches the app. [ADR 0071](../decisions/0071-runtime-hardening-on-both-targets.md)
  narrows the peers to the proxies veyq ships or the operator names, by network: separate compose networks,
  and a NetworkPolicy set on Helm. A peer inside that set, or a cluster whose CNI does not enforce policy,
  can still forge the header. A NAT'd source still shares one bucket.
- **A full bucket may be evicted under key-flood pressure** (past `MAX_TRACKED_KEYS`). Behaviourally free — a
  full bucket is indistinguishable from an absent one.
- **The key map is bounded in steady state, not absolutely** — the weakness that pairs with the eviction above,
  and the one this list exists to not round off. The pass drops only **full** buckets, and a bucket refills one
  token's worth of time after its last request, so an idle flood is reclaimed. A flood arriving **faster than one
  token refills** leaves every bucket partly spent, nothing is evictable, and the map grows for as long as the
  flood outpaces the refill. At the per-IP bound's one-token-per-second that needs on the order of 100 000
  distinct keys per second — a documented shape, not a hole, and the same shape the fixed window had.

These are **deferred** hardening items (a distributed store, an address-based `trusted-proxies` default), recorded so the limit
isn't mistaken for a strong guarantee. It raises the cost of automated abuse; it does not make it impossible.

## Verified by

Runtime tests (RestAssured) hit the **real** governed endpoints, so a `429` can only fire where `@RateLimited`
is actually wired — proving mechanism **and** configuration in one. Each test presents its own client IP via
`X-Forwarded-For` (the test profile enables `proxy-address-forwarding`), so the cases are order-independent and
the per-IP property is asserted directly.

- `AuthRateLimitTest.challengeIsThrottledOverTheLimit` — past the limit `/login/challenge` → `429` +
  `rate_limited`.
- `AuthRateLimitTest.verifyIsThrottledOverTheLimit` — `/login/verify` is throttled.
- `AuthRateLimitTest.refreshIsThrottledOverTheLimit` — `/token/refresh` is throttled.
- `AuthRateLimitTest.oneIpTrippingTheLimitDoesNotThrottleAnother` — **per-IP isolation**: one IP hitting the
  limit does not throttle a second IP.
- `AuthRateLimitRefillTest.theBucketRefillsSoADrainedCallerIsAllowedAgain` — **refill**: under a 1-second
  window (3 per second) a drained per-IP bucket accrues a token and lets the caller through again.

**Out of scope for any test:** whether the configured *limit value* is the right number — that is a policy
judgement (review + config), not something a test asserts.

## Governs (per-share password-attempt bound, cr-0165)

A **second, independent** bound protects password-protected shares ([ADR 0037](../decisions/0037-password-protected-shares.md))
from online password guessing. It is **not** per-IP: a share's anonymous guests sit
behind one proxy address, so a per-IP counter would collapse to a global cap on the share (throttling honest
visitors) while an IP-rotating attacker evades it. The attack targets one `shareId`, so the counter keys on that.

- **Mechanism:** `backend/.../features/sharing/SharePasswordAttemptLimiter.kt` — a per-`shareId` bucket on the
  shared core, in-memory and per-instance like every other bound. It is consumed **only on a wrong attempt**,
  inside `SharingHandler.passwordCheck`: a correct password is admitted *before* the bucket is touched (a
  legitimate guest is never locked out), and a tokenless challenge-fetch never spends budget. Over the cap the
  guest gets `429 rate_limited` instead of the `403 share_password_incorrect` oracle. Config
  `veyq.share.password.rate.max-per-window` (default **10**) / `.window-seconds` (default **60**).
- **What the bucket changed for this bound, in both directions** ([ADR 0052](../decisions/0052-token-bucket-rate-limit-core.md)):
  the 2× boundary burst that undercut the stated cap is gone, and a guesser who exhausts the budget now regains
  one guess every 6 s instead of facing a wall until the window rolls. Guess **throughput** — the quantity that
  actually bounds a brute force, and the one [ADR 0037](../decisions/0037-password-protected-shares.md)'s argument
  rests on — is unchanged; deterrence is marginally softer. Restoring the wall would be a penalty/backoff feature,
  not a property of the core.
- **Rides all six guest endpoints:** since cr-0164 the same `passwordCheck` fronts resolve, blob GET, and the four
  guest-write ops — so the bound applies wherever a wrong password can be presented, not just the resolve.

**Verified by** `SharePasswordAttemptBoundTest` (runtime, RestAssured; drives the budget down to 3 via its own
`@TestProfile`): N wrong → `403`, the N+1st → `429`; a tokenless resolve never consumes budget; one share's
exhausted budget does not block another (the per-share axis); and a guest **write** op inherits the bound.

## Governs (per-share contributor-write bounds)

Two further per-share budgets on the anonymous contributor path (`ShareRateLimiter`, ADR 0030), kept
**separate** so one abusive axis cannot take the other down with it:

- **Uploads** — `startShareBlobUploads` + `completeShareMedia` (the start and attach halves of one flow).
  Generous by intent: a legitimate bulk contributor uploading an evening's photos is the normal case.
  `veyq.share.upload.rate.*` (default 300 / 60 s).
- **Identity creation** — `createShareIdentity`, the one guest call that writes a row **before any upload
  exists**, so an unbounded link holder could grow the identity table on its own. A real guest names itself
  **once** per share, so the cap is an order of magnitude tighter without ever touching honest traffic.
  `veyq.share.identity.rate.*` (default 30 / 3600 s).

`deleteShareMedia` is deliberately unbounded: the store authorizes it by **single-row ownership**, so a
contributor can only delete rows it created — rows the upload budget already caps. A delete bound would cap
nothing new.

**Verified by** `ShareIdentityBoundTest` (runtime, RestAssured, budget driven to 3 via its own `@TestProfile`,
on a **password-less** share so a 429 can only come from the identity budget): the N+1st create is `429`; an
exhausted share does not spend another's budget; and the identity budget is separate from the upload one.

## Related

- Concept: [security-model](security-model.md) (threat model — this is the "online brute-force / DoS" row's
  mechanism + verification)
- Decision: [ADR 0051](../decisions/0051-declarative-rate-limit-posture.md) (the posture vocabulary + the guard)
- Decision: [ADR 0052](../decisions/0052-token-bucket-rate-limit-core.md) (the one token-bucket core behind it)
- Code: `backend/.../shared/ratelimit/` (the vocabulary, the two boundary filters, the shared bucket core),
  `backend/.../features/sharing/{ShareRateLimiter,SharePasswordAttemptLimiter}.kt`,
  `backend/.../features/admin/tenant/InviteMailRateLimiter.kt`
