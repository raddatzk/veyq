# ADR 0158 — Every pin has an updater: a self-hosted Renovate proposes each bump with what moves with it

- Status: Accepted
- Built: no
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

2. **The updater is Renovate, self-hosted.** It runs as a GitHub Action from the default branch, on Dependabot's
   weekly slot and on dispatch, and is pinned like everything else: the action by commit SHA, Renovate's own version by
   image digest. Renovate also proposes its own bumps.
   - **Never the Mend-hosted app.** It would give a third party write access to the repository, and it blocks the
     `postUpgradeTasks` that decision 4 needs.
   - **Never an LLM in the update path.** A pull request carries third-party release notes, and an LLM reading them
     is a prompt-injection path. This reason was already given in `dependabot_follow.py` and still holds.

3. **Renovate acts as a GitHub App of its own, installed on this repository alone**, so that its pull requests and
   pushes start `Checks` like a person's.
   - The App gets only the permissions the job needs: contents, pull requests and workflows to write; issues to write,
     for the Dependency Dashboard; Dependabot alerts to read, for decision 6.
   - Its private key is a secret that only the Renovate job reads. A job token is minted per run and expires within
     the hour.
   - This makes the close-and-reopen of the libwebp pull request and the approval of the follow's pushes unnecessary.
   - Creating the App and storing its key is the owner's step.

4. **What has to move with a version moves in the same pull request, written by the updater.**
   - Renovate's own artifact updates are used where it has one: the pnpm lockfile, Gradle wrapper checksums and
     `verification-metadata.xml`, hashed pip requirements.
   - Everything else is a `postUpgradeTask` that runs a repository script, for example the Helm tarball's SHA-256,
     Schemathesis' `lock.py` and the WebP encoder's `build.py --write`. `allowedCommands` admits exactly those
     scripts and nothing else.
   - The rules the follow enforced stay rules, as guards that read what the pull request wrote:
     - `verification-metadata.xml` only gains entries;
     - one `repo:tag` has one digest (`validate_image_pins.py`);
     - the Playwright image matches the library.

     A guard does not care who wrote the bytes, so a hand-made bump is held to the same rules.
   - Dependencies' install scripts stay off in the Renovate job, as in CI.

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

6. **Security updates come from Renovate too; GitHub's alerts stay.**
   - Dependabot alerts and the Gradle dependency submission (`dependency-submission.yml`) keep feeding GitHub's
     advisory view.
   - Dependabot's own security pull requests are switched off.
   - Renovate's `vulnerabilityAlerts` opens the fix at once, outside the schedule and the cool-down. This avoids two
     bots proposing the same fix.

7. **Bumping a pin is no longer part of cutting a release.** This replaces ADR 0057 § 6's last sentence. Pins move
   when their updater proposes them, and they are tested on every push like any other change. A release promotes
   whatever snapshot it promotes.

8. **The libwebp updater moves into Renovate** (part of ADR 0127 decision 4). Renovate reads libwebp's stable `vX.Y.Z`
   tags as a git-tags dependency and runs `build.py --write` as its post-upgrade task, which rebuilds the encoder in
   the pinned Emscripten image. The encoder's tests then run in `Checks`, because the App's pull request starts it.
   Trust on first use of the tag's commit stays as decision 4 has it: the pin records the commit the tag named when it
   was first read.

## Consequences

- **Dependabot's version updates and the three updater workflows go.** `.github/dependabot.yml` keeps no version
  updates. `dependabot-follow-derive.yml`, `dependabot-follow.yml`, `scripts/dependabot_follow.py` with its self-test,
  and `webp-encoder-update.yml` are deleted once Renovate covers what each one did. The switch happens ecosystem by
  ecosystem after a dry run, never in one step.
- **A write-capable job with a secret now exists.** It is the first job that holds a long-lived secret able to push
  to the repository and change workflows. What bounds it:
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
  tuning values for the CR's dry run, recorded where the configuration sets them.
