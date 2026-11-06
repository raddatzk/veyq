# infra — local dev stack

The deployment lives here by target ([ADR 0106](../docs/decisions/0106-deployment-under-infra-tests-under-tests.md)):
[`docker/`](docker/) holds what Compose runs — the dev stack's `docker-compose.yml` this file is about, the production
recipe `docker-compose.prod.yml` and what both read beside them; [`kubernetes/`](kubernetes/) holds the Helm chart
(`infra/kubernetes/charts/veyq/`). An instance's local `.env` sits beside the compose file, in `infra/docker/`. The
images' Dockerfiles are not here: each lives beside the code it ships (`src/backend/`, `src/admin-web/`, `src/tools/`
for the `veyq-tools` image only the chart runs), built from the repo root.

## The launcher: `./gradlew <surface>:<verb>`

The repo root is a Gradle build whose only job is to **trigger** what this file and the surfaces' own docs
describe — the compose lines below, the package scripts, the backend's own `./gradlew` — from a terminal or from
IntelliJ's Gradle tool window (link the root `settings.gradle.kts` as a Gradle project beside `src/backend/`, which
stays its own). It replaces the IntelliJ run configurations `.run/` used to carry. It is not an orchestrator: it
holds no surface's build, and no task aggregates their checks — each still proves itself with its own
`pnpm run check` / `./gradlew check` (the tasks live in `launcher/<surface>/build.gradle.kts`; no task is named
`check`, `test` or `clean`, so a bare `./gradlew check` aggregates nothing).

