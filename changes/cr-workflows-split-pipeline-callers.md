---
id: cr-workflows-split-pipeline-callers
title: One pipeline definition and two workflows, Checks and Publish; one Mutation workflow
status: in-progress
satisfies: []
adrs: ["0091", "0045", "0057"]
invariants:
  - the published snapshot is the tested bytes, from the same run (ADR 0091 decisions 2 and 5) — Publish calls the pipeline, never reads another run's results
  - permissions stay what they are today, job by job (owner 2026-11-07, "aus Security nichts ändern, einfach nur so aufteilen")
  - a pull request, a push and a local run (`scripts/run_pipeline.py`) never write to a registry, never sign, never tag
  - the snapshot tag is created through `gh api --method POST repos/…/git/refs`, never `git push` (0dd2438c9)
  - a backend-only change runs only the backend mutation gate; a PIT-scoped class still triggers it, on PR and on push
  - nothing is pushed, dispatched or deleted on GitHub without the owner's go for that step
---

## Goal

Before V1 the Actions list says what each workflow is for. Today `ci.yml` is three things at once — the check of
every pull request and push, the weekly snapshot and the dispatched one. After this CR (owner 2026-11-07: two
workflows on one shared base, Checks (owner first said "PR", then: pushes to `main` must run it too) and Publish; a
pure split, no security change):

- **`pipeline.yml`** (`on: workflow_call`) — the shared base: every stage of today's `ci.yml` (`guards`, `backend`,
  `backend-races`, `frontend`, `chart`, `web-e2e`, `api-fuzz`, `version`, `build`, `stack`, `upgrade-proof`) with
  its permissions unchanged, plus a `green` job that needs every stage.
