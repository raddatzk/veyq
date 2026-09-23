# Acceptance coverage

The machine-checked end of the trust chain: **feature → use-case → acceptance criteria → tests (or
explicit gaps)**. One coverage file per use-case, mirroring the feature tree:

```
docs/coverage/features/<feature>/use-cases/uc.<feature>.<goal>.yaml
```

Each file lists acceptance criteria; every criterion's tests are keyed by **area** (which surface) then
**level** (which kind of test). Surfaces with **no** coverage ref for the use-case are declared under
`gaps` with a reason — never left silent.

> **A surface that no CI job runs makes no coverage claim** ([ADR 0040](../decisions/0040-coverage-gaps-and-http-not-a-surface.md)).
> The surfaces are **`backend` and `web`**. `http/` was hand-fired IDE tooling and has been deleted — real
> requests that proved nothing, because nobody fired them. A surface enters this model when its CI job is
> green, not when its code exists.

`scripts/validate_coverage.py` enforces the chain (run it after changing a use-case, a coverage file,
or the tests they point to).

## Dimensions

**Area** — *where* the test runs (the surface): `backend` · `web`.

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
  whose stem is `ClassName`, containing `method`); a `web` ref's file must exist.
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
- **Gaps are explicit *and* mean uncovered:** a surface with no coverage ref is listed under `gaps` with a
  reason (silent omission is not allowed); a surface that **is** covered may not also be a gap — that overlap
  is a stale gap (a build-note or deferred-slice left behind) and fails validation ([ADR 0040](../decisions/0040-coverage-gaps-and-http-not-a-surface.md)).
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
