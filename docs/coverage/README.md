# Acceptance coverage

The machine-checked end of the trust chain: **feature → use-case → acceptance criteria → tests (or
explicit gaps)**. One coverage file per use-case, mirroring the feature tree:

```
docs/coverage/features/<feature>/use-cases/uc.<feature>.<goal>.yaml
```

Each file lists acceptance criteria; every criterion's tests are keyed by **area** (which surface) then
**level** (which kind of test). Every use-case **owes** `backend` and `web`: each either carries a coverage
ref or is declared under `gaps` with a reason — never left silent. `crypto`, `admin-web` and `stack` are opt-in.

> **A surface that no CI job runs makes no coverage claim** ([ADR 0040](../decisions/0040-coverage-gaps-and-http-not-a-surface.md)).
> The surfaces are **`backend`**, **`web`**, **`crypto`** (`packages/crypto/`), **`admin-web`** and **`stack`**
> (`tests/e2e/`, lane L1's browser suite against the release candidate, [ADR 0090](../decisions/0090-end-to-end-tests-boot-the-release-candidate.md)),
> each run by a CI job. `http/` was hand-fired IDE tooling and has been deleted — real
> requests that proved nothing, because nobody fired them. A surface enters this model when its CI job is
> green, not when its code exists — and "green" means a run executed it, not that a workflow declares it. A
> surface outside `ADMITTED` in `scripts/validate_coverage.py` has a job no run has executed, so its refs are
> rejected and its suites wait under `gaps`, named (ADR 0040's note on which rows a run has admitted).

`scripts/validate_coverage.py` enforces the chain (run it after changing a use-case, a coverage file,
or the tests they point to).

## Dimensions

**Area** — *where* the test runs (the surface): `backend` · `web` · `crypto` · `admin-web` · `stack`.

**Level** — *what kind* of test it is. Classify by the **scope of behaviour under test**, not by the
test harness:

| Level | Scope under test |
| --- | --- |
| `unit` | a single component in isolation (even if it uses the DI container to obtain it) |
| `integration` | several components together across an internal boundary, in-process (e.g. HTTP resource → handler → DB) |
| `e2e` | the system through its external interface against a running instance (e.g. a browser, or a web suite against a live backend) |

> Examples: a Quarkus `…SliceTest` (RestAssured → resource → handler → H2) is **integration**; a focused
> deserialization test is **unit**; a web suite run against a live backend (`run_web_e2e.py`) is **e2e**.

## Shape

```yaml
useCase: uc.<feature>.<goal>
feature: <feature>
title: <title>
acceptanceCriteria:
  - id: <kebab-id>
    text: <one sentence>
    coverage:
      <area>:
        <level>: [<ref>, ...]   # backend ref: "ClassName.testMethod"; web ref: a path under web/
    gaps:                       # optional: where this criterion's refs on a surface it covers stop short
      - surface: <area>         # must carry a ref on THIS criterion
        reason: <text>          # the part of the sentence those refs do not prove, and why
gaps:
  <area>: <reason>              # a surface with NO coverage ref for this use-case (never also covered)
```

## Rules

- **Every acceptance criterion needs at least one test** (any area/level). This is enforced.
- **Level is descriptive, not enforced.** It records *what kind* of test covers a criterion so gaps are
  visible (e.g. "happy path has integration but no e2e yet") — the validator does **not** require any
  particular level, because coverage is risk-based, not box-ticking.
- **Areas and levels come from the fixed sets above**; unknown names fail validation (catches typos).
- **References must resolve:** a `backend` `ClassName.method` ref resolves against *that* test class (a file
  whose stem is `ClassName`, containing `method`); a `web` ref's file must exist, and so must a `crypto` or
  `admin-web` one under that package's root. A `stack` ref is a file under `tests/e2e/` (`*.spec.ts`, or the setup
  project's `*.setup.ts`) one of whose tests carries the criterion's tag, `@<use-case>:<criterion-id>` — every lane
  L1 test carries one, every tag names a criterion, and a use-case a tag names says something on `stack`
  (`scripts/validate_stack_criterion_tags.py`).
- **A backend ref must name a test a CI run executes** ([ADR 0040](../decisions/0040-coverage-gaps-and-http-not-a-surface.md)).
  A test behind a JUnit gate (`@EnabledIfEnvironmentVariable`, `@Disabled`, `@EnabledOnOs`, …) counts only if
  a Gradle test run declared in `.github/workflows/` satisfies that gate; an opt-in test like
  `BlobBatchE2ETest` (`VEYQ_S3_E2E=1`, set by no workflow) is named in a criterion `gaps` entry instead.
  `validate_coverage.py`'s docstring says which gates it decides and which fail closed.
- **A criterion's `gaps` say where its refs stop short** — a list of `{surface, reason}` whose surface
  **does** carry a ref on that criterion: the refs are real and CI-run, and the reason names the part of the
  sentence they do not prove (a real store the hermetic test cannot reach, a deadlock H2 cannot produce). It is
  the mirror of the use-case `gaps` below, which name a surface with **no** ref; removing a criterion's last ref
  on a surface therefore turns its note red rather than leaving it to describe nothing.
- **Gaps are explicit *and* mean uncovered:** an owed surface (`backend`, `web`) with no coverage ref is
  listed under `gaps` with a reason, and silence fails validation; a surface that **is** covered may not also
  be a gap — that overlap is a stale gap (a build-note or deferred-slice left behind) and fails validation
  ([ADR 0040](../decisions/0040-coverage-gaps-and-http-not-a-surface.md)).
- **Only `backend` and `web` are owed** — the two surfaces every use-case crosses. `crypto` is a library
  proven beneath web, and `admin-web` is the Server-Owner surface only admin use-cases touch. A ref or a gap
  on either is checked like any other, but silence is legal: owing them would demand a "not applicable" gap
  on almost every file, burying the few that say something. "No part here" is a legitimate reason, and still
  a statement: it is how a Server-Owner use-case says its client lives on `admin-web`. And writing it is where
  a hidden surface shows up — `uc.admin.create-tenant` was silent on web, yet web carries its invite acceptance.
- **Stop at area × level.** Do not add finer per-test metadata (individual assertions, requirement ids) —
  classification noise without a decision payoff.
- **One shape is deliberately not expressible: "built on every surface, but one criterion no surface can
  reach."** The two rules above interlock — every criterion needs a ref *unless* the file is `status:
  planned`, `planned` needs at least one gap, and a gap may only name an **uncovered** surface. So a use-case
  whose other criteria are covered on both `backend` and `web` has no legal home for a criterion whose
  observable is a Python operator tool, a workflow, or anything else that is not a coverage surface
  ([ADR 0040](../decisions/0040-coverage-gaps-and-http-not-a-surface.md)). The resolution is `status:
  planned` with the gaps carrying the reason *and naming the suites*, so the later flip is a mechanical edit
  — which is what `uc.admin.back-up-and-restore-instance` and `uc.admin.identify-instance-release` both do.
  **Plan for it at refine time:** a CR phase that says "replace the gaps with refs" is unbuildable the moment
  one of that use-case's criteria is out of every surface's reach, and the CR that says it will discover this
  at its last phase.
