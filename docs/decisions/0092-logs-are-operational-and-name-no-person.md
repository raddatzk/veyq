# ADR 0092 — Logs are operational and name no person; evidence never rides a log

- Status: Accepted
- Built: yes
- Date: 2026-10-16
- Model: [cc.privacy.retention](../crosscutting/cc.privacy.retention.md)

## Context

veyq logs by default and had never decided what. The pieces grew one CR at a time, each careful locally — the admin
nginx format trimmed, the invite mailer tested never to log its token, every end-to-end run sweeping every log for
sealed values and tokens ([cc.security.wire-assertion](../crosscutting/cc.security.wire-assertion.md)) — but nothing
held the whole. Left alone, the backend echoed a client-supplied string and, on an unexpected error, the request URI
with its query; Hibernate logged the database driver's message, which for a unique violation names the value — an
email address; the rate limiters kept addresses and email addresses as heap keys until 100 000 were tracked; the
admin nginx logged the client address, the User-Agent and `X-Forwarded-For`; and the store, at `info`, logs every
request with its peer and query.

The evidence a report needs lives in the database — the guest-write record and the moderation log
([ADR 0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) §§ 10–11) — so no log
is needed for it. Which address a request has is
[ADR 0094](0094-a-clients-address-is-the-hop-the-network-vouches-for.md)'s.

## Decision

§§ 1–8 are the rule for every log veyq writes or its recipes configure, on both deployment targets.

### 1. Three kinds of data, never mixed

- **A log** is operational. It never carries a person's identifiers beyond the ids of § 3, it is never evidence, and
  its retention is the runtime's, bounded by size. This ADR owns it.
- **The guest-write record** is evidence: a database row with a retention the app enforces (ADR 0093 § 10).
- **The moderation log** is the record of what was reported and done about it (ADR 0093 § 11).

A log never holds a record's address or a moderation event, and a log may name a moderation case by its id and nothing
more. Built and held by `ModerationLogSliceTest` and `GuestWriteRecordCoreSliceTest`.

### 2. The guest-write record's retention period

The record is ADR 0093 § 10's; which address it takes is ADR 0094's. This ADR fixes the period:

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
  **is** emitted (the positive control) and does not carry the value, on one capture of the root logger
  (`LogCapture`, whose own control record proves it is attached). Built for every such site: the mail and invite
  lines, the recovery worker, the unknown wrap method by its length, Garage's admin errors by status and code.
- **A catch-all for unexpected errors**, one formatter (`UnexpectedError`) behind one Vert.x failure handler ordered
  just ahead of Quarkus's own, so no failure reaches the default handler's line. A JAX-RS resource and a Vert.x
  route both reach it: Quarkus REST hands an exception no mapper matches to the routing context's failure, and a
  request filter leaves the matched resource's template for it. **Not a JAX-RS mapper for `Throwable`**, as first
  planned: a mapper for a supertype of `NotFoundException` makes Quarkus REST answer an unmatched route itself instead
  of passing it on, which the app shell's SPA fallback and the admin channel's unknown-route refusal depend on, and it
  would receive every bodiless `WebApplicationException` the boundary's filters answer. It logs at ERROR a random
  error id, the method, the route **template** (`-` for a route that is no JAX-RS resource), the class chain, a
  SQLState and vendor code where a `SQLException` is in it, and the first frame in `me.raddatz.veyq`; never the URI,
  the query or any message. It answers 500 with the existing `ErrorResponse` (`internal_error`, the error id as
  `message`). The full exception goes to DEBUG under its own category, `me.raddatz.veyq.core.http.unexpected.detail`,
  off by default — lower case, so an environment variable can raise it. Held by
  `UnexpectedErrorLogSliceTest`.
- **Hibernate's statement error log is silenced**: `org.hibernate.orm.jdbc.error` is set to ERROR, so the WARN line
  that carries the driver's message is not written. The catch-all keeps the SQLState and vendor code.
- **Narayana's commit-failure warning is rewritten, not silenced**: a constraint violation raised at **commit** (not
  at an explicit flush) is logged once more by Narayana, as `ARJUNA012125` at WARN on `com.arjuna.ats.arjuna`, with
  the converted exception whose text carries the driver's message — a colliding email address. That record loses its
  exception and keeps the class chain, SQLState and vendor code (`CommitFailureLogFilter`, a filter on the logger, so
  every handler gets the rewritten record); the category is not silenced, because its other warnings — the
  transaction reaper's timeouts among them — pass unchanged.
