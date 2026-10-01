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
the **backend** closes that gap on its first start, from the declared store type
(`veyq.blob-store.type=garage` plus the admin endpoint and token). It assigns a single-node layout, creates the
bucket `veyq.blob-store.bucket` names (`blobs` by default), imports the **deterministic dev credentials** (so the
e2e and a dev backend use fixed keys), grants them, and writes the bucket CORS policy. Every step is idempotent on
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

- **S3 API** → `http://localhost:19000`. Dev key `GK0123456789abcdef01234567` / secret
  `0123…` (64 hex, see `application-dev.properties`), bucket `blobs`, region `us-east-1` — local throwaway only.
- **Admin API** → `http://localhost:19003`, token `veyq-dev-admin-token` — how the backend provisions the
  store, published local-only in dev and **not published at all** in production (the app reaches it on the
  compose network as `http://garage:3903`).
- **Backend e2e** (`BlobBatchE2ETest`): bring the profile up **and start a backend once** so the bucket exists,
  then
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
unless the store is **same-origin**. `traefik` path-routes the bucket so it is, from the static
[`traefik-dev/blobs.yml`](traefik-dev/blobs.yml) — its only provider, as in production, so it holds no Docker
socket. It forwards `/<bucket>/…` **without stripping the prefix** and **preserving the Host header** (SigV4 signs host+path+query, so a
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

## Running in production

`infra/docker-compose.prod.yml` is the deployment artifact: the app, its administration surface, MariaDB,
Garage, and a Traefik that terminates TLS in front of them. It is **standalone**, not an override of the dev
file — that file has no app service at all, publishes `3306`/`19000`/`18080`, and ships `change-me` passwords.

```sh
# first: § Verifying a release, below — it leaves the recipe in ./recipe-<version>/ and prints two image lines
cp ./recipe-<version>/docker-compose.prod.yml ./recipe-<version>/.env.example infra/
cp infra/.env.example infra/.env          # then fill in every value (it is gitignored), and the two image lines
docker compose -f infra/docker-compose.prod.yml up -d
```

**Every secret is required and has no default.** A missing variable fails the `up` with the name of the variable,
rather than starting an instance on a password that is printed in this repository.

**Every container runs unprivileged, and the proxy cannot reach the database**
([ADR 0071](../docs/decisions/0071-runtime-hardening-on-both-targets.md)). Each service drops every capability,
cannot escalate, runs as a non-root user and has a read-only root. A one-shot `volume-ownership` service chowns the
Garage and ACME volumes to their writers first, so `ps` lists it as exited (0) after every `up`, and that is its
done state. Three networks separate the peers: `veyq_edge` (Traefik, app, Garage), `veyq_db` (app, MariaDB, no
route out) and `veyq_admin` (app, admin container, and Traefik for the `ADMIN_DOMAIN` router). A command that joins
the stack names one of them.

### Verifying a release — before anything runs it

A release is two images, `ghcr.io/raddatzk/veyq` and `ghcr.io/raddatzk/veyq-admin`, and the publish signs both
keylessly with cosign ([ADR 0057](../docs/decisions/0057-release-identity-and-deployment-recipe.md) §3). This is
the one procedure for a first install, an upgrade and a Helm install alike. It ends with the two image references
you deploy, **by digest**: a tag can move between the moment you verify it and the moment you pull it, and a
digest cannot.

```sh
version=<version>        # the <major>.<minor> you are about to run, e.g. 1.0

# 1. resolve each tag to its multi-arch index digest ONCE — everything below uses the digest, never the tag
app_digest="$(docker buildx imagetools inspect "ghcr.io/raddatzk/veyq:${version}" --format '{{.Manifest.Digest}}')"
admin_digest="$(docker buildx imagetools inspect "ghcr.io/raddatzk/veyq-admin:${version}" --format '{{.Manifest.Digest}}')"

# 2. verify both signatures against the one identity that publishes veyq
cosign verify "ghcr.io/raddatzk/veyq@${app_digest}" \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  --certificate-identity-regexp '^https://github\.com/raddatzk/veyq/\.github/workflows/container\.yml@refs/heads/(main|release/[0-9]+\.x)$'
cosign verify "ghcr.io/raddatzk/veyq-admin@${admin_digest}" \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  --certificate-identity-regexp '^https://github\.com/raddatzk/veyq/\.github/workflows/container\.yml@refs/heads/(main|release/[0-9]+\.x)$'

# 3. pull both by digest, and extract the recipe from the verified app image
docker pull "ghcr.io/raddatzk/veyq-admin@${admin_digest}"
docker create --name veyq-recipe "ghcr.io/raddatzk/veyq@${app_digest}" >/dev/null
docker cp veyq-recipe:/opt/veyq/deploy/. "./recipe-${version}/"
docker rm veyq-recipe >/dev/null

# 4. the two lines infra/.env pins (under Helm: image.tag/digest and admin.image.tag/digest, § Running on Kubernetes)
echo "VEYQ_IMAGE=ghcr.io/raddatzk/veyq:${version}@${app_digest}"
echo "VEYQ_ADMIN_IMAGE=ghcr.io/raddatzk/veyq-admin:${version}@${admin_digest}"
```

**If either `cosign verify` fails, stop.** Nothing has run yet, and nothing should.

**What the identity accepts, and why it is this narrow.** A signature made by `container.yml` in
`raddatzk/veyq`, on `main` or a `release/<major>.x` branch, under GitHub's own OIDC issuer — which are exactly the
branches the publish runs from, so what signs and what verifies agree. It rejects every other workflow in the
repository, every other branch, pull-request and tag refs, every other owner, and every repository whose name
only starts with `veyq`: a looser pattern accepts a signature the veyq release never made, and a check that
accepts anything a fork signs checks nothing. The upstream is written out rather than left as a placeholder for
the same reason. If you deploy your own fork's images, verify them against **your** fork's identity, knowingly.
`scripts/validate_release_identity.py` holds this command to what the workflow signs.

In the two image lines, Docker pulls by the digest and ignores the tag; the tag is there so a person can still
read which release a line names.

**Nothing has been published yet**, so this procedure has never run against a veyq release. It has been run as
written against another image that is keyless-signed from a GitHub workflow on `main`, with that image's own
identity; the first publish is where it is first run for real.

### Setting up a brand-new instance

**You create the first account on the administration surface, not on the app's domain.** That is the whole
point of it being a separate container ([ADR 0065](../docs/decisions/0065-admin-ui-cli-separate-service.md)):
the `admin` service reverse-proxies the backend from **its own** origin, so first-run setup works over plain
`http://localhost` with no DNS record, no certificate, and `VEYQ_DOMAIN` pointing at a name that does not
resolve yet. Before this, setup lived in the app SPA served at `VEYQ_DOMAIN` — which quietly made a working
public hostname a precondition for creating the first account.

**And the Server-Owner operations answer nowhere else.** The backend runs a second, internal **admin listener**
(`8090`, `veyq.admin-channel.port`); setup, tenant administration and the instance reads answer only for a request
that arrived there from a non-public peer, and the public port 404s them
([uc.admin.admin-channel](../docs/features/admin/use-cases/uc.admin.admin-channel.md)). So until you have created
the owner, nobody who finds `VEYQ_DOMAIN` in a certificate log can claim the instance first. The compose file
points the admin container at that listener and routes nothing else to it; the image does not `EXPOSE` it. **If
you put your own reverse proxy in front of the app, route the public port `8080` only** — nothing but the admin
container may ever be pointed at `8090`.

So the order is: bring the stack up, create the owner over the tunnel, and *then* worry about DNS and TLS.

```sh
docker compose -f infra/docker-compose.prod.yml up -d
ssh -L 8081:127.0.0.1:8081 you@the-host        # from your laptop; 8081 = ADMIN_PORT
# then open http://localhost:8081 and create the Server Owner account
```

**Save the Emergency Kit on that screen.** It carries the Secret Key, it is shown once, and there is no
operator-side recovery — the server holds no key material by construction.

**Two exposure modes, one image, chosen by one variable.**

- **`ADMIN_DOMAIN` unset — the default, and the one to keep.** No Traefik router exists for the admin
  container at all; it is published on `127.0.0.1:${ADMIN_PORT}` of the host only, so nothing on the internet
  can reach the highest-privilege surface this instance serves. Reach it with the SSH tunnel above, or
  `docker compose exec`.
- **`ADMIN_DOMAIN` set** — it gets its own hostname, router and certificate. The app renders that router into
  the routing file below, from configuration and never from the tenant table: the admin surface is 1:1 with the
  instance ([ADR 0065](../docs/decisions/0065-admin-ui-cli-separate-service.md)). Point the name's DNS
  at this host **first**, or the ACME order fails against the rate limit. Choose a name you publish nowhere:
  the app never links to this surface, and an unlisted name is one more layer between a stranger and it.
  **Set it only after the Server Owner exists.** This mode puts the admin container — and with it the admin
  listener — on the internet, so before setup it reopens exactly the first-caller window the listener closes on
  the public origin (uc.admin.admin-channel A5). Nothing enforces the order; it is yours.

The user-facing app never links to the admin surface and carries no hint that it exists. The backend is handed
`ADMIN_DOMAIN` only to render its router, and no client ever reads it back.

**The same container also holds `veyq-admin`, a CLI for the same operations** ([ADR
0065](../docs/decisions/0065-admin-ui-cli-separate-service.md)) — tenant create/list/resend/reassign/
change-domain/revoke/suspend/reactivate/quota, plus `release`. It is an HTTP client of the same Admin API the UI
calls, so it needs no route and no exposed port at all:

```sh
read -rs VEYQ_ADMIN_PASSWORD && export VEYQ_ADMIN_PASSWORD
docker compose exec \
  -e VEYQ_ADMIN_EMAIL=you@example.com -e VEYQ_ADMIN_PASSWORD \
  admin veyq-admin tenant list
```

Pass the credential **on the invocation**, as above, rather than putting it in `infra/.env`: it is the Server
Owner's own password, and the CLI signs in fresh on every call and writes nothing to disk, so there is no
reason for it to sit in a file between calls. Pass it **by name**: a bare `-e VEYQ_ADMIN_PASSWORD` copies the
value from your shell into the container, whereas `-e VEYQ_ADMIN_PASSWORD="$VEYQ_ADMIN_PASSWORD"` expands it
into `docker compose`'s own argv, where `ps` shows it to every user on the host for as long as the call runs —
the rule `scripts/instance_backup.py` follows for its credentials too. Each call therefore pays one Argon2id derivation (~1–3s,
measured) before it does anything — the accepted price of never holding a session that outlives the process.
First-run **setup** is deliberately not a command: it mints key material and shows the Emergency Kit once, so
it stays on the screen above, where a person can actually save the kit.

**TLS is a precondition, not a preference.** `veyq.session.cookie.secure=true` is the shipped default, so over
plain HTTP the refresh cookie is set and never sent back and "remember me" breaks silently; and every invitation
link is built as `https://<domain>/tenant/accept#<token>`, so an HTTP-only instance mails links pointing at a
scheme it does not answer. Traefik obtains a Let's Encrypt certificate over the HTTP-01 challenge, which needs
port 80 reachable from the internet and `VEYQ_DOMAIN` resolving to this host. While you are still testing a
deployment, point `VEYQ_ACME_CASERVER` at Let's Encrypt's staging directory. In production 5 failed validations
lock a hostname out for an hour, and the weekly limit is on **issued** certificates — 50 per registered domain,
which a burst of test tenants reaches ([rate limits](https://letsencrypt.org/docs/rate-limits/)). Staging
certificates are not browser-trusted, so switch back before anyone uses the instance.

**HSTS is on by default, and one switch turns it off — on both targets.** The app sends
`Strict-Transport-Security: max-age=15552000` (180 days, no `includeSubDomains`, no `preload`) itself, alongside
its CSP and the other security headers ([ADR 0069](../docs/decisions/0069-backend-serves-the-web-app-shell.md)),
and the admin container's nginx sends the same value on the admin origin
([cc.security.csp § admin-web](../docs/crosscutting/cc.security.csp.md#admin-web)), so neither Traefik nor an
Ingress carries anything for it. The switch is `VEYQ_HSTS_ENABLED=false` in `infra/.env` under compose and
`hsts.enabled: false` in the Helm values; it belongs to the deployment rather than to one container, so both
origins answer to the same value. The admin container accepts exactly `true` or `false` and refuses to start on
anything else, naming the variable. Turn it off for one of
two reasons only: a hostname of this instance is **also** served over plain HTTP somewhere, or a proxy of your
own in front of it already sets the header. Know the cost of having had it on: a browser that saw the header
keeps it for the whole 180 days, switched off or not.

**The config-secret volume is the point of this file.** `veyq-config-secret` is mounted at `/var/lib/veyq` and
`VEYQ_SECURITY_CONFIG_SECRET_FILE` points into it. Without it, every `up -d --force-recreate app` regenerates the
KEK, the instance reports `configSecretState: MISMATCH`, and member invites and password resets refuse with
`503 mail_unavailable` — an instance that boots, serves every page, and cannot admit anybody.
`scripts/validate_config_secret_persistence.py` holds the three files that have to agree about that path
(`Dockerfile`, `VeyqConfig.kt`, this compose file) to each other on every push.

**A domain routes because the app writes the routing, not because you edit this file.**
`veyq-traefik-dynamic` is mounted at `/etc/traefik/dynamic` — read-write on `app`, read-only on `traefik`, which
file-watches it — and holds exactly one generated file, which is **all** of this stack's routing: the router
pair for `VEYQ_DOMAIN`, one per non-suspended tenant, the admin router when `ADMIN_DOMAIN` is set, and the
services and store middleware they reference. Traefik runs no other provider, so no container mounts the Docker
socket — a `:ro` mount of it restricts nothing about the Docker API, and the proxy faces the internet. The one
cost: if the app cannot write the file, `VEYQ_DOMAIN` is unrouted too, and it logs that loudly; the admin
surface's localhost port bypasses Traefik, so you keep a way in. **Never hand-edit it.** It is not configuration you maintain, it is a projection of
the `tenant` table ([ADR 0063](../docs/decisions/0063-database-driven-tenant-ingress.md)): the app rewrites the
whole file on every tenant create, domain change, suspend, reactivate and at startup, so an edit survives until
the next one of those and no longer. Nothing else is in there — no secret, no content — and it is deliberately
**not** part of a backup: wiping the volume costs nothing, because the next start regenerates it. Adding a
tenant does mean a new Let's Encrypt certificate order per domain, which is the one place where the ACME
rate-limit advice above stops being about your own testing.

Worth knowing rather than discovering:

- **Upgrading is a procedure, not a flag change.** It has its own section — § *An upgrade, step by step* — and
  the reason it is not one line is that the schema migrates at boot with no gate and no downgrade path.
- **There are two images per release, and they are pinned together.** `VEYQ_IMAGE` and `VEYQ_ADMIN_IMAGE` are
  built, versioned and cosign-signed by the same publish ([ADR 0057](../docs/decisions/0057-release-identity-and-deployment-recipe.md)),
  so pin the same `<major>.<minor>` on both, each by the digest § *Verifying a release* resolved. A mismatched pair is two versions of one instance, and only one of
  them migrated the schema.
- **The compose file and the env template ride inside the image.** Every published image carries the recipe it
  was built with at `/opt/veyq/deploy/`, so the file you deploy a version with cannot drift from the bits it
  describes, and it inherits the image's cosign signature instead of needing a second trust decision — which
  holds only for a recipe read out of the digest you verified, so § *Verifying a release* extracts it from
  exactly that digest, never from a tag.

  (Note the current limit: the recipe is those two files, so the store's `garage.toml` still comes from this
  repository — the Helm chart carries its own copy as a `ConfigMap`, so the file has a second, self-sufficient
  home there, but the compose recipe still reads it from here. It used to be two files — ADR 0067 deleted
  `garage-init.sh` along with the image that ran it.)

## Running on Kubernetes

`charts/veyq` is the second deployment target ([ADR 0064](../docs/decisions/0064-kubernetes-helm-deployment-target.md)):
the same five services as the production compose file — the app, its administration surface, MariaDB, Garage
— on an operator's own cluster, with the ingress controller as the operator's choice. It deploys published
images and builds nothing; it **generates nothing** either, so every secret is yours to supply and an install
that leaves one unset is refused by name (`values.schema.json`). One release per namespace: the app projects
tenant domains as `Ingress` objects into its own namespace and lists them by label, so two releases there
would reconcile each other's routing away.

```sh
cp charts/veyq/values.yaml my-values.yaml         # fill in: domain, storeDomain, both image repositories,
                                                  # every key under secrets, the ingress.tls block, and
                                                  # networkPolicy.ingressFrom — your ingress controller's pods:
#   networkPolicy:
#     ingressFrom:
#       - namespaceSelector: {matchLabels: {kubernetes.io/metadata.name: ingress-nginx}}
#         podSelector: {matchLabels: {app.kubernetes.io/name: ingress-nginx}}
# § Verifying a release, then pin what it verified: image.tag/digest and admin.image.tag/digest
helm install veyq ./charts/veyq -n veyq --create-namespace -f my-values.yaml
```

**Reachability is default-deny, and you name the ingress controller** ([ADR
0071](../docs/decisions/0071-runtime-hardening-on-both-targets.md)). The chart renders a NetworkPolicy set that
admits only the paths the stack uses. The controller's namespace and labels are yours, so the chart never guesses
them: an install without `networkPolicy.ingressFrom` fails and names the key. **A CNI that does not enforce
NetworkPolicy renders the set and enforces none of it.** On such a cluster, or if you write your own policies, set
`networkPolicy.enabled: false`, which removes every policy the chart renders, including the one on the admin
listener. `app.trustedProxies` stays unset unless your proxy has a stable address, such as a host-network
controller or an external load balancer. A pod-IP range wide enough to survive a controller restart trusts every
pod beside it.

Generate the store's secrets the same way as for compose: `openssl rand -hex 32` for `garageRpcSecret`
(exactly 64 hex characters), and an access key of the shape `GK` + 24 hex for `s3AccessKey`. An operator who
keeps key material out of values files names an existing `Secret` in `secrets.existingSecret` instead.

**Setting up a brand-new instance works the same way it does under compose — before DNS and TLS exist.** The
administration surface gets a `ClusterIP` `Service` and **no** `Ingress` by default; `kubectl port-forward` is
this target's `127.0.0.1:${ADMIN_PORT}`:

```sh
kubectl -n veyq port-forward svc/veyq-admin 8081:8080
# then open http://localhost:8081 and create the Server Owner account
```

Save the Emergency Kit on that screen. Set `admin.domain` to expose the surface on a hostname of its own
afterwards — never before the owner exists, for the reason given under compose — or never.

**The admin listener has a `Service` of its own, `veyq-app-admin`, and a `NetworkPolicy` keeps every other pod off
it.** It is deliberately not a second port on `veyq-app`: that Service is what the chart's `Ingress`, every
projected tenant `Ingress` and any `Ingress` of yours aim at, so a port on it would sit behind all of them. The
`veyq-app` policy admits the public port from `networkPolicy.ingressFrom` and the admin port from the admin pod
alone. **A CNI that
does not enforce NetworkPolicy leaves the admin port reachable from any pod in the cluster** — the Service
separation still keeps every Ingress off it, but in-cluster reachability is then only as narrow as your cluster
([ADR 0065](../docs/decisions/0065-admin-ui-cli-separate-service.md) residual (ii)). Every pod passes the `restricted` PodSecurity standard — the admin image runs
unprivileged for exactly this — so the namespace can carry that label from the start. Every container also has a
read-only root filesystem.

**Three hosts, two of them mandatory.** `domain` is the instance (the compose file's `VEYQ_DOMAIN`);
`storeDomain` is the object store on **its own host** — not a path under `domain`, because an operator-chosen
ingress controller is not guaranteed to preserve the prefix and `Host` header a SigV4 signature needs, so
presigned URLs are signed against the store's own origin (`store-origin` signing,
[ADR 0067](../docs/decisions/0067-store-provisioning-from-a-declared-type.md) Decision 5) under the `*` CORS
policy the app writes on first start ([ADR 0013 §4](../docs/decisions/0013-blob-storage-presigned-direct-to-store.md)
measures why `*` is the only policy the bundled store can express for many tenants). `admin.domain` is the
optional third.

**TLS is a passthrough, and it is a precondition here as much as under compose.** The app only ever stamps a
reference: `ingress.annotations` verbatim on every `Ingress` — the chart's own three, and every tenant `Ingress`
the app writes at runtime — and `spec.tls` by a named mode, `ingress.tls.mode`. The issuer that reference names is
yours, or — opted in — the one the chart templates (below):

- `none` (the default, so that nothing moves): no `spec.tls` anywhere. Right where TLS terminates upstream of
  the ingress controller. Wrong everywhere else — the refresh cookie is `Secure` and every mailed link is
  `https`, so an instance nothing terminates TLS for boots, serves every page, and silently breaks sessions.
- `per-domain`: one `spec.tls` per `Ingress` whose `secretName` is the object's own name, which is
  cert-manager's ingress-shim shape. Every tenant domain — a custom domain included — gets its own certificate
  with no chart edit and no restart, from `ingress.tls.acme`'s issuer or, bringing your own, from the one
  `cert-manager.io/cluster-issuer: <issuer>` in the annotations names. Test a deployment against a **staging**
  directory: each new tenant is a certificate order, and what Let's Encrypt production limits is not what it
  sounds like — 5 **failed** validations per hostname lock that hostname out for an **hour**, while the week is a
  cap of 50 **issued** certificates per registered domain ([rate limits](https://letsencrypt.org/docs/rate-limits/)).
  Residue worth knowing: when a tenant is suspended and its `Ingress`
  withdrawn, cert-manager does **not** delete the `Secret` it issued unless it runs with
  `--enable-certificate-owner-ref`. A withdrawn domain therefore leaves an orphaned TLS `Secret` in the
  namespace — visible to you, not a routing leak (the `Ingress` is gone) and not an access leak (a suspended
  tenant is locked out by the app's scope-access gate, not by routing). The chart does not sweep them, because
  sweeping `Secret`s would need the RBAC this design exists to avoid.
- `shared`: every `Ingress` points at the one `Secret` you name in `ingress.tls.secretName` — a wildcard
  certificate you obtained yourself. A working instance with no ACME at all, at the price of per-tenant custom
  domains: a custom domain routes and the browser refuses it. The app refuses `shared` without a secret name
  — loud, and with tenant routing disabled — rather than projecting routes without TLS.

**The ACME opt-in: the chart's counterpart of `VEYQ_ACME_EMAIL`.** Set `ingress.tls.acme.enabled: true`, an
`email` and `mode: per-domain`, and the chart templates one namespaced cert-manager `Issuer`, `veyq-acme` (ACME
HTTP-01, its solver on `ingress.className`), and adds `cert-manager.io/issuer: veyq-acme` to the annotation map —
so it reaches every tenant `Ingress` the app projects later, exactly as a resolver reference does under compose.

```yaml
ingress:
  className: nginx
  tls:
    mode: per-domain
    acme:
      enabled: true
      email: ops@example.org
      # server: https://acme-staging-v02.api.letsencrypt.org/directory   # while testing; default is production
```

- **Preconditions the chart cannot meet for you.** cert-manager runs in the cluster — the chart does not install
  it (its CRDs and `ClusterRole` are cluster-wide), and without it `helm install` fails whole, before creating
  anything. Port 80 of every host reaches the ingress controller, since HTTP-01 answers there. A tenant's DNS
  points here **before** the tenant is created: the creation is the order, and cert-manager checks reachability
  itself before it asks the CA to validate, so an order for a name that does not resolve yet waits there.
- **`server` defaults to Let's Encrypt production**, as `VEYQ_ACME_CASERVER` does; any ACME CA is one URL. A
  staging certificate is not browser-trusted — an instance left on staging boots and every browser refuses it.
- **Refused, not resolved:** `values.schema.json` rejects the opt-in with any mode but `per-domain`, with no
  e-mail, or beside an issuer annotation of your own — two issuers on one `Ingress` would be cert-manager's
  choice, not yours.
- **Lifecycle.** `helm uninstall` removes the `Issuer`. The `Certificate` cert-manager creates for an `Ingress`
  is owned by that `Ingress`, so it goes when a tenant's domain is withdrawn; the issued `Secret` stays (the
  residue above). The ACME account key (`veyq-acme-account-key`) is cert-manager's `Secret`, not the chart's,
  and may remain after an uninstall.

**What the app's cluster identity can and cannot do.** A namespace-local `ServiceAccount` + `Role` with the
`Ingress` verbs, never a `ClusterRole`: the app manages routing in its own namespace and nowhere else. The
namespace is read from the mounted token, so the same image serves every install; do not set
`veyq.tenant.ingress.kubernetes.namespace` in values, it is the outside-a-cluster override.

**The config-secret key lives on a `PersistentVolumeClaim`, and the claim is kept.** Same loss, same place as
under compose: without the key the instance boots and then refuses invites and resets. The claim
(`veyq-config-secret`) is annotated `helm.sh/resource-policy: keep`, so `helm uninstall` leaves it behind, and
the pod sets `fsGroup` to the image's runtime user because a claim comes up root-owned and — unlike a Docker
named volume — inherits nothing from the image. `scripts/chart_meta_check.py` holds that to the `Dockerfile`
(and the rest of the chart's structure), outside the buildless guard glob because it renders the chart with
`helm`.

### Upgrading with `helm upgrade`

The same procedure as § *An upgrade, step by step*, with the same reason it is a procedure: the schema
migrates at boot with no gate and no downgrade path, so the backup is the way back. Two things are specific
to this target.

- **One app pod, `strategy: Recreate`, and that is not a tuning knob.** Kubernetes' default `RollingUpdate`
  starts the new pod before the old one stops, which would run two migrators against one schema during every
  upgrade; `Recreate` is what makes "one migrator at a time" true, at the cost of a short outage per upgrade.
  The day the update mechanism is decided (inbox #121) this is the constraint to revisit.
- **Kubernetes-native backup is not built** ([ADR 0064](../docs/decisions/0064-kubernetes-helm-deployment-target.md)
  defers it, [ADR 0056](../docs/decisions/0056-instance-durable-state-and-cold-logical-backup.md)'s gap
  statement stays accurate). Until it is, § *Backup & restore*'s capture runs against this cluster through
  `kubectl port-forward` to MariaDB and Garage and `kubectl cp` of the key file, with the app **scaled to
  zero** for the duration (`kubectl -n veyq scale deploy/veyq-app --replicas=0`) — *cold* means the writer is
  stopped, and that is the precondition you own. The key is copied out before the scale-down, because only the
  app pod mounts its claim, and the capture then puts it on a medium of its own, never in the capture directory
  (§ Capture).

```sh
# 0. leg (c) out of the pod first, while it still mounts the claim, to a file only you can read
(umask 077; kubectl -n veyq cp "$(kubectl -n veyq get pod -l app.kubernetes.io/component=app \
  -o jsonpath='{.items[0].metadata.name}')":/var/lib/veyq/config-secret.key ./veyq-config-secret.key)
kubectl -n veyq scale deploy/veyq-app --replicas=0      # 1. stop the application, and only it
# 2. capture and verify — § Backup & restore, through port-forwards, with
#    --config-secret-file ./veyq-config-secret.key --key-into /media/usb-stick/veyq-<stamp>.key;
#    then delete ./veyq-config-secret.key — the key now lives on its own medium, never in the capture
helm upgrade veyq ./charts/veyq -n veyq -f my-values.yaml \
  --set image.tag=<new> --set image.digest="${app_digest}" \
  --set admin.image.tag=<new> --set admin.image.digest="${admin_digest}"   # from § Verifying a release
# 3. the schema migrates during this boot; then verify as the compose runbook says — not by "it started"
```

Pin the same tag on both images, each with the digest the verification resolved for it: they ship as one
release, and only one of a mismatched pair migrated the schema. With a digest set the pod runs
`repository:tag@digest`, and the node pulls by the digest. Rollback is the backup plus the previous tags and
digests, exactly as under compose.

#### Restore under Helm

The mirror of the capture above: the tool reaches MariaDB and Garage through port-forwards and cannot reach the
app's claim, so it writes leg (c) to a local file of your own, and `kubectl cp` puts it in the pod. `kubectl cp`
unpacks as the container's user, uid 10001, so the key is born the app's — the property the compose restore gets
from running as root. Onto a **freshly installed** release, exactly as § Restore requires:

```sh
kubectl -n veyq scale deploy/veyq-app --replicas=0      # 1. stop the application
# 2. port-forward MariaDB and Garage as for the capture; then § Restore's command, with
#    --config-secret-file ./veyq-config-secret.key --key-from /media/usb-stick/veyq-<stamp>.key
#    (a file you own, so the tool writes it 0600 as you)
kubectl -n veyq scale deploy/veyq-app --replicas=1      # 3. the pod mounts the claim again
kubectl -n veyq rollout status deploy/veyq-app
kubectl -n veyq cp ./veyq-config-secret.key "$(kubectl -n veyq get pod -l app.kubernetes.io/component=app \
  -o jsonpath='{.items[0].metadata.name}')":/var/lib/veyq/config-secret.key   # 4. leg (c) in
rm ./veyq-config-secret.key
kubectl -n veyq rollout restart deploy/veyq-app         # 5. NOT optional — see below
# 6. verify as § Restore says: configSecretState: OK, then a person opens a Memory with media
```

The restart is part of the procedure: anything that touched the seal between step 3 and step 4 holds the fresh
install's key in memory, and the instance would report `MISMATCH` until it restarts. `kubectl cp` needs `tar` in
the container — the app image (`runtime-jvm`, the Dockerfile's only runtime target) has it (measured
2026-10-01). **Derived, not rehearsed**: no cluster has run it.

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

# 3. Capture legs (a) and (b) into infra/backups/<UTC-ISO8601>/ (gitignored), and leg (c) somewhere ELSE.
python3 scripts/instance_backup.py capture \
    --docker-network infra_default \
    --config-secret-file backend/config-secret.key \
    --key-into /media/usb-stick/veyq-<stamp>.key

# 4. CHECK both — the store leg is captured logically precisely so this is possible at all.
python3 scripts/instance_backup.py verify infra/backups/<stamp> --key-from /media/usb-stick/veyq-<stamp>.key

# 5. Start the application again. The downtime was the capture, and nothing else.
```

**Leg (c) never goes beside the dump.** The dump plus the key opens every tenant's SMTP password, so a capture
directory holding both is exactly the leaked backup the key exists to defend against (ADR 0056 §1). `capture`
therefore refuses a `--key-into` inside the capture directory, refuses to run with a key found and no destination
for it, and never overwrites an existing file there. `--key-beside-dump` puts it in the capture anyway — knowingly,
and the manifest records that it did. Everything the tool writes is owner-only (`0700` directories, `0600` files),
and `verify` checks it.

**Before a capture leaves this host, encrypt it, and keep the key on another medium.** The tool encrypts nothing:
the dump holds the members' emails and the password wraps an attacker can grind offline even without the key.
Pack the capture directory and encrypt the archive for a recipient whose private key is not on this host — for
example `tar -C infra/backups -c <stamp> | age -r <recipient> > veyq-<stamp>.tar.age` — and carry the
`--key-into` file on a different medium from that archive.

`--docker-network` is the network the pinned `rclone` container joins, and it must be one the store is on. For the
**dev** stack above it is `infra_default`: that file names no networks and declares no project, so Compose derives
`<project>_default` from its directory. The production file declares `name: veyq` and separate networks, and the
store is on `veyq_edge` (§ Running in production). List them with `docker network ls`. An operator with their own `rclone` install can pass `--rclone-binary rclone` instead, and
one whose database is not this compose service passes `--db-host`/`--db-port` (the client then runs from `PATH`
if installed, else in the pinned `mariadb:11.4` image on `--docker-network`).

No credential ever reaches `argv`: the store remote is defined entirely through `RCLONE_CONFIG_*` and the
database password through `MYSQL_PWD`, both handed to the child as bare `-e NAME`. `rclone` logs
`NOTICE: Config file … not found - using defaults` on every call — that is normal, not an error, and no config
file is written.

**What `verify` tells you, and its one deliberate asymmetry.** An **object with no row** is an ordinary steady
state, and since ADR 0062 for a better reason: the row delete and the reclaim entry share one transaction, and a
single sweep drains that list, so a capture taken between the two legitimately holds objects whose rows are gone
— a reclaim *pending*, not a deletion that failed unnoticed. Orphans are therefore still counted and tolerated,
but a stuck one is now visible on the instance itself
(`SELECT count(*) FROM veyq_store_reclaim WHERE abandoned_at IS NOT NULL`). (Abandoned upload sessions
themselves are reclaimed — `V11__media.sql:9`'s "a later slice" note is a historical artifact of that
migration.) A **row with no object** is fatal (A4): it
restores as a Memory whose picture yields a valid presigned URL and a `404`, with no server-side error anywhere
— so `verify` names every missing object and exits non-zero. It exits `2`, with a different headline, when the
backup is structurally intact but carries no leg (c). A key kept apart is checked only when `--key-from` names it,
and `verify` says so when it did not.

### Restore — onto a freshly installed instance

A restore targets the **same domain** (ADR 0056 §4); `--domain` is required and the tool refuses anything the
backup did not hold. It also refuses a target that already holds **data**, or a bucket that already holds
objects — the restore is defined onto an empty instance and nothing reconciles two populated ones (A5).

*Empty* means **holding no data**, not holding nothing. The target is a **normally installed instance**: bring
the stack up and let the app start once, which migrates the schema and provisions the store. There is no
shorter path and there is no manual `CREATE DATABASE` step — since ADR 0067 the app is the only thing that can
make the store usable, and the same start migrates the schema, so the two go together by construction.

```sh
# 1. A freshly installed instance: start the stack and let the app run once, then STOP the app.
#    That one start migrates the schema AND provisions the store (layout, bucket, key, grant, CORS).
docker compose -f infra/docker-compose.yml --profile s3 up -d
#    …start the application, wait for GET /api/instance/store-provisioning to report PROVISIONED, stop it.

# 2. Load all three legs — leg (c) from wherever you kept it.
python3 scripts/instance_backup.py restore infra/backups/<stamp> \
    --domain veyq.localhost \
    --docker-network infra_default \
    --config-secret-file backend/config-secret.key \
    --key-from /media/usb-stick/veyq-<stamp>.key

# 3. Start the application, then VERIFY — this is the step that separates a restore from a hope.
```

The restore takes the key from where the capture's manifest says it went. A capture that kept it apart is
**refused** without `--key-from`, before anything is written: restoring without leg (c) is A2 — an instance that
boots and refuses invites and password resets — and it happens only when you pass `--without-key` on purpose.

**The key must end up readable by the application, which runs as uid 10001** (the image's `USER`). The tool gives
the key the owner of the directory it lands in, and refuses — before anything is written — a run that cannot: a
key born `root:root 0600` in the app's volume is one the app cannot read, so it reports `ABSENT` and refuses
invites and resets with the key right there. On the **production recipe** leg (c) lives on the
`veyq-config-secret` volume, which Docker created owned by `10001`, so the restore runs as root, addressed the way
the capture addresses it (§ An upgrade, step by step, step 2), with the same `veyq_edge` network:

```sh
sudo -E python3 scripts/instance_backup.py restore infra/backups/<stamp> \
    --domain <your domain> \
    --compose-file infra/docker-compose.prod.yml \
    --docker-network veyq_edge \
    --config-secret-file "$(docker volume inspect -f '{{.Mountpoint}}' veyq_veyq-config-secret)/config-secret.key" \
    --key-from /media/usb-stick/veyq-<stamp>.key
sudo stat -c '%u:%g %a' "$(docker volume inspect -f '{{.Mountpoint}}' veyq_veyq-config-secret)/config-secret.key"
#    → 10001:10001 600
```

`-E` keeps the `VEYQ_BACKUP_DB_PASSWORD` / `VEYQ_BLOB_STORE_*` variables the tool reads (a `sudoers` that resets
the environment needs them passed explicitly). Run without root against that path, the tool refuses and says
why. **This production command is derived, not rehearsed** — no production stack has been restored onto yet;
the tool's owner rule is held by `scripts/instance_backup_selftest.py`, without Docker or root.

The store's **configuration** is not restored because it was never state: bucket, access key and single-node
layout are recreated idempotently by the app itself, on every start. This has no "fresh node only" limit — the
layout step is satisfied by a node already carrying a role at any version, and applies `current + 1` where it
has work to do.

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

### An upgrade, step by step

The schema migrates at boot with no operator gate and the migration set carries no downgrade path, so the backup
is not a precaution taken *around* this procedure — it **is** the way back ([ADR 0056](../docs/decisions/0056-instance-durable-state-and-cold-logical-backup.md) §6).
Everything else below exists to keep that backup usable: to name the release you are leaving, and to notice a
recipe change before it becomes a failed `up`.

**Which of these steps has ever been executed — because the answer is "one".** Step 2 is rehearsed on every
`scripts/run_web_e2e.py` run: it captures a live instance, destroys it, restores it and runs a suite against the
result, and it holds the production capture to resolving the production project instead of whatever happens to
run out of `infra/`. **No image has ever been published**, so no other step here has ever run — not the stop,
not the pull, not the recipe diff, not the rollback. This is a procedure derived from the decisions that govern
it, not from a rehearsal, and you are the one finding out where it is wrong. Read it that way.

1. **Stop the application — and only the application.** *Cold* means the **writer** is stopped; MariaDB and
   Garage stay up, because the capture reads through their own interfaces (ADR 0056 §2).

   ```sh
   docker compose -f infra/docker-compose.prod.yml stop app
   ```

   **`scripts/instance_backup.py` does not check this, and does not pretend to** — it cannot know what "the
   app" is on your host. It is the one precondition you own, and the entire consistency argument rests on it:
   with the app down the database gains no rows, so a row whose object was never captured is impossible and
   there is no ordering to get right.

2. **Capture, then check the capture.** § Backup & restore owns the commands and the credentials; two arguments
   differ here from the dev examples there. The network is `veyq_edge`, the one the store is on: the production
   compose file declares `name: veyq`, so that is the project, and nothing has to be passed on the command line to
   make it so. And
   leg (c) lives on a **volume**, not on your filesystem, so it has to be addressed there — and it is copied to a
   destination of its own, never into the capture:

   ```sh
   sudo -E python3 scripts/instance_backup.py capture \
       --compose-file infra/docker-compose.prod.yml \
       --docker-network veyq_edge \
       --config-secret-file "$(docker volume inspect -f '{{.Mountpoint}}' veyq_veyq-config-secret)/config-secret.key" \
       --key-into /media/usb-stick/veyq-<stamp>.key
   sudo python3 scripts/instance_backup.py verify infra/backups/<stamp> --key-from /media/usb-stick/veyq-<stamp>.key
   ```

   **Both run as root.** The key is the application's — uid `10001`, mode `0600`, on a volume under Docker's data
   root — so no other user can read it, and a capture without root cannot take leg (c). `-E` keeps the credential
   variables, as in § Restore. What a root capture writes is root's, owner-only: the capture directory and the
   key file. So `verify` runs as root too, and so does the encryption in § Capture — or hand both to yourself
   first: `sudo chown -R "$(id -u):$(id -g)" infra/backups/<stamp> /media/usb-stick/veyq-<stamp>.key`.
   **Derived, not rehearsed** — the e2e rehearsal captures a key file it owns, not the production volume.

   Do not skip `verify`. A capture that silently missed objects and one that is complete look identical until
   the day you need it, which is the day you find out. Encrypt it before it leaves this host (§ Capture).

3. **Confirm the backup can name the release you are leaving.** Read `app.version` in the capture's
   `manifest.json`. A release is `<major>.<minor>`, and a publish onto an existing tag **fails** rather than
   replacing it ([ADR 0057](../docs/decisions/0057-release-identity-and-deployment-recipe.md)), so that tag
   names one fixed set of bits for good — which is what makes it a rollback target rather than a label. If the
   manifest records no release, the backup is still valid and still restorable; what it cannot tell you is which
   image to start it under, so write that down yourself now, from **Settings → Server → Release**, while the
   instance you are leaving still exists to be asked. And keep a copy of `infra/.env`'s `VEYQ_IMAGE` and
   `VEYQ_ADMIN_IMAGE` lines: they name, by the digest you verified when you installed them, the pair you roll
   back to.

4. **Verify the new release and pull both of its images**, so the download is not part of the outage: run
   § *Verifying a release* with `version=<new-version>`. It pulls the app and the admin image by digest, extracts
   the new recipe into `./recipe-<new-version>/` and prints the two image lines step 6 pins. If either
   `cosign verify` fails, stop here — nothing has changed yet.

5. **Diff the new image's recipe against the one you are running.** The image's copy is authoritative for the
   version it belongs to (ADR 0057), and a version that adds a service or a required variable has **no other
   delivery path to you** — the first sign of one you missed is a `up` that dies on a missing variable.

   ```sh
   diff -u infra/docker-compose.prod.yml ./recipe-<new-version>/docker-compose.prod.yml
   diff -u infra/.env.example            ./recipe-<new-version>/.env.example
   ```

6. **Apply what the diff showed, then bring the new version up.** Take the new recipe as yours
   (`cp ./recipe-<new-version>/docker-compose.prod.yml ./recipe-<new-version>/.env.example infra/`), carry any
   new variable into `infra/.env`, replace its `VEYQ_IMAGE` and `VEYQ_ADMIN_IMAGE` lines with the two step 4
   printed — both, never one — and:

   ```sh
   docker compose -f infra/docker-compose.prod.yml up -d
   ```

   The schema migrates during this boot. There is no gate and no prompt.

7. **Verify, and not by "it started".** Read the instance's config-secret state as the Server Owner
   (`GET /api/tenants/{id}/mail-settings` → `configSecretState: OK`), then have a person sign in and **open a
   Memory that contains media**. That is the one assertion touching all three legs at once — the row, the
   object and the key are three different failures wearing the same "it looked fine".

**Rollback is the backup plus the tag, and both are nameable only because a release is immutable.** Stop the
app, restore the capture from step 2 onto an empty instance (§ Restore), and pin `VEYQ_IMAGE` and
`VEYQ_ADMIN_IMAGE` back to the two lines step 3 kept. There is no other way back: the migrations carry no down path, so an instance that has booted
on the new schema cannot be handed to the old image.

## Seeding a reproducible dev instance (cr-0046)

Bring up a known `{Server Owner + 127.0.0.1 tenant owner + personal scope}` in one command and persist it
(credentials **and** raw key material) to a gitignored `web/scripts/.seed/dev-seed.json` a harness can load:

```sh
docker compose -f infra/docker-compose.yml down -v && docker compose -f infra/docker-compose.yml up -d
# start the backend writing the dev-invite file (so the seeder reads the bootstrap secret deterministically):
VEYQ_TENANT_INVITE_DEV_FILE=$PWD/backend/build/dev-invites.jsonl ./gradlew -p backend quarkusDev
cd web && npm run seed:dev
```

The seeders talk to two bases: `VEYQ_BASE` (the public port, default `http://localhost:8080`) for everything a
member does, and `VEYQ_ADMIN_BASE` (the admin listener, default `http://127.0.0.1:8090`) for setup and tenant
creation — which the public port 404s. `npm run bootstrap:user` reads the same two.

`veyq.tenant.invite.dev-file` (dev/test only, never written in production) is the second sink of the invite
line the logging mailer already emits — no console-log scraping. `seed:dev` creates the tenant under a hostname
(`POST /api/tenants` rejects a numeric domain) and remaps the domain to `127.0.0.1` in the dev DB so the web
dev server (which presents `Host: 127.0.0.1`) resolves it. Load it from a harness via `web/scripts/devSeed.mts`
(`loadDevSeed()` / `vaultFromSeed()`).
