# Concern: Release and deployment

- **ID:** release-and-deployment
- **Type:** **Cross-cutting model** — how a veyq release is named, built, tested, published and installed, on both
  deployment targets. It is the current picture of the ADR topic *Release, deployment, administration*
  ([index](../decisions/README.md#topics)): what is true now, whole. The *why* of each part is the ADR linked beside
  it; nothing is decided here.
- **Not here:** the operator's procedures — the docs site's [Run area](../../site/src/content/docs/run/index.md); the
  maintainer's commands for the dev stack and for cutting a release — [`infra/README.md`](../../infra/README.md); the
  test stages, what each proves, when it runs and its budget — the one stage map,
  [How veyq is tested](../../site/src/content/docs/security/how-veyq-is-tested.md).

**Built, never run.** Everything below is in the tree unless it stands under [§ Decided, not built](#decided-not-built).
The repository has no remote, so no workflow has executed and no release exists: `VERSION` is `0`, git holds no
`v*` tag, and `scripts/applied_migrations.sha256` says `release: none`. A statement about what CI or a publish *does* is
a statement about [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml), held by
`scripts/validate_release_identity.py`; the only runs are local, through `scripts/run_pipeline.py`, which reads that
file and runs what this machine can.

## The version

A release is `<major>.<minor>`, a function of the commit ([ADR 0057](../decisions/0057-release-identity-and-deployment-recipe.md) § 1):

- `VERSION` holds the **major** alone; the **minor** is the number of commits since the one that last changed `VERSION`.
  `ci.yml`'s `version` job computes it offline and refuses a shallow clone, which would count 1.
- **Major `0` is pre-release:** it exercises the publish path and promises no upgrade path. The migration set is
  frozen by the first release of a major ≥ 1, which sets the `release:` line ([src/backend/CLAUDE.md](../../src/backend/CLAUDE.md) § Migrations).
- **The release major is the protocol major** ([ADR 0085](../decisions/0085-one-major-moved-on-a-yearly-train.md)
  decision 1). The precise release is operator information, never pre-auth (ADR 0057 § 4): it is stamped as `ENV` into
  the runtime stage of an image only, never into a stage that compiles a client, and the Server Owner reads it in
  admin-web ([uc.admin.identify-instance-release](../features/admin/use-cases/uc.admin.identify-instance-release.md)).

## The pipeline

`ci.yml` is the one pipeline per commit ([ADR 0091](../decisions/0091-one-pipeline-per-commit-a-release-promotes-a-tested-snapshot.md)
decision 2). It runs on a push to `main` or `release/**` and on a pull request into one (a change to docs, Markdown or
the site only is left to `trust-chain.yml`, which is unfiltered), and on a `workflow_dispatch`. Its stages start
together; the image build needs only the version, and lane L1 needs the build. Every job has a budget
(`timeout-minutes`, held by `scripts/validate_pipeline_shape.py`). **The stages themselves are listed once**, in
[How veyq is tested](../../site/src/content/docs/security/how-veyq-is-tested.md).

**A push or a pull request is a dry run.** It runs every stage and builds every image (app, admin, tools; amd64 and
arm64) and writes to no registry; nothing is published by a push (ADR 0057 § 1).

## Publishing: a snapshot or a release

A publish is a `workflow_dispatch` from `main` or `release/<major>.x`; the `version` job refuses any other branch. The
run is the whole pipeline once more, and then:

1. `build` pushes each image under a staging tag, `build-<sha>-<arch>` — unsigned, carrying no release name.
2. Lane L1 (`stack`) installs exactly those images, pulled by the digests `build` handed over
   ([ADR 0090](../decisions/0090-end-to-end-tests-boot-the-release-candidate.md) decision 2).
3. `publish` runs only when every other job of the run is green (the disabled `upgrade-proof`'s *skipped* tolerated);
   `validate_release_identity.py` holds its `needs` to every job of the file. It names those same digests:
   - **A snapshot** (the default): the images as `<major>.<minor>-SNAPSHOT` and `sha-<commit>`, the chart as
     `<major>.<minor>-SNAPSHOT`, signed; no git ref, no GitHub Release. **A dispatch again on the same commit replaces
     it** — it is the one name that moves, and never a rollback target.
   - **A release** (`release: true`): `<major>.<minor>`, `<major>`, `sha-<commit>` and, from `main`, `latest`. A name
     that already exists is **refused before anything is pushed**. The images and the chart are signed keylessly; then
     the git ref `v<major>.<minor>` is created; then the release's assets are produced, `manifest.json` and `veyqctl`
     signed with `cosign sign-blob`, and one `gh release create` uploads them all — last, so a release that has a
     GitHub Release is a whole one.
   - `unscheduled_major: true` marks a release as an unscheduled major in its manifest
     ([ADR 0085](../decisions/0085-one-major-moved-on-a-yearly-train.md)), refused unless it is the first of its major.
4. **The image carries the version it was built for**: `VEYQ_VERSION` is `<major>.<minor>-SNAPSHOT` in a snapshot's
   images and `<major>.<minor>` in a release's. A release is built by its own dispatch and tested by that run's lanes;
   it does not reuse a snapshot's bytes.

**The signing identity** is `.github/workflows/ci.yml` in `raddatzk/veyq` on `refs/heads/main` or
`refs/heads/release/<major>.x`, with GitHub's OIDC issuer (ADR 0057 § 3); the operator's check is
[Verify a release](../../site/src/content/docs/run/verify-a-release.md), and `validate_release_identity.py` holds that
page to the branches the workflow publishes from.

**What a release consists of:**

| Artifact | Where | Notes |
|---|---|---|
| `veyq` (the app, `runtime-jvm`), `veyq-admin` (admin-web on nginx), `veyq-tools` (the backup tool, for the cluster's gate) | `ghcr.io/<owner>/…`, amd64 and arm64 | signed by digest; `veyq-tools` bakes the release a new major is entered from (`release_assets.py --major-entry`) |
| The Helm chart | `oci://ghcr.io/<owner>/charts/veyq` | `version` = `appVersion` = the publish's version, set at packaging; the tree's `Chart.yaml` carries the placeholder `0.0-dev` |
| The GitHub Release on `v<major>.<minor>` | the repository's releases | `docker-compose.prod.yml`, `env.example`, every file the compose file reads beside itself (`garage.toml`), `veyqctl`, `instance_backup.py`, `manifest.json` (the three images by `tag@digest`, every asset by its sha256), and the two signature bundles ([ADR 0096](../decisions/0096-veyqctl-manages-an-instance-from-immutable-releases.md) decision 3) |

The asset set is **derived from the compose file**, never listed (`scripts/release_assets.py`; assertion 4 of
`validate_release_identity.py` holds every derived file to the tree). **No image carries a recipe.** The same producer
writes the mirror layout `veyqctl` reads (`releases.json`, `<tag>/…`), which lane L1 serves as a local source.

**A release pins what it runs and what it is built from** (ADR 0057 § 6): third-party images by digest, written
`repo:tag@sha256:…` (`validate_image_pins.py`); Dockerfile bases by their index digest; the OpenAPI generator JAR, the
Gradle wrapper and distribution by SHA-256, the backend's dependencies by Gradle verification; the actions by commit
SHA (`validate_action_pins.py`); Helm by its tarball's SHA-256. Dependabot's `docker`, `docker-compose` and `helm`
ecosystems propose bumps ([`.github/dependabot.yml`](../../.github/dependabot.yml)); bumping a pin is part of cutting a
release.

## The two deployment targets

Every infra-level decision has a **Compose** and a **Kubernetes** answer, and a change to one checks the other
([ADR 0064](../decisions/0064-kubernetes-helm-deployment-target.md) § Consequences). Their files are `infra/docker/` and
`infra/kubernetes/` ([ADR 0106](../decisions/0106-deployment-under-infra-tests-under-tests.md)); a script learns the
paths from `scripts/repo_layout.py`.

| | Compose | Kubernetes |
|---|---|---|
| Recipe | `docker-compose.prod.yml`, project `veyq`: `traefik`, `app`, `admin`, `mariadb`, `garage` | the chart: the `Gateway`, `app` (one pod, `strategy: Recreate`), `admin`, `mariadb` (`mariadb.enabled: false` for the operator's own), `garage` (opt-in) |
| Managed by | `veyqctl` on the host (below) | `helm upgrade` by exact version or digest, and the operator's GitOps tooling; no in-cluster operator ([ADR 0096](../decisions/0096-veyqctl-manages-an-instance-from-immutable-releases.md) decision 8) |
| Tenant routing | the backend writes Traefik's dynamic file from the database ([ADR 0063](../decisions/0063-database-driven-tenant-ingress.md)) | the same projector's Gateway API sink: per domain an `HTTPRoute` and, under `per-domain`, a `ListenerSet`, labelled `managed-by: veyq-tenant-ingress`, through a namespace-local Role on those two kinds only — nothing on `gateways` |
| TLS | Traefik's ACME resolver, referenced on every router | `veyq.tenant.ingress.kubernetes.tls.*`: `none` (default), `per-domain`, `shared`; an opt-in namespaced cert-manager `Issuer` (`gateway.tls.acme`, `per-domain` only) |
| Bundled store | on, on the app's origin ([ADR 0013](../decisions/0013-blob-storage-presigned-direct-to-store.md) § 4) | opt-in (`garage.enabled`), on a host of its own with a CORS policy written at runtime ([ADR 0067](../decisions/0067-store-provisioning-from-a-declared-type.md)) |
| Admin console | the `admin` container, reachable on the host's loopback; `ADMIN_DOMAIN` optional, set after setup | the same image, its own `HTTPRoute` only when `admin.domain` is set |
| Hardening | every container unprivileged, reachable only by its peers ([ADR 0071](../decisions/0071-runtime-hardening-on-both-targets.md)) | every pod passes `restricted`; a default-deny NetworkPolicy set (ADR 0071) |
| Backup before a migrating start | `veyqctl upgrade` | the `backup-gate` init container ([ADR 0098](../decisions/0098-on-kubernetes-the-start-that-would-migrate-backs-up-first.md)) |

**The chart generates no secret**: every value is the operator's, enforced by `values.schema.json`, except the
config-secret KEK, which the app generates onto its own claim ([ADR 0050](../decisions/0050-operator-config-secret-encryption.md)).
The app migrates its schema at start, which is why it is one pod under `Recreate`. Two checks need `helm` and sit
outside the guard glob, in `ci.yml`'s `chart` job: `scripts/chart_meta_check.py` holds the rendered manifests to these
rules, and `scripts/chart_upgrade_check.py` renders the predecessor's chart and the candidate's with the same values and
fails on a change to a field Kubernetes refuses to change, or an operator's values the new schema refuses. It renders,
never installs; until the first release it informs and gates nothing.

**Administration is not management.** Administration is what the Server Owner does inside a running instance, through
the Admin API: admin-web and its Node CLI, one image, `veyq-admin` ([ADR 0065](../decisions/0065-admin-ui-cli-separate-service.md)).
The backend answers that API only on its admin listener (port `8090`, never published), which only the admin container
reaches ([uc.admin.admin-channel](../features/admin/use-cases/uc.admin.admin-channel.md)). Instance settings live
there; the environment only bootstraps ([ADR 0089](../decisions/0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md)).
The backend serves the web client's shell itself ([ADR 0069](../decisions/0069-backend-serves-the-web-app-shell.md)).
Management — the instance's lifecycle on the host — is `veyqctl`'s, and neither holds the other's access.

## `veyqctl` (Compose)

One Python file, standard library only ([`src/tools/veyqctl.py`](../../src/tools/veyqctl.py), its docstring the
command reference; [ADR 0096](../decisions/0096-veyqctl-manages-an-instance-from-immutable-releases.md)). An instance
lives in `--home` (default `/opt/veyq`): `releases/<release>/`, `.env`, `backups/`, and the operator's
`compose.override.yml`, laid over the recipe on every command.

- **Which release:** `.env`'s `VEYQ_RELEASE` — an exact release (pinned), a major (its newest release) or `latest`.
  **A major is entered only from the newest release of the one before**, and no run crosses more than one major.
- **What it trusts:** it verifies `manifest.json.bundle` before it reads any digest or sha256 the manifest names, then
  both images' signatures by those digests, then each asset against its sha256. So a source — GitHub by default, a
  mirror or a directory (`--source`) — decides which releases are offered, never what one contains. `--unsigned-local`
  is the one way past the signatures, accepted only for a local build's bare `name:tag` (lane L1's path).
- **`upgrade` runs only by hand**, in this order: resolve; verify and download; refuse a value the new `.env.example`
  requires that `.env` lacks; back up, stop the app, switch the recipe and the image lines, start, wait until healthy.
  The previous release's directory stays.
- The commands — `install`, `start`/`stop`/`status`, `logs`, `verify`, `check`, `upgrade`, `backup`, `restore`,
  `rollback`, `self-update` — are documented for the operator on
  [Set up a new instance](../../site/src/content/docs/run/new-instance.mdx), [Upgrade](../../site/src/content/docs/run/upgrade.mdx),
  [Backup and restore](../../site/src/content/docs/run/backup-and-restore.md) and
  [Verify a release](../../site/src/content/docs/run/verify-a-release.md).

## The backup before a migrating start

The rule is [ADR 0056](../decisions/0056-instance-durable-state-and-cold-logical-backup.md) § 6: a backup is the
precondition of an upgrade, and the way back is to restore the backup taken before it. A capture is the database, the
store's **index** (never the objects, which an upgrade does not rewrite) and, apart from both, the config-secret key;
one tool takes it on both targets, `instance_backup.py`, in one format.

- **Compose** — `veyqctl upgrade` captures with the app stopped, before it switches releases; with an object pool
  configured it copies the new objects there first. `veyqctl rollback` returns to the release a capture was taken
  under, in place.
- **Kubernetes** — the `backup-gate` init container of the app pod runs `instance_backup.py gate` in the release's
  `veyq-tools` image. Under `Recreate` the old pod has ended before it starts, so the capture is cold, and it runs the
  same under `helm`, Argo CD, Flux or `kubectl apply`. It captures when the target release differs from the one its
  record (`state.json` on the backup claim) names, or when `backup.request` changed; it refuses a downgrade, a jump of
  more than one major and a major entered from any release but the one the tools image names. It never mounts the
  key. The claim is the operator's (`backup.existingClaim`); the schema refuses an install that names neither a claim
  nor `backup.enabled: false`. A failed or refused gate keeps the app from starting, with nothing migrated. A restore
  is a one-off Job, never automatic, run with the tools image of the release that wrote the capture
  ([Backup and restore](../../site/src/content/docs/run/backup-and-restore.md), [Kubernetes](../../site/src/content/docs/run/kubernetes.md)).

**The upgrade is proven before a release** (ADR 0057 § 7): `scripts/run_web_e2e.py --upgrade` lets the predecessor's
own build write sealed content, captures it, restores it onto the candidate, which migrates it, and has the
candidate's suites open it. The predecessor is the first parent until `release:` is set, then the newest `v*` tag. Its
CI job, `upgrade-proof`, is written and switched off (`if: false`) until the first release, which switches it on in the
same change that sets `release:`.

## The release check

The backend looks for newer releases itself ([ADR 0097](../decisions/0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md)
decisions 1–2): `ReleaseCheck` runs on `veyq.release-check.every`, reads the published releases from the source the
Server Owner set in admin-web (the GitHub Releases, or a mirror) and compares them with its own `VEYQ_VERSION`
locally. It is on by default and switched off in admin-web; an image without a release version never checks. What it
finds is a notice and never an action (`ReleaseNoticePolicy`): a newer release in the running major, a planned or an
unscheduled new major, and the client window's end 90 and 30 days ahead. admin-web shows the notices; once instance
mail is configured and its address confirmed, each is mailed once — the channel is [cc.mail](cc.mail.md)'s. A failed
check is logged and retried with a back-off, and blocks nothing.

## Decided, not built

- **A green push publishes a snapshot; a release promotes it** ([ADR 0091](../decisions/0091-one-pipeline-per-commit-a-release-promotes-a-tested-snapshot.md)
  decision 5; replaces part of ADR 0057 § 1). Every green push to `main` or `release/<major>.x` would publish its
  images as `<major>.<minor>-SNAPSHOT` on the tested digests, **written once per commit and never replaced**, with each
  lane's result as a signed attestation naming its inputs. A release would be a dispatch that verifies a snapshot's
  attestations, runs only the lanes whose inputs moved, and gives the same digests the release names — nothing
  rebuilt; the asset set produced again and refused if its hash differs from the attested one. The image would then
  carry `<major>.<minor>`, never `-SNAPSHOT`. Retention: the 20 newest unpromoted snapshots per branch and every
  promoted one.
- **Lanes L2 and L3 and the backup archive** (ADR 0091 decisions 3 and 6): the previous release upgraded in place
  with `veyqctl upgrade`, and every archived backup of the major, with its object pool, restored into the candidate.
  When they gate, the jar-level upgrade proof retires (decision 7; replaces ADR 0057 § 7). Until then the release a
  major is entered from is the one `--major-entry` derives, not an attested L2 input.
- **Immutable releases and the first publish** (ADR 0096 decision 3): the repository setting and the first dispatch
  wait on the remote. The staged order of the first runs is in [`infra/README.md`](../../infra/README.md) § Cutting a release.
- **The window of majors a client speaks** ([ADR 0085](../decisions/0085-one-major-moved-on-a-yearly-train.md)
  decision 5). The yearly train, its release order and the previous major's release branch (decisions 2, 3 and 10)
  are a procedure, written in [`infra/README.md`](../../infra/README.md) § Cutting a release, and no train has left.
- **Lane L3 holding the major entry** (ADR 0085 decision 10, ADR 0096): the previous major's last release in L3's
  archive. What moves the entry is built — a `release/<old>.x` publish, and every release of the current major proved
  from the newest release of the previous major among every tag, a branch's included, and baking it for its gate
  (ADR 0098 decision 3; `run_web_e2e.major_entry_ref()`).
