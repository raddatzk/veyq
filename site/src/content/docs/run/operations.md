---
title: Operations
description: The health probe, what the logs hold and leave out, that there are no metrics, and the background jobs an instance runs.
sources:
  - docs/decisions/0062-store-object-reclaim-is-a-durable-work-list.md
  - docs/crosscutting/cc.security.wire-assertion.md
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

Deleting a file's stored object is the job of one sweep only. An object it gave up on is logged once and stays in the
store; count them in the database:

```sql
SELECT count(*) FROM veyq_store_reclaim WHERE abandoned_at IS NOT NULL;
```

## How far this is proven

The health probe ran in the one live run of the hardened Compose recipe (2026-09-28). The log sweep runs with every
end-to-end run, on the development stack. The Kubernetes probes have not run in a cluster.
