---
id: cr-renovate
title: A self-hosted Renovate proposes every pin's bump with what moves with it, and a check is red on a pin it does not reach
status: in-progress
satisfies: []
adrs: ["0158", "0057", "0127"]
invariants:
  - "no LLM anywhere in the update path: a pull request's body carries third-party release notes (ADR 0158 decision 2)"
  - "the App is installed on raddatzk/veyq alone; its private key is an environment secret of `renovate`, whose deployment-branch rule admits `main` alone, so only the Renovate job on the default branch reads it, never a pull request's run"
  - "postUpgradeTasks run only repository scripts that allowedCommands names exactly; dependencies' install scripts stay off"
  - "a version and everything that moves with it (SHA-256, digest, lockfile, verification metadata, rebuilt bytes) change in one pull request, and the guards hold a hand-made bump to the same rules"
  - "nothing merges by itself; Dependabot's version updates are switched off only for an ecosystem Renovate has been shown to cover"
---

## Goal

After this CR, every pin in the tree has an updater
([ADR 0158](../docs/decisions/0158-every-pin-has-an-updater-a-self-hosted-renovate-proposes-each-bump-with-what-moves-with-it.md)).
A self-hosted Renovate, acting as a GitHub App, opens a pull request for each bump, together with what has to move with
it, and that pull request starts `Checks` like a person's. A check is red on any pin Renovate does not extract.

The following are gone at the end:
- Dependabot's version updates;
- the two follow workflows;
- the libwebp updater;
- bumping pins by hand at a release.

## Context & constraints

- **Decisions:** ADR 0158. It replaces in part ADR 0057 § 6 (decision 7) and ADR 0127 decision 4 (decision 8).
- **What is replaced:**
  - `.github/dependabot.yml`. Its comments are the measured source for every rule ported here: they are read, and
    each rule is re-proved.
  - `.github/workflows/dependabot-follow-derive.yml` and `dependabot-follow.yml`.
  - `scripts/dependabot_follow.py` and `scripts/dependabot_follow_selftest.py`.
  - `.github/workflows/webp-encoder-update.yml`.
- **What stays:**
  - Dependabot alerts and `dependency-submission.yml`, the Gradle graph for GitHub's advisories.
  - The guards `validate_image_pins.py`, `validate_action_pins.py`, `validate_build_input_pins.py`,
    `validate_release_identity.py`, `validate_pnpm_installs_gated.py` and `validate_workflow_paths.py`. Any of them
    that names Dependabot or the follow is edited, not dropped.
- **Pins no updater reaches on `main` `23df7b577`** (read 2026-11-22; ADR 0158 § Context has the list). Phase 3 turns
  each one into an extracted dependency:
  - Helm and `cosign-release`;
  - `node-version:` and `java-version:`;
  - the pnpm sites;
  - the root Gradle wrapper;
  - `tests/fuzz/Dockerfile`;
  - Schemathesis and its wheels;
  - PyYAML, pinned and unpinned;
  - the OpenAPI generator;
  - the chart values, `instance_backup.py`'s `*_IMAGE`, `measure_volume.py`'s `PROBE_IMAGE`, and the release
    recipe's busybox;
  - libwebp.
- **To measure, not assume:**
  - ~~Node.js 27 and LTS.~~ Measured 2026-11-23 from nodejs/Release `schedule.json`: 23 and 25 never became LTS, 27
    becomes LTS on 2027-10-20. dependabot.yml's rule held 27 back as odd, which was wrong.
  - ~~Whether Renovate reads `packageManager` for pnpm.~~ It does: the dry run proposed `pnpm` 12.16.1 → 12.21.0 in
    `package.json` and `site/package.json` (depType `packageManager`). The other pnpm sites still need phase 3.
  - ~~Whether `helm-values` takes a `repo:tag@sha256` value.~~ Not needed: one regex manager with `currentDigest` reads
    the chart's values and the Python image constants alike (phase 3).
- **Owner-only steps:**
  - creating the App and storing its key (phase 1);
  - switching on, in the repository settings, anything phase 5 cannot set from a file.

## Design

