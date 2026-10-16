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
- [x] 3 **Budgets.** Every job of every workflow declares `timeout-minutes`, the values ADR 0091's table gives; a new
      guard holds the presence (every job, every workflow) and names the ADR as the values' home — it does not keep a
      second copy of the table. Bites: a job without `timeout-minutes` → red; a workflow added without any → red.
      **Built 2026-10-16:** `scripts/validate_pipeline_shape.py` (named for the shape ADR 0091 gives the pipeline, so
      phase 4's assertion joins it): every job of every `.github/workflows/*.y(a)ml` in the directory — globbed, so an
      untracked new file counts — declares a positive whole-number `timeout-minutes`; a reusable-workflow call is
      exempt (GitHub refuses the key there). Run on the untouched tree first: red on all 17 jobs of the six workflows.
      The table gave no value for the jobs that are no stage, and mutation's row said "as today" (that is, GitHub's
      360-minute default), so ADR 0091 decision 1 now carries them, marked as estimates: mutation 30 per gate, the
      version 5, the publish 15, the upgrade proof 15, a path filter 5, CodeQL's analysis 30, the site's check 10 and
      deploy 5. `trust-chain.yml`'s one job carries 13 (guards 3 + guard gaps 10) until phase 4 splits it. **Bites**
      (snapshot-and-restore of `ci.yml`, restore confirmed by byte comparison and an unchanged `git status
      --porcelain`): `chart`'s `timeout-minutes` removed → red naming `ci.yml › chart`; a new `bite-new.yml` with two
      jobs and no budget → red naming both. Glob green; `validate_guard_gaps.py --force` green (79 cases, 6 workers,
      166 s, load 30 → 24). Re-proven at close on the tree merged with `main`: both bites red again, every one of the 19
      jobs carries the table's value, glob green (57 guards), `--force` green (79 cases, 218 s, load 18 → 35).
- [x] 4 **Guard gaps in CI.** `trust-chain.yml`'s loop runs every guard but `validate_guard_gaps.py`; a second job
      runs that one when `scripts/**` or `.claude/skills/trust-chain/**` change (`dorny/paths-filter`, pinned as
      `codeql.yml` pins it) and on a nightly `schedule` on `main`, always in full there. `changes/README.md`
      § The trust-chain guards stay green and `docs/ai/README.md` say what runs where. Bite: a path filter that misses
      `scripts/` (the forged filter) → the guard of phase 3, or a new assertion beside it, red.
      **Built 2026-10-16:** `trust-chain.yml` has three jobs — `trust-chain` (the glob minus `validate_guard_gaps.py`,
      3 min), `guard-gap-inputs` (the pinned `dorny/paths-filter` on `scripts/**`, `.claude/skills/trust-chain/**` and
      the workflow itself, only on a push or pull request, 5 min) and `guard-gaps` (`--force`, 10 min) when the filter
      says `true`, on a dispatch and on the nightly `schedule` (`17 3 * * *`; a schedule runs on the default branch
      only). `validate_pipeline_shape.py` assertion 2: a glob loop names `validate_guard_gaps.py` (its exclusion); some
      job runs it alone; that job's workflow has a `schedule`; its `if:` admits the schedule and a
      `needs.<job>.outputs.<filter> == 'true'`; that job's one paths-filter step exports the output and lists every
      `SKIP_INPUTS` entry of `validate_guard_gaps.py` as `<input>/**` — read from that constant, so the local skip and
      the CI filter watch one set. Docs moved: `changes/README.md`, `docs/ai/README.md`, `docs/README.md`, the
      trust-chain skill's map (its line numbers re-measured), `validate_guard_gaps.py`'s docstring (the CI filter has
      the skip's blind spot: an anchor outside the inputs shows at the next nightly run), ADR 0091 § Consequences, and
      the Security page *Verify it yourself*. **Bites** (snapshot-and-restore of `trust-chain.yml`, restore confirmed by
      byte comparison and an unchanged `git status --porcelain`): the pre-phase `trust-chain.yml` (live) → red twice
      (the loop runs the guard gaps; no job runs them alone); the forged filter without `scripts/**` → red naming
      `guard-gap-inputs`' filter; the `schedule` removed → red. Glob green; `validate_guard_gaps.py --force` green (79
      cases, 6 workers, 431 s under a load of 66 → 99); `npm run check` in `site/` green. Re-proven at close: the three
      bites red again (the pre-phase file red twice, the filter without `scripts/**`, the schedule removed), each
      restored by bytes, `git status --porcelain` unchanged.
