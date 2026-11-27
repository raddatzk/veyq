---
id: cr-stack-lane-l3-backup-archive
title: Lane L3 and the backup archive — every release archived once by its own seeder, one backup per schema state restored into the candidate, the jar-level upgrade proof retired
status: in-progress
satisfies: ["uc.admin.back-up-and-restore-instance", "uc.admin.install-and-upgrade-instance"]
adrs: ["0091", "0096", "0085", "0057", "0056", "0090"]
invariants:
  - an archived backup holds test data only, written by the release's own seeder on its own published images
  - the selection of backups L3 restores is derived from the tags and their pin files, never listed
  - a backup the selection names that is not archived is red, never skipped
  - the jar-level upgrade proof is removed only in the last phase, after L2 and L3 have gated a push green
---

## Goal

Build lane L3 and the backup archive of [ADR 0091](../docs/decisions/0091-one-pipeline-per-commit-a-release-promotes-a-tested-snapshot.md)
as decision 6's amendment of 2026-11-26 decides them (inbox #461), then retire the jar-level upgrade proof (decision 7).

- **The archive:** a workflow of its own writes, for one release, a backup of an instance that release's own seeder
  filled: published release installed and verified, seeded, `veyqctl backup` with its object pool and kept-apart key,
  published as a signed OCI artifact beside the release's images. `release.yml` calls it after a promotion; a dispatch
  with a tag runs it for a release already out. Every release is archived. The eighteen released before it are
  written once, by dispatch.
- **L3:** on every push, on amd64 alone (ADR 0091 decision 6, amended 2026-11-27), the candidate restores one archived backup per schema state of its major
  (the oldest release of each state) plus the major entry's, each into a fresh `veyqctl install` of the candidate with
  `veyqctl restore --objects-from`, and reads it with the candidate's reader and browser spec.
- **Then** the `upgrade-proof` job goes, and every doc that names it says what proves the upgrade now.

**Builds on lane L2** (built, attested since the Publish of `d5ce223da`): L3 reuses its building block —
`scripts/stack_lane.py` and `scripts/run_stack_upgrade.py`'s install-and-seed stages (a published release installed
and seeded on the stack, the suites in a container on the stack's networks), the browser spec
`tests/e2e/tests/upgrade/upgrade-in-place.spec.ts`, and the predicate's `inputs` beside `predecessor`. A done-when
below that only GitHub can show (a dispatch, a registry listing, a push's L3 run) is met after the owner's push, per
`changes/README.md` § the done-when in two halves.

## Context & constraints

### Measured substrate (refine spikes, 2026-11-26, worktree at `50c0ae3ca` = `main`)

| Fact | Locator |
|---|---|
| No backup or OCI artifact is published anywhere today; release assets are recipe, tools and manifest | `git grep -n -E "oras\|backup" -- .github/workflows/release.yml .github/workflows/publish.yml scripts/promote.py`; `gh release view v1.318 --json assets` |
| No existing run holds a capture of any release: L1 takes none; the jar-level proof's `upgrade-proof-out-*` and `web-e2e`'s are kept 7 days | `grep -n -i backup scripts/run_stack_e2e.py`; checks.yml `retention-days` of those uploads; `gh api repos/raddatzk/veyq/actions/runs/<run>/artifacts` for the 18 release commits' Checks runs |
| `capture --objects-to`, `restore --objects-from` exist in the backup tool and are passed on by `veyqctl backup` / `restore` | `grep -n "objects-to\|objects-from" src/tools/instance_backup.py src/tools/veyqctl.py` |
| `veyqctl restore DIR --domain D (--key-from F \| --without-key) [--objects-from POOL]` needs an installed, empty instance; `--domain` is the capture's tenant domain | `cmd_restore` in `src/tools/veyqctl.py` |
| The seeder's tenant lives on the bootstrap domain (`VEYQ_HOST` = `VEYQ_DOMAIN`) | `src/web/src/test/e2eBootstrap.ts` `birthInstance` |
| The schema state of a release is its highest migration in its own pin file; on 2026-11-26: `V202611070833` (v0.2480, v1.0 – v1.152), `V202611122155` (v1.168 – v1.247), `V202611160756` (v1.248, v1.249), `V202611221402` (v1.260 – v1.318) | `for t in $(git tag -l 'v*' \| sort -V); do git show "${t}:scripts/applied_migrations.sha256" \| grep -oE 'V[0-9]{12}' \| sort \| tail -1; done` |
| One derivation of the major entry exists and is shared by the chart check, the tools-image stamp and the proof | `git grep -n "major_entry_ref" -- scripts .github` |
| Checks has no `id-token`; Publish signs under `publish.yml`'s identity, the promotion under `release.yml`'s | `git grep -n "id-token" -- .github/workflows` |
| What retiring the jar-level proof touches | `git grep -n -I -E 'upgrade-proof\|upgrade proof\|--upgrade-major-entry\|run_web_e2e\.py --upgrade\|upgrade_proof_selftest' -- ':!docs/inbox.md'` |

## Decisions (technical, settled here)

1. **The archive workflow is `archive.yml`**, a `workflow_call` that `release.yml` calls after its `publish` job, and a
   `workflow_dispatch` with a required `tag` input (a `v<major>.<minor>` tag, refused otherwise). It runs on amd64: a
   backup is the database dump, the object pool and the key, none of it per architecture. It reuses L2's building block
   to install and seed the published release, then `veyqctl backup --objects-to --key-into`, then pushes one OCI
   artifact `ghcr.io/<owner>/veyq-backups:<major>.<minor>` and signs it keylessly; the identity it signs under is
   `archive.yml`'s, pinned in L3 as `veyqctl`'s release identity is pinned there. Written once: a tag that already
   exists is a notice and nothing written, as Publish treats a commit with a `snapshot-` tag. Its failure never touches
   the immutable release; a re-run writes it.
2. **The artifact's contents and manifest.** The capture directory, the object pool, the kept-apart key, and the
   seeder's record, plus a small `archive.json`: release, its highest migration, the domain the capture's tenant lives
   on, the seeder's record version. It is public: test data only, every secret in it (the throwaway owner's
   credentials, the key) a test value of a throwaway instance. The domain is read from `archive.json` by L3, never
   assumed.
