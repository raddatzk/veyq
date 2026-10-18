---
id: cr-api-fuzzing
title: The API is fuzzed from its contracts on every commit — Schemathesis against the e2e fast-jar
status: in-progress
satisfies: []
adrs: ["0091", "0100", "0090", "0057", "0042"]
invariants:
  - operator-blind — the run only reads what any client may; a refusal echoes nothing it was sent (developer styleguide §7)
  - self-hostable — the tool is the MIT OSS image pinned by digest; no hosted service, no account, no telemetry
  - API-first — the contracts are the oracle; a finding is fixed in the contract or the code, never by weakening the check
---

## Goal

Every operation in `contracts/api/**/*.openapi.yaml` gets requests nobody wrote by hand, on every commit:
[Schemathesis](https://schemathesis.readthedocs.io/) generates them from the contracts and sends them to a live
backend. A run is red when an operation answers 5xx, answers a status it does not declare, answers a body outside its
declared schema, accepts a request its contract forbids, or echoes back what it refused. This closes the gaps
`scripts/validate_request_bounds.py` names in its own docstring (*"It does not see path/query/header **parameters**
… no test sends an out-of-range one), **response** schemas"*) and the absent 5xx hunt. Decided in
[ADR 0091](../docs/decisions/0091-one-pipeline-per-commit-a-release-promotes-a-tested-snapshot.md) decision 9
(the stage, its target, its first slice, the baseline rules) and pointed to from
[ADR 0100](../docs/decisions/0100-pentests-attack-a-reset-candidate-locally-and-publish-every-runs-record.md)
decision 2 (the pentests reuse this harness against the candidate).

First slice (ADR 0091 decision 9): **negative mode on all operations** and **positive mode on every `GET`** with seeded
ids. Positive writes, stateful sequences and the second-identity check (`object_level_authorization`) are a later CR
(Q3).

## Context & constraints

### Measured at refine (2026-10-18, worktree at `975c1ba2`)

- **Schemathesis 4.32.0** is current (PyPI, uploaded 2026-10-18; 12 releases since 2026-09-19), MIT, *Production/
  Stable*, repository not archived and pushed 2026-10-18 — `curl -s https://pypi.org/pypi/schemathesis/json`,
  `curl -s https://api.github.com/repos/schemathesis/schemathesis` (`archived: false`). **`requires_python >=3.11`**
  (Python 3.9 support was removed in 4.5.0, 3.10 in 4.31.0 — its CHANGELOG). The project's website advertises no paid
  plan, and the installed package carries no client for one: `grep -rl -i 'telemetry\|schemathesis\.io' <venv>/…/schemathesis`
  → no hit. We use only the CLI.
- **The image** `schemathesis/schemathesis` publishes `v<version>` tags as multi-arch indexes (amd64 + arm64) —
  `curl -s 'https://hub.docker.com/v2/repositories/schemathesis/schemathesis/tags?page_size=15'`. `4.32.0` →
  `sha256:c4935c2c…a7a935f5` at refine; **the pin is resolved again at implement time** with
  `docker buildx imagetools inspect schemathesis/schemathesis:<tag>` (the index digest, both architectures — the
  check `validate_image_pins.py`'s docstring says it cannot do itself).
- **The specs load as they are.** In a throwaway venv (Python 3.13, `schemathesis==4.32.0`) every one of the 17 spec
  files loaded with `schemathesis.openapi.from_path`, cross-file `$ref`s, OpenAPI 3.1 and the `x-veyq-*` extensions
  included; **227 operations**, positive and negative cases generated offline for all of them. Three operations
  (`GET /api/moderation/key`, `GET /api/operator`, `GET /api/setup`) have no negative case: no parameter and no body,
  nothing to negate — Schemathesis skips them in that mode, correctly. Second angle on the count, keyed on the YAML:
  ```
  python3 -c "import sys,yaml;sys.path.insert(0,'scripts');import contract_specs as c
  ops=[(m,op) for p in c.specs() for it in (yaml.safe_load(p.read_text()).get('paths') or {}).values() for m,op in it.items() if m in ('get','put','post','delete','patch')]
  print(len(ops), sum(op.get('x-veyq-channel')=='admin' for m,op in ops), sum(m=='get' for m,op in ops), sum(m=='get' and op.get('x-veyq-channel')=='admin' for m,op in ops))"
  ```
  → `227 53 64 15` (operations, admin-marked, `GET`s, admin `GET`s). No bundler is needed and none exists
  (`web/package.json`'s `generate:api` feeds the generator one spec at a time too).
- **Negative cases reach parameters**, and each case records which part was negated: for `GET /api/shares/{shareId}`
  a drawn case reads `{'path': 'negative', 'header': 'positive'}` (`case.meta.components`). That is what lets a
  custom check demand a status per location (phase 1).
- **The parameters with a bound**, the set the done-when is about (path ids `format: uuid` on 22 names; `shareId`,
  `capabilityId`, `requirement` as patterned/length-bound path strings; headers `X-Share-Password`, `X-Share-Write`,
  `X-Veyq-Moderation-Challenge`/`-Signature`, `X-Contributor-Token`, `If-None-Match`; query `kind`, `state` ×2,
  `sinceDelete`, `since`, `shareId`, `nodeId`, `parentId`, `targetId`). Locator — every parameter, resolved through
  `$ref`, with its schema's bounds:
  ```
  python3 - <<'EOF'
  import sys, yaml, posixpath; sys.path.insert(0, "scripts"); import contract_specs as c
  def res(k, n):
      while isinstance(n, dict) and "$ref" in n:
          t, _, ptr = n["$ref"].partition("#"); k = c.ref_key(k, t) if t else k; n = c.load(k)
          for part in ptr.strip("/").split("/"): n = n[part]
      return k, n
  seen = set()
  for p in c.specs():
      k = c.key(p)
      for it in (c.load(k).get("paths") or {}).values():
          for m, op in it.items():
              if m not in ("get","put","post","delete","patch"): continue
              for prm in it.get("parameters", []) + op.get("parameters", []):
                  k2, rp = res(k, prm); _, s = res(k2, rp.get("schema", {}))
                  seen.add((rp["in"], rp["name"], str({b: s[b] for b in ("pattern","maxLength","minLength","minimum","maximum","format","enum") if b in s})))
  print("\n".join(" ".join(x) for x in sorted(seen)))
  EOF
  ```
  The generator puts those bounds on the parameters (`useBeanValidation` in
  `backend/buildSrc/src/main/kotlin/me/raddatz/veyq/buildlogic/OpenApiTasks.kt:95`; the generated interfaces carry
  e.g. `@PathParam("shareId") @Pattern(...) @Size(min=43…` and `@HeaderParam("If-None-Match") @Size(max=64)` —
  measured on the main checkout's `backend/core/build/generated`, built at an unknown commit). **That they take
  effect is exactly what no test shows today.**
- **Auth.** One security scheme, `bearerAuth` (`http`/`bearer`), on 195 operations; 30 carry no `security` key and 2
  `security: []` (sign-in, setup, the guest share routes, operator). `X-Share-Password`, `X-Share-Write` and the
  moderation challenge/signature are **header parameters, not schemes** (`contracts/api/sharing.openapi.yaml`
  `parameters/SharePassword…`), so Schemathesis generates them like any parameter — without a valid one a guest route
  answers its non-enumerable 404, which is the point of a negative run. The access token is `Authorization: Bearer`
  (`web/scripts/apiTransport.mts:86`) and lives **15 minutes** (`SessionService.kt` `ACCESS_TTL_MINUTES = 15L`). A
  sign-in is client-side Argon2id + a signature (`web/scripts/bootstrap-user.mts:147-151`), so Schemathesis cannot do
  it; **no script prints a token today** (`grep -rln accessToken web/scripts e2e/src` → `apiTransport.mts`,
  `bootstrap-user.mts`, neither prints it).
- **Channels.** `x-veyq-channel: admin` is a contract extension, not a header: those 53 operations answer only on the
  admin listener (`veyq.admin-channel.port=8090`, `backend/core/src/main/resources/application.properties:29`) and
  404 on the public port. The listener binds the main listener's `quarkus.http.host`
  (`AdminListener.kt:47,57`). Schemathesis selects them by `--include-by '/x-veyq-channel == "admin"'` (and
  `--exclude-by` for the rest) — tested offline: the expression selects all 42 operations of
  `instance.openapi.yaml`. Tenant-scoped calls resolve the tenant from `Host` (`apiTransport.mts:7-9`; the e2e
  harness uses `veyq.localhost`, `run_web_e2e.py:174`).
- **Rate limits.** Production answers 429 at 60 auth requests per IP per minute (`application.properties:154`);
  `%e2e` raises every limiter to 1 000 000 (`backend/src/main/resources/application-e2e.properties:62-70`), and
  `run_web_e2e.py`'s `boot()` starts the jar with `-Dquarkus.profile=e2e` (`run_web_e2e.py:490-522`). This is why the
  target is the e2e jar and not lane L1 (ADR 0091 decision 9).
- **Status rules a fuzzer must be told** (each a SETTLED decision, its pointer):
  - a path id that is no UUID matches no route → **404** — `IdBoundarySliceTest.aPathIdThatIsNoUuidIsNeverAServerError`
    (`backend/core/src/test/kotlin/me/raddatz/veyq/core/http/IdBoundarySliceTest.kt:43-58`);
  - a refusal for shape → **`400 ErrorResponse{code: invalid_request}`**, nothing echoed —
    `MalformedRequestSliceTest` (`backend/core/src/test/kotlin/me/raddatz/veyq/core/http/MalformedRequestSliceTest.kt:20-33`),
    developer styleguide §7 (`docs/crosscutting/developer-styleguide.md:162`);
  - membership-gated absence → **404**, uniform (`docs/crosscutting/cc.security.authorization.md:96-99`); every 401 is
    the same generic `authentication_failed` (`AuthExceptionMappers.kt`).
  - Error bodies are `ErrorResponse` (`contracts/api/_common.openapi.yaml:519-535`), not `problem+json`.
  - **Declared responses** across all specs: 400 ×107, 401 ×197, 403 ×177, 404 ×153, 409 ×96, 410 ×3, 422 ×20,
    429 ×23, 451 ×5, 503 ×13; **405, 406, 413, 415, 500 never** —
    `grep -rhoE "^ +['\"]?[45][0-9][0-9]['\"]?:" contracts/api | tr -d " '\":" | sort | uniq -c`. So the first run will name
    every operation that answers a status it does not declare — expect undeclared 404s, and Quarkus' own 405/415 for
    the coverage phase's unexpected methods and content types. Each is a decision in phase 3, not a mute.
- **Python.** The scripts run on Python 3.9 (`release_assets.py:48`; the development Mac's system `python3` is 3.9.6) and CI's
  `ubuntu-latest` `python3` (no `setup-python` in any workflow: `grep -rn setup-python .github/workflows` → none).
  The one third-party module the scripts use, PyYAML, is installed unpinned per job (`ci.yml` `pip install --quiet
  pyyaml`); there is no `requirements*.txt` and no hash-pinned Python dependency. Hence the image (ADR 0091
  decision 9, "Rejected: Schemathesis as a Python library on the host").
- **Budget.** `web-e2e` ran 13.8 of its 15 minutes in its last local run (ADR 0091 § Consequences), so the fuzzing
  is its own job (`api-fuzz`, 15 min, ADR 0091 decision 1's table). How long a run takes is **not measured**; phase 1
  measures it.

### Schemathesis facts the plan relies on (4.32.0, read from the installed package)

- `schemathesis run <spec-file> --url <base>`; `--mode negative|positive`, `--phases examples,coverage,fuzzing,stateful`,
  `--max-examples`, `--seed`, `--generation-deterministic`, `--generation-database none`, `--workers`,
  `--checks …`, `--include-by/--exclude-by <json-pointer expr>`, `--include-method`, `-H`, `--report junit,ndjson,vcr`,
  `--report-dir`, `--baseline PATH` (`schemathesis run --help`).
- Config file `schemathesis.toml`: `[checks.<name>] expected-statuses = […]`, `[parameters] "path.scopeId" = …` with
  `${ENV}` substitution, `[[operations]]` blocks, `hooks = "<module>"` for custom checks. **Fixed parameters override
  generation**, so the negative run and the seeded positive run are separate invocations.
- Default `negative_data_rejection` accepts `400 401 403 404 405 406 409 415 422 428 429 5xx`
  (`schemathesis/config/_checks.py:14-27`) — far wider than veyq's rule; this CR narrows it.
- **Baseline file** (`--baseline`, since 4.27.0): JSON, `format_version`, `entries: [{operation, check, failure,
  signature, expires?, …}]`; *"Only `expires` means anything to Schemathesis; the rest is yours"*
  (`schemathesis/baseline/model.py:33`), and unknown keys survive a `--baseline-update` (`:76,87`). So a `reason`
  per entry is first-class.

### Constraints

- Never the developer's dev stack or IntelliJ backend: `run_web_e2e.py`'s compose stack is the developer's one
  compose project (its docstring; `run_pipeline.py:39-44`). Run from a worktree only as that runner allows.
- Never weaken what ships to make the run fit (ADR 0091 decision 1): no lowered Argon2, no disabled check. The
  examples count is a sampling knob, set from phase 1's measurement and written down.
- **The existing local checks this stage meets** (§ *a new universal check meets every existing local one*):
  `IdBoundarySliceTest` (path id → 404), `MalformedRequestSliceTest` (400 shape, no echo), `OperatorBlindProbeTest`
  (every operation × stranger/proposal/suspended/deleting: `(status, code)`), `RateLimitPostureSweepTest`. The fuzz
  rules must agree with each; where they seem not to, the test is the settled rule and the fuzz configuration bends.
- `satisfies: []` on purpose: the stage verifies the contract boundary of every use-case at once and is no
  use-case's acceptance criterion; a coverage ref onto a fuzz run would claim a criterion it does not name.

## Owner questions (each with a recommendation)

**Decided (Q1–Q3).** Owner 2026-10-18: all three as recommended; implementation has the owner's go.

- **Q1 — A malformed *patterned* path segment (`shareId`, `capabilityId`, `requirement`): `400` or `404`?** A
  non-UUID path id is 404 by routing (settled, `IdBoundarySliceTest`); a patterned string segment reaches bean
  validation and most likely answers 400 today (not measured). **Recommendation: accept both for a path segment, 400
  only for query, header and body** — the format is public, so neither leaks anything, and forcing one would be a
  backend change with no security gain. ADR 0091 decision 9 is written this way; a different answer edits it.
- **Q2 — Findings of the first run: all fixed in this CR, or some deferred?** **Recommendation:** every 5xx, every
  undeclared status and every schema mismatch is fixed here (contract or code); a behaviour change that is its own
  feature-sized work may be **deferred** with a baseline entry whose `reason` names a new inbox row — never a 5xx, and
  never by `expires` (a gate turns red on a commit, not on a date). If phase 3's list is longer than this CR can carry,
  the CR stops after phase 3's triage and asks again, with the list.
- **Q3 — Slice scope.** **Recommendation:** as ADR 0091 decision 9 — negative everywhere, positive `GET`s only. Positive
  writes, stateful link sequences, and `object_level_authorization` (a second identity) are one follow-up row
  (inbox #486 names it), because each needs a decision about what a run may change and which identity owns what.

**Raised (Q4–Q8) by phase 3's triage, 2026-10-18** — decided below. The first run's list (§ Phases, phase 3's triage) is
longer than this CR can carry under Q2: four of its mechanisms are decisions about the wire or about the check, not
fixes. Phases 1 and 2 are done and the 5xx class is fixed; the CR stops here and asks (Q2's own rule).

- **Q4 — `405` and `OPTIONS` (mechanism C, 321 of the run's failures).** Every path answers `TRACE` with a bare `405`
  and no `Allow` header (RFC 9110 requires one), an undeclared method on four paths answers `404` or `400` instead of
  `405`, and `OPTIONS` answers `200` with an `Allow` listing the resource class's methods rather than the path's.
  Making every path answer `405 ErrorResponse` with its own `Allow`, and `OPTIONS` with the declared methods, needs a
  per-path method index the backend does not have today (Quarkus REST builds one; nothing exposes it) — feature-sized.
  **Recommendation: defer to a new inbox row, recorded as baseline entries** (one per operation and check, written by
  `--baseline-update`, each `reason` naming the row): the guard holds them, `--baseline-prune` empties them the day the
  row lands, and every other check stays live on those operations. The alternative — `unsupported_method` and
  `allow_header_conformance` switched off in `schemathesis.toml` with the row in a comment — is a two-line diff, but no
  guard sees it and someone has to remember to switch them back on.
- **Q5 — Request bodies and parameters of the wrong JSON type are accepted (mechanisms F and FMT, ~50 cases).**
  Jackson coerces a scalar of the wrong type into the declared one (a number into a string field, `0` into an
  `Instant` — 1970, then a domain refusal —, an integer into a boolean); a `format: uuid` or `date-time` body field
  typed `String` is never checked; JAX-RS converts a query `boolean` leniently (`revokeLinks=5` is `false`). Each is a
  request the contract forbids, answered past the boundary (200, 204, 404, 409 …). **Recommendation: fix here** — one
  strict coercion config beside `NullElementsRefused` (every scalar coercion fails; the generated clients never send
  one), the uuid/date-time fields typed or patterned, strict parameter converters — each mechanism with its case in
  `MalformedRequestSliceTest`, plus a `run_pipeline.py --job web-e2e` run, because the e2e harness' hand-built calls are
  the one caller that could lean on a coercion.
- **Q6 — A malformed credential is refused as a credential, before its shape (mechanism G1, ~32 cases).** The
  moderation proof headers (`X-Veyq-Moderation-Challenge`/`-Signature`) answer `403 moderation_proof_invalid` for a
  malformed value or for extra headers, the confirmation codes `403 …_code_invalid`, a body-less pairing call or a
  refresh token of the wrong type `401`, `DELETE /api/sessions/{id}` with a non-UUID id `401`: authentication runs
  before shape validation, and the check demands `400`/`404`. **Recommendation: keep the check and name the credential
  inputs** — a negated *credential* (a parameter or body field the contract marks as one) may be refused with the
  operation's declared `401`/`403`: an auth-first refusal leaks nothing, and moving shape validation in front of
  authentication would answer an unauthenticated caller about the request's shape. A narrowing of
  `refusal_by_location` the contract drives, not a mute; the marker (an `x-veyq-*` extension on those parameters and
  fields) is part of the decision.
- **Q7 — `null` where the schema says absent (mechanism D, 4 positive `GET`s).** `GET /api/setup`, `GET
  /api/tenants/{tenantId}/capabilities`, `GET /api/scopes` and `GET /api/scopes/my-tenants` serialize an optional,
  non-nullable property (`Capability.addConsent`/`linkPassword`/`linkMaxDays`, `CompromiseRotation`) as `null`.
  **Recommendation: the contract follows the wire** — those properties become `[T, "null"]`, so the generated
  TypeScript type says what the client already receives, rather than a global "omit nulls", which would also drop the
  required-nullable fields (`parentId: null` is the scope root). Only the schemas the positive run reached are known:
  the 18 `GET`s that never answered 2xx went unread.
- **Q8 — TraceCov in the official image (information; a decision only if the answer is no).** The
  `schemathesis/schemathesis` image is MIT Schemathesis plus **TraceCov**, a proprietary, binary-only coverage plugin
  ("TraceCov Community License", Schemathesis.io s.r.o.) its Dockerfile loads on every run unless
  `SCHEMATHESIS_COVERAGE=false`. The runner switches it off and `hooks.py` refuses to load if it was imported anyway, so
  only the MIT tool runs. **Recommendation: keep the official image this way.** The alternative — an image of our own
  from the PyPI package, where TraceCov is no dependency — adds a Dockerfile, a pinned base image and a hash-pinned
  Python dependency set, which ADR 0091 decision 9 chose the image to avoid.

**Decided (Q4–Q8).** Owner 2026-10-18:
- **Q4** as recommended: 405/`Allow`/`OPTIONS` is deferred — baseline entries with a `reason` each and a new open inbox
  row; no check is switched off.
- **Q5** as recommended: fixed here — strict JSON type coercion (Jackson config), formats strict, one test per mechanism.
- **Q6** as recommended: `401`/`403` is a correct refusal of a negated value of a parameter the contract marks as a
  credential (an `x-veyq-*` extension); the check is narrowed by that marker, not muted.
- **Q7 differs from the recommendation: the backend OMITS null fields** — Jackson `non_null` serialization inclusion,
  chosen knowing it is global. Before switching, measure what relies on an explicit `null` in a response (web and
  admin-web checks `=== null`/`!== null` on response fields, the generated TypeScript types, e2e and L1 specs, the admin
  CLI's output) and adjust contracts (such a field becomes optional, not `[T, "null"]`) and clients so that nothing
  changes behaviour. If the measurement finds a response where absent and `null` must mean different things, stop and
  report it.
- **Q8 differs: our OWN image** — a small Dockerfile under `infra/fuzz/` (a Python slim base pinned by digest,
  Schemathesis from PyPI with hash-pinned requirements, MIT), no TraceCov; the official image's pin goes, the hook that
  aborts if TraceCov is ever loaded stays. Recorded in ADR 0091 decision 9 and in the image-pin guard.

## Phases

- [x] **1 harness — the run exists, measures itself, and can go red.**
  - `infra/fuzz/schemathesis.toml` — the checks (below), `negative_data_rejection.expected-statuses = ["400", "404"]`
    (the per-location rule is the custom check's), `generation.database = "none"`; `[parameters]` for the seeded ids
    only in a profile/section the positive invocation uses. `infra/fuzz/hooks.py` — custom checks, loaded by `hooks`:
    1. **`refusal_by_location`**: for a negative case (`case.meta.components`), a negated query, header or body must
       answer `400` with `ErrorResponse.code == "invalid_request"`; a negated path, `400` or `404` (Q1).
    2. **`refusal_echoes_nothing`**: no 4xx body contains a generated path, query or header value of length ≥ 6
       (MalformedRequestSliceTest's `assertRefused` rule, applied to every operation).
    Keep `hooks.py` importable as Python-3.9 syntax (`from __future__ import annotations`): the repo's tooling reads
    tracked `*.py` with 3.9.
  - `infra/fuzz/baseline.json` — `{"format_version": …, "entries": []}`, written by the pinned version itself
    (`--baseline-update` on an empty run) so the format version is the tool's.
  - **Checks enabled:** `not_a_server_error`, `status_code_conformance`, `content_type_conformance`,
    `response_schema_conformance`, `response_headers_conformance`, `negative_data_rejection`,
    `missing_required_header`, `unsupported_method`, `allow_header_conformance`, `ignored_auth`, and the two custom
    ones. Off, named with why in the config: `positive_data_acceptance` (no positive writes in this slice),
    `use_after_free`, `ensure_resource_availability` (stateful), `object_level_authorization` (Q3).
  - `web/scripts/fuzz-seed.mts` — births a fresh instance with the real client crypto (the Server Owner on the admin
    channel, a tenant on `veyq.localhost`, the owner accepted and signed in) and writes to the run directory, mode
    `0600`, never stdout: the credentials, a fresh access token, and the ids the positive `GET`s use (tenant, the
    owner's spaces, a folder node — whatever the locator below names that is cheap to create). A `--login` mode
    signs in again and rewrites only the token. Built on `web/scripts/apiTransport.mts` and the steps of
    `bootstrap-user.mts` — **factored into a module both import** where they share more than a few lines, never
    copied (one cold birth, ADR 0065). Ids the positive run needs: the parameter locator above with its method filter
    narrowed to `("get",)` and its output to `in == "path"`.
  - `scripts/run_api_fuzz.py` (Python 3.9, not a `validate_*`): imports `run_web_e2e`'s primitives (`compose_up`,
    `provision_store`, `fresh_database`, `boot`, `kill`, as `validate_e2e_discovery.py` imports `discover`) — boots
    one fresh `%e2e` jar, runs the seeder, then **four invocations** of the image — {public, admin} × {negative on all
    operations, positive on `GET`s with the seeded ids} — each per spec file found by `contract_specs.specs()`, never
    listed, each after a fresh `--login` (the token lives 15 minutes). The image is a module-level constant
    `SCHEMATHESIS_IMAGE = "schemathesis/schemathesis:<tag>@sha256:<index>"` (the shape `validate_image_pins.py`
    finds). Networking: `docker run --rm --add-host veyq.localhost:host-gateway --add-host
    veyq-admin.internal:host-gateway`, the repo mounted read-only, the run directory read-write; `--url
    http://veyq.localhost:<port>` / `http://veyq-admin.internal:<admin-port>`. Measure first that the jar's listeners
    accept the bridge (the boot log's `admin listener on %s:%d` line names the host); this is the one step that differs
    between Docker Desktop and a Linux runner, and it must be the same command on both. The seed is a constant in
    the runner (`--seed`), with `--generation-deterministic`.
  - Output: `.fuzz-out/<stamp>/` (gitignored like `.e2e-out/`) with each invocation's JUnit, NDJSON and VCR report,
    the boot log, the seed record — and a summary: per invocation the operations tested, failures by check and
    operation with Schemathesis' `curl` reproduction line, and **the `GET` operations that never answered 2xx**
    (information: where response schemas went unchecked for lack of a seeded id). **Red when** any failure is not in
    the baseline, any invocation exits non-zero, **or an invocation tested no operation** (§ *a bite test must tell
    "the guard held" apart from "nothing ran"*: read the operation count from the report, not the exit code).
  - Run it once on the untouched backend and keep the list — that list *is* phase 3's input. Time it: total and per
    invocation; set `--max-examples` from it so the job fits 15 minutes with the jar build, and write the measured
    numbers into the runner's docstring.
  - **Bites** (each on a detached worktree of `HEAD`, `git worktree add --detach`, never the shared tree; each must
    go red **by name** — the check and the operation in the summary — not by "exit 1"):
    1. *parameter bounds take effect*: `"useBeanValidation" to "false"` in `OpenApiTasks.kt` → `refusal_by_location`
       / `negative_data_rejection` red on a header or query bound (e.g. `X-Share-Password`, `If-None-Match`).
    2. *5xx*: `throw IllegalStateException("bite")` as the first statement of one `GET` handler → `not_a_server_error`
       red on that operation.
    3. *echo*: the mapper in `RequestRejectionMappers.kt` that refuses a bean-validation violation writes the
       violation's invalid value into `ErrorResponse.message` → `refusal_echoes_nothing` red.
    4. *nothing ran*: an `--include-by` that matches no operation → the runner red with "tested no operation".
  - Done when: the runner executes all four invocations against a fresh jar and reports a tested-operation count of
    227 across them minus the three negative-less operations in the negative half (count from the reports); the four
    bites went red by name and green again after restore; the measured times are in the docstring.

  - **Built (2026-10-18)**, as planned, with what implementation measured. The image is pinned at the index digest
    the refine resolved (`sha256:c4935c2c…a7a935f5`, both architectures, re-resolved with `docker buildx imagetools
    inspect`; upstream's `.github/workflows/release.yml` publishes it); its bundled TraceCov is switched off (Q8). A
    negative case that only drops the bearer token is not generated (`with-security-parameters = false`):
    `ignored_auth` holds the `401`. Schemathesis ORs its inclusions, so the positive invocations exclude every method
    but `GET` (`--exclude-method-regex`): an `--include-method GET` beside the admin channel's `--include-by` selected
    all 59 admin-spec operations in the first run. The seeded tenant mails through Mailpit, or the three operations
    that mail answer `503 mail_unavailable`. The seed has no node, medium, share or moderation case, so the 18 `GET`s
    below one are listed as "never answered 2xx" (information). Counts, read from the reports: negative 175 public + 53
    admin = **228** operations tested (the three parameter-less ones included — the coverage phase's undeclared-method
    probes reach them), positive 50 + 15 = **65** `GET`s. 236 s for all four invocations at `MAX_EXAMPLES` 10 (the
    runner's docstring).
  - **Bites, each red by name** (1–3 on detached worktrees of `HEAD` with their own jar, compared with the untouched
    run, which is the restored state and carries none of these failures): (1) `useBeanValidation=false` →
    `negative_data_rejection` on `X-Share-Password`'s pattern (`GET /api/shares/{shareId}/blobs/{blobId}`, `POST
    /api/shares/{shareId}/identities`, …) and on `If-None-Match`'s `maxLength` (`GET /api/scopes/{scopeId}/nodes`); (2)
    `throw IllegalStateException("bite")` in `listMyTenants` → `not_a_server_error` on `GET /api/scopes/my-tenants`;
    (3) the bean-validation mapper writing the invalid value into `message` → `refusal_echoes_nothing` on 17 operations
    (`GET /api/shares/{shareId}`, `PATCH /api/shares/{shareId}`, …); (4) a `VEYQ_FUZZ_INCLUDE_BY` matching nothing →
    "tested no operation (selected 0, expected 1)" for each invocation.
- [x] **2 guard — the baseline is held.** `scripts/validate_api_fuzz_baseline.py` (Python 3.9, `json` only, docstring
  per `changes/README.md` § trust-chain guards): every entry of `infra/fuzz/baseline.json` has a non-empty `reason`;
  its `check` is never `not_a_server_error`; it carries no `expires`; its `operation` (`METHOD /path`) is an
  operation of `contract_specs.specs()`; a `reason` that defers (Q2) names an inbox row `#<n>` that exists in
  `docs/inbox.md` and is not done. Order: the guard lands **before** the first entry (§ *guard → red → fix → green*).
  Bites, snapshot-and-restore: an entry without `reason`; one with `check: not_a_server_error`; one with `expires`;
  one naming `GET /api/nope`; one deferring to a row marked DONE — each red, naming the entry; the empty baseline
  green. Add the guard's forgeries to `validate_guard_gaps.py` only for limits its docstring states (`grep -n
  'def case_\|CASES' scripts/validate_guard_gaps.py` for the shape). · Done when: the five bites red by name, the
  glob green.

  - **Built (2026-10-18).** A deferral is a `reason` with the word "defer", and it names an open row; "open" is a
    status cell whose first word is no closed marker (`done`, `dissolved`, `closed`, `dropped`, `superseded`). The five
    bites went red naming the entry, the empty baseline green; `validate_guard_gaps.py` case 84 pins the limit open (a
    `500` recorded under `status_code_conformance` passes — the same response is `not_a_server_error` in that run).
- [ ] **3 triage and fix — the first run's list goes to zero.** For each failure of phase 1's list, one of three,
  recorded in the commit body of its fix:
  - **contract gap** (a status the server deliberately answers, a schema that is wrong) → fix the spec;
    `npm run generate:api` in `web/` and `admin-web/`, `./gradlew check` (a response added to a spec changes no
    generated Kotlin signature with `returnResponse=true`, but run it). A 405/415 answered by the framework for a
    method or content type the operation never declared is declared once, in `_common`, if the body is
    `ErrorResponse` — and made `ErrorResponse` first if it is not (the same mapper family as `RequestRejectionMappers.kt`).
  - **backend defect** → fix, with a test in the ordinary suite that fails on the defect (ADR 0091 decision 9) — the
    parameter-bound class extends `MalformedRequestSliceTest` / `IdBoundarySliceTest`, one per mechanism.
  - **false positive or deferral** → a baseline entry with its `reason` (Q2), through `--baseline-update` so the
    signature is the tool's.
  Run phase 1's runner after each batch. · Done when: the runner is green on a fresh jar; the baseline holds only
  reasoned entries and the guard is green; `./gradlew check`, `npm run check` in `web/` and `admin-web/` green; the
  explicit parameter done-when holds: **every bounded path, query and header parameter of the locator above, sent
  out of range, answers `400` (query, header) or `400`/`404` (path) — never 2xx, never 5xx** — read from the
  negative invocations' reports.

  - **Triage (2026-10-18) — stopped here under Q2; Q4–Q7 open.** The first run (untouched backend, seed 20261018,
    `MAX_EXAMPLES` 10) failed on every spec file. By mechanism, as the run after the 5xx fix reads it
    (`python3 scripts/run_api_fuzz.py`, then the run directory's `summary.txt` and each invocation's `report.json`):

    | Mechanism | Where it shows | Class | State |
    |---|---|---|---|
    | E — a `null` element in a list of objects reaches the handler | `not_a_server_error` on 4 operations (`blob-uploads-batch`, the moderation epoch, `setup/owner`, `tenants/accept/register`) | backend defect | **fixed**: `NullElementsRefused` refuses it while binding (`400 invalid_request`); `MalformedRequestSliceTest.aNullElementInAListOfObjectsIsInvalidRequest` red before (a `500`, the handler's NPE), green after |
    | H — the seeded tenant could not mail | `not_a_server_error` (`503 mail_unavailable`) on 3 operations | harness | **fixed**: the seed points the tenant at Mailpit |
    | B — a route miss under `/api` (a non-UUID path id) answers `404` with no body and no content type | content type and JSON on 86 operations; an undeclared `404` on 3 | backend defect + contract gap | open, no decision needed: `ErrorResponse{not_found}` for an unmatched `/api` path without breaking the app shell's fallback or the admin channel's unknown-route refusal (`RequestRejectionMappers.kt` says why a `NotFoundException` mapper did), and `404` declared where a path id is |
    | J — a `400 invalid_request` the operation does not declare | `status_code_conformance` on 21 operations (the share routes, `recovery/*`, `tasks/{jobId}/*`, `capability-switches`, …) | contract gap | open, no decision needed: declare the `400` |
    | C — `TRACE` → bare `405`; an undeclared method → `404`/`400`; `OPTIONS` → a class-wide `Allow` | `unsupported_method` 165, `allow_header_conformance` 156 | feature-sized | **Q4** |
    | F, FMT — scalar coercion, unchecked `uuid`/`date-time` formats, lenient query booleans | `negative_data_rejection` ~50, `refusal_by_location` 1 (`0` as `deferStatementUntil` → `deferral_out_of_bounds`) | backend defect, global | **Q5** |
    | G1 — a malformed credential refused `401`/`403` before its shape | `negative_data_rejection` ~32 | rule | **Q6** |
    | D — `null` for an absent optional property | `response_schema_conformance` on 4 `GET`s | contract vs wire | **Q7** |

    No `refusal_echoes_nothing` failure in either run. Phase 4's `ci.yml` job was written and held back: a stage that
    is red on every commit would hold back every publish (`publish.needs`), so it lands when the list is at zero.
- [ ] **4 pipeline and published docs — it gates.**
  - `ci.yml`: job `api-fuzz`, `timeout-minutes: 15`, the `web-e2e` job's setup steps (Java, the fast-jar, Node, the
    gated `npm ci`), then `python3 scripts/run_api_fuzz.py`, then `upload-artifact` of `.fuzz-out/` (`if: always()`,
    the action pinned like its siblings). Add it to `publish.needs` and the publish's `if:` (held by
    `validate_release_identity.py` assertion 9) — the budget by `validate_pipeline_shape.py`.
  - `run_pipeline.py`: `api-fuzz` shares the developer's compose stack like `web-e2e` — follow what its docstring
    says for that job (`grep -n 'web-e2e' scripts/run_pipeline.py`), and run it once via `--job api-fuzz`.
  - Docs that change with it: ADR 0091's Status line (decision 9 built) and its stage table if the measured budget
    differs; `site/src/content/docs/security/how-veyq-is-tested.md` — its **API fuzzing** rows, added at refine as
    *Designed; not built* / *no job yet*, become *Built* and `ci.yml › api-fuzz`, every commit, with phase 1's
    measured local time (ADR 0091 decision 8), `npm run check` in `site/`; the limit sentence in
    `validate_request_bounds.py`'s docstring (parameters are now sent out of range — by the fuzz stage, which is not a
    guard, so the guard's own limit stands and says where the effect is shown); `infra/README.md` if it lists the
    harness directories (`grep -n 'infra/e2e' infra/README.md`).
  - Done when: `python3 scripts/run_pipeline.py --job api-fuzz` green; the guard glob green
    (`for f in scripts/validate_*.py; do python3 "$f" || echo "RED $f"; done`, `validate_guard_gaps.py` with `--force`
    because a guard was added); `site/` check green.

## Definition of done

- The four phases' done-whens. Baseline before the first edit, on a detached worktree of `HEAD`: the guard glob —
  `for f in scripts/validate_*.py; do python3 "$f" >/dev/null 2>&1 || echo "RED $f"; done; ls scripts/validate_*.py | wc -l`
  (at refine: 60 guards, every one green except as recorded in this CR's commit).
- No coverage YAML changes: `satisfies: []` (Context § Constraints says why); `python3 scripts/validate_coverage.py`
  green.
- The structural invariant this CR rests on — *the baseline never hides a 5xx and every exception has a reason* — is
  promoted to `validate_api_fuzz_baseline.py` (phase 2).
- Published docs: the Security area's *How veyq is tested* (phase 4); no Use or Run page speaks for this.
- Inbox #486 → done, its follow-up row (Q3) opened.

## Retrospective

(written at implement close)
