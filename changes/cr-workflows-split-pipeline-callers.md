---
id: cr-workflows-split-pipeline-callers
title: One pipeline definition, two callers; the snapshot's write rights live in snapshot.yml alone; one Mutation workflow
status: planned
satisfies: []
adrs: ["0091", "0045", "0057"]
invariants:
  - the published snapshot is the tested bytes, from the same run (ADR 0091 decisions 2 and 5) — the snapshot jobs call the pipeline, never read another run's results
  - "no file a pull request or a push runs holds a write permission: `packages: write`, `id-token: write` and `contents: write` are spelled in `snapshot.yml` (and `release.yml`) only"
  - a pull request, a push and a local run (`scripts/run_pipeline.py`) never write to a registry, never sign, never tag
  - the snapshot tag is created through `gh api --method POST repos/…/git/refs`, never `git push` (0dd2438c9)
  - a backend-only change runs only the backend mutation gate; a PIT-scoped class still triggers it, on PR and on push
  - nothing is pushed, dispatched or deleted on GitHub without the owner's go for that step
---

## Goal

Before V1 the Actions list says what each workflow is for. Today `ci.yml` is three things — the check of every pull
request and push, the weekly snapshot and the dispatched one — and it holds `packages: write`, `id-token: write` and
`contents: write` in the file every pull request runs. After this CR:

- **`pipeline.yml`** (`on: workflow_call`) — every stage of today's `ci.yml`: `guards`, `backend`, `backend-races`,
  `frontend`, `chart`, `web-e2e`, `api-fuzz`, `version`, `build`, `stack`, `upgrade-proof`, plus a `green` job that
  needs every stage. It spells no `write`.
