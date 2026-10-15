# infra — local dev stack

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

```sh
# database only (the usual dev loop)
docker compose -f infra/docker-compose.yml up -d
docker compose -f infra/docker-compose.yml down        # stop, keep data
docker compose -f infra/docker-compose.yml down -v      # stop AND wipe (fresh instance)

# + the bundled object store (media transport). This leaves it RUNNING BUT NOT USABLE —
# the first backend start sets up layout + bucket + dev key + CORS itself (ADR 0067)
docker compose -f infra/docker-compose.yml --profile s3 up -d

# + the test mailbox (per-tenant SMTP smoke) — SMTP on :11025, web UI/API on :18025
docker compose -f infra/docker-compose.yml --profile mail up -d
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
told it), grants it, and writes the bucket CORS policy. **A fresh instance must also choose the bundled store** — admin-web's
Server → Store page, `veyq-admin store set --bundled`, or `npm run bootstrap:user` — before an upload works. Every step is idempotent on
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
  `VEYQ_S3_E2E=1 ./gradlew :apps:memories:test --tests '*BlobBatchE2ETest'` from `backend/` (the test lives in the
  Memories module; defaults match the compose; override via
  `VEYQ_S3_ENDPOINT`/`VEYQ_S3_ADMIN_TOKEN`/`VEYQ_S3_BUCKET`/`VEYQ_S3_REGION`).
- **Backend against the store** (`quarkusDev`): `application-dev.properties` already carries the hint for this Garage, so
  there is nothing to point it at; only `VEYQ_BUNDLED_STORE_PUBLIC_ENDPOINT` is yours (the origin you browse — the run
  configuration sets it). An instance with no store chosen **starts** and refuses uploads with a reason; the in-memory stub
  exists only in `%test`.

### Same-origin via Traefik (web, no CORS)

A browser can only PUT/GET a presigned URL if the store is reachable from the web origin **and** allows CORS —
unless the store is **same-origin**. `traefik` path-routes the bucket so it is, from the static
[`traefik-dev/blobs.yml`](traefik-dev/blobs.yml) — its only provider, as in production, so it holds no Docker
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
  the sealed-password path, so an auth-less send needs neither a stored password nor an openable KEK. `npm run
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

For the maintainer; an operator starts at the [Run area](https://raddatzk.github.io/veyq/run/) of the docs site. **Nothing is published by a push**
([ADR 0057](../docs/decisions/0057-release-identity-and-deployment-recipe.md) §1): a push to `main` or
`release/**`, and every pull request, runs `.github/workflows/container.yml` as a **dry run** — the test gate (the
chart's `scripts/chart_meta_check.py` and `scripts/chart_upgrade_check.py` included) and both image builds, with no registry login and no push. A publish
is started by hand, from `main` or `release/<major>.x`, and is one of two kinds:

```sh
gh workflow run container.yml --ref main                    # a snapshot: <major>.<minor>-SNAPSHOT
gh workflow run container.yml --ref main -f release=true    # a release:  <major>.<minor>
```

- **A snapshot** publishes both images and the chart as `<major>.<minor>-SNAPSHOT` (and the images as
  `sha-<commit>`), signed, with no git ref. Dispatching again on the same commit replaces it — it is the one name
  that moves, and it is never a rollback target.
- **A release** publishes `<major>.<minor>`, `<major>`, `sha-<commit>`, `latest` from `main`, and the chart, all
  signed, then creates the git ref `v<major>.<minor>`. A release whose images, chart or ref already exist is
  **refused** before anything is pushed: release a later commit instead.
- **The version is the tree's.** `VERSION` holds the major alone; the minor counts the commits since `VERSION` last
  changed, so `VERSION` is touched only to change the major — any commit touching it restarts the minor at `0`.
  Major `0` is pre-release: it exercises the whole path and promises no upgrade path.
- **The packages must be pullable.** The first publish creates `veyq`, `veyq-admin` and `charts/veyq` under the
  owner's GHCR packages. Make all three public, or every operator needs registry credentials — and the chart has no
  `imagePullSecrets` value, so public is the state it supports.
- **Bumping a pin is part of cutting a release** (ADR 0057 §6), Helm's included: `HELM_VERSION` and `HELM_SHA256`
  at the top of `container.yml`, checked against the published `.sha256sum` and the GitHub release notes.
- **The first `1.x` release is one commit, then a release dispatch:** `VERSION` set to `1` **and** the `release:`
  line of `scripts/applied_migrations.sha256` set to the highest migration that release ships, together. From then
  on a released migration is never edited ([backend/CLAUDE.md](../backend/CLAUDE.md) § Migrations).

**No dispatch has ever run** — this repository has no remote — so all of the above is a statement about a file,
held by `scripts/validate_release_identity.py`, not about a run. The first runs are its proof, in this order: a
push (the dry run is green and nothing lands in GHCR); a snapshot dispatch at `VERSION=0` (both images and the
chart published and signed, no git tag); a release dispatch (`v0.<n>` exists), then a **second** release dispatch
on the same commit, which must be red at the refusal; then the `1.x` commit above and its release dispatch. Until
that refused repeat has run, ADR 0064 says the chart's release is built, its publish unobserved.

## Running in production

The operator's procedures — verifying a release, setting up a new instance on Docker Compose or Kubernetes,
upgrading, and backup and restore — are the **Run** area of the documentation site
([ADR 0087](../docs/decisions/0087-published-docs-are-a-starlight-site-docs-is-for-development.md)), published at
<https://raddatzk.github.io/veyq/run/> and written in [`site/src/content/docs/run/`](../site/src/content/docs/run/).
This file keeps the development stack and how a release is cut.

## Seeding the local dev principal

`npm run bootstrap:user` (IntelliJ: *Bootstrap User*) births one user who is Server Owner **and** owner of a
`*.localhost` tenant with a personal scope, over the real API, and writes its key material back into `.run/.env`
([`web/CLAUDE.md`](../web/CLAUDE.md) § Prove your work owns the inputs and the cold start):

```sh
docker compose -f infra/docker-compose.yml down -v && docker compose -f infra/docker-compose.yml up -d
# start the backend (IntelliJ: Run Backend), then:
cd web && npm run bootstrap:user
```

It talks to two bases: `VEYQ_BASE` (the public port, default `http://localhost:8080`) for everything a member
does, and `VEYQ_ADMIN_BASE` (the admin listener, default `http://127.0.0.1:8090`) for setup and tenant creation —
which the public port 404s.

It reads the owner invite's secret from `createTenant`'s own response, so it needs neither a mailbox nor the
backend's dev-invite file for the owner; the second user's invite comes from Mailpit. The tenant's domain is a
`*.localhost` name, never an IP literal: the contract's hostname pattern refuses one, and no seeder writes to the
database to get around it.
