---
id: cr-one-pipeline-per-commit
title: One pipeline per commit — every stage once, on a budget, the build beside the tests, runnable locally
status: in-progress
satisfies: []
adrs: ["0091", "0057", "0040", "0045"]
invariants:
  - "Self-hostable by default: the local runner needs nothing GitHub provides; a stage that only GitHub can run (a registry write, keyless signing) is listed as not run, never as green"
  - "No deprecated dependencies: every action stays pinned by commit SHA with its full version (validate_action_pins.py)"
  - "Nothing is weakened for speed: a stage over its budget is sharded or moved, never given lower parameters (ADR 0091 decision 1)"
---

## Goal

[ADR 0091](../docs/decisions/0091-one-pipeline-per-commit-a-release-promotes-a-tested-snapshot.md) decisions 1, 2
and 4, built: `ci.yml` and `container.yml` become **one workflow** in which the guards, the surface checks, the races,
the API end-to-end run and the image build **start together** (the build no longer waits for a second copy of the
tests); every job carries its stage's budget as `timeout-minutes`; `validate_guard_gaps.py` runs its cases in
parallel and, in CI, only when a guard changed (plus nightly); and **one command runs the pipeline locally** from the
workflow file itself, because the repository will have no remote until the owner judges veyq ready to publish, and
then it will be public (owner, 2026-10-16) — until that day the local run is the only run. Inbox #459.