- **`checks.yml`** "Checks" on `pull_request` and `push` (`main`, `release/**`, today's `paths-ignore`): calls the pipeline
  with `publish: false`. Publishes nothing, as a PR and a push do today.
- **`publish.yml`** "Publish" on `schedule` (`17 3 * * 1`) and `workflow_dispatch`: calls the pipeline with
  `publish: true`, then today's `snapshot` and `snapshot-tag` jobs **in the same run**. Keeps the
  HEAD-already-has-`snapshot-*` notice and the REST-API tag.
- **`mutation.yml`** "Mutation": `mutation.yml` (Stryker) and `mutation-backend.yml` (PIT) merged; a
  `dorny/paths-filter` job decides which gate runs.
- `release.yml` unchanged except the snapshot identity it verifies against.

## Context & constraints

- ADR 0091 decisions 2 (one pipeline per commit), 4 (when what runs), 5 (build once, promote; the 2026-11-07
  amendment); ADR 0045 (the mutation gates' trigger lists); ADR 0057 §3 (the identity a verification pins).
- Live CR `cr-release-promotes-a-tested-snapshot` (phase 2, retention, after V1) names `ci.yml` in its text; this CR
  re-points the code and corrects that CR's identity lines in its docs phase.
- **Substrate:** worktree `claude/awesome-sinoussi-6ae208` at `0dd2438c9` (= pushed `main`), clean. Remote
  `raddatzk/veyq`.
- **Gates at refine:** `for f in scripts/validate_*.py; do /usr/bin/python3 "$f" || echo "RED $f"; done` (without
  `validate_guard_gaps.py`) + `promote_selftest.py` + `snapshot_selftest.py` → no RED at `0dd2438c9`.

### Measured

| Fact | Command / source |
|---|---|
| Today's snapshot SAN is the file that holds the signing job: 0.2343 verifies with `--certificate-identity https://github.com/raddatzk/veyq/.github/workflows/ci.yml@refs/heads/main` and `--certificate-github-workflow-name CI` (rc 0) | `cosign verify ghcr.io/raddatzk/veyq:0.2343-SNAPSHOT --certificate-identity … --certificate-oidc-issuer https://token.actions.githubusercontent.com` |
| Fulcio's SAN for GitHub Actions is `https://github.com/{job_workflow_ref}`; `job_workflow_ref` is "for jobs using a reusable workflow, the ref path to the reusable workflow"; the caller is in `workflow_ref` (Build Config URI) | github.com/sigstore/fulcio docs/oidc.md, docs/oid-info.md; docs.github.com/en/actions/reference/security/oidc |
| ⇒ a job **defined in** `publish.yml` signs as `…/publish.yml@refs/heads/<branch>`; a job inside `pipeline.yml` would sign as `…/pipeline.yml@…` whoever called it — so `snapshot` stays in Publish, not in the base | the two rows above (the first is the direct-job case, measured) |
| A called workflow can only downgrade the caller's token ("can be only downgraded (not elevated)") — so each caller's call job grants what today's `ci.yml` grants (`contents: read`, `packages: write` for `build`) | docs.github.com/en/actions/reference/workflows-and-actions/reusing-workflow-configurations |
| The `github` context in a called workflow is the caller's (`event_name`, `ref`); workflow-level `env` is **not** propagated | docs.github.com/en/actions/how-tos/reuse-automations/reuse-workflows |
| A `./` call reads the called workflow from the caller's commit | same page |
| `main` has no branch protection and no ruleset, so renamed check names break nothing | `gh api repos/raddatzk/veyq/branches/main/protection` → 404 "Branch not protected"; `…/rulesets` → empty |
| Remote snapshot tags: `snapshot-0.2343` only (0.2347's tag failed, 0.2350's dispatch 42380712118 failed at signing) | `git ls-remote --tags origin 'snapshot-*'`; `gh run view 42380712118` |
| `promote.py` reads the lane matrix from `git show <commit>:.github/workflows/ci.yml` and pins `ci.yml` in `snapshot_identity()` | `scripts/promote.py:96-101, 447-454` |
| `validate_coverage.py` keeps only workflows triggered by `push`/`pull_request` and reads `steps` — a `workflow_call` file and a call job are both invisible to it today | `scripts/validate_coverage.py:452-552` |
| `run_pipeline.py` has no job-level `uses:` handling (`inputs` is `{}`) | `scripts/run_pipeline.py:656, 874` |

**Remembered, measured only by the first GitHub runs (phase 5):** (a) a nested job requesting more than its call
job grants fails the run at startup, whatever its `if:` — hence both call jobs grant `packages: write`; (b) artifacts
a called job uploads are downloadable by a later caller job of the same run; (c) a call job's `result` is `success`
when a nested job was skipped by `if: false`. The design does not lean on (c): `snapshot` reads the pipeline's
`green` output.

## Design

### `pipeline.yml` — the shared base

- `on.workflow_call.inputs.publish` (boolean, default `false`); `outputs`: `version`, `major`, `kind`, `green`.
- Every job and its `permissions:` block moved verbatim (top-level `contents: read`, `build` `packages: write`,
  `stack` `packages: read`, …).
- **`version`**: the step's env `EVENT_NAME` becomes `PUBLISH: ${{ inputs.publish }}`. `kind=snapshot` iff
  github-hosted ∧ `PUBLISH == true` ∧ HEAD has no `snapshot-*` tag (the notice stays); the branch check (`main` or
  `release/<major>.x`) runs when `PUBLISH` is true. Consequence, decided: a push to a stray `release/7.x` is no
  longer red — it publishes nothing, so the red guarded nothing.
- **`green`** (new, "Pipeline green"): `needs` every stage, `if: always()`, red unless every result is `success`
  (`upgrade-proof`: `success` or `skipped`), output `green=true`. Today's explicit result list moves here from
  `snapshot.if`; it is also the one check a future required status names.
- Top-level `env: HELM_VERSION/HELM_SHA256` (`chart` needs it; a caller's env does not propagate). No
  `concurrency:` — the callers own it.
- `stack`'s `exclude` keeps `github.event_name == 'pull_request'` (the caller's context).

### `checks.yml` "Checks"

`on: push / pull_request` exactly as today (branches, `paths-ignore`); `permissions: contents: read`;
`concurrency: veyq-checks-<ref>`, `cancel-in-progress: false`; one job `pipeline: uses: ./.github/workflows/pipeline.yml`,
`permissions: {contents: read, packages: write}` (today's ceiling, (a)), `with: {publish: false}`. The push to
`main` stays here: the owner works on `main` directly, so a push is tested as a PR is.

### `publish.yml` "Publish"

`on: schedule (17 3 * * 1) / workflow_dispatch` (no inputs); `permissions: contents: read`; `concurrency:
veyq-publish-<ref>`, `cancel-in-progress: false` (today's "publish" group, now a file's own); top-level Helm env.
Jobs: `pipeline` (call, `contents: read, packages: write`, `publish: true`); `snapshot` (`needs: pipeline`, `if:
needs.pipeline.outputs.green == 'true' && needs.pipeline.outputs.kind == 'snapshot'`, permissions and steps
unchanged, reading `needs.pipeline.outputs.version`); `snapshot-tag` (unchanged, `contents: write`). The shard/arch
lists in `snapshot.py predicates` stay held to `pipeline.yml`'s `stack` matrix.

**New snapshot identity:** `^https://github\.com/<repo>/\.github/workflows/publish\.yml@refs/heads/(main|release/[0-9]+\.x)$`.

### One way to read a call: `scripts/workflow_calls.py`

Four readers must see through a call job (`validate_coverage`, `validate_pnpm_installs_gated`,
`validate_release_identity`, `run_pipeline`); four hand-written expansions would be four rules for one GitHub
semantic. One module: `load(path, rev=None)` → the workflow; `expand(workflow)` → its jobs with every `uses:
./.github/workflows/X.yml` call job replaced by X's jobs (one level; a nested call or a remote `owner/repo/…@ref`
is refused), each carrying its caller job, the `with:` inputs (defaults filled), the effective permissions (the
nested job's block, or the call job's grant; a request above the grant is an error, mirroring (a)), and `needs`
rewritten so the call job's needs reach the called roots and its dependants wait for the called sinks. Its own
selftest (`scripts/workflow_calls_selftest.py`) on inline fixtures; it runs in `guards`.

### Mutation

One `mutation.yml` "Mutation". `on.pull_request.paths` and `on.push.paths` = the union of today's two lists (the
self-paths become `.github/workflows/mutation.yml`), `workflow_dispatch`. Jobs: `changes` (`pull-requests: read`,
`dorny/paths-filter@ceb8a2b8…` v4.0.3 as in `trust-chain.yml`, `if:` push or PR, filters `crypto:` = today's Stryker
list, `backend:` = today's PIT list), `crypto` and `backend` — today's two jobs unchanged, each `if:
github.event_name == 'workflow_dispatch' || needs.changes.outputs.<f> == 'true'`. One concurrency group
(`veyq-mutation-<pr|ref>`, cancel-in-progress true): both gates belong to one run now.

## Progress (2026-11-07)

Phases 1–4 are built and committed on `claude/awesome-sinoussi-6ae208` (`57b4cfb94`, `51f22f12f`, `6a5777127`, and the
docs phase); phase 5 waits for the owner. Where the build left the plan above:
- **The gate is a job-level `if:`**, as the snapshot's was: `green` has `always()` and every stage's result as `&&`
  terms and one step that writes `green=true`, so a red stage skips it and no output exists. `snapshot` reads
  `needs.pipeline.outputs.green == 'true'` **and** `needs.pipeline.result == 'success'`. Remembered point (c) is
  measured in substance: run 42274582708 of `ci.yml`, with `upgrade-proof` and `snapshot-tag` skipped, concluded
  `success` (`gh run view 42274582708 --json conclusion,jobs`), and a call's result is its called run's conclusion.
- **`validate_release_identity.py` reads each caller's run as one workflow** (`composed_run`): the call replaced by the
  pipeline's jobs, `needs.pipeline.outputs.<o>` read as the job that sets it, `needs.pipeline.result` as `green`'s, a
  caller's need on the call as the called jobs it reads. So the existing assertions run unchanged on the Publish run;
  the split's own holds were added beside them (checks.yml runs nothing of its own, `publish` literals per caller,
  triggers per file, no concurrency and no `id-token` in the pipeline). Twelve bites, each red with its own message.
- **A push to a non-release branch is green** (kind `none`), no longer red: it publishes nothing, so the red guarded
  nothing (decided at refine, built as such; the scenarios hold it).
- **`promote.py`'s refusal of a pre-split commit** (no `pipeline.yml` at the snapshot's commit) is in the real host's
  `stack_matrix`, which the selftest fakes; it is a run's to show, at the first promotion of an old tag — none is
  wanted (owner: the tag is deleted in phase 5).

