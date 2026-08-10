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

# + the bundled object store (media transport) — the garage-init sidecar sets up
# layout + bucket + dev key automatically (idempotent; also after a `down -v` wipe)
docker compose -f infra/docker-compose.yml --profile s3 up -d

# + the test mailbox (per-tenant SMTP smoke) — SMTP on :11025, web UI/API on :18025
docker compose -f infra/docker-compose.yml --profile mail up -d
```

## Object store (media, `--profile s3`)

The backend is **operator-blind**: it never streams media bytes, it only **presigns** short-TTL URLs the
client PUT/GETs directly against the store ([ADR 0013](../docs/decisions/0013-blob-storage-presigned-direct-to-store.md)),
via the **vendor-neutral AWS S3 SDK** (works against any S3-compatible store). The bundled store is **Garage**
(S3-compatible, AGPL, built for self-hosting — it replaced the archived MinIO, cr-0029). On `--profile s3 up`
the **`garage-init`** sidecar assigns a single-node layout, creates the `veyq` bucket, and imports
**deterministic dev credentials** (so the e2e + a dev backend use fixed keys) — automatically, and idempotent
(a no-op once the bucket exists, so it self-heals after a `down -v`). Garage's own image is shell-less, so the
sidecar is a tiny Alpine + Garage-CLI image (`garage-init.Dockerfile`) that drives the node over RPC. The same
script still runs by hand from the host (`./infra/garage-init.sh`, via `docker exec`) if you ever need it.

- **S3 API** → `http://localhost:19000`. Dev key `GK0123456789abcdef01234567` / secret
  `0123…` (64 hex, see `garage-init.sh`), bucket `veyq`, region `us-east-1` — local throwaway only.
- **Backend e2e** (`BlobBatchE2ETest`): bring the profile up (the sidecar inits it), then
  `VEYQ_S3_E2E=1 ./gradlew :backend:test --tests '*BlobBatchE2ETest'` (defaults match the compose; override via
  `VEYQ_S3_ENDPOINT`/`VEYQ_S3_ACCESS_KEY`/`VEYQ_S3_SECRET_KEY`/`VEYQ_S3_BUCKET`/`VEYQ_S3_REGION`).
- **Backend against the store** (`quarkusDev`): point it at the store via env —
  `veyq.blob-store.endpoint=http://localhost:19000`, `…access-key=GK0123456789abcdef01234567`,
  `…secret-key=0123…`. A blank `endpoint` **aborts startup** outside the hermetic `%test` profile
  (`BlobStoreStartupCheck`, ADR 0013 §1 / cr-0105) — media is not an optional feature, so a store-less instance
  is misconfigured, not gracefully degraded. The in-memory stub exists only in `%test`, and there is no
  client-facing `directTransport` flag left to soft-gate on.

### Same-origin via Traefik (web, no CORS)

A browser can only PUT/GET a presigned URL if the store is reachable from the web origin **and** allows CORS —
unless the store is **same-origin**. `traefik` path-routes the bucket so it is: it forwards `/<bucket>/…`
**without stripping the prefix** and **preserving the Host header** (SigV4 signs host+path+query, so a
stripPrefix or host rewrite would break the signature). Entry is `http://localhost:18080`; sign presigned URLs
for that public endpoint via `veyq.blob-store.public-endpoint=http://localhost:18080` (CORS-free, ADR 0013 §4).
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
  with `host=localhost`, `port=11025`, any `username`/`password`, `enabled=true`, `tls=false`. The next
  **member** invite is emailed; read it at `http://localhost:18025` or fetch it from the API
  (`GET http://localhost:18025/api/v1/messages`). Sealing the SMTP password needs the **config-secret KEK**
  ([ADR 0050](../docs/decisions/0050-operator-config-secret-encryption.md) + amendment): the server **generates
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

## Running in production

`infra/docker-compose.prod.yml` is the deployment artifact: the app, MariaDB, Garage, and a Traefik that
terminates TLS in front of both. It is **standalone**, not an override of the dev file — that file has no app
service at all, publishes `3306`/`19000`/`18080`, and ships `change-me` passwords.

```sh
cp infra/.env.example infra/.env          # then fill in every value (it is gitignored)
docker compose -p veyq -f infra/docker-compose.prod.yml up -d
```

**Always name the project (`-p veyq`).** Compose otherwise derives it from the compose file's *directory*, which
both files share — so an unqualified `down -v` meant for the dev stack would destroy the production volumes. With
the project named, `docker volume ls` shows `infra_*` and `veyq_*` side by side and the two never touch.

**Every secret is required and has no default.** A missing variable fails the `up` with the name of the variable,
rather than starting an instance on a password that is printed in this repository.