Not here: the snapshot on every green push and the release as a promotion (inbox #460), the stack lanes
(`cr-stack-e2e`, #453, #461). The hand-dispatched publish keeps working as it does, now waiting on every stage.

## Context & constraints

- **No remote.** Every phase is proven locally; a statement about what CI does is a statement about a file
  (ADR 0040). The local runner of phase 5 is what makes that file runnable.
- **The workflow's file name is the signer's identity.** `site/src/content/docs/run/verify-a-release.md` pins
  `cosign verify` to `.github/workflows/container.yml@refs/heads/(main|release/…)`, and
  `validate_release_identity.py` holds those commands to the publishing workflow. No image has ever been published,
  so renaming costs documentation only — today, and never again cheaply after the first publish.
- **Decided at refine (owner, 2026-10-16): the one workflow is `ci.yml`**, the name a reader looks for, and it is the
  signer: `container.yml`'s jobs move into it, `container.yml` is deleted, and every `container.yml` reference in a
  guard, a Run page, an ADR and the inbox moves with it. The alternative — keep the file name `container.yml` for
  everything — would have saved the rename and left the pipeline under a name that says "image".
- **20 scripts read a workflow** (locator below), among them `validate_coverage.py` (which Gradle runs execute
  which backend refs, named `<file> › <job>`), `validate_release_identity.py` (registry writes only on dispatch,
  the publish's needs, the cosign identity), `validate_npm_installs_gated.py`, `validate_mutation_trigger_paths.py`
  and `validate_guard_gaps.py`, whose forgeries anchor on workflow text — so every phase that edits a workflow ends
  with `validate_guard_gaps.py --force` (its skip does not see an anchor move outside `scripts/`).
- `trust-chain.yml` stays its own workflow (ADR 0091 decision 2): it is where a docs-only commit is validated, since
  `ci.yml` ignores `docs/**`. The pipeline runs the guards too, so a snapshot (#460) can wait on them; the overlap is
  the cheap guards, about a minute and a half (97 s measured 2026-10-16).
- `validate_guard_gaps.py` measured 809–1073 s for its 79 cases on this machine under load, the rest of the glob
  97 s (2026-10-16). Its copies drive `fseventsd` and the virus scanner hard (a neighbour's run took the load average
  to ~190 on 2026-10-15), so its parallelism is capped, not `cpu_count()` unbounded.
- *Machine hints, for the CR's planned shape:* the local runner skips `uses:` steps and runs `run:` steps with the
  host's own Java, Node and Docker; a parallel wave of sessions makes every number here 3–10× slower — measure a phase
  on a quiet machine (`uptime` first) or say under which load it was measured.

### Inventory — the locators

| What | Locator |
|---|---|
| Scripts that read a workflow | `grep -ln "ci\.yml\|container\.yml\|trust-chain\.yml\|\.github/workflows" scripts/*.py` |
| Docs and pages that name a workflow file | `git grep -n "container\.yml\|ci\.yml" -- docs site/src infra charts '*.md'` |
| The cosign identities an operator is told to pin | `git grep -n "certificate-identity" -- site infra` |
| Jobs and their needs today | `python3 -c "import yaml;[print(f,j,v.get('needs')) for f in ['ci','container'] for j,v in yaml.safe_load(open(f'.github/workflows/{f}.yml'))['jobs'].items()]"` |
| What `container.yml`'s `test` repeats from `ci.yml` | its step names against `ci.yml`'s `backend` and `frontend` jobs (both lists printed by the line above with `steps`) |
| Forgeries that anchor on a workflow | `grep -n "workflows" scripts/validate_guard_gaps.py` |

## Phases

Done when, per phase: its local proof green and its log read; `for f in scripts/validate_*.py; do python3 "$f"; done`
green with `validate_guard_gaps.py --force` once at the end of the phase; the bites below made red once each and
restored by snapshot-and-restore.

- [x] 1 **Guard gaps in parallel.** The 79 cases run on a bounded pool (default `min(cpu_count, 6)`,
      `VEYQ_GUARD_GAPS_WORKERS` overrides; 1 is today's behaviour), each case on its own copy of the one snapshot; the
      un-forged verdict per guard is computed once, before the cases, in the same pool; the report lists failures in
      case order regardless of finishing order. Bites: a forgery whose documented verdict is flipped in `CASES` →
      red in parallel exactly as with one worker; the same full run with 1 and with N workers yields the same verdict
      set. Measured: the full `--force` run's wall time at 1 and at N workers, under a named load.
      **Built 2026-10-16:** a `ThreadPoolExecutor` (threads: the work is file copies and child processes, both
      outside the GIL, and one process keeps Ctrl-C in the main thread); the snapshot and every case copy live
      under one run directory, removed in `main`'s `finally` after the pool is shut down with `cancel_futures`.
      The un-forged verdicts are one pool pass over the 22 distinct target guards before any case is submitted.
      **Bites** (snapshot-and-restore of the guard file, restore confirmed by `cmp` against the snapshot and an
      unchanged `git status --porcelain`): case 35's verdict flipped to `True` and case 14's anchor
      (`TENANT_REPROJECT_CALL`) renamed, run together through a harness that calls `main()` and records every guard
      exit — at 1 and at 6 workers both exit 1 with byte-identical output naming exactly case 14 ("could not apply
      forgery") and case 35 ("should still pass … but it was rejected"), and the 100 recorded guard exits (22
      un-forged, 78 cases; case 14 never reached its guard) are identical. A worker that raises propagates and
      leaves no run directory; a SIGINT to the process group mid-run (6 copies live) leaves none either.
      **Measured** (14-core Mac, a neighbour's Docker-heavy `cr-stack-e2e` session running): the full `--force`
      run 334 s at 1 worker (load average 24 → 13) and 167 s at 6 (13 → 30); bitten, 350 s and 147 s. About 2×,
      not 6×: the copies are bound by the file system and its watchers. Both well inside the 10-min budget here;
      the 809–1073 s of the morning were sequential under a heavier load.
- [x] 2 **One workflow.** `ci.yml` (or the name confirmed above) holds every stage of ADR 0091 decision 1 that exists
      today: the guards; the surface checks — backend, crypto, web, **admin-web** and the **chart** check, which only
      `container.yml`'s `test` ran; the races; the API end-to-end run; the image build matrix, needing only the version
      job, not the tests; the disabled upgrade proof; the dispatch publish, which now needs **every** stage with
      `success`. `container.yml` is gone; its triggers' `paths-ignore` and its concurrency groups carry over. Every
      reader moves: the guards (release identity's publish rules and cosign identities, coverage's `<file> › <job>`
      names, the install gate), `verify-a-release.md` and `infra/README.md` § Cutting a release, ADR 0057, ADR 0064,
      ADR 0091, the Run pages. Bites: drop one stage from the publish's `needs` → `validate_release_identity.py` red
      (that assertion is added here); a registry write on a push → red (exists); a backend ref whose Gradle run is
      no longer declared → `validate_coverage.py` red (exists).
      **Built 2026-10-16:** `ci.yml` jobs `guards` (the glob minus `validate_guard_gaps.py`, which stays in
      `trust-chain.yml` until phase 4), `backend`, `backend-races`, `frontend` (crypto, web, admin-web; the one
      registry-asking install), `chart` (pinned Helm, `chart_meta_check.py`, the upgrade check and its self-test),
      `web-e2e`, `version`, `build` (`needs: version` only), `upgrade-proof` (`if: false`, no `needs`) and `publish`
      (needs all nine, `always()` plus one `== 'success'` term per need, the upgrade proof's `skipped` tolerated).
      Triggers `main` + `release/**` with `container.yml`'s `paths-ignore`; concurrency groups `veyq-publish` /
      `veyq-ci-<ref>`. `validate_release_identity.py` assertion 9 (new): every other job of the file is in the
      publish's `needs`, and a status-function `if:` names each need's `success`. `validate_npm_installs_gated.py`'s
      premise moved from "the gate before the build" to "the gate before the publish" (a job building an image runs
      the gate, needs it, or is needed only by jobs that do) — consequence, stated in ADR 0091: on a dispatch the
      `build-<sha>-<arch>` staging tags are pushed before the tests finish, unsigned and unnamed until `publish`.
      Signer identity on Verify a release → `ci.yml`. **Bites** (snapshot-and-restore of `ci.yml`, restore confirmed
      by `cmp` and an unchanged `git status --porcelain`): `chart` dropped from `publish.needs` → release identity
      red (assertion 9); its `success` term dropped from the `if:` → red; `frontend` dropped from `needs` →
      `validate_npm_installs_gated.py` red (the registry-asking install no longer before the publish); the build's
      registry login ungated → release identity red (assertion 3); the backend job's `check` turned into `assemble`
      → `validate_coverage.py` red (no run executes the 1249 backend refs). Glob green, `validate_guard_gaps.py
      --force` green (79 cases, 6 workers, 302 s under load 30–44); `npm run check` in `site/` green.
      **Run locally, in the workflow's order** (14-core Mac, a neighbour's Docker-heavy session running, load 24–38):
      `frontend` — the gate with its registry half (64 skipped locked packages asked), crypto 449, web 4055 (32
      skipped), admin-web 252 tests green, 4 min; `chart` — all three scripts green against the local Helm 4.0.1 (the
      pinned-tarball step is Linux-only and was not run); `version` — the step's script with a push's inputs →
      `0.481-SNAPSHOT`, kind `none`; `backend` — `./gradlew --no-daemon check` green, 8 m 15 s; `backend-races` —
      56 filters in 3 projects on the pinned MariaDB, green, 7.5 min; `build` — `docker build` of `runtime-jvm` and
      `runtime-nginx` on arm64 (the host's matrix leg), green, both from BuildKit's cache (15 s and 1 s, the inputs
      unchanged by this phase), the app image carrying `VEYQ_VERSION` and the recipe under `/opt/veyq/deploy`.
      **Not run:** `guards` as a job (its loop is the glob run above); `web-e2e` (`run_web_e2e.py` recreates the dev
      compose stack's services from a worktree unless pinned, and a parallel session runs Docker-heavy e2e on it);
      the amd64 image leg and Buildx's provenance/SBOM (no `docker/build-push-action` locally); `upgrade-proof`
      (disabled); `publish` (registry writes and keyless signing exist only in GitHub). No CI run exists.
- [ ] 3 **Budgets.** Every job of every workflow declares `timeout-minutes`, the values ADR 0091's table gives; a new
      guard holds the presence (every job, every workflow) and names the ADR as the values' home — it does not keep a
      second copy of the table. Bites: a job without `timeout-minutes` → red; a workflow added without any → red.
- [ ] 4 **Guard gaps in CI.** `trust-chain.yml`'s loop runs every guard but `validate_guard_gaps.py`; a second job
      runs that one when `scripts/**` or `.claude/skills/trust-chain/**` change (`dorny/paths-filter`, pinned as
      `codeql.yml` pins it) and on a nightly `schedule` on `main`, always in full there. `changes/README.md`
      § The trust-chain guards stay green and `docs/ai/README.md` say what runs where. Bite: a path filter that misses
      `scripts/` (the forged filter) → the guard of phase 3, or a new assertion beside it, red.
- [ ] 5 **The pipeline, locally.** `scripts/run_pipeline.py` reads `ci.yml`, resolves each job's `needs`, runs the
      `run:` steps of every job the host can run (its own Java, Node, Docker; the host architecture of a matrix),
      independent jobs in parallel, each under its `timeout-minutes`, and prints per job: ran / green / red / **not
      run, and why** (a `uses:`-only step it cannot reproduce, a registry write, keyless signing). It exits non-zero on
      any red, and never reports a job it did not run as green. No second list of stages exists: the workflow is the
      list. `nektos/act` was considered and is not used: it would add a runtime that re-implements GitHub's runner,
      and its arm64, cache and registry gaps would surface as its failures rather than ours. Bites: a job that fails →
      red naming it; a job over its budget → red; a job made of `uses:` steps only → "not run", the summary not green.
- [ ] 6 **The page says what runs where.** [How veyq is tested](../site/src/content/docs/security/how-veyq-is-tested.md)
      (ADR 0091 decision 8): the stages now run in one pipeline and locally by one command; guard gaps' CI trigger;
      measured wall times where they exist, estimates marked; still nothing has run in CI.

## Definition of done

`python3 scripts/run_pipeline.py` on a quiet machine runs every stage the host can run, green, with its per-job wall
times recorded in this CR's retrospective and in ADR 0091 § Consequences (replacing the estimate there); the dispatch
publish waits on every stage; no workflow job lacks a budget; the trust-chain is green with `--force`; the site check
(`npm run check` in `site/`) is green. What only a first CI run can show — the critical path in CI, arm64, the path
filter on a real push — is listed in ADR 0091 as awaiting the remote.

## Retrospective

(filled at close)
