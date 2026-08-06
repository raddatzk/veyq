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
