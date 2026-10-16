---
title: Operations
description: The health probe, what the logs hold and leave out, that there are no metrics, the background jobs an instance runs, and its daily look for new releases.
sources:
  - docs/decisions/0062-store-object-reclaim-is-a-durable-work-list.md
  - docs/crosscutting/cc.security.wire-assertion.md
  - docs/decisions/0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md
  - docs/features/admin/use-cases/uc.admin.learn-of-new-releases.md
---

## Health

The probe is `GET /api/setup`. It needs no sign-in, answers as soon as the database is migrated, and answers even
when the object store is not usable yet. There is no other health endpoint.

- **Docker Compose** runs it as the application's healthcheck (every 15 s, after a 60 s start period). MariaDB has a
  healthcheck of its own; Garage, Traefik and the admin surface have none, so `docker compose ps` shows them as
  running, not healthy. `volume-ownership` shows as exited (0), which is its done state.
- **Kubernetes** uses it for the startup probe (every 5 s, up to 36 times) and the readiness probe (every 15 s).
  There is **no liveness probe by default, on purpose**: it would turn a long garbage-collection pause into a
  restart loop, and the JVM already exits on its own when it runs out of memory. `app.livenessProbe.enabled` turns
  one on.

## Logs

The application writes to standard output, at the default level:

```sh
docker compose -f infra/docker-compose.prod.yml logs -f app
kubectl -n veyq logs -f deploy/veyq-app
```

What it logs is operational: routing being written, the object store being set up (an access key's id at most,
never a secret), the config-secret key being generated or unreadable, background jobs that give up, a mail that
could not be sent. **What it does not log:** content, names, keys, tokens and the links that carry them. A tenant's
invitation link and its secret, an SMTP password and the store's secrets are never written to the log.

Two personal facts **are** logged: the recipient address of a mail that could not be sent, and the owner's address
when a new tenant's invitation falls back to the copy link.

Every run of the end-to-end orchestrator searches every server and container log of the run for any value the client
sealed and for every token it handled, and fails if one appears
([cc.security.wire-assertion](https://github.com/raddatzk/veyq/blob/main/docs/crosscutting/cc.security.wire-assertion.md)).
Traefik's access log is not enabled by either recipe; the admin surface's access log keeps the method, the path
without its query, the status, the size, the user agent and the forwarded address. A cluster's gateway
implementation keeps its own access log, which is yours to configure.

## No metrics

veyq has no metrics endpoint and no health surface beyond the probe above
([ADR 0062](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0062-store-object-reclaim-is-a-durable-work-list.md)).
Watch the container, the database and the store's disk with the tools you already use.

## Background jobs

The application runs its own cleanup on a schedule; there is nothing to set up.

| Job | Keeps | Runs |
|---|---|---|
| Trashed folders, Memories and media | 30 days, then purged | hourly |
| A deleted group | 30 days, then purged | hourly |
| Abandoned uploads | 1 hour | hourly |
| Tenant invitations | valid 7 days, removed 30 days after they end | hourly |
| Recovery and address-confirmation links | valid 1 and 24 hours, removed 7 days after | hourly |
| Device-linking codes | valid 10 minutes, removed after a day | every 5 minutes |
| Deleting store objects | retried 5 times with growing pauses, then given up | hourly |
| Looking for new releases ([below](#new-releases)) | what it found, until you run a release as new | daily, and once after each start |

Deleting a file's stored object is the job of one sweep only. An object it gave up on is logged once and stays in the
store; count them in the database:

```sql
SELECT count(*) FROM veyq_store_reclaim WHERE abandoned_at IS NOT NULL;
```

## New releases

Once a day — and once shortly after each start — the application reads the published releases and compares them
with its own, on the instance itself
([ADR 0097](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md)).
This is the one outbound request the application makes that you did not configure, and it is on by default, so that a
forgotten instance still hears of a security fix.

- **What it asks.** An anonymous `GET` of the release list from `api.github.com`, and, for a new major only, that
  release's `manifest.json` from `github.com` (a redirect to GitHub's download host). The request names no release
  of yours and nothing about the instance's users; the source learns only that this address asks.
- **What it does with the answer.** Nothing but show it: the console's **Server → Release** lists every newer release
  — a newer one of your major, a planned new major with the day your major leaves the apps' window, an unscheduled
  major marked **urgent** — and `veyq-admin release` prints the same. Upgrading stays yours
  ([Upgrade](/veyq/run/upgrade/)). With the instance's own mail set up, each notice is also mailed to you once
  ([Storage and email](/veyq/run/storage-and-email/#the-instances-own-mail-to-you)).
- **When it fails.** A source that cannot be reached, answers slowly (10 s), too much or something that is not a
  release list is logged as a warning and asked again the next day. Nothing waits on it. The panel shows when it
  last succeeded.
- **A build without a release stamp** (a local image, a development run) checks nothing.

**Switch it off** on **Server → Release**, or `veyq-admin release check off`: the application then makes no request
at all. **Point it at a mirror** instead by entering the mirror's `http(s)` address on the same panel: the check then
reads `<mirror>/releases.json` and `<mirror>/<tag>/manifest.json`, the layout `veyqctl` reads too.

## How far this is proven

The release check runs in the backend's tests against a source inside the test run — each kind of notice, each way a
source fails, the switch, and that no request names a release; it has not run against GitHub.

The health probe ran in the one live run of the hardened Compose recipe (2026-09-28). The log sweep runs with every
end-to-end run, on the development stack. The Kubernetes probes have not run in a cluster.
