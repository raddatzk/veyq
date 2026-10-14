---
id: cr-release-pipeline-and-chart-oci
title: A publish is started by hand as a snapshot or a release, releases the chart beside the images, and a restore accepts an older backup
status: done
satisfies: [uc.admin.identify-instance-release, uc.admin.back-up-and-restore-instance]
adrs: ["0057", "0064", "0065", "0056", "0040"]
invariants: [self-hostable by default]
---

## Open for the owner

Nothing is open. Refined with the owner 2026-10-14. The two last questions were answered the same day:

- **Q1 — the minor counts the commits since the last change to `VERSION`.** It restarts at every major, and
  ADR 0057 §1 now says so, with the all-commits form recorded as considered.
- **Q2 — CI gets Helm as the official release tarball, checked against a pinned SHA-256.** No setup action.
  This is the shape ADR 0057 §6 already uses for the OpenAPI generator JAR.

## Goal

Today every push to `main` publishes the version in `VERSION`, and every push after the first one is a red run
until someone commits a bump. After this CR a push only **builds**, as a dry run. A publish starts by hand
(`workflow_dispatch`), from `main` or `release/<major>.x`, and is either a **snapshot** or a **release**:

- **Snapshot** (default): `<major>.<minor>-SNAPSHOT`, mutable, signed, no git ref.
- **Release** (asked for explicitly): `<major>.<minor>`, refused if it exists, git ref `v<major>.<minor>`.

`VERSION` holds the major alone. The minor is a commit count. Major `0` is pre-release. Either kind of publish
also packages the Helm chart and pushes it, signed, to `oci://ghcr.io/<owner>/charts/veyq`. An operator can
then install the chart from the registry and verify it the way they already verify the images, instead of
needing a checkout.