## Phases

Each phase ends green on `for f in scripts/validate_*.py` (guard gaps aside) + the selftests it touches, and commits
(unsigned, explicit paths, landed by a `--no-ff` merge into `main`). Bites per changes/README.md:
snapshot-and-restore, never `git checkout`; each bite says its lever.

### Phase 1 — the call reader, on the layout it is about to leave

Re-point readers first: everything works on today's tree (no call job) and on a fixture with one.

- `scripts/workflow_calls.py` + selftest (bites: a nested request above the grant → refused; a remote call →
  refused; a call job's dependants wait on the called sinks).
- `validate_coverage.py`: workflows triggered by `push`/`pull_request` are expanded; a run is labelled `<caller> ›
  <call job> › <job>`; a call job's `if:` drops its nested jobs as a job `if:` does today.
- `validate_pnpm_installs_gated.py`: section 3's asking workflow and "same workflow" are the expanded caller.
- `run_pipeline.py`: expands a call job (inputs from `with:`, the `inputs` context filled), reports nested jobs as
  `pipeline / build`; `--workflow` default stays `ci.yml` until phase 2. Its docstring states the remaining gap
  (one level, local files only).
- **Done when:** guards green on the untouched workflows; `workflow_calls_selftest.py` green; bite —
  `validate_guard_gaps.py` case 18 (an `if: false` on the backend job) still makes `validate_coverage` red when that
  job sits in a called fixture (lever: a scratch copy of `ci.yml` split into caller + called, snapshot-and-restore).

### Phase 2 — the split

- Write `pipeline.yml`, `checks.yml`, `publish.yml`; delete `ci.yml`. Comments move with their jobs; each file's header
  says what it is for and points at ADR 0091.
- `validate_release_identity.py`: `WORKFLOW` → three constants (`PIPELINE`, `CHECKS`, `PUBLISH`). Per assertion:
  2 executes `version` with `PUBLISH`/`REF_NAME`/`RUNNER_ENVIRONMENT` scenarios (publish × branch × tag × runner);
  3 every registry write in the expanded Publish run is gated on `kind == 'snapshot'`, `snapshot`/`snapshot-tag` are
  in `publish.yml` with today's permission shapes, and `checks.yml` passes `publish: false` (literal); 9 every stage of
  `pipeline.yml` is needed by `green`, with today's result rule, and `snapshot` reads `green`; 10/13 the lane matrix
  is read from `pipeline.yml`; 7 the release identity rejects `publish.yml`, `pipeline.yml` and `checks.yml`; 14 one
  cosign, one Helm pin across `pipeline.yml`, `publish.yml`, `release.yml`; 15/16 over the expanded Publish run +
  `release.yml`; 17 triggers per file (`checks.yml`: push + PR only; `publish.yml`: schedule with a cron + dispatch
  without inputs, no push/PR, no tag trigger; `pipeline.yml`: `workflow_call` only), concurrency per caller with
  `cancel-in-progress: false`. Docstring's defect/limits updated.
- `promote.py`: `snapshot_identity()` → `publish.yml`; `stack_matrix()` reads `pipeline.yml` at the snapshot's
  commit and refuses a commit without it ("snapshot made before the workflow split; not promotable").
  `promote_selftest.py`: the accept/reject table under the new file, plus `ci.yml@refs/heads/main` and
  `pipeline.yml@refs/heads/main` rejected.
- Build-job readers to `pipeline.yml`: `validate_images_run_unprivileged.py`, `validate_image_pins.py`,
  `validate_compose_posture.py`, `image_identity_cache_proof.py`; `tests/e2e/shards.test.mjs` slices `pipeline.yml`.
- `run_pipeline.py --workflow` default → `checks.yml` (through the call).
- **Bites** (each must go red, then restore): a stage removed from `green.needs`; `snapshot.if` without the `green`
  term; `checks.yml` passing `publish: true`; `push:` added to `publish.yml`; `PUBLISH` replaced by `true` in the version
  step (assertion 2's PR scenario publishes); a `git push` tag in `snapshot-tag`; `snapshot` moved into
  `pipeline.yml` (assertion 3: signing outside Publish); `promote_selftest` with `snapshot_identity` left on
  `ci.yml`.
- **Done when:** guards + `promote_selftest` + `snapshot_selftest` + `workflow_calls_selftest` green, `pnpm run
  check` in `tests/e2e` green (shards), `run_pipeline.py --dry-run` lists every pipeline job under `checks.yml`.

### Phase 3 — one Mutation workflow

- Merge into `mutation.yml`, delete `mutation-backend.yml`.
- `validate_mutation_trigger_paths.py`: the PIT files ⊆ `on.pull_request.paths`, ⊆ `on.push.paths` (separately, as
  today), ⊆ the `backend` filter; **new**: every entry of each filter is in both trigger lists (a filtered path that
  does not trigger is a gate that never starts), and the `backend` job's `if:` reads `outputs.backend`.
- **Bites:** a PIT class removed from `push.paths` only → red; from the `backend` filter only → red; a crypto filter
  entry missing from `pull_request.paths` → red; `backend`'s `if:` reading `outputs.crypto` → red; case 10 of
  `validate_guard_gaps.py` still red-as-documented.
- **Done when:** guards green; `mutation_meta_check_selftest.py` green.

### Phase 4 — the docs move with it

- ADR 0091: decision 2 (one pipeline definition, two workflows, why `snapshot` sits in Publish and in the same run),
  decision 4 (the triggers per file), decision 5 + its 2026-11-07 amendment (identity `publish.yml`). ADR 0045
  line 118 ("both workflow `paths:` blocks" → the triggers and the filter). Dated amendments, no new ADR.
- `docs/crosscutting/release-and-deployment.md`, `infra/README.md` § Cutting a release (`gh workflow run
  publish.yml`, "Actions → Publish → Run workflow", the snapshot `cosign verify` regex), `site/…/run/
  verify-a-release.md`, `run/upgrade.mdx`, `security/how-veyq-is-tested.md` (stage map), `security/
  verify-it-yourself.md`, `.claude/skills/trust-chain/SKILL.md` (its line citations), the coverage prose (41 hits
  `ci.yml → stack|frontend` → `pipeline.yml → …`), the comment-only mentions in scripts/src/tests/launcher/.github,
  and `cr-release-promotes-a-tested-snapshot.md`'s identity lines.
- `validate_guard_gaps.py --force` (guards changed in phases 1–3).
- **Done when:** `grep -rn 'ci\.yml\|mutation-backend\.yml' . --exclude-dir=.git` hits only history-stating lines
  (ADR amendments); trust chain green; guard gaps green; the site's check if a site page changed.

### Phase 5 — on GitHub (owner's go for each step)

1. Owner pushes. **Measured then:** the push run of Checks is green (proves (a): the call job's grant admits `build`);
   Mutation runs only the gate(s) the push touched.
2. A pull request (any Dependabot one) runs Checks green, arm64 legs absent.
3. Owner dispatches Publish on `main`. **Measured then:** `build` pushes the staging tags, `snapshot` downloads the
   called jobs' artifacts (b), the snapshot and its tag land.
4. `cosign verify ghcr.io/raddatzk/veyq:<v>-SNAPSHOT --certificate-identity-regexp '<new regex>'
   --certificate-oidc-issuer https://token.actions.githubusercontent.com` → rc 0, and with `ci.yml`'s identity →
   rc ≠ 0; `cosign verify-attestation --type https://veyq.app/attestations/stack-lane/v1` likewise.
5. Delete the tag `snapshot-0.2343` (`gh api --method DELETE repos/raddatzk/veyq/git/refs/tags/snapshot-0.2343`), so
   the release page offers no tag `promote.py` refuses.
6. Delete the runs of the retired workflows "CI", "Mutation (backend)" and "Mutation (crypto)", once Checks, Publish
   and Mutation each ran green once — GitHub lists a workflow while it has runs.
7. The CR's lessons moved, the CR deleted.

## Decided (owner 2026-11-07)

- **The ci.yml-signed snapshots are given up.** 0.2343, 0.2347 and 0.2350 stay unpromotable (`promote.py` refuses
  their identity and their lane-matrix file); V1 is promoted from the first Publish snapshot. The tag
  `snapshot-0.2343` is deleted (phase 5 step 5), and run 42380712118 (0.2350, red at signing during a Rekor outage)
  is **not** re-run.
- **The retired workflows' runs are deleted** after the first green runs of the new ones (phase 5 step 6).
- **Names:** `checks.yml` "Checks" (pull requests and pushes to `main` and `release/**`, since the owner works on
  `main` directly) and `publish.yml` "Publish", both on `pipeline.yml`.

## Amendment 2 (owner 2026-11-07, before V1): Publish signs what Checks tested, and runs no pipeline

**The ask.** Publish no longer runs the pipeline. It publishes HEAD only when HEAD's **Checks** push run is fully
green, and it publishes exactly the bytes that run tested. Release stays as it is. This supersedes the "Publish calls
the pipeline" half of the design above and its phase 5 Publish steps (the push, the PR and the Mutation half of
phase 5 stand: measured below).

### Measured at refine (substrate: origin/main `867fc543a`, this branch at `d107fde35` + the merge `d7f5bea69`)

| Fact | Command / source |
|---|---|
| The split runs on GitHub: a PR run of Checks went green (42390810915, Dependabot's stryker bump); it ran no arm64 lane leg (3 arm64 jobs, the `build` legs) | `gh run view 42390810915 --json jobs` |
| A nested job asking `packages: write` under a call granting it starts (no startup error) | push run 42388487609's `build` legs started |
| The push runs of Checks on `d7f5bea69`…`e78e0a707` were cancelled by `raddatzk` (by hand), not by the concurrency group | `gh api …/runs/42390271894 --jq .triggering_actor.login` |
| A per-arch staging push is an OCI **index** (the image manifest + its attestation manifest); the snapshot index (`imagetools create`) references the per-arch **children**, not the staging index | `docker buildx imagetools inspect --raw ghcr.io/raddatzk/veyq:build-79ff52e8…-amd64` and `…:0.2343-SNAPSHOT` |
| YAML anchors and aliases are supported in workflow files (merge keys `<<:` not documented) | docs.github.com …/reusing-workflow-configurations § YAML anchors and aliases |
| `actions/download-artifact` downloads another run's artifacts with `run-id` + `github-token` (a token with `actions: read`); `pattern` with `run-id` is not documented | github.com/actions/download-artifact README |
| Artifact retention is at most 90 days | upload-artifact README |
| "List workflow runs for a workflow" filters by `head_sha`, `event`, `branch`, `status`; a run carries `run_attempt`, `conclusion`; jobs list takes `filter=latest` | docs.github.com/en/rest/actions/workflow-runs, …/workflow-jobs |
| A workflow's `GITHUB_TOKEN` may delete a package version when the repository has the package's Admin role (granted to the repo that published it) — **public preview**; no untag API exists | docs.github.com …/deleting-and-restoring-a-package |
| The owner's `gh` token lacks `read:packages`, so the package versions could not be listed from here | `gh api users/raddatzk/packages/container/veyq/versions` → 403 |

### Design

- **The pipeline folds back into `checks.yml`.** With Publish no longer calling it, `pipeline.yml` has one caller;
  folding it in removes the Actions entry the owner asked about, and `scripts/workflow_calls.py` (with its selftest,
  `composed_run` and the expansions in `validate_coverage`, `validate_pnpm_installs_gated`, `run_pipeline`) has no
  call left to read, so it is deleted rather than kept for a call nobody makes. (Decided here: technical.)
- **Checks stages on a push.** The `version` job's `kind` becomes `stage` on GitHub's runner for a push to `main` or
  `release/<major>.x`, `none` for a pull request, a local run and a push to any other branch — no third value: what
  `kind=snapshot` did in the pipeline is exactly staging (push `build-<sha>-<arch>`, lanes pull by digest and record
  their result), and the "HEAD already has its snapshot" check moves to Publish. The digest and lane-result artifacts
  are kept **30 days** (from 7), so a Publish can read them.
- **`packages: write` on the push's build only**, as the owner asks: two jobs share one definition through YAML
  anchors on `strategy:` and `steps:` (no merge keys) — `build` (`if: push`, `packages: write`) and `build-local`
  (`if: pull_request`, `contents: read`). `stack` needs both and gates on whichever ran. PR runs then hold no write in
  any job — a guard holds it.
- **Publish** (`schedule` + `workflow_dispatch`, identity unchanged, `publish.yml`):
  1. `checks-run` job (`actions: read`): HEAD's SHA on `main` or `release/<major>.x` (red elsewhere); HEAD already
     tagged `snapshot-*` → notice, publish nothing; else HEAD's Checks run = the newest `event=push` run of
     `checks.yml` for that SHA and branch. Refused, red and named, unless it is completed, `success`, its latest
     attempt, and every job `success` (only `upgrade-proof` may be `skipped`), with a lane leg per architecture × domain
     of the commit's own `stack` matrix. The decisions are `scripts/snapshot.py checks-run` (the API answers in, the
     verdict out), held by `snapshot_selftest.py` on recorded API shapes.
  2. `snapshot` job: downloads `digest-*` and `lane-result-*` **from that run id**; refuses unless every lane record
     names exactly the digest artifacts, and every `build-<sha>-<arch>` staging tag still resolves to its digest (a
     re-staged or moved tag is red); version from the tree (`release_assets.tree_release`, already held equal to the
     version step); then today's steps unchanged — write-once refusal, `-SNAPSHOT` and `sha-…` names, signatures,
     chart, one attestation per lane and architecture whose `run` is the **Checks run's** URL.
  3. `snapshot-tag` unchanged (REST API).
  4. `staging-retention` job, weekly (schedule only): deletes `build-*` staging indexes older than **30 days** whose
     commit is no branch tip; for a commit without a snapshot it deletes the index's two children too (nothing else
     references them), for one with a snapshot only the staging index (the snapshot references the children). Its
     selection is `scripts/staging_retention.py`'s, selftested; it deletes from its first run (owner) and prints
     every version it deletes and why (the delete path is a public preview and unmeasured here).
- **Guards**: `validate_release_identity.py` — Checks: every write gated on `kind == 'stage'`, no job of a PR run can
  write (`build-local`, every other job read-only), assertion 2's scenarios (push → stage, PR / local / other branch →
  none); Publish: no pipeline job and no build, runs `snapshot.py checks-run` before its first write, downloads only
  from the verified run id, `actions: read` only where it reads, the old split holds trimmed. `promote.py`'s
  `stack_matrix` reads `checks.yml` at the snapshot's commit. `validate_pipeline_shape.py` is checked for anything
  that assumes the call (it exempted call jobs only).
