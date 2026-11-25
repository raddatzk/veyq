# ADR 0158 — Every pin has an updater: a self-hosted Renovate proposes each bump with what moves with it

- Status: Accepted
- Built: yes (2026-11-23; decisions 4 and 8 amended the same day, below; proven on Renovate's own pull requests
  2026-11-23 to 2026-11-25, § Consequences, Helm's follow excepted)
- Date: 2026-11-22
- Model: [release-and-deployment](../crosscutting/release-and-deployment.md)

## Context

[ADR 0057](0057-release-identity-and-deployment-recipe.md) § 6 pins everything a release runs and is built from, and
says that bumping a pin is part of cutting a release. Dependabot proposes the bumps it can reach. Two follow workflows
(`dependabot-follow-derive.yml`, `dependabot-follow.yml`, `scripts/dependabot_follow.py`) write what has to move with a
bump that Dependabot does not write. A third workflow (`webp-encoder-update.yml`, [ADR 0127](0127-the-web-client-encodes-webp-with-its-own-build-of-libwebp.md)
decision 4) is an updater of its own.

What the tree held on `main` `23df7b577` (read 2026-11-22):

- **Pins that no updater reaches**, so they move only when someone remembers them:
  - Helm, `HELM_VERSION` and `HELM_SHA256` in the `env:` of three workflows. It sat at v4.0.1 from 2026-10-14 until
    cutting v1.260 on 2026-11-22. In that time Helm 4.1.4 shipped three security fixes, and nothing proposed the bump.
  - `cosign-release`, an action input.
  - `node-version:` and `java-version:` in about twenty jobs.
  - pnpm, at twelve sites: `packageManager`, `npm install -g pnpm@`, and two Dockerfiles.
  - The root launcher's Gradle wrapper. Dependabot's `gradle` entry reads `src/backend` only, so PR #36 moved
    `src/backend` to 9.9.1 and left the root at 9.9.0.
  - `tests/fuzz/Dockerfile`'s base image, Schemathesis and its hashed wheels, PyYAML in
    `scripts/requirements-release.txt`, and three `pip install pyyaml` steps with no version at all.
  - The OpenAPI generator, held back by an `ignore` rule and moved by hand.
- **Bumps that are red until something else follows.** Every Dependabot pull request of 2026-11-12 needed a follow
  (dependabot.yml's header):
  - Image pins the chart, the backup tool and the volume probe share with the compose files. Dependabot reads the
    compose files but none of these.
  - The Playwright image, which must match `@playwright/test` from another ecosystem.
  - Gradle's `verification-metadata.xml`.
  - The Emscripten image, which needs a rebuild (`build.py --write`).
- **No pull request starts `Checks` by itself.** A pull request or push made with `GITHUB_TOKEN` starts no other
  workflow (GitHub's rule against recursion). So the libwebp pull request must be closed and reopened by a person, and
  every push of the follow waits for a person's approval.
- **The Dependabot configuration carries measured workarounds** for its own behaviour:
  - Members of a multi-ecosystem group refused `open-pull-requests-limit`.
  - The `helm` ecosystem failed on every run from 2026-11-05 until it was removed on 2026-11-07.
  - An `ignore` rule matched nothing until the registry was dropped from the name (PR #20).
  - Several rules are marked unproven.
- **Dependabot runs on GitHub only.** The repository's own forge is a choice that can change. A self-hostable project
  whose update machinery exists on one vendor's forge alone has tied a duty to that vendor.

What Renovate offers (its documentation in `renovatebot/renovate`, read 2026-11-22; the project is AGPL-3.0, not
archived, release 44.257.9 of 2026-11-21, and `renovatebot/github-action` v46.3.14 of 2026-11-16):

- Regex custom managers that capture `currentValue` and `currentDigest`.
- `with:` version inputs of the setup actions.
- Gradle wrappers in any directory, and `verification-metadata.xml` rewritten with `--write-verification-metadata`.
- Grouping across managers.
- `postUpgradeTasks` restricted by `allowedCommands`, available only when Renovate is self-hosted. The Mend-hosted app
  blocks them by default.
- Node.js versioning that knows LTS. Its readme says that from Node.js 27 on every major becomes LTS, which
  dependabot.yml's `node` rule (it ignores 23, 25 and 27 as odd) does not know.
- The same configuration on Gitea, Forgejo and GitLab.

## Decision

1. **Every pin has an updater, and a pin no updater reaches is a red check, not a comment.** A pin is anything that
   ADR 0057 § 6 pins:
   - an image or base by digest,
   - a downloaded tool by version and SHA-256,
   - an action by commit SHA,
   - a runtime version a job sets up,
   - a package manager's version,
   - a wrapper,
   - a locked dependency.

   A check enumerates the pins in the tree and compares them with the dependencies the updater extracts. A pin found
   but not extracted is red, and so is a pin with no version at all, such as the bare `pip install pyyaml`. The
   updater's own extraction is the authority for "reached"; a list kept beside it would drift the way the
   "Reached by no ecosystem" comments did.

   As built:
   - **The check is `pins` in `checks.yml`**: Renovate `--platform=local` (no token, the pinned image) writes its
     extract report, and `scripts/pin_inventory.py` compares it with the pins it finds by shape. A new shape is added
     to the inventory, never to an exception list.
   - **A docs-only or site-only commit does not run it** (`checks.yml`'s `paths-ignore`). A site-only change can touch
     `site/package.json`; its pins are checked by the next commit that runs `Checks`. Accepted: no shape lives only
     under `site/`.
   - **A rule holds a version back by `allowedVersions`, not by `enabled: false`** (TypeScript's major): the report
     still lists an update a rule disabled, and `pins` reads that report. A disabled dependency is still extracted, so
     `pins` stays meaningful under a disabling rule (measured).
   - **The five bare `pip install pyyaml` install from `scripts/requirements-release.txt`, hashed**, like the jobs
     with write permissions: PyYAML has one pin, which the `pip_requirements` manager reads.
   - **`tests/fuzz/requirements.txt` is in `ignorePaths`**: `lock.py` writes it whole, and Renovate reading it as
     requirements would bump each transitive package alone. Its one input, `SCHEMATHESIS` in `lock.py`, has a regex
     manager.
   - **Renovate's own version is a regex manager on `renovate.yml`** (the github-actions manager refuses
     `<version>@<digest>`), grouped with the actions; `pins` reads the image from that one line.

2. **The updater is Renovate, self-hosted.** It runs as a GitHub Action from the default branch, on Dependabot's
   weekly slot and on dispatch, and is pinned like everything else: the action by commit SHA, Renovate's own version by
   image digest. Renovate also proposes its own bumps.
   - **Never the Mend-hosted app.** It would give a third party write access to the repository, and it blocks the
     `postUpgradeTasks` that decision 4 needs.
   - **Never an LLM in the update path.** A pull request carries third-party release notes, and an LLM reading them
     is a prompt-injection path. This reason was already given in `dependabot_follow.py` and still holds.

   As built:
   - **Renovate's config is the repository's `renovate.json5`**; the workflow's `env:` sets only the operator's part
     (which repository, no onboarding, the allowed commands), so the policy runs unchanged on another forge.
   - **It runs three times on Saturday morning, 05:00, 07:00 and 09:00 UTC** (owner 2026-11-24), before `publish.yml`'s
     Monday cut. A lookup that fails in a run — Maven Central answers 429 to GitHub's shared runner addresses — makes
     Renovate leave every group branch as it was, in a run that is still green; with one slot that held a group back
     a week. A run with nothing to do takes under a minute and changes nothing.
   - **Neither a failed lookup nor a failed branch push turns the run red** (owner 2026-11-24). Renovate logs a refused
     push at DEBUG and exits 0 (run 45197394644, the App still without `workflows`); only the log shows it. The next
     slot retries both.
   - **Rejected: a mirror registry or a persistent cache against the 429.** A mirror is a second source beside
     Gradle's own and one vendor's; a weekly run outlives the cache.

3. **Renovate acts as a GitHub App of its own, installed on this repository alone**, so that its pull requests and
   pushes start `Checks` like a person's.
   - The App gets only the permissions the job needs: contents, pull requests and workflows to write; issues to write,
     for the Dependency Dashboard; commit statuses to write, for the status that holds a release in its cool-down
     (decision 5); Dependabot alerts to read, for decision 6. It never gets checks to write, which would let it create a
     check run of any name.
   - Its private key is a secret of an environment that admits the default branch alone, so only jobs that run from
     the default branch read it: the Renovate job, and the Renovate follow's push (decision 4), which mints a token
     for contents alone. A job token is minted per run and expires within the hour.
   - This makes the close-and-reopen of the libwebp pull request and the approval of the follow's pushes unnecessary.
   - Creating the App and storing its key is the owner's step.

   As built: the App is `veyq-renovate`, its bot account `veyq-renovate[bot]` (id 359998714), which
   `scripts/release_notes.py` exempts from the release-note check beside Dependabot's logins (`UPDATERS`, matched
   exactly; the hosted `renovate[bot]` is not exempt). The environment `renovate` holds the Client ID as a variable
   (`actions/create-github-app-token` v3 deprecates the App ID input) and the key as a secret. Three permissions on
   Renovate's list are left out: administration (it reads branch protection to rebase on its own when `main` demands
   up-to-date branches, which `main`'s ruleset does not, and the failed read is handled), checks: write (see above), and
   merge queues (there is none). Workflows: write is not optional: without it the first push of an actions bump was
   refused.

4. **What has to move with a version moves in the same pull request, written by the updater.** (Amended
   2026-11-23, when it was built: Renovate's Gradle artifact updates run the repository's Gradle, its plugins included,
   in the job that holds the key — `gradleWrapper` in `allowedUnsafeExecutions` — and its container has no Docker for
   `lock.py` or `build.py`. So a follow-up that needs the bumped tree executed is not Renovate's.)
   - Renovate's own artifact updates are used where they execute nothing of the repository: the pnpm lockfile
     (`--ignore-scripts`).
   - A follow-up that published bytes decide is a `postUpgradeTask` running `scripts/pin_follow.py`: the Helm
     tarball's SHA-256, the OpenAPI generator JAR's, PyPI's hashes, the Gradle distribution's checksum.
     `allowedCommands` admits exactly those calls and nothing else.
   - A follow-up that needs the bumped tree executed — the Gradle verification metadata, Schemathesis' `lock.py`, the
     WebP encoder's `build.py --write` — is the **Renovate follow** (`renovate-follow-derive.yml`,
     `renovate-follow.yml`, `scripts/renovate_follow.py`), the shape the Dependabot follow had: a read-only run on the
     pull request executes it and uploads what it wrote; a `workflow_run` job from the default branch checks that the
     pull request is Renovate's and untouched, accepts the upload as data, and pushes one commit with the App's token
     minted for contents alone, so `Checks` starts on it as on Renovate's own push.
   - The rules the follow enforced stay rules, held by checks that read what the pull request wrote:
     - `verification-metadata.xml` only gains trust (`verification_metadata_diff.py`, in `Checks` against the pull
       request's base: a property of a change, which no history-blind guard can hold);
     - one `repo:tag` has one digest (`validate_image_pins.py`);
     - the Playwright image matches the library.

     A guard does not care who wrote the bytes, so a hand-made bump is held to the same rules.
   - Dependencies' install scripts stay off in the Renovate job, as in CI.

   As built:
   - **`gradleWrapper` stays out of `allowedUnsafeExecutions`.** Without it Renovate's wrapper update moves
     `distributionUrl` alone (read in the pinned image's `gradle-wrapper/artifacts.js`: "Not allowed to execute
     gradle"); `pin_follow.py gradle-distribution` writes `distributionSha256Sum` from services.gradle.org's `.sha256`
     and the zip's own hash, required equal. The wrapper JAR stays as it is.
   - **The follow's commit is authored as the App's bot**, so the follow's gate still sees a branch only Renovate wrote
     and Renovate still rebases it. Its push starts the derive again, which sees its own commit at the head and skips.
   - **The root launcher's metadata is followed too**: Dependabot never reached the root wrapper, Renovate does. A cold
     home that fetched nothing (Gradle's distribution brings what `help` needs) is listed as no module, not refused.
   - **`build.py --write` takes the Dockerfile's image into the pin**, since that is the line an Emscripten bump moves;
     the comparison still refuses a pin whose image is not the Dockerfile's. The first Emscripten bump found the write
     refusing the image it was asked to take (#88, fixed by #89).
   - **The metadata rule is a script `Checks` runs against the event's base, not a `validate_*.py`**: "only gains" is a
     property of a change, and the guard glob reads one tree and never history. A first push or a local
     `run_pipeline.py` run has no base and checks the form only.
   - **Dropping a metadata entry is allowed**, whole components and single artifacts: a drop cannot widen trust, since
     Gradle refuses an artifact with no entry, and history did it twice (7f28ce7d2, 408ce9c73). What is red is a
     changed entry: the trust rules, or the bytes a kept artifact is trusted under. There is no exception for a
     legitimate change of either: such a pull request merges red, by the owner, which is the decision the check is
     there to make visible.
   - **The Playwright match reads the lockfiles**: the manifest's exact pin was already held to the image, but what
     installs is what pnpm resolves, which an override or a second copy can move.
   - **`validate_workflow_paths.py` reads `renovate.json5` too**, through a JSON5 subset reader in the guard (the
     standard library has none, and a dependency for one file is not worth it): a rule whose file moved stops applying
     without a word, which `pins` sees only for a manager.

5. **The version policy is configuration, and each rule keeps its reason.**
   - Dependabot's `ignore` rules become `packageRules`, each with its measured reason as the rule's `description`:
     - LTS lines only for Node, MariaDB and Ubuntu;
     - nginx's stable branch;
     - Garage below 2.1;
     - TypeScript's major held to typescript-eslint's.
   - Majors arrive one per pull request; minors and patches are grouped per ecosystem, as now.
   - A release must be a few days old before it is proposed (`minimumReleaseAge`), as a cool-down against a hijacked
     release. Security fixes are exempt (decision 6).
   - Nothing merges by itself: a person merges after `green`.

   As built:
   - **The cool-down is three days and thirty minutes**: Renovate adds its default `minimumReleaseAgeBuffer` (30
     minutes, read in 44.258.3's `config/options`) to `minimumReleaseAge`, and `internalChecksFilter: strict` opens no
     branch inside it. Left at the default.
   - **An image update takes `minimumReleaseAgeBehaviour: timestamp-optional`**: a digest move has no release time,
     and Docker Hub's tag pages past the tenth answer 403, so with the timestamp required no digest update would ever
     leave the cool-down. An image that has a release time still waits.
   - **The `images` and `actions` groups keep their majors** (`separateMajorMinor: false`), as Dependabot's groups did.
   - **A runtime major is one pull request across managers**: `node major` (the images' `node`, setup-node's
     `node-version`, `@types/node`) and `java major` (`eclipse-temurin`, setup-java's `java-version`), so a job never
     runs a major its image does not. `@types/node` then proposes only its newest allowed version, so its minors wait
     with a pending Node major; that replaces Dependabot's held-back `@types/node` major.
   - **Java follows its LTS releases by a positive regex** (25, 29, 33, 37): a release off Oracle's cadence is held,
     never proposed by mistake. Dependabot's Node rule (no 23, 25, 27) is not ported: 27 becomes LTS on 2027-10-20
     (nodejs/Release `schedule.json`), and removing the rule changed no proposal.
   - **libwebp and the Emscripten image are a group of their own**, so a rebuild never holds back the images group; the
     fuzzer's Python image is held to 3.13 by `allowedVersions`, since `lock.py` hashes `cp313` wheels.

6. **Security updates come from Renovate too; GitHub's alerts stay.**
   - Dependabot alerts and the Gradle dependency submission (`dependency-submission.yml`) keep feeding GitHub's
     advisory view.
   - Dependabot's own security pull requests are switched off.
   - Renovate's `vulnerabilityAlerts` opens the fix at once, outside the schedule and the cool-down. This avoids two
     bots proposing the same fix.

   As built: Renovate's `vulnerabilityAlerts` defaults (read in the pinned image: no cool-down, no group, at once) are
   what this decision asks for, so `renovate.json5` only turns it on. Dependabot's security pull requests are off in the
   repository's settings (`gh api repos/raddatzk/veyq/automated-security-fixes`: `enabled: false`, 2026-11-25).

7. **Bumping a pin is no longer part of cutting a release.** This replaces ADR 0057 § 6's last sentence. Pins move
   when their updater proposes them, and they are tested on every push like any other change. A release promotes
   whatever snapshot it promotes.

8. **The libwebp updater moves into Renovate** (part of ADR 0127 decision 4). Renovate reads libwebp's stable `vX.Y.Z`
   tags as a git-tags dependency; the Renovate follow (decision 4, amended) runs `build.py --write`, which rebuilds the
   encoder in the pinned Emscripten image, with libwebp and the Emscripten image as one pull request of their own. The
   encoder's tests and its byte-for-byte rebuild then run in `Checks`, because the push starts it. Trust on first use of
   the tag's commit stays as decision 4 has it: the pin records the commit `git ls-remote` names for the tag, asked
   again by the writing job before it accepts the pin.

## Consequences

- **Dependabot's version updates and the libwebp updater go.** `.github/dependabot.yml` is deleted and
  `webp-encoder-update.yml` with it; the Dependabot follow became the Renovate follow (decision 4). The switch happened
  after a dry run, ecosystem by ecosystem for the actions, the images and npm, each proven by Renovate's first real pull
  request, and for Gradle and the rest in one step.
- **Each kind of follow-up was proven on Renovate's own pull request**, each of which started `Checks` by itself and
  merged green:
  - actions #65 (cosign and Renovate's own digest), images #67, npm and pnpm #71 and #72;
  - the Gradle wrapper's checksum by `gradle-distribution` (#74, services.gradle.org's `.sha256` for 9.9.2), and the
    verification metadata pushed by the Renovate follow (#74, #83);
  - Schemathesis' `requirements.txt` re-locked and pushed by the follow (#75), PyYAML's hashes by `pypi-hashes` (#82,
    all 16 equal to PyPI's);
  - the generator JAR's SHA-256 by `openapi-generator-jar` (#76). 7.27.0 also needed `ClassPathKept`
    (`src/backend/buildSrc`, whose comment holds why); no upstream issue was filed (owner 2026-11-24);
  - Emscripten 7.0.1 (#88): the derive rebuilt the encoder byte-identical to 7.0.0's, the follow moved the pin's
    `image` line alone, and `Checks`' byte-for-byte rebuild was green on the follow's commit (run 45585288594).

  **Not yet proven: Helm's follow.** No Helm release has shipped since the switch, so `pin_follow.py helm` has run
  only in its self-test and on a forced-back copy, never as Renovate's task. Its first bump is that proof; the same
  holds for a libwebp tag move, whose derive step #88 ran with an unchanged tag.
- **A write-capable job with a secret now exists, and a second one that pushes.** The Renovate job is the first job
  that holds a long-lived secret able to push to the repository and change workflows; the Renovate follow's push reads
  the same key, mints a token for contents alone, and runs no code of the pull request. What bounds them:
  - the App is installed on this repository alone;
  - the key is readable by one job only, which runs from the default branch, never from a pull request;
  - post-upgrade commands are allowlisted, and install scripts are off.

  A compromised Renovate release would still run with that key, which is why Renovate's own version is pinned and
  proposed like any other.
- **The measured lore is re-proved, not copied.** Each `ignore` rule and each follow behaviour is carried over only
  with a case that shows it bites, against Renovate's dry run. dependabot.yml's comments are the source, and they are
  deleted with the file once their rules live in `renovate.json5`.
- **The forge's part shrinks to one workflow.** On another forge the same `renovate.json5` runs on that platform, and
  only the workflow and the App have to be redone.
- **Rejected: staying on Dependabot with a custom updater for the rest.** It would mean writing for every unreached
  pin what Renovate's regex managers already do, keeping the follow workflows, and still needing an App for `Checks`
  to start. That is the same work for less reach, and it stays on one forge.
- **Rejected: Renovate with `GITHUB_TOKEN`.** Its pull requests would start no `Checks`, which is the problem decision
  3 removes.
- **Rejected: the Mend-hosted app.** See decision 2.
- **Not decided here:** the cool-down's length and the Dependency Dashboard's approval gate for majors. These are
  tuning values, recorded where the configuration sets them (`renovate.json5`: three days plus Renovate's thirty-minute
  buffer, decision 5; no approval gate).

## Related

- The policy: [`renovate.json5`](../../renovate.json5); the follow-ups: `scripts/pin_follow.py`, `scripts/renovate_follow.py`.
- Security area of the docs site: [How veyq is tested](../../site/src/content/docs/security/how-veyq-is-tested.md)
