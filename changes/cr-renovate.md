---
id: cr-renovate
title: A self-hosted Renovate proposes every pin's bump with what moves with it, and a check is red on a pin it does not reach
status: planned
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
  - Renovate's Node.js versioning readme says that from Node.js 27 on every major becomes LTS. dependabot.yml ignores
    27 as odd. Read nodejs.org's release schedule before porting the rule.
  - Whether Renovate reads `packageManager` for pnpm. Its npm readme names Corepack only for Yarn.
  - Whether `helm-values` takes a `repo:tag@sha256` value. If it does not, a regex manager with `currentDigest` does.
- **Owner-only steps:**
  - creating the App and storing its key (phase 1);
  - switching on, in the repository settings, anything phase 5 cannot set from a file.

## Design

```
.github/workflows/renovate.yml      schedule (Saturday 05:00 UTC, Dependabot's slot) + workflow_dispatch (input: dry run)
  job renovate                      environment: renovate (main only); permissions: {} (the App token does the writing)
    actions/create-github-app-token@<sha>   client-id: vars.RENOVATE_APP_CLIENT_ID, private-key: secrets.RENOVATE_APP_PRIVATE_KEY
    renovatebot/github-action@<sha>         renovate-version: <image digest>, configurationFile: renovate.json5
                                            env: RENOVATE_ALLOWED_COMMANDS (exact regexes), RENOVATE_IGNORE_SCRIPTS
renovate.json5                      extends nothing remote; one packageRule per policy with its reason in `description`
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

- [ ] **1 the App** (owner). Create a GitHub App `veyq-renovate` and install it on `raddatzk/veyq` only, with no
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
- [ ] **2 dry run beside Dependabot.** Add `renovate.yml` and `renovate.json5` with `dryRun: "full"`. Port the version
  policy: every Dependabot group and `ignore` rule as a `packageRule` with its reason. Measure the Node.js LTS question
  first. Dependabot stays untouched.
  · Done when: a dispatched run's log lists every update Dependabot proposed on its last run, and none it held back.
  Each ported rule is bitten: deleting it makes the dry run propose the held-back version.
- [ ] **3 every pin reached.**
  - Custom managers for each unreached pin.
  - `pin_follow.py` with its self-test: the Helm SHA-256, checked three ways as the Helm pin's comment does (the
    `.sha256sum` file, the tarball's hash, the release notes); `lock.py`; `build.py --write`.
  - Pin the three bare `pip install pyyaml`.
  - `pin_inventory.py` and the `pins` job in `Checks`.

  · Done when: the dry run extracts every pin `pin_inventory.py` finds. A pin added without a manager turns `pins` red,
  and so does one added without a version (two bites). Forcing Helm back to v4.0.1 on a scratch branch makes the dry run
  propose v4.3.2 with the matching SHA-256 in all three workflows.
- [ ] **4 the guards take over the follow's rules.**
  - The append-only check on `verification-metadata.xml` becomes a guard.
  - The Playwright match is completed in `validate_image_pins.py`.
  - Each guard that names Dependabot or the follow is edited to name Renovate.
  - `scripts/release_notes.py` exempts only Dependabot's authors (`is_dependabot`) from the release-note check and
    lists their pull requests as one line under the dependencies. Renovate's App is added to both (its author login,
    read from its first pull request), or every Renovate pull request is red on *The pull request carries a release
    note* and missing from the draft (found 2026-11-22 on this CR's phase 1 pull request).

  · Done when: the trust chain is green. Each moved rule is bitten: a removed metadata entry is red, and a Playwright
  image one minor off is red.
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
- [ ] **6 docs.**
  - `infra/README.md` § Cutting a release: no pin bump, and the Helm paragraph goes.
  - `docs/crosscutting/release-and-deployment.md`: the pin paragraph names Renovate, and the *Decided, not built*
    entry goes.
  - The site's *How veyq is tested*: the stage map's update row.
  - ADR 0158 `Built: yes`, ADR 0057 and 0127's `Replaced in part` marked built.
  - The tuning values the dry run settled (the cool-down's length, majors behind the dashboard's approval or not) as
    comments where `renovate.json5` sets them.

  · Done when: the trust chain and the site's check are green.

## Definition of done

Every pin `pin_inventory.py` finds is extracted by Renovate, and `pins` is red on one that is not. Renovate's pull
requests start `Checks` by themselves and carry every follow-up. Dependabot proposes no version update. The three
updater workflows are deleted, and cutting a release no longer bumps anything.