- [x] 5 **The pipeline, locally.** `scripts/run_pipeline.py` reads `ci.yml`, resolves each job's `needs`, runs the
      `run:` steps of every job the host can run (its own Java, Node, Docker; the host architecture of a matrix),
      independent jobs in parallel, each under its `timeout-minutes`, and prints per job: ran / green / red / **not
      run, and why** (a `uses:`-only step it cannot reproduce, a registry write, keyless signing). It exits non-zero on
      any red, and never reports a job it did not run as green. No second list of stages exists: the workflow is the
      list. `nektos/act` was considered and is not used: it would add a runtime that re-implements GitHub's runner,
      and its arm64, cache and registry gaps would surface as its failures rather than ours. Bites: a job that fails →
      red naming it; a job over its budget → red; a job made of `uses:` steps only → "not run", the summary not green.
      **Built 2026-10-16:** `scripts/run_pipeline.py` reads the workflow from the commit it runs (`--rev`, default
      `HEAD`; `--workflow <file>` reads a file from disk), expands matrices (GitHub's include/exclude rules) and keeps
      the host's architecture, evaluates the GitHub expressions in `if:`, `env:`, `run:` and job outputs (three-valued:
      a secret, or a need that did not run here, is unknown, never true), and runs each job in its own `git clone
      --shared` of the commit — two jobs' `npm ci` or Gradle builds in one tree would trample each other — under its
      `timeout-minutes`, up to `--max-parallel` (4) at once, `CI=true`, `GITHUB_OUTPUT`/`ENV`/`PATH` and `RUNNER_TEMP`
      as on a runner. Verdicts per job: green / green, partial (the steps it could not run named) / red / skipped (the
      workflow's own `if:`, as in CI) / not run, with the reason; exit 1 on red, 3 when nothing is red but something
      did not run, 0 only when all ran green. `--dry-run`, `--job`, `--skip-job`, `--keep`; logs and `summary.json` in
      `.pipeline-out/<run>/` (gitignored). Four `uses:` count as host-provided (checkout, setup-java — `java_home -v`
      on macOS —, setup-node, setup-buildx) and two as inert (upload-artifact, cache); every other is not run, named.
      Decisions taken here, beyond the CR's text: (1) `ci.yml` carries the host's **stand-ins** where a stage's work sits
      in a `uses:` step, guarded by `runner.environment != 'github-hosted'` (GitHub's context; this runner reports
      `local`): `build` runs `docker build` with the matrix's Dockerfile, target, platform and build arguments, and
      `chart` checks the host's Helm is `HELM_VERSION` where the pinned Linux tarball cannot run — so the image build
      is a stage the local run executes, and what it does is read in the workflow, not in the runner; (2) every job gets
      `VEYQ_INFRA_DIR` = the main checkout's `infra/` (`run_web_e2e.py` from a clone would otherwise recreate the
      developer's compose project onto the clone's mounts); (3) a step outrunning its budget is stopped SIGINT → SIGTERM
      → SIGKILL, so its own `finally` runs — the first run sent SIGTERM first and left the races runner's MariaDB
      container running (stopped by hand, by name). **Bites** (re-run by the session that closed the phase, forged
      workflows in the scratchpad, `--workflow`): a job `exit 7` → `RED — fails`, exit 1, its green sibling still
      green; a job with `timeout-minutes: 0.1` whose Python step sleeps 60 s → red "over its budget", exit 1 after 7 s,
      no sleeper left, and the step's `finally` ran (its marker file written); a job of `checkout` +
      `docker/build-push-action` only → "not run: no step it can run here", `NOT GREEN`, exit 3; control: one green
      job → `GREEN`, exit 0. The kill order itself bitten (snapshot-and-restore of `run_pipeline.py`, restore confirmed
      by bytes and an unchanged `git status --porcelain`): with SIGTERM first the same over-budget step is red and its
      `finally` does **not** run. **The local run** (commit `04c9f6d1`, `--skip-job web-e2e`, 14-core arm64 Mac, four
      jobs at once, load average 10 → 18, no Docker project running but the dev stack's own containers): guards green
      1.5 min (57 guards); backend green, partial 8.1 (`BUILD SUCCESSFUL`, 64 tasks; the wrapper validation not run);
      backend-races green, partial 9.5 of its 10 (56 filters in 3 projects on the pinned MariaDB, its container gone
      afterwards); frontend green 5.0 (crypto 452, web 4055 + 32 skipped, admin-web 252 tests; host Node 23, the
      workflow asks 22); chart green 0.4 (the host Helm check, both chart scripts); version green 0.1; build arm64
      green, partial — app 0.3, admin 0.1, both from BuildKit's cache (the image inputs unchanged since an earlier run
      built the app image in 4.6 min), the metadata and build-push actions not run. Verdict `NOT GREEN`, exit 3, as
      designed: nothing red, but jobs not run whole or in part. **Not run:** `web-e2e` (left out: it drives the
      developer's one compose stack, which this phase may not touch), both amd64 build legs (arm64 host);
      `upgrade-proof` and `publish` skipped by their own `if:` on a push, as in CI. The earlier run (commit `22f3c731`,
      load 13 → 35) had the races **red, over their 10-minute budget** beside the backend's Gradle run, and green in 7.0
      alone: locally the races share the machine with `./gradlew check`, in CI they get a runner of their own, so 9.5
      of 10 here says more about local parallelism than about the budget. No CI run exists.
      **`web-e2e` under the pipeline, at close** (2026-10-16/17, measured by the coordinating session: `python3
      scripts/run_pipeline.py --job web-e2e` from a worktree, `VEYQ_INFRA_DIR` pinned to the main checkout's `infra/`,
      the owner's dev stack untouched — its containers' uptime unchanged; logs in that worktree's `.pipeline-out/`, not
      in git). First run, commit `1cf88638` (its `summary.json`): **red after 1.45 min** in the job's first check step,
      *Backup tool self-test*: `instance_backup_selftest.py`'s seeded-table case named `veyq_release_check`, which a
      migration of that day seeds, so every restore onto a fresh install was refused. Fixed in `23177221`
      (`instance_backup.py`'s `NOT_DATA`; `backend/CLAUDE.md` carries the rule). Second run, commit `23177221`:
      **green, partial, 13.8 min of its 15** — fast-jar, install, the three tool self-tests (backup, `veyqctl`, upgrade
      proof) and `[e2e] total: 27 passed: 27 failed: 0 run-level sweeps failed: 0`; the one step not run is the
      Gradle wrapper validation (a `uses:` action). Load average ~15–19 throughout (other agents building), so 13.8
      is a loaded figure, and the 15-minute budget is tight under that load; the direct run of 2026-10-15 took 9.2
      for 26 suites. The first CI run replaces it.
- [x] 6 **The page says what runs where.** [How veyq is tested](../site/src/content/docs/security/how-veyq-is-tested.md)
      (ADR 0091 decision 8): the stages now run in one pipeline and locally by one command; guard gaps' CI trigger;
      measured wall times where they exist, estimates marked; still nothing has run in CI.
      **Built 2026-10-16:** the stage table keeps *what it proves / cannot see* and says only built or designed (the
      image build gains its row, the surface checks name the chart check); a new section *Where and when each stage
      runs* gives per stage the `workflow › job` and its trigger, the budget and the local wall time — every CI cell
      read from `.github/workflows/*.yml` at `a9afc0d4` (the locator: the *Jobs and their needs* line above, over all
      six files, with `on:`, `if:` and `timeout-minutes`), every local number taken from this CR's phase records, ADR
      0091 § Context and § Consequences and lane L1's 75–90 s, the estimates marked (mutation's and the upgrade proof's
      budgets, the 20–25 min critical path). *One command runs the pipeline locally* says what `run_pipeline.py` does,
      what its one recorded full run (`04c9f6d1`) did not run and why, and that it cannot run `stack` on any one host
      (the job waits for the build's other-architecture leg; `run_pipeline.py`'s docstring). *How a release will be
      gated* now describes the dispatch as built — staging tags, L1 on those digests, the publish on every job's
      success, signing, and for a release the tag and the immutable GitHub Release (cr-veyqctl phase 5) — as a file
      that has never run. *Re-run it* names `run_pipeline.py` and `validate_guard_gaps.py --force`. The L1 row's
      status facts moved into the new table unchanged (its pipeline job, 75–90 s, no CI run). `npm run check` in
      `site/` green; the glob green.
      **The close (2026-10-17):** `web-e2e` ran under `run_pipeline.py` (phase 5's record), from a worktree with the
      infra directory pinned rather than from the main checkout; its wall time is in ADR 0091 § Consequences and the
      page's *Local run* cell for the API end-to-end row.

## Definition of done

`python3 scripts/run_pipeline.py` on a quiet machine runs every stage the host can run, green, with its per-job wall
times recorded in this CR's retrospective and in ADR 0091 § Consequences (replacing the estimate there); the dispatch
publish waits on every stage; no workflow job lacks a budget; the trust-chain is green with `--force`; the site check
(`npm run check` in `site/`) is green. What only a first CI run can show — the critical path in CI, arm64, the path
filter on a real push — is listed in ADR 0091 as awaiting the remote.

## Retrospective

**Done against the Definition of done, measured, with one deviation named.** Every stage the host can run has run
green under `run_pipeline.py` — but in two runs of two commits, not one run of one: the full run at `04c9f6d1`
without `web-e2e` (guards 1.5 min, backend 8.1, races 9.5, frontend 5.0, chart 0.4, version 0.1, the arm64 build
from cache) and `web-e2e` alone at `23177221` (13.8). Neither machine was quiet (load 10 → 18 and ~15–19). The DoD's
"replacing the estimate" in ADR 0091 does not hold and was never going to: a local wall time is not a CI runner's,
so the 20–25 min critical path stays an estimate until the first CI run, and the ADR says so. The publish waits on
every stage (`validate_release_identity.py` assertion 9); no job lacks a budget (`validate_pipeline_shape.py`); the
guard gaps green with `--force` at each phase; the site check green.

**The pipeline's first `web-e2e` run found a real defect, and that is the CR's best result.** A migration of
2026-10-16 seeded `veyq_release_check`, so every restore onto a fresh install was refused — the second seeding
migration that day to do so. Both migrations had landed past `./gradlew check` and the guard glob, which cannot see
it: the one check that reads the seeded tables, `instance_backup_selftest.py`, runs only in `ci.yml`'s `web-e2e`
job, and neither landing ran that job. With no remote, a job that only CI runs is a job nobody runs.
Harvested: the specific rule (run that self-test for a seeding migration) to `backend/CLAUDE.md` with the fix; the
general one — a done-when built from the surface checks and the glob does not cover `web-e2e`'s tool self-tests — to
`changes/README.md` beside the "no remote" gate.

**Budgets under local load are near their edge.** `web-e2e` 13.8 of 15 and the races 9.5 of 10, both while other
sessions built. Decision 1 forbids lowering a stage's parameters to fit; whether a budget is too tight is a CI
runner's question, recorded in ADR 0091 as awaiting the first run rather than answered here by raising it.

**Process.** The close's one live step needed the developer's compose stack and so waited on a quiet window that a
parallel wave never offered; it ran under load instead, from a worktree, safe only because phase 5 had pinned
`VEYQ_INFRA_DIR` for every job — the CR's "run it from the main checkout" was stricter than the runner required. A CR
whose last proof shares a host resource with every other session names who runs it and when at refine (§ *Split an
expensive live proof*), not at close.
