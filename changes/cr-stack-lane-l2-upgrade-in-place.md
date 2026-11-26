---
id: cr-stack-lane-l2-upgrade-in-place
title: Lane L2 — a published release upgraded in place to the candidate by its own veyqctl, written by its own seeder, read by the candidate, rolled back
status: planned
satisfies: ["uc.admin.install-and-upgrade-instance", "uc.admin.back-up-and-restore-instance"]
adrs: ["0091", "0096", "0090", "0085", "0057", "0056"]
invariants:
  - no product code and no production recipe changes; the lane runs the recipe exactly as a Server Owner gets it
  - the starting release is installed from its published, signed release and verified under the release identity — never rebuilt from source
  - the starting release's half (seeder, veyqctl, reader after the rollback) runs from that release's own tree, never from the candidate's
  - the jar-level upgrade proof keeps gating; it retires only in cr-stack-lane-l3-backup-archive, once L2 and L3 both gate
---

## Goal

Build lane L2 of [ADR 0091](../docs/decisions/0091-one-pipeline-per-commit-a-release-promotes-a-tested-snapshot.md)
as decision 6's amendment of 2026-11-26 decides it (inbox #453), and the building block lane L3 reuses: **install a
published, signed release on the production stack and write into it with that release's own seeder.**

On every pull request (amd64) and every push (both architectures), L2:
1. installs the starting release from its published release with `veyqctl install`, verified under the release identity;
2. writes with that release's own API seeder (`upgrade.a-seed.e2e.test.ts` from a worktree of its tag);
3. upgrades to the candidate with **that release's** `veyqctl upgrade --unsigned-local`;
4. reads with the candidate's reader (`upgrade.b-read.e2e.test.ts`) and opens the seeded content in a browser with one
   Playwright spec of the candidate's;
5. rolls back with `veyqctl rollback` to the backup the upgrade took, and reads again with the starting release's own
   reader.

Two legs per architecture: the **newest release** (`default_predecessor_ref`) and the **major entry**
(`major_entry_ref`, crossing the major in place; the leg builds nothing when the entry is the default, as the jar-level
proof's `--resolve` already decides). Each leg's result is a `lane-result` record with its predecessor, attested by
Publish and accepted by the promotion.

Why the release's own seeder and not a new browser write half, why the release's own `veyqctl`, and why per push: the
ADR amendment's points 1, 2 and 5. This CR does not re-decide them.

## Context & constraints

### Measured substrate (refine spikes, 2026-11-26, worktree at `50c0ae3ca` = `main`, static reads unless a run is named)

| Fact | Locator |
|---|---|
| L1 installs the candidate with that release's own `veyqctl install --unsigned-local --source src/` | `scripts/run_stack_e2e.py` docstring stage 3; `grep -n unsigned-local scripts/run_stack_e2e.py` |
| `install` without `--source` reads GitHub (`GitHubSource`); `--source` wins over `state.json`; `upgrade` takes `--unsigned-local` | `grep -nE "class GitHubSource\|def source_spec\|unsigned-local" src/tools/veyqctl.py` |
| The verified path knows one identity, the release's; there is no snapshot-identity override | `grep -n COSIGN_IDENTITY src/tools/veyqctl.py` |
| `veyqctl` refuses a cosign older than 3.1.3; no Checks job installs cosign today | `grep -n COSIGN_MIN_VERSION src/tools/veyqctl.py`; `grep -c cosign .github/workflows/checks.yml` → 0 |
| `upgrade` selects from `.env`'s `VEYQ_RELEASE` (no `--release` flag); crossing a major needs it set | `grep -nE "def selected\|VEYQ_RELEASE" src/tools/veyqctl.py` |
| `upgrade` takes a cold backup with `--key-into`; on an unhealthy start it names `rollback`, it never rolls back itself | `cmd_upgrade` / `cmd_rollback` in `src/tools/veyqctl.py` |
| Every released `veyqctl` (v0.2480, v1.0, v1.248, v1.318 checked) has `upgrade --unsigned-local` and `restore --objects-from` | `git show <tag>:src/tools/veyqctl.py \| grep -nE "unsigned-local\|objects-from"` |
| Seeder, reader, record and bootstrap are byte-identical at v0.2480, v1.0, v1.168, v1.248, v1.260 and HEAD; every tag writes record version 1, the reader accepts exactly 1 | `git rev-parse <tag>:src/web/src/features/instanceBackup/upgrade.a-seed.e2e.test.ts` (and `upgrade.b-read…`, `src/web/src/test/upgradeRecord.ts`, `src/web/src/test/e2eBootstrap.ts`) |
| The seeder uses nothing only the e2e profile serves (no profile-gated bean, `createTenant` returns `bootstrapSecret` in every mode, no mail sent, no dev backdoor) | `grep -rn IfBuildProfile src/backend`; `TenantsResource.kt` `createTenant`; `src/web/src/test/e2eBootstrap.ts` |
| The seeder makes 3 requests and the reader 2 on the per-IP auth budget (60 / 60 s); no other limit is near | `@RateLimit` on `SetupResource`, `LoginResource`; `src/backend/core/src/main/resources/application.properties` `veyq.ratelimit.*` |
| The suites need, on a stack: `VEYQ_HOST` = `VEYQ_BASE`'s host = the bootstrap domain (`VEYQ_DOMAIN`), `VEYQ_ADMIN_BASE` through the admin nginx, the Pebble root as `NODE_EXTRA_CA_CERTS`, `VEYQ_E2E=1`, `VEYQ_GROUP_DIR`, `VEYQ_STAMP` | `src/web/src/test/e2eBootstrap.ts`; `src/web/src/test/upgradeRecord.ts`; `src/admin-web/nginx.conf.template`; `tests/e2e/stack/docker-compose.e2e.yml` (the `browser` service, Traefik's `edge` alias) |
| `veyq.test` resolves only inside the stack's networks; L1's browser runs there | `tests/e2e/stack/docker-compose.e2e.yml` |
| The jar-level orchestrator already runs a predecessor's suites from a worktree of its ref, with its own install and `generate:api` | `scripts/run_web_e2e.py` docstring § The upgrade proof; `--upgrade-from` |
| The lane-result record has no predecessor; the predicate's `inputs.predecessor` is `None`; `LANES` names L2 | `scripts/snapshot.py` `lane-result`, `predicates()` |
| Publish requires the gate plus every `Stack lane L1 (<arch>, <domain>)` job and assembles `--lane L1` only | `scripts/snapshot.py` `checks_verdict`; `.github/workflows/publish.yml` (`--lane L1`) |
| The promotion knows `LANES = ("L1",)` and refuses any input but the asset set | `scripts/promote.py` `LANES`, `lane_verdicts` |
| `tests/e2e/shards.test.mjs` reads the `stack` job by slicing checks.yml up to `upgrade-proof:` | `grep -n "upgrade-proof" tests/e2e/shards.test.mjs` |
| Critical path today is `frontend` + L1 ≈ 18 min; `upgrade-proof` runs beside it (run 45882863817) | `gh run view 45882863817 --json jobs` |

