---
id: cr-release-promotes-a-tested-snapshot
title: Every green push publishes a signed, attested snapshot; a release promotes one, and nothing is rebuilt
status: planned
satisfies: []
adrs: ["0091", "0057", "0096", "0064", "0090", "0085"]
invariants:
  - a pull request (Dependabot's included) and a local run (`scripts/run_pipeline.py`) never write to a registry, never sign, never tag
  - a published name is written once: a snapshot is never replaced, a release is never republished, a snapshot tag is never `v…`
  - a release is the snapshot's bytes; no promotion runs a build (images), and every promoted file is checked against what the lanes installed
  - the identity that signs a release is not the identity that signs a snapshot, so `cosign verify` with the release identity is the answer to "is this digest a release"
  - self-hostable and vendor-neutral: cosign / in-toto attestations in the OCI registry, no closed attestation service
---

## Goal

Inbox #460, [ADR 0091](../docs/decisions/0091-one-pipeline-per-commit-a-release-promotes-a-tested-snapshot.md)
decision 5. Today a push to `main` is a dry run and only a `workflow_dispatch` publishes — a snapshot that the next
dispatch of the commit replaces, or a release built by its own run. After this CR, **every green push to `main` or
`release/<major>.x` publishes its snapshot** — the three images on exactly the digests lane L1 installed, the chart —
signed, with one signed attestation per lane and architecture, and sets the git tag `snapshot-<major>.<minor>` on its
commit. A scheduled job keeps the 20 newest unpromoted snapshots per branch and every promoted one. **A release is a
promotion** of one snapshot, started from the draft the owner saves in GitHub's release page: it verifies the
snapshot's signature and attestations, gives the same digests the release names and the release signature, attaches
the asset set and publishes the draft last. Nothing is rebuilt.

**One owner decision is unbuildable as written and blocks phase 3** (§ Open question): GitHub starts no workflow
for a draft release, so a saved draft cannot itself start the promotion. Phases 1 and 2 do not depend on the answer.

## Context & constraints

- **ADR 0091** decisions 4 and 5 (the work; decision 5's bullet of 2026-11-06 is the owner's shape), decision 2 (one
  pipeline per commit: the snapshot stays in `ci.yml`), decision 3 (lanes; only L1 is built).
- **ADR 0057** §1–§3 (version from the tree, one `v<major>.<minor>` ref, the identity a verification pins), replaced
  in part by ADR 0091. **ADR 0096** §3 (one immutable GitHub Release, assets before publication). **ADR 0064** (the
  chart is the third artifact, OCI on GHCR). **ADR 0085** decision 10 (the major entry the tools image bakes).
- Model: [release-and-deployment](../docs/crosscutting/release-and-deployment.md) § Publishing and § Decided, not built;
  stage map: [How veyq is tested](../site/src/content/docs/security/how-veyq-is-tested.md); operator check:
  [Verify a release](../site/src/content/docs/run/verify-a-release.md); commands: [`infra/README.md`](../infra/README.md)
  § Cutting a release.
- Inbox: #453 (L2) and #461 (L3, archive) are **not built**; #471 (what only GitHub can prove) gets this CR's first-run
  items in the phase that makes them true.
- **Substrate of every measurement below:** this worktree at `b7c8d8c17` (`claude/offene-cr-ubersicht-a6d765`, clean),
  remote `raddatzk/veyq`. `git ls-remote --tags origin` → no tag; `gh release list --repo raddatzk/veyq` → no release;
  a snapshot dispatch (run 42256787583) was running under today's `ci.yml` and was not touched. **No release has ever
  been signed**, so changing the release identity now breaks no installed `veyqctl`.
- **Gates at refine:** `for f in scripts/validate_*.py; do /usr/bin/python3 "$f" || echo "RED $f"; done` (without
  `validate_guard_gaps.py`) → no RED at `b7c8d8c17`.

### What GitHub does — measured from GitHub's docs, 2026-11-06

| Question | Answer | Source |
|---|---|---|
| Does `release: [created]` fire for a draft saved in the UI? | **No.** "Workflows are not triggered for the `created`, `edited`, or `deleted` activity types for draft releases." Editing or deleting a draft starts nothing either. | docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows, § `release` (fetched twice, once by a subagent, once directly) |
| What fires on publication of a draft? | `published` (and `released` unless it is a pre-release); `created` does not fire again — the webhook definition of `created` is "A draft was saved, or a release or pre-release was published without previously being saved as a draft". | same page; docs.github.com/en/webhooks/webhook-events-and-payloads § `release` |
| Can a workflow retag a draft and publish it? | `PATCH /repos/{owner}/{repo}/releases/{id}` takes `tag_name`, `target_commitish` and `draft` ("false publishes the release"); needs push access (`contents: write`). Whether it *creates* a tag that does not exist is **not documented** — so the promotion pushes `v<major>.<minor>` itself first. A draft's id is found only by listing releases ("Only users with push access will receive listings for draft releases"); get-by-tag returns published releases only. | docs.github.com/en/rest/releases/releases |
| What does an immutable release forbid, and when? | From publication: assets cannot be added, modified or deleted; the tag cannot be moved or deleted while the release exists. Title and notes stay editable. A draft is not immutable (the docs recommend draft → attach assets → publish). Deleting an immutable release is allowed, but **its tag name can never be used again**. Tags with no release are not addressed. | docs.github.com/en/code-security/concepts/supply-chain-security/immutable-releases; github.blog changelog 2025-10-28 |
| Can `GITHUB_TOKEN` delete GHCR package versions? | **Contradictory docs.** The Packages pages say a `GITHUB_TOKEN` can delete and restore a package version via the REST API when the repository has the `admin` role on the package (a package a workflow published grants it by default); the REST packages page says a classic PAT with `delete:packages`. The only `packages` permission levels are `read`/`write`. Endpoint for a user-owned package: `DELETE /users/{username}/packages/container/{name}/versions/{id}` (`/orgs/{org}/…` after the move to the `veyq` organisation, #471). **Measured only on GitHub** (phase 2's first run). | docs.github.com/en/packages/learn-github-packages/deleting-and-restoring-a-package; …/about-permissions-for-github-packages; docs.github.com/en/rest/packages/packages |
| Does a tag or release made with `GITHUB_TOKEN` trigger workflows? | **No**: "events triggered by the `GITHUB_TOKEN` will not create a new workflow run", except `workflow_dispatch` and `repository_dispatch`. No loop is possible; `ci.yml` does not trigger on tags anyway (`ci.yml:24-31`). | docs.github.com/en/actions/concepts/security/github_token |
| Which ref signs? | A `release` run's `GITHUB_REF` is `refs/tags/<tag_name>` and it runs the workflow file **of the tag's commit**; a `workflow_dispatch` run's is the dispatched branch. Fulcio's certificate identity is the workflow file at that ref. | events page, `release` and `workflow_dispatch` rows; docs.github.com/en/actions/concepts/workflows-and-actions/workflows |
| Schedule | default branch only, may be delayed or dropped under load, at most every 5 min, disabled after 60 days without activity in a public repository. | events page § `schedule` |

### What exists today (measured in the tree)

- **Lanes:** only L1 (`ci.yml` job `stack`, 2 architectures × 6 domain legs, `ci.yml:751-931`). L2/L3: "no job"
  (how-veyq-is-tested stage map). The jar-level `upgrade-proof` is `if: false` (`ci.yml:952`) until the first
  release of a major ≥ 1. **So the only attestation that can exist is L1's, per architecture.** L1's inputs are
  the commit's own (the asset set and the images); it has no predecessor and no backup list, so **no L1 input can
  move between snapshot and promotion** — "rerun the lanes whose inputs moved" has nothing to rerun until #453.
- **Signing:** keyless cosign v3.1.3 (`ci.yml:1278-1281`), identity
  `^https://github\.com/raddatzk/veyq/\.github/workflows/ci\.yml@refs/heads/(main|release/[0-9]+\.x)$` pinned in
  `src/tools/veyqctl.py:206` and on `verify-a-release.md` (6 commands). That page says snapshot and release "are
  signed by the same identity, so the signature does not tell them apart and the name does"
  (`verify-a-release.md:150-151`). Once the image carries `<major>.<minor>` (ADR 0091 decision 5) and a snapshot and
  its release are the same digest, the name no longer tells them apart either — **only a separate identity can**.
- **The push path builds twice and publishes nothing:** `build` pushes only on a dispatch (`ci.yml:612-633`), and on a
  push the `stack` legs rebuild the app and admin images locally (`ci.yml:819-857`) — bytes that are never published.
- **The image stamp** is `<major>.<minor>-SNAPSHOT` except on a release dispatch (`ci.yml:483-505`); the backend reads
  a `-SNAPSHOT` stamp as "on no release line" (`ReleaseCheck.kt:37`), and `uc.admin.identify-instance-release` A5
  says a snapshot instance reports `-SNAPSHOT`.
- **The chart's default image tag is its `appVersion`** (`infra/kubernetes/charts/veyq/templates/_helpers.tpl:44`), so a
  snapshot chart must carry `<major>.<minor>-SNAPSHOT` to run the snapshot's images, and the release chart `<major>.<minor>`.
- **The asset set is not byte-stable across productions:** `manifest.json` carries `date` (the day it is produced,
  `release_assets.py:342`), the image references (local `name:tag` in the lane, `ghcr…@digest` in a release) and
  `unscheduled` (`release_assets.py:354`). The other files are the commit's.
- **A local run simulates a push** (`run_pipeline.py:644`, `runner.environment` `local` at `:652`): once a push
  publishes, a local run on `main` would reach the publish jobs unless the gate excludes it.
- **What reads the release identity or a release's files:** `veyqctl` (identity constant; it lists GitHub releases and
  skips drafts, `veyqctl.py:461`), the verify page, `validate_release_identity.py` assertions 2, 3, 7–11. admin-web's
  release panel reads `GET /api/instance/release` and the backend's `ReleaseCheck`; neither verifies a signature.

### Open question (owner) — blocks phase 3 only

The owner's trigger, "the draft (`release: created`) starts the promotion", cannot fire (table above). What the
owner wanted from it holds in every option below: the release page's tag list offers the promotable snapshots, the
owner writes the notes in the draft, and the draft is what gets published. The options differ only in what starts it:

1. **(recommended) Save the draft, then press *Run workflow* on `release.yml`** (a `workflow_dispatch` with no
   required input; it promotes the one draft on a `snapshot-` tag and refuses zero or several). Immediate,
   deterministic, runs on the branch the release comes from — so a fix release of `release/<old>.x` is signed on that
   branch as today's identity expects — and it keeps `unscheduled_major` as a dispatch input instead of a marker in the
   notes. Cost: one click outside the release page. It is not the dispatch ADR 0091 rejected (that one named a commit);
   this one names nothing, the draft does.
2. **Save the draft; a scheduled `release.yml` run promotes it.** No second click. Cost: every 10–15 min forever (a
   run in the Actions list each time), "may be delayed during high loads … some queued jobs may be dropped", main's
   branch only (a `release/<old>.x` promotion signs as `main`), disabled after 60 quiet days, and `unscheduled_major`
   must become a marker in the draft's notes.
3. **Publish instead of draft** (`release: published`): not possible — an immutable release takes no asset and its tag
   cannot move once published, and the published tag would be `snapshot-…` forever.

The phase 3 below is written trigger-agnostic; only its `on:` and the "find the draft" step depend on the answer, and
the owner's answer is edited into ADR 0091 decision 5 in that phase.

## Decisions this CR takes

Each was mine to take (technical), with its reason; the owner's decisions are above and in ADR 0091.

1. **The promotion is its own workflow, `.github/workflows/release.yml`, and it is the release identity.** A snapshot
   is signed by `ci.yml` (on a push, `ci.yml@refs/heads/main`); a release signature from `ci.yml` on `main` would be the
   same identity, so `cosign verify` could not tell a release from a snapshot — which ADR 0091 decision 5 says it does.
   So: **snapshot identity** `ci.yml@refs/heads/(main|release/<n>.x)`, **release identity**
   `release.yml@refs/heads/(main|release/<n>.x)`; `veyqctl` and the verify page pin the release identity and reject
   `ci.yml`. ADR 0091 decision 2 is about the per-commit pipeline; a promotion is not per commit. Free now: no release
   has ever been signed. (It also keeps the promotion's logic the newest one: a dispatch runs the branch tip's
   `release.yml`, where a `release` event would run the snapshot commit's.)
2. **The image stamp becomes `<major>.<minor>` in phase 1**, as ADR 0091 decision 5 says. Its ripple is wording, not
   logic: the backend's `-SNAPSHOT` branch stays (images stamped so exist, the running dispatch makes one), and A5 is
   rewritten — a snapshot instance reports the release it may become; whether it is one is the signature's fact.
3. **The chart is packaged again at promotion**, from the snapshot's commit, with `<major>.<minor>`. Its `appVersion`
   is its default image tag (`_helpers.tpl:44`), so the snapshot chart must name `-SNAPSHOT` and the release chart
   cannot be the same bytes. No lane installs the chart; `chart` checks the source of that commit. "Nothing is
   rebuilt" holds for every image and every file a lane installed.
4. **The asset-set hash is the sha256 of the manifest's `assets` map** (asset name → sha256, canonical JSON: sorted
   keys, no whitespace), computed by one function in `scripts/release_assets.py`. It excludes `date`, the image
   references (attested separately, by digest) and `unscheduled` (a promotion input) — the parts that legitimately
   differ between the lane's production and the release's. Everything a lane installs from the set is in it.
5. **Attestations are cosign in-toto attestations in GHCR**, predicate type `https://veyq.app/attestations/stack-lane/v1`,
   one per lane and architecture, attached to the app and the admin image's snapshot index (the two images L1
   installs). Predicate: `lane`, `arch`, `outcome`, `commit`, `run` (URL), `shards` (the domain legs), `images` (the
   per-arch digests installed), `inputs` (`assetSet`; `predecessor` and `backups` `null` for L1). **They are signed in
   the `snapshot` job, never in a lane leg**: a leg runs the commit's test code and gets no `id-token: write`; each leg
   hands its result over as an artifact, and the job that signs runs only when every leg is green.