**TLS is a precondition, not a preference.** `veyq.session.cookie.secure=true` is the shipped default, so over
plain HTTP the refresh cookie is set and never sent back and "remember me" breaks silently; and every invitation
link is built as `https://<domain>/tenant/accept#<token>`, so an HTTP-only instance mails links pointing at a
scheme it does not answer. Traefik obtains a Let's Encrypt certificate over the HTTP-01 challenge, which needs
port 80 reachable from the internet and `VEYQ_DOMAIN` resolving to this host. While you are still testing a
deployment, point `VEYQ_ACME_CASERVER` at Let's Encrypt's staging directory — a handful of failed production
orders per hostname per hour is all it takes to be locked out for a week.

**The config-secret volume is the point of this file.** `veyq-config-secret` is mounted at `/var/lib/veyq` and
`VEYQ_SECURITY_CONFIG_SECRET_FILE` points into it. Without it, every `up -d --force-recreate app` regenerates the
KEK, the instance reports `configSecretState: MISMATCH`, and member invites and password resets refuse with
`503 mail_unavailable` — an instance that boots, serves every page, and cannot admit anybody.
`scripts/validate_config_secret_persistence.py` holds the three files that have to agree about that path
(`Dockerfile`, `VeyqConfig.kt`, this compose file) to each other on every push.

Two things worth knowing rather than discovering:

- **The proxy is scoped to this stack** (`--providers.docker.constraints=Label(veyq.stack,prod)`). Traefik's
  docker provider is not project-aware: unconstrained, it discovers *every* container on the host — which both
  exposes whatever else the machine runs and, with two veyq stacks side by side, collides on the router name and
  makes Traefik drop the router for both.
- **Upgrading** means changing `VEYQ_IMAGE` and running `up -d`. The schema migrates at boot with no gate and
  there is no downgrade path, so **take a backup first** (§ Backup & restore) — restoring it is the only way back.

## Backup & restore

The use-case owns the goal and the failure modes
([uc.admin.back-up-and-restore-instance](../docs/features/admin/use-cases/uc.admin.back-up-and-restore-instance.md));
[ADR 0056](../docs/decisions/0056-instance-durable-state-and-cold-logical-backup.md) owns the decisions. This
section owns the **commands**, and `scripts/instance_backup.py` is what they call — its module docstring carries
the tool's own reference.

**An instance's durable state is exactly three legs:** (a) the database, (b) the object store's *contents*,
(c) the **config-secret key file**. Two legs produce an instance that boots, serves content, and refuses member
invites and password resets with `503 mail_unavailable` — the leg that gets forgotten is the one whose absence
looks like health.

**The capture is cold, and *cold* means the writer is stopped — not the stores.** Stop the application; leave
MariaDB and Garage running, because the capture reads through their own interfaces. That single act is the whole
consistency argument: with the app down the database gains no rows, so a row whose object was never captured is
structurally impossible and there is no ordering to remember. The tool cannot check this for you and does not
pretend to.

### Capture

```sh
# 1. STOP the application (quarkusDev / the app container). Nothing else stops.

# 2. Credentials — the SAME variables the backend already reads, so there is nothing new to configure.
#    The endpoint must be the one the rclone CONTAINER sees (an internal name), not a published host port.
export VEYQ_BACKUP_DB_PASSWORD=change-me-root
export VEYQ_BLOB_STORE_ENDPOINT=http://garage:3900
export VEYQ_BLOB_STORE_ACCESS_KEY=GK0123456789abcdef01234567
export VEYQ_BLOB_STORE_SECRET_KEY=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef

# 3. Capture all three legs into infra/backups/<UTC-ISO8601>/ (gitignored).
python3 scripts/instance_backup.py capture \
    --docker-network infra_default \
    --config-secret-file backend/config-secret.key

# 4. CHECK it — the store leg is captured logically precisely so this is possible at all.
python3 scripts/instance_backup.py verify infra/backups/<stamp>

# 5. Start the application again. The downtime was the capture, and nothing else.
```