3. **The OCI tool** is the one the implementer finds maintained and vendor-neutral (oras is the reference candidate;
   cosign's own `attach` is deprecated upstream — check before choosing, CLAUDE.md's no-deprecated rule). Pinned by
   version and checksum as every tool the workflows install.
4. **The selection is one function** next to `major_entry_ref()` (same module, same tag input `git tag --list 'v*'`,
   not `--merged`, so fix releases on `release/<N>.x` count): the releases of the candidate's major grouped by the
   highest migration of their own pin file, the oldest of each group, plus the major entry. Its self-test holds the
   2026-11-26 grouping as a fixture case and a case where a fix release on the old branch moves the entry.
5. **L3's jobs.** `Stack lane L3 (<arch>, <shard>)` on push only, amd64 alone (a data-path lane: `validate_release_identity.py`'s
   `EVERY_ARCH_LANES` leaves it out, as L2); the selection split over shards
   so a shard fits decision 1's 15-minute budget, each shard restoring its backups one after another into a fresh
   install each. Per backup: pull, `cosign verify` under `archive.yml`'s identity, fresh `veyqctl install
   --unsigned-local` of the candidate with the archived domain, `veyqctl restore --objects-from --key-from`, the
   candidate's reader, the browser spec. A selected backup that does not exist or does not verify is red, naming the
   release.
6. **Attestation and promotion.** `lane-result` and `inputs.backups` carry the list of archived releases with their
   artifact digests; `checks_verdict` requires the L3 jobs; `promote.py` adds L3 and compares the list with the one
   the selection yields now — equal passes, different is refused naming the difference.
7. **Backfill is a phase, not a script.** The eighteen releases are archived by dispatching `archive.yml` once per tag,
   oldest first. That each tag's own seeder is byte-identical to today's is measured (L2's substrate); a tag whose
   seeder fails against its own images is handed back, never patched.
8. **The retirement keeps the derivations.** The `upgrade-proof` job, its `green` need and `upgrade_proof_selftest.py`'s
   CI step go; `default_predecessor_ref`, `major_entry_ref` and `major_entry_proof_ref` stay (the chart check and the
   tools-image stamp read them), and so does the ordinary web-e2e run of the seed/read pair on one build — it is what
   keeps the pair honest between releases. `run_web_e2e.py --upgrade` and `--upgrade-major-entry` stay as a local
   tool or go, the implementer's call by what still calls them; ADR 0057 §7 names L2 and L3.

## Inventory (locators — run at implement time)

| Surface | Locator |
|---|---|
| Release workflow's last job and its permissions | `grep -nE "^  [a-z-]+:$\|permissions\|id-token" .github/workflows/release.yml` |
| Pinned tool installs to copy the pattern from | `git grep -n -E "sha256sum -c\|checksum" -- .github/workflows` |
| Major-entry derivation and its self-test | `git grep -n -E "def major_entry\|major_entry_ref" -- scripts` |
| Lane records, predicates, verdicts | `grep -nE "LANES\|backups\|def (predicates\|checks_verdict\|lane_verdicts)" scripts/snapshot.py scripts/promote.py` |
| The jar-level proof and every reader of it | the last row of the substrate table |
| Docs naming L3 or the archive as not built | `git grep -n -E "L3\|backup archive\|archived backup" -- docs/decisions docs/crosscutting infra/README.md site/src/content/docs` |
| Guards over the workflow set and identities | `git grep -n -E "release.yml\|publish.yml" -- scripts/validate_release_identity.py` |

## Delivery — two pull requests, the owner's dispatches between them (owner 2026-11-27)

`workflow_dispatch` resolves against the default branch, so `archive.yml` cannot be dispatched before it is on `main`;
and L3 gating on an empty archive would be red on every push. So:

- **Pull request 1 = phase 1**: `archive.yml`, `release.yml`'s call of it, the archive's script and self-test, the new
  identity in `validate_release_identity.py`. Nothing reads the archive yet; nothing gates on it.
- **Between them, the owner's**: merging pull request 1, then dispatching `archive.yml` for `v1.318` (phase 1's
  observed half) and for every other `v` tag (phase 2). The implementer dispatches nothing and writes to no registry
  but a local one. If GHCR creates `veyq-backups` private, making it public is the owner's too (the implementer
  measures what a first push from a workflow does, or says it could not).
- **Pull request 2 = phases 3 and 4**, started only once phase 2 is observed.

Each done-when below is written in two halves (`changes/README.md` § the done-when in two halves): **locally**, what
the implementer proves before handing over; **observed**, what only GitHub shows after the owner's step.

## Phases

### Phase 1 — the archive workflow, run for one release

Decisions 1 to 3. **Locally:** the archive's script, run on this Mac against the published `v1.318`, writes the capture,
pool, key, record and `archive.json`, and pushes them as one OCI artifact to a local registry (a pinned
`registry:2`-class image), from which a pull gives back byte-identical files; a second run for the same tag against
that registry writes nothing and says so; `actionlint`-class checks of the new workflow and the pipeline-shape guard
are green; `validate_release_identity.py` knows the new identity and is bitten by a workflow that signs under it without
being listed. **Observed** (after the owner's merge and dispatch): a dispatch of `archive.yml` for `v1.318` publishes `veyq-backups:1.318`, its `cosign
verify` under `archive.yml`'s identity passes and under `release.yml`'s fails (both outputs in the PR); a second
dispatch for `v1.318` writes nothing and says so; `validate_release_identity.py` knows the new identity and is bitten
by a workflow that signs under it without being listed.

### Phase 2 — backfill

Decision 7. **The owner's**, after pull request 1: a dispatch for every release, oldest first. **Observed:** `veyq-backups` holds one artifact per `v` tag, each
verifying; the run links are in pull request 2. A tag whose run is red is handed back, never patched (decision 7).

### Phase 3 — the selection and lane L3

Decisions 4 to 6, in pull request 2. **Locally:** the bites below and the self-tests. **Observed:** L3 is green on pull request 2's merge push on amd64, restoring exactly the selection (five
backups on 2026-11-26's tags: v0.2480, v1.0, v1.168, v1.248, v1.260 — v0.2480 as the entry, v1.0 as the oldest of
the first state); **bites:** a selection whose newest state's oldest release is deleted from the registry (or its tag
pointed at a missing artifact in a test registry) is red naming it; a candidate whose reader expects one more item is
red per backup; an archived artifact signed under another identity is refused; the selection's self-test fails with the
grouping step removed. `promote.py`'s self-test covers a moved backup list.

### Phase 4 — the jar-level proof retires, the docs move

Decision 8. Done when: checks.yml has no `upgrade-proof` job and `green` no need of it; `shards.test.mjs` green (its
slice was re-anchored in the L2 CR); every hit of the retirement locator is updated or justified as history; ADR 0091's
`Built:` names L2, L3 and the archive; ADR 0085's and ADR 0096's notes on the archive holding the major entry say it
does; the site's *How veyq is tested* and the Run area's upgrade page say what proves an upgrade now; inbox #461 is
deleted, and #436's mention of the disabled major-entry proof is updated.

## Definition of done

- Every release, the eighteen before this CR included, has a signed archived backup written by its own seeder.
- L3 gates every push on amd64 from one backup per schema state plus the major entry; the promotion
  refuses a snapshot whose backup list moved.
- The jar-level upgrade proof is gone and nothing that read it is left stale.
- Every bite went red and was reverted; the scripts' self-tests and the trust-chain guards green.
- The PR's body carries `## Release note`: none for users; for operators one line that every release is now restored
  from a backup of every older schema state of its major before it is released.