6. **The tools image's baked major entry is a promotion input** (`release_assets.py --major-entry`, ADR 0085 decision
   10): the snapshot records it, and a promotion refuses when the current answer differs — it is baked into the
   image, so it cannot be rerun, only promoted from a newer snapshot. (Relevant from the first fix release of a
   previous major on.)
7. **The rerun of moved lanes is not built here.** While L1 is the only lane, no input can move (§ What exists). The
   promotion compares every recorded input with the current one and **refuses** on a difference, naming the lane;
   the rerun (calling a lane from `release.yml`) is built by #453's CR, with the first lane that can need it.
8. **`ci.yml` keeps `workflow_dispatch`, with no inputs**, as a by-hand snapshot of a commit that has none (a run that
   was superseded while pending, a docs-only tip). `release` and `unscheduled_major` leave `ci.yml` with the release
   path; `unscheduled_major` moves to the promotion.
9. **Off GitHub's hosted runners nothing publishes.** The version step's kind is `snapshot` only for a push or dispatch
   on `main`/`release/<major>.x` **and** `runner.environment == 'github-hosted'`; `none` on a pull request and in
   `run_pipeline.py`. Every write in `ci.yml` is gated on that one output. A push to a `release/**` branch that is not
   `release/<VERSION>.x` is red at the version step (a green push without a snapshot would contradict the rule).
