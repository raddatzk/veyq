---
title: Configuration reference
description: Every setting an operator gives veyq — the Compose variables, the Helm values, the console's settings and the backend keys meant for tuning.
sources:
  - docs/decisions/0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md
  - docs/decisions/0064-kubernetes-helm-deployment-target.md
  - docs/decisions/0094-a-clients-address-is-the-hop-the-network-vouches-for.md
  - docs/features/moderation/enablers/en.moderation.guest-write-record.md
  - docs/decisions/0078-production-reads-committed-like-its-tests.md
  - docs/decisions/0131-a-memorys-background-music-plays-through-the-vendors-sdk-in-a-frame-of-its-own-origin.md
---

veyq splits its configuration in two
([ADR 0089](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md)):

- **The environment** carries only what the process needs before it can reach the database or serve its first
  request: the database connection, the public domain and the proxy posture, the path of the config-secret key file,
  the images and the admin listener. On Compose that is the instance's `.env` (`/opt/veyq/.env`); on Kubernetes, the
  chart's values.
- **Everything else is set in the admin console** and stored in the database. Today that is the object store.

`scripts/validate_run_config_reference.py` fails when a variable of the production compose file or of
`infra/docker/.env.example`, or a key of the chart's `values.yaml`, is missing from this page.

## Docker Compose: `.env`

