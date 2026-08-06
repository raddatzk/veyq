# ADR 0052 — One token-bucket core for every rate limit

- Status: Accepted
- Date: 2026-07-27

## Context

[ADR 0051](0051-declarative-rate-limit-posture.md) gave every endpoint a **declared** rate-limit posture and put
the *numbers* in `VeyqConfig` so the annotation carries the **key axis only**. It explicitly left the *algorithm*
open behind that indirection. This is the decision that fills it.

The algorithm in place was a **fixed window** aligned on the epoch: a key's counter lives in the slot
`nowSec - (nowSec % windowSeconds)` and resets when the slot rolls. Three forces:

- **The window-boundary burst.** A caller who times a burst across the wall-clock slot edge lands **2× the cap**
  within a second (`max` at the end of one slot, `max` at the start of the next). The bound reads as "60 per 60 s"
  and admits 120 in two seconds. This is a property of epoch-aligned windows, not an implementation slip.
- **Burst capacity and sustained rate are one number.** A window cannot express "60 per minute, but never more
  than 10 at once" — the cap *is* both quantities. Any future per-endpoint budget layer would have to pick one.
- **A wall-clock jump moves the bound.** The window is keyed on `System.currentTimeMillis()` epoch seconds, so an
  NTP step backwards re-opens a slot the caller already spent, and a step forwards rolls one early. Nothing was
  built against this; the window simply inherits whatever the clock does.

**No incident in this repo is attributable to the window model.** The boundary burst was never observed, never
reported, and — until this decision — was never even written down as a known limit. So this is a decision taken
on **design grounds, not on measured pain**, and it is recorded that way on purpose: the honest reason to spend
the change is that the boundary burst is a real weakness in a *security* bound (the share-password guess budget,
[ADR 0037](0037-password-protected-shares.md), whose whole argument is arithmetic), not that it hurt.

## Decision

**Every bound runs on one pure token-bucket core, derived from today's config, over monotonic time.**

- **The core is pure and mutation-scoped.** `TokenBucket` holds no state: it is functions over
  `(tokensMilli, lastRefillNanos, nowNanos, capacityMilli, nanosPerMilliToken)`, mirroring how the fixed-window
  arithmetic it replaces took `nowSec` as a parameter. It replaces `RateLimitWindow` in
  [ADR 0045](0045-mutation-testing-trust-chain-unit-layer.md) §2's mutate scope — the assertion-strength gate
  moves with the class, in the same change, or it silently stops covering the thing it exists for. One imperative
  shell (`TokenBucketCounter`) owns the concurrent map, the clock and the bounded key set; **no limiter
  re-implements the arithmetic**, and that completeness is enforced by `scripts/validate_ratelimit_core.py`
  rather than claimed here.

- **The bucket is *derived* from the existing config. No key is added, renamed or removed.**
  `capacity = max-per-window`, and the refill rate is `max-per-window / window-seconds` tokens per second. Two
  properties follow, and they are why the derivation is the decision rather than a detail:
  - **the long-run rate is unchanged** (a bucket refilling `max` tokens per `window` seconds admits exactly the
    old sustained rate) and **burst capacity equals the old cap** — so every operator's tuning keeps its meaning
    without them editing a line;
  - **a rename would have failed silently.** `@ConfigMapping` (SmallRye) ignores unknown keys, so renaming
    `max-per-window`/`window-seconds` to bucket vocabulary would leave a self-hoster who *deliberately tightened*
    a limit running on the default, with **no startup error and no log line**. The dangerous failure here is not
    a crash, it is silence — which is why the config surface is untouched ([invariant] self-hostable).

  The **separate burst/sustained dials** — the capability a bucket actually buys — are therefore *not* shipped
  with the algorithm. They land later as an **additive, optional** config key over the same core. This decision
  ships the algorithm and **one** of its two benefits: the boundary burst is gone, and the hard wall is gone.

- **Monotonic time.** `System.nanoTime()` in the shell, `nowNanos` as a core parameter. A wall-clock jump cannot
  move a bucket; negative elapsed time (a `nanoTime` comparison across an overflow, a stale state) is clamped to
  zero rather than trusted. This retires by design a class of bug the window model had by accident.