10. **Write-once with an idempotent re-run.** The snapshot job refuses when `<v>-SNAPSHOT`, `sha-<commit>` or
    `snapshot-<v>` already name *another* digest or commit, and continues when they name this run's — so "re-run
    failed jobs" can finish a half-done snapshot, and a second build of the same commit (a dispatch after a push) is
    refused, not substituted.
11. **Retention groups by major and sweeps by reachability.** A branch publishes one major (`release/<major>.x`, and
    `main` its `VERSION`), so "per branch" is "per major". Kept: per major the 20 highest-minor snapshots not promoted
    (promoted ⇔ git tag `v<v>` exists), every promoted one, every version tagged with a release name, everything
    reachable from a kept tag (index children, buildx attestation manifests, cosign `sha256-<digest>…` signature and
    attestation tags), and every version younger than 24 h (a pipeline in flight has pushed staging tags it has not
    named yet). Everything else is deleted, with the `snapshot-<v>` tags of the dropped snapshots. The dispatch
    defaults to a dry run; the schedule deletes.
12. **The promotion deletes `snapshot-<v>` after publishing**, so the tag list keeps offering only promotable
    snapshots; retention never sees a promoted snapshot's tag.
13. **A promotion refuses a version that is not newer than every release of its major**, so `latest` and `<major>`
    never move backwards (ADR 0057: "release a later commit").
