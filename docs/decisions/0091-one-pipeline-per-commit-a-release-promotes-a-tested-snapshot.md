# ADR 0091 — One pipeline per commit, every stage on a budget; a release promotes a tested snapshot

- Status: Accepted 2026-10-16; built: the local skip of `validate_guard_gaps.py` (decision 4) and its parallel cases (decision 1); the rest is not (§ Consequences)
- Date: 2026-10-16

## Context

There is no decision about testing as a whole — only single ones: what a coverage claim needs
([ADR 0040](0040-coverage-gaps-and-http-not-a-surface.md)), assertion strength
([ADR 0045](0045-mutation-testing-trust-chain-unit-layer.md)), the wire assertion
([ADR 0053](0053-wire-assertion-canary-sweep.md)), the upgrade proof
([ADR 0057](0057-release-identity-and-deployment-recipe.md) §7), races on MariaDB
([ADR 0078](0078-production-reads-committed-like-its-tests.md)), the stack-level browser suite
([ADR 0090](0090-end-to-end-tests-boot-the-release-candidate.md)). Each was right on its own; together they grew
into seven workflows nobody budgeted, and the owner wants three kinds of test on top: **an upgrade of a running
instance with data**, **an old backup restored into a new version**, and **feature tests against a running
instance**.

What a run costs, measured on 2026-10-16 on the development Mac (14 cores, under a load of 20–55 from neighbour
sessions, so high rather than low):

| Stage | Measured | How |
|---|---|---|
| The 58 `validate_*` guards | 998 s; **`validate_guard_gaps.py` alone 901 s**, the other 57 together 97 s | each timed in sequence |
| Backend tests (`:core`, `:apps:memories`, root) | 1544 tests, 7.5 min of test time (`OperatorBlindProbeTest` 44 s) | the JUnit reports of that morning |
| `run_web_e2e.py`, 26 suites, a fresh boot each | 9.2 min | its run directory of 2026-10-15 |
| `run_web_e2e.py --upgrade` | 3.5 min | its run directory of 2026-10-15 |
| The app image (`runtime-jvm`) | 2.5–4.6 min | `docker buildx history` |

Three structural costs sit on top of those numbers:

- **`validate_guard_gaps.py` is 90 % of the trust-chain.** It tests the guards, not the product: 79 forgeries
  against 22 guards, each a fresh copy of the tree and a subprocess, one after the other.
- **The backend and both frontends are checked twice per push.** `ci.yml` runs them, and `container.yml`'s `test`
  runs them again — and `build` waits for that second run before it starts.
- **A release builds again.** It publishes bytes no test saw; ADR 0090 gates them by testing `build-<sha>` inside the
  dispatch, which is the right bytes at the latest possible moment.

## Decision