- **Bites** (each red): Publish without a green Checks run (a failed job, a cancelled run, an older attempt, a missing
  arm64 leg — selftest), a publish of digests other than the staged ones (a lane record or a staging tag that differs —
  selftest + a guard bite that drops the check), a write in a PR run (`packages: write` on `build-local`, an ungated
  push), Publish calling a pipeline again, the version step staging a PR.
- **Docs**: ADR 0091 decisions 2 and 5 (today's first amendment rewritten, not stacked), release-and-deployment,
  infra/README § Cutting a release, how-veyq-is-tested, the verify pages (identity unchanged).

### Phases

6. Sync onto origin/main (the Dependabot and MariaDB commits), fold `pipeline.yml` into `checks.yml`, delete the call
   reader, stage on push, split the build — guards green, bites for the PR-write and the version step.
7. Publish from the Checks run: `snapshot.py checks-run` + the digest/staging verification + selftest; publish.yml;
   guards and bites; `promote.py`.
8. Staging retention + selftest.
9. Docs; `validate_guard_gaps.py --force`.
10. GitHub (owner's go per step): push → a Checks push run stages and goes green; dispatch Publish → it publishes in
    minutes; `cosign verify` under publish.yml's identity (rc 0) and ci.yml's (rc ≠ 0); the retention's first run and what it deleted;
    then the owner deletes `snapshot-0.2343` and the retired runs.

### Decided (owner 2026-11-07)

1. **Retention is on from its first run** — no dry-run phase. So its first GitHub run deletes; its selection is
   proven before that only by its selftest, and its job prints every version it deletes and why.
2. **A Checks run cancelled by hand, or not green, is refused, named** — Publish never re-runs Checks; the next push
   or a re-run of that Checks run makes HEAD publishable again.