14. **Concurrency:** `ci.yml` keeps one group per ref for every event (the separate `veyq-publish` group goes: a
    dispatch is now a snapshot like a push); `release.yml` and the retention workflow share `veyq-release`, so a sweep
    never runs during a promotion.

## Phases

- [ ] **1 — The snapshot on every green push, attested, tagged; the identity split.**
  - `ci.yml` `version`: stamp `<major>.<minor>` always; outputs `major`, `version`, `kind` (`snapshot`|`none`, decision
    9); `publish_branch_tags`/`publish_latest` and the two dispatch inputs go.
  - `build`: registry login and push gated on `kind == 'snapshot'` (was the event); the digest hand-off as today.
    `stack`: the pull-by-digest path on `kind == 'snapshot'`, the local build only on `none`; each leg writes its
    result (decision 5) as an artifact. `release_assets.py` gains the asset-set function (decision 4) with a CLI
    flag that prints it for a produced directory; `run_stack_e2e.py` writes it into the leg's result.
  - `publish` becomes `snapshot` (same `needs` and success terms; permissions `contents: read`, `packages: write`,
    `id-token: write`): the write-once refusal (decision 10), the three indexes as `<v>-SNAPSHOT` and `sha-<commit>`,
    `cosign sign` of each, the chart as `<v>-SNAPSHOT` pushed and signed, then `cosign attest` per lane × arch on app
    and admin. The release half (refuse-release step, `release-assets-check`, `release-tag`, `gh release create`)
    is deleted; phase 3 rebuilds it in `release.yml`.
  - New job `snapshot-tag`: `needs: snapshot`, `permissions: contents: write` and nothing else, gated on `kind`;
    pushes `refs/tags/snapshot-<v>` at `github.sha` (no `--force`; a tag on another commit is red, on this one a no-op).
  - `veyqctl.py` `COSIGN_IDENTITY_REGEXP` and every identity on `verify-a-release.md` → `release.yml` (decision 1).
    Until phase 3, nothing verifies as a release — true, since none exists. `infra/README.md` gets the snapshot
    identity for the maintainer's own check of a snapshot.
  - `validate_release_identity.py`: assertion 2's scenarios (push and dispatch on `main` and `release/<n>.x` →
    `snapshot` with `<major>.<minor>`; pull request → `none`; `runner.environment` `local` → `none`; push on
    `release/foo`, dispatch on a feature branch, a shallow clone → fail); assertion 3 (every registry write, `git
    push` and `cosign` line gated on `kind == 'snapshot'`, found by what the step does as today; the only `git push`
    in `ci.yml` is in `snapshot-tag`, pushes `refs/tags/snapshot-…` and never `v…`; `snapshot-tag`'s permissions are
    exactly `contents: write`; no `gh release` in `ci.yml`); assertion 7 (the release identity accepts `release.yml`
    on both branch shapes and **rejects `ci.yml` on every ref**, beside today's rejects); assertions 8–10 renamed to
    the `snapshot` job; assertion 11 is retired from `ci.yml` (phase 3 re-homes it); a new assertion holds the
    attest step to the predicate type and to every architecture of the `stack` matrix. **Each new or changed
    assertion is bitten** (snapshot-and-restore, `changes/README.md` § Bite-proof): a write ungated, a `v` tag pushed
    from `snapshot-tag`, `packages: write` added to `snapshot-tag`, a `ci.yml` identity accepted, an attest line
    dropping `arm64`, a pull-request scenario turned `snapshot`.
  - Text ripple (decision 2): locator `git grep -n -- '-SNAPSHOT' -- ':!docs/inbox.md'` — `uc.admin.identify-instance-release`
    A5 and its coverage header comment, `contracts/api/instance.openapi.yaml` (the description; run
    `pnpm run generate:api`), the comments in `ReleaseIdentity.kt`, `ReleaseCheck.kt`, `ReleaseNoticePolicy.kt`,
    `VeyqConfig.kt`, `application.properties`, `Chart.yaml`, `tests/e2e/stack/docker-compose.e2e.yml`,
    `instance-mail.spec.ts`, `run_stack_e2e.py`. `instance_backup.py`'s grammar keeps the optional `-SNAPSHOT`
    (backups of such images exist). The measurements on `run/kubernetes.md`/`new-instance.mdx` naming
    `0.2265-SNAPSHOT` stay: they record what ran.
  - Docs: `release-and-deployment.md` § Publishing (a push publishes, a dispatch is a by-hand snapshot, the two
    identities) and its § Decided, not built; `infra/README.md` § Cutting a release (the snapshot half; the release
    commands point at phase 3 as not built); `how-veyq-is-tested.md` (stage map: `snapshot` and `snapshot-tag` jobs,
    their budgets; § How a release will be gated); `verify-a-release.md` (§ What the identity accepts, the snapshot
    paragraph); ADR 0091's Built line (decision 5's snapshot and attestations built; promotion not).
  - **Done when:** the guard loop green with every bite red-then-green; `/usr/bin/python3 scripts/release_assets.py`
    selftest of the asset-set function (same tree → same hash; another date, other image refs, `--unscheduled` →
    same hash; one recipe byte changed → another hash) green; `python3 scripts/run_pipeline.py --job version` on
    `main` prints `kind=none`; `./gradlew check` and `pnpm run check` (web, admin-web, site) green;
    `python3 src/tools/veyqctl_selftest.py` and `python3 src/tools/instance_backup_selftest.py` green.
  - **First run on GitHub (owner, after landing; added to #471):** the push's `build` legs push
    `build-<sha>-<arch>`; the `stack` legs log `docker pull …@sha256:` and no candidate build; `snapshot` publishes
    `0.<n>-SNAPSHOT` and `sha-<sha>` on the three images and the chart; `cosign verify` with the snapshot identity
    passes and with the release identity **fails** for the same digest; `cosign verify-attestation --type
    https://veyq.app/attestations/stack-lane/v1` with the snapshot identity prints one predicate per architecture for
    the app and the admin index; `snapshot-tag` creates `snapshot-0.<n>` and the Actions list shows no run started by
    it; "Re-run failed jobs" of `snapshot` after a deliberate cancel finishes green; a pull request's run (a Dependabot
    one) shows `snapshot` and `snapshot-tag` skipped and no `build-…` tag for its head commit.