`veyqctl install` writes `/opt/veyq/.env` (mode `0600`) from the release's template, `env.example` — the tree's
`infra/docker/.env.example` ([Set up a new instance](/run/new-instance/#install)). It generates the four secrets, writes
the two image lines from the release's manifest, and takes every other value from `--set KEY=VALUE` or asks for it; an
optional variable is given the same way. `upgrade` keeps every value, and every key the new template does not name.
By hand, copy the template to `.env` and fill it in: every required variable has no default, and a missing one fails
`docker compose up` with its name. Generate the secrets with `openssl rand -hex 32` (the Garage RPC secret) and
`openssl rand -base64 24` (the others).

| Variable | Required | Default | Meaning |
|---|---|---|---|
| `VEYQ_RELEASE` | No | `latest` | Read by `veyqctl` only: the release line `install` and `upgrade` take. `<major>.<minor>` pins one release, `<major>` takes the newest of that major, `latest` the newest of all; a new major is entered only from the release it was tested from ([Upgrade](/run/upgrade/#which-release-upgrade-takes)) |
| `VEYQ_DOMAIN` | Yes | — | The instance's bootstrap domain: routed from configuration, so the instance is reachable before any tenant exists, and the bundled store's public address. The links the instance mails carry each tenant's own domain, not this one ([Plan your instance](/run/plan/#domains)). Changing it later is untested |
| `VEYQ_IMAGE` | Yes | — | The application image, as `ghcr.io/raddatzk/veyq:<major>.<minor>@sha256:<digest>`. `veyqctl` writes it from the release's manifest ([Verify a release](/run/verify-a-release/)) and refuses it as a `--set` |
| `VEYQ_ADMIN_IMAGE` | Yes | — | The admin image of the same release, pinned the same way |
| `MARIADB_PASSWORD` | Yes | — | The application's database password |
| `MARIADB_ROOT_PASSWORD` | Yes | — | MariaDB's root password; a backup capture uses it |
| `GARAGE_RPC_SECRET` | Yes | — | The bundled Garage's node secret, exactly 64 hex characters |
| `GARAGE_ADMIN_TOKEN` | Yes | — | The bundled Garage's admin token. The application reads it too: it sets the bundled store up with it and derives the store's access keys from it, so there is no store key to set |
| `ADMIN_DOMAIN` | No | unset | A hostname of the admin surface's own. Unset, the console is reachable from the host only ([First-run setup](/run/first-run/#reach-the-console)); set it only after the Server Owner exists |
| `ADMIN_PORT` | No | `8081` | The host port the admin surface is published on, on `127.0.0.1` only |
| `MUSIC_DOMAIN` | No | unset | The music host for background music: a hostname of its own beside `VEYQ_DOMAIN`, routed to the application, which serves the music frame there and nothing else. Unset, no music plays in veyq ([Background music](/run/capabilities/#background-music)) |
| `MUSIC_SPOTIFY_CLIENT_ID` | No | unset | The client ID of your Spotify app; with `MUSIC_DOMAIN`, Spotify playlists play in veyq |
| `MUSIC_APPLE_DEVELOPER_TOKEN` | No | unset | A MusicKit developer token (a JWT, valid at most six months); with `MUSIC_DOMAIN`, Apple Music playlists play in veyq until it expires |
| `VEYQ_ACME_CASERVER` | No | Let's Encrypt production | The ACME directory; point it at the staging directory while testing |
| `VEYQ_HSTS_ENABLED` | No | `true` | HSTS on the instance and the admin surface ([Hardening](/run/hardening/#tls-hsts-and-csp)) |
| `VEYQ_MEM_LIMIT` | No | `1g` | The application container's memory limit; the JVM heap is 60 % of it |
| `VEYQ_BACKUP_OBJECTS_TO` | No | unset | Read by `veyqctl` only: the object pool every `backup` and `upgrade` copies the store's new objects into — an absolute directory on another disk, or `s3:<bucket>[/<prefix>]`. Unset, a backup holds no object, and `veyqctl status` warns while the bundled store is in use ([Backup and restore](/run/backup-and-restore/#capture)) |
| `VEYQ_BACKUP_POOL_ENDPOINT` | With an S3 pool | — | The S3 pool's endpoint, as the backup tool's container reaches it |
| `VEYQ_BACKUP_POOL_ACCESS_KEY` | With an S3 pool | — | The S3 pool's access key; reaches the tool through its environment, never a capture |
| `VEYQ_BACKUP_POOL_SECRET_KEY` | With an S3 pool | — | Its secret key, the same way |
| `VEYQ_BACKUP_POOL_REGION` | No | `us-east-1` | The S3 pool's region |
| `VEYQ_BACKUP_POOL_KEEP` | No | `3` | The pool keeps the objects its newest this-many captures index, and deletes the rest |

The compose file sets the rest of the application's environment itself (the database URL, the bundled store's
internal addresses, the config-secret key path, the routing file, and `VEYQ_HTTP_CLIENT_PORT_SOURCE: header`, which
goes with the recipe's Traefik plugin — [Networking](/run/networking/#where-the-source-port-comes-from)). Leave
those to it.

## Kubernetes: the chart's values

Run `helm show values` for the chart and its comments. The chart generates no secret, and `values.schema.json`
refuses an install that leaves a required value unset, naming it. Required: `domain`, `gateway.className`, the
database keys under `secrets` (or `secrets.existingSecret`), `storeDomain` and the two Garage keys under `secrets`
when `garage.enabled` is `true`, `networkPolicy.ingressFrom` while
network policy is on, `externalDatabase.jdbcUrl` when `mariadb.enabled` is `false`, `gateway.tls.secretName` in
`shared` mode, and a backup choice: `backup.existingClaim`, or
`backup.enabled: false`.

### Domains, images, HSTS

| Value | Default | Meaning |
|---|---|---|
| `domain` | — | The instance's domain, as `VEYQ_DOMAIN` above |
| `storeDomain` | — | The bundled store's own host; browsers upload to and download from it. Required, and read, only with `garage.enabled` |
| `hsts.enabled` | `true` | HSTS on the instance and the admin surface |
| `clusterDomain` | `cluster.local` | The cluster's DNS domain. The admin surface reaches the app's admin listener by the full name `veyq-app-admin.<namespace>.svc.<clusterDomain>`, because its proxy's resolver applies no search list. Change it only if your cluster uses another |
| `image.repository` | `ghcr.io/raddatzk/veyq` | The application image |
| `image.tag` | the chart's `appVersion` | The release; set it when installing from a checkout |
| `image.digest` | — | The digest you verified; the image is then pulled by digest |
| `image.pullPolicy` | `IfNotPresent` | |
| `admin.image.repository` | `ghcr.io/raddatzk/veyq-admin` | The admin image |
| `admin.image.tag` | the chart's `appVersion` | Pin the same release as the application |
| `admin.image.digest` | — | Its verified digest |
| `admin.image.pullPolicy` | `IfNotPresent` | |
| `admin.domain` | — | A hostname of the admin surface's own; unset, it is reached by port-forward only |
| `music.domain` | — | The music host for background music, as `MUSIC_DOMAIN` above: an HTTPRoute to the application and, under `per-domain`, a listener and certificate of its own |
| `music.spotifyClientId` | — | As `MUSIC_SPOTIFY_CLIENT_ID` above |
| `music.appleDeveloperToken` | — | As `MUSIC_APPLE_DEVELOPER_TOKEN` above |

### The application

| Value | Default | Meaning |
|---|---|---|
| `app.trustedProxies` | — | The hops in front of the gateway, such as a load balancer: IP addresses and CIDR ranges, comma-separated; renders `VEYQ_HTTP_TRUSTED_PROXIES` ([Hardening](/run/hardening/#list-only-the-hops-in-front-of-the-gateway)) |
| `app.clientPort.source` | `none` | Where the client's source port comes from: `none`, `proxyProtocol` (the gateway sends the PROXY protocol to the app's routes; also turns it on in the app and moves the probes to the admin listener) or `header` (your edge overwrites the header on every request); renders `VEYQ_HTTP_CLIENT_PORT_SOURCE` ([Networking](/run/networking/#where-the-source-port-comes-from)) |
| `app.clientPort.header` | `X-Veyq-Client-Addr` | The header `header` reads; rendered as `VEYQ_HTTP_CLIENT_PORT_HEADER` only when it differs |
| `app.resources.limits.memory` | `1Gi` | The memory limit; the JVM heap is 60 % of it |
| `app.resources.requests.cpu` | `250m` | |
| `app.resources.requests.memory` | `512Mi` | |
| `app.startupProbe.periodSeconds` | `5` | The startup probe reads `/api/setup`; 5 × 36 covers a slow first start |
| `app.startupProbe.failureThreshold` | `36` | |
| `app.readinessProbe.periodSeconds` | `15` | |
| `app.readinessProbe.timeoutSeconds` | `5` | |
| `app.livenessProbe.enabled` | `false` | Off on purpose: a liveness probe turns garbage-collection pauses into restart loops, and the JVM exits on its own when out of memory |
| `app.livenessProbe.periodSeconds` | `30` | |
| `app.livenessProbe.timeoutSeconds` | `5` | |
| `app.livenessProbe.failureThreshold` | `6` | |
| `app.persistence.size` | `100Mi` | The claim for the config-secret key and the erasure ledger; kept on uninstall |
| `app.persistence.storageClass` | — | |
| `app.persistence.accessMode` | `ReadWriteOnce` | |
| `app.configSecret.existingSecret` | — | A Secret holding your own config-secret key; the app reads it read-only and generates none ([Storage and email](/run/storage-and-email/#the-config-secret-key)). The claim still holds the erasure ledger |
| `app.configSecret.key` | `config-secret.key` | The Secret's key that holds it |
| `admin.resources.limits.memory` | `128Mi` | |
| `admin.resources.requests.cpu` | `50m` | |
| `admin.resources.requests.memory` | `32Mi` | |

### Network policy, gateway and TLS

| Value | Default | Meaning |
|---|---|---|
| `networkPolicy.enabled` | `true` | Default-deny plus the paths the stack uses. `false` removes every policy, the admin listener's included |
| `networkPolicy.ingressFrom` | — | The NetworkPolicy peers that match your gateway's data-plane pods ("ingress" is the policy direction); required while policy is on |
| `gateway.className` | — | The `GatewayClass` of the chart's `Gateway` `veyq`, i.e. which Gateway API implementation serves it; required |
| `gateway.annotations` | — | Stamped on the `Gateway` and on every tenant `ListenerSet`; how a certificate issuer of your own is wired |
| `gateway.tls.mode` | `none` | `none` (plain HTTP only; TLS ends before the gateway), `per-domain` (an HTTPS listener and certificate per host, tenants' in `ListenerSet`s) or `shared` (one HTTPS listener with a wildcard certificate, no custom tenant domains) |
| `gateway.tls.secretName` | — | The wildcard certificate's Secret in `shared` mode |
| `gateway.tls.acme.enabled` | `false` | Let the chart create a cert-manager issuer (HTTP-01) and use it; needs `per-domain` and cert-manager 1.21 or later |
| `gateway.tls.acme.server` | Let's Encrypt production | The ACME directory |

### Secrets

| Value | Default | Meaning |
|---|---|---|
| `secrets.existingSecret` | — | The name of a Secret of your own holding the keys below (the two Garage keys only with `garage.enabled`); the chart then renders none |
| `secrets.mariadbPassword` | — | The application's database password |
| `secrets.mariadbRootPassword` | — | MariaDB's root password |
| `secrets.garageRpcSecret` | — | Exactly 64 hex characters. Required only with `garage.enabled` |
| `secrets.garageAdminToken` | — | The bundled Garage's admin token; the application derives the store's access keys from it. Required only with `garage.enabled` |

### Database and store

| Value | Default | Meaning |
|---|---|---|
| `mariadb.enabled` | `true` | `false` uses a database of your own |
| `mariadb.image` | `mariadb:12.3@sha256:…` | Pinned by digest |
| `mariadb.persistence.size` | `10Gi` | |
| `mariadb.persistence.storageClass` | — | |
| `mariadb.resources.limits.memory` | `1Gi` | |
| `mariadb.resources.requests.cpu` | `250m` | |
| `mariadb.resources.requests.memory` | `256Mi` | |
| `externalDatabase.jdbcUrl` | — | `jdbc:mariadb://…` of your own database; the password is still `secrets.mariadbPassword` |
| `externalDatabase.username` | `veyq` | |
| `garage.enabled` | `false` | `true` deploys the bundled Garage and offers it in the console. Off by default here, unlike on Compose: on a cluster it is one node on one claim, with no replication or versioning, and needs a host of its own. Off, set a store of your own after the install ([Storage and email](/run/storage-and-email/)) |
| `garage.image` | `dxflrs/garage:v2.0.0@sha256:…` | Pinned by digest |
| `garage.logFilter` | `garage=warn` | The store's `RUST_LOG`. At `info` it logs every request with the reader's address; raise it only to troubleshoot ([Operations](/run/operations/#logs)) |
| `garage.persistence.storageClass` | — | |
| `garage.persistence.meta.size` | `1Gi` | |
| `garage.persistence.data.size` | `50Gi` | The stored media |
| `garage.persistence.snapshots.size` | `1Gi` | Kept apart from the metadata on purpose |
| `garage.resources.limits.memory` | `512Mi` | |
| `garage.resources.requests.cpu` | `100m` | |
| `garage.resources.requests.memory` | `128Mi` | |

A database of your own needs no isolation setting: veyq sets READ COMMITTED on every connection itself
([ADR 0078](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0078-production-reads-committed-like-its-tests.md)).
If you replicate it with a binary log, use `binlog_format=ROW` or `MIXED` (MariaDB's default): at READ COMMITTED a
`STATEMENT` binlog refuses every write. The bundled database keeps no binlog.

### Backups

The backup gate captures the database and an index of the store before a release change migrates them
([Upgrade](/run/upgrade/), [Backup and restore](/run/backup-and-restore/)).

| Value | Default | Meaning |
|---|---|---|
| `backup.enabled` | `true` | The gate. `false` is a choice the schema accepts: then the backup before an upgrade is yours, by hand |
| `backup.existingClaim` | — | A PersistentVolumeClaim of your own for the captures; required while the gate is on. The chart creates none |
| `backup.keep` | `3` | Complete captures kept at most — a second bound beside B, the instance's backup retention, at which every capture goes, the newest too |
| `backup.prune.schedule` | `17 3 * * *` | When the daily CronJob `veyq-backup-prune` deletes the captures older than B |
| `backup.request` | — | Change it to ask for a capture without a release change |
| `backup.image.repository` | `ghcr.io/raddatzk/veyq-tools` | The tools image |
| `backup.image.tag` | `image.tag` | The application's release; the gate refuses any other |
| `backup.image.digest` | — | Its verified digest |
| `backup.image.pullPolicy` | `IfNotPresent` | |
| `backup.store.existingSecret` | — | For a store of your own: a Secret with `endpoint`, `bucket`, `accessKey`, `secretKey` (optionally `region`), allowed to list the bucket |
| `backup.resources.limits.memory` | `256Mi` | |
| `backup.resources.requests.cpu` | `100m` | |
| `backup.resources.requests.memory` | `64Mi` | |

## Settings in the admin console

| Setting | Where | Meaning |
|---|---|---|
| The object store | **Server → Object store**, or `veyq-admin store set` | The bundled store, an S3-compatible store of your own, or a Garage of your own, with its endpoint, bucket, region and keys. It applies once it has passed its check, before which no tenant can be created; where the deployment brings its Garage and nothing is saved, the server sets the bundled store and checks it itself ([First-run setup](/run/first-run/#connect-the-object-store)) |
| A tenant's storage quota | the tenant's panel, or `veyq-admin tenant quota` | The most a tenant may keep on the store ([Plan your instance](/run/plan/#storage-and-quotas)) |
| How long a guest's write is kept | **Server → Moderation**, or `veyq-admin guest-write-record set <days>` | N, 1 to 30 days, 7 by default: how long a link guest's IP address, source port, time and request `Origin` are kept. It is kept wherever a guest may write: switching off *Links to look and add* and *Drop boxes* keeps none ([Moderation](/run/moderation/#what-a-guests-write-keeps)) |
| How long a backup is kept | **Server → Moderation → Backups**, or `veyq-admin backup-retention set <days>` | B, 7 to 365 days, 30 by default: every capture veyq's tools wrote — `veyqctl`'s and the cluster gate's — is deleted once it is older, the newest too; the erasure ledger drops its lines at the same age ([Backup and restore](/run/backup-and-restore/#how-long-a-backup-is-kept--b)) |
| Who runs the instance | **Server → Operator**, or `veyq-admin operator set` | The instance contact and its languages, the statement address, the provider identification, the supervisory authority — all public, each optional and published as *not stated by this operator* while empty — and the private notification address, confirmed by a code ([Moderation](/run/moderation/#who-runs-the-instance)) |

A tenant's mail server, terms and per-link upload limit are its owner's, set in the app, not here.

## The admin image and the CLI

| Variable | Default | Meaning |
|---|---|---|
| `BACKEND_URL` | `http://app:8090` | Where the admin surface and the CLI reach the application's admin listener; the recipes set it |
| `VEYQ_ADMIN_EMAIL`, `VEYQ_ADMIN_PASSWORD` | — | The Server Owner's credentials for one `veyq-admin` call; pass them on the invocation, never in a file |
| `VEYQ_STORE_SECRET_KEY`, `VEYQ_STORE_GARAGE_ADMIN_TOKEN` | — | The secrets `veyq-admin store set` seals; unset keeps the stored ones |

## Backend keys for tuning

The backend reads more keys, each with a default that suits a household instance. They are named here so an
operator who needs one knows it exists; neither recipe passes them, so setting one means adding it to the
application's environment yourself (on Compose with a compose file of your own beside the production one). The
environment-variable form is the key in upper case with `.` and `-` turned into `_`
(`veyq.tenant.invite.ttl` → `VEYQ_TENANT_INVITE_TTL`).

| Key | Default | Meaning |
|---|---|---|
| `veyq.tenant.invite.ttl` | `P7D` | How long an invitation is valid, a space's first owner's and a member's alike; a member's invitation carries its expiry signed by the inviting owner, so a change applies to invitations sent after it |
| `veyq.recovery.token.ttl` | `PT1H` | How long a password-recovery link is valid |
| `veyq.email-confirmation.token.ttl` | `PT24H` | How long an address-confirmation link is valid |
| `veyq.pairing.channel.ttl` | `PT10M` | How long a device-linking code is valid |
| `veyq.node.trash.retention` | `P30D` | How long trashed folders, Memories, photos and videos can be restored |
| `veyq.scope.delete.retention` | `P30D` | How long a deleted group stays restorable, and how long a deleted tenant waits before it is erased |
| `veyq.account.erasure.window` | `P14D` | How long after the request an account is erased, its own spaces with it; until then you can stop the erasure ([Accounts](/run/first-run/#accounts)). It must be positive and, with the hourly purge, at most 28 days (the shortest calendar month), or the application refuses to start; a change applies to requests made after it |
| `veyq.application.off.retention` | `P30D` | How long an application stays switched off in a tenant before its content there is erased; the day is the switch-off plus this ([Capabilities](/run/capabilities/#what-acts-at-once-and-what-only-on-new-data)) |
| `veyq.blob.max-bytes` | 20 GiB | The largest single file |
| `veyq.blob.upload.retention` | `PT1H` | When an abandoned upload is cleaned up; must be longer than the presigned-link lifetime |
| `veyq.blob-store.presign-ttl-seconds` | `600` | The lifetime of a presigned upload or download link |
| `veyq.blob-store.garage.node-capacity` | `1G` | The capacity veyq declares when it lays out a Garage it sets up |
| `veyq.store-reconcile.grace` | `P30D` | How long an object no record claims stays before it is deleted, once you confirmed the bucket ([Operations](/run/operations/#objects-no-record-claims)) |
| `veyq.store-reconcile.period` | `PT6H` | How long after one full listing of the store the next one starts |
| `veyq.store-reconcile.max-gap` | `P1D` | Two listings further apart than this restart every grace period; must exceed the period by more than a minute, or the application refuses to start |
| `veyq.ratelimit.*` | see [cc.security.rate-limit](https://github.com/raddatzk/veyq/blob/main/docs/crosscutting/cc.security.rate-limit.md) | The rate limits of the public endpoints, per address and per account |
| `veyq.http.hsts.max-age` | `P180D` | The HSTS lifetime |
| `veyq.admin-channel.port` | `8090` | The admin listener's port. The recipes assume 8090; do not change it |

**How far this is proven.** The variables and values on this page are read from the recipes as committed, and the
guard above keeps the list complete. Their meanings are not tested beyond the recipes' own checks.
