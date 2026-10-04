#!/usr/bin/env python3
"""Per-glob meta-check for the backend mutation gate (ADR 0045 §4) — two assertions, one per hole the
build's aggregate `mutationThreshold` leaves open.

**What it asserts.** For every `targetClasses` glob in `pitest.scope.json`, read out of PIT's report:

1. **Non-empty** — the glob matches > 0 mutations. PIT's `failWhenNoMutations=true` already fails the build
   when the *whole* scope mutates nothing; the delta here is one stale glob among healthy siblings. A policy
   renamed while its glob is left behind (or a typo'd glob) then matches *nothing*, `failWhenNoMutations`
   stays green because the siblings carry the run, and that class loses its mutation coverage unnoticed.
2. **Survivor budget** — at most `SURVIVOR_BUDGET` undetected mutants, and at least one detected one. This is
   the per-member sibling of `mutationThreshold`, which is a score over the **whole** scope and so lets a weak
   class pass on its siblings' mutants.

**The defects that bought them.** (1) no incident — built with the gate itself (ADR 0045 §4) because a
whole-scope signal structurally cannot see a single stale member; `ai/README.md` rule 3 is the standing
reason. (2) **a real one, inbox #116:** the first `./gradlew pitest` after `TokenBucket` entered the scope
scored **56/58 = 97 %** and the build was **green** against the calibrated threshold of 95, while
`TokenBucket` — the one class the change was about — was **23/25 = 92 %** on its own, with *both* survivors
inside it. Nothing said so. Found only by running the gate; every structural check was green throughout.

**Why a count and not a percentage.** [backend/build.gradle.kts] justifies `mutationThreshold.set(95)` as
deliberate headroom for one *future* equivalent mutant (one PIT cannot prove equivalent, so it surfaces as
SURVIVED). Applied per class that same 95 % is unreachable: at the scope's measured sizes — 3, 6, 9, 15 and
25 mutants — a single survivor scores 66.7/83.3/88.9/93.3/96.0 %, so a per-glob 95 % floor would mean **100 %
for four of the five classes** and abolish the very headroom it was copied from. A budget of one undetected
mutant states the same intent independently of class size, and does not need re-tuning as the scope grows.

**The undetected set is PIT's own, not a hand-rolled status list.** A mutant counts as a survivor here when
its `detected` attribute is not `"true"`. Measured against PIT 1.19.4's `DetectionStatus.isDetected()`:
KILLED, TIMED_OUT, **NON_VIABLE**, MEMORY_ERROR and RUN_ERROR are detected; SURVIVED, NO_COVERAGE, NOT_STARTED
and STARTED are not. So a non-viable mutant — reported but never a real survivor — is excluded for free, and
an uncovered one correctly counts against the budget. Re-measure with:

    javac -cp <pitest.jar> Probe.java && java -cp <pitest.jar>:. Probe   # prints each status + isDetected()

**Their limits.** (1) proves a glob matched *something*, never that what it matched is the class the manifest
meant. (2) tolerates exactly one undetected mutant per glob **by design**, so a single genuinely-weak
assertion per class stays invisible; and it cannot tell an *equivalent* mutant (unkillable — no fix exists)
from a *missed* one, which stays a human call because PIT cannot make it either. Neither says anything about
code outside the manifest — the dumb stores and the `@QuarkusTest` slices are excluded on purpose (ADR 0045
§2), so a green run here is a claim about the pure decision tier and nothing else.

Deliberately diff-free — a stale glob points at nothing regardless of what a given PR changed, so the
invariant holds on every run, not only when the diff intersects the scope. It reads the SAME manifest the
`pitest{}` build block consumes (backend/core/pitest.scope.json), so the mutator and this guard never disagree on
WHAT is in scope.

Not a `validate_*.py`: the trust-chain job runs those on every push with no build, but this needs PIT's
`mutations.xml`, which only the mutation CI job produces — so it is invoked from that job, not the glob. It is
a permanent guard all the same, standing alongside the build's `mutationThreshold`. Runnable locally after a
`./gradlew pitest`:

    python3 scripts/mutation_meta_check_backend.py
"""
import fnmatch
import json
import pathlib
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
BACKEND = ROOT / "backend"
MANIFEST = BACKEND / "core" / "pitest.scope.json"  # :core owns PIT (it mutates core classes only)
REPORT = BACKEND / "core" / "build" / "reports" / "pitest" / "mutations.xml"

# One undetected mutant per glob — the per-class expression of the build threshold's own headroom clause. NOT
# a percentage: see the docstring for why 95 % cannot be applied per class at this scope's sizes.
SURVIVOR_BUDGET = 1


def scope_globs() -> list[str]:
    """The PIT class-name globs the build mutates — the single source of truth for WHAT is in scope."""
    return json.loads(MANIFEST.read_text())["targetClasses"]


def tally(glob: str, root: ET.Element) -> tuple[int, int]:
    """(total, undetected) mutants matching `glob`. Undetected == PIT's own `detected != true`, which already
    excludes NON_VIABLE/TIMED_OUT/MEMORY_ERROR/RUN_ERROR — see the docstring."""
    matched = [m for m in root.findall("mutation")
               if fnmatch.fnmatchcase((m.findtext("mutatedClass") or ""), glob)]
    return len(matched), sum(1 for m in matched if m.get("detected") != "true")


def main() -> int:
    if not REPORT.exists():
        print(f"::error::meta-check: no PIT report at {REPORT} — run `./gradlew pitest` first (the gate ran nothing).")
        return 1

    root = ET.parse(REPORT).getroot()
    failed = False
    for glob in scope_globs():
        total, undetected = tally(glob, root)
        detected = total - undetected

        if total == 0:
            failed = True
            print(f"::error::meta-check: scope glob '{glob}' matched 0 mutations — it points at nothing (a renamed "
                  f"class, or a typo) while its siblings carry the run, so failWhenNoMutations stayed green. Fix "
                  f"backend/core/pitest.scope.json or the class name (ADR 0045 §4).")
            continue

        if undetected > SURVIVOR_BUDGET:
            failed = True
            print(f"::error::meta-check: scope glob '{glob}' has {undetected} undetected mutants "
                  f"({detected}/{total} killed) — over the budget of {SURVIVOR_BUDGET}. The build's aggregate "
                  f"mutationThreshold can stay green on the siblings' mutants while THIS class is under the bar "
                  f"(inbox #116). Strengthen its assertions, or — if a mutant is genuinely equivalent — say so "
                  f"in the test and raise SURVIVOR_BUDGET deliberately (ADR 0045 §4).")
        elif detected == 0:
            failed = True
            print(f"::error::meta-check: scope glob '{glob}' has {total} mutation(s) and killed none — the "
                  f"survivor budget alone would pass it (a 1-mutant, 1-survivor glob is within budget), so this "
                  f"asserts separately that the class is tested at all (ADR 0045 §4).")
        else:
            print(f"meta-check: scope glob '{glob}' — {detected}/{total} killed, {undetected} undetected "
                  f"(budget {SURVIVOR_BUDGET}). OK.")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