## Retrospective

### Phase 1 — decision log (implementer, 2026-11-27)

- **The OCI tool is oras, v1.4.0** (decision 3). cosign 3.1.3 itself prints, for `attach` and `upload`, "deprecated, … will
  be removed in v4.0.0 … please use oras" (measured: `cosign attach --help`, `cosign upload --help`); oras is the CNCF
  project, Apache-2.0, not archived, v1.4.0 released 2026-11-17 (`gh api repos/oras-project/oras`, its releases), past
  renovate.json5's 3-day age. Pinned as Helm is: `ORAS_VERSION`/`ORAS_SHA256` in archive.yml's `env:`, the tarball
  checked before it is extracted; the SHA-256 agreed three ways on 2026-11-27 (the release's checksums file, the
  tarball's hash, GitHub's asset digest). Pinning it "as the existing ones are" meant a Renovate custom manager (the
  `pins` job is red without one, bitten below) and `pin_follow.py oras` as its `postUpgradeTask` (three sources, the
  same shape as `helm`), with renovate.yml's allowed command.
- **The artifact's form** (decision 2): an OCI image manifest in the 1.1 artifact form — artifact type
  `application/vnd.veyq.backup.v1`, the empty config, two layers: `archive.json` and ONE tar of `capture/`, `pool/`,
  `config-secret.key`, `upgrade-record.json`. The tar is deterministic (sorted, mtime 0, owner 0, each file's own mode)
  and the manifest is written by `scripts/archive_backup.py` with no timestamp, so the digest is a function of the
  files; oras is only the transport (`blob push`, `manifest push` by digest, `tag`, `blob fetch`). `archive.json` also
  carries every file's SHA-256 and the release's two image digests, so a reader checks what it unpacked.
- **"Written once" is the tag, and the tag is the last write.** Blobs and manifest are pushed by digest, untagged; the
  workflow signs the digest, verifies it under the archive identity and refuses it under the release's, and only then
  `archive_backup.py publish` sets the tag — reading it again first, and reading it back after. A failure before the
  tag leaves an untagged manifest no reader names; a re-run writes again.
- **How a registry's answer is read.** `not found` (snapshot.py's words) is absent; `denied` is unknown and the run goes
  on, because GHCR answers an anonymous read of a repository that does not exist with `denied` (measured:
  `oras manifest fetch ghcr.io/raddatzk/veyq-backups:1.318` → "denied: requested access to the resource is denied",
  while `…/veyq:does-not-exist-0` → "not found"); `publish` requires a definite answer. Anything else is red.