Not run in the refine, and phase 1's first job: name resolution and TLS from Node inside a container on the stack's
networks, and whether each starting release's recipe needs a variable today's does not (an older `.env.example` may
require one, e.g. `VEYQ_ACME_EMAIL`): the install reads the **starting release's** `.env.example`, never today's.

## Decisions (technical, settled here)

1. **A new orchestrator, beside L1's, not a mode of it.** `scripts/run_stack_upgrade.py` (name the implementer's) owns
   L2. It reuses `run_stack_e2e.py`'s stack primitives (the run directory, Pebble, the overlay, the browser container,
   teardown) by import, not by copy; what is new is the starting release's install, its worktree, the upgrade, the
   rollback. `run_stack_e2e.py` stays L1's and gains no branch for L2. Where a primitive is wired to the candidate's own
   `Release` (the Pebble stage reads the candidate image's keytool), it is lifted into a shared module in phase 1,
   with L1 green on it before L2 uses it.
2. **The starting release comes from a mirror built with `gh release download`, not from `GitHubSource`.** The mirror
   carries the release's files and its `manifest.json.bundle`, so `veyqctl install --source <mirror>` still verifies
   everything under the release identity, and the run does not depend on GitHub's unauthenticated API budget. The
   images are pulled from GHCR by the digests the verified manifest names. cosign is installed in the job at the
   version publish.yml pins.
3. **The suites run in a container on the stack's networks** (`edge` and `admin`), from the run's worktree of the
   suite's own tree, with the variables the substrate table names. One container definition serves the seeder, both
   readers and the browser spec; the browser spec uses L1's `browser` service.
4. **Each tree's half comes from its own worktree.** The seeder and the post-rollback reader from a worktree of the
   starting release's tag (built as `run_web_e2e.py --upgrade-from` builds one; reuse its function), the reader and the
   browser spec from the candidate's checkout. The upgrade and the rollback run the starting release's `veyqctl`
   (`releases/<start>/veyqctl` in the instance home), never the candidate's.
5. **The order on the instance:** install the starting release with no first-run setup (the seeder does it and asserts
   `setupRequired=true`); seed; `VEYQ_RELEASE` set to the candidate's version in `.env`; `veyqctl upgrade
   --unsigned-local --source <candidate src/>` with `--key-into`; read; browser spec; `veyqctl rollback <the upgrade's
   backup> --key-from <key>`; read with the starting release's reader. Every step's command and outcome goes to the
   run's summary, ending with the predecessor, both highest migrations and the outcome, as the jar-level proof's does.
6. **The browser spec is one file, tagged with the criterion it covers**, in the candidate's `tests/e2e`: it signs in
   as the seeded owner from the record and opens the seeded Memory and Depot file. It runs only in L2 (and L3 later),
   never in L1's domains; `shards.test.mjs` learns that.
7. **Jobs.** A matrix job `Stack lane L2 (<arch>, <leg>)` in checks.yml, `needs:` as `stack`'s, 15-minute budget per
   leg, amd64 only on `pull_request` (ADR 0091 decision 4). A leg whose predecessor resolves to nothing builds nothing
   and passes, saying why. `green` needs it. `shards.test.mjs`'s slice is re-anchored so it reads the `stack` job
   alone, whatever follows it.
8. **Attestation and promotion.** `lane-result` gains `predecessor` (tag, and the app and admin digests the verified
   manifest named); `predicates()` writes it into `inputs.predecessor` for L2. `checks_verdict` requires the L2 jobs as
   it requires L1's; publish.yml assembles `--lane L2`. `promote.py` adds L2 to `LANES` and compares L2's predecessor
   with the one the promotion resolves now: equal passes, different is refused naming both (decision 5's rerun stays
   not built — the refusal is its stand-in, as for every input it cannot compare).
9. **No product change.** If the lane finds an upgrade defect, the CR stops and hands it back as a CR of its own.

## Inventory (locators — run at implement time)

| Surface | Locator |
|---|---|
| Stack primitives L2 reuses | `grep -nE "^def " scripts/run_stack_e2e.py` |
| Predecessor and worktree helpers | `grep -nE "def (default_predecessor_ref\|major_entry_ref\|major_entry_proof_ref\|.*worktree)" scripts/run_web_e2e.py` |
| What names the stack lane jobs | `git grep -n "Stack lane L1" -- scripts .github` |
| Lane records, predicates, verdicts | `grep -nE "LANES\|predecessor\|def (predicates\|checks_verdict\|lane_verdicts)" scripts/snapshot.py scripts/promote.py` |
| Their self-tests | `ls scripts/*selftest*.py \| xargs grep -ln "lane"` |
| Guards over checks.yml's job list | `git grep -n "green" scripts/validate_release_identity.py`; `tests/e2e/shards.test.mjs` |
| Docs naming L2 as not built | `git grep -n -E "L2" -- docs/decisions/0090* docs/decisions/0091* docs/decisions/0096* docs/crosscutting/release-and-deployment.md site/src/content/docs/security/how-veyq-is-tested.md site/src/content/docs/run/upgrade.mdx` |
| Coverage that names the upgrade proof | `git grep -n -i "upgrade proof\|upgrade-proof" -- docs/coverage docs/features` |

## Phases

### Phase 1 — the building block: a published release, installed and seeded

Shared stack module lifted out of `run_stack_e2e.py` (decision 1), L1 unchanged in behaviour; the mirror of a
published release and its verified install (decision 2); the starting release's worktree and seeder run in a container
on the stack (decisions 3 and 4). A local command installs v1.318 and seeds it.

Done when: `run_stack_e2e.py` (L1, one domain) green locally on the lifted module; the new orchestrator, run locally
against v1.318 and against v0.2480, installs, verifies (its log shows the release-identity `cosign verify` lines) and
seeds, and its record file exists; `scripts/run_pipeline.py`'s selftest and the trust chain green.

### Phase 2 — upgrade, read, browser, rollback

Decisions 5 and 6. Done when: locally, from v1.318 and from v0.2480, the lane runs to a green summary; **bite:** a
candidate whose reader expects one more seeded item than the record holds is red at the read and names it (injection,
reverted); a candidate built with an edited released migration (its pin re-written, which the migration guard lets
through, its limit (b)) is red at the upgrade's health wait with Flyway's checksum refusal in the summary (injection,
reverted) — the defect class the per-commit proof exists for; the rollback's read is green and runs the starting
release's reader (its worktree path in the log).

### Phase 3 — CI, attestation, promotion

Decisions 7 and 8. Done when: a pull request's run shows the amd64 L2 legs green and a push's both architectures;
`snapshot_selftest.py` and `promote.py`'s self-test cover the predecessor field, the L2 requirement and the refusal of
a moved predecessor (each bitten: remove the check, see the case red); a dispatched Publish on the push attests L2 per
architecture with its predecessor (`cosign verify-attestation` output in the PR); `shards.test.mjs` green with the
re-anchored slice; budgets entered in the stage map of `site/src/content/docs/security/how-veyq-is-tested.md`.

### Phase 4 — the docs move

L2 becomes built where it is written as not built (inventory row), the Run page's *How far this is proven* names the
lane, ADR 0091's and ADR 0090's `Built:` lines name L2, ADR 0096's note on decision 9's minor promise points at L2,
inbox #453 is deleted. Coverage rows for `uc.admin.install-and-upgrade-instance` point at the lane.

## Definition of done

- L2 gates every pull request (amd64) and every push (both architectures) from the newest release and the major entry,
  and the promotion refuses a snapshot whose L2 predecessor moved.
- Every phase's bite went red and was reverted.
- `./gradlew check` untouched; `pnpm run check` in `tests/e2e`, the scripts' self-tests and the trust-chain guards green.
- The PR's body carries `## Release note`: none for users; for operators one line that every release is now proven to
  upgrade in place from the previous one and from the previous major's last release, with the operator's own tool.

## Retrospective

(filled by the implementer)
