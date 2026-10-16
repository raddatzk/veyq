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
and this page follows it: a stage moves to *runs in CI* only after a CI log shows it executed.
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

Each stage proves one thing. The table says what, what it cannot see, and its status.

| Stage | What it proves | What it cannot see | Status |
|---|---|---|---|
| **Guards** (`scripts/validate_*.py`) | The tree keeps its written rules: every reference resolves, every contract field declares what the server may read, every image is pinned by digest, the production recipe is hardened. | That what a reference points at carries weight — a cited test that asserts nothing passes. | Built; runs locally. |
| **Guard gaps** (`validate_guard_gaps.py`) | The guards still reject the forgeries they claim to reject, and their documented limits are still exactly where they are written. | A guard's limit that was never documented. | Built; locally it skips while no guard changed, CI never skips. |
| **Surface checks** (`./gradlew check`, `npm run check` per package) | Each part's unit and integration tests: the backend against an in-memory database and in-process fakes, the web client, the admin console and the crypto package in their test runners. | The real database, the real object store, the real browser. | Built; runs locally. |
| **Crypto conformance** | The bytes of the cryptographic suite cannot move silently: every primitive is pinned to published test vectors. | Whether a correct primitive is used correctly. | Built; see [Verify it yourself](/veyq/security/verify-it-yourself/). |
| **Mutation** | The tests of the scoped crypto and policy files fail when that code changes. | Files outside the scope. | Built; runs locally. |
| **Races on MariaDB** | Concurrent writes behave on the production database as they do in the in-memory one. | Races no test provokes. | Built; runs locally. |
| **API end-to-end** (`scripts/run_web_e2e.py`) | Real client cryptography against a live backend, database and object store, a fresh instance per suite; backup and restore round trips; no sealed plaintext or capability token readable in the database, the store, the HTTP traffic, the logs, the SQL statements or the mail. | Only the values the suite unsealed; no browser, no production image. | Built; runs locally. |
| **Upgrade proof** (`run_web_e2e.py --upgrade`) | Content sealed by the previous version opens after a restore onto this one, which migrates it. | The upgrade of a running instance; the shipped images. | Built; its CI job stays off until the first release. |
| **Stack lane L1** — the candidate, fresh (`scripts/run_stack_e2e.py`) | The two images that ship, installed the way a Server Owner installs them — `veyqctl install` from the candidate's own release files — with a real ACME certificate from a test CA, driven in Chromium: the domain says it is not set up, the Server Owner is created and its Emergency Kit read back, the bundled store connected and checked, the first tenant created; its owner accepts on the tenant's own domain and certificate, writes a Memory with a photo and shares it read-only; a second browser signs in with only the password and the Secret Key and sees the photo decrypted; an anonymous visitor opens the link. Then the first feature scenario: the Server Owner switches drop boxes, adding a device from another one, unlocking without a password and remembered devices off and on again in the console, and the web client loses and regains each of them; a trusted and a remembered device ask for the password and the Secret Key while their switch is off, delete or replace none of their keys on the server, and open as before once it is back on. Every test names the acceptance criterion it covers, and a check holds each name to a criterion that exists. No page request to any origin but the instance — the one breached-password query is answered locally. Afterwards every value the browser typed (the password, the Secret Key, the names, the photo's name and the text inside it) is searched for, and must be absent, in the instance's database, its bucket, every container log and every SQL statement the database received — the same sensors as the API end-to-end suites. A run that executes no test is red. | Only what the browser typed, not everything the page sealed (the API suites cover that half); no HTTP traffic recorded, so no capability token searched for here; one tenant, one Memory, one photo, one link, one feature scenario; no browser restart. The certificate comes from a test CA that is told every challenge is valid. The images are local builds installed unsigned, so `veyqctl`'s signature check ([ADR 0096](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0096-veyqctl-manages-an-instance-from-immutable-releases.md)) is not exercised here. | Built, with its pipeline job (per architecture, after the image build; the publish waits for it); runs locally (75–90 s measured on a laptop); no CI run yet. |
| **Stack lanes L2 and L3** — upgrade in place, backup import | The previous release upgraded in place with `veyqctl upgrade`; every archived backup of the major restored into the candidate with `veyqctl restore`. | Anything the scenarios do not do. | Designed ([ADR 0090](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0090-end-to-end-tests-boot-the-release-candidate.md), ADR 0091); not built. |

## How a release will be gated

Every commit runs every stage in one pipeline, the image build beside the tests, and a hand-started publish waits
for every stage to be green — built as a workflow file, never yet run, since there is no remote. The rest is designed,
not built (ADR 0091): a green commit's images published, as a signed snapshot on exactly the bytes that were tested, with each stack lane's result
attached as a signed attestation. A release does not build again: it gives that snapshot's bytes the release name and
signature, after checking the attestations. So a release is always bytes every test saw, and anyone can check which
release they run against its signature ([Verify a release](/veyq/run/verify-a-release/)).

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

Every built stage runs from a checkout with the repository's own commands: the guards with
`for f in scripts/validate_*.py; do python3 "$f"; done`, each part's tests with `./gradlew check` in `backend/` and
`npm run check` in `web/`, `admin-web/` and `packages/crypto/`, the end-to-end suites with
`python3 scripts/run_web_e2e.py` (Docker required), and lane L1 with
`python3 scripts/run_stack_e2e.py --app <app image> --admin <admin image>` (Docker, the repository's `npm ci`, and
ports 80 and 443 free). How to reproduce the security-specific checks, and what each pins,
is on [Verify it yourself](/veyq/security/verify-it-yourself/).