- **`checks.yml`** "Checks" (name to be agreed, § Open questions) on `pull_request` and `push` (`main`,
  `release/**`, today's `paths-ignore`): calls the pipeline with `publish: false`, grants `contents: read` +
  `packages: read`, writes nothing.
- **`snapshot.yml`** "Snapshot" on `schedule` (`17 3 * * 1`) and `workflow_dispatch`: calls the pipeline with
  `publish: true`, then today's `snapshot` and `snapshot-tag` jobs **in the same run**. The only file besides
  `release.yml` with a write permission. Keeps the HEAD-already-has-`snapshot-*` notice and the REST-API tag.
- **`mutation.yml`** "Mutation": `mutation.yml` (Stryker) and `mutation-backend.yml` (PIT) merged; a
  `dorny/paths-filter` job decides which gate runs.
- `release.yml` unchanged except the snapshot identity it verifies against.

## Context & constraints

- ADR 0091 decisions 2 (one pipeline per commit), 4 (when what runs), 5 (build once, promote; the 2026-11-07
  amendment); ADR 0045 (the mutation gates' trigger lists); ADR 0057 §3 (the identity a verification pins).
- Live CR `cr-release-promotes-a-tested-snapshot` (phase 2, retention, waits for after V1) names `ci.yml` in its
  text; this CR re-points the code, that CR's prose is corrected in this CR's docs phase.
- **Substrate:** worktree `claude/awesome-sinoussi-6ae208` at `0dd2438c9` (= pushed `main`), clean. Remote
  `raddatzk/veyq`.
- **Gates at refine:** `for f in scripts/validate_*.py; do /usr/bin/python3 "$f" || echo "RED $f"; done` (without
  `validate_guard_gaps.py`) + `promote_selftest.py` + `snapshot_selftest.py` → no RED at `0dd2438c9`.

### Measured

| Fact | Command / source |
|---|---|
| Today's snapshot SAN is the file that holds the signing job: 0.2343 verifies with `--certificate-identity https://github.com/raddatzk/veyq/.github/workflows/ci.yml@refs/heads/main` and `--certificate-github-workflow-name CI` (rc 0) | `cosign verify ghcr.io/raddatzk/veyq:0.2343-SNAPSHOT --certificate-identity … --certificate-oidc-issuer https://token.actions.githubusercontent.com` |
| Fulcio's SAN for GitHub Actions is `https://github.com/{job_workflow_ref}`; `job_workflow_ref` is "for jobs using a reusable workflow, the ref path to the reusable workflow"; the caller is in `workflow_ref` (Build Config URI) | github.com/sigstore/fulcio docs/oidc.md, docs/oid-info.md; docs.github.com/en/actions/reference/security/oidc |
| ⇒ a job **defined in** `snapshot.yml` signs as `…/snapshot.yml@refs/heads/<branch>`; a job inside `pipeline.yml` would sign as `…/pipeline.yml@…` whoever called it — which is why signing must not move into the pipeline | the two rows above (the first is the direct-job case, measured) |
| A called workflow can only downgrade the caller's token ("can be only downgraded (not elevated)") | docs.github.com/en/actions/reference/workflows-and-actions/reusing-workflow-configurations |
| The `github` context in a called workflow is the caller's (`event_name`, `ref`); workflow-level `env` is **not** propagated | docs.github.com/en/actions/how-tos/reuse-automations/reuse-workflows |
| A `./` call reads the called workflow from the caller's commit | same page |
| `main` has no branch protection and no ruleset, so renamed check names break nothing | `gh api repos/raddatzk/veyq/branches/main/protection` → 404 "Branch not protected"; `…/rulesets` → empty |
| Remote snapshot tags: `snapshot-0.2343` only (0.2347's tag failed, 0.2350's dispatch 42380712118 failed at signing) | `git ls-remote --tags origin 'snapshot-*'`; `gh run view 42380712118` |
| `promote.py` reads the lane matrix from `git show <commit>:.github/workflows/ci.yml` and pins `ci.yml` in `snapshot_identity()` | `scripts/promote.py:96-101, 447-454` |
| `validate_coverage.py` keeps only workflows triggered by `push`/`pull_request` and reads `steps` — a `workflow_call` file and a call job are both invisible to it today | `scripts/validate_coverage.py:452-552` |
| `run_pipeline.py` has no job-level `uses:` handling (`inputs` is `{}`) | `scripts/run_pipeline.py:656, 874` |

**Remembered, measured only by the first GitHub runs (phase 5):** (a) a nested job requesting more than the caller
grants is a startup error ("The nested job … is requesting …, but is only allowed …"), raised whatever its `if:`;
(b) a nested job with no `permissions:` gets the caller job's grant; (c) artifacts a called job uploads are
downloadable by a later caller job of the same run; (d) a call job's `result` is `success` when a nested job was
skipped by `if: false`. The design does not lean on (d): `snapshot` reads the pipeline's `green` output.

## Design

### The one design problem: `build` writes, and the pipeline must not

`build` pushes the per-arch staging tags (`packages: write`) when the run publishes, and `stack` pulls them by
digest — that is how the snapshot is the bytes L1 installed. So the pipeline cannot be read-only in every run.
Three ways:

1. **`build` declares no `permissions:` and inherits the caller's grant** (chosen). `checks.yml` grants `contents:
   read, packages: read`, so a PR's `build` holds no write; `snapshot.yml` grants its `pipeline` call job `contents:
   read, packages: write` (never `id-token`, never `contents: write`), so a publishing run's `build` can push. Every
   other pipeline job declares its own read-only block (`stack`: `contents: read, packages: read`), and
   `pipeline.yml` has **no top-level `permissions:`** (one there would cap `build` in the snapshot run too). The word
   `write` appears in `pipeline.yml` nowhere — a guard holds that.
2. `build` exports OCI tarballs as artifacts and `snapshot` pushes them — truly read-only pipeline, and L1 would test
   the build job's bytes on PRs too; but ~6 × several hundred MB of artifacts per run, a new push path that must keep
   digests, and the PR lane's own build goes away only if the artifacts are cheap. Bigger than this CR; rejected here.
3. Two `build` jobs (one `packages: write`, `if: inputs.publish`) — breaks on (a): a PR's checks would fail at
   startup on the nested job's request. Rejected.

**What the hardening is, and is not.** A `pull_request` run executes the PR's own workflow files: someone with push
access can still write a branch whose `checks.yml` grants writes, and fork and Dependabot PRs already get a read-only
token. What it removes is the write token from every **push** run and every same-repo PR run, where `build` (third-party
actions, the commit's Dockerfiles) held `packages: write` today. That is the real gain; it is not a defence against a
committer.

### `pipeline.yml`

- `on.workflow_call.inputs.publish` (boolean, default `false`); `outputs`: `version`, `major`, `kind`, `green`.
- **`version`**: the step's env `EVENT_NAME` becomes `PUBLISH: ${{ inputs.publish }}`. `kind=snapshot` iff
  github-hosted ∧ `PUBLISH == true` ∧ HEAD has no `snapshot-*` tag (the notice stays); the branch check (`main` or
  `release/<major>.x`) runs when `PUBLISH` is true. Consequence, decided: a push to a stray `release/7.x` is no
  longer red — it publishes nothing, so the red guarded nothing. A PR that hand-edits `publish: true` into its
  `checks.yml` gets `kind=snapshot` and a `build` without `packages: write`: red, fail-closed.
- **`green`** (new, "Pipeline green"): `needs` every stage, `if: always()`, red unless every result is `success`
  (`upgrade-proof`: `success` or `skipped`), output `green=true`. Today's explicit result list moves here from
  `snapshot.if`. It is also the one job a future required check names.
- Top-level `env: HELM_VERSION/HELM_SHA256` (needed by `chart`; caller env does not propagate). No `concurrency:`
  (the callers own it; same-group caveat in the docs).
- `stack`'s `exclude` keeps `github.event_name == 'pull_request'` (the caller's context).

### `checks.yml`

`on: push / pull_request` exactly as today (branches, `paths-ignore`); `permissions: contents: read`; `concurrency:
veyq-checks-<ref>`, `cancel-in-progress: false`; one job `pipeline: uses: ./.github/workflows/pipeline.yml`,
`permissions: {contents: read, packages: read}`, `with: {publish: false}`.

### `snapshot.yml`

`on: schedule (17 3 * * 1) / workflow_dispatch` (no inputs); top-level `permissions: contents: read`; `concurrency:
veyq-snapshot-<ref>`, `cancel-in-progress: false` (today's "publish" group, now a file's own); top-level Helm env.
Jobs: `pipeline` (call, `contents: read, packages: write`, `publish: true`); `snapshot` (`needs: pipeline`, `if:
needs.pipeline.outputs.green == 'true' && needs.pipeline.outputs.kind == 'snapshot'`, `contents: read, packages:
write, id-token: write`, steps unchanged, reading `needs.pipeline.outputs.version`); `snapshot-tag` (unchanged, `if:
!cancelled() && needs.snapshot.result == 'success' && …kind == 'snapshot'`, `contents: write`). The shard/arch lists
in `snapshot.py predicates` stay held to `pipeline.yml`'s `stack` matrix.

**New snapshot identity:** `^https://github\.com/<repo>/\.github/workflows/snapshot\.yml@refs/heads/(main|release/[0-9]+\.x)$`.

### One way to read a call: `scripts/workflow_calls.py`

Four readers must see through a call job (`validate_coverage`, `validate_pnpm_installs_gated`,
`validate_release_identity`, `run_pipeline`); four hand-written expansions would be four rules for one GitHub
semantic. One module: `load(path, rev=None)` → the workflow; `expand(workflow)` → its jobs with every `uses:
./.github/workflows/X.yml` call job replaced by X's jobs (one level; a nested call or a remote `owner/repo/…@ref`
is refused), each carrying its caller job, the `with:` inputs (defaults filled), the effective permissions (the
nested job's own block, or the caller job's grant when it has none; a nested request above the grant is an error,
mirroring (a)), and `needs` rewritten so the call job's needs reach the called roots and its dependants wait for the
called sinks. Its own selftest (`scripts/workflow_calls_selftest.py`) on inline fixtures; it runs in `guards`.

### Mutation

One `mutation.yml` "Mutation". `on.pull_request.paths` and `on.push.paths` = the union of today's two lists (the
self-paths become `.github/workflows/mutation.yml`), `workflow_dispatch`. Jobs: `changes` (`pull-requests: read`,
`dorny/paths-filter@ceb8a2b8…` v4.0.3, `if:` push or PR, filters `crypto:` = today's Stryker list, `backend:` =
today's PIT list), `crypto` and `backend` — today's two jobs unchanged, each `if: github.event_name ==
'workflow_dispatch' || needs.changes.outputs.<f> == 'true'`. Concurrency per job pair kept apart
(`veyq-mutation-<pr|ref>`, cancel-in-progress true, workflow-level is enough since both gates belong to one run).

## Phases

Each phase ends green on `for f in scripts/validate_*.py` (guard gaps aside) + the selftests it touches, and commits
(unsigned, explicit paths, `--no-ff` merge into `main` per the landing rule). Bites per changes/README.md:
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
  job sits in a called fixture (lever: a temporary copy of `ci.yml` split into caller + called in a scratch
  `.github/workflows/`, snapshot-and-restore).

### Phase 2 — the split

- Write `pipeline.yml`, `checks.yml`, `snapshot.yml`; delete `ci.yml`. Comments move with their jobs; the file
  header of each says what it is for and points at ADR 0091.
- `validate_release_identity.py`: `WORKFLOW` → three constants (`PIPELINE`, `CHECKS`, `SNAPSHOT`). Per assertion:
  2 executes `version` with `PUBLISH`/`REF_NAME`/`RUNNER_ENVIRONMENT` scenarios (publish × branch × tag × runner);
  3 every registry write in the expanded snapshot run is gated on `kind == 'snapshot'`, `snapshot`/`snapshot-tag`
  are in `snapshot.yml` with today's permission shapes; **new**: `pipeline.yml` spells no `write`, has no top-level
  `permissions:`, every job but `build` has its own read-only block; `checks.yml` grants no write anywhere; the
  snapshot call job grants exactly `contents: read, packages: write`; 9 every stage of `pipeline.yml` is needed by
  `green`, with today's result rule, and `snapshot` reads `green`; 10/13 the lane matrix is read from `pipeline.yml`;
  7 the release identity rejects `snapshot.yml`, `pipeline.yml` and `checks.yml`; 14 one cosign, one Helm pin across
  `pipeline.yml`, `snapshot.yml`, `release.yml`; 15/16 over the expanded snapshot run + `release.yml`; 17 triggers
  per file (`checks.yml`: push + PR only; `snapshot.yml`: schedule with a cron + dispatch without inputs, no push/PR,
  no tag trigger; `pipeline.yml`: `workflow_call` only), concurrency per caller with `cancel-in-progress: false`.
  Docstring's defect/limits updated.
- `promote.py`: `snapshot_identity()` → `snapshot.yml`; `stack_matrix()` reads `pipeline.yml` at the snapshot's
  commit, refusing a commit without it with "snapshot made before the workflow split (cr-workflows-split…); not
  promotable" — the owner question below decides that this is the wanted behaviour. `promote_selftest.py`: the
  accept/reject table under the new file, plus `ci.yml@refs/heads/main` and `pipeline.yml@refs/heads/main` rejected.
- Build-job readers to `pipeline.yml`: `validate_images_run_unprivileged.py`, `validate_image_pins.py`,
  `validate_compose_posture.py`, `image_identity_cache_proof.py`; `tests/e2e/shards.test.mjs` slices `pipeline.yml`.
- `run_pipeline.py --workflow` default → `checks.yml` (through the call).
- **Bites** (each must go red, then restore): `packages: write` added to `pipeline.yml`'s `build`; a top-level
  `permissions:` in `pipeline.yml`; `checks.yml`'s call job granted `packages: write`; `id-token: write` on the
  snapshot call job; a stage removed from `green.needs`; `snapshot.if` without the `green` term; `push:` added to
  `snapshot.yml`; `PUBLISH` replaced by `true` in the version step (assertion 2's PR scenario publishes); a
  `git push` tag in `snapshot-tag`; `promote_selftest` with `snapshot_identity` left on `ci.yml`.
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

- ADR 0091: decision 2 (one pipeline definition, two callers, why the snapshot is in the caller and the same run),
  decision 4 (the triggers per file), decision 5 + its 2026-11-07 amendment (identity `snapshot.yml`; the write
  rights in `snapshot.yml` and the `build` inheritance), § Rejected (option 2 and 3 above, one line each). ADR 0045
  line 118 ("both workflow `paths:` blocks" → the triggers and the filter). Amendment, dated, no new ADR.
- `docs/crosscutting/release-and-deployment.md`, `infra/README.md` § Cutting a release (`gh workflow run
  snapshot.yml`, "Actions → Snapshot → Run workflow", the snapshot `cosign verify` regex), `site/…/run/
  verify-a-release.md`, `run/upgrade.mdx`, `security/how-veyq-is-tested.md` (stage map), `security/
  verify-it-yourself.md`, `.claude/skills/trust-chain/SKILL.md` (its line citations), the coverage prose (41 hits
  `ci.yml → stack|frontend` → `pipeline.yml → …`), the D-class comments in scripts/src/tests/launcher/.github, and
  `cr-release-promotes-a-tested-snapshot.md`'s identity lines.
- `validate_guard_gaps.py --force` (guards changed in phases 1–3).
- **Done when:** `grep -rn 'ci\.yml\|mutation-backend\.yml' . --exclude-dir=.git` hits only history-stating lines
  (ADR amendments); trust chain green; guard gaps green; site build if a site page changed (`site/` check).

### Phase 5 — on GitHub (owner's go for each step)

1. Owner pushes. **Measured then:** the push run of `checks.yml` is green (proves (a)/(b) for the read-only side:
   `build` starts and pushes nothing); `mutation.yml` runs only the gate(s) the push touched.
2. A pull request (any Dependabot one) runs `Checks` green, arm64 legs absent.
3. Owner dispatches `snapshot.yml` on `main`. **Measured then:** `build` pushes staging tags under the inherited
   grant (b), `snapshot` downloads the called jobs' artifacts (c), the snapshot and its tag land.
4. `cosign verify ghcr.io/raddatzk/veyq:<v>-SNAPSHOT --certificate-identity-regexp '<new regex>'
   --certificate-oidc-issuer https://token.actions.githubusercontent.com` → rc 0, and the same with `ci.yml`'s
   identity → rc ≠ 0; `cosign verify-attestation --type https://veyq.app/attestations/stack-lane/v1` likewise.
5. The CR's lessons moved, the CR deleted.

## Open questions (owner)

1. **Name of the checks caller.** "PR" (owner's suggestion) also runs on every push to `main` and `release/<major>.x`,
   so the Actions list would show "PR" runs for merges. Proposed: `checks.yml`, "Checks". Or "PR" anyway.
2. **The ci.yml-signed snapshots** 0.2343 (tagged), 0.2347, 0.2350: after this CR `promote.py` refuses them (identity
   and lane-matrix file), V1 is promoted from the first `snapshot.yml` snapshot. Confirm — and: should
   `snapshot-0.2343` be deleted so the release page offers no tag that cannot be promoted? And 42380712118 should
   then **not** be re-run (its 0.2350 would be another unpromotable snapshot).
3. **Old workflow entries.** GitHub keeps "CI" and "Mutation (backend)/(crypto)" in the Actions list while runs of them
   exist; delete those runs after phase 5 (an outward cleanup, like the pages-build one), or keep the history?
