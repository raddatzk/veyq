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

**Built, first run on GitHub.** Everything below is in the tree unless it stands under
[§ Decided, not built](#decided-not-built). The repository has a remote since 2026-11-05 and the pipeline has run there; no
release exists yet: `VERSION` is `0`, git holds no `v*` tag, and `scripts/applied_migrations.sha256` says
`release: none`. A statement about what CI or a publish *does* is a statement about
[`checks.yml`](../../.github/workflows/checks.yml) (the pipeline) and [`publish.yml`](../../.github/workflows/publish.yml)
(the snapshot), held by `scripts/validate_release_identity.py`; what a run on GitHub has shown is inbox #471's list,
and a local run goes through `scripts/run_pipeline.py`, which reads checks.yml and runs what this machine can.

## The version

A release is `<major>.<minor>`, a function of the commit ([ADR 0057](../decisions/0057-release-identity-and-deployment-recipe.md) § 1):

- `VERSION` holds the **major** alone; the **minor** is the number of commits since the one that last changed `VERSION`.
  The pipeline's `version` job computes it offline and refuses a shallow clone, which would count 1.
- **Major `0` is pre-release:** it exercises the publish path and promises no upgrade path. The migration set is
  frozen by the first release of a major ≥ 1, which sets the `release:` line ([src/backend/CLAUDE.md](../../src/backend/CLAUDE.md) § Migrations).
- **The release major is the protocol major** ([ADR 0085](../decisions/0085-one-major-moved-on-a-yearly-train.md)
  decision 1). `VERSION`'s major is compiled into the backend, which advertises it in `GET /api/setup`, and into the web
  build; no runtime setting names it, so no instance can advertise a major its code does not speak. The precise release
  is operator information, never pre-auth (ADR 0057 § 4): it is stamped as `ENV` into the runtime stage of an image
  only, never into a stage that compiles a client, and the Server Owner reads it in admin-web
  ([uc.admin.identify-instance-release](../features/admin/use-cases/uc.admin.identify-instance-release.md)).

## The pipeline

`checks.yml` (**Checks**) is the one pipeline per commit ([ADR 0091](../decisions/0091-one-pipeline-per-commit-a-release-promotes-a-tested-snapshot.md)
decision 2). It runs on a push to `main` or `release/**` and on a pull request into one (a change to docs, Markdown or
the site only is left to `trust-chain.yml`, which is unfiltered). Its stages start together; the image build needs
only the version, and lane L1 needs the build. Every job has a budget (`timeout-minutes`, held by
`scripts/validate_pipeline_shape.py`). **The stages themselves are listed once**, in
[How veyq is tested](../../site/src/content/docs/security/how-veyq-is-tested.md).

**It publishes nothing.** A pull request and a local run run every stage and build every image (app, admin, tools;
amd64 and arm64 — a pull request's lane on amd64 only) and write to no registry and no remote: the `version` job's
`kind` is `none` for them, a pull request's images are built by `build-local`, which holds no write, and every write
of the file is gated on `kind == 'stage'`. A push to `main` or `release/<major>.x` (`kind` `stage`) also **stages**:
`build`, the one job with `packages: write`, on a push alone, pushes each image as `build-<sha>-<arch>`, and the lanes
install exactly those (below). What a push stages is what Publish may sign.

## Publishing: a snapshot weekly, or by dispatch

**Publish** (`publish.yml`) publishes a **snapshot** (ADR 0091 decision 5, amended 2026-11-07) on two triggers: the
weekly `schedule` (Monday 03:17 UTC; GitHub runs it on the default branch only, so it publishes `main`'s HEAD) and a
`workflow_dispatch` — Actions → Publish → *Run workflow* — on `main` or `release/<major>.x`, the one way a release
branch's fix gets a snapshot. It runs no pipeline and builds nothing: it publishes the **publishable commit's newest
Checks push run**, and only when that run concluded `success` with its gate and every lane leg on both architectures
green; a run still going, red, or cancelled by hand is refused, named, and Publish never starts Checks. The publishable
commit is HEAD, or — Checks skips a commit that changes only what its push `paths-ignore` names (docs, Markdown, the
site), so a docs-only HEAD has no run — the newest first-parent ancestor with a run, provided every commit after it is
docs-only (amended 2026-11-08; within 50 commits, refused and named otherwise); every name the snapshot writes, its
version and its `snapshot-` tag are that commit's. It takes minutes. It publishes nothing for a publishable commit that
already carries a `snapshot-` tag (its own full checkout's tags), and is red on any other branch. Until 2026-11-07 every green push published, then for a few hours a Publish ran the pipeline itself; both are
gone. Publish has its own concurrency group per ref, so two publishing runs go one after the other and the later one
sees the earlier one's tag.

1. In the Checks push run, `build` pushes each image under a staging tag, `build-<sha>-<arch>` — unsigned, carrying
   no published name — and keeps its digest as an artifact of the run (30 days).
2. Lane L1 (`stack`) installs exactly those images, pulled by the digests `build` handed over
   ([ADR 0090](../decisions/0090-end-to-end-tests-boot-the-release-candidate.md) decision 2), and each green leg records
   what it ran (`scripts/snapshot.py lane-result`): its digests and the hash of the asset set it installed
   (`release_assets.py --asset-set`, the manifest's asset map: what a promotion produces again from the same commit),
   kept as an artifact of the run too. The gate `green` needs every stage (the disabled `upgrade-proof`'s and the
   other event's build's *skipped* tolerated); `validate_release_identity.py` holds its `needs` to every job of the file.
3. Publish's `checks-run` finds the publishable commit and its run (`scripts/snapshot.py checks-run`: the first-parent
   history and each commit's changed paths, then the API's runs and jobs of the commit), and `snapshot` checks that
   commit out and downloads its digests and lane records by its run id and refuses unless every lane record names exactly
   those digests and every staging tag still resolves to its digest (`snapshot.py staged`). It refuses next: a snapshot is
   **written once per commit and never replaced**, so each image's `<major>.<minor>-SNAPSHOT` and `sha-<commit>` must
   be free or already name exactly this build (a re-run of a half-done snapshot continues; a second build of the commit,
   or a version another commit took, is refused; a registry read that fails for any reason but "not found" is refused
   too). Then it names those digests `<major>.<minor>-SNAPSHOT` and `sha-<commit>` — no `<major>`, no `latest` — packages
   the chart as `<major>.<minor>-SNAPSHOT` (its `appVersion` is the image tag its values default to), signs the three
   images and the chart keylessly, and attaches each lane's result as a signed in-toto attestation per architecture
   (`https://veyq.app/attestations/stack-lane/v1`: lane, architecture, outcome, shards, the Checks run, the digests, and the inputs —
   the asset set; a predecessor and backups once lanes L2 and L3 exist) to the app and admin images. No GitHub Release.
4. `snapshot-tag`, a job that holds `contents: write` and nothing else, sets the git tag `snapshot-<major>.<minor>` on
   the commit, so GitHub's release page offers the snapshots that can be promoted. Never `v…`. It creates the tag
   through the REST API (`git/refs`), as `release.yml` creates `v<major>.<minor>`, never by `git push`: GitHub refuses a
   `GITHUB_TOKEN` push of a ref whose commit's `.github/workflows/` differs from every branch head, which is every
   tag on a commit main has since moved past with a workflow change (0.2347's tag, 2026-11-07). That the API is not held
   to that rule is reported by others, not documented by GitHub; the next such run proves it.
5. **Staging images are deleted** by the weekly run's `staging-retention` 30 days after their push, unless their commit
   is a branch tip (`scripts/staging_retention.py`, selftested; on from its first run, owner 2026-11-07). A staging
   index is the image and its attestation manifest; a snapshot's own index names those two, so for a commit with a
   snapshot the staging index goes alone, and for one without it goes with its children. The deletion is GitHub's
   package API under the workflow's token — a public preview, proven by its first run.
6. **The image carries `<major>.<minor>`, never `-SNAPSHOT`**: a release is the snapshot's bytes, so the two carry
   one name, and a snapshot reports the release it may become. Whether a digest *is* a release is the release
   signature's fact. Images published before this carry `<major>.<minor>-SNAPSHOT`.

**Two signing identities**, both GitHub's OIDC issuer, in `raddatzk/veyq`, on `refs/heads/main` or
`refs/heads/release/<major>.x`: a **snapshot** is signed by `.github/workflows/publish.yml` (the file that holds the signing job: keyless
signing names it, so `snapshot` is Publish's own job, not the pipeline's; snapshots signed by `ci.yml` before the split
of 2026-11-07 are not promotable), a **release** by
`.github/workflows/release.yml` — the promotion (below). The operator's check, [Verify a
release](../../site/src/content/docs/run/verify-a-release.md), and `veyqctl` pin the release identity and reject the
snapshot's, so a snapshot never verifies as a release; `validate_release_identity.py` holds both to that. The owner and
repository in those strings come from the chart's `home:`; a move of the repository edits that line, and the guard
names every line that must follow it.

## Releasing: a snapshot promoted

A release is a snapshot promoted (ADR 0091 decision 5); nothing is built again. The owner saves a **draft** GitHub
Release on a `snapshot-<major>.<minor>` tag, with the notes, and starts `release.yml` (*Run workflow*) on the branch
the snapshot came from; the click path is [`infra/README.md`](../../infra/README.md) § Cutting a release. GitHub starts
no workflow for a draft, which is why the dispatch starts it. Its jobs, each holding only what it writes, in group
`veyq-release`:

1. `check` (`scripts/promote.py`) refuses unless exactly one draft exists, on a snapshot tag; the run's branch may
   release that major (`main` its `VERSION`, `release/<major>.x` its own); the tag's commit counts that version and is on
   the branch; it is newer than every release of its major (so `<major>` and `latest` never move backwards); the
   snapshot's `sha-<commit>` and `-SNAPSHOT` name one index per image, signed under the snapshot identity; no release
   name is taken by anything else; every lane attested a pass on every architecture and shard, on exactly the index's
   builds; the asset set produced again from the commit equals the attested one; and the tools image is stamped
   `<major>.<minor>` with the major entry the tags give now.
2. `promote` gives each index `<major>.<minor>` (read back: the tested digest), signs the three with the release
   identity, packages the chart from the snapshot's commit as `<major>.<minor>` and signs it, and produces the release's
   files with that commit's producer, checks them against the plan and signs `manifest.json` and `veyqctl`.
3. `publish` verifies the files as `veyqctl` will, sets the git ref `v<major>.<minor>` on the commit, moves the draft
   onto it (or, if GitHub refuses, replaces it with a new draft carrying its notes), attaches every file and publishes
   the draft **last** — `latest` on GitHub from `main` only — then removes the `snapshot-` tag.
4. `channels` then points `<major>` and, from `main`, `latest` at the release.

A refusal or failure before publication leaves the draft a draft, back on its snapshot tag, with the reason appended to
its notes by `refused`, which also removes a `v` tag the run had set; a second run accepts the registry names the first
had already given this snapshot and completes them. `unscheduled_major` marks an unscheduled major in the manifest
(ADR 0085), refused by the producer unless it is the first release of its major.

**What a release consists of:**

| Artifact | Where | Notes |
|---|---|---|
| `veyq` (the app, `runtime-jvm`), `veyq-admin` (admin-web on nginx), `veyq-tools` (the backup tool, for the cluster's gate) | `ghcr.io/<owner>/…`, amd64 and arm64 | signed by digest; `veyq-tools` bakes the release a new major is entered from (`release_assets.py --major-entry`) |
| The Helm chart | `oci://ghcr.io/<owner>/charts/veyq` | `version` = `appVersion` = the release's version, set at packaging (a snapshot's chart: `<major>.<minor>-SNAPSHOT`); the tree's `Chart.yaml` carries the placeholder `0.0-dev` |
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
| Tenant routing | the backend's projector writes Traefik's dynamic file from the database ([ADR 0063](../decisions/0063-database-driven-tenant-ingress.md)), the bootstrap domain's routers from configuration | the same projector's Gateway API sink: per domain an `HTTPRoute` and, under `per-domain`, a `ListenerSet`, labelled `managed-by: veyq-tenant-ingress`, through a namespace-local Role on those two kinds only — nothing on `gateways`; the bootstrap domain is the chart's own route |
| TLS | Traefik's ACME resolver, referenced on every router | `veyq.tenant.ingress.kubernetes.tls.*`: `none` (default), `per-domain`, `shared`; an opt-in namespaced cert-manager `Issuer` (`gateway.tls.acme`, `per-domain` only) |
| Bundled store | on, on the app's origin ([ADR 0013](../decisions/0013-blob-storage-presigned-direct-to-store.md) § 4): a URL is presigned for the tenant's domain | opt-in (`garage.enabled`), on a host of its own with a CORS policy written at runtime ([ADR 0067](../decisions/0067-store-provisioning-from-a-declared-type.md)): a URL is presigned for the store's own host (decision 5) |
| Admin console | the `admin` container, reachable on the host's loopback; `ADMIN_DOMAIN` optional, set after setup | the same image, its own `HTTPRoute` only when `admin.domain` is set |
| Hardening | every container unprivileged, reachable only by its peers ([ADR 0071](../decisions/0071-runtime-hardening-on-both-targets.md)) | every pod passes `restricted`; a default-deny NetworkPolicy set (ADR 0071) |
| The app's health | the healthcheck is the image's own `health` command on its `java` — the image is distroless, no shell, no `curl` ([ADR 0118](../decisions/0118-the-app-image-is-distroless-and-carries-its-own-commands.md)); `veyqctl` waits on it | `httpGet` probes on `/api/setup` |
| Backup before a migrating start | `veyqctl upgrade` | the `backup-gate` init container ([ADR 0098](../decisions/0098-on-kubernetes-the-start-that-would-migrate-backs-up-first.md)) |

**Tenant routing is one projection with a sink per target**
([ADR 0063](../decisions/0063-database-driven-tenant-ingress.md)). The projector regenerates the whole routing from the
`tenant` table on every tenant create, domain change and deactivation, and once at startup before serving, never as a
patch; which proxy it tells is named by `veyq.tenant.ingress.sink`, never inferred. A sink never throws: a failed
projection fails neither the tenant operation that triggered it nor the start.

**The chart generates no secret**: every value is the operator's, enforced by `values.schema.json`, except the
config-secret KEK, which the app generates onto its own claim ([ADR 0050](../decisions/0050-operator-config-secret-encryption.md))
unless the operator names a Secret holding their own (`app.configSecret.existingSecret`, ADR 0118).
The app migrates its schema at start, which is why it is one pod under `Recreate`. Two checks need `helm` and sit
outside the guard glob, in the pipeline's `chart` job: `scripts/chart_meta_check.py` holds the rendered manifests to these
rules, and `scripts/chart_upgrade_check.py` renders the predecessor's chart and the candidate's with the same values and
fails on a change to a field Kubernetes refuses to change, or an operator's values the new schema refuses. With
`--upgrade-major-entry` it renders the same comparison from the release's major entry
([ADR 0085](../decisions/0085-one-major-moved-on-a-yearly-train.md) decision 10), and says why and passes when there is
none or it is the default predecessor. It renders, never installs; until the first release it informs and gates
nothing.

**The config-secret key** ([ADR 0050](../decisions/0050-operator-config-secret-encryption.md)) is a file the app
generates at its first start onto its own persistent volume (a named volume under Compose, a claim of its own under
Kubernetes), never in the database — or the operator's own: under Kubernetes a `Secret` the chart mounts read-only, the
erasure ledger then set onto the claim; under Compose a key put into the volume with the image's `place-key`
([ADR 0118](../decisions/0118-the-app-image-is-distroless-and-carries-its-own-commands.md) decision 6). It seals three holders: the tenants' SMTP passwords,
the instance mail password and the object store's setting. Each sealed value carries the fingerprint of the key that
sealed it, so a missing or replaced key is detected per value; it degrades and never bricks: what it cannot open is
named in admin-web and only what needs it stops, until that value is entered again. A capture keeps the key apart from
the dump ([ADR 0056](../decisions/0056-instance-durable-state-and-cold-logical-backup.md) § 1); what it protects
against, and what not, is the [security model](security-model.md)'s.

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
  **A major is entered only from its major entry** ([ADR 0085](../decisions/0085-one-major-moved-on-a-yearly-train.md)
  decision 10), which the next major's signed manifest names as `majorEntry`. An instance on an older release of its
  major upgrades to the entry first and is told that the next `upgrade` enters the new major; one on a later release of
  the old major (a fix cut after the entry) is refused until a release of the new major entered from it exists. No
  run crosses more than one major ([ADR 0096](../decisions/0096-veyqctl-manages-an-instance-from-immutable-releases.md)
  decision 5).
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
  configured it copies the new objects there before the app stops and tops the copy up after, so the downtime grows by
  the newest uploads only. `veyqctl rollback` returns to the release a capture was taken under, in place.
- **Kubernetes** — the `backup-gate` init container of the app pod runs `instance_backup.py gate` in the release's
  `veyq-tools` image. Under `Recreate` the old pod has ended before it starts, so the capture is cold, and it runs the
  same under `helm`, Argo CD, Flux or `kubectl apply`. The target release is the app image's tag, which the chart
  renders into the gate's environment. It captures when the target differs from the release its record (`state.json` on
  the backup claim) names, or when `backup.request` changed; it refuses a downgrade, a jump of more than one major and a
  major entered from any release but the major entry the tools image bakes. It takes the store's index only and no
  object pool ([ADR 0098](../decisions/0098-on-kubernetes-the-start-that-would-migrate-backs-up-first.md) decision 4),
  and never mounts the key. A capture is written under `.partial` and renamed when complete; then the gate prunes beyond
  `backup.keep` complete captures and every unfinished one, and at every start whatever is older than the retention
  bound ([cc.privacy.retention](cc.privacy.retention.md) § Backups and the erasure ledger). The claim is the operator's
  (`backup.existingClaim`); the schema refuses an install that names neither a claim nor `backup.enabled: false`. A
  failed or refused gate keeps the app from starting, with nothing migrated. A restore is a one-off Job, never
  automatic, run with the tools image of the release that wrote the capture
  ([Backup and restore](../../site/src/content/docs/run/backup-and-restore.md),
  [Kubernetes](../../site/src/content/docs/run/kubernetes.md)).

**The upgrade is proven before a release** (ADR 0057 § 7): `scripts/run_web_e2e.py --upgrade` lets the predecessor's
own build write sealed content, captures it, restores it onto the candidate, which migrates it, and has the
candidate's suites open it. The default predecessor is the commit's first parent until `release:` is set, then the
newest release tag that first parent reaches. **A release is proved a second time from its major entry**
([ADR 0085](../decisions/0085-one-major-moved-on-a-yearly-train.md) decision 10), `--upgrade-major-entry`, when that is
a release other than the default predecessor. The entry is derived once, `run_web_e2e.major_entry_ref()`; the same
release is what `release_assets.py --major-entry` bakes into the tools image for the gate and names as the signed
manifest's `majorEntry`, by which `veyqctl` routes, so neither target enters a major from a release the pipeline did
not prove. The CI job, `upgrade-proof`, runs one leg per predecessor, each on its own budget; a leg asks `--resolve`
first and builds nothing when it has nothing to prove. The job is written and switched off (`if: false`) until the first
release, which switches it on in the same change that sets `release:`.

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

- **The rerun of moved lanes, and retention** ([ADR 0091](../decisions/0091-one-pipeline-per-commit-a-release-promotes-a-tested-snapshot.md)
  decision 5). A promotion compares each lane's recorded inputs with the current ones; while L1 is the only lane none
  can move, and an input it cannot compare (a predecessor, a backup list) is refused rather than rerun — the rerun comes
  with lanes L2 and L3. Retention — the 20 newest unpromoted snapshots per major and every promoted one, a scheduled
  job deleting the rest with their `snapshot-` tags — is not built: until it is, every snapshot stays in GHCR.
- **Lanes L2 and L3 and the backup archive** (ADR 0091 decisions 3 and 6): the previous release upgraded in place
  with `veyqctl upgrade`, and every archived backup of the major, with its object pool, restored into the candidate.
  When they gate, the jar-level upgrade proof retires (decision 7; replaces ADR 0057 § 7).
- **Immutable releases and the first release** (ADR 0096 decision 3): the repository setting is the owner's, before
  the first promotion. What the first runs on GitHub must show is inbox #471.
- **The window of majors a client speaks** ([ADR 0085](../decisions/0085-one-major-moved-on-a-yearly-train.md)
  decision 5). The yearly train, its release order and the previous major's release branch (decisions 2, 3 and 10)
  are a procedure, written in [`infra/README.md`](../../infra/README.md) § Cutting a release, and no train has left.
- **L3's archive holding the major entry** (ADR 0085 decision 10, inbox #461): the major entry's backup in the
  archive lane L3 restores.