- **Integer milli-tokens, never a `Double`.** Tokens are counted in thousandths. Float accumulation across
  millions of `compute` calls drifts, and drift inside a mutation-scoped class makes mutants survive for reasons
  unrelated to assertion strength. **On refill, `lastRefillNanos` advances by the nanos actually converted into
  tokens — never to `now`.** Setting it to `now` discards the sub-token remainder on every call and systematically
  **under-refills** under high call rates; it is the one thing most token-bucket implementations get wrong, so it
  is named here and pinned by a unit test that an "advance to `now`" implementation fails.

- **Eviction gets simpler and stricter.** The bounded map (`MAX_TRACKED_KEYS`, against a flood of spoofed keys)
  stays, but the drop predicate becomes "**the bucket is full**": a full bucket is indistinguishable from an
  absent one, so dropping it is exactly free — where the window model had to reason about elapsed slots. Both
  hand-rolled limiters that are **unbounded** today inherit this bound by moving onto the shell. The bound holds
  **in steady state only** — what that condition is, and why it is not a hole, is stated once in
  [`cc.security.rate-limit`](../crosscutting/cc.security.rate-limit.md) § Known limits, not restated here.

- **`@RateLimit` is untouched.** Not a single annotation changes, which is [ADR 0051](0051-declarative-rate-limit-posture.md)'s
  `key`-indirection paying out exactly as claimed. It is **checked, not asserted**: the change is only complete
  when `grep -rn "@RateLimit(key" backend/src/main` is byte-identical to before it.

## Consequences

- **The 2× boundary burst is gone**, for every bound at once — including the share-password guess budget, whose
  security argument is the arithmetic.
- **The hard lockout is gone, and that is a real trade in one direction.** Under a window, a caller who burns the
  share-password budget (10 wrong / 60 s) faces a **wall until the slot rolls**; under the bucket they regain one
  guess every 6 s. **Throughput — what actually bounds a brute force — is identical**, but the felt friction is
  not. Net for that bound: strictly better on burst, slightly weaker on deterrence. For the invite anti-bombing
  axis the same change is an **improvement**: 3 mails per hour per recipient stops arriving as a burst of 3 and
  becomes one per 20 minutes. **If the wall is wanted back, it is a penalty/backoff feature — not a property of
  the algorithm**, and not a knob to smuggle into the core.
- **`Retry-After` becomes the wait for *one* token, not the time to the next slot edge.** It gets smaller and
  more truthful (it is now the answer to "when may I retry?", which under a window it never was), and it stays a
  positive whole second count.
- **Per-instance and in-memory is unchanged.** The bucket is not a distributed quota; a shared-instance bucket
  remains a documented known limit ([cc.security.rate-limit](../crosscutting/cc.security.rate-limit.md)).
  Operator-blind is untouched: bucket state is ephemeral, and keys stay the same server-visible metadata.

**Rejected — first-attempt anchoring** (the window starts on a key's first request instead of on the epoch).
Strictly stronger than the bucket on burst (it admits at most `max` in any `window`, where a full bucket plus
refill admits slightly more), a much smaller diff, and it was the refine's recommendation. Rejected because it
keeps burst and sustained rate fused into one number and keeps the hard wall, so the *next* limiter question
re-opens the same file; the bucket is the shape the two open follow-ups (per-endpoint budgets, separate dials)
both need. Chosen against the smaller fix knowingly — that is what makes this an ADR.

**Rejected — keep epoch alignment and document the burst.** The smallest possible diff: write the 2× boundary
case into § Known limits and move on. Rejected because the same paragraph would have to explain that a security
bound admits twice its stated cap, and a documented weakness in the guess budget is worse to carry than to fix.
It is worth noting that the boundary burst reached `ShareRateLimiter` *silently* precisely because it had never
been written down anywhere.

**Rejected — a `Double`-based bucket, or a library.** A `Double` is rejected above. A library (Bucket4j and
friends) is rejected for the same reason the mutate scope exists: the arithmetic is ~30 lines whose boundaries
this repo wants to **own and mutation-verify**, and a dependency here buys distributed backends we do not use at
the cost of a gate we do.
