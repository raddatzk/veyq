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
  change, and outside this CR). Recommendation: an inbox row; the fix is likely `--modify-window 1s` on the directory
  pool's copies in `instance_backup.py` (rclone's own lever for coarse-mtime filesystems), proved by the same two-copy
  case. The local run used a scratch `docker` shim adding `RCLONE_MODIFY_WINDOW=1s` to the rclone container only —
  never in the repository.
- **`veyq-backups` will most likely be created private.** GitHub's docs (read, not measured): "When you first publish
  a package that is scoped to your personal account, the default visibility is private", and a linked package inherits
  "the access permissions (but not the visibility)" of its repository. Decision 2 says the archive is public, so making
  it public after the first dispatch is the owner's step (below). The first push from a workflow could not be measured
  here (no registry writes).

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