- **The e2e sweep.** `scripts/e2e_sweeps.py` fails a run on any sealed value or token in any log, and on its third
  needle set, `pii`: every email address the run's suites signed up with, searched in every
  boot and container log and nowhere else, since the database dump, the statement log, the HTTP recording and the
  mailbox hold it by design. Its control: at least one address, and each found where the instance handled it — a mail
  or a database dump of the run on the API lane, the statement log on lane L1, which reads no mailbox. A forged client
  address is the slice tests', which capture the root logger and so every framework's lines too.
- **`validate_compose_posture.py` assertion 6**: every service bounded, the store below `info`, and the proxy with no
  access log, by flag or variable, and its own level, `ERROR`. The chart has no proxy; its store is
  `chart_meta_check.py` (xxvii).
- No static scan of log statements. A grep for `email` in a log call is easy to evade and would turn red on a comment.
  The tests above bite on the value instead, and for the same reason no `CLAUDE.md` line restates the rule.

### 5. Retention and rotation, per source and target

veyq bounds every log by size and keeps people out of them, so their age stops mattering for what veyq writes. The
period each source keeps is stated as its criterion — size, and a container's recreation, which every upgrade does —
because no container log driver offers a time bound on both targets. On Compose the recipe sets `json-file` with
`max-size: 10m` and `max-file: 3` on every service; on Helm the kubelet rotates. The store logs at `warn`, Traefik at
`ERROR` with no access log, and the admin nginx's access log keeps no client address, User-Agent or
`X-Forwarded-For`; its error log keeps nginx's default, which names the hop (loopback or the container bridge on the
recipe's binding). The rate limiters drop every full bucket each minute, so an address or an email address stays only
while its bucket refills. A gateway's or Traefik's access log, or a log shipper, is the operator's own processing, and
the Run docs say so. No log is in a backup veyq takes. The e2e stacks alone keep the store at `info`: the wire sweep's
per-run control is a key planted in the store's request log.

Each source's bound, beside every other datum's period, is [cc.privacy.retention](../crosscutting/cc.privacy.retention.md).

### 6. Legal basis and disclosure, for logs

Every log veyq writes, and the rate-limit buckets, rest on GDPR Art. 6(1)(f): operating and securing the service, and
defence against abuse. They are disclosed in the instance privacy notice
([ADR 0035](0035-server-terms-of-service.md) decision 8) and the Run area's record of processing. A gateway's access
log or a log shipper is the operator's own. A dynamic IP address is personal data and no law puts an IP-retention duty
on a host, which is why addresses stay out of the logs rather than being kept for a period; a Server Owner running
veyq for their household only may fall under the household exemption, which is their call. The research is the
[legal register](../crosscutting/legal-register.md) (L7, L9) — research, not legal advice; each source's basis is a row
of [cc.privacy.retention](../crosscutting/cc.privacy.retention.md).

#### Still open

Not pursued (owner 2026-10-28), as ADR 0093's Still open are: what is built and decided is taken to meet them, no row
tracks them and none holds back a CR or a release; an answer that differs, should one come, is a legal duty that wins
over what is built (`CLAUDE.md`).

1. Whether a period stated as a criterion — size-based rotation and recreation, no time bound — meets Art. 5(1)(e) for a
   log holding pseudonymous ids only.
2. Whether an operator who exposes the admin console must be told more than § 5 says about its error log naming the
   hop.

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
  reader's address. Its only consumer is the e2e sweep's control, which the e2e stack keeps.
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
- The Run and Security pages carry the rule for the reader:
  [Operations § Logs](../../site/src/content/docs/run/operations.md#logs),
  [Troubleshooting](../../site/src/content/docs/run/troubleshooting.md),
  [Data protection](../../site/src/content/docs/run/data-protection.md),
  [What the server sees](../../site/src/content/docs/security/what-the-server-sees.md) and
  [Verify it yourself](../../site/src/content/docs/security/verify-it-yourself.md).

## Measurement ledger

Only the entry code cites is kept.

- **M6** Quarkus 3.36.0 sources in the Gradle cache: `unzip -p …/quarkus-vertx-http-3.36.0-sources.jar
  io/quarkus/vertx/http/runtime/QuarkusErrorHandler.java | grep -n 'HTTP Request to'` → `:178`
  `log.errorf(exception, "HTTP Request to %s failed, error id: %s", event.request().uri(), uuid)`; `unzip -p
  …/resteasy-reactive-3.36.0-sources.jar org/jboss/resteasy/reactive/server/core/RuntimeExceptionMapper.java | grep -n
  'Request failed'` → `:119`, reached only when no mapper matches.
