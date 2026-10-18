---
id: cr-logging
title: No log names a person - unexpected errors, database messages, the remaining sites, the limiters' keys and the admin log
status: planned
satisfies: []
adrs: ["0092", "0093", "0094", "0035", "0064", "0057"]
invariants:
  - operator-blind - no log line carries a key, a token, a sealed value or plaintext metadata; this CR only removes what a log carries
  - Compose and Helm move in step - every change reaches both targets (one image for the app and the admin console), or the CR says why one has nothing to change
  - every negative assertion owes a positive control on the same capture - the line IS emitted, and the value is not in it
---

## Goal

Build the rest of [ADR 0092](../docs/decisions/0092-logs-are-operational-and-name-no-person.md). GDPR Art. 5(1)(c) and
(e) ask that a log hold no personal data beyond what operating the service needs, kept no longer than needed. The
legal audit of 2026-10-18 fixed the mail, invite and recovery lines and bounded every log by size (inbox #481, #482).
Still open: an unexpected error logs the request URI with its query and the exception's text, Hibernate logs the
database's message for every failed statement — on a colliding sign-up, the email address —, one site echoes client
input and one a remote body, the rate limiters keep client and email addresses in heap until a restart, and the admin
console logs its reader's address with no time bound. This CR closes each with a test that bites on the value, and moves
the Run and Security pages with it. By the owner rule that a legal duty wins, the duty is not open; the shape is.

## Context & constraints

- Decisions: ADR 0092 (all of it; the status line lists what is built); [ADR 0093](../docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
  §§ 10, 11 (the record and the moderation log, built — not this CR's); [ADR 0094](../docs/decisions/0094-a-clients-address-is-the-hop-the-network-vouches-for.md)
  (the address the limiters key on, built); [ADR 0035](../docs/decisions/0035-server-terms-of-service.md) decisions 3
  and 8; [ADR 0064](../docs/decisions/0064-kubernetes-helm-deployment-target.md) and [ADR 0057](../docs/decisions/0057-release-identity-and-deployment-recipe.md)
  (the two targets, the recipe in the image).
- **Legal stance.** ADR 0092 § 6 Still open is taken as met until veyq is offered as a service (owner 2026-10-18, the
  same stance as ADR 0093's Still open). Nothing here builds on a reading beyond that; this is research, not legal advice.
- **Neighbours.** `cr-instance-privacy-notice` writes the notice's log passage from what runs when it ships (its
  Context names this CR); the two split the work by page, this CR writing no notice text. `cr-api-fuzzing` asserts no
  5xx: the catch-all keeps a 500 a 500, so whichever lands first changes nothing for the other, and a fuzzing failure
  logs safely once this lands.
- **A test-scope resource that throws** is outside `RateLimitPostureSweepTest` and `DefaultDenyTest`'s reach by file
  location (the trust-chain skill's note on test-scope resources); keep it in `src/test`.

### Measured substrate (worktree `worktree-agent-a929a4319c05a1f59` at `427c68ac`, clean)

ADR 0092 § Measurement ledger holds M1–M17; this table holds the locators the build starts from.

| Fact | Locator |
|---|---|
| Every log call site, to re-read against ADR 0092 § 3 | `grep -rnE '(log\|Log)\.(info\|warn\|error\|debug)[fv]?\(' backend/core/src/main backend/apps/memories/src/main backend/src/main` |
| Sites that log a whole exception (its message and causes ride along) | `grep -rnE 'log\.(error\|warn)f?\((e,\|.*, e\))' backend/core/src/main` |
| No catch-all mapper exists | `git grep -nE 'ExceptionMapper<(Throwable\|Exception\|RuntimeException)>' -- 'backend/*/src/main' 'backend/src/main'` (empty); the narrow ones: `ls backend/core/src/main/kotlin/me/raddatz/veyq/core/auth/AuthExceptionMappers.kt backend/core/src/main/kotlin/me/raddatz/veyq/core/http/RequestRejectionMappers.kt` |
| The default handler's line, and RESTEasy's when no mapper matches | ADR 0092 M6 (Quarkus 3.36.0 sources: `QuarkusErrorHandler.java:178`, `RuntimeExceptionMapper.java:119`) |
| Non-REST routes the Vert.x failure handler must cover | `grep -rln '@Route\|Router\b' backend/core/src/main/kotlin` |
| `internal_error` already answered, and the envelope | `grep -rn '"internal_error"' backend/core/src/main`; `grep -n -A14 'ErrorResponse:' contracts/api/_common.openapi.yaml` |
| Hibernate's statement log and the unique index it names | ADR 0092 M7, M8; no prod log config: `git grep -nE 'quarkus\.log' -- 'backend/*.properties'` |
| The echo, the remote body, the untested recovery line | `grep -n 'unrecognised method' backend/core/src/main/kotlin/me/raddatz/veyq/core/account/keymaterial/AccountResource.kt`; `grep -n 'take(300)' backend/core/src/main/kotlin/me/raddatz/veyq/core/blobstore/GarageStoreProvisioner.kt`; `grep -n 'e.javaClass.simpleName' backend/core/src/main/kotlin/me/raddatz/veyq/core/account/recovery/RecoveryHandler.kt` |
| The existing log-capture tests to copy (handler, control) | `grep -rlE 'java.util.logging.(Handler\|LogRecord)' backend/core/src/test`; the Garage stub: `ls backend/core/src/test/kotlin/me/raddatz/veyq/core/blobstore/FakeGarageAdminApi.kt` |
| Every token-bucket counter, and the keys that are email addresses | `grep -rn 'TokenBucketCounter()' backend/core/src/main backend/apps/memories/src/main`; `grep -rn 'normalizeEmail' backend/core/src/main/kotlin/me/raddatz/veyq/core/*/*/*RateLimiter.kt backend/core/src/main/kotlin/me/raddatz/veyq/core/*/*RateLimiter.kt` |
| The eviction today | `grep -n 'MAX_TRACKED_KEYS\|removeIf' backend/core/src/main/kotlin/me/raddatz/veyq/core/ratelimit/TokenBucketCounter.kt` |
| The admin console's log format and its CSP test (the place for a format test) | `grep -n 'log_format' -A1 admin-web/nginx.conf.template`; `grep -n 'nginx.conf.template' admin-web/src/shared/security/csp.ts admin-web/src/shared/security/csp.test.ts` |
| Traefik's flags, and the posture guard's assertion 6 | `grep -n '"--' infra/docker-compose.prod.yml`; `sed -n 31,40p scripts/validate_compose_posture.py` |
| The chart has no proxy and its store is guarded | `ls charts/veyq/templates/`; `grep -n '(xxvii)' scripts/chart_meta_check.py` |
| The e2e sweeps: needle kinds, log surfaces, controls | `grep -n 'def canary_needles\|def sweep_logs\|def sweep_containers\|def sweep_mailbox\|def load_canaries' scripts/e2e_sweeps.py`; where suites write canaries: `grep -rn 'canary' web/src --include='*.e2e.test.ts' -l \| head` |
| Docs that speak for logs | `git grep -ln 'docs/decisions/0092' -- site/src/content/docs`; `grep -n 'access log' docs/decisions/0076-a-guest-write-proves-it-holds-the-links-key.md`; `grep -n 'records \*\*nothing\*\*' docs/decisions/0035-server-terms-of-service.md`; `grep -n 'MAX_TRACKED_KEYS' docs/crosscutting/cc.security.rate-limit.md`; `grep -n 'Server log' site/src/content/docs/security/verify-it-yourself.md`; `grep -n 'logs' site/src/content/docs/security/threat-model.md` |

## Decisions this CR takes

1. **One formatter, two entry points.** A JAX-RS `ExceptionMapper<Throwable>` and a Vert.x failure handler ordered
   ahead of Quarkus's own share one formatter: ERROR, a random error id, the method, the route template (the matched
   resource's `@Path` joined, or the Vert.x route's path pattern), the class chain, a `SQLException`'s SQLState and
   vendor code where one is in the chain, and the first stack frame in `me.raddatz.veyq`. Never the URI, the query, a
   header or any exception's message. The answer is 500 with the existing `ErrorResponse`: `code: internal_error`,
   `message: "error id <id>"`. The narrow mappers stay ahead of it (JAX-RS picks the most specific type).
2. **The full exception at DEBUG** under the category `me.raddatz.veyq.core.http.UnexpectedError.detail`, off by default
   (Q31). Raising it is the Run page's troubleshooting step, with Garage's "set it back".
3. **`quarkus.log.category."org.hibernate.orm.jdbc.error".level=ERROR`** in core's `application.properties`, so the
   WARN line carrying the driver's message is not written; its SQLState survives in decision 1's line.
4. **The remaining sites.** `AccountResource`'s unknown wrap logs the method's length and the account id; Garage's admin
   errors carry the status and Garage's error `code` when it matches `^[A-Za-z]{1,64}$`, never the body; the recovery
   worker's line gets its log-capture test (its code is already right). **Spared, by kind:** the sites that log a whole
   exception (the table's locator) keep it — they are purges, sweeps and setup steps whose failing calls bind ids,
   paths and store keys, never a person's value (ADR 0092 § 3's last paragraph); a site that starts binding one moves
   to the class chain.
5. **Time-based eviction in `TokenBucketCounter` itself**, so every counter gets it with no wiring at its users: every
   counter registers with one core sweeper (weakly held, so a counter that goes away is not kept), and a pass every
   minute drops every full bucket. The 100 000-key pass stays as the flood bound. No hashing of the keys: a key lives
   only while its bucket refills, and a hash key would sit in the same heap.
6. **The admin console's access log** keeps `[$time_local] "$request_method $veyq_request_path $server_protocol" $status
   $body_bytes_sent` and drops `$remote_addr`, `$http_user_agent` and `$http_x_forwarded_for` (Q29). Its error log
   keeps nginx's default.
7. **Traefik** gets `--log.level=ERROR` explicitly, and `validate_compose_posture.py` assertion 6 gains: the proxy's
   command sets no `--accesslog` flag (Q30). The chart has no proxy and nothing to change: its admin console is the same
   image, its store already guarded.
8. **The e2e sweep's email needle.** A new canary kind, `pii`, which the suites write for every address they sign up
   with; it is searched in every boot and container log (`canary_needles`' spellings) and nowhere else, since the
   mailbox, the database dump and the HTTP recording hold it by design. Its control: every `pii` canary is found in the
   mailbox the run read. Zero `pii` canaries is a failure, as zero logs is.
9. **No guard over log statements and no CLAUDE.md line** (Q33): the tests bite on the value.

## Open questions (owner)

Each has a recommendation; acceptance of ADR 0092 takes the recommendation unless the owner answers otherwise, and an
answer that differs is edited into the ADR's § 4 or § 5 and the decision above.

- **Q29 — The admin console's access log: drop the reader's address?** **Recommendation: drop the client address, the
  User-Agent and `X-Forwarded-For`.** On the recipe's loopback binding (Compose) and a port-forward (Helm) the address
  names the bridge or 127.0.0.1, never a person; where an operator exposes the console it names a person with no time
  bound, because rotation is by size and the console is quiet. The alternative is ADR 0092's earlier exception — keep
  it as the security log of the most privileged surface — which then needs a time bound neither target offers.
- **Q30 — A Traefik access log at all?** **Recommendation: none, held by the posture guard.** The catch-all's error id
  answers what a 5xx log would; any access log records every reader's share id, and a trimmed one (Traefik can drop
  `ClientHost`) still holds the paths. An operator who wants one turns it on as their own processing, which the Run page
  says.
- **Q31 — The full exception at DEBUG for troubleshooting?** **Recommendation: yes, under its own category, off by
  default.** Without it an unexpected error is diagnosable only by class chain and frame; with it an operator who raises
  the level knowingly logs messages that can name a person, the same trade Garage's `info` already makes on the Run page.
- **Q32 — Retention values.** **Recommendation: keep Compose at 10m × 3 and the kubelet's default, and state the period
  as its criterion** (size, and recreation at every upgrade; ADR 0092 § 5, Still open 1). With no person in a log a
  smaller bound buys nothing, and a time bound is the host's journald setting, not something the recipe can carry.
- **Q33 — A line in `CLAUDE.md`'s hard invariants on what a log holds?** ADR 0092's former inventory planned one.
  **Recommendation: no.** The rule binds through tests that fail on the value; a per-turn line restates the ADR.

## Phases

- [ ] **1 contracts — none.** The catch-all answers the existing `ErrorResponse` with `internal_error`, which the
  backend already answers; no operation, field or header changes. · Done when: nothing to do; the phase is recorded as
  skipped with this reason.
- [ ] **2 backend** — decisions 1–5. Order each live defect guard → red → fix → green. · Done when: `./gradlew check`
  green. **Bites, each with its control on the same capture (the root JUL logger, as `GuestWriteRecordCoreSliceTest`
  does):**
  - a test-scope JAX-RS resource throws `IllegalStateException` whose message holds `someone@example.org` and
    `203.0.113.77`, called with `?probe=q-marker`: red today on M6's line; green when the captured records hold none of
    the three and do hold one ERROR line with the route template and the error id the 500's `message` carries;
  - the same through a test-scope Vert.x route (the second entry point), so a handler wired in only one place is red;
  - a duplicate insert into `veyq_user` with an existing email inside a capture, through a test-scope resource: red today
    on Hibernate's WARN line (remembered: H2's unique-violation message names the value as MariaDB's does — the red step
    measures it; the suite runs on H2 unless `VEYQ_TEST_DB_KIND` says otherwise); green when no record holds the address
    and the catch-all's line holds the SQLState;
  - the DEBUG category raised in the test: the message is there (the control that decision 2 works), and absent at the
    default level;
  - an unknown wrap method `x\nsomeone@example.org`: the line is emitted with the method's length, without the value;
  - `FakeGarageAdminApi` answering 500 with a body holding a marker and a JSON `code`: the exception text and the
    retry line hold the status and the code, never the marker; a body with no matching code yields the status alone;
  - the recovery worker's DB-error path (the lever is the implementer's; a `QuarkusMock` of the store is acceptable): the
    line is emitted with the class name, without the address;
  - a counter on a fake clock: a full bucket is gone after one pass, a partly spent one stays, and a spend after the pass
    answers what it answered before it (the eviction changes no limit); bite by removing the registration.
- [ ] **3 infra and chart** — decisions 6 and 7. `admin-web/nginx.conf.template`'s format; a test beside `csp.test.ts`
  that reads the template and fails on `$remote_addr`, `$http_user_agent` or `$http_x_forwarded_for` in any `log_format`
  (control: it finds the `veyq` format); Traefik's `--log.level=ERROR`; assertion 6's new clause with its docstring
  fields. The chart changes nothing (state it in the commit body). · Done when: `npm run check` in `admin-web/` green;
  `python3 scripts/validate_compose_posture.py` green and bitten by snapshot-and-restore (an `--accesslog=true` flag
  added → red); `helm lint` and `python3 scripts/chart_meta_check.py` green.
- [ ] **4 e2e sweep** — decision 8 in `scripts/e2e_sweeps.py` and the suites' canary writers. · Done when: one full
  `scripts/run_web_e2e.py` run green, its log showing the `pii` count and the mailbox control met; bitten by a throwaway build whose
  `SmtpDelivery` line logs the recipient again → the run is red naming the log and the address. Never on the shared dev
  stack's containers. *Machine hint, for a run from a worktree: pin `VEYQ_INFRA_DIR` to the main checkout's `infra/` and
  `VEYQ_JAR` to this tree's jar (the orchestrator's `compose_up` note).*
- [ ] **5 docs** — ADR 0092's status → Accepted and built, its § 4 and § 5 marked built; `docs/crosscutting/security-model.md`
  § What is encrypted vs. server-visible gains one paragraph on logs (ids only, per ADR 0092 § 3); `cc.security.rate-limit.md`
  (time-based eviction, the email keys); `cc.security.wire-assertion.md` (the `pii` kind and its control); ADR 0076's
  access-log remark points at ADR 0092; ADR 0035 decision 3's "records nothing" names that its logs name no visitor
  either. Site, Run: `operations.md` § Logs (the admin row, Traefik's level, the catch-all's error id, the hold-reach
  sentence of ADR 0092 § 8, and the caveat this refine added about the two open paths removed), `troubleshooting.md`
  (an error id → raising the DEBUG category, and setting it back), `data-protection.md`'s Logs row (the period as its
  criterion). Site, Security: `what-the-server-sees.md` (a log's content), `verify-it-yourself.md` (the `pii` needle in
  the sensor table), `threat-model.md`'s passive-operator row if its wording on logs changes. Each page's `sources:`
  keeps ADR 0092. · Done when: every `scripts/validate_*.py` except `validate_guard_gaps.py` green, and `npm run check`
  in `site/` green.

## Definition of done

Every phase's Done-when; no log line of a run names an address or an email (phase 4's sweep green with its control
met); ADR 0092 Accepted with its status naming what is built; the docs site's pages above updated. Implementation waits
for the owner's go.

## Retrospective

_Filled at implementation._