`--docker-network` is the network the pinned `rclone` container joins; for the bundled stack it is
`infra_default` (the project name comes from the compose file's **directory**). List them with
`docker network ls`. An operator with their own `rclone` install can pass `--rclone-binary rclone` instead, and
one whose database is not this compose service passes `--db-host`/`--db-port` (the client then runs from `PATH`
if installed, else in the pinned `mariadb:11.4` image on `--docker-network`).

No credential ever reaches `argv`: the store remote is defined entirely through `RCLONE_CONFIG_*` and the
database password through `MYSQL_PWD`, both handed to the child as bare `-e NAME`. `rclone` logs
`NOTICE: Config file … not found - using defaults` on every call — that is normal, not an error, and no config
file is written.

**What `verify` tells you, and its one deliberate asymmetry.** An **object with no row** is an ordinary steady
state: every store deletion is best-effort *after* the commit and the upload-session GC `V11__media.sql:9`
promised was never built, so orphans are counted and tolerated. A **row with no object** is fatal (A4): it
restores as a Memory whose picture yields a valid presigned URL and a `404`, with no server-side error anywhere
— so `verify` names every missing object and exits non-zero. It exits `2`, with a different headline, when the
backup is structurally intact but carries no leg (c).

### Restore — onto an instance that holds nothing

A restore targets the **same domain** (ADR 0056 §4); `--domain` is required and the tool refuses anything the
backup did not hold. It also refuses a target database that already has tables, or a bucket that already has
objects — the restore is defined onto an empty instance and nothing reconciles two populated ones (A5).

```sh
# 1. An EMPTY instance: an empty database, an initialised-but-empty bucket, no application running.
docker compose -f infra/docker-compose.yml --profile s3 up -d      # garage-init recreates layout+bucket+key
docker compose -f infra/docker-compose.yml exec -T mariadb \
    mariadb -uroot -pchange-me-root -e "CREATE DATABASE veyq;"

# 2. Load all three legs.
python3 scripts/instance_backup.py restore infra/backups/<stamp> \
    --domain veyq.localhost \
    --docker-network infra_default \
    --config-secret-file backend/config-secret.key

# 3. Start the application, then VERIFY — this is the step that separates a restore from a hope.
```

The store's **configuration** is not restored because it was never state: bucket, access key and single-node
layout are recreated idempotently by `garage-init.sh`. One limit worth knowing rather than discovering: that
script hardcodes `layout apply --version 1`, so it initialises a **fresh** node — which is exactly a restore
target's shape, and not a partly-configured one.

The final step is not "it started". Read the instance's **config-secret state** (`GET
/api/tenants/{id}/mail-settings` → `configSecretState: OK`), then have a person sign in and **open a Memory that
contains media**. That is the only assertion that touches all three legs at once: the row, the object and the key
are three different failures with the same symptom of *"it looked fine"*.

### The volume-copy fast path — and what it is not

For a **same-host, same-version** rollback you can stop everything and copy the named volumes
(`infra_veyq-mariadb-data`, `infra_veyq-garage-meta`, `infra_veyq-garage-data`) plus the config-secret key file.
It is faster and needs no credentials. It is **not** the procedure the durability bar is measured against: a
volume copy is opaque, so a complete one and a truncated one look identical until you restore them, and it binds
the backup to one store's on-disk format and version.

Garage can snapshot its metadata database consistently while running:

```sh
docker exec veyq-garage /garage meta snapshot
```

There is **no** equivalent for its data blocks (`garage block --help` offers `info | list-errors | purge |
retry-now` and no snapshot), which is the second reason this path is a rollback aid and not the procedure.
`garage.toml` sets `metadata_snapshots_dir` to its own named volume (`infra_veyq-garage-snapshots`) because the
default writes snapshots **inside** `metadata_dir` — i.e. into the very volume the fast path copies, so each
snapshot grows the thing being snapshotted. An instance created before that setting still carries the old
snapshots under `meta/snapshots/`; they are safe to delete once.

### An upgrade begins with a backup

The schema migrates at boot with no operator gate and the migration set carries no downgrade path, so returning
to the previous image means restoring a backup (ADR 0056 §6). Take one **before** you pull a new tag. There is no
upgrade runbook yet; this section is its precondition, not its substitute.

## Seeding a reproducible dev instance (cr-0046)

Bring up a known `{Server Owner + 127.0.0.1 tenant owner + personal scope}` in one command and persist it
(credentials **and** raw key material) to a gitignored `web/scripts/.seed/dev-seed.json` a harness can load:

```sh
docker compose -f infra/docker-compose.yml down -v && docker compose -f infra/docker-compose.yml up -d
# start the backend writing the dev-invite file (so the seeder reads the bootstrap secret deterministically):
VEYQ_TENANT_INVITE_DEV_FILE=$PWD/backend/build/dev-invites.jsonl ./gradlew -p backend quarkusDev
cd web && npm run seed:dev
```

`veyq.tenant.invite.dev-file` (dev/test only, never written in production) is the second sink of the invite
line the logging mailer already emits — no console-log scraping. `seed:dev` creates the tenant under a hostname
(`POST /api/tenants` rejects a numeric domain) and remaps the domain to `127.0.0.1` in the dev DB so the web
dev server (which presents `Host: 127.0.0.1`) resolves it. Load it from a harness via `web/scripts/devSeed.mts`
(`loadDevSeed()` / `vaultFromSeed()`).
