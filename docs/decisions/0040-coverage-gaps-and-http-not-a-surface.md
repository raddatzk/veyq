# ADR 0040 — Coverage model: `gaps` are uncovered surfaces; `http` is IDE tooling, not a coverage surface

- Status: Accepted
- Date: 2026-07-13

> Realises the rest of audit finding **#62 (P1b)**; the class-scoped-resolution core already landed.
> Merges the two split sub-findings — inbox **#81** (coverage/`gaps`
> overlap) and **#82** (CI-vs-live levels) — because the refine found they share one root cause. To be
> implemented by a change-request (P1b follow-up); this ADR is the decision it builds on.

## Context

The acceptance-coverage trust chain (`feature → use-case → criteria → tests` — see
[docs/coverage/README.md](../coverage/README.md)) records, per acceptance criterion, a `coverage` map keyed by
**area** (`backend · web`) then **level** (`unit · integration · e2e`), plus a
per-use-case `gaps` map (`area: reason`) for surfaces "intentionally not (yet) covered". `scripts/validate_coverage.py`
enforces that every criterion has ≥1 test and every ref resolves.

P1b flagged three things. The class-vs-flat resolution is done (cr-0158). The remaining two were split to #81
(an area appearing in both `coverage` and `gaps` — 16 files) and #82 (`level: e2e` conflating CI-run tests,
IDE-only `.http`, and env-gated live smokes). Refining them **together** surfaced
a single root cause: **`gaps` had drifted into an overloaded free-form per-area status field**, doing three
different jobs at once —

1. a **real gap** — the surface is not built;
2. a **deferred sub-slice** — the core is covered, a later feature is pending (`uc.memory.date` "the aggregated
   cross-chapter timeline is a later CR"; `uc.memory.place` "blind gazetteer");
3. a **build-note for a manual/live smoke** — the CI tests exist, the end-to-end check was a human clicking once
   (`uc.memory.gallery` "the live browser smoke … driven manually per the CR done-gate").

The 16 overlaps are the *symptom* of (2)+(3) sitting in `gaps`. The naive "forbid overlap" the finding first
proposed is a misdiagnosis — it would break legitimate partial coverage.

Two facts from the corpus + the real CI config decide the shape:

| Fact | Measured |
|---|---|
| CI actually runs | backend `gradlew check` (unit+integration), web `npm run check` (lint+typecheck+**unit**+build), the trust-chain validators, **and web's `*.e2e.test.ts`** via the `web-e2e` job (`scripts/run_web_e2e.py` — it brings up the bundled stack and gives each file its own database + a fresh `%e2e` backend boot) |
| CI does **not** run | `.http` (no `ijhttp`/`httpyac` in any workflow), env-gated backend smokes (`LiveRoundTrip*`, `BlobBatchE2E*`) |
| `http` as **sole** coverage area | **1 of 256** criteria (`uc.account.login/boundary-validation`) — the other 66 pair it with a backend SliceTest that proves the same behaviour in CI |

So **"CI-proven" is not a function of area alone**: an env-gated smoke inside an otherwise-CI area is not
proven, whatever colour its column is. **The unit is the test, not the surface.**

> **The obligation this table carries.** It once read *"web `npm run check` + `run_web_e2e.py` (unit+e2e)"* and
> the sentence above it began *"web e2e runs"*. Both were false when written: the orchestrator did not exist and
> its job was disabled. A table headed **Measured**, carrying this ADR's core decision, that nobody had run —
> and four web `e2e` coverage refs were riding on the false half. They were removed; each criterion they touched
> keeps a CI-run ref. The rule above was always right; the ADR simply had not applied it to its own surface.
>
> So the standing obligation, which outlives the incident: **a row moves into "CI actually runs" only after a run
> has executed it.** Not when the test is written, not when the job is enabled — those are intentions, and this
> is the one claim in the repo that has already been false once.
>
> It was honoured on the way back. The four refs returned only after a green `web-e2e` run whose log was read to
> confirm it had executed four suites rather than discovered none — `total: 4  passed: 4  failed: 0`. Reading that
> log is the step, not the green tick: "the job passed" and "the job ran nothing" render identically in the UI,
> which is why `scripts/validate_e2e_discovery.py` now guards the difference.

## Decision

**Two changes, no new schema. #82 is resolved by removing a surface, not by adding a marker.**

1. **`gaps` means exactly one thing:** *this surface has no coverage ref for this use-case (yet), and here is
   why that is acceptable.* Enforced: an area listed under `gaps` **must not** appear in any criterion's
   `coverage` for that use-case — overlap is now a hard validation error, which is the enforceable stale-gap
   guard #81 wanted. The two illegitimate uses leave `gaps`: **build-notes** (the surface is covered — the prose
   belongs to the CR record, not the coverage file) and **deferred sub-slices** (future scope, not untested
   current scope — they belong in the inbox / a future CR).