The same release story has one more part, on the operator's side of an upgrade. Today `scripts/instance_backup.py
restore` never compares the backup's version with the target's. An older backup loaded onto a newer instance
leaves the newer tables standing under the older migration history, and the app then fails at its next start.
A newer backup loads and is then refused at boot by the older image. After this CR the restore checks before it
writes anything:

- **A newer backup** is refused, and the message names the release it came from.
- **The same or an older backup:** the restore first drops every table of the data-less target, then loads the
  dump. The app migrates the data up at its next start, the same forward migration every upgrade runs.

"Install the backup's release, restore, upgrade" becomes "restore, start". This part touches neither the
workflow nor the chart. It is a phase of this CR only because the owner asked for it here, so it can be
implemented on its own, before or after the others.

## Context & constraints

- [ADR 0057](../docs/decisions/0057-release-identity-and-deployment-recipe.md) §1–2 (edited in place by this
  refinement):
  - the version comes from the tree;
  - snapshot vs release;
  - major `0` is pre-release;
  - only a release has a git ref.
- §3 (verify identity, deploy by digest) and §6 (pins, action SHAs) are unchanged and still bind.
- [ADR 0064](../docs/decisions/0064-kubernetes-helm-deployment-target.md) § "The chart is released the same way
  the image is" (edited in place):
  - the path `charts/veyq` and why it has that segment;
  - `version` = `appVersion` = the release version, set at packaging;
  - the image repositories default to the upstream's;
  - a release refuses to republish the chart.
- [ADR 0065](../docs/decisions/0065-admin-ui-cli-separate-service.md): one release, two images. The chart
  becomes the third artifact of that same release.
- [ADR 0040](../docs/decisions/0040-coverage-gaps-and-http-not-a-surface.md): a workflow is not a coverage
  surface, and a workflow that has never run proves nothing. The publish half is held by
  `scripts/validate_release_identity.py`, not by coverage.
- [uc.admin.identify-instance-release](../docs/features/admin/use-cases/uc.admin.identify-instance-release.md):
  A5 (a snapshot instance) was added by this refinement. The backend only reports the string, so it needs no
  new behaviour.
- [ADR 0056](../docs/decisions/0056-instance-durable-state-and-cold-logical-backup.md) §7, added by this
  refinement: a restore accepts a backup of the target's schema or an older one, drops every table of the target
  first, and refuses a newer one. §6, the rollback, still starts by installing the backup's own release.
- [uc.admin.back-up-and-restore-instance](../docs/features/admin/use-cases/uc.admin.back-up-and-restore-instance.md):
  step 7, A7 (a rollback restores onto the backup's own release) and the new A9 and A10.
- [inbox #26](../docs/inbox.md) (the chart's OCI release is its last open sub-slice). The upgrade proof this
  does **not** contain is #419.
- **Invariant at risk: self-hostable by default.** Nothing here adds a central dependency. GHCR is where the
  upstream publishes, and a fork overrides every name. A chart that only installs from the upstream registry
  would be a regression, so `helm install ./charts/veyq` from a checkout keeps working.

## Inventory (measured at refine, `main` @ `d2269021`, read, not run)

- **[`container.yml`](../.github/workflows/container.yml) triggers:** `push` on `main` + `release/**`,
  `pull_request`, `workflow_dispatch` (no inputs).
- **Version job:** reads `VERSION` against `^([0-9]+)\.([0-9]+)$`. It publishes on any push to `main` or
  `release/${major}.x` and refuses any other branch.
- **Build job:** pushes per-arch `build-<sha>-<arch>` images whenever the event is not a PR.
- **Publish job, in order:**
  1. `assert-release-unpublished`, over both images and the git tag;
  2. metadata (`<version>`, `<major>`, `sha-<sha>`, `latest`);
  3. manifests;
  4. cosign over the `sha-` digest;
  5. `release-tag`.
- **The tree:** `VERSION` = `1.0`. `Chart.yaml` `version: 1.0.0`, `appVersion: "1.0"`, with a comment
  ([Chart.yaml:8](../charts/veyq/Chart.yaml) (line 8)) that defers deriving them to this sub-slice.
  `values.yaml` `image.repository: ""` and `admin.image.repository: ""`. `values.schema.json` requires
  `repository`, and `digest` is optional.
- **[`validate_release_identity.py`](../scripts/validate_release_identity.py)** anchors on these step ids:
  - `version/veyq-version`
  - `publish/assert-release-unpublished`
  - `publish/release-tag`
  - the `build` matrix (the published-image set)

  On those it asserts:
  - It **executes** the version step offline under push/PR/release-branch/foreign-branch scenarios, with no
    `gh|curl|wget|nc`.
  - The refuse and tag steps read `needs.version.outputs.version`, use `refs/tags/v${VERSION}`, and do not
    force.
  - Every `cosign verify` in infra/README pins the one identity, and every `ghcr.io/<owner>/<name>` token there
    is `@digest`. A token with two path segments (`charts/veyq`) is **not** collected today.
  - The owner comes from Chart.yaml `home`.
- **[`chart_meta_check.py`](../scripts/chart_meta_check.py):** needs `helm`, is run by hand, and is not in any
  workflow. Inbox #26 says its CI gate belongs to this sub-slice.
- **Wire prose that states the old form:**
  - [instance.openapi.yaml:141](../contracts/api/instance.openapi.yaml) (line 141): `<major>.<minor>`, "the value this
    repository's VERSION file held". No `pattern`, `maxLength: 32`, so `-SNAPSHOT` fits.
  - [ReleaseIdentity.kt:26](../backend/core/src/main/kotlin/me/raddatz/veyq/core/admin/instance/ReleaseIdentity.kt) (line 26)
  - [VeyqConfig.kt:90](../backend/core/src/main/kotlin/me/raddatz/veyq/core/config/VeyqConfig.kt) (line 90)
  - [application.properties:40](../backend/core/src/main/resources/application.properties) (line 40)
  - the header of `docs/coverage/…/uc.admin.identify-instance-release.yaml`
  - `docs/architecture/README.md` (grep `VERSION`)
- **[`instance_backup.py`](../scripts/instance_backup.py) restore:**
  - Its only gate on the target is A5: no data rows (`populated_tables()`, which excludes `NOT_DATA` =
    `flyway_schema_history`, `veyq_instance`) and no objects.
  - It never reads the target's schema version and never compares it with the manifest's.
  - The dump is a full `mariadb-dump` with `--add-drop-table` on by default. It drops and recreates only the
    tables it names, and its docstring says so (line 298 ff.).
  - `Database.schema_version()` reads the last successful Flyway version by `installed_rank`. Its docstring
    records that `MAX(version)` is lexicographic and wrong.
  - The manifest carries `database.schemaVersion` and `app.version`.
  - The restore is rehearsed by `scripts/run_web_e2e.py`, which captures, destroys, restores and runs a suite.
    It already has a refusal hook in the same shape, `assert_populated_target_is_refused`.
    `scripts/instance_backup_selftest.py` covers the tool without Docker.
- **Operator docs:** [infra/README](../infra/README.md) § Verifying a release covers two images only.
  § Running on Kubernetes installs from `./charts/veyq` only.
- **Helm 4.0.1 (local), measured:**
  - `helm package --version 1.0`, `--version 1.42-SNAPSHOT` and `--version 0.0-dev` all package unchanged.
- **Remembered, not measured, to be measured in phase 3:**
  - `helm push` appends the chart name to the target.
  - Helm ignores pre-releases in version ranges without `--devel`.
  - Helm installs `oci://…@sha256:<digest>`.

## Decisions

1. **Triggers.**
   - `push` (`main`, `release/**`) and `pull_request`: `test` + `build`, **no registry write**. The build job's
     `push:` and its GHCR login run on `workflow_dispatch` only. PRs stay, because Dependabot opens them.
   - `workflow_dispatch` with one boolean input, `release` (default `false`), is the only event that runs
     `publish`.
   - Dry runs and publishes get separate concurrency groups, so a push never queues behind a release.
2. **The version step** keeps every input in `env:`, uses no `${{ }}` in its script and reaches no network.
   - `VERSION` must match `^[0-9]+$`. The step derives the minor as `git rev-list --count "$(git log -1 --format=%H -- VERSION)"..HEAD` (Q1), refuses a **shallow** checkout
     (`git rev-parse --is-shallow-repository`) instead of counting 1, and its job checks out with
     `fetch-depth: 0`.
   - It emits `version` (with `-SNAPSHOT` for a snapshot) and `kind` (`none` | `snapshot` | `release`), plus
     the existing tag flags.
   - Release only from `main` or `release/<major>.x`. `latest` only from `main`.
3. **Tags per kind.**
   - **Snapshot:** `<version>` (which already ends in `-SNAPSHOT`) and `sha-<sha>`.
   - **Release:** `<version>`, `<major>`, `sha-<sha>`, and `latest` from `main`.
   - `sha-<sha>` names *the last publish of that commit*. It is not a rollback name, and the docs say so.
   - `assert-release-unpublished` runs for a release only, and it now also covers the chart. The git tag is
     for a release only.
   - Signing signs each artifact's **resolved digest**, as today.
4. **The image is stamped with the full version.** `VEYQ_VERSION` carries `-SNAPSHOT` for a snapshot. The
   running instance reports exactly what it is (A5).
5. **The chart.**
   - Package with `helm package charts/veyq --version "$VERSION" --app-version "$VERSION"` in `publish`, after
     the images, and push with `helm push … oci://ghcr.io/<owner>/charts`.
   - Read the digest from the push and `cosign sign` `ghcr.io/<owner>/charts/veyq@<digest>`, with the same
     identity as the images.
   - In the tree, Chart.yaml carries `version: 0.0-dev` / `appVersion: "0.0-dev"`, a placeholder no publish
     uses. Its comment says why: the minor is a commit count, so no tree can hold it. For an install from a
     checkout, set `image.tag`.
6. **Default image repositories:** `ghcr.io/raddatzk/veyq` and `ghcr.io/raddatzk/veyq-admin` in `values.yaml`.
   The guard holds them to the image names the README verifies.
8. **Restore across versions (ADR 0056 §7).** After the A5 gate and before any write, `restore` reads the
   target's Flyway version with the same `schema_version()` the capture uses, and compares it with the
   manifest's in **Flyway's version order**: dot-separated numeric parts, never strings.
   - **Manifest newer:** refuse, naming `app.version` (or saying it is unknown) as the release to install.
   - **Otherwise:** drop every base table of the target (`information_schema`, the same derivation
     `populated_tables()` uses, so a table a future migration adds is covered with no list to maintain), then
     load.
   - **Manifest older:** the closing log says the application will migrate the data to the target's schema at
     its next start, and that a rollback needs the backup's own release installed instead (uc A7).
   - **Equal:** behaves as today.
9. **`chart_meta_check.py` runs in CI,** in the `test` job, with Helm installed from the official release tarball, verified against a SHA-256 pinned in the workflow (Q2). The same install serves the chart packaging in `publish`. It is outside the
   buildless guard glob, which is why it needs its own step.

## Phases

- [x] 1 contracts + backend: the wire prose states the new form.
  - `instance.openapi.yaml` `release` description: `<major>.<minor>` or `<major>.<minor>-SNAPSHOT`, a
    snapshot's name being the one that moves (ADR 0057 §1).
  - The KDoc and property comment from the inventory.
  - No shape change.
  - Done when `npm run generate:api` + `npm run check` are green in `web/` and `admin-web/`, and
    `./gradlew check` is green.
- [x] 2 workflow + guard: Decisions 1–4 and 9, plus `VERSION` → `0` (the owner's first test stage).
  - Extend `validate_release_identity.py` so that it:
    - executes the version step inside a throwaway git repository it builds itself: a few commits, a `VERSION`
      change, more commits, and a shallow clone of it. The minor is then asserted against a known count, never
      against this repository's own history, which moves;
    - executes the version step for dispatch-snapshot and dispatch-release on `main`, dispatch-release on
      `release/0.x`, dispatch on a foreign branch (refused), a push (kind `none`) and a shallow checkout
      (refused);
    - asserts that every registry write (`build` push, GHCR login, the whole `publish` job) is reachable only
      under `workflow_dispatch`;
    - asserts that the refuse step and the git tag run for `kind == release` only.
  - Each new assertion gets a **bite test**: the mutation it must fail on is written and run before the
    assertion counts.
  - Done when every `scripts/validate_*.py` is green and every bite test failed as intended. Name the run in
    the retro.
- [x] 3 chart: Decisions 5–6 and the chart half of the refuse step.
  - Rehearse locally against a throwaway OCI registry (`registry:2`, pinned by digest): package, push, check
    that the artifact lands at `<registry>/charts/veyq`, then `helm install --dry-run` by tag and by digest.
    This measures the three remembered Helm facts.
  - Extend the guard so that:
    - the chart is in `assert-release-unpublished`;
    - `--version` / `--app-version` read the version output;
    - the values defaults equal the README's image names;
    - two-segment `ghcr.io/<owner>/charts/veyq` tokens are collected and must be `@digest`.
  - Done when `chart_meta_check.py` and `helm lint` are green, the rehearsal output is recorded in the retro,
    and the guards plus bite tests are green.
- [x] 4 docs: the release process and the operator side.
  - infra/README § Verifying a release adds the chart: resolve its digest once, `cosign verify` it with the
    same identity, install by digest (or by tag, if phase 3 measured that digest installs do not work).
  - § Running on Kubernetes installs from `oci://ghcr.io/raddatzk/charts/veyq` and keeps `./charts/veyq` as
    the checkout path.
  - Sweep every "bump `VERSION`" / `major.minor in VERSION` line (inventory list).
  - Write the maintainer's release steps where the tree keeps them today (find with
    `grep -rn "release" docs/architecture/README.md infra/README.md`). If no place exists, infra/README gets a
    short § Cutting a release:
    - dry run on push;
    - snapshot and release dispatch;
    - packages public or a pull secret;
    - the first `1.x` release sets `VERSION=1` **and** the `release:` line of
      `scripts/applied_migrations.sha256` in one commit.
  - ADR 0064's "not yet built" sentence and inbox #26 move to *built, publish unobserved*.
  - Done when the trust-chain guards are green.
- [x] 5 restore across versions: Decision 8, in `scripts/instance_backup.py`, plus the infra/README restore and
  upgrade sections (the rollback sentence and the older-backup case).
  - `instance_backup_selftest.py` pins three things:
    - the version order (a case where string order and Flyway order disagree, e.g. `9` vs `44`);
    - the refusal message, which names the release;
    - the closing log for an older backup.
  - `run_web_e2e.py` gets two hooks beside `assert_populated_target_is_refused`:
    - **older:** before the restore, the target carries an extra empty table and a successful history row
      above the backup's version (a simulated newer target). The restore succeeds, the extra table is gone,
      the history equals the backup's, and the suite runs green.
    - **newer:** a copy of the capture whose manifest claims a higher `schemaVersion` is refused, by its own
      message, and nothing was written.
  - Bite test: with the drop removed, the older-case hook must fail.
  - What this does **not** prove is that a real forward migration over real data works. That is #419, and it
    needs a released predecessor.
  - Done when the selftest is green, the `run_web_e2e.py` run with both hooks is green (named in the retro),
    and the bite test failed as intended.
- [x] 6 coverage:
  - The header of `uc.admin.identify-instance-release.yaml` restates the publish half in the new form (still a
    guard, not coverage).
  - `uc.admin.back-up-and-restore-instance.yaml` gains two criteria, `an-older-backup-restores-and-migrates-up`
    and `a-newer-backup-is-refused-naming-its-release`. Neither has a surface, so each carries the comment
    saying where it is asserted (the two hooks), in the shape of
    `a-populated-target-is-refused-rather-than-reconciled`.
  - Done when `python3 scripts/validate_coverage.py` is green.

## Definition of done

- Every `scripts/validate_*.py` is green on a local run over the committed tree. `./gradlew check` is green, and
  `npm run check` is green in `web/` and `admin-web/` after regenerating.
- `chart_meta_check.py` is green, and the local OCI rehearsal is recorded.
- The `run_web_e2e.py` run with both restore-version hooks is green, and the drop's bite test is recorded.
- The extended `validate_release_identity.py` is the permanent guard for every structural claim above, and each
  new assertion was seen to fail on its mutation.
- **Stated gap, owned by the owner, not closed by this CR:** no workflow run has happened. The first proof is
  the owner's staged run, in this order:
  1. push → the dry run is green and nothing is in GHCR;
  2. snapshot dispatch at `VERSION=0` → images and chart are published and signed, no git tag;
  3. release dispatch → `v0.<n>` exists; a **second** release dispatch on the same commit is red at the refuse
     step;
  4. `VERSION=1` + the `release:` line → release dispatch.

  Until step 3 has run, ADR 0064 says "built, publish unobserved", not "released".

## Retrospective

**Where it was run.** `main` from `0724a9d7` (commits `af73974d`, `ada162ef`, `cb1be12a`, then phases 4–6). Every
guard glob ran on a detached worktree holding exactly the phase's state, because the live tree carries the owner's
gitignored `infra/.env` (`veyq:cr162`), which `validate_release_identity.py` scans and reds on — pre-existing,
untouched. Baseline at `0724a9d7`: 57 guards, 0 red. Phases 2, 3, 4: 57/57 green each. Phase 1: `generate:api` in
`web/` and `admin-web/`, both `npm run check` exit 0, `./gradlew check` BUILD SUCCESSFUL (5 m 15 s).

**Bite tests, each injected on a worktree copy, red BY MESSAGE, restored and byte-compared.** Phase 2, 17/17:
VERSION `0.4`; the minor counted from the first VERSION change (5) and over every commit (6); the shallow
refusal removed; dispatch from any branch; `release` defaulting to true; `RELEASE` not passed to the step; a
`git fetch --unshallow` in the step; the build login and the build push on every non-PR event; the publish job on
every non-PR event and widened by `||`; the refusal for a snapshot; the tag for every publish; an ungated
`docker push` in `test`; a snapshot without `-SNAPSHOT`; a push publishing as a snapshot. Phase 3, 10/10: the
chart and the admin image missing from the refusal; `--app-version` dropped; a literal `VERSION`; a push to the
owner root; the chart unsigned; the values repository blank and swapped; the README verifying the chart by tag;
an ungated `helm push`. Phase 4, 2/2: the chart's verify dropped, and widened. Phase 5: the selftest bit on string
order, a newer backup accepted, the rollback dropped from the closing log, and the release dropped from the
refusal (the first form of that last injection removed the name from one of two places and did not bite — the
oracle still found it in the other; re-cut, it bit); the e2e drop bite is below.

**The Helm facts the CR remembered, measured** (Helm 4.0.1, `registry:2@sha256:a3d8aaa6…` on 127.0.0.1:5055):
`helm push … oci://<r>/charts` landed at `<r>/charts/veyq` (*holds*); `helm install --dry-run=client` by
`oci://…@sha256:97c57e87…` and by `--version 0.3` rendered (*holds*); "pre-releases are ignored without
`--devel`" holds only for three-part versions (`0.5.0` vs `0.6.0-SNAPSHOT`) — for the two-part ones the publish
stamps, Helm finds **no** tag without an exact version, `--devel` or not, and `helm lint` warns "not a valid
SemVerV2". ADR 0064 and 0057 corrected; the trade it leaves is inbox #420. Also measured: the push's
`Digest:` line equals the sha256 of `imagetools inspect --raw`, `imagetools inspect` resolves and refuses ("not
found", exit 1) a chart tag like an image's, and cosign signs and verifies the chart by digest (with a key).

**Restore across versions.** `run_web_e2e.py` over the `restore` group, run `.e2e-out/1791967032`: the A10 control
refused a copy claiming `V10000000000000` from release `0.9999` before any write, the older-backup rehearsal
accepted with `veyq_e2e_future_table` gone and the 2-row history the capture's, total 2 passed / 0 failed. Drop
removed, run `.e2e-out/1791967193`: red with "OLDER BACKUP LEFT THE TARGET'S TABLE".

**Where the CR was under-refined, and what I did.**
- *The dry run's stamp.* Decision 2 names `version` for a snapshot and a release, never for a push. A dry run
  stamps `<major>.<minor>-SNAPSHOT`, so an image that was never released cannot carry a release's name; the guard
  pins it.
- *A target with no Flyway history.* Decision 8 compares with "the target's version" and never says what happens
  when there is none. It is refused, telling the operator to start the app once — ADR 0056 §7 compares against
  "the freshly installed target", which such a target is not.
- *Two of the three remembered Helm facts were incomplete* (above), and `0.0-dev`, the placeholder Decision 5
  fixes, draws the same lint warning. Docs corrected; the version shape is the owner's call (#420).
- *Pullability.* Phase 4 asks for "packages public or a pull secret"; the chart has no `imagePullSecrets` value
  (`grep imagePullSecrets charts/veyq` is empty), so the section says public.
- *Mechanical:* `helm push` needs its own `helm registry login`; the refusal's existence check needed `--raw` to
  be safe on a chart artifact; the guard's chart section had to sit after the build-matrix reader it calls; the
  e2e's default ports (8080, and 18080, which the dev compose publishes) were taken.
- *Phases 4 and 5 both edit `infra/README.md`*; the phase-4 commit was staged from a reconstructed blob.

**Next time:** a remembered tool fact in a CR gets the one command that measures it run at refine — all three
here were seconds against a `registry:2`, and one of them changed an ADR.