- [ ] **2 — Retention.** New workflow `.github/workflows/snapshot-retention.yml`: daily `schedule` (off the hour) on
  `main` and `workflow_dispatch` (input `delete`, default `false` = dry run); concurrency `veyq-release`. Three jobs,
  split by permission: `plan` (`contents: read`, `packages: read`) runs `scripts/snapshot_retention.py` (decision 11)
  and prints both sets; `drop-tags` (`contents: write` only) deletes the dropped `snapshot-<v>` tags; `drop-versions`
  (`packages: write` only) deletes the dropped package versions of `veyq`, `veyq-admin`, `veyq-tools` and
  `charts/veyq`, owner endpoint by `github.event.repository.owner.type`. The two drop jobs run only on the schedule or
  `delete == true`. The script's decisions are pure functions over a listing; its selftest
  (`scripts/snapshot_retention_selftest.py`) feeds forged listings: 25 unpromoted + 3 promoted of one major, two
  majors, a release tag on an otherwise dropped index, a cosign `.sig`/`.att` tag of a kept and of a dropped digest, an
  untagged child of a kept index, a staging tag 2 h old, a listing page boundary.
  - Docs: `infra/README.md` § Cutting a release (retention, the dry run), `release-and-deployment.md` (retention
    built), the stage map's line on jobs that are no stage (its budget).
  - **Done when:** the selftest green, and bitten (keep set without reachability → a kept index loses its child;
    promoted check removed → a promoted snapshot is dropped); the guard loop green (`validate_pipeline_shape.py` holds
    the budgets, `validate_action_pins.py` and `validate_workflow_paths.py` the new file).
  - **First run on GitHub (owner):** a dispatch with `delete=false` lists every package version with its verdict,
    which the owner compares with GHCR's package page; the first deletion (the first scheduled run once more than 20
    snapshots of a major exist, or a dispatch with `delete=true`) answers the open GitHub question. **If GitHub
    refuses the token (403):** the deletion stops, nothing else changes, and how to authorise it (a classic PAT with
    `delete:packages` as a secret, or pruning by hand) goes to the owner — it is a credential decision.

