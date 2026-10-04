#!/usr/bin/env python3
"""Guard that rate-limit state lives in exactly one place: the kernel package `com.veyq.core.ratelimit` (ADR 0052).

**What it asserts.** No main Kotlin file of the backend whose name contains `Limiter` or `Counter` holds
rate-limit state — a concurrent map, a hand-rolled map, an atomic counter — unless it sits inside the shared
`core/ratelimit/` package. Plus the inverse, so the check cannot pass by pointing at nothing: the shared
package must itself still hold that state (a renamed or emptied core makes this red instead of vacuously green).
One core, one shell, one place where a bucket is stored.

**The defect that bought it.** Rate limiting arrived four times in a row, each time with its own map and its own
arithmetic: a per-IP filter, `ShareRateLimiter`, `SharePasswordAttemptLimiter` (its own `Window` data class and
inline window maths) and `InviteMailRateLimiter` (two maps of its own, over borrowed arithmetic). Two consequences
were measured, not feared. The **window-boundary burst** — an attacker timing a burst across the wall-clock slot
edge lands 2× the cap — reached `ShareRateLimiter` silently, because it was a property nobody had written down in
any of the four copies. And both hand-rolled maps were **unbounded**, while the shared shell had had a bounded key
set since the day it was written; a flood of spoofed keys grew them without limit. `cr-ratelimit-posture` reduced
four to two by hand and `cr-token-bucket-rate-limit-core` two to zero — and a count in a deleted CR proves nothing
about tomorrow, which is why the completeness proof is this file rather than that tally
(`changes/README.md` § promote one-shot proofs).

**Its limit.** This is a **filename + symbol text grep**, deliberately: it is decidable without a build, so it
runs in the buildless trust-chain glob on every push (unlike the JVM-reflection posture sweep of ADR 0051, which
`./gradlew check` owns). Three things it therefore does not see:
  1. a limiter that **avoids both name parts** — `ShareThrottle.kt`, `GuestBudget.kt` — evades it entirely. That
     gap is pinned open, executably, in `validate_guard_gaps.py`; the naming convention is the only thing that
     makes the grep reach, and no grep can enforce a name nobody wrote yet.
  2. **re-implemented arithmetic without state** — a `retryAfter` computed inline in a handler, borrowing no map.
     Green here means "the state lives in one place", never "the maths lives in one place".
  3. the **test** tree, excluded on purpose: a test may hold whatever state it needs to drive a limiter.

A `validate_*.py`, so the trust-chain glob runs it on every push with no build. Runnable locally:

    python3 scripts/validate_ratelimit_core.py
"""
from __future__ import annotations

import pathlib
import sys

import backend_tree  # where the backend's sources live — resolved by glob, never spelled here

ROOT = pathlib.Path(__file__).resolve().parent.parent
CORE = backend_tree.package_dir("com.veyq.core.ratelimit")

# A file that *is* a rate limiter by name. Both parts are load-bearing: `Counter` catches the shell shape
# (`FixedWindowCounter`, `TokenBucketCounter`), `Limiter` the handler-side ones (`ShareRateLimiter`,
# `SharePasswordAttemptLimiter`, `InviteMailRateLimiter`).
NAME_MARKERS = ("Limiter", "Counter")

# How rate-limit state is actually held in this codebase's history — the two hand-rolled offenders used a
# `ConcurrentHashMap`, and the shell additionally an `AtomicLong`. The others are the near-misses a
# re-implementation would reach for next.
STATE_MARKERS = (
    "ConcurrentHashMap",
    "ConcurrentMap",
    "AtomicLong",
    "AtomicInteger",
    "mutableMapOf",
    "HashMap<",
)


def limiter_files() -> list[pathlib.Path]:
    """Every `*Limiter*.kt` / `*Counter*.kt` in backend main sources, in a stable order."""
    return sorted(
        p for p in backend_tree.main_kotlin()
        if any(marker in p.name for marker in NAME_MARKERS)
    )


def state_hits(path: pathlib.Path) -> list[tuple[int, str, str]]:
    """`(line number, marker, the line)` for every rate-limit-state marker the file holds."""
    hits = []
    for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if line.lstrip().startswith(("*", "//", "/*")):
            continue  # a marker named in prose is documentation, not state
        # First marker wins: `ConcurrentHashMap` also contains `HashMap<`, and one line is one defect.
        for marker in STATE_MARKERS:
            if marker in line:
                hits.append((n, marker, line.strip()))
                break
    return hits


def main() -> int:
    if not CORE.is_dir():
        print("RATELIMIT-CORE VALIDATION FAILED:")
        print(f"  - the shared core package is gone: {CORE.relative_to(ROOT)} does not exist. Every check below "
              f"would pass by looking at nothing — repoint this guard at the core's new home (ADR 0052).")
        return 1

    errors: list[str] = []
    outside = []
    for path in limiter_files():
        rel = path.relative_to(ROOT)
        if CORE in path.parents:
            continue
        outside.append(rel)
        for n, marker, line in state_hits(path):
            errors.append(
                f"{rel}:{n} holds rate-limit state (`{marker}`) outside the shared core — every bound runs on "
                f"`core/ratelimit/TokenBucketCounter` (ADR 0052), which owns the map, the bounded key set and "
                f"the arithmetic. Offending line: {line}")

    core_state = [p.relative_to(ROOT) for p in limiter_files() if CORE in p.parents and state_hits(p)]
    if not core_state:
        errors.append(
            f"no file in {CORE.relative_to(ROOT)} holds rate-limit state ({'/'.join(STATE_MARKERS)}) — the shared "
            f"shell is what makes 'nowhere else' meaningful, so its absence is a red, not a green.")

    if errors:
        print("RATELIMIT-CORE VALIDATION FAILED:")
        for e in errors:
            print("  -", e)
        return 1

    print(
        f"ratelimit core OK: {len(outside)} limiter/counter file(s) outside "
        f"{CORE.relative_to(ROOT)} hold no rate-limit state; the shared shell holds it "
        f"({', '.join(str(p.name) for p in core_state)})")
    for rel in outside:
        print(f"    - {rel} (state-free, drives the shared core)")
    print("  scope: a filename+symbol grep over backend main sources. NOT 'no re-implemented arithmetic' and NOT "
          "'a limiter under any other name' — see the docstring.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
