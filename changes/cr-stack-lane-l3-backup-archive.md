---
id: cr-stack-lane-l3-backup-archive
title: Lane L3 and the backup archive — every release archived once by its own seeder, one backup per schema state restored into the candidate, the jar-level upgrade proof retired
status: planned
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

**Requires `cr-stack-lane-l2-upgrade-in-place` done**: L3 reuses its building block (a published release installed and
seeded on the stack, the suites in a container on the stack's networks, the browser spec, the predecessor field).

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

## Phases

### Phase 1 — the archive workflow, run for one release

Decisions 1 to 3. Done when: a dispatch of `archive.yml` for `v1.318` publishes `veyq-backups:1.318`, its `cosign
verify` under `archive.yml`'s identity passes and under `release.yml`'s fails (both outputs in the PR); a second
dispatch for `v1.318` writes nothing and says so; `validate_release_identity.py` knows the new identity and is bitten
by a workflow that signs under it without being listed.

### Phase 2 — backfill

Decision 7. Dispatch for every release, oldest first. Done when: `veyq-backups` holds one artifact per `v` tag, each
verifying; the run links are in the PR.

### Phase 3 — the selection and lane L3

Decisions 4 to 6. Done when: L3 is green on a push on amd64, restoring exactly the selection (five
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

(filled by the implementer)
