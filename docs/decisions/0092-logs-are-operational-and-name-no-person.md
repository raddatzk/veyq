# ADR 0092 — Logs are operational and name no person; evidence never rides a log

- Status: **Accepted 2026-10-18** by the owner, with Q29–Q33 of `changes/cr-logging.md` as recommended (proposed
  2026-10-16; re-grounded 2026-10-18 after moderation landed, inbox #462).
  **Built:** § 1's separation, held by `ModerationLogSliceTest` (no moderation event in a log line) and
  `GuestWriteRecordCoreSliceTest` (neither the recorded nor a forged address in a log line); § 2 whole, by
  cr-guest-write-record and ADR 0093 § 10; § 3 at the mail, invite and recovery sites (inbox #481, the first two pinned
  by `SmtpDeliveryLogTest` and `TenantInviteMailerLogTest`; the recovery line has no test yet); § 5's size bound on every
  Compose service and `garage=warn` on both targets, held by `validate_compose_posture.py` assertion 6 and
  `chart_meta_check.py` (xxvii) (#482); the Run pages [Operations § Logs](../../site/src/content/docs/run/operations.md#logs)
  and [Data protection](../../site/src/content/docs/run/data-protection.md). **Open, and built by
  `changes/cr-logging.md`:** the catch-all for unexpected errors (§ 4) — today Quarkus logs the request URI with its
  query and the exception's text, which carries a database's bound value (M6, M7); the remaining sites of § 3 (M3, M4);
  the rate limiters' time-based eviction, whose keys include email addresses (§ 5); the admin console's access log
  without the client's address (§ 5, owner Q29); Traefik's level set explicitly and its access log held off by the
  posture guard (§§ 4, 5); the e2e sweep's email-address needles (§ 4); the Run and Security pages. **Acceptance
  commits to:** §§ 1–8 as the rule for every log veyq writes or its recipes configure, on both deployment targets; the
  CR's build; the answers to the CR's owner questions as recommended unless answered otherwise; § 6's Still open taken
  as met until veyq is offered as a service. It does not commit to JSON logs, a request id, a static log guard or a
  time-bounded retention.
- Date: 2026-10-16

Provenance: **(Mn)** is a command in § Measurement ledger, run on this worktree (branch
`worktree-agent-a929a4319c05a1f59` at `427c68ac`, clean); **(Rn)** is remembered from outside the repo and listed in
§ Remembered, to be measured before it drives code.

## Context

veyq logs by default and had never decided what. The pieces grew one CR at a time, and each was careful locally: the
admin nginx format was trimmed (inbox #256), the invite mailer was tested never to log its token, and every end-to-end
run sweeps every log for sealed values and tokens ([cc.security.wire-assertion](../crosscutting/cc.security.wire-assertion.md)).
A legal audit on 2026-10-18 found addresses in three log lines and no rotation on Compose, and both were fixed ahead of
this ADR. The moderation ADR ([ADR 0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md))
is built: the evidence a report needs lives in the guest-write record (§ 10) and the moderation log (§ 11), both in the
database, so no log is needed for it. Which address a request has is
[ADR 0094](0094-a-clients-address-is-the-hop-the-network-vouches-for.md)'s, built.

### What is logged today

| Source | Goes to | Holds | Bound today |
|---|---|---|---|
| Backend app | stdout, plain text, INFO; no access log, no JSON, no request id (M1) | operational lines at 53 call sites, all in core (M2): ids, domains, key ids, counts; **a client-supplied string echoed verbatim** (`AccountResource`, M3); up to 300 characters of a Garage admin response in an exception's text (M4) | the runtime's (below) |
| Backend, unexpected error | Quarkus's default handler; no catch-all mapper (M5) | **the request URI with its query** and the exception with its message (M6) — a client's own query string verbatim; the contract's query parameters are ids, states and cursors (M15) | same |
| Hibernate | `org.hibernate.orm.jdbc.error` at WARN | **the database driver's message** for every failed statement, and the same text folded into the exception (M7); MariaDB's message for a unique violation names the value (R1) — an email address on `ux_veyq_user_email` (M8) | same |
| Rate limiters | heap only | the client address as ADR 0094 resolves it, and for the recovery and invite mails the email address, as bucket keys (M9); a bucket is dropped only once 100 000 keys are tracked, so below that a key stays until restart | process lifetime |
| Traefik (Compose) | stdout | its own errors; no access log and no level set, so the image's (M11, R3); the client-address plugin logs nothing (M11) | runtime's |
| Admin nginx (both targets, one image) | stdout | the client address, time, method, path without query, status, size, User-Agent, `X-Forwarded-For` (M12); the error log, at nginx's default level, names the client and the request line on an upstream failure (R7) | runtime's |
| Garage (both targets) | stdout | warnings and errors: `RUST_LOG=garage=warn` on both targets; the e2e overlay raises it to `info` (M10). At `info` it logs every request with the peer and the URI with its query, and behind a proxy the forwarded address (R4) | runtime's |
| MariaDB (both targets) | stdout | startup and errors; no general, slow or binary log (M13) | runtime's |
| Gateway implementation (Helm) | outside the chart | whatever the operator turns on | the cluster's |
| Container runtime | Compose: `json-file`, `max-size: 10m`, `max-file: 3` on all six services (M10); Helm: the kubelet | — | Compose 30 MB per container; Helm 10 MiB × 5 by default (R6) |

Only the guest-write record's table stores a client address, and no table stores a User-Agent (M14).

## Decision

### 1. Three kinds of data, never mixed

- **A log** is operational. It never carries a person's identifiers beyond the ids of § 3, it is never evidence, and
  its retention is the runtime's, bounded by size. This ADR owns it.
- **The guest-write record** is evidence: a database row with a retention the app enforces (ADR 0093 § 10).
- **The moderation log** is the record of what was reported and done about it (ADR 0093 § 11).

A log never holds a record's address or a moderation event, and a log may name a moderation case by its id and nothing
more. Built and held by `ModerationLogSliceTest` and `GuestWriteRecordCoreSliceTest` (M16).

### 2. The guest-write record's retention period

The record is ADR 0093 § 10's; which address it takes is ADR 0094's. This ADR fixes the period, and all of it is built:

- **An instance setting** ([ADR 0089](0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md)
  decision 1), set in admin-web and its CLI: **7 days** by default, **1 to 30**. Off is the capability switch's
  ([ADR 0088](0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md)), never a period of 0; the
  server refuses any value outside the range. The disclosure renders the value current when the guest writes.
- **A change never extends a promise already made.** A row goes at the earlier of its write time plus the period then
  and its write time plus the period now: a shortening reaches every row at the next purge, a lengthening only new rows.
- **The purge also runs at startup**, so a restored backup's stale rows go at boot.

### 3. What a log may carry

| Never | Allowed |
|---|---|
| A key, a fragment, a token or anything derived from one (session, refresh, contributor, `X-Share-Write`, pwToken, invite and recovery secrets, a presigned URL's query) | ids: account, tenant, scope, node, share, blob, job, moderation case |
| A password, a sealed value, plaintext of any metadata | a tenant's domain, a bucket name, an access key's **id** |
| An email address | an exception's class, a cause the app wrote, an SMTP reply code, a SQLState and vendor code |
| A network address or a User-Agent (the admin nginx's error log is § 5's one exception) | counts, durations, attempt numbers |
| A request's input echoed verbatim — a URI, a query, a header, a body field | a known enum value; an unknown one by its length |
| A message written by someone else: a database driver's, a mail server's, a remote API's body | a remote API's status and its error code, matched against a fixed pattern |
| A guest-write record or a moderation event | — |

Ids are allowed because operating the service needs them, and they appear on failures and setup events, not per
request. They are pseudonymous, which is not anonymous: the operator holds the database that resolves them, so for the
operator a log line with an account id is personal data (GDPR Recital 26), processed on § 6's basis. A share id is
half a capability whose other half never reaches the server, and the operator holds every share id in the database
anyway (`security-model.md` § Sharing & share-boundary Trust & Safety).

An exception is logged whole only where the app bound no person's value into the failing call. Everywhere else, and
always for an unexpected error, it is logged by its class chain.

### 4. Enforcement

- **A log-capture test per site that handles a person's value**, after `SmtpDeliveryLogTest`: it asserts the line
  **is** emitted (the positive control) and does not carry the value. Built for the mail and invite sites; owed for the
  recovery worker, the unknown-wrap warning and the catch-all.
- **A catch-all for unexpected errors**, one formatter behind two entry points — a JAX-RS mapper for `Throwable` and a
  Vert.x failure handler ahead of Quarkus's own, so no failure reaches the default handler's line (M6). It logs at ERROR
  a random error id, the method, the route **template**, the class chain, a SQLState and vendor code where a
  `SQLException` is in it, and the first frame in `me.raddatz.veyq`; never the URI, the query or any message. It
  answers 500 with the existing `ErrorResponse` (`internal_error`, the error id as `message`). The full exception goes
  to DEBUG under its own category, off by default (owner Q31).
- **Hibernate's statement error log is silenced**: `org.hibernate.orm.jdbc.error` is set to ERROR, so the WARN line
  that carries the driver's message is not written (M7). The catch-all keeps the SQLState and vendor code.
- **The e2e sweep, widened.** `scripts/e2e_sweeps.py` already fails a run on any sealed value or token in any log
  (M17). It gains a needle kind for every email address the run's suites signed up with, searched in every log and
  exempt only where it belongs (the mailbox, the database dump, the HTTP recording); its control is that each address
  is found in the mailbox. A forged client address is the slice tests' (built, M16), which capture the root logger
  and so every framework's lines too.
- **`validate_compose_posture.py` assertion 6** (built: every service bounded, the store below `info`) gains: the
  proxy sets no access log. The chart has no proxy; its store is `chart_meta_check.py` (xxvii).
- No static scan of log statements. A grep for `email` in a log call is easy to evade and would turn red on a comment.
  The tests above bite on the value instead.

### 5. Retention and rotation, per source and target

veyq bounds every log by size and keeps people out of them, so their age stops mattering for what veyq writes. The
period each source keeps is stated as its criterion (size, and a container's recreation, which every upgrade does),
because no container log driver offers a time bound on both targets.

| Source | Compose: veyq sets | Helm: veyq sets | veyq documents for the operator |
|---|---|---|---|
| App, admin nginx, MariaDB, Traefik, Garage | `json-file`, `max-size: 10m`, `max-file: 3` per service (built) | nothing: the kubelet rotates, 10 MiB × 5 by default (R6) | rotation is by size, not time; a log shipper's retention is theirs (built) |
| Garage | `RUST_LOG=garage=warn` (built) | `garage.logFilter=garage=warn` (built) | raising it for troubleshooting logs every request with its address (built) |
| Traefik | `--log.level=ERROR`, set rather than inherited; no access log (owner Q30) | — | turning an access log on records every reader and is the operator's own processing |
| Gateway implementation | — | nothing in the chart | the same as Traefik's access log, and the place to keep it short or off |
| Admin nginx | the access log keeps time, method, path, status and size, **without** the client address, the User-Agent and `X-Forwarded-For` (owner Q29); the error log keeps nginx's default, which names the hop — loopback or the container bridge on the recipe's binding (M12) | the same image | an operator who exposes the console beyond loopback sees addresses in its error log |
| Rate limiters | a pass every minute drops every full bucket of every counter, whatever the key count; a full bucket is the same as an absent one, so this changes no limit, and a key — an address or an email address — stays only while its bucket refills | the same | — |
| Backups | — | — | the deletion concept is [ADR 0056](0056-instance-durable-state-and-cold-logical-backup.md) decision 8's; a log is in no backup veyq takes |

The e2e stacks keep `garage=info`: the wire sweep's per-run control is a key planted in the store's request log. Only
`infra/e2e/docker-compose.e2e.yml` sets it; the development stack inherits the image's `info` (M10).

### 6. Legal basis and disclosure, for logs

**This is research, not legal advice.**

| Data | Basis (GDPR Art. 6) | Disclosed in |
|---|---|---|
| App log (ids, § 3) | (1)(f): operating and securing the service | the instance privacy notice ([ADR 0035](0035-server-terms-of-service.md) decision 8); Run, Data protection's record of processing (built) |
| Admin nginx log (no address, § 5) | (1)(f): operating the administration surface | Run docs |
| Rate-limit buckets | (1)(f): defence against abuse; heap only, dropped once full | the instance privacy notice |
| A gateway's or Traefik's access log, a log shipper | the operator's own | Run docs: "yours, not veyq's" (built) |

A Server Owner running veyq only for their household may fall under the household exemption (Art. 2(2)(c)). That is
their call. A dynamic IP address is personal data (CJEU C-582/14, *Breyer*), and no EU or German law puts an
IP-retention duty on a host (R9), which is why addresses stay out of the logs rather than being kept for a period.

#### Still open

Taken as met by what is built and decided, the same way as ADR 0093's Still open (owner 2026-10-18): while veyq is
not offered as a service, none of them holds back a CR or a release. Before it is, each is answered by counsel, and an
answer that differs is a legal duty that wins over what is built (`CLAUDE.md`).

1. **A period stated as a criterion.** Art. 13(2)(a) and 30(1)(f) accept "the criteria used to determine that period"
   where a period is not possible. Whether size-based rotation plus recreation meets Art. 5(1)(e) for a log that holds
   pseudonymous ids only, without a time bound, is this ADR's reading, not a settled answer.
2. **The admin console's error log** names the hop that reached it. On the recipe's loopback binding that is no person;
   whether an operator who exposes the console must be told more than § 5's line is open.

### 7. Structure and correlation

Logs stay plain text at INFO. JSON logging and a request id are deferred until something reads them. An id, where one
exists — a request id later, the catch-all's error id now — is random per event and never derived from an address, an
account or a token.

### 8. What a hold can reach

Only what ADR 0093's snapshot copies out of the database. Every log, the store's request log, an ingress log and a
backup are **outside a hold's reach**, and the Run docs say so for each source. No log is needed for a report.

## Rejected alternatives

- **An access log as the evidence.** It records every reader, links to no object and keeps its own retention; the
  guest-write record is the evidence (ADR 0093 § 10).
- **A static log-call guard in `scripts/`.** See § 4: it bites on spelling, not on the value.
- **Time-bounded log retention set by veyq.** No container log driver offers it on both targets; `journald`'s
  `MaxRetentionSec` is the host's setting, not the recipe's. veyq keeps people out of its logs instead.
- **Let the store keep its request log in production.** It records every object read with presign signatures and every
  reader's address (R4). Its only consumer is the e2e sweep's control, which the e2e stack keeps.
- **A Traefik access log with the client fields dropped.** Traefik can drop `ClientHost` and filter by status, but the
  path still carries every share id read, and the catch-all's error id already answers what a 5xx log would.
- **Keep the admin console's client address** as the security log of the most privileged surface. On the recipe's
  binding it names the loopback or the bridge, never a person, and where an operator exposes the console it names a
  person with no time bound, because a quiet console's log rotates by size almost never.

## Consequences

- An operator who reads a log learns nothing that names a person beyond pseudonymous ids. That is a tested property —
  the log-capture tests and the e2e sweep — no longer a habit.
- An unexpected error is diagnosed by its error id, route and class chain; the full text needs a DEBUG category raised
  on purpose, and the troubleshooting page says to set it back.
- The Garage request log is quiet in production; a store problem is diagnosed by raising the level.
- A log's age is not bounded, only its size. The Run docs state that criterion for the record of processing.

## Measurement ledger

Run from the worktree root unless noted; the commands are scripted in one file outside the repo, each listed here.

- **M1** `git grep -nE 'quarkus\.(log|http\.access-log|hibernate-orm\.log)' -- 'backend/*.properties'` → only
  `application-dev.properties:40,46`.
- **M2** `grep -rcE '(log|Log)\.(info|warn|error|debug)[fv]?\(' backend/core/src/main backend/apps/memories/src/main backend/src/main | grep -v ':0$'`
  → 25 files, 53 call lines, all under `backend/core`.
- **M3** `grep -n 'unrecognised method' backend/core/src/main/kotlin/me/raddatz/veyq/core/account/keymaterial/AccountResource.kt`
  → `:59`, `wrap.method` interpolated.
- **M4** `grep -n 'take(300)' backend/core/src/main/kotlin/me/raddatz/veyq/core/blobstore/GarageStoreProvisioner.kt` →
  `:182`; it reaches a log through `grep -n 'e.toString(), delay' …/blobstore/StoreProvisioningRunner.kt` → `:156`.
- **M5** `git grep -nE 'ExceptionMapper<(Throwable|Exception|RuntimeException)>' -- 'backend/*/src/main' 'backend/src/main'`
  → empty; the mappers that exist are narrow (`AuthExceptionMappers.kt`, `RequestRejectionMappers.kt`).
- **M6** Quarkus 3.36.0 sources in the Gradle cache: `unzip -p …/quarkus-vertx-http-3.36.0-sources.jar
  io/quarkus/vertx/http/runtime/QuarkusErrorHandler.java | grep -n 'HTTP Request to'` → `:178`
  `log.errorf(exception, "HTTP Request to %s failed, error id: %s", event.request().uri(), uuid)`; `unzip -p
  …/resteasy-reactive-3.36.0-sources.jar org/jboss/resteasy/reactive/server/core/RuntimeExceptionMapper.java | grep -n
  'Request failed'` → `:119`, reached only when no mapper matches.
- **M7** `ls ~/.gradle/caches/modules-2/files-2.1/org.hibernate.orm/hibernate-core` → `7.3.4.Final`; in its sources
  `SqlExceptionHelper.java:144` `ERROR_LOG.warn( sqlException.getMessage() )` and `:115` folds the same message into the
  converted exception; `SQLExceptionLogging.java:31` names the logger `org.hibernate.orm` + `.jdbc.error`.
- **M8** `grep -n 'ux_veyq_user_email' backend/core/src/main/resources/db/migration/V202610041200__core_baseline.sql` → `:41`.
- **M9** `grep -n 'MAX_TRACKED_KEYS\|removeIf' …/core/ratelimit/TokenBucketCounter.kt` → `:30,43,64`;
  `grep -n 'clientAddress(' …/core/ratelimit/RateLimitFilters.kt` → `:89`; `grep -n 'normalizeEmail' …/core/account/recovery/RecoveryRateLimiter.kt
  …/core/admin/tenant/InviteMailRateLimiter.kt` → an email address in each key; `grep -rln 'TokenBucketCounter()' backend/core/src/main`
  → every counter.
- **M10** `grep -c 'logging: \*logging' infra/docker-compose.prod.yml` → 6, one per service; `grep -n 'RUST_LOG\|logFilter:'
  infra/docker-compose.prod.yml infra/e2e/docker-compose.e2e.yml infra/docker-compose.yml charts/veyq/templates/garage.yaml
  charts/veyq/values.yaml` → `warn` in the recipe and the chart, `info` in the e2e overlay, nothing in the dev file.
- **M11** `grep -nE 'accesslog|--log\.' infra/docker-compose.prod.yml` → empty; `grep -nE 'log\.|fmt\.F?[Pp]rint|os\.Stderr'
  infra/traefik-clientaddr.go` → empty.
- **M12** `sed -n 30,35p admin-web/nginx.conf.template`; `grep -c error_log admin-web/nginx.conf.template` → 0;
  `grep -n '"127.0.0.1:' infra/docker-compose.prod.yml` → the admin port only.
- **M13** `grep -rnE 'general[_-]log|slow[_-]query|log[_-]bin|binlog' infra/docker-compose.prod.yml charts` → empty.
- **M14** `grep -rlniE '\b(ip|ip_address|remote_addr|client_ip|user_agent)\b' backend/core/src/main/resources/db/migration
  backend/apps/memories/src/main/resources/db/migration` → only `V202610172106__core_guest_write_record.sql`.
- **M15** `grep -rn -B3 'in: query' contracts/api | grep -oE 'name: [A-Za-z]+'` → ids, states, cursors and one boolean.
- **M16** `grep -rlE 'java.util.logging.(Handler|LogRecord)' backend/core/src/test backend/apps/memories/src/test` →
  among them `SmtpDeliveryLogTest`, `TenantInviteMailerLogTest`, `ModerationLogSliceTest`,
  `GuestWriteRecordCoreSliceTest`; `git grep -l 'recovery delivery' -- backend` → the handler only, no test.
- **M17** `grep -n 'def sweep_logs\|def sweep_containers\|def canary_needles' scripts/e2e_sweeps.py`; `grep -n pii
  scripts/e2e_sweeps.py` → only the database and statement sweeps' controls; no log needle for an address.

## Remembered (measure before it drives code)

- **R1** MariaDB's message for a duplicate key (1062) is `Duplicate entry '<value>' for key '<index>'`.
- **R3** Traefik v3 writes no access log without `--accesslog`, and its own log is ERROR by default.
- **R4** Garage at `info` prints `<X-Forwarded-For> (via <peer>)` when the header is present. Moot at `warn`; the Run
  page's troubleshooting warning assumes it.
- **R6** The kubelet's `containerLogMaxSize` is 10 MiB and `containerLogMaxFiles` is 5.
- **R7** nginx's error log at its default level writes `client: <addr>, … request: "<line>"` on an upstream failure.
- **R8** Quarkus REST hands an exception no mapper matches to the Vert.x failure handler, which writes M6's line; which
  of M6's two lines fires per path is the catch-all's bite test's to show.
- **R9** A dynamic IP address is personal data (CJEU C-582/14, *Breyer*); EU and German law put no IP-retention duty on
  a host.