1. **The stages, and the one thing each proves.** Every stage has a budget (the job's `timeout-minutes`) and an
   owner of its slowness: a stage that outgrows its budget is sharded or moved to a later trigger, never made faster
   by weakening what ships (no lowered Argon2 parameters, no skipped TLS, no H2 where MariaDB is the point).

   | Stage | Proves | Budget |
   |---|---|---|
   | **Guards** (the 57 product guards) | the tree keeps its written rules | 3 min |
   | **Guard gaps** (`validate_guard_gaps.py`) | the guards still bite as documented | 10 min, cases in parallel |
   | **Surface checks** (`./gradlew check`, `npm run check` of crypto, web, admin-web; the chart check) | each surface's unit and integration tests | 15 min |
   | **Races on MariaDB** | ADR 0078 | 10 min |
   | **API end-to-end** (`run_web_e2e.py`) | real crypto against a live backend, fresh per file; backup and restore groups; the wire sweeps | 15 min |
   | **Image build** | both images, both architectures, once per commit | 15 min |
   | **Stack lanes** (ADR 0090's harness, on the built image) | see 3 | 15 min each, in parallel |
   | **Mutation** (ADR 0045) | assertion strength of the scoped files | as today, path-gated |

2. **One pipeline per commit, and nothing waits that need not.** `ci.yml` and `container.yml` become one workflow.
   The guards, the surface checks, the races, the API end-to-end run **and the image build start together**; the
   build does not wait for the tests, because nothing is published until every stage is green. The stack lanes need
   the build. `container.yml`'s second copy of the surface checks goes; admin-web's check and the chart check, which
   only it ran, move into the surface checks. `trust-chain.yml` stays separate and unfiltered, because a docs-only
   commit is validated there and nowhere else.

3. **Three stack lanes, one scenario suite.** The scenarios are split into a **write half** (put content into an
   instance) and a **read half** (open every item and assert it), with one record between them — the shape the
   upgrade proof already has (`web/src/test/upgradeRecord.ts`):
   Every lane drives the operator's own tool, `veyqctl` ([ADR 0096](0096-veyqctl-manages-an-instance-from-immutable-releases.md)), against the candidate's release assets served as a
   local source — so what is tested is what a Server Owner runs:
   - **L1 feature** — `veyqctl install` of the candidate, fresh: write, then read; plus the feature scenarios that
     need no predecessor.
   - **L2 upgrade in place** — `veyqctl install` of the previous release, write; `veyqctl upgrade` to the candidate
     on the same instance (its backup, template check and health wait, ADR 0096 §5); read.
   - **L3 backup import** — every archived backup of the current major (decision 6), each `veyqctl restore`d into a
     fresh `veyqctl install` of the candidate (ADR 0056 §7); read. One runner per backup.

   Feature scenarios are Playwright tests tagged with the acceptance criterion they cover
   (`@uc.admin.setup-server:<criterion-id>`); a guard holds both directions — every tag names a criterion, every
   `stack` ref of a criterion names a tagged test. There is no second wording of a criterion.

4. **When what runs.**
   - **Pull request**: everything in 1 except mutation's scope rule, L1 on amd64, nothing published. A pull request
     never writes to a registry.
   - **Push to `main` or `release/<major>.x`**: everything, all three lanes on **both** architectures (what is
     published for an architecture must have passed on it), then the snapshot (decision 5).
   - **Guard gaps** run when `scripts/` or `.claude/skills/trust-chain/` change, and nightly on `main` — they test the
     guards, and a product commit changes no guard.
   - **Release**: decision 5's promotion; the tests it needs already ran.

5. **Build once, promote.** A green push publishes its images as a **snapshot** — `<major>.<minor>-SNAPSHOT` and
   `sha-<commit>` on the exact digests the lanes tested — signed, with each lane's result attached as a signed
   attestation (lane, architecture, outcome, and its **inputs**: the predecessor digest for L2, the list of backups
   for L3, and the hash of the release-asset set it installed). A snapshot is written once per commit and never
   replaced. **A release is a promotion**: a dispatch names a commit (default: the tip), its snapshot's attestations
   are verified, and the same digests get `<major>.<minor>`, `<major>`, `latest` (from `main`), a release signature
   and the git ref `v<major>.<minor>`; then the **immutable GitHub Release** is created from the asset set
   ([ADR 0096](0096-veyqctl-manages-an-instance-from-immutable-releases.md) §3). Nothing is rebuilt.
   - **The asset set is the candidate's, too.** The pipeline produces it from the commit and the tested digests
     (`scripts/release_assets.py`, `cr-veyqctl`) before the lanes run, and the lanes install exactly it. A snapshot
     gets no GitHub Release (ADR 0096 §3); the promotion produces the set again from the same commit and digests, and
     refuses when its hash differs from the one the attestations name — so the published files are the ones tested.
   - **In CI the candidate is pulled by digest and given a local name** before `veyqctl install --unsigned-local`, the
     flag `veyqctl` refuses for a registry reference; the signature check itself is exercised where it can be: by L2's
     install of the previous, signed release.
   - **The image carries `<major>.<minor>`, never `-SNAPSHOT`.** The minor is a function of the commit (ADR 0057 §1),
     so `0.12` can only ever name this tree, and a snapshot and its release are the same bytes. Whether a digest *is*
     a release is the registry's and the signature's fact, not the image's: `cosign verify` with the release identity
     answers it, and admin-web's release panel says so. The cost: a snapshot that is never promoted shows `0.12`
     too.
   - **A result is reused only for the inputs it ran against.** When a release was cut after the snapshot was tested,
     the predecessor and the archive have moved; the promotion compares the attestations' inputs with the current
     ones and runs **only the missing lanes** on the snapshot's digest before it promotes.
   - **Retention**: the 20 newest unpromoted snapshots per branch and every promoted one; a scheduled job deletes the
     rest.

6. **The backup archive.** Every release publishes the L1 run's capture (ADR 0056's tool, test data only) as a
   signed OCI artifact beside its images. L3 restores every archived backup of the current major — within a major,
   everything older must open ([ADR 0085](0085-one-major-moved-on-a-yearly-train.md)). Before the first release the
   archive is empty, L2 upgrades from the previous snapshot, and both inform and gate nothing, as ADR 0057 §7 says
   for the upgrade proof today.

7. **The jar-level upgrade proof retires when L2 and L3 gate.** They prove the same data path on the artifact that
   ships; ADR 0057 §7 then names them. The API end-to-end suites stay: fast, fresh per file, and where a wire defect is
   bisected.

8. **The test concept is published, because it is what stands in for a human review.** veyq's code is written by an
   AI coding agent under the owner's direction and is not reviewed line by line by a person, so what a reader can
   rely on is what the checks show — and only that. The Security area of the docs site carries
   [How veyq is tested](../../site/src/content/docs/security/how-veyq-is-tested.md): each stage of decision 1, what
   it proves and what it cannot, when it runs, what gates a release, and which of it is **built and has run**. It
   claims no more than a run shows: a stage that is designed and not built says so, a number is measured or marked
   an estimate, and until the repository has a remote it says that no stage has run in CI. A CR that adds, removes
   or moves a stage updates that page in the same change.

## Consequences

- **The critical path per push**, estimated (no CI run exists): today `test` → `build` → (ADR 0090's `e2e`), 35–50
  min; with decisions 2 and 4, `max(surface checks ≈ 15, build ≈ 8 + lanes ≈ 12)`, **20–25 min**. A release is a
  promotion: minutes. The first CI run's numbers replace both.
- **ADR 0057 is edited in place**: §1 (a green push publishes a snapshot; a snapshot is immutable; a release is a
  promotion; the image carries no `-SNAPSHOT`), §2 (unchanged in substance: one ref, created by the promotion), §7
  (decision 7). **ADR 0090** follows: its Decision 2 (the gate is the push, the release verifies), 7 (no tiers; the
  lanes of decision 3) and its rejected "testing the published snapshot".
- **Work, in this order**, each its own CR: the pipeline (decision 2, the guard-gap filter and its parallel cases);
  `cr-stack-e2e` (L1), re-cut to this ADR; promotion and attestations (decision 5); L2 (inbox #453); the archive and
  L3 (decision 6). None is provable in CI before the repository has a remote.
- **The local skip of `validate_guard_gaps.py`** is the first piece built (2026-10-16): a green full run stamps the
  hash of `scripts/` and the trust-chain skill, a local run with the same inputs skips, CI never does. The CI half of
  decision 4 — the path filter and the nightly run — comes with the pipeline CR.
- **The guard-gap cases run in parallel** (2026-10-16, `cr-one-pipeline-per-commit` phase 1): a pool of
  `min(cpu_count, 6)` workers, `VEYQ_GUARD_GAPS_WORKERS=1` the sequential run. Measured on the same Mac with a
  neighbour's Docker-heavy session running: the full run **167 s at 6 workers** (load average 13 → 30) against
  334 s at 1 (24 → 13) — inside its 10-minute budget; about 2×, not 6×, because the tree copies are bound by the
  file system and its watchers, not by the CPU. The 901 s of § Context was the sequential run under a load of 20–55.
- **The remote comes when the owner judges veyq ready to publish, and it will be public** (owner, 2026-10-16). Until
  then every stage's only run is local, so the pipeline must run locally from the workflow file itself, by one command
  (`changes/cr-one-pipeline-per-commit.md` phase 5); the first public push is also the first CI run.
- **Registry growth** is bounded by the retention rule; a snapshot per `main` commit is otherwise one image pair per
  commit.

### Rejected

- **A release that builds again** (the `-SNAPSHOT` stamp kept in the image). The release would be bytes no test saw.
- **Testing only at the release.** A regression is found at the moment it costs most; the lanes run per push instead.
- **Gherkin (playwright-bdd, cucumber-js) or Gauge.** All three are maintained (default branches touched on
  2026-10-13/14, measured); none removes a layer here. The acceptance criteria already live in `docs/features` and
  `docs/coverage`, and a `.feature` file would be their second wording — two copies, one of which drifts.
- **Guard gaps on every push.** 90 % of the trust-chain's time spent proving that guards no commit touched still bite.
- **Keeping `ci.yml` and `container.yml` apart.** The second copy of the surface checks is a quarter of an hour on the
  critical path and proves nothing the first did not.
