---
title: Operations
description: The health probe, what the logs hold and leave out, that there are no metrics, the background jobs an instance runs, and its daily look for new releases.
sources:
  - docs/decisions/0062-store-object-reclaim-is-a-durable-work-list.md
  - docs/features/admin/use-cases/uc.admin.configure-object-store.md
  - docs/crosscutting/cc.security.wire-assertion.md
  - docs/decisions/0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md
  - docs/features/admin/use-cases/uc.admin.learn-of-new-releases.md
  - docs/decisions/0094-a-clients-address-is-the-hop-the-network-vouches-for.md
---

## Health

The probe is `GET /api/setup`. It needs no sign-in, answers as soon as the database is migrated, and answers even
when the object store is not usable yet. There is no other health endpoint.

- **Docker Compose** runs it as the application's healthcheck (every 15 s, after a 60 s start period). MariaDB has a
  healthcheck of its own; Garage, Traefik and the admin surface have none, so `veyqctl status` shows them as
  running, not healthy. `volume-ownership` shows as exited (0), which is its done state.
- **Kubernetes** uses it for the startup probe (every 5 s, up to 36 times) and the readiness probe (every 15 s).
  There is **no liveness probe by default, on purpose**: it would turn a long garbage-collection pause into a
  restart loop, and the JVM already exits on its own when it runs out of memory. `app.livenessProbe.enabled` turns
  one on. With `app.clientPort.source: proxyProtocol` the probes poll the **admin listener** instead of the public
  port: the public port then closes every connection that does not start with a PROXY header, and a probe sends
  none. They still prove the process is up and the database migrated, but no longer the public listener — a public
  port that fails to bind still stops the application from starting.

## Logs

The application writes to standard output, at the default level:

```sh
./veyqctl logs -f app                 # in /opt/veyq
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
| Comparing the store with the database ([below](#objects-no-record-claims)) | each object no record claims, 30 days before it is deleted — once you allow it | a full listing every 6 hours, a few pages a minute |
| Looking for new releases ([below](#new-releases)) | what it found, until you run a release as new | daily, and once after each start |

Deleting a file's stored object is the job of one sweep only, which works through a deletion list. An object it gave
up on is logged once and stays in the store. **Server → Object store** shows how many objects wait on that list and how
many it gave up on, and `veyq-admin store reconcile` prints the same; in the database they are:

```sql
SELECT count(*) FROM veyq_store_reclaim WHERE abandoned_at IS NULL;      -- waiting
SELECT count(*) FROM veyq_store_reclaim WHERE abandoned_at IS NOT NULL;  -- given up on
```

### Objects no record claims

In the background the application lists the object store and compares every object with its database
([ADR 0062](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0062-store-object-reclaim-is-a-durable-work-list.md)).
An object shaped like veyq's that no record claims, older than an hour on the store's own clock, is counted: under
**Server → Object store**, *Objects no record claims* shows the last full listing — how many objects the store holds,
how many no record claims and their size, how many are not this server's — and `veyq-admin store reconcile` prints it.
The store check's probe object and anything not named like veyq's objects (another tool's, an object pool kept in the
same bucket) are never counted as unclaimed and never deleted.

**Where they come from.** After an [in-place rollback](/veyq/run/upgrade/#rollback), the uploads made under the
failed release: their records went with the emptied database, so nobody can read them again. Otherwise, a writer other
than this instance in the same bucket, or a defect — report that one.

**When they are deleted — only under three conditions:**

1. **You confirmed the bucket is this server's alone.** Until you press *Confirm exclusive bucket* (or run
   `veyq-admin store reconcile --confirm-exclusive-bucket`), the application only counts. Confirm only when no other
   server and no other tool writes to this bucket: their objects would look unclaimed here. The confirmation is for the
   store in use; another store setting withdraws it, and so does every [restore](/veyq/run/backup-and-restore/).
2. **The object stayed unclaimed for the grace period** — 30 days from when it was first seen, in every listing since
   ([`veyq.store-reconcile.grace`](/veyq/run/configuration/#backend-keys-for-tuning)). A record that comes back (a
   restore of the right backup) takes it out again. A restore or a rollback restarts every grace period, and so does a
   gap of more than a day between two listings: the grace counts only time the application actually watched.
3. **You did not pause it.** *Pause deleting* (`--pause`) stops the deleting and keeps the counting; *Resume deleting*
   (`--resume`) lets the next full listing hand over what is due.

The page shows the candidates — how many uploads, their objects and size, when the first and the last were first seen
— and, while deleting is on, the date the first will be deleted. A due object goes onto the deletion list above, whose
sweep deletes it about an hour later; a pause does not reach what is already on that list. The application itself
never deletes an object here.

The listing needs the store's key to be allowed to **list** the bucket. Where it is not, the page says *the store
refused it* and nothing is counted ([Storage and email](/veyq/run/storage-and-email/)).

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