```
.github/workflows/renovate.yml      schedule (Saturday 05:00 UTC, Dependabot's slot) + workflow_dispatch (input: dry run)
  job renovate                      environment: renovate (main only); permissions: {} (the App token does the writing)
    actions/create-github-app-token@<sha>   client-id: vars.RENOVATE_APP_CLIENT_ID, private-key: secrets.RENOVATE_APP_PRIVATE_KEY
    renovatebot/github-action@<sha>         renovate-version: <tag>@<image digest>
                                            env: the operator's part only (repository, no onboarding, dry run,
                                            RENOVATE_ALLOWED_COMMANDS as exact regexes); install scripts stay off
renovate.json5                      the repository's config, read from the default branch: the version policy, no
                                    forge in it; extends nothing remote; one packageRule per policy with its reason
                                    in `description`
                                    customManagers: regex with currentValue/currentDigest for every unreached pin
                                    postUpgradeTasks: scripts/pin_follow.py <kind> (Helm SHA-256, lock.py, build.py --write)
                                    vulnerabilityAlerts on; minimumReleaseAge; Dependency Dashboard issue
scripts/pin_follow.py               the follow-ups a manager cannot write, one subcommand per kind, standard library;
                                    selftest scripts/pin_follow_selftest.py (offline: forged release pages)
scripts/pin_inventory.py            enumerates the pins in the tree by shape; compares them with Renovate's extraction
Checks job `pins`                   Renovate `--platform=local` (no token, the pinned version) writes its extraction;
                                    pin_inventory.py is red on a pin found but not extracted, and on a pin with no version
```

- **The inventory's shapes** are the ones ADR 0057 § 6 lists:
  - `repo:tag@sha256:` references;
  - `FROM` lines;
  - `uses:` with a SHA;
  - `*_VERSION` / `*_SHA256` pairs in workflow `env:`;
  - setup-action `*-version:` inputs;
  - `pnpm@` / `packageManager`;
  - wrapper properties;
  - requirement files;
  - `*.pin` files.

  A new shape is added to the inventory, never to an exception list.
- **Rules that move from `dependabot_follow.py` into guards:**
  - `verification-metadata.xml` only gains entries;
  - the Playwright image matches `@playwright/test`. `validate_image_pins.py` already holds part of this.
- **Grouping:**
  - Playwright: npm, docker-compose and the MCR image in one pull request.
  - OpenAPI generator: `openapitools.json` ×2, the version catalog and `scripts/openapi_generator_jar.sha256` in one
    pull request.
  - Helm: its three workflow `env:` blocks in one pull request.

## Phases

- [x] **1 the App** (owner). Create a GitHub App `veyq-renovate` and install it on `raddatzk/veyq` only, with no
  webhook. Repository permissions:
  - contents: write;
  - pull requests: write;
  - workflows: write;
  - issues: write, for the Dependency Dashboard;
  - commit statuses: write. Renovate's GitHub readme lists it (read 2026-11-22), and `minimumReleaseAge` reports
    through the status `renovate/stability-days`;
  - Dependabot alerts: read;
  - checks: read;
  - metadata: read.

  The readme's list also has administration: read, checks: write and merge queues: write. They are left out:
  - Administration: Renovate reads branch protection with it (`checkBranchProtectionForForceRebase` in its
    `lib/modules/platform/github/index.ts`, read 2026-11-22), to rebase on its own when `main` demands up-to-date
    branches. `main`'s ruleset does not demand that, and the read's failure is handled. Phase 2's dry run shows whether
    the missing permission costs anything.
  - Checks: write would let the App create a check run of any name, `Pipeline green` included.
  - The repository has no merge queue.

  Create an environment `renovate` whose deployment-branch rule admits `main` alone, and store the App's Client ID as
  its variable `RENOVATE_APP_CLIENT_ID` and the private key as its secret `RENOVATE_APP_PRIVATE_KEY`.
  `actions/create-github-app-token` v3 marks the App ID input deprecated, so the Client ID is what the job reads.
  `.github/workflows/renovate.yml` holds, until phase 2, only the job that proves the App: it mints a token and is red
  unless the token reaches this repository and no other.
  · Done when: the variable and the secret exist in `renovate`, and a dispatch of `renovate.yml` on `main` is green.
  Measured 2026-11-23: run 45140008011 on `main` `8869a0322` was green and logged `App veyq-renovate reaches:
  raddatzk/veyq`.
