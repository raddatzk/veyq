# ADR 0040 — Coverage counts only what a CI run has executed; `gaps` name the surfaces with none

- Status: Accepted
- Built: yes
- Date: 2026-07-13
- Model: [How veyq is tested](../../site/src/content/docs/security/how-veyq-is-tested.md)

## Context

The acceptance-coverage trust chain (`feature → use-case → criteria → tests` — see
[docs/coverage/README.md](../coverage/README.md)) records, per acceptance criterion, a `coverage` map keyed by
**area** then **level** (its § Dimensions), plus a per-use-case `gaps` map (`area: reason`) for surfaces not
covered. `scripts/validate_coverage.py` enforces that every criterion has ≥1 test and every ref resolves.

Two defects shared one root cause. **`gaps` had drifted into an overloaded free-form status field**, doing three
jobs at once: a **real gap** (the surface is not built), a **deferred sub-slice** (the core is covered, a later
feature is pending) and a **build-note for a manual smoke** (the CI tests exist, the end-to-end check was a human
clicking once) — so an area could sit in both `coverage` and `gaps` (16 files did). And **`e2e` conflated three
things**: tests CI runs, IntelliJ `.http` requests fired by hand, and env-gated live smokes no workflow enables.

So **"CI-proven" is not a function of area alone**: an env-gated smoke inside an otherwise-CI area is not proven,
whatever colour its column is. **The unit is the test, not the surface** — and a claim about a test is only as good
as the run that executed it. This ADR's own table of what CI runs once read "web e2e runs" while the orchestrator
did not exist and its job was disabled, with four coverage refs riding on the false half.

## Decision

**Three rules, no new schema.**

1. **`gaps` means exactly one thing:** *this surface has no coverage ref for this use-case (yet), and here is
   why that is acceptable.* An area listed under `gaps` **must not** appear in any criterion's `coverage` for that
   use-case — overlap is a hard validation error, which catches a gap left stale after the surface is covered.
   Build-notes (the surface is covered; the prose is the CR's) and deferred sub-slices (future scope, not untested
   current scope; they belong in the inbox) never go into `gaps`.

2. **`http` is not a coverage surface.** Requests a developer fires by hand prove nothing a run executed; the area
   and the `.http` files are gone, and no CR phase is an `http` one (the phase order is root
   [CLAUDE.md](../../CLAUDE.md)'s and [`changes/README.md`](../../changes/README.md)'s). The lesson that outlives
   them: **demoting a mechanism means finding every decision that leaned on it** — another ADR still named those
   suites as the net under re-implemented orchestration, and this one never said so. Grep for the mechanism's name,
   not only for the docs that describe it.

3. **A ref counts only when a CI run executes it — no marker, no manifest.** A `ci-surfaces.yaml` manifest, a
   per-ref `ci: false` flag or a filename convention would be machinery for a rule the tree already carries:

   - **A row moves into "CI actually runs" only after a run has executed it** — not when the test is written, not
     when the job is enabled. Reading the run's log is the step, not the green tick: "the job passed" and "the job
     ran nothing" render identically, which `scripts/validate_e2e_discovery.py` guards.
   - **A gated test counts only where a workflow sets its gate.** Gradle skips a test gated on an unset variable
     cleanly and says nothing, so the gate is read from the test itself and held against the runs the workflows
     declare ([the coverage README](../coverage/README.md) § Rules). What only a run can know (a runtime `assume`,
     a variable a script exports) stays out of the guard's reach, and its docstring says so.
   - **What a row is, and which rows a run has admitted.** A row is a *job* — the runner that executes one
     surface's tests — never a single ref. Once a run has executed a job, a new test inside it counts as soon as a
     workflow declares that run. A job no run has executed admits nothing, however green its suites are locally:
     until its first log is read, a job that runs everything and a job that runs nothing are indistinguishable.
     `validate_coverage.py` carries `ADMITTED`, one line per surface a run has admitted, and rejects a ref on any
     other; a surface not admitted keeps its suites under the use-case `gaps`, named, until the first run whose log
     shows them executed. That edit is the admission, and nothing checks it — it records the one fact the tree
     cannot derive: that someone read a log. The set in the guard is the live list. Since the repository has had no
     remote, no run has admitted a job born after its import.

## Consequences

- **The stale gap has an enforceable guard for free.** Overlap-forbidden catches it with zero schema growth.
- **Each ambiguity dissolves without machinery** — removing the one decorative non-CI surface, and reading the
  gate and the admitted jobs from what the tree already says, beat a marker system.
- **Cost, paid honestly:** reversing a documented stance (`http` as a verification surface) dropped ~67 redundant
  `http` refs and added one backend boundary test for the criterion only `http` had covered.
- **A suite that exists can still claim nothing** — a suite on a job no run has executed waits under `gaps` until a
  read log admits that job. That is the rule working, not a gap in it.
- **Rejected alternatives:**
  - *A CI-gating marker* (`ci-surfaces.yaml` / per-ref `ci: false` / filename convention) — machinery for what the
    gate on the test and the runs in `.github/` already state; `ADMITTED` is not that manifest: one line per
    surface, no field on any ref.
  - *Per-criterion `gaps`* — makes covered+gap an exact contradiction, but most gaps are use-case-wide ("the
    surface is not built at all" applies to every criterion), so per-criterion gaps are artificial and a heavy
    migration. *(What a criterion carries under `gaps` is a different thing and does not reopen this: a note that a
    surface **covered** on that criterion has refs stopping short of its sentence — see
    [the coverage README](../coverage/README.md) § Rules. The use-case `gaps` stay the only home for an uncovered
    surface.)*
  - *Finer area keys* (`web-ui` vs `web-data`) — violates the documented "**stop at area × level**" rule and
    invents a taxonomy only to dodge the overlap check.
  - *Forbid all overlap naively* — breaks legitimate partial coverage; it is why this needed a model decision
    rather than a one-line validator tweak.

Explained for security researchers on the docs site: [Verify it yourself](../../site/src/content/docs/security/verify-it-yourself.md),
and the rule's place among every test stage: [How veyq is tested](../../site/src/content/docs/security/how-veyq-is-tested.md).
