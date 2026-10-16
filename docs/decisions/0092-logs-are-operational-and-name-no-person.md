# ADR 0092 — Logs are operational and name no person; evidence never rides a log

- Status: Proposed 2026-10-16 (draft for refinement); nothing of it is built. The guest-write record, the hold, the
  report path and the moderation log belong to the moderation ADR,
  [ADR 0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md); which client
  address a request has belongs to [ADR 0094](0094-a-clients-address-is-the-hop-the-network-vouches-for.md). This
  ADR owns logs: what they may hold, how long, and how that is enforced.
- Date: 2026-10-16

Provenance: every factual claim in Context carries **(Mn)**, a command in § Measurement ledger re-run on this
worktree (branch `claude/veyq-logging-concept-756957` at `dfe912d3`, clean), or **(Rn)**, remembered from outside
the repo and listed in § Remembered, to be measured before it drives code.

## Context

veyq logs by default and has never decided what. The pieces grew one CR at a time, and each was careful
locally: the admin nginx format was trimmed (inbox #256), the invite mailer was tested never to log its token
(`TenantInviteMailerLogTest`), and every end-to-end run sweeps every log for sealed values and tokens
([cc.security.wire-assertion](../crosscutting/cc.security.wire-assertion.md)). No document says what any log may
hold, how long it is kept, on what legal basis, or how it relates to evidence of abuse.

### What is logged today

| Source | Goes to | Holds | Retention today |
|---|---|---|---|
| Backend app | stdout, plain text, INFO, no access log, no JSON, no request id (M1, M2) | operational lines; **the recipient address of a failed mail** (`TenantMailSender.kt:81`) and **the owner's address** on the invite copy-link fallback (`TenantInviteMailer.kt:71,81`) (M3); a client-supplied string echoed verbatim (`AccountResource.kt:59`) (M3); tenant, scope and domain ids; exception texts that may carry SQL values (R1) or up to 300 chars of a Garage admin response (M4) | the container runtime's (below) |
| Backend, unhandled exception | Quarkus default handler | the request URI with its query, e.g. `/api/shares/{shareId}/…?node=…` (R2); no catch-all mapper exists (M5) | same |
| Rate limiter | heap only | client IP as a bucket key; evicted only once 100 000 keys are tracked, so below that an IP stays until restart (M6) | process lifetime |
| Traefik (Compose) | stdout | its own errors only; **no access log** (M7, R3) | runtime's |
| Admin nginx (both targets, one image) | stdout | `$remote_addr`, time, method, path without query, status, size, User-Agent, X-Forwarded-For (M8); nginx error log names the whole request line, query included, on an upstream failure (cc.security.wire-assertion) | runtime's |
| Garage (both targets) | stdout, `RUST_LOG=garage=info` baked into the image (M9) | **every S3 and admin request**: peer address and port, method, URI with its query — presign signatures, object keys, admin key ids (M9); behind a proxy it may also print the forwarded client address (R4) | runtime's |
| MariaDB (both targets) | stdout | startup and errors; no general, slow or binary log configured (M10); 0 lines in an e2e window (cc.security.wire-assertion) | runtime's |
| Gateway implementation (Helm) | outside the chart | every reader: client IP, path with `shareId` (and `?node=` on a reload), UA — the operator's to configure (M11) | the cluster's |
| Container runtime (Compose) | no `logging:` block on any service (M12) | — | the daemon's default driver; on a stock Linux Docker that is `json-file` **without rotation** (R5); this Mac's daemon uses `local` (M9) |
| Container runtime (Helm) | the kubelet | — | the kubelet's rotation, 10 MiB × 5 files per container by default (R6) |

No table stores an IP or a User-Agent (M13). Tokens travel in headers, a cookie or bodies, never in a query or
path; `shareId` is a path segment and half the capability, the share key stays in the fragment (M14).

### What the moderation ADR (0093) decides, and this one assumes

- The app keeps a **guest-write record** (address and time) for every write that enters a guest as the last
  editor, and for nothing a member writes or anyone reads. The record lives in the database for its retention
  period (7 days by default, § 2) and is switched per instance through the capability register
  ([ADR 0088](0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md)).
- A **hold** is a snapshot: on a report the server copies the affected records into a hold table. The live record
  is always purged when its retention period ends, and the copy lives as long as the hold.
- A **moderation log** is append-only, with structured fields in the clear and sealed free text, and keeps its
  own retention.
- An access log, Traefik's or a gateway implementation's, is **not** the evidence: it records every reader, links to
  no object, sits outside a hold's reach and keeps its own retention.

When this ADR was drafted none of this was in the repo (`legal hold` had no hit, M15); ADR 0093 now decides it.
This ADR rests on it only where a log meets it:
§ 1, § 3's last row, § 4's slice test and § 8.

## Decision

### 1. Three kinds of data, never mixed

- **A log** is operational. It never carries a person's identifiers, it is never evidence, and its retention is
  the runtime's, bounded by size. This ADR owns it.
- **The guest-write record** is evidence: a database row with a retention the app enforces.
- **The moderation log** is the record of what was reported and done about it.

The moderation ADR owns the second and third. A log never holds a record's address or a moderation event, and
neither of them is ever written to a log line. A log may name a moderation case by its id, and nothing more.

### 2. The guest-write record, as far as logs and deployment touch it

The record itself (triggers, shape, switch, hold, disclosure) is the moderation ADR's. This ADR fixes two things
it depends on:

- **Which address is recorded is decided elsewhere.** Quarkus's forwarding takes the leftmost `X-Forwarded-For`
  entry and, with no `trusted-proxies`, trusts every peer (M18), so an address taken from it can be the one a
  client sent. A forged
  address in an evidence record can name an innocent third party. How veyq resolves a client address it can
  stand behind, on both targets and for the rate limiter too, is
  [ADR 0094](0094-a-clients-address-is-the-hop-the-network-vouches-for.md)'s; this ADR only requires that the
  record never takes an address a client can choose.
- **The retention period is an instance setting.** The Server Owner sets it in admin-web and its CLI, and it is
  stored in the database ([ADR 0089](0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md)
  decision 1; [ADR 0088](0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md) lists
  retention periods as parameters, not switches). It defaults to **7 days** and accepts **1 to 30 days**. The
  lower bound keeps "off" with the capability switch, where ADR 0088 puts it, not in a period of 0. The server refuses any value outside that range. The disclosure renders the value
  that is current when the guest writes.
- **A change never extends a promise already made.** A row is purged at the earlier of two times: its write time
  plus the period current at the write, and its write time plus the period current now. A shortening therefore
  applies to every row at the next purge. A lengthening applies only to rows written after it, so no guest is
  kept longer than the page told them. How the row carries its own limit is the moderation ADR's.
- **The purge also runs at startup**, so a restored backup's stale rows go at boot.

### 3. What a log may carry

| Never | Allowed |
|---|---|
| A key, a fragment, a token or anything derived from one (session, refresh, contributor, `X-Share-Write`, pwToken, invite and recovery secrets, a presigned URL's query) | ids: account, tenant, scope, node, share, blob, job |
| A password, a sealed value, plaintext of any metadata | a tenant's domain, a bucket name, an access key's **id** |
| An email address | an exception's class and a cause the app wrote |
| A network address or User-Agent, from the app (the admin nginx is § 5's exception) | counts, durations, attempt numbers |
| A request's input echoed verbatim | a known enum value; an unknown one by its length and account id |
| A guest-write record or a moderation event | a moderation case's id |

Share ids are allowed. A share id is half a capability whose other half never reaches the server, and the operator
holds every share id in the database anyway (`security-model.md:316`). An id is pseudonymous: it names a person
only through the database, which the operator holds under its own retention.

### 4. Enforcement

- **The e2e sweep, widened.** `scripts/run_web_e2e.py` already fails a run on any sealed value or token in any log
  (M17). It gains two needle sets: every email address the run's suites signed up with (exempt only in the
  mailbox), and a guest-write slice's forged client address.
- **A log-capture test per personal-data site**, after the `TenantInviteMailerLogTest` pattern: the failed-mail
  path, the copy-link fallback, the unknown-wrap warning (a crafted value with a newline and an email address).
  Each asserts the line **is** emitted (the positive control) and does not carry the value.
- **The guest-write slice test** makes a guest write from a known address (`203.0.113.77`) and asserts the address
  is in the record (the control) and in no captured log record. ADR 0094 § For the moderation CR sharpens it to a two-entry
  `X-Forwarded-For` whose forged leftmost entry must be in no record and no log.
- **A catch-all exception mapper** logs the exception and the route **template**, never the URI and never its
  query, and answers `internal_error`.
- **`validate_compose_posture.py` gains** a `logging:` block with `max-size` and `max-file` on every service, and
  `RUST_LOG` below `info` on the store. A Helm equivalent is a chart-render test of the store's `RUST_LOG`.
- No static scan of log statements. A grep for `email` in a log call is easy to evade and would turn red on a
  comment. The tests above bite on the value instead.

### 5. Retention and rotation, per source and target

| Source | Compose: veyq sets | Helm: veyq sets | veyq documents for the operator |
|---|---|---|---|
| App, admin nginx, MariaDB, Traefik | `json-file`, `max-size: 10m`, `max-file: 3` per service | nothing; the kubelet rotates (R6) | rotation is by size, not time; a log shipper's retention is theirs |
| Garage | `RUST_LOG=garage=warn`: no per-request log | the same env | raising it for troubleshooting logs every request with its address |
| Traefik access log | stays off | — | turning it on records every reader and is outside veyq's guarantees |
| Gateway implementation | — | nothing in the chart | the same as Traefik's access log, and the place to keep it short or off |
| Rate limiter | evicts a bucket once it is full again, regardless of the key count | same | — |
| Backups | — | — | the instance backup's dump leaves out the guest-write record's rows; it holds the hold and moderation tables, past their ends, for as long as the operator keeps it; a backup made otherwise holds the record too, for at most B days on the deletion concept of ADR 0093 § 10 |

The admin nginx keeps `$remote_addr` and the User-Agent. The admin surface is loopback-only by default, its
reader is the Server Owner, and who reached it is the security log of the most privileged surface.

The e2e stacks keep `garage=info`: the wire sweep's per-run control is a key planted in the store's request log
(cc.security.wire-assertion). The dev compose file and `infra/e2e/docker-compose.e2e.yml`
([ADR 0090](0090-end-to-end-tests-boot-the-release-candidate.md)) set it. The production recipe does not.

### 6. Legal basis and disclosure, for logs

**This is not legal advice.** It needs a review by someone qualified before the disclosure text ships (R9). The
guest-write record's basis and disclosure are the moderation ADR's.

| Data | Basis (GDPR Art. 6) | Disclosed in |
|---|---|---|
| App log (ids only) | (1)(f): operating and securing the service | Run docs; terms clause 2 |
| Admin nginx log | (1)(f): securing the administration surface | Run docs |
| Rate-limit buckets | (1)(f): heap only, never stored | terms clause 2 |
| A gateway implementation's or Traefik's access log, a log shipper | the operator's own | Run docs: "yours, not veyq's" |

A Server Owner running veyq only for their household may fall under the household exemption (Art. 2(2)(c)). That
is their call.

### 7. Structure and correlation

Logs stay plain text at INFO. JSON logging and a request id are deferred until something reads them. A request id
would be random per request and never derived from an address, an account or a token.

### 8. What a hold can reach

Only what the moderation ADR's snapshot copies out of the database. Every log, the store's request log, an ingress
log and a backup are **outside a hold's reach**, and the Run docs say so for each source. How a report is read is
the moderation ADR's; this ADR only guarantees that no log is needed for it.

## Rejected alternatives

- **An access log as the evidence.** Rejected by the moderation concept, and § 1 makes it structural. It records
  every reader, links to no object and keeps its own retention.
- **A static log-call guard in `scripts/`.** See § 4: it bites on spelling, not on the value.
- **Time-bounded log retention set by veyq.** No container log driver offers it on both targets. veyq bounds logs
  by size and keeps personal data out of them, so their age stops mattering.
- **Let the store keep its request log in production.** It is the one log that already records every object read,
  with presign signatures, and probably every reader's address (R4). Its only consumer is the e2e sweep's control,
  which the e2e stacks keep.

## Consequences

- An operator who reads a log learns nothing that names a person, except at the admin surface they reach
  themselves. That is now a tested property, no longer a habit.
- The failed-mail and copy-link lines lose the address. An operator looks the account up by id.
- The Garage request log goes quiet in production. A store problem is diagnosed by raising the level, which
  the troubleshooting page says.
- Backups are outside every retention veyq sets, except that the instance backup leaves the guest-write record's
  rows out (ADR 0093 § 10). The Run docs say so per table.

## Inventory for the CR (moves into the joint CR when written; not built)

The documentation that both ADRs change lands in one joint CR: `CLAUDE.md`'s invariant,
`docs/crosscutting/security-model.md`, [ADR 0035](0035-server-terms-of-service.md) decision 3, and the terms
template's clauses 2 and 7. What follows is this ADR's share.

**Docs**
- [ADR 0076](0076-a-guest-write-proves-it-holds-the-links-key.md): the access-log remark at `:20` points here.
- `docs/crosscutting/cc.security.wire-assertion.md`: the new needle sets; Garage's level differs between e2e and
  production.
- `docs/crosscutting/cc.security.rate-limit.md`: time-based eviction.
- `uc.sharing.share-contributor` `:47` ("from a database copy or a log, names no guest"): the log half stays true.
- Site, Run: `configuration.md` or `capabilities.mdx`, the retention setting and its range; `operations.md` § Logs becomes the per-source table of § 5 with hold reach; `troubleshooting.md` raises
  Garage's level; `backup-and-restore.md` backups are outside every retention.
- Site, Security: `what-the-server-sees.md` and `threat-model.md`, what a log holds; `known-limitations.md`
  backups and the household exemption.

**Code**
- Backend: the catch-all mapper; time-based bucket eviction in `TokenBucketCounter`; the record's purge at startup and its two-limit rule (§ 2).
- The retention setting: the Admin API contract and its range check, admin-web's instance settings and the CLI
  (ADR 0089); the disclosure reads the current value.
- Backend log sites: `TenantMailSender.kt:81` (account or tenant id and the SMTP reply code, not the address or
  `e.message`), `TenantInviteMailer.kt:71,81` (tenant id), `AccountResource.kt:59` (length and account id),
  `GarageStoreProvisioner.kt:182` (no response body in the exception text).
- Infra: an `x-logging` anchor on every service of `infra/docker-compose.prod.yml`; `RUST_LOG=garage=warn` on the
  store in Compose and Helm; `garage=info` kept in `infra/docker-compose.yml` and the e2e overlay.
- Guards and tests: § 4.

## Measurement ledger

Run from the worktree root unless noted.

- **M1** `grep -rnE 'quarkus\.(log|http\.access-log|hibernate-orm\.log|otel|micrometer)' backend --include='*.properties'`
  → only `application-dev.properties:40,46`.
- **M2** `grep -rniE '\bMDC\b|request-?id|trace-?id|traceparent|opentelemetry|micrometer' backend web/src contracts`
  → no logging hit.
- **M3** `grep -rnE '\b(log|Log)\.[a-z]+f?\(' backend/core/src/main` → 39 lines; `sed -n 76,83p …/TenantMailSender.kt`
  shows `log.warnf("SMTP delivery of %s to %s failed: %s", what, to, e.message)`; `sed -n 55,60p
  …/AccountResource.kt` shows `wrap.method` interpolated.
- **M4** `grep -n 'take(300)' backend/core/src/main/kotlin/me/raddatz/veyq/core/blobstore/GarageStoreProvisioner.kt`.
- **M5** `grep -rnE 'ExceptionMapper<Throwable>|ExceptionMapper<Exception>|Throwable::class' backend --include='*.kt'
  | grep /src/main/` → empty; the mappers that exist are narrow (`AuthExceptionMappers.kt`, `RequestRejectionMappers.kt`).
- **M6** `grep -n 'MAX_TRACKED_KEYS\|removeIf' backend/core/src/main/kotlin/me/raddatz/veyq/core/ratelimit/TokenBucketCounter.kt`
  → `:30,43,64`; `sed -n 76,84p …/RateLimitFilters.kt` → `remoteAddress()` as key.
- **M7** `grep -rniE 'accesslog|--log\.|forwardedHeaders|trustedIPs' infra charts` → empty.
- **M8** `sed -n 27,35p admin-web/nginx.conf.template`.
- **M9** `docker logs --tail 4000 veyq-garage | grep INFO` on this Mac's dev store → lines of the form
  `INFO garage_api_common::generic_server: [::ffff:192.168.65.1]:23927 GET /v2/GetKeyInfo?id=GK…`;
  `docker inspect veyq-garage --format '{{.Config.Env}}'` → `RUST_LOG=garage=info` (image default; `grep -rn
  RUST_LOG infra charts` is empty); `docker info --format '{{.LoggingDriver}}'` → `local`. Dev topology, direct
  peer, not the production one.
- **M10** `grep -rnE 'general[_-]log|slow[_-]query|log[_-]bin|binlog' infra charts; find . -name '*.cnf'` → empty.
- **M11** `grep -n 'trustedProxies' charts/veyq/values.yaml charts/veyq/templates/app-deployment.yaml` → empty
  default, rendered only when set; `grep -rn 'annotations' charts/veyq/values.yaml` → `{}`.
- **M12** `grep -rniE 'logging|max-size|max-file' infra/` → empty.
- **M13** `grep -rniE '\b(ip|ip_address|remote_addr|user_agent)\b' backend/*/src/main/resources/db/migration
  backend/apps/*/src/main/resources/db/migration` → empty; `awk '/CREATE TABLE veyq_share_identity/,/;/'
  backend/core/src/main/resources/db/migration/V202610041200__core_baseline.sql`.
- **M14** `grep -rn -E 'in: (query|cookie)' contracts` → six non-secret query params, no cookie params;
  `sed -n 26,28p web/src/core/sharing/shareLink.ts` → `#k=`; `grep -n 'node' web/src/core/sharing/guestAccess.ts`
  → `?node=<id>`.
- **M15** `grep -rniE 'legal.?hold' . --exclude-dir=node_modules --exclude-dir=.git` → empty;
  `sed -n 116,128p docs/crosscutting/security-model.md`; `sed -n 57,62p docs/decisions/0035-server-terms-of-service.md`;
  `sed -n 47,48p web/src/features/tenant/termsTemplate.ts`.
- **M17** `grep -n 'def sweep_logs\|def sweep_containers\|def token_needles' scripts/run_web_e2e.py`.

- **M18** Quarkus 3.36.0 sources (`backend/gradle/libs.versions.toml:6`; the `quarkus-vertx-http-3.36.0-sources.jar` in
  the Gradle cache): `ForwardingProxyOptions.java:50` `allowXForwarded().orElse(!allowForwarded)`, so `X-Forwarded-For`
  is honoured by default; `:61-62` with no `trusted-proxies` no check is built, and `ProxyConfig.java:146` documents
  "All proxy addresses are trusted"; `ForwardedParser.java:291` `parseFor(getFirstElement(forHeader), …)` and
  `:318-321` cut at the first comma, so the address is the leftmost entry.

## Remembered (measure before it drives code)

- **R1** A MariaDB constraint error's message carries the bound value; Hibernate's `SqlExceptionHelper` logs it.
- **R2** Quarkus's default handler logs `HTTP Request to <uri> failed, error id: …` with the query.
- **R3** Traefik v3 without `--accesslog` writes no access log; its own log is ERROR by default.
- **R4** Garage prints `<X-Forwarded-For> (via <peer>)` when the header is present. If true, the production store
  log behind Traefik records **every reader's address** with the object key. Measure on the release-candidate
  stack (ADR 0090).
- **R5** Docker's default driver on Linux is `json-file` with no `max-size`, so it grows without bound.
- **R6** The kubelet's `containerLogMaxSize` is 10 MiB and `containerLogMaxFiles` is 5.
- **R9** A dynamic IP address is personal data (CJEU C-582/14, *Breyer*). EU and German law put no IP-retention
  duty on a host. A short retention for abuse defence can rest on Art. 6(1)(f). Legal review, not this ADR, settles
  this.