- **The run's ref is gated**: `check` and `archive` refuse a ref other than `main` or `release/<major>.x` before any
  write — a dispatch from another branch would sign an identity L3 refuses, under a tag that is written once.
- **The app's truststore is written by the release's own app image**, pulled by the digest its manifest names after
  the release's own `veyqctl verify --release <r>` (L2 uses the candidate's; the archive has none). Every release's
  `veyqctl` has `verify --release` (measured: `git show <tag>:src/tools/veyqctl.py` for v0.2480 and v1.318).
- **`archive.json`'s domain is read from the capture** (`manifest.json`'s `domains`, exactly one), and its highest
  migration from the tag's own pin file, cross-checked against the capture's Flyway `schemaVersion` (red if they
  differ: the schema state L3's selection groups by is then the one the archive holds).
- **The archive identity is stated in `scripts/archive_backup.py`** (`IDENTITY_REGEXP`, printed by `identity`), where
  L3 will read it; archive.yml reads it from there and states none of its own.
- **`validate_release_identity.py` assertion 18** (new): every workflow file that runs `cosign sign`/`sign-blob`/`attest`
  is one of three listed identities and each listed one signs; a `uses:` job may hand `id-token: write` only to a listed
  workflow; the archive identity accepts archive.yml on the publishing branches and rejects 37 others (release.yml and
  publish.yml named apart), and veyqctl's identity rejects it; archive.yml's triggers, permissions, single job,
  concurrency, ref hand-over, cosign pin and write order. `id-token: write` alone is not counted as signing:
  site.yml's Pages deploy holds it (the first draft counted it and was red there). Assertion 14 holds release.yml's sixth
  job (`archive`); assertions 15 and 16 read archive.yml.
- **`pull` is built now** (it was phase 3's reader): the local round trip needs it, and it is the one place the checks
  of what an archive must be live.
- **release.yml's `archive` job** runs on `always() && needs.publish.outputs.published == 'true'`, as `channels`; its
  failure leaves the release as it is. archive.yml's budget is 20 minutes (the site's no-stage line says so).
- **The docs say only what is true after pull request 1**: ADR 0091's `Built:`, release-and-deployment.md and the
  site's *How veyq is tested* name the archive's workflow as built and no release as archived; the rest is phase 4's.

### Phase 1 — handed back

- **`veyqctl backup --objects-to <directory>` fails on Docker Desktop for macOS, in every release since 2026-10-21's
  early copy** (v0.2480 and v1.0 carry `copy_objects_early`). Measured: the first local run was red at `backup` —
  rclone exit 6, "Timestamp mismatch between immutable objects … immutable file modified" on every key the hot early
  copy had written. Cause, measured with the pinned rclone image: in a bind-mounted directory Docker Desktop keeps the
  mtime rclone sets only to the whole second (`17:19:07.000000000` beside the source's `.105268669`), so the cold
  `copy --immutable` takes each pooled object for a modified one; on the Fedora VM (podman, native bind mount) the
  mtime keeps its nanoseconds and the second copy passes. So CI (a Linux runner) is expected unaffected (inferred, not
  measured on GitHub), and a Server Owner on a Mac with a directory pool cannot back up. Not patched here (a tools
  change, and outside this CR). Recommendation: an inbox row — filed as inbox #870; the fix is likely `--modify-window 1s` on the directory
  pool's copies in `instance_backup.py` (rclone's own lever for coarse-mtime filesystems), proved by the same two-copy
  case. The local run used a scratch `docker` shim adding `RCLONE_MODIFY_WINDOW=1s` to the rclone container only —
  never in the repository.
- **`veyq-backups` will most likely be created private.** GitHub's docs (read, not measured): "When you first publish
  a package that is scoped to your personal account, the default visibility is private", and a linked package inherits
  "the access permissions (but not the visibility)" of its repository. Decision 2 says the archive is public, so making
  it public after the first dispatch is the owner's step (below). The first push from a workflow could not be measured
  here (no registry writes). **Superseded by what the first dispatch showed:** GHCR created `veyq-backups` public, no
  owner step taken (§ Phases 1 and 2 — observed).

### Phases 1 and 2 — observed (the owner's dispatches, 2026-11-27)

- **Phase 1's observed half holds.** The dispatch for `v1.318` ([run 46047675181](https://github.com/raddatzk/veyq/actions/runs/46047675181))
  published `veyq-backups:1.318`; its verify step passed under the archive identity and was refused under release.yml's.
  The second dispatch ([run 46048381605](https://github.com/raddatzk/veyq/actions/runs/46048381605)) wrote nothing and said so.
- **The package is public, by inheritance, without an owner step.** Measured: an anonymous token
  (`curl 'https://ghcr.io/token?scope=repository:raddatzk/veyq-backups:pull'`) lists the tags and fetches every
  manifest (HTTP 200). So L3 pulls without a login — but it may log in with `GITHUB_TOKEN` (`packages: read`) all the
  same; the implementer's call.
- **Phase 2 holds: every `v` tag is archived.** One dispatch per tag, oldest first; every run green, v1.0 on its second
  attempt (the first failed at a transient `docker pull` of the Playwright image, before anything was written). Every
  `archive.json` (fetched anonymously) names 14 files, domain `veyq.test`, record version 1, and the highest migration
  the substrate table names for that tag — the four schema states exactly.

  | Tag | Run | Manifest digest |
  |---|---|---|
  | v0.2480 | [46048626398](https://github.com/raddatzk/veyq/actions/runs/46048626398) | `sha256:0af17dbf60eebb3984bc5a0d5efa06aefbfd6c78b8e4bf150b72dbfdc17fcfff` |
  | v1.0 | [46048630362](https://github.com/raddatzk/veyq/actions/runs/46048630362) (attempt 2) | `sha256:5aab38485b277dc2e8ad9a61633f0d1800643ffaaf8f0a62d3f1f789edebb576` |
  | v1.61 | [46048633927](https://github.com/raddatzk/veyq/actions/runs/46048633927) | `sha256:77aa3a56753679512ee5db1d6f01e13e89e2590459faec6c6412f383bfbd900a` |
  | v1.66 | [46048637388](https://github.com/raddatzk/veyq/actions/runs/46048637388) | `sha256:99fb85f2c5d4e6b135ddc937bb464f1e235ab04c83c2138ac833aa83e5b8490e` |
  | v1.82 | [46048640861](https://github.com/raddatzk/veyq/actions/runs/46048640861) | `sha256:690c3808536d49854629b594401750a4643f1985c22aba512ca1a7cf56927add` |
  | v1.84 | [46048644589](https://github.com/raddatzk/veyq/actions/runs/46048644589) | `sha256:40393e597e2762d7e0e190ddcebdecf9d4e18184842a3925ebfe4577f5bf4cd5` |
  | v1.133 | [46048648107](https://github.com/raddatzk/veyq/actions/runs/46048648107) | `sha256:82a0ffba54164b746d2d68e7fe28dde824677aa1aeee0d5f9e134048a98beaa4` |
  | v1.152 | [46048651853](https://github.com/raddatzk/veyq/actions/runs/46048651853) | `sha256:cc0b020389c32699cab39f42bb5f9c4cdf8a846e1e4bad22b04093fd4b77e6fc` |
  | v1.168 | [46048655057](https://github.com/raddatzk/veyq/actions/runs/46048655057) | `sha256:09a207fff9cc3fb0591069a7c21a5a637aa8d94fa5ded31206d057c84e1c2020` |
  | v1.215 | [46048658475](https://github.com/raddatzk/veyq/actions/runs/46048658475) | `sha256:18be801232e1f3181ca1f1c592b3e09e01efe16d1b34ee1e42009f812781ea09` |
  | v1.223 | [46048662199](https://github.com/raddatzk/veyq/actions/runs/46048662199) | `sha256:6451b1aed143bacc6e7c0be7709a3649f25e3c80cc4cce2d297171d89d250359` |
  | v1.243 | [46048666366](https://github.com/raddatzk/veyq/actions/runs/46048666366) | `sha256:fb24d91ae82ae58fbd26438e1a45cd1c096b2d623b17eac623c53827ca22b159` |
  | v1.247 | [46048670631](https://github.com/raddatzk/veyq/actions/runs/46048670631) | `sha256:8a0bca6e686ea26b70d20d80ed0fad9abde0b4761b753569a9e3f64e9f942101` |
  | v1.248 | [46048674597](https://github.com/raddatzk/veyq/actions/runs/46048674597) | `sha256:da5e97106947073b78ac274acbbcb124b37706add57a52c0addc5366f1abeecb` |
  | v1.249 | [46048678699](https://github.com/raddatzk/veyq/actions/runs/46048678699) | `sha256:8bc3a6948c2bc24fa04c25246ab2ff2807f7300b98381d82e7247593f4d403ca` |
  | v1.260 | [46048683166](https://github.com/raddatzk/veyq/actions/runs/46048683166) | `sha256:f6a9c476825ac40662a45a4184b1a0ff6ffae512bf09dde4408d53ff198d8d97` |
  | v1.312 | [46048687016](https://github.com/raddatzk/veyq/actions/runs/46048687016) | `sha256:4090c3c6ff364a0d49b6cdc283622599d6210e1a58e64582c8f31e9236d24181` |
  | v1.318 | [46047675181](https://github.com/raddatzk/veyq/actions/runs/46047675181) | `sha256:270a4345647d353642a67514dc0830d67edf0baf3de0445c55248821cc58a008` |

  Digests measured with an anonymous `HEAD /v2/raddatzk/veyq-backups/manifests/<major>.<minor>` (`Docker-Content-Digest`).
- **The repository's tag list holds more than the archives:** beside each `<major>.<minor>` stands a `sha256-<hex>` tag —
  cosign's signature, stored by the tag scheme. A reader names an archive by `<major>.<minor>`, never by listing tags.

### Phase 1 — technical observations

- The local run against the published v1.318 (`archive --tag v1.318 --registry localhost:15500/l3p1 --plain-http`,
  registry `registry:3.0.0@sha256:6c5666b8…`): green in 112 s (verify 11 s, install 27 s, start tree 44 s, seed 14 s,
  backup 11 s, assemble and push 0.2 s); 14 files, a 1,013,760-byte tar, `archive.json` with domain `veyq.test`,
  highest migration `V202611221402`, record version 1, both image digests. Before `publish` the repository had no tag;
  after it `1.318` named the pushed digest. `pull` gave back `archive.json`, the tar and the manifest byte-identical
  (`cmp`) and every unpacked file equal to the run's capture, pool, key and seeder record (`diff -r`, `cmp`). A second
  `archive` for v1.318: 0.09 s, the notice, `written=false`, no run directory. `publish` with another digest: the
  notice, nothing tagged. `check` from `refs/heads/feature`: red, nothing read.
- L2's building block held unchanged: the archive imports `run_stack_upgrade`'s start release, override, install values,
  install, start tree, seed and `predecessor_of`, and `stack_lane`'s stages; nothing of L2 was edited.
- Bites, each red and restored (bytes compared, `git status` identical): assertion 18 six ways (an unlisted workflow that
  signs; archive.yml dropped from the list; the identity widened to `(archive|release)\.yml`; the tag set before the
  signature; release.yml's `archive` ungated; the archive step without the run's ref); the archive self-test four ways
  (a tar entry keeping its file's mtime, `denied` read as anything but unknown, an unpack that follows a path outside,
  a pull that skips a layer's digest); `pin_follow` with GitHub's digest dropped from the three-way check; and the
  `pins` job (Renovate 44.262.1's extract-only run on a clone of the commit, then `pin_inventory.py`) without the oras
  manager: "ORAS_VERSION: v1.4.0: no Renovate manager extracts it". With it: 332 pins, every one reached.
- Proof on the committed tree (`e0d7d9534`): all 71 guards of the CI glob green; `archive_backup_selftest.py`,
  `pin_follow_selftest.py` (68 checks), `snapshot`, `promote`, `staging_retention`, `pin_inventory` and `release_notes`
  self-tests green; `actionlint` green on archive.yml, release.yml and renovate.yml (checks.yml's one finding, SC2129 at
  its `version` step, is on `main` too); the site's `pnpm run check` green (0 errors, 217 pages); `validate_guard_gaps.py`
  in full (a validator changed): 104 pinned cases hold.
- Not reproduced locally: the workflow itself (GitHub's runner, the GITHUB_TOKEN push, keyless signing, `cosign verify`
  of an OCI artifact in GHCR). That `job_workflow_ref` names archive.yml when release.yml calls it is GitHub's and
  Fulcio's documented behaviour, remembered; the workflow's verify step is what the observed half shows.

### Phase 1 — process observations

- The CR's local done-when assumed `veyqctl backup --objects-to` runs on this Mac; it does not (above). The refine ran
  L2's install-and-seed but not the one new command phase 1 hangs on — § *run the lever's first invocation at refine*
  would have found it in a minute. Next time: a refine that adds a command to a building block runs that command once.
- My misses: a self-test case written with `or True` (removed before it ran green); two of the self-test's first bites
  stayed green — `info.mtime = 0` restates `TarInfo`'s default, so its removal was no lever (re-bitten with a real mtime),
  and a `denied` case that raised instead of naming itself (rewritten to report the state it read); assertion 18's
  first draft counted `id-token: write` as signing and was red on site.yml's Pages deploy.

### Phase 3 — decision log (implementer, 2026-11-28)

- **The selection is `run_web_e2e.backup_selection` / `backup_selection_ref`**, beside `major_entry` / `major_entry_ref`
  (decision 4): the pure function takes the major, every tag and `highest_of(tag)`; the impure one feeds it `git tag
  --list 'v*'` and `git show refs/tags/<tag>:scripts/applied_migrations.sha256` per tag, and is empty while the pin file
  says `release: none` (the switch `major_entry_ref` makes). A tag without a pin file, or whose pin file names no
  migration, is refused naming it — never grouped as "empty". On today's tags it yields exactly v0.2480, v1.0, v1.168,
  v1.248, v1.260 (measured: `backup_selection_ref()`, and `run_stack_restore.py --resolve`).
- **Its self-test is a new file, `scripts/stack_restore_selftest.py`** (in the `guards` job beside the archive's), not
  `upgrade_proof_selftest.py`: phase 4 retires that file's CI step, and lane L3's pure parts (the shards, the facts it
  checks of an archive, the fetch order, a backup's red being its own) belong with the lane. It holds the 2026-11-26
  grouping as a fixture (the eighteen tags with their states, from the substrate table), a fix release `v0.2481` moving
  the entry, version order, and a fabricated git history (each tag's own pin file; a fix on `release/1.x` main never
  reaches is major 2's entry; a tag without a pin file refused; `release: none` empty).
- **The runner is `scripts/run_stack_restore.py`**, L2's sibling, importing L2's building block (`override`, `read`,
  `browser`, `candidate_release`, `candidate_tree`, the summary's `STEPS`/`READINGS`) and `archive_backup`'s reference,
  `lookup`, `pull` and identity; nothing of L2 or the archive was edited. One project and one run sub-directory per
  backup (`<tag>/`), torn down before the next installs on ports 80/443; the host check after each.
- **Shards are static, three, round-robin in version order**, the count read from the `stack-restore` matrix
  (`snapshot.restore_shards`, one statement of it) by the runner, the publish's predicates and the guard. Rejected: a
  dynamic matrix from a plan job — `checks-run`, `predicates` and assertion 13 read the shards from the file, and a
  computed matrix would have to be derived again at Publish time from tags that may have moved since. A shard
  carrying more than `MAX_PER_SHARD` (4) is red before anything runs, naming the matrix to widen: an estimate (local
  41–50 s per backup, CI unmeasured) with a factor of about two in hand; the first push's timings replace it.
- **Per backup, the order is lookup → `cosign verify` of the digest the tag names → `pull` by that digest**, so the
  bytes read are the verified ones; `absent` is red naming the release and the dispatch, `unknown` (the registry will
  not say) is red too — never a skip (decision 5). The identity is `archive_backup.OIDC_ISSUER`/`IDENTITY_REGEXP`,
  imported, never restated.
- **The domain is read from `archive.json` and checked**, not followed blindly: the lane's Pebble, the overlay's
  Traefik alias and the browser's `stack.json` are set up for `stack_lane.DOMAIN` (`veyq.test`), so another domain is
  red naming it. Every archive names `veyq.test` (phase 2's observation), so this costs nothing today; making the stack
  domain-variable is not worth doing until a seeder births elsewhere.
- **`archive.json` is checked against the selection**: its tag, its highest migration against the one the selection
  read from the tag's own pin file (the selection's state is then the archive's state), and its record version
  against the candidate's reader (`run_web_e2e.UPGRADE_RECORD_VERSIONS`).
- **The override is L2's** (the overlay without its release-check double), not the overlay as L1 uses it: L3 installs
  the candidate fresh, but the double's stamp and stub mirror are L1's release-check scenario, not L3's subject.
- **A red backup is its own** (`Stages` with a blocking rule by tag prefix): the next backup still runs, so one red run
  names every red backup, as the bite of the reader showed (both red, each named).
- **The job runs on a push alone; `green` accepts it skipped on a pull request alone**, by two `&&` terms —
  `(needs.stack-restore.result == 'success' || github.event_name == 'pull_request')` and `contains(fromJSON('["success",
  "skipped"]'), …)` — never one with an inner `&&`, which assertion 9's and 16's term split would cut. Rejected: running
  the job on a pull request as a no-op (a green "Stack lane L3" on every pull request that restored nothing reads as a
  pass). `validate_release_identity.py` learnt `PUSH_ONLY_LANES`: the lane's `if:` must hold `github.event_name ==
  'push'` and no other lane's may, and the gate's pair is accepted for that lane alone (assertions 9, 10, 16).
- **Attestation:** `lane-result --backups FILE` (required of L3, refused of every other lane, `snapshot.BACKUP_LANES`),
  each record `backups: [{tag, digest}]`, `[]` for an empty shard; the predicate's `inputs.backups` is the union in
  version order, a tag restored by two shards refused. `checks_verdict` requires every L3 shard. The promotion compares
  the TAGS with `run_stack_restore.py --resolve` at the snapshot's commit (digests recorded, not compared — as L2's), and
  refuses naming "selected now and not restored" / "restored and not selected now". A snapshot whose commit has no L3
  job is refused (lane L3's matrix names nothing), as L2's arrival did; the next weekly snapshot after the merge is the
  first promotable one.
- **oras in checks.yml is the archive's pin** (`ORAS_VERSION`/`ORAS_SHA256` in checks.yml's `env:` too): Renovate's
  oras manager now matches `(archive|checks)\.yml`, `pin_follow.py oras` rewrites both (refusing two versions), and
  `validate_release_identity.py` holds the two copies equal. Rejected: reading the registry over HTTP in Python — a second
  transport beside `archive_backup.pull`, which is the one place what an archive must be is checked.
- **The archive is read signed in** (the job's `GITHUB_TOKEN`, `packages: read`, the login the staged images need
  anyway); that this token reads `veyq-backups` rests on the package being the repository's (published by its own
  workflow, `org.opencontainers.image.source` naming it) — inferred, not measured; the first push shows it.
- **The browser spec is now L2's and L3's**: its title says "after an upgrade" (no longer "in place"), and it carries a
  second tag, `@uc.admin.back-up-and-restore-instance:a-predecessors-backup-upgrades-with-its-content-intact`, with a
  `gaps.stack` on that use-case until a CI log admits the ref (ADR 0040), as L2's criterion has.
- **The docs that say what is built move with phase 3, the retirement's with phase 4** (the delivery constraint: phase 4
  may become its own pull request, and ADR 0091 decision 8 says a CR that adds a stage updates *How veyq is tested* in
  the same change): ADR 0091's `Built:` (L3 and the archive built, decision 7 not), its *L3 as built*, ADR 0090's,
  0085's and 0096's notes, `release-and-deployment.md`, `infra/README.md`'s major-entry sentence, and the site page's L3
  rows. Phase 4 keeps the rest of its done-when list.
- **The invariant conflict, recorded, not resolved here:** the CR's fourth invariant removes the jar-level proof only
  after L2 and L3 have gated a push green, and L3 runs on a push only, so it cannot gate before pull request 2 merges.
  Phase 3 and phase 4 are separate commits, phase 4 on top and independent of nothing in it, so phase 4 can move to a
  branch of its own; whether it does is the owner's (asked through the coordinator).

### Phase 3 — technical observations

- **Local run of the whole selection, green** (2026-11-28, arm64 Mac, load ≈ 5; candidate images `veyq:e2e-l2` /
  `veyq-admin:e2e-l2`, the candidate's release produced as `v1.329` from this tree; the archive read anonymously from
  GHCR, `DOCKER_CONFIG` an empty scratch config naming the CLI plugins): `run_stack_restore.py --shard 1|2|3 --app …
  --admin … --asset-set-out … --backups-out …` — shard 1 (v0.2480, v1.248) 98 s, shard 2 (v1.0, v1.260) 101 s, shard
  3 (v1.168) 43 s. Per backup: archive (lookup, `cosign verify`, pull of 14 files checked against `archive.json`) 4.6–6.7
  s, install 16.7–17.1 s, restore 10.0–10.2 s, reader 3.3–11.8 s, browser 4.6–5.8 s; every reading `configSecretState
  OK`, the restore logging the forward migration from each backup's state to `V202611221402` (v0.2480's across the
  major). The digests the shards recorded are the ones phase 2's table names.
- **`veyqctl restore --objects-from <directory>` does not hit inbox #870 on Docker Desktop**: the restore copies the
  pool into an EMPTY store, once, so no second `copy --immutable` meets a truncated mtime. No shim was used.
- **Bites, each red and restored** (bytes compared; `git status --porcelain` identical before and after; the scripted
  ones by `bites.py` in the scratchpad):
  - a selected backup missing from the registry — shard 2 against a local `registry:3.0.0` holding v1.0 (copied with its
    keyless signature, which verifies there too) and no `1.260`: `v1.260 archive: v1.260 is selected and not archived:
    …veyq-backups:1.260 is not found … — dispatch .github/workflows/archive.yml with tag v1.260; a selected backup is
    never skipped`, while v1.0 restored green in the same run;
  - an archive signed under another identity — (a) the real v1.260 artifact copied untagged-signature-less to the local
    registry and signed there with a throwaway key (`cosign sign --key … --tlog-upload=false
    --use-signing-config=false`, nothing written anywhere but the local registry): `does not verify as an archive …
    no matching attestations`; (b) the real GHCR archive with `IDENTITY_REGEXP` pointed at `release.yml`: `… no
    matching CertificateIdentity found`; in both nothing was pulled;
  - a candidate whose reader expects one more item (the Memory's media rows plus one) — shard 2: `v1.0 read: …
    AssertionError: the Memory: the media rows: expected [ …(2) ] to deeply equal [ …(3) ]` and the same for v1.260,
    each backup red on its own;
  - the selection with its grouping step removed (every release kept): `FAIL selection: 2026-11-26's eighteen releases
    select v0.2480, v1.0, v1.168, v1.248, v1.260`; with the entry dropped: the same label;
  - the fetch without `cosign verify`: `FAIL archive: a signature that does not verify under archive.yml's identity is
    refused before the pull`; the shards contiguous: `FAIL shards: dealt round-robin in version order`; the domain
    unchecked: `FAIL facts: a domain the lane's stack does not serve is refused, naming it`;
  - `promote.py` with the backup list not compared: `a release that opened a schema state since the lane ran: PROMOTED`;
    `snapshot.py` accepting one backup in two shards: `one backup restored by two shards is attested`;
  - `validate_release_identity.py`: the gate's push term removed (L3 skipped accepted on a push) — assertion 9's
    `does not require needs.stack-restore.result == 'success'`; the job's `github.event_name == 'push'` removed — `lane
    L3's job stack-restore does not run on a push alone`; the record without `--backups` — `records no --backups`; the
    two oras pins apart — `the oras pin differs between …`; publish.yml's L3 predicates for two shards — `assembles the
    attestations for … shards ['1', '2'], while lane stack-restore runs ['1', '2', '3']`.
- **Proof on the tree**: the 71 guards of the CI glob green; `stack_restore`, `snapshot`, `promote`, `archive_backup`,
  `pin_follow` (71 checks), `pin_inventory`, `upgrade_proof`, `release_notes` and `staging_retention` self-tests green;
  `tests/e2e` `node --test shards.test.mjs` green (3 tests); `actionlint` green on checks.yml, publish.yml,
  archive.yml and renovate.yml but for checks.yml's SC2129 at its `version` step, which `main` has too; the site's
  `pnpm run check` green (0 errors, 217 pages); `validate_guard_gaps.py --force` (validators changed): 104 pinned cases hold.
- **The `pins` job** (Renovate 44.262.1's extract-only run on a clone of `968a9aa0e` — this commit before this note —, then `pin_inventory.py`): 348
  pins in 42 files, every one reached. Bitten with the oras manager matching archive.yml alone: `checks.yml:56:
  env-version ORAS_VERSION: v1.4.0: no Renovate manager extracts it` (and its SHA-256 as derived from an unreached pin).
- Not reproduced locally: the job on GitHub's runner (its checkout of every tag, the oras install from the pinned
  tarball, the signed-in read of `veyq-backups`, the staged candidate pulled by digest), and its timings; the
  publish's L3 predicate and the promotion against a real attestation.

### Phase 3 — handed back

- **Phase 4's timing** (the invariant conflict in the decision log): whether the jar-level proof's retirement ships in
  pull request 2 or in a third pull request after L3 has gated a push green is the owner's call.
- Nothing else: no product, legal or owner-only question came up.

### Phase 3 — process observations

- My misses: the first local run was red at every Pebble stage — an empty `DOCKER_CONFIG` (set against the hanging
  credential helper) also hides `~/.docker/cli-plugins`, so `docker compose` did not exist; a config naming
  `cliPluginsExtraDirs` fixed it (worth a line in the machine notes). The first identity bite did not bite: its `sed`
  matched nothing and the restore `cp` was the interactive alias (the known trap), so the run was green on an unchanged
  file — seen from the unchanged regexp it printed, redone with a scripted edit and `/bin/cp -f`. A bite whose
  mutation is not shown applied is no bite; the scripted bites assert the old text occurs once before they replace it.
- The CR's phase 3 lists no docs, and phase 4 lists them all; with phase 4 possibly split off, the docs that say a
  stage exists had to move into phase 3 (ADR 0091 decision 8). A CR whose last phase may ship apart should say which
  of its docs belong to which phase.