- [x] **2 dry run beside Dependabot.** Add `renovate.yml` and `renovate.json5` with `dryRun: "full"`. Port the version
  policy: every Dependabot group and `ignore` rule as a `packageRule` with its reason. Measure the Node.js LTS question
  first. Dependabot stays untouched.
  · Done when: a dispatched run's log lists every update Dependabot proposed on its last run, and none it held back.
  Each ported rule is bitten: deleting it makes the dry run propose the held-back version.
  Built 2026-11-23 (PR #54): `renovate.json5` ports every group and `ignore` rule, and `renovate.yml`
  runs Renovate 44.257.9 with `RENOVATE_DRY_RUN=full`, weekly in Dependabot's slot and on dispatch. Measured in a local
  lookup-only run of the pinned image against `main` (`--platform=local`, report file): it proposes everything
  Dependabot's last run proposed that `main` does not hold yet (#46 mailpit, traefik, distroless, node 26; #47 astro;
  #48 the Gradle wrapper and awssdk; #49 @lucide/vue; #49's vitest and vue-i18n were already on `main`), and nothing a
  rule holds back. Bitten in one run with every hold-back rule removed: TypeScript 7, openapi-generator 7.27, MariaDB
  13, Garage 2.5, nginx 1.31, Ubuntu 26.04 and Java 26/27 each came back. Measured on GitHub 2026-11-23: dispatch
  run 45149606386 on `main` `3bf0aef2f` was green and logged `DRY-RUN: Would commit files to branch` for the same
  seven branches as the local run (actions, gradle minor and patch, images, node-major, npm minor and patch, the site
  group, postcss-nested 9), then `Repository finished`. `gh run view --log` cuts this log short; the run's log archive
  (`gh api repos/raddatzk/veyq/actions/runs/<id>/logs`) holds all of it. Two warnings, both for later phases: the
  Gradle wrapper's files are not regenerated, because `gradleWrapper` is not in `allowedUnsafeExecutions` (phase 3);
  and branch protection cannot be read without administration, which Renovate handles as foreseen in phase 1.
- [x] **3 every pin reached.**
  - Custom managers for each unreached pin.
  - `pin_follow.py` with its self-test: the Helm SHA-256, checked three ways as the Helm pin's comment does (the
    `.sha256sum` file, the tarball's hash, the release notes); `lock.py`; `build.py --write`.
  - Pin the three bare `pip install pyyaml`.
  - Decide `allowedUnsafeExecutions: ["gradleWrapper"]` (phase 2's run warned that the wrapper update leaves its
    files stale). It runs the repository's Gradle, with the build's plugins, in the job that holds the App's token:
    a malicious plugin version could read the token from Renovate's process. The alternative is a `postUpgradeTask`
    or a `Checks` job that regenerates the wrapper files, and a guard that refuses a wrapper bump without them.
  - `pin_inventory.py` and the `pins` job in `Checks`.

  · Done when: the dry run extracts every pin `pin_inventory.py` finds. A pin added without a manager turns `pins` red,
  and so does one added without a version (two bites). Forcing Helm back to v4.0.1 on a scratch branch makes the dry run
  propose v4.3.2 with the matching SHA-256 in all three workflows.

  Built 2026-11-23. Measured with the pinned Renovate image on the branch's tree:
  - `pin_inventory.py` against Renovate's extract report: `297 pins in 42 files, every one reached by Renovate`. Its
    first run was red on three catalog versions only build code reads (`java`, `opentelemetry`, `mariadb-jdbc`);
    three regex managers reach them now.
  - Bites, each a real extract: a new `KUBECONFORM_VERSION`/`_SHA256` pair in a workflow `env:` was red on both lines;
    `pip install requests` in a workflow was red as versionless; a new `PROBE_IMAGE` constant from a registry no
    manager reads and a `pip install requests==2.32.0` were red as unreached.
  - Helm forced back to v4.0.1 in a copy: the lookup proposed v4.3.2 for all three workflows, in the one branch
    `renovate/helm`. The SHA-256 is `pin_follow.py helm`'s: run on a copy at v4.1.4 it wrote `70b2c30a…` into all
    three, the same from get.helm.sh's `.sha256sum`, the tarball and the release notes.
  - Renovate runs the task itself, measured on GitHub 2026-11-23 (dispatch run 45169620214 on `main` `561bbdcc4`, its
    full log from the run's logs archive): in `renovate/openapi-generator` it executed `python3 scripts/pin_follow.py
    openapi-generator-jar`, which wrote 7.27.0's `db9c4b73…` (matching Central's `.sha1` and `.md5`) and dropped
    7.23.0's line; the task's `fileFilters` kept `scripts/openapi_generator_jar.sha256` and left out the
    `__pycache__` the run wrote, and the branch is a would-commit. PyYAML's 6.0.4 was not proposed in that run: it was
    uploaded 2026-11-20 23:34 UTC, inside the three-day cool-down, so `internalChecksFilter` held the branch. The
    `pypi-hashes` task is proven by its live probe and selftest; its first run under Renovate is the scheduled run of
    2026-11-28, read before phase 5 starts.
  - `pin_follow_selftest.py` (31 cases) and `pin_inventory_selftest.py` run in `Checks`' `guards` job, each bitten by
    breaking one of its checks.
- [x] **4 the guards take over the follow's rules.**
  - The append-only check on `verification-metadata.xml` becomes a guard.
  - The Playwright match is completed in `validate_image_pins.py`.
  - Each guard that names Dependabot or the follow is edited to name Renovate.
  - `scripts/release_notes.py` exempts only Dependabot's authors (`is_dependabot`) from the release-note check and
    lists their pull requests as one line under the dependencies. Renovate's App is added to both (its author login,
    read from its first pull request), or every Renovate pull request is red on *The pull request carries a release
    note* and missing from the draft (found 2026-11-22 on this CR's phase 1 pull request).

  · Done when: the trust chain is green. Each moved rule is bitten: a changed metadata entry is red, and a Playwright
  image one minor off is red.

  Built 2026-11-23:
  - `scripts/verification_metadata_diff.py --base <commit>`, run in `Checks`' `guards` job against a pull request's
    base or a push's previous tip (decision log). Red on a changed `<configuration>`, a kept artifact's checksum changed
    or of another kind, an `also-trust` added, a deleted file, a DOCTYPE, a duplicate component, a malformed checksum;
    a dropped component or artifact and a moved file pass. Run against every one of the 15 commits on `main` that touch
    a `gradle/verification-metadata.xml` (each against its first parent, in a detached worktree): all green.
    `verification_metadata_diff_selftest.py` (17 verdict cases, 4 through the command in a throwaway repository) runs
    beside it; bitten by switching off the checksum compare (3 cases red), the `<configuration>` compare (2 red) and
    the move pairing (1 red), with a no-op edit as the control (green).
  - `validate_image_pins.py` reads every tracked `pnpm-lock.yaml`'s resolved `@playwright/test`, `playwright` and
    `playwright-core` (6 resolutions today, all 1.65.1). Bitten: `playwright-core` at 1.66.1 in the lockfile red, the
    image at v1.66.1 red, a lockfile resolving none of them red.
  - `release_notes.py` exempts `UPDATERS`, Dependabot's two logins and `veyq-renovate[bot]`, matched exactly; its
    self-test gained Renovate's exemption, the hosted `renovate[bot]` not exempt, and a Renovate pull request in the
    draft's dependency line. Bitten by dropping `veyq-renovate[bot]` (red) and adding `renovate[bot]` (red).
  - `validate_workflow_paths.py` rule 6: renovate.json5's 20 path entries (managers, rules, task filters, ignored
    paths). Bitten six ways, each red: a manager regex at a moved file, a stale `matchFileNames`, a stale `fileFilters`,
    a stale literal `ignorePaths`, a single-quoted key (no JSON5 this reader takes), a regex Python cannot compile.
  - Reworded for Renovate: `validate_image_pins.py` (one tag per repository, the Playwright pair),
    `validate_action_pins.py` (the version comment: Renovate's dry run read `currentValue` `v7.0.1` from
    `actions/checkout@3d3c42e… # v7.0.1`), `validate_release_identity.py` and `checks.yml` (an updater's pull request
    writes nothing), `release-note.yml`.
  - Every `validate_*.py` green, `validate_guard_gaps.py --force` green (104 cases), the five self-tests green.
- [ ] **5 the switch, one ecosystem at a time.** Order: actions → docker/compose (with the chart and the constants) →
  npm → gradle → the rest. For each ecosystem:
  - Renovate's dry run for it is turned off.
  - Dependabot's entry for it is removed.
  - The follow code that served it is deleted.

  Then delete `webp-encoder-update.yml` once libwebp's manager has opened a real pull request. Switch Dependabot's
  security pull requests off and Renovate's `vulnerabilityAlerts` on.
  · Done when: each ecosystem's first real pull request started `Checks` by itself (no reopen, no approval), carried
  everything that moves with it, and went green. `dependabot.yml` keeps no version updates, and the follow workflows
  and `dependabot_follow*.py` are gone.

  Progress:
  - [x] **5a actions.** Renovate writes (no `RENOVATE_DRY_RUN`); the last rules of renovate.json5 hold every other
    manager off, and `node`/`java-jdk` too (decision log); `dependabot.yml` loses its `github-actions` entry. No follow
    code served actions. Measured on the branch's tree with the pinned image: a lookup run enables only
    `renovate/actions` (cosign v3.1.4 in publish.yml and release.yml, Renovate 44.258.1), 174 dependencies `disabled`;
    an extract run as `pins` makes it is still `304 pins in 42 files, every one reached by Renovate`. On GitHub, after
    #63: the first dispatch (run 45197394644) was green and opened nothing; its log had the push refused, "refusing to
    allow a GitHub App to create or update workflow … without `workflows` permission" (the App had not been granted
    it; the owner set it). The next dispatch (run 45198639207) opened #65, `chore(deps): update actions`: cosign v3.1.4
    at its four sites and Renovate 44.258.1 with its digest. `Checks`, `Trust chain` and `CodeQL` started on their own,
    the release-note check passed on the `UPDATERS` exemption, and all 31 checks went green; merged.
  - [x] **5b docker and compose**, with the chart's values and the image constants (their regex managers), and the
    runtimes (`node`, `java-jdk`: the images', setup-node's, setup-java's and the version catalog's). Dependabot keeps
    only the Emscripten image; the images follow leaves `dependabot_follow.py` (with its `--config`). Held to later
    steps, each by name: the Playwright image (5c, with `@playwright/test`), Emscripten (5e, `build.py --write`), the
    fuzzer's Python image (5e, `lock.py`; it had no updater before this CR either). Measured on the branch's tree: a
    lookup proposes `renovate/images` (eclipse-temurin's digest), and holds exactly those three images; the `pins`
    extract is still 304 pins, every one reached. On GitHub, after #66: the dispatch (run 45212583696) opened #67,
    eclipse-temurin's new digest (`docker buildx imagetools inspect`: an OCI index carrying linux/amd64 and
    linux/arm64/v8), and `Checks`, `Trust chain`, `CodeQL` and the release-note check started on their own; all 32
    went green (merged).
  - [x] **5c npm**, both workspaces, with pnpm (`packageManager` and the global installs' regex manager) and the
    Playwright image, one `playwright` group with `@playwright/test`; `dependabot.yml` loses both npm entries, and
    `dependabot_follow.py` its Playwright follow with the image-site helpers and the registry client, leaving the Gradle
    follow alone. Renovate updates `pnpm-lock.yaml` itself: phase 2's dry run on GitHub (run 45169620214) ran `pnpm
    update … --lockfile-only --recursive --ignore-scripts --ignore-pnpmfile` with pnpm 12.16.1 for
    `renovate/npm-minor-and-patch`, which passed pnpm's supply-chain policies and wrote one lockfile. Measured on the
    branch's tree: a lookup proposes `renovate/npm-minor-and-patch`, `renovate/pnpm` (12.22.0 at all eight sites) and
    `renovate/postcss-nested-9.x` (the site's override), and holds only Emscripten and the fuzzer's Python image among
    the images; the `pins` extract is still 304 pins. On GitHub, after #68: the dispatch (run 45235243109) opened #71
    (pnpm 12.22.0 at every site, both lockfiles written by Renovate's pnpm) and #72 (the site's postcss-nested
    override, a major of its own); `Checks` started on both by itself, and all 38 and 8 checks went green.
  - [ ] **5d gradle** and **5e the rest**, one pull request with phase 6 (owner 2026-11-23: one pull request per
    feature). Built:
    - The gradleWrapper measurement, read in the pinned image's `lib/modules/manager/gradle-wrapper/artifacts.js`:
      without `gradleWrapper` in `allowedUnsafeExecutions`, `updateArtifacts` returns null ("Not allowed to execute
      gradle"), so a wrapper bump moves `distributionUrl` alone. `pin_follow.py gradle-distribution` (a
      `postUpgradeTask` of the gradle-wrapper manager) writes `distributionSha256Sum` from services.gradle.org's
      `.sha256` and the zip's own hash, required equal; the JAR stays. Its self-test: 56 checks, bitten seven ways
      (agreement, the `.sha256` body, the URL's shape, all-or-nothing, a duplicate line, the sum's form, tracked files
      only). Live, on a throwaway tree with the launcher at 9.9.1: it wrote `d8a18a7a…ba228`, which is
      services.gradle.org's `.sha256` for 9.9.1.
    - The Renovate follow (`renovate-follow-derive.yml`, `renovate-follow.yml`, `scripts/renovate_follow.py`), moved
      from the Dependabot follow with its history: the Gradle metadata of both builds (src/backend and the root
      launcher), the WebP encoder's `build.py --write`, the fuzzer's `lock.py`; the writing job pushes with the App's
      token minted for `contents: write` alone. Its self-test: 50 checks, bitten ten ways (the libwebp commit, image
      and path checks, the lock's line check, the gate's author and commit-author checks, the skip of its own commit,
      the root build's input set); the path bite first stayed green, because the forged file did not exist and another
      check refused it, so the case now forges an existing file. On the real tree: the committed requirements.txt and
      encoder files are accepted as written, and `git ls-remote` names `4fa21912…` for v1.6.0, the pin's commit.
    - renovate.json5 holds no ecosystem off: every manager proposes; the WebP encoder's two inputs are a group of
      their own; the fuzzer's Python image is held to 3.13 (`lock.py` hashes `cp313` wheels); `vulnerabilityAlerts`
      is on. `.github/dependabot.yml` and `webp-encoder-update.yml` are deleted, and `validate_workflow_paths.py` loses
      its Dependabot rule.
    - Measured on the branch's tree with the pinned image: a lookup proposes ten branches (`gradle-minor-and-patch`
      with the root wrapper 9.9.0 → 9.9.2, `openapi-generator` 7.27.0, `pyyaml-6.x`, `schemathesis-4.x`, `webp-encoder`
      with Emscripten 7.0.1, `images`, `npm-minor-and-patch`, `pnpm`, `postcss-nested-9.x`, the MariaDB driver's major)
      and no dependency is disabled; the `pins` extract: `300 pins in 41 files, every one reached by Renovate`.
    After the merge (#70), the dispatch (run 45245170891) rebased #71 off the deleted `webp-encoder-update.yml` and
    opened #73–#77. Measured on them: `gradle-distribution` wrote 9.9.2's `971fcd71…c76b` into the root wrapper (#74),
    services.gradle.org's `.sha256`; `openapi-generator-jar` wrote 7.27.0's `db9c4b73…` (#76); the Renovate follow
    pushed `tests/fuzz/requirements.txt` onto #75 as `veyq-renovate[bot]`, and its own commit started the derive again,
    which skipped. Three derives were red: #74's root launcher step, because a cold home that fetched nothing has no
    module cache and the listing refused it (fixed: the root step lists an empty home as no module); #77's on Maven
    Central's 429 while resolving buildSrc (a rerun); #76's on 149 backend tests the 7.27.0 generator breaks (a real
    red pull request, left to a person). Still open: the follow's metadata commit on a Gradle pull request, the
    encoder's rebuild, PyYAML, Helm when it moves.
    Open until after the merge, as written then: each ecosystem's first real pull request (Gradle with the follow's metadata commit,
    the follow's push starting `Checks`, the generator, Helm when it moves, PyYAML, Schemathesis with the lock, the
    encoder with its rebuild), and the owner's switch of Dependabot's security pull requests (below).
- [x] **6 docs** (in the 5d/5e pull request): `infra/README.md`, `release-and-deployment.md` (the *Decided, not
  built* entry is gone), the site's *How veyq is tested* (the budgets, the updater beside the stages, ADR 0158 among
  its sources), ADR 0158 `Built: yes` with decisions 4 and 8 amended to what was built, ADR 0057 and 0127 marked built,
  the cool-down's comment; every file that still said Dependabot proposes something now names Renovate.
  - `infra/README.md` § Cutting a release: no pin bump, and the Helm paragraph goes.
  - `docs/crosscutting/release-and-deployment.md`: the pin paragraph names Renovate, and the *Decided, not built*
    entry goes.
  - The site's *How veyq is tested*: the stage map's update row.
  - ADR 0158 `Built: yes`, ADR 0057 and 0127's `Replaced in part` marked built.
  - The tuning values the dry run settled (the cool-down's length, majors behind the dashboard's approval or not) as
    comments where `renovate.json5` sets them.

  · Done when: the trust chain and the site's check are green.

## Decision log

- **2026-11-23, phase 2.** Renovate's own config is the repository's `renovate.json5`, and the workflow sets only the
  operator's part, so the policy runs unchanged on another forge (ADR 0158 § Consequences).
- **2026-11-23, phase 2.** Image updates take `minimumReleaseAgeBehaviour: timestamp-optional`: a digest move has no
  release time, and Docker Hub's tag pages past the tenth answer 403, so with the timestamp required no digest update
  would ever leave the cool-down (measured in the local run). An image with a release time still waits.
- **2026-11-23, phase 2.** The `images` and `actions` groups keep their majors (`separateMajorMinor: false`), as
  Dependabot's groups did.
- **2026-11-23, phase 2.** A runtime major is one pull request of its own across managers: `node major` (the images'
  `node`, setup-node's `node-version`, `@types/node`) and `java major` (`eclipse-temurin`, setup-java's
  `java-version`). It never holds back the images or actions groups. `@types/node` then proposes only its newest
  allowed version, so its minor updates wait with a pending Node major; that replaces Dependabot's held-back
  `@types/node` major.
- **2026-11-23, phase 2.** Java on its LTS releases by a positive regex (25, 29, 33, 37): a release off Oracle's
  cadence is held, never proposed by mistake. New, not ported: the github-actions manager reaches `java-version`,
  which no Dependabot ecosystem did, and proposed Java 27.
- **2026-11-23, phase 2.** Dependabot's Node rule (no 23, 25, 27) is not ported: removing it changed no proposal,
  because 26 is newer than 23 and 25, and 27 is LTS.
- **2026-11-23, phase 2.** TypeScript's major is held by `allowedVersions: "<7"`, not by `enabled: false` on the
  major: Renovate's report still lists an update a rule disabled, and phase 3's check reads that report.
- **2026-11-23, phase 2.** Found reached in the dry run, so phase 3 has less to do: the root Gradle wrapper
  (`gradle-wrapper`), `tests/fuzz/Dockerfile` (`dockerfile`), setup-node's and setup-java's versions and
  `cosign-release` (`github-actions`, depType `uses-with`), and `pnpm` in `packageManager`.

- **2026-11-23, phase 3.** The five bare `pip install pyyaml` install from `scripts/requirements-release.txt`, hashed,
  like the jobs with write permissions: PyYAML has one pin, which `pip_requirements` reads, and `pin_follow.py
  pypi-hashes` rewrites its hashes on a bump.
- **2026-11-23, phase 3.** `tests/fuzz/requirements.txt` is in `ignorePaths`: it is written whole by `lock.py`, and
  Renovate read as requirements would bump each transitive package alone. Its one input, `SCHEMATHESIS` in `lock.py`,
  has a regex manager. Running `lock.py` as a postUpgradeTask needs the Dockerfile's Python 3.13 image, so a
  Schemathesis bump has no follow-up yet; the same holds for libwebp's and Emscripten's `build.py --write`. Both are
  phase 5's, before those ecosystems leave the dry run.
- **2026-11-23, phase 3.** `gradleWrapper` stays out of `allowedUnsafeExecutions`: it would run the repository's
  Gradle, with the build's plugins, in the job that holds the App's token. Phase 5 measures whether a wrapper bump
  without it (properties only) is green, and if not, a `Checks`-side regeneration comes before an exception.
- **2026-11-23, phase 3.** Renovate's own version is a regex manager on `renovate.yml` (the github-actions manager
  refuses `<version>@<digest>`), grouped with the actions; `pins` reads the image from that one line.
- **2026-11-23, phase 3.** `pins` runs in `checks.yml`, so a docs-only or site-only commit does not run it (the
  workflow's `paths-ignore`). A site-only change can touch `site/package.json`; its pins are checked by the next
  commit that runs `Checks`. Accepted: no new shape lives only under `site/`.

- **2026-11-23, phase 4.** The metadata rule is no `validate_*.py`: the glob reads one tree and never history (the
  trust-chain skill's fourth edge), and "only gains" is a property of a change. It is a script `Checks` runs with the
  event's base, fetched alone (`--depth=1`); a first push or a local `run_pipeline.py` run has none and checks the form.
- **2026-11-23, phase 4.** Dropping a metadata entry is allowed, whole components and single artifacts: the CR's
  "a removed entry is red" was the follow's rule over a file Gradle wrote, which only appends. Over a change it was
  wrong twice in history: 7f28ce7d2 dropped `kotlin-stdlib-2.3.21.pom` from a kept version, and 408ce9c73 moved the
  file under `src/`. A drop cannot widen trust, since Gradle refuses an artifact with no entry. What is red is a
  changed one: the trust rules, or the bytes a kept artifact is trusted under.
- **2026-11-23, phase 4.** No exception exists for a legitimate change of the trust rules or a re-published artifact:
  such a pull request merges red, by the owner, which is the decision the check is there to make visible.
- **2026-11-23, phase 4.** Completing the Playwright match meant the lockfile: the manifest's exact pin was already
  held to the image, but what installs is what pnpm resolves, which an override or a second copy can move.
- **2026-11-23, phase 4.** Renovate's author login is `veyq-renovate[bot]`, read from the App's bot account (`gh api
  /users/veyq-renovate%5Bbot%5D`: a Bot, id 359998714), not from a first pull request as the phase said: phase 2 and 3
  open none, and the first real one comes in phase 5, which would then be red on its release note.
- **2026-11-23, phase 4.** `validate_workflow_paths.py` reads renovate.json5 too, through a JSON5 subset reader in the
  guard (the standard library has none, and a dependency for one file is not worth it): a rule whose file moved stops
  applying without a word, which `pins` sees only for a manager.

- **2026-11-23, phase 5.** `dryRun` is one setting for the whole run, so "the dry run is turned off per ecosystem"
  became: the dry run is off, and the ecosystems not moved yet are `enabled: false` by the last rules of renovate.json5.
  Their lookups are no longer logged; phases 2 and 3 compared them with Dependabot already, and Dependabot keeps
  proposing them until their step. A disabled dependency is still extracted, so `pins` stays meaningful (measured).
- **2026-11-23, phase 5.** `node-version` and `java-version` are the github-actions manager's, but their majors are one
  group with the images' `node`/`eclipse-temurin` (phase 2), so they stay held until the docker step: enabled alone
  they could open a runtime major the images do not follow.

- **2026-11-23, phase 5.** A Renovate run whose branch push fails is green: Renovate logs the refusal at DEBUG and
  exits 0 (run 45197394644, the missing `workflows` permission). Nothing showed it but reading the log. Making such a
  run red is not built yet; it is the owner's call whether it belongs to this CR.
- **2026-11-23, phase 5.** An image is held back by name, in the last rules, when what moves with it is not written
  in its step yet, rather than splitting a manager: the manager moves whole, the exceptions are listed with the step
  that takes them.

- **2026-11-23, phase 5.** A follow-up that needs the bumped tree executed is the Renovate follow's, not a
  `postUpgradeTask`: Renovate's Gradle artifacts run the repository's Gradle in the job holding the App's key, and its
  container has no Docker for `lock.py` or `build.py`. The follow keeps the Dependabot follow's shape (a read-only
  derive, a `workflow_run` writer from the default branch) and pushes with the App's token minted for contents alone,
  so `Checks` starts without an approval. Its commit is authored as the App's bot: the gate still sees a branch only
  Renovate wrote, and Renovate still rebases it.
- **2026-11-23, phase 5.** The root launcher's metadata is followed too: Dependabot never reached the root wrapper,
  Renovate does, and Gradle 9.9.0's move changed that file (59a8423c0).
- **2026-11-23, phase 5.** libwebp and Emscripten are one pull request of their own, so a rebuild never holds back the
  images group; the fuzzer's Python image stays on 3.13 by `allowedVersions`, since `lock.py`'s wheel filter names
  `cp313`.
- **2026-11-23, phase 5.** Dependabot's security pull requests are switched off in the repository's settings, an
  owner step; Renovate's `vulnerabilityAlerts` defaults (read in the pinned image: no cool-down, no group, immediate)
  are what ADR 0158 decision 6 asks for, so the config only turns it on.
- **2026-11-23, phase 5.** 5d, 5e and phase 6 are one pull request (owner: one pull request per feature); the proofs
  that need Renovate on `main` follow the merge and go into the closing change.
- **2026-11-24, after phase 6.** The first Emscripten bump (#88) found `build.py --write` refusing the image the
  Dockerfile had moved to: the write held the pin's old image to the new FROM. Fixed in #89 — the write takes the
  Dockerfile's image into the pin, the comparison still refuses a mismatch.
- **2026-11-24, after phase 6.** The cool-down is three days and thirty minutes: Renovate adds its default
  `minimumReleaseAgeBuffer` (30 minutes, read in 44.258.3's config/options) to `minimumReleaseAge`. Left at the default.
- **2026-11-24, after phase 6.** A lookup that fails in a run (Maven Central's 429 on the shared runner addresses) makes
  Renovate leave every group branch unchanged, in a green run. Owner: Renovate runs three times on Saturday morning
  (05:00, 07:00, 09:00 UTC) — neither a failed lookup nor a failed branch push turns the run red; a mirror registry and
  a persistent cache were weighed and not taken (a mirror is a second source beside Gradle's own and one vendor's; a
  weekly run outlives the cache).

## Definition of done

Every pin `pin_inventory.py` finds is extracted by Renovate, and `pins` is red on one that is not. Renovate's pull
requests start `Checks` by themselves and carry every follow-up. Dependabot proposes no version update. The three
updater workflows are deleted, and cutting a release no longer bumps anything.