| Task | Runs |
|---|---|
| `infra:up` · `infra:down` · `infra:wipe` | the compose lines below with `--profile s3 --profile mail` (`up -d`, then waiting on MariaDB's healthcheck) · `down` · `down -v` — **wipe deletes the dev database and every blob** |
| `web:bootstrap` | `pnpm install` in `src/web/` — the workspace install — then `pnpm run generate:api`, which regenerates web's API client (the install's `prepare` does so only when the install changed something) |
| `admin:bootstrap` | `pnpm install` in `src/admin-web/` — the same workspace install — then `pnpm run generate:api` for admin-web's API client |
| `site:bootstrap` | `pnpm install` in `site/` (outside the workspace) |
| `backend:run` · `web:run` · `admin:run` · `site:run` | `quarkusDev` · Vite on 8081 · Vite on 5174 · Astro on 4321 |
| `backend:seed` | `pnpm run bootstrap:user` in `src/web/` ([§ Seeding](#seeding-the-local-dev-principal)) |
| `backend:build` · `web:build` · `admin:build` · `site:build` | `quarkusBuild` (no tests) · `pnpm run build` in each |
| `backend:image` · `admin:image` | `docker build` of `src/backend/Dockerfile` (the app image, web's SPA baked in) · of `src/admin-web/Dockerfile`, context the repo root as CI builds them; both tagged `local`, a tag no publish emits, so it is never one to deploy |

A cold start is `infra:up` → `backend:run` → `web:run` (and `admin:run` for first-run setup) → `backend:seed`, then
browse `veyq.localhost:8081`. `backend:run` does not start the infra on its own: a backend restart should not touch
compose. Its hot reload reaches every module of the backend (ADR 0079): a change under `src/backend/core`,
`src/backend/apps/memories` or `src/backend/apps/depot` is recompiled and restarts the app on the next HTTP request — measured
2026-10-20 through this launcher from a worktree, one source edit per module, each answered by Quarkus' "Restarting
quarkus due to changes in …" naming that module's classes. A verb without a surface selects every surface's — `./gradlew bootstrap`, `./gradlew build`; a bare
`./gradlew run` selects all four `run`s, which run one after another and so block on the first unless `--parallel`
is given (`backend:run web:run --parallel` ran both, measured).

`./gradlew tasks --all --group veyq` lists every task with its description; without `--all`, Gradle folds the
same verb across projects into one row (`run`, `build`, … with one surface's description), so it is no task list.
A task finds `pnpm`, `node` and `docker` in `veyq.toolPath` (`local.properties` or `-P`, a PATH-style list), then the
Gradle daemon's `PATH`, then `/opt/homebrew/bin` and `/usr/local/bin`, and hands the process that list as its `PATH`: a
daemon IntelliJ starts has only macOS' minimal `PATH`, not the shell environment IntelliJ loads (`settings.gradle.kts`
says why). A command found nowhere fails naming the list it searched. IntelliJ's project-interpreter setting, which the
run configurations used, no longer applies.

**A fresh checkout** needs one install before anything else: `web:bootstrap` or `admin:bootstrap` — either installs the
whole workspace (one lockfile, one root `node_modules`) and generates its own client; the other generates the second.

**`infra:*` always runs the main checkout's compose file**, also from a worktree. The containers are named
(`veyq-mariadb`, …) and the file declares its project (`name: infra`), so a worktree's copy is the same project
with other mount sources, and `up` would recreate the running containers onto the worktree's files (measured with
`--dry-run`: `veyq-garage` and `veyq-traefik` *Recreate*). One machine, one dev infra.

**A second instance from a worktree** moves its ports in an untracked `local.properties` at the worktree's root
(or `-P…` per call, which wins): `veyq.webPort`, `veyq.adminPort`, `veyq.sitePort`, `veyq.backendPort`,
`veyq.backendAdminPort`, `veyq.debugPort`. An absent property sets nothing — every surface keeps its own default —
and a given one sets everything derived from it: `veyq.webPort` also moves the store endpoint `backend:run` signs
presigned URLs for (`http://veyq.localhost:<webPort>`), `veyq.backendPort` / `veyq.backendAdminPort` also move
web's and admin-web's proxy targets and `backend:seed`'s bases. Ports do not separate the data: both instances run
on the one dev database and bucket, so a branch's schema meets the other checkout's backend (inbox #456).

## Compose profiles

Docker Compose for the local dev dependencies. Three profiles:

- **default** — `mariadb`: the persistent dev database (prod-parity for `quarkusDev`). The test suites use
  in-memory H2 and need none of this.
- **`s3`** (opt-in) — `garage` + `traefik`: the bundled object store for **media blobs**
  ([ADR 0013](../docs/decisions/0013-blob-storage-presigned-direct-to-store.md), cr-0028 / cr-0029).
- **`mail`** — `mailpit`: a throwaway test mailbox for the per-tenant SMTP send path
  ([ADR 0048](../docs/decisions/0048-provider-neutral-smtp-mail-transport.md)). Opt-in for a hand dev loop, but
  **`scripts/run_web_e2e.py` always brings it up**: a member invite travels by mail only
  ([ADR 0055](../docs/decisions/0055-mail-is-a-precondition-not-a-capability.md) §2), so every web e2e suite
  reads this mailbox.

**Every port is published on `127.0.0.1` only.** The stack's credentials are throwaways that are public with the
repository, so a port on every interface (Compose's default) would let anyone on the same network into the dev
database and store with them. Nothing in the dev loop needs another interface: the backend, Vite (which proxies
`/blobs/`) and the e2e orchestrators reach the ports on the host, and the containers that need the store or the
database join the compose network. A stack started before 2026-10-26 keeps its old bindings until its containers are
recreated — `docker compose -f infra/docker/docker-compose.yml --profile s3 --profile mail up -d` recreates the four
changed services and keeps their volumes; `scripts/run_web_e2e.py`'s `up --no-recreate` never does.

```sh
# database only (the usual dev loop)
docker compose -f infra/docker/docker-compose.yml up -d
docker compose -f infra/docker/docker-compose.yml down        # stop, keep data
docker compose -f infra/docker/docker-compose.yml down -v      # stop AND wipe (fresh instance)

# + the bundled object store (media transport). This leaves it RUNNING BUT NOT USABLE —
# the first backend start sets up layout + bucket + dev key + CORS itself (ADR 0067)
docker compose -f infra/docker/docker-compose.yml --profile s3 up -d

# + the test mailbox (per-tenant SMTP smoke) — SMTP on :11025, web UI/API on :18025
docker compose -f infra/docker/docker-compose.yml --profile mail up -d
```

## Object store (media, `--profile s3`)

The backend is **operator-blind**: it never streams media bytes, it only **presigns** short-TTL URLs the
client PUT/GETs directly against the store ([ADR 0013](../docs/decisions/0013-blob-storage-presigned-direct-to-store.md)),
via the **vendor-neutral AWS S3 SDK** (works against any S3-compatible store). The bundled store is **Garage**
(S3-compatible, AGPL, built for self-hosting — it replaced the archived MinIO, cr-0029).

**`--profile s3 up` starts the store; it does not make it usable.** A fresh Garage has no layout, no bucket, no
access key and no grant, and since [ADR 0067](../docs/decisions/0067-store-provisioning-from-a-declared-type.md)
the **backend** closes that gap on its first start, from the **bundled-store hint** (`veyq.bundled-store.*`: the
endpoint, the admin endpoint and the admin token; [ADR 0089](../docs/decisions/0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md)).
It assigns a single-node layout, creates the bucket `veyq.bundled-store.bucket` names (`blobs` by default), imports the key
pair **derived from the admin token** (so the e2e, a dev backend and the backup tool agree on one pair without any being
told it), grants it, and writes the bucket CORS policy. **On a fresh instance the backend then sets the bundled store itself**
and runs its check, so the first upload works without a step of yours (ADR 0089 decision 3); a store saved in admin-web's
Server → Object store first is never replaced. Every step is idempotent on
its own, so a later start repairs a missing piece rather than skipping the lot, and a `down -v` wipe self-heals on
the next start.

There is no init sidecar any more, in dev or in production, and that is deliberate rather than a simplification:
its image was built locally and published nowhere, so a Helm chart had nothing to pull
([ADR 0064](../docs/decisions/0064-kubernetes-helm-deployment-target.md)) — and keeping a *dev-only* one would
have meant the store was always already provisioned by the time a dev backend started, so the **fresh** path
would never run outside a unit test.

*"I brought the store up and media still fails"* has one answer: read `GET /api/instance/store-provisioning` as
the Server Owner (the admin console's **Object store** rubric), which reports `PROVISIONED`, `IN_PROGRESS`,
`FAILED`, or `NOT_ATTEMPTED` for a store you brought yourself. Provisioning never gates the boot, so the app
starts and looks healthy either way.

- **S3 API** → `http://localhost:19000`. The dev key pair is derived from the dev admin token (`BundledStoreCredentials`),
  bucket `blobs`, region `us-east-1` — local throwaway only.
- **Admin API** → `http://localhost:19003`, token `veyq-dev-admin-token` — how the backend provisions the
  store, published local-only in dev and **not published at all** in production (the app reaches it on the
  compose network as `http://garage:3903`).
- **Backend e2e** (`BlobBatchE2ETest`): bring the profile up **and start a backend once** so the bucket exists,
  then
  `VEYQ_S3_E2E=1 ./gradlew :apps:memories:test --tests '*BlobBatchE2ETest'` from `src/backend/` (the test lives in the
  Memories module; defaults match the compose; override via
  `VEYQ_S3_ENDPOINT`/`VEYQ_S3_ADMIN_TOKEN`/`VEYQ_S3_BUCKET`/`VEYQ_S3_REGION`).
- **Backend against the store** (`quarkusDev`): `application-dev.properties` already carries the hint for this Garage, so
  there is nothing to point it at; only `VEYQ_BUNDLED_STORE_PUBLIC_ENDPOINT` is yours (the origin you browse — the run
  configuration sets it). An instance with no store chosen **starts** and refuses uploads with a reason; the in-memory stub
  exists only in `%test`.

### Same-origin via Traefik (web, no CORS)

A browser can only PUT/GET a presigned URL if the store is reachable from the web origin **and** allows CORS —
unless the store is **same-origin**. `traefik` path-routes the bucket so it is, from the static
[`traefik-dev/blobs.yml`](docker/traefik-dev/blobs.yml) — its only provider, as in production, so it holds no Docker
socket. It forwards `/<bucket>/…` **without stripping the prefix** and **preserving the Host header** (SigV4 signs host+path+query, so a
stripPrefix or host rewrite would break the signature). Entry is `http://localhost:18080`; sign presigned URLs
for that public endpoint via `veyq.bundled-store.public-endpoint=http://localhost:18080` (CORS-free, ADR 0013 §4).
(The backend + web are added behind Traefik in a fuller deployment; this profile routes only the bucket path.)

## Mail (test mailbox, `--profile mail`)

A tenant can configure its **own SMTP** so member invites are emailed from its own domain
([ADR 0048](../docs/decisions/0048-provider-neutral-smtp-mail-transport.md)); with none configured a **member**
invite is refused outright (`503 mail_unavailable`) rather than falling back to a copy-link — mail is the only
channel it has ([ADR 0055](../docs/decisions/0055-mail-is-a-precondition-not-a-capability.md) §2). The one invite
that still falls back is the ServerOwner's tenant-**bootstrap** invite, which by construction predates any mail
settings (§3). **Mailpit** is a throwaway mailbox to smoke that send path locally — MIT-licensed
and actively maintained (**not** the archived MailHog; root `CLAUDE.md` forbids EOL deps). It accepts any SMTP
AUTH over plain (dev only) and exposes an HTTP API, so a harness can read what arrived without scraping a UI.

- **SMTP** → `localhost:11025`; **web UI + HTTP API** → `http://localhost:18025` (nothing persists across
  restarts — a test mailbox, by design).
- **Smoke the send path**: bring the profile up, then (as a tenant owner) `PUT /api/tenants/{id}/mail-settings`
  with `host=mailpit.localhost`, `port=11025`, `enabled=true`, `tls=false`. The next
  **member** invite is emailed; read it at `http://localhost:18025` or fetch it from the API
  (`GET http://localhost:18025/api/v1/messages`). **The host must be a dotted name** — `TenantMailSettingsUpdate.host`
  is pinned to `^([a-z0-9](-?[a-z0-9])*\.)+[a-z]{2,}$`, so a bare `localhost` is a `400`, while any `*.localhost`
  resolves to loopback and passes. Leave `username` unset unless you want to exercise SMTP AUTH: it is what keys
  the sealed-password path, so an auth-less send needs neither a stored password nor an openable KEK. `pnpm run
  bootstrap:user` writes exactly these settings for the dev tenant, so a hand-seeded instance can invite members
  out of the box. Sealing the SMTP password needs the **config-secret KEK**
  ([ADR 0050](../docs/decisions/0050-operator-config-secret-encryption.md)): the server **generates
  and manages** this key as a `0600` file on first start (default `config-secret.key` in the process CWD — a local
  `quarkusDev` drops one there, gitignored), so a fresh instance can seal secrets with **no** operator setup. To
  supply your own key (e.g. a mounted K8s/Docker secret), point `VEYQ_SECURITY_CONFIG_SECRET_FILE` at it. **In a
  container the key file MUST live on a persisted volume** — otherwise every recreate loses it, and the server
  then reports the stored SMTP password as undecryptable (`configSecretState: MISMATCH`, the fingerprint tripwire
  firing loudly) and **refuses member invites and password resets** (`503 mail_unavailable`) until the key is
  restored or the password re-entered. That is a hard stop, not a degraded mode — which is why the key belongs on
  a persisted volume rather than wherever the container happened to start.
- **The backend's own coverage needs no container**: it is hermetic (an in-JVM GreenMail server in
  `SmtpInviteMailSendTest`), so `./gradlew check` never touches this profile. The **web e2e** harness is the
  opposite — `scripts/run_web_e2e.py` starts this profile itself and waits on it, because every suite it runs
  onboards a member out of this mailbox.

## Cutting a release

For the maintainer; an operator starts at the [Run area](https://veyq.app/run/) of the docs site. The
release model itself — the version, snapshot and release, the assets, both targets, what is built and what is only
decided — is [`docs/crosscutting/release-and-deployment.md`](../docs/crosscutting/release-and-deployment.md); this
section keeps the commands.

**Every green push publishes a snapshot** ([ADR 0091](../docs/decisions/0091-one-pipeline-per-commit-a-release-promotes-a-tested-snapshot.md)
decision 5). A push to `main` or `release/<major>.x` runs `.github/workflows/ci.yml` — the one pipeline per commit,
every stage listed once in the stage map of
[How veyq is tested](../site/src/content/docs/security/how-veyq-is-tested.md#where-and-when-each-stage-runs) — and when
every stage is green, its `snapshot` job publishes the three images on exactly the digests lane L1 installed, as
`<major>.<minor>-SNAPSHOT` and `sha-<commit>`, and the chart as `<major>.<minor>-SNAPSHOT`, signs them, attests each
lane's result per architecture, and `snapshot-tag` sets the git tag `snapshot-<major>.<minor>` on the commit. A snapshot
is **written once and never replaced**: a second build of a commit that has one is refused. A pull request
(Dependabot's included) and a local run (`scripts/run_pipeline.py`) build and test the same and publish nothing. A
commit whose run was superseded while it waited, or a docs-only tip (which `ci.yml` does not run on), gets its snapshot
by hand:

```sh
gh workflow run ci.yml --ref main                    # the tip's snapshot, exactly as a push makes it
```

- **A snapshot is signed by `ci.yml`, a release by `release.yml`**, both on `main` or `release/<major>.x`. The two are
  the same digests, so the identity is what tells them apart: Verify a release and `veyqctl` accept only the release
  identity. To check a snapshot you try on a throwaway instance, verify it under the snapshot identity:

  ```sh
  cosign verify ghcr.io/raddatzk/veyq@sha256:<digest> \
    --certificate-oidc-issuer https://token.actions.githubusercontent.com \
    --certificate-identity-regexp '^https://github\.com/raddatzk/veyq/\.github/workflows/ci\.yml@refs/heads/(main|release/[0-9]+\.x)$'
  cosign verify-attestation ghcr.io/raddatzk/veyq@sha256:<digest> --type https://veyq.app/attestations/stack-lane/v1 \
    --certificate-oidc-issuer https://token.actions.githubusercontent.com \
    --certificate-identity-regexp '^https://github\.com/raddatzk/veyq/\.github/workflows/ci\.yml@refs/heads/(main|release/[0-9]+\.x)$'
  ```

- **The image carries `<major>.<minor>`**, the release its commit can become, never `-SNAPSHOT`: a snapshot instance
  reports that number, and only the release signature says it is a release.
- **The version is the tree's.** `VERSION` holds the major alone; the minor counts the commits since `VERSION` last
  changed, so `VERSION` is touched only to change the major — any commit touching it restarts the minor at `0`.
  Major `0` is pre-release: it exercises the whole path and promises no upgrade path.
- **The packages must be pullable.** The first publish creates `veyq`, `veyq-admin`, `veyq-tools` and `charts/veyq` under the
  owner's GHCR packages. Make all four public, or every operator needs registry credentials — and the chart has no
  `imagePullSecrets` value, so public is the state it supports.
- **Bumping a pin is part of cutting a release** (ADR 0057 §6), Helm's included: `HELM_VERSION` and `HELM_SHA256`
  at the top of `ci.yml`, checked against the published `.sha256sum` and the GitHub release notes.

**A release promotes a snapshot** (ADR 0091 decision 5, `.github/workflows/release.yml`). Nothing is built: the
images every lane tested get the release's names and a second signature, from `release.yml` — the identity Verify a
release and `veyqctl` accept. Once, before the first release: switch on **immutable releases** in the repository's
settings (ADR 0096 decision 3). Then, per release:

1. **GitHub → Releases → Draft a new release.** *Choose a tag*: the snapshot's, `snapshot-<major>.<minor>` — the list
   offers exactly the snapshots that are still promotable. The title is overwritten (`veyq <major>.<minor>`); write the
   **notes** in your words — whatever an operator must act on goes here. **Save draft** (never *Publish*). Leave no
   other draft lying around: the promotion releases exactly one draft and refuses when there are several.
2. **GitHub → Actions → Release → Run workflow**, on the branch the snapshot came from: `main`, or `release/<major>.x`
   for a fix of a previous major. Tick `unscheduled_major` only for an unscheduled major (ADR 0085 decision 4).
3. **Watch the run.** `check` decides — every refusal names what it saw, and the `refused` job writes it into the
   draft's notes under *Promotion refused*, so the draft page says why. `promote` gives the images `<major>.<minor>`,
   signs them, packages and signs the chart, produces and signs the files. `publish` verifies the files as `veyqctl`
   will, sets the git tag `v<major>.<minor>`, moves the draft onto it with every file and publishes it **last**, then
   removes `snapshot-<major>.<minor>`. `channels` moves `<major>` and, from `main`, `latest`.

```sh
gh workflow run release.yml --ref main                  # step 2 from a terminal, after the draft is saved
gh run watch                                            # step 3
```

**A refused or failed promotion publishes nothing.** The draft stays a draft, back on its snapshot tag, with the
reason in its notes; a `v<major>.<minor>` tag the run had set is removed. Fix the cause (or delete the draft and save
one on another snapshot), delete the *Promotion refused* lines if you like, and run the workflow again: names the run
had already given in the registry (`<major>.<minor>` on the tested digests, the chart, signatures) are this snapshot's,
and the next run accepts and completes them. A `<major>.<minor>` tag that names *another* digest is refused for good —
a release name is never replaced. One case needs your hand: should `promote` report that a release name it just set is
not the tested digest (Buildx copying an index differently from its documentation), delete that `<major>.<minor>` tag
of that package in GHCR before running again.

**Which version is V1** — `0.<n>` or `1.0` — is the owner's call, and the workflow promotes either. `1.0` is not a
snapshot you can pick until a `0.<n>` release exists: the `1.0` commit sets the `release:` line (below), and from then
on the upgrade proof and the chart upgrade check prove from the newest `v` tag the commit's parent reaches — with no
`v` tag they refuse, and that commit's pipeline is red.

- **The first `1.x` release is one commit, then its promotion:** `VERSION` set to `1` **and** the `release:`
  line of `scripts/applied_migrations.sha256` set to the highest migration that release ships, together — the same
  commit deletes `upgrade-proof`'s `if: false` and the `"skipped"` the `snapshot` job tolerates for it. From then
  on a released migration is never edited ([src/backend/CLAUDE.md](../src/backend/CLAUDE.md) § Migrations).
**The 1 May train** ([ADR 0085](../docs/decisions/0085-one-major-moved-on-a-yearly-train.md) decisions 2, 3 and 10)
leaves only when a removal is pending. A year without one has no major, and an unscheduled major (decision 4) follows
the same steps on its own date:

1. **The clients that speak the new major are published first,** so the apps in the stores speak it before any server
   does. Until a native client exists this step is empty: the web client and admin-web ship in the server's image.
2. **The old major's last release, on the commit before the bump:** promote that commit's snapshot from `main`.
3. **The release branch, cut from that release's tag.** Its own history counts on from it, so its first fix is
   `<old>.<n+1>`. The train ends every branch cut before it, so those get no more releases.
4. **The bump:** one commit setting `VERSION` to the new major, then the promotion of its snapshot. Whatever an
   operator must act on goes into that release's notes.

```sh
git fetch --tags && git branch release/<old>.x v<old>.<n> && git push origin release/<old>.x  # step 3
```

**A fix for the previous major** is one decision 4 keeps inside a major. It never carries a migration. It lands on
`main` first, then on the branch, whose push publishes the fix's snapshot, and the branch is released **before** the
current major's next release, because that release is the one the new major is entered from after it:

```sh
git switch release/<old>.x && git cherry-pick -x <fix-commit-on-main> && git push   # snapshot-<old>.<m>
# then promote snapshot-<old>.<m> from release/<old>.x (<old> moves, latest does not), then the current major's release
```

The current major's next release then moves the major entry by itself: every release of major N is proved — the
upgrade proof and the chart upgrade check — from the newest release of major N−1 among every tag, the branch's
included, beside its default predecessor, its tools image bakes that release as the one its gate admits a new
major from, and its manifest names it as `majorEntry`, the one `veyqctl` enters the new major from
(`run_web_e2e.py --upgrade-major-entry`, `chart_upgrade_check.py --upgrade-major-entry`,
`release_assets.py --major-entry`, `release_assets.produce`: one derivation, ADR 0085 § Consequences). A snapshot
bakes the entry when it is built, so a release of major N promotes a snapshot built **after** that fix release. Until
that release exists, an instance on the fix release cannot enter the new major. Not built is lane L3's archive holding
that release (ADR 0096, inbox #461).

**What a run on GitHub has shown, and what it still must,** is inbox #471: the first snapshot of a push, its
signatures, attestations and tag, and later the first promotion. Until each has run, the statements above are about a
file, held by `scripts/validate_release_identity.py`.

## Expenses' receipts moved to core (once per dev instance)

Expenses' receipts are blobs of core's blob service since ADR 0112, and their old table was **discarded, not migrated**
(decision 5: no instance is live). The migration that drops it, `V202611012216__expenses_receipts_move_to_core.sql`,
**deletes no row and refuses to run while `veyq_expenses_receipt` holds one**: a dev instance with a receipt from before
the move does not start, and its log names `ck_expenses_receipt_reset_first`. Reset it first, one of two ways, on the
code from before the move (the commit before the move is the last that runs on such a database):

- **Every Pot gone**: delete each Pot (`deleteNode`) and purge it from its space's trash (`purgeNode`) — today's erasure
  of its revisions, its receipts, their blob rows (their store objects on the reclaim list), its links and guest
  identities and the measures on them. Then check `SELECT COUNT(*) FROM veyq_expenses_receipt` is `0`.
- **The whole dev instance**: `./gradlew infra:wipe infra:up` (or `docker compose -f infra/docker/docker-compose.yml down -v`
  and `up -d` with the store's profile), which drops the database and the store volume, then seed again
  (§ Seeding the local dev principal).

Then start the new code. A start that already refused left a failed run in Flyway's history: a dev start clears it
(`application-dev.properties`: `quarkus.flyway.repair-at-start=true`) and runs the migration again, which now drops the
empty table. Never delete the rows in SQL instead — that would leave their blob rows with no arm and their objects in
the store, found only by the bucket reconcile (ADR 0112 decision 5). `ReceiptsResetMigrationSliceTest` holds the refusal.

## Memories' media moved to core (once per dev instance)

A medium's original and derivatives are blobs of core's blob service since ADR 0113, and the media from before the move
are **discarded, not migrated** (decision 9: no instance is live). The migration that drops `veyq_media.blob_id` and
`veyq_media_derivative`, `V202611020620__memories_media_move_to_core.sql`, **deletes no row and refuses to run while a
medium, a derivative or a hold's copy of either is left**: such a dev instance does not start, and its log names
`ck_media_reset_first`, `ck_media_derivative_reset_first` or `ck_hold_row_media_reset_first`. Reset it first, on the
code from before the move (the commit before the move is the last that runs on such a database), one of two ways:

- **Every medium gone**: in each Memory, delete every medium and empty it from the Memory's trash (`deleteMedia`, then
  `purgeMedia`), or delete each Memory and purge it from its space's trash (`deleteNode`, `purgeNode`) — today's erasure
  of a medium, its blob rows (their store objects on the reclaim list) and the measures on it. Then check
  `SELECT COUNT(*) FROM veyq_media` and `SELECT COUNT(*) FROM veyq_media_derivative` both answer `0`.
- **The whole dev instance**: `./gradlew infra:wipe infra:up`, then seed again (§ Seeding the local dev principal). The
  only way when `SELECT COUNT(*) FROM veyq_hold_row WHERE source_table IN ('veyq_media', 'veyq_media_derivative')` is not
  `0`: a hold keeps its copy, and after the move no code reads a medium's blob from that shape.

Then start the new code; a start that refused clears its failed run at the next dev start, as above. Never delete the
rows in SQL instead — that would leave their blob rows with no arm and their objects in the store.
`MediaResetMigrationSliceTest` holds the refusal.

## Running in production

The operator's procedures — verifying a release, setting up a new instance on Docker Compose or Kubernetes,
upgrading, and backup and restore — are the **Run** area of the documentation site
([ADR 0087](../docs/decisions/0087-published-docs-are-a-starlight-site-docs-is-for-development.md)), published at
<https://veyq.app/run/> and written in [`site/src/content/docs/run/`](../site/src/content/docs/run/).
This file keeps the development stack and how a release is cut.

## Seeding the local dev principal

`pnpm run bootstrap:user` (`./gradlew backend:seed`) births one user who is Server Owner **and** owner of a
`*.localhost` tenant with a personal scope, over the real API, and writes its key material back into `.run/.env`
([`src/web/CLAUDE.md`](../src/web/CLAUDE.md) § Prove your work owns the inputs and the cold start):

```sh
./gradlew infra:wipe infra:up
# start the backend (./gradlew backend:run), then:
./gradlew backend:seed
```

It talks to two bases: `VEYQ_BASE` (the public port, default `http://localhost:8080`) for everything a member
does, and `VEYQ_ADMIN_BASE` (the admin listener, default `http://127.0.0.1:8090`) for setup and tenant creation —
which the public port 404s.

It reads the owner invite's secret from `createTenant`'s own response, so it needs no mailbox for the
owner; the second user's invite comes from Mailpit. The tenant's domain is a
`*.localhost` name, never an IP literal: the contract's hostname pattern refuses one, and no seeder writes to the
database to get around it.
