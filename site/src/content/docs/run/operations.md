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
  - docs/decisions/0092-logs-are-operational-and-name-no-person.md
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

Every container writes to standard output; the application at the default level:

```sh
./veyqctl logs -f app                 # in /opt/veyq
kubectl -n veyq logs -f deploy/veyq-app
```

What the application logs is operational: routing being written, the object store being set up (an access key's id
at most, never a secret), the config-secret key being generated or unreadable, background jobs that give up, a mail
that could not be sent. **What it does not log:** content, names, email addresses, client addresses, keys, tokens and
the links that carry them. A mail that could not be sent is logged by its kind and the mail server's reply code, not
by its recipient; a new tenant's invitation that falls back to the copy link is logged by the tenant's id. An
operator who needs to know whose mail it was looks the account or the tenant up by that id. Nor does it log what
someone else wrote: a database's message, a mail server's reply text, a remote API's body. When the database refuses a
write only as it commits — two requests racing for the same email address — the transaction manager's warning
(`ARJUNA012125`) names the error's type and the database's SQLState, not its message; the transaction manager's other
warnings, such as a transaction timing out, are written as they are.

**An unexpected error** — one the server had no answer for — is answered `500` with an error id in the response
(`"message": "error id 3f2c…"`), and logged once at `ERROR` under that id: the method, the route as a template
(`POST /api/scopes/{scopeId}/nodes`, never the address the request was sent to or its query), the exception's class
chain, the database's SQLState and code where the database refused, and the first line of veyq's own code it passed.
Not the error's text. A user who reports an error can hand you the id, and you find its one line with
`./veyqctl logs app | grep <id>`. When that line is not enough, [Troubleshooting](/veyq/run/troubleshooting/) says how
to log the full text for a while.

What each source holds:

| Source | What it logs |
|---|---|
| Application | operational lines, with ids (account, tenant, scope, node, share), domains, key ids and counts; an unexpected error by its id, route template and class chain |
| Admin surface | an access log of the admin console: the time, the method, the path without its query, the status and the size — not who asked: no client address, no user agent, no forwarded address. Its error log, at nginx's default, names the hop that reached it: the loopback or the container bridge on the recipe's binding, a person's address only if you expose the console beyond that |
| Traefik (Compose) | its own errors, at `--log.level=ERROR`, set by the recipe; **no** access log, which the release's posture check holds. Turning one on records every reader with every share link they open, and it is then your own processing to disclose and bound |
| Garage | warnings and errors only — `RUST_LOG=garage=warn`, set by both recipes; no line per request |
| MariaDB | startup and errors; no general, slow-query or binary log |
| Your gateway (Kubernetes) | its own access log if you turn it on: every reader's address and path, kept as you configure it |

**How long a log stays.** A log is bounded by size, never by time — no container log driver offers time on both
targets, so veyq keeps people out of the logs instead.

- **Docker Compose** sets `json-file` with `max-size: 10m` and `max-file: 3` on every service: at most 30 MB per
  container, and the oldest file is deleted when a fourth would start. On a quiet instance that can span weeks; on a
  busy one, days. Recreating a container (every upgrade does) deletes its log with it. Without that block a stock
  Linux Docker keeps a container's log unrotated for as long as the container exists — keep it if you edit the recipe.
- **Kubernetes** leaves rotation to the kubelet, which rotates every container's log by size — 10 MiB × 5 files by
  default (`containerLogMaxSize`, `containerLogMaxFiles`), your cluster's setting — and drops it with the pod.
- **A log shipper** you run (Loki, a journal, a cloud logging agent) keeps copies under its own retention, which is
  yours to set and to name in your record of processing.

**A hold does not reach a log.** A moderation hold copies what it needs out of the database; no log, the store's
request log, a gateway's access log or a backup is within its reach, and none is needed for a report — the evidence is
the guest-write record in the database.

**Raising Garage's level for troubleshooting.** A store problem the warnings do not explain may need the request log:
on Compose add `services: garage: environment: RUST_LOG: garage=info` to `compose.override.yml` and run
`./veyqctl start`; on Kubernetes `--set garage.logFilter=garage=info`. At `info` Garage logs **every** S3 and admin
request with the peer's address and port and the object key — behind the proxy, every reader's — so set it back as
soon as you are done.

Every run of the end-to-end orchestrator searches every server and container log of the run for any value the client
sealed, for every token it handled and for every email address its suites signed up with, and fails if one appears
([cc.security.wire-assertion](https://github.com/raddatzk/veyq/blob/main/docs/crosscutting/cc.security.wire-assertion.md)).
The run raises Garage to `info` on purpose: the sweep's control is a key it sends to the bucket and then finds in that
request log.

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
  ([Upgrade](/veyq/run/upgrade/)). With the instance's own mail set up and confirmed, each notice is also mailed to
  you once ([Storage and email](/veyq/run/storage-and-email/#the-instances-own-mail-to-you)); until then the panel says,
  beside the switch, that finds are only shown, and `veyq-admin release` prints `noticesMailedTo` as null.
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

The log bounds and the store's level were measured on the pinned Garage image under the Compose recipe (2026-10-18):
the container runs with `json-file`, 10m × 3, and at `garage=warn` three requests for a bucket key left no line naming
it, where the same requests at `garage=info` left three, each with the peer's address and port.

The health probe ran in the one live run of the hardened Compose recipe (2026-09-28). The log sweep runs with every
end-to-end run, on the development stack. The Kubernetes probes have not run in a cluster.
