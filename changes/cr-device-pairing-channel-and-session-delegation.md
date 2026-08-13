---
id: cr-device-pairing-channel-and-session-delegation
title: The pairing channel and the delegated session — contracts + backend for both link directions
status: planned
satisfies: [uc.account.onboard-device]
adrs: ["0059", "0004", "0003", "0005", "0021", "0026", "0054"]
invariants:
  - Operator-blind E2EE — the server relays one opaque handle, one opaque answer and one opaque envelope, and
    interprets none of them
  - Non-enumerability — an unknown, expired and spent channel are indistinguishable, and no account identifier
    ever reaches the server from the device being onboarded
  - No code ever names a server (ADR 0059 §4) — nothing on the pairing wire may carry a URL-shaped field
  - Auth ≠ unlock — the delegated family is an auth-layer object and grants no plaintext by itself
---

## Goal

Build the server half that both directions of device linking need before either screen can exist: a
short-lived, single-use **pairing channel** that relays one opaque answer and one sealed envelope between two
devices, and a **session delegation** by which an already-authenticated session mints one further family for
its own account so the onboarded device gets a refresh token it could never obtain by itself (it never learns
the password, and veyq's auth keypair is password-derived — [ADR 0003](../docs/decisions/0003-operator-blind-password-auth.md)).
Phases are contracts then backend. Neither web direction, the live QR component, nor the
[uc.account.sessions](../docs/features/account/use-cases/uc.account.sessions.md) E3 triage is in this slice —
this is the enabler both of them stand on.

## Context & constraints

- [ADR 0059](../docs/decisions/0059-device-linking.md) governs — in particular §3 (one envelope sealed to the
  new device's ephemeral key; pickup deliberately unauthenticated), §4 (no code ever names a server), §6 (the
  proof of the transcribed code gates the **sealing**, never the release — so the server must not be able to
  check it), and §8 (short-lived, single-use, one outstanding pairing per account, in `AccountRecoveryEntity`'s
  shape and explicitly **not** [ADR 0021](../docs/decisions/0021-operator-blind-background-tasks.md)'s job
  queue).
- [uc.account.onboard-device](../docs/features/account/use-cases/uc.account.onboard-device.md) is the flow; its
  E1 (identical answers for expired/spent/unknown) and E3 (an uncollected envelope expires with the row) are
  backend obligations of this slice. E2, E4 and E5 are web-side and are not.
- [ADR 0004](../docs/decisions/0004-session-tokens.md) § *Session family = one login/device* already carries
  the second origin this slice implements — it is edited in place, not owed an edit.
- [ADR 0026](../docs/decisions/0026-client-trust-tiers.md) is why §6 refuses a server-side gate on the pairing
  code: a malicious operator releasing the envelope on a correct code would be past the only barrier.
- The two hard shapes this must not break, both already guarded:
  [`validate_session_family_shape.py`](../scripts/validate_session_family_shape.py) (a family carries no
  per-family ciphertext) and [`validate_device_shapes.py`](../scripts/validate_device_shapes.py) (no cleartext
  device↔wrap correlation). A delegated family is an ordinary family and must stay inside the first.

### Five constraints, measured against `main` @ `736c4fe` (clean tree)

1. **The row shape is `AccountRecoveryEntity` + `RecoveryPurge`, not `JobEntity`.** *Measured:*
   `backend/src/main/kotlin/com/veyq/app/features/account/recovery/Entities.kt:22-62` carries
   `{id, userId, email, tokenHash, status, expiresAt, createdAt}` with `PENDING/CONSUMED/SUPERSEDED/EXPIRED`
   and `TERMINAL`; liveness is checked at read (`RecoveryStore.isLive`, `RecoveryStore.kt:64-65`) *and* swept
   (`RecoveryPurge.kt:26-32`, `@Transactional` + `@Scheduled(every = "{veyq.recovery.purge.every}")`,
   expire-then-delete in that order). `JobEntity`
   (`backend/src/main/kotlin/com/veyq/app/features/tasks/Entities.kt:22-72`) routes on `scopeId` only —
   `TasksHandler.kt:52-54` refuses a non-member, `TasksStore.kt:89-104` filters claims against
   `grantedScopeIds(userId)`, and there is no other addressing column. It structurally cannot address a device
   with no membership yet.
2. **Rate limiting is a compulsory declaration.** *Measured:*
   `RateLimitPostureSweepTest.everyEndpointDeclaresARateLimitPosture`
   (`backend/src/test/kotlin/com/veyq/app/shared/ratelimit/RateLimitPostureSweepTest.kt:146-154`) enumerates the
   real compiled + CDI-deployed resources, so a new resource with no `@RateLimit`/`@RateLimitExempt` reddens the
   build without anyone adding it to a list. It also pins `IP` to `@PermitAll` and `USER` to `@Authenticated`
   (lines 168-190). `AccountWrapsResource.kt:21-26`'s exemption reads *"Bounded by the DEVICE whitelist and the
   caller's own session, not by request volume"* — the pairing endpoints have **no caller**, so it does not
   transfer.
3. **Session delegation is wholly new.** *Measured:* `SessionService.issue(userId)`
   (`backend/src/main/kotlin/com/veyq/app/features/account/login/SessionService.kt:41`) has exactly one
   production call site — `LoginHandler.kt:66`, inside the unauthenticated challenge-response verify. Every
   other method on that class operates on an existing family. No method mints one for an authenticated
   principal.
4. **Collection is unauthenticated on purpose.** The envelope is worthless without the ephemeral private key
   (ADR 0059 §3), and gating the fetch would force the new device to name an account before it has one.
5. **A guard is owed and does not exist.** *Measured:* `grep -rli 'pairing|device-link|devicelink|onboard'
   scripts/` → no matches; `grep -rn -i -E 'pairing|device-link|devicelink|onboard-device' contracts/` → no
   matches. [`uc.account.onboard-device.yaml`](../docs/coverage/features/account/use-cases/uc.account.onboard-device.yaml)
   lines 10-13 record `no-code-ever-names-a-server` as owed, *"the same shape as
   `scripts/validate_device_shapes.py`, one schema over"*. It is in this CR's Definition of done.

## The endpoint form — this CR's decision

ADR 0059 left the endpoint form open. It is derived here from §3, §6 and §8, and the derivation **surfaces a
fifth verb the four-verb sketch (open / answer / seal / collect) hides**: the envelope is sealed *client-side*,
so its plaintext — including the delegated refresh token — must be in the trusted device's hand **before** it
can seal. Delegation therefore cannot be a side effect of the seal; it is its own call, made after the user's
confirmation and immediately before it.

All five are `POST` with the handle **in the body**, never in a path or query — the built precedent is
`POST /api/recovery/release` carrying `RecoveryTokenRequest.token`
(`contracts/api/auth.openapi.yaml:294`), and it keeps a routing secret out of logs and referrers.

| Endpoint | Auth | Posture | Body → response |
|---|---|---|---|
| `POST /api/pairing/open` | `@PermitAll` | `@RateLimit(IP)` | `{handle}` → `204` |
| `POST /api/pairing/answer` | `@PermitAll` | `@RateLimit(IP)` | `{handle, answer}` → `204` |
| `POST /api/pairing/delegate` | `@Authenticated` | `@RateLimit(USER)` | `{handle}` → `{refreshToken}` |
| `POST /api/pairing/seal` | `@Authenticated` | `@RateLimit(USER)` | `{handle, envelope}` → `204` |
| `POST /api/pairing/collect` | `@PermitAll` | `@RateLimit(IP)` | `{handle}` → `{answer, envelope}` |

Seven decisions ride that table, each with its reason:

- **The server holds three opaque values and interprets none of them** — the handle, one `answer` and one
  `envelope`. `answer` means different things in the two directions (the new device's ephemeral public key when
  *adding a device*; the commitment to the transcribed code when *signing in with a QR code*), and the server
  must not know which — §6 forbids it checking a code, and a server that could tell the two apart is one
  release-on-correct-code refactor away from being the barrier ADR 0026 says it may never be.
- **The handle is minted client-side and is not a public key.** The server stores only `sha256(handle)`, the
  same posture as a recovery token — it sees the value at use, keeps only the hash, and a database dump finds
  no live channel. It stays separate from the ephemeral transport key because the key has a cryptographic job
  and the handle a routing one; conflating them would also make *"the displayed key"* two different keys across
  the two directions while the handle stays one thing.
- **`collect` is the single read, used by both sides.** The trusted device polls it for the answer; the new
  device polls it for the envelope. One endpoint, one shape, and reading the answer spends nothing — only
  handing over the envelope moves the row to `COLLECTED`.
- **No server-minted identifier is returned anywhere.** `open` answers `204`. A returned channel id would be a
  second addressing value that can leak and that nothing needs; the client already holds the only one.
- **Every dead channel answers identically** — `404 pairing_channel_invalid` for unknown, expired, spent and
  wrong-state alike, mirroring `RecoveryResource.releaseRecoveryMaterial`'s single `recovery_token_invalid`
  (`RecoveryResource.kt`). This is E1, and it is why `open` cannot refuse a duplicate.
- **The account is bound at `delegate`, and that is where §8's "one outstanding pairing per account" is
  enforced** — by superseding the account's other live channels, exactly as `RecoveryStore.mint` supersedes
  (`RecoveryStore.kt:42-46`). It is the first and only moment the account exists on the row: in the QR-sign-in
  direction the channel is opened by a device with no session at all, so a `userId` at `open` is not merely
  unnecessary, it is unavailable. `userId` is therefore **nullable** — the one deliberate departure from
  `AccountRecoveryEntity`'s shape, and it is ADR 0059 §3's *"which account this becomes is decided by whoever
  scans"* written as a column.
- **A delegated family whose channel dies unspent is revoked by the sweep.** The row records its
  `delegatedFamilyId`, and a channel reaching `EXPIRED` without `COLLECTED` takes that family with it.
  Otherwise an abandoned ceremony leaves a listed family holding a live 30-day refresh token
  (`SessionService.REFRESH_TTL_DAYS = 30`, `SessionService.kt:232`) that nobody ever picked up. Once collected,
  the family is ordinary in every respect ADR 0004 names — listed, rotating, revocable.

## What this slice deliberately excludes

Slice 2 (*add a device*, web), slice 3 (*sign in with a QR code*, web), the live on-screen QR component, and
E3's triage on the sign-in form. Also excluded: any master-key or identity rotation — ADR 0059 § Context names
that as the live obligation this feature creates ([ADR 0028](../docs/decisions/0028-identity-authenticity-tofu-verification.md) (d)),
and it is a separate refinement, not a phase here.

## Inventory & locators

Run each of these; read the **list**, not the count. Each is stated with the angle it is keyed on, and the two
that decide something are asked twice on different keys.

**1 — Does anything pairing-shaped exist on the wire yet?** Two angles: the *word*, and the *shape it would
take*.

```bash
grep -rn -i -E 'pairing|device-link|devicelink|onboard-device' contracts/
grep -rn -i -E 'ephemeral|transportKey|envelope' contracts/
```

*Run at planning:* the first returns nothing. The second returns four lines, all ADR 0021 prose in
`contracts/api/tasks.openapi.yaml` (job "envelope") plus `contracts/tasks/tags/v1.json` — unrelated, and the
disagreement is the finding: "envelope" is already a taken word in this repo's vocabulary, so the pairing
schemas must not reuse it loosely in prose.

**2 — Where the endpoints and schemas go.**

```bash
grep -n '^  /api' contracts/api/auth.openapi.yaml
```

*Run at planning:* ten paths — `/api/login/{challenge,verify}`, `/api/token/refresh`, `/api/logout`,
`/api/sessions`, `/api/sessions/revoke-others`, `/api/sessions/{id}`, `/api/recovery/{request,release,complete}`.
The house form is `noun/verb` under `/api`, and sessions sit at top level rather than under `/account`.
**Decision: the new endpoints join `auth.openapi.yaml` under a new `pairing` tag** — the generator makes one
`*Api` interface per tag (`backend/gradle/openapi.gradle.kts`, *"one tag per API resource"*), so a new spec file
would buy nothing and would force another copy of the shared `bearerAuth` scheme that
[`validate_contracts_common.py`](../scripts/validate_contracts_common.py) has to keep byte-consistent.

**3 — What a new contract property owes.** Two angles: the marker, and its exemption.

```bash
python3 scripts/validate_field_visibility.py
python3 scripts/validate_maxlength_storage.py
```

*Run at planning:* both green. The rule that bites here is the **inverse** of the obvious one: a property with
`contentEncoding: base64` must **not** carry `x-veyq-visibility` (`validate_field_visibility.py:136-142` treats
a marker on an opaque field as an error), which `DeviceRegister.blob` demonstrates by carrying none. So
`handle`, `answer` and `envelope` take no marker; `refreshToken` takes `x-veyq-visibility: token`, as its
siblings in the same spec already do (`auth.openapi.yaml:446, 500, 506`). Every base64 cap must fit `TEXT`
(65 535 on MariaDB): the `wrappedKey` precedent is `maxLength: 8192` (`account.openapi.yaml:418, 449`).

**4 — Where the backend slice sits, and what it must look like.**

```bash
find backend/src/main/kotlin/com/veyq/app/features/account -maxdepth 1 -type d
find backend/src/test/kotlin/com/veyq/app/features/account -maxdepth 2 -type f
```

*Run at planning:* six packages — `deviceregister`, `identitypins`, `keymaterial`, `login`, `profile`,
`recovery`; **no standalone `sessions` package** (session code lives inside `login/`). Tests are one
`<Slice>SliceTest.kt` per resource, colocated, plus `<X>PurgeTest.kt` for a scheduled sweep. So: a new
`account/pairing/` package, and the delegation lands in `login/SessionService.kt` beside `issue`.

**5 — The next migration number.**

```bash
ls backend/src/main/resources/db/migration/ | sed 's/V\([0-9]*\)__.*/\1/' | sort -n | tail -3
```

*Run at planning:* `43, 44, 45` — so `V46__device_pairing.sql`.

**6 — The guard baseline.**

```bash
for f in scripts/validate_*.py; do python3 "$f"; done
```

*Run at planning:* **23 guards, all exit 0** on the untouched tree. That baseline is what makes a red in phase 1
mean *"this CR broke something"* rather than *"it was already red"*.

## Phases

- [ ] **1 contracts** — the `pairing` tag, its five paths and seven schemas in `contracts/api/auth.openapi.yaml`;
      `scripts/validate_pairing_shapes.py`.
      **Done when:** `for f in scripts/validate_*.py; do python3 "$f"; done` is green across **24** guards
      (baseline 23, measured above), **and** the bite below has been run and its red read by name.

  **Sketch.** Seven schemas, each an exact closed set — `PairingOpenRequest{handle}`,
  `PairingAnswerRequest{handle, answer}`, `PairingDelegateRequest{handle}`,
  `PairingDelegateResult{refreshToken}`, `PairingSealRequest{handle, envelope}`,
  `PairingCollectRequest{handle}`, `PairingChannel{answer, envelope}`. `PairingChannel` deliberately carries
  **no status field**: presence of `answer` and `envelope` says everything either side needs, and a status
  enum is one more thing that can be read and one more property the guard would have to allow. `handle`,
  `answer` and `envelope` are `contentEncoding: base64` (no visibility marker); caps `128 / 8192 / 8192`;
  `refreshToken` is `x-veyq-visibility: token`, capped like its sibling in the same spec.

  The guard is `validate_device_shapes.py` copied one schema over: a `EXPECTED: {schema → allowed property
  set}` map over those seven, exact in both directions, with a missing schema a **failure** rather than a
  silent pass. It lands **in this phase, not before it** — a guard that pins schemas which do not exist yet is
  red on arrival, which is the one state that makes every later red unreadable.

  **Bite (must run, and the red must name the field).** Add `serverUrl: {type: string, x-veyq-visibility:
  public}` to `PairingOpenRequest`; the guard must fail printing `PairingOpenRequest.serverUrl`, not merely
  fail. Then remove it and confirm green. Snapshot-and-restore per
  [`changes/README.md`](README.md) § bite-proof — `command cp -f` on **both** halves, never
  `git checkout -- <file>`, and confirm the restore against the snapshot's bytes rather than against `HEAD`
  (the file already carries this CR's own uncommitted edits, so `git diff --quiet` reports dirty either way).

  **The predicate reached for first, and why it cannot be used — measured, so it goes in the guard's `limit`
  field.** The tempting *file-scoped* shape *"no property whose name matches `url|uri|host|endpoint|server|
  origin|instance` anywhere in the specs"* is **red on arrival: 8 hits on the untouched tree**, among them
  `sharing.openapi.yaml:PresignedUrl.url`, `tenants.openapi.yaml:TenantMailSettings.host` and
  `auth.openapi.yaml:LoginChallenge.instanceSalt` — all legitimate. Only the schema-scoped allowlist is green
  on the tree as it stands. This is the same trap `validate_device_shapes.py` records in its own docstring, met
  a second time; the next reader will reach for the same easy shape, so the guard must say why it cannot work.

- [ ] **2 backend** — `account/pairing/` (entity, store, purge, handler, resource), `V46__device_pairing.sql`,
      the delegation on `SessionService`, and the config keys.
      **Done when:** `cd backend && ./gradlew check` green — including
      `RateLimitPostureSweepTest.everyEndpointDeclaresARateLimitPosture`, which reddens by itself if any of the
      five new endpoints declares no posture.

  **Baseline, measured at planning: 68 test classes, 510 tests, 0 failures, 0 errors, 1 skipped.** Read that
  number off `build/test-results/test/*.xml`, not off the console line — the first attempt returned
  `> Task :check UP-TO-DATE` with `11 actionable tasks: 11 up-to-date`, which is a green that executed nothing
  and is indistinguishable from a green that passed. `./gradlew check --rerun-tasks` is what produced the run
  above. The one skip is pre-existing; a second one is this CR's.

  **Sketch.** `DevicePairingEntity` over `veyq_device_pairing`, mirroring `AccountRecoveryEntity` field for
  field except where the ceremony differs: `{id, handleHash(64, unique), userId(36, NULLABLE),
  delegatedFamilyId(36, nullable), answer(TEXT, nullable), envelope(TEXT, nullable), status(16), expiresAt,
  createdAt}`. Status vocabulary `PENDING → ANSWERED → SEALED` with terminal
  `{COLLECTED, SUPERSEDED, EXPIRED}`. There is no `ABANDONED` and no abandon endpoint: §6's three-attempt
  ceiling is counted on the trusted device, which never tells the server — the channel simply expires, which is
  what E3 already describes for an uncollected envelope.

  `PairingStore` copies `RecoveryStore`'s division exactly: `findByHandleHash` returns the row **unfiltered**
  and `isLive` is a separate predicate, so the handler — not the query — owns the indistinguishability of
  unknown / expired / spent. `PairingPurge` copies `RecoveryPurge`'s two-step order (expire overdue first, then
  delete terminal rows past retention) and adds the one thing recovery has no equivalent of: revoking the
  `delegatedFamilyId` of a channel that expires uncollected.

  On `SessionService`, the new capability sits beside `issue` and is deliberately **not** a general
  `issueFor(userId)`: it is reachable only through the pairing handler, against a live channel, so ADR 0004's
  second origin is *"a device-linking grant"* in code and not *"any authenticated caller may mint a session"*.

  Config keys `veyq.pairing.channel.ttl`, `veyq.pairing.channel.retention` and `veyq.pairing.purge.every` join
  `VeyqConfig`, whose docstring calls itself *"the complete inventory of `veyq.*`"* — so a key added anywhere
  else is a lie about that file.

  **Tests.** `PairingSliceTest` over the full HTTP stack for the whole lifecycle in both directions, with a
  **second account live throughout** so *"scoped to the account that delegated"* is observed at the boundary
  rather than argued from SQL; the three dead-channel cases each with a fresh channel as its **live control**
  (without one, "they all answer the same" is satisfied by an endpoint that refuses everything); `PairingPurgeTest`
  for the sweep and the uncollected-family revoke; and an addition to `SessionManagerSliceTest` proving a
  delegated family lists, rotates and revokes like any other **and** that revoking it leaves the delegating
  family refreshing — the granting session is the live control the coverage file names.

- [ ] **3 coverage** — `docs/coverage/features/account/use-cases/uc.account.onboard-device.yaml`.
      **Done when:** `python3 scripts/validate_coverage.py` green.

  **Sketch.** The backend gap is **rewritten, not removed, and `status:` stays `planned`.** *Measured:*
  `git remote -v` is empty — this repository has no remote and no CI run has ever executed, so
  [ADR 0040](../docs/decisions/0040-coverage-gaps-and-http-not-a-surface.md)'s rule leaves every criterion
  uncovered however green the suites are locally. This is the same call
  [`uc.account.sessions.yaml`](../docs/coverage/features/account/use-cases/uc.account.sessions.yaml) already
  makes for the same reason, and that file is the idiom to copy: name the suites in the gap's prose so the
  later flip is mechanical, and keep no `coverage:` block at all. What the rewrite must say precisely: which
  criteria this slice's backend suites *exercise*, that the web half of every criterion is still unbuilt, and
  that `no-code-ever-names-a-server` is now carried by `scripts/validate_pairing_shapes.py` rather than owed.

## Definition of done

Phases 1–3 green, and:

- `for f in scripts/validate_*.py; do python3 "$f"; done` green over **24** guards, run against the
  **committed** tree — the glob sees files, so an untracked guard is green locally and unchecked everywhere
  else.
- `cd backend && ./gradlew check` green (the full suite, not the touched classes — the `%test` H2 database is
  JVM-shared across test classes).
- `scripts/validate_pairing_shapes.py` exists, is bitten, and its docstring carries all three required fields —
  **Asserts**, **Bought by** (no incident: written at the moment the temptation goes live, like
  `validate_device_shapes.py`) and **Limit** (what a contract sensor over seven schemas cannot see: a path or
  query parameter, a header, an error `message`, a DB column with no contract field — and the file-scoped
  name-pattern predicate measured red-on-arrival above).
- Nothing in `docs/` is restated by this CR that was not already decided; ADR 0059 and ADR 0004 are edited in
  place if — and only if — implementation contradicts them, and that contradiction is escalated first rather
  than resolved in code.

## Retrospective

<!-- Filled at close: what went wrong in planning, execution and process/collaboration; root cause; the one
concrete "next time". Both directions. Harvest before deleting this file. -->