- [ ] **3 — The promotion (blocked on § Open question).** New workflow `.github/workflows/release.yml`, concurrency
  `veyq-release`; refuses unless its ref is `main` or `release/<major>.x`.
  - Job `check` (`contents: write` — listing a draft needs push access, and a refusal writes into it — `packages:
    read`): finds the draft; then `scripts/promote_snapshot.py` (pure checks, selftest
    `scripts/promote_snapshot_selftest.py`) refuses unless: the tag is `snapshot-<major>.<minor>`; the tree at its
    commit counts that version (`release_assets.tree_release`, on a full-history checkout of the tag); that commit is
    an ancestor of the run's ref and its major is the ref's; no `v<major>.<m>` with `m ≥ minor` exists (decision 13);
    no artifact named `<v>` exists (today's refusal step, moved); `sha-<commit>` and `<v>-SNAPSHOT` name the same index
    per image, verified against the **snapshot** identity; for every lane that exists (today L1) and every
    architecture of `ci.yml`'s `stack` matrix, one attestation verifies, `outcome` passed, its `images` equal that
    index's children; every recorded input equals the current one — `assetSet` against the set produced again from
    the snapshot's commit, the major entry (decision 6) — else refused naming the lane (decision 7).
  - Job `promote` (`needs: check`; `contents: write`, `packages: write`, `id-token: write`): pushes `v<v>` at the
    commit; gives each image index `<v>`, `<major>`, and `latest` when the ref is `main`, and asserts each new tag
    resolves to the snapshot's index digest; signs the three indexes with the **release** identity; packages the chart
    from the snapshot's commit as `<v>`, pushes and signs it (decision 3); produces the asset set from the snapshot's
    commit with the published `tag@digest` references, checks its hash against the attested one, signs `manifest.json`
    and `veyqctl` (`sign-blob --bundle`); `PATCH`es the draft to `tag_name v<v>`, `target_commitish <commit>`, title
    `veyq <v>`; uploads every asset; **publishes it last** (`draft: false`, `make_latest` only from `main`); then
    deletes `snapshot-<v>` (decision 12). No build step: no `docker build`, no build-push action in the file.
  - A failure before publication appends the reason and the run's URL to the draft's notes (`if: failure()`), leaves
    it a draft, and the run is red. **If GitHub refuses to retag a draft** (undocumented either way): the fallback is
    a new draft on `v<v>` carrying the owner's notes, the owner's draft deleted after publication — same outcome,
    no question back.
  - `validate_release_identity.py`: assertion 11 re-homed in `release.yml` (one publication, last; every `cosign sign`
    and `sign-blob` and every asset upload before it; no release write after it but the snapshot tag's deletion; the
    producer's reserved bundle names); assertion 7 holds that the release identity's file exists and is the only file
    that creates or publishes a GitHub Release; a new assertion holds `release.yml` free of any build and of `push`
    and `pull_request` triggers. Bitten each (an upload after publication, a `docker build` added, a `push:` trigger
    added, publication before the chart's signature).
  - Docs: ADR 0091 decision 5 (the owner's answer replaces "`release: created`"; Built line), ADR 0057 header if a
    § is affected, `release-and-deployment.md` (promotion built; rerun not), `infra/README.md` § Cutting a release (the
    release half and the train commands with the new start), `verify-a-release.md` § How far this is proven,
    `how-veyq-is-tested.md` § How a release will be gated (→ how it is gated), #460's row deleted once the first-run
    checklist below has run, #471's release items replaced by a pointer to it.
  - **Done when:** the selftest green with every refusal reached by a forged input; the guard loop green with every
    bite red-then-green; `pnpm run check` in `site/` green.
  - **First run on GitHub (owner):** immutable releases switched on first (ADR 0096, #471). One refused promotion on
    purpose (a draft on a tag that is not `snapshot-…`): the draft stays a draft, its notes carry the reason, nothing
    in GHCR changed. Then the real one: `v0.<n>` exists at the snapshot's commit; `docker buildx imagetools inspect`
    shows `0.<n>`, `0`, `latest` and `0.<n>-SNAPSHOT` on one digest per image; `cosign verify` with the release
    identity passes for the three images and the chart; the release is published, immutable, with every asset and
    both bundles; `snapshot-0.<n>` is gone; the Actions list shows no run started by the publication; `veyqctl
    install` from GitHub verifies and installs it on a throwaway host.

## Definition of done

Each phase's Done-when, and its first GitHub run observed by the owner (a row that is built but owes a proof is not
done). The guards hold every rule above that a file can show; what only a run shows is the three first-run
checklists. No `satisfies:` use case: the work is a workflow, which is no coverage surface (the coverage header of
`uc.admin.identify-instance-release` says why), and the one criterion text it touches, A5, is a scenario without a
coverage row. The published pages listed per phase are updated in that phase.

## Retrospective

(filled at implementation)