2. **`http` is not a coverage surface, and `http/` no longer exists.** It was removed from the area set and
   stripped from the coverage files; the one http-only criterion (`uc.account.login/boundary-validation`) gained
   a backend boundary test (malformed email / non-base64 signature / missing token → 400) so it stays
   CI-covered. `docs/ai/README.md`'s "`http/` is a real contract-level verification surface" stance and the
   `contracts → backend → http → web → …` phase order were rewritten to drop `http`.

   This ADR originally kept the `.http` files "in the repo as what they were built for — IntelliJ requests a
   developer fires by hand". **They have since been deleted** (2026-07-14): once they counted for nothing, the
   only thing keeping 2,448 lines alive was the hope someone would fire them by hand, and nobody did.
   **The consequence this ADR did not chase down:** another ADR named those same suites as the net that made
   re-implemented orchestration safe across clients — so demoting `http` quietly dismantled a guarantee a
   different decision was still promising, and this one never said so. **The reusable lesson: demoting a
   mechanism means finding every decision that leaned on it** — a grep for the mechanism's name, not just for
   the docs that describe it.

3. **No CI-gating marker (#82 needs none).** After (2), a `ci-surfaces.yaml` manifest / a per-ref `ci: false`
   flag / a filename convention would all be machinery to police a handful of criteria — not worth it. The
   rule carries itself: a ref only counts when a CI run executes it, and that is checked by the build, not by
   a field.

## Consequences

- **#81 gets an enforceable guard for free.** Overlap-forbidden catches a gap left stale after a surface is
  covered, with zero schema growth. The 16 overlaps are migrated as part of the follow-up CR (build-note and
  deferred prose relocated), leaving `gaps` meaning only "genuinely uncovered".
- **#82 dissolves without machinery** — the "Start light / pain before tool" trade: removing the one decorative
  non-CI surface beats a marker system for the handful of cases left. The merge of #81+#82 into one decision
  reflects that they were one problem (an overloaded `gaps`) seen from two angles.
- **Cost, paid honestly:** this reverses a *documented* stance (`http` as a verification surface); ~67 criteria
  drop their now-redundant `http` refs; one backend boundary test is added; `docs/ai/README.md` +
  `docs/coverage/README.md` are rewritten; the phase order becomes `contracts → backend → web`.
- **Rejected alternatives:**
  - *A CI-gating marker* (`ci-surfaces.yaml` / per-ref `ci: false` / filename convention) — machinery for a
    problem that removing `http` shrinks to a handful of criteria.
  - *Per-criterion `gaps`* — makes covered+gap an exact contradiction, but most gaps are genuinely use-case-wide
    ("the surface is not built at all" applies to every criterion), so per-criterion gaps are artificial and a heavy
    migration.
  - *Finer area keys* (`web-ui` vs `web-data`) — violates the documented "**stop at area × level**" rule
    and invents a taxonomy only to dodge the overlap check.
  - *Forbid all overlap naively* (the finding's first proposal) — breaks legitimate partial coverage; it is the
    reason this needed a model decision rather than a one-line validator tweak.
