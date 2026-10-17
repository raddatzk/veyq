---
title: How veyq is tested
description: veyq's code is written by an AI coding agent and not reviewed line by line by a person. This page says which checks stand in for that review, what each one proves, what none of them can, and which of them have actually run.
sources:
  - docs/decisions/0091-one-pipeline-per-commit-a-release-promotes-a-tested-snapshot.md
  - docs/decisions/0090-end-to-end-tests-boot-the-release-candidate.md
  - docs/decisions/0040-coverage-gaps-and-http-not-a-surface.md
  - docs/ai/README.md
---

veyq's code is written by an AI coding agent working under the owner's direction. The owner decides what is built
and how, in conversation, before the work starts; no person reads each change line by line afterwards. So the
question a reviewer would answer — *is this change correct, and did it break anything?* — has to be answered by
checks that run on every change. What you can rely on is what those checks show, and only that. This page lists
them, says what each one proves and where it stops, and marks which are built and which are only designed
([ADR 0091](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0091-one-pipeline-per-commit-a-release-promotes-a-tested-snapshot.md)).

:::caution[Nothing here has run in CI yet]
This repository has had no remote since it was imported on 2026-08-06, so no workflow has ever executed in it.
Every check described below as *built* runs on a developer machine; none has run in CI. The project's own rule is
that a test no CI run executes makes no coverage claim
([ADR 0040](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0040-coverage-gaps-and-http-not-a-surface.md)),
and this page follows it: a stage moves to *runs in CI* only after a CI log shows it executed. What the pipeline
would run in CI is read from its workflow files; until the first CI run, that is a statement about a file, not
about a run. The remote comes when the owner judges veyq ready to publish, and it will be public; that first push is
also the first CI run.
:::

## Why checks, and how they are kept honest

A reviewer catches what the author did not think of. Without one, a defect is caught by a check or it ships. Four
habits make the checks worth relying on:

- **The rules are written down first.** The API contracts under `contracts/` are the source the server and the
  clients are generated from, so no hand-written copy can drift from them. Every feature has use-cases with numbered
  acceptance criteria, and every use-case has a coverage file that names the test proving each criterion — or says,
  with a reason, that none does yet ([`docs/coverage/`](https://github.com/raddatzk/veyq/tree/main/docs/coverage)).
- **A check is made to fail before it is trusted.** The working rules require that a new guard, or a test that
  carries weight, is first run against a case that must make it fail — a check that has never been red has not shown
  that it can be. That is a rule for each change, not a property re-checked across the whole tree; the guard-gap stage
  re-checks it for the guards.
- **Assertion strength is measured, not assumed.** A test that asserts nothing still passes. Mutation testing changes
  the code under test on purpose and requires the tests to notice; it covers the cryptographic and policy code where a
  weak assertion would matter most
  ([ADR 0045](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0045-mutation-testing-trust-chain-unit-layer.md)).
- **The operator-blind claim is tested from the outside.** End-to-end runs record every plaintext value their client
  sealed and then search everything an operator could read for it
  ([wire assertion](https://github.com/raddatzk/veyq/blob/main/docs/crosscutting/cc.security.wire-assertion.md)).

## The stages

Each stage proves one thing. The table says what, what it cannot see, and whether it is built; [where and when
each one runs](#where-and-when-each-stage-runs) follows it.

| Stage | What it proves | What it cannot see | Status |
|---|---|---|---|
| **Guards** (`scripts/validate_*.py`) | The tree keeps its written rules: every reference resolves, every contract field declares what the server may read, every image is pinned by digest, the production recipe is hardened. | That what a reference points at carries weight — a cited test that asserts nothing passes. | Built. |
| **Guard gaps** (`validate_guard_gaps.py`) | The guards still reject the forgeries they claim to reject, and their documented limits are still exactly where they are written. | A guard's limit that was never documented. | Built. |
| **Surface checks** (`./gradlew check`, `npm run check` per package, the chart check) | Each part's unit and integration tests: the backend against an in-memory database and in-process fakes, the web client, the admin console and the crypto package in their test runners; the Helm chart's renders keep the production recipe's structural rules, and an upgrade from the previous chart changes no field Kubernetes refuses to change. | The real database, the real object store, the real browser. | Built. |
| **Crypto conformance** | The bytes of the cryptographic suite cannot move silently: every primitive is pinned to published test vectors. | Whether a correct primitive is used correctly. | Built, inside the surface checks; see [Verify it yourself](/veyq/security/verify-it-yourself/). |
| **Mutation** | The tests of the scoped crypto and policy files fail when that code changes. | Files outside the scope. | Built. |
| **Races on MariaDB** | Concurrent writes behave on the production database as they do in the in-memory one. | Races no test provokes. | Built. |
| **API end-to-end** (`scripts/run_web_e2e.py`) | Real client cryptography against a live backend, database and object store, a fresh instance per suite; backup and restore round trips; no sealed plaintext or capability token readable in the database, the store, the HTTP traffic, the logs, the SQL statements or the mail. | Only the values the suite unsealed; no browser, no production image. | Built. |
| **Image build** | Both images that ship — the app and the admin console — build from the commit, for amd64 and arm64. | Whether they run: lane L1 installs them. | Built. |
| **Upgrade proof** (`run_web_e2e.py --upgrade`) | Content sealed by the previous version opens after a restore onto this one, which migrates it. | The upgrade of a running instance; the shipped images. | Built; its CI job is off until the first release. |
| **Stack lane L1** — the candidate, fresh (`scripts/run_stack_e2e.py`) | The two images that ship, installed the way a Server Owner installs them — `veyqctl install` from the candidate's own release files — with a real ACME certificate from a test CA, driven in Chromium: the domain says it is not set up, the Server Owner is created and its Emergency Kit read back, the bundled store connected and checked, the first tenant created; its owner accepts on the tenant's own domain and certificate, writes a Memory with a photo and shares it read-only; a second browser signs in with only the password and the Secret Key and sees the photo decrypted; an anonymous visitor opens the link. Then the first feature scenario: the Server Owner switches drop boxes, adding a device from another one, unlocking without a password and remembered devices off and on again in the console, and the web client loses and regains each of them; a trusted and a remembered device ask for the password and the Secret Key while their switch is off, delete or replace none of their keys on the server, and open as before once it is back on. Every text field and the profile picture written once — a folder renamed and moved, a Memory's date, a sub-bucket, a link's label and note, a guest's name, the member's own name and picture — and read back after a reload and on a second device. A guest's upload the link's limit refuses leaves no empty file behind, and the same guest cannot take back a member's empty folder. The Server Owner points the release check elsewhere and switches it off, and sets up instance mail against a test mailbox, confirming it with the code the instance mailed; then the check, pointed at a stand-in release mirror the run serves, finds the newer releases there: the console lists them with an unscheduled major apart as urgent, each is mailed once, the planned major's mail carries its date as a calendar file, and the check is switched off. Every test names the acceptance criterion it covers, and a check holds each name to a criterion that exists. No page request to any origin but the instance — the one breached-password query is answered locally. Afterwards every value the browser typed (the password, the Secret Key, the names, the photo's name and the text inside it) is searched for, and must be absent, in the instance's database, its bucket, every container log and every SQL statement the database received — the same sensors as the API end-to-end suites. A run that executes no test is red. | Only what the browser typed, not everything the page sealed (the API suites cover that half); no HTTP traffic recorded, so no capability token searched for here; one tenant, a few Memories, folders and links; no browser restart. The release notices come from a stand-in: the candidate of a push is a snapshot, and only a release-stamped build looks for releases, so the run stamps its own instance with the release it installed, has it look every few seconds rather than daily, and serves the newer releases itself; veyq's real GitHub Releases are never read. The certificate comes from a test CA that is told every challenge is valid. The images are local builds installed unsigned, so `veyqctl`'s signature check ([ADR 0096](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0096-veyqctl-manages-an-instance-from-immutable-releases.md)) is not exercised here. | Built. |
| **Stack lanes L2 and L3** — upgrade in place, backup import | The previous release upgraded in place with `veyqctl upgrade`; every archived backup of the major restored into the candidate with `veyqctl restore`. | Anything the scenarios do not do. | Designed ([ADR 0090](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0090-end-to-end-tests-boot-the-release-candidate.md), ADR 0091); not built. |

## Where and when each stage runs

Each stage is a job in a workflow file under `.github/workflows/`. The file says when the job runs, and the job's
`timeout-minutes` is the stage's budget: ADR 0091 holds the values, and a guard fails any job that has none. The *In
CI* column is read from those files and has never run. The *Local run* column was measured on the development
machine (a 14-core arm64 Mac) on 2026-10-15 and 2026-10-16, under whatever load other work put on it, so it says
little about a CI runner's time.

`ci.yml` is the pipeline. It runs on every push to `main` or a `release/<major>.x` branch and on every pull request
into one, unless the change touches only docs, Markdown or the site, and once more when a publish is started by hand.
Its stages start together; only lane L1 waits for another stage, the image build. *Every commit* below means those triggers.

| Stage | In CI: workflow › job, and when | Budget | Local run |
|---|---|---|---|
| Guards | `ci.yml › guards`, every commit; and `trust-chain.yml › trust-chain`, every push and pull request to `main`, docs-only ones included — the only place a docs-only change is checked | 3 min | 1.5 min in the pipeline run; 97 s timed in sequence |
| Guard gaps | `trust-chain.yml › guard-gaps`, always in full: when a push or pull request to `main` changes `scripts/`, the trust-chain skill or that workflow, nightly on `main`, and by hand. Locally it skips while nothing it reads changed since its last green full run | 10 min | 2.5–7 min with six workers (147–431 s over six runs, load average 13 to 99) |
| Surface checks, crypto conformance | `ci.yml › backend` (`./gradlew check`), `› frontend` (crypto, web, admin-web), `› chart`; every commit | 15 min each | backend 8.1 min, frontend 5.0, chart 0.4 |
| Races on MariaDB | `ci.yml › backend-races`, every commit | 10 min | 9.5 min beside the backend's check, 7.0 alone; once, under a heavier load, over its budget beside it. Locally the jobs share one machine; in CI each has its own runner |
| API end-to-end | `ci.yml › web-e2e`, every commit | 15 min | 9.2 min for its 26 suites, run directly (2026-10-15); 13.8 min in the local pipeline, all 27 suites green, run alone while other work kept the machine busy (2026-10-16) |
| Image build | `ci.yml › build`, both images on amd64 and arm64, every commit; it writes to a registry only on a publish | 15 min | the app image 2.5–4.6 min uncached on arm64; **the amd64 legs have never run** |
| Stack lane L1 | `ci.yml › stack`, per architecture after the image build: a push on both, a pull request on amd64 only; on a publish it installs the images the build pushed, pulled by digest | 15 min | 75–90 s, its steps run directly; **the local pipeline cannot run this job** (below) |
| Mutation | `mutation.yml` when a push or pull request to `main` changes the crypto package or web's sources, `mutation-backend.yml` when one changes a scoped backend file or its test; both when their own configuration changes, and by hand | 30 min per gate, an estimate | not timed as a whole; ADR 0045 puts an incremental run at 1–3 min |
| Upgrade proof | `ci.yml › upgrade-proof`, switched off until the first release | 15 min, an estimate | 3.5 min, run directly (2026-10-15) |
| Stack lanes L2, L3 | no job | — | — |

Per commit in CI, the longest of these paths is *estimated* at 20–25 minutes (ADR 0091); the first CI run replaces the
estimate. Two other workflows check the repository without being a stage: CodeQL's static analysis (`codeql.yml`) and
the docs site's build (`site.yml`).

### One command runs the pipeline locally

`python3 scripts/run_pipeline.py` reads `ci.yml` from the commit and runs it: every job this machine can run, each in
its own clone of the commit, independent jobs in parallel, each under its budget. It keeps no list of stages of its
own; the workflow is the list. Where a stage's work sits in a step only GitHub runs, the workflow carries the local
stand-in beside it, such as the image build with the machine's own `docker build`. Per job it prints green, red,
skipped by the workflow's own condition, or *not run* with the reason, and nothing it did not run counts as green: its
verdict is green only when every job ran green. It runs `ci.yml` only; the guard gaps and the mutation gates have
their own commands ([Re-run it](#re-run-it)).

Its one recorded full run (commit `04c9f6d1`, 2026-10-16) was **not green, and nothing in it was red**. Every job it ran
passed, and it named what it did not run:

- `web-e2e`, left out on purpose: it drives the machine's one local compose stack, which other work was using;
- the amd64 legs of the image build, since the machine is arm64;
- the steps only GitHub runs: the check of the Gradle wrapper against Gradle's published checksums, and the image
  build's own action with its provenance and SBOM;
- `upgrade-proof` and `publish`, skipped by their own conditions on a push, as they are in CI.

The `stack` job came after that run. The local pipeline cannot run it on any one machine: it waits for every leg of the
image build, and one machine builds only its own architecture.

## How a release will be gated

A publish is started by hand from `main` or a `release/<major>.x` branch and runs the whole pipeline once more. The
image build pushes each image to the registry under a staging tag, unsigned and carrying no release name; lane L1
installs exactly those images, pulled by digest; and the `publish` job runs only when every other job of that run is
green (the switched-off upgrade proof excepted). It then names those same digests — a snapshot `<major>.<minor>-SNAPSHOT`
by default, a release `<major>.<minor>` when asked — signs them and the Helm chart keylessly, and for a release creates
the tag `v<major>.<minor>` and the immutable GitHub Release with its files. All of that is a workflow file. It has
never run: there is no remote, and a registry write and keyless signing cannot run on a developer machine at all.

The rest is designed, not built (ADR 0091): every green push publishes its images as a signed snapshot on exactly the
bytes that were tested, with each stack lane's result attached as a signed attestation, and a release does not build
again — it gives that snapshot's bytes the release name and signature, after checking the attestations. So a release
is always bytes every test saw, and anyone can check which release they run against its signature
([Verify a release](/veyq/run/verify-a-release/)).

## What no test here can prove

- **That a test's assertion is the right one.** A check can show a test exists, runs and fails when the code changes;
  it cannot show the test asks the right question. Mutation testing narrows this for the scoped files and nowhere else.
- **That a test ran.** Whether a test executed is a fact about a run, not about a file. That is why nothing on this
  page claims CI.
- **A rule nobody wrote.** When two features interact in a way no document describes, the docs and the code agree and
  the defect is still real.
- **History.** The checks read the current tree; a defect that only shows across versions is caught by the upgrade and
  backup stages or not at all.
- **Anything against an actively malicious operator.** These checks run on the server's side of the line. The
  operator is the server, so no server-side check closes a row of
  [What holds today](https://github.com/raddatzk/veyq/blob/main/docs/crosscutting/security-model.md#what-holds-today-on-a-client-the-operator-does-not-serve);
  see [Start here](/veyq/security/start-here/).
- **The absence of defects.** Tests show that defects they look for are absent. There has been no external audit
  ([Known limitations](/veyq/security/known-limitations/)).

## Re-run it

Every built stage runs from a checkout with the repository's own commands. The pipeline as a whole runs with
`python3 scripts/run_pipeline.py` (`--dry-run` prints what it would run and what not; `--skip-job web-e2e` leaves out
the job that needs the local compose stack and reports it as not run). Stage by stage: the guards with
`for f in scripts/validate_*.py; do python3 "$f"; done`, the guard gaps with
`python3 scripts/validate_guard_gaps.py --force`, each part's tests with `./gradlew check` in `backend/` and
`npm run check` in `web/`, `admin-web/` and `packages/crypto/`, the end-to-end suites with
`python3 scripts/run_web_e2e.py` (Docker required), and lane L1 with
`python3 scripts/run_stack_e2e.py --app <app image> --admin <admin image>` (Docker, the repository's `npm ci`, and
ports 80 and 443 free). How to reproduce the security-specific checks, and what each pins,
is on [Verify it yourself](/veyq/security/verify-it-yourself/).
