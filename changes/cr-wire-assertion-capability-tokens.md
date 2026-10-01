---
id: cr-wire-assertion-capability-tokens
title: Every capability token the run handled appears nowhere raw — only its hash, where the server keeps one
status: planned
satisfies: []
adrs: ["0053", "0004", "0044", "0059", "0029"]
invariants:
  - Operator-blind, and ADR 0004's "a database dump yields no usable session" — this CR ADDS observation only;
    it changes no storage, since every kind the server stores is measured hashed already (Inventory T1–T8).
  - Needles are derived, never listed — the token set is read out of the run's own HTTP recording by the
    contract's `token` visibility marker, exactly as ADR 0053 §2 derives canaries by unsealing.
  - API-first — the contract edits (questions 2 and 6) land in `contracts/` first and are regenerated, not
    hand-written.
---

## Open for the owner

**Fourth round answered by the owner, 2026-10-01: every recommendation added since the third answer is taken too** (incl. the granter-lock questions Q1–Q3 and the rotation CR's Q8/Q9).

**Third round answered by the owner, 2026-10-01: every recommendation added since the second answer is taken too** (questions added by the third refine wave and by implementation hand-backs). Rows those questions proposed exist in `docs/inbox.md` (#233–#243).

**Open — added 2026-10-01 by the inbox #226 fold, not yet answered.** The CR below is written as if its
recommendation is taken; the go given for questions 1–5 does not cover it.

6. **Does the recorder capture response headers, and how much of them?** Today it records none (T14), so the
   refresh token — which every web sign-in delivers **only** as a `Set-Cookie` (T17) — is never a needle, and
   D3 had to declare it swept-for-nothing. Measured on the retained run: **27** successful sign-ins, every one
   with `sessionScope: SESSION`, so 27 refresh tokens crossed the wire unrecorded (T17).
   - (a) **Record every response header**, `set-cookie` as the array `Headers.getSetCookie()` returns, and
     mark the five declared response headers in the contract (the four `Set-Cookie` as `token`, the one `ETag`
     as `structure`), with `validate_field_visibility.py` requiring a marker on every declared response header.
   - (b) Record `set-cookie` alone, no contract edit; the derivation reads the cookie value by the header's name.
   - (c) Leave it, keep D3 as written.

   **Recommendation: (a).** The whole header set costs a few hundred bytes per call, and it also puts response
   headers into the HTTP **canary** haystack (D6) — a `Location` or `Content-Disposition` carrying plaintext is
   a leak no sweep sees today. The contract edit keeps D2's rule that needles come from markers, not names:
   (b) is exactly the name-keyed derivation question 2 rejected, one header wide. It costs one more guard rule
   beside the header-parameter rule phase 1 already adds, and one marker on each of five headers (T19).
   **The trap, measured (T18):** the recorder's own `headersOf` idiom (`forEach` into an object) keeps only the
   **last** `Set-Cookie`, and `SessionCookie.issue` sends the token cookie **first** and the clearing cookie for
   the other lifetime second, so a recorder written the obvious way records `veyq_refresh=` (empty) on every
   sign-in and the sweep stays green with nothing to look for. B5 pins it.

**Answered by the owner, 2026-10-01: questions 1–5 below — every recommendation taken, and implementation has the go.** Read each question's recommendation as its decision. Where a question proposed a separate inbox row, that row exists in `docs/inbox.md`.

Each question carries a recommendation; the CR below is written as if every recommendation is taken.

1. **Is #203 a live defect?** **No — measured.** Every one of the six kinds the row names is hashed (hex SHA-256,
   `Tokens.hash`) before it reaches an entity, and looked up by that hash (T1–T6); the two further capability
   secrets on the wire are a SHA-256 verifier and a KEK-sealed value (T7, T8); no log statement interpolates a
   token (T9). A probe over the newest retained run found **46** distinct raw tokens (27 access, 15 invite
   bootstrap, 2 recovery, 2 contributor) in **0** of 115 retained non-recording files — boot logs, all three retained
   database dumps, every store mirror — while the hex SHA-256 of 4 of them **is** in the backup dumps (T10). So
   the row is a **missing sensor**, as written. *Recommended:* build the sensor (this CR), because nothing pins
   the property today: the backend tests find rows **by** hash (a positive), and none asserts the raw value is
   absent from every column (T11) — a raw copy added to a second column, or a lookup by the raw value, would
   pass every existing test.
2. **Where do the needles come from?** *Recommended:* **the contract's `token` class, read over the run's HTTP
   recording** — every JSON value whose schema property carries `x-veyq-visibility: token` (19 properties today,
   T12), plus the bearer of `Authorization` (the one `securityScheme` shape, `type: http, scheme: bearer`), plus
   header parameters marked `token`. That needs **three contract edits**, each the smallest honest one:
   - **mark the two capability header parameters** (`X-Contributor-Token`, `X-Share-Password`) with
     `x-veyq-visibility: token`, and extend `validate_field_visibility.py` to require a marker on every
     `in: header` parameter (3 exist, T13). ADR 0044 scoped itself to body properties and named these headers as
     unseen; the extension is a dated note on ADR 0044, **not** a new ADR.
   - **reclassify the pairing `handle`** (5 properties, `contentEncoding: base64` today) as `token`. It is a
     routing *capability* — the contract's own description calls it "the client-derived routing secret", stored
     only as a hash — not ciphertext; filed as opaque it is invisible to a token sweep, and the HTTP opacity check
     it gets instead proves nothing about random bytes. (Rule 5 of the field-visibility guard forbids a marker on
     a `base64` field, so it is one or the other.)
   - **mark `InviteBootstrapSecret`** — the bare `type: string` component the two response-side
     `bootstrapSecret` properties `$ref` (T16). The guard's rule 2 exempts a `$ref` property because "its target's
     own properties carry theirs" — and this target is a scalar with no properties, so today the response copy of
     the invite secret is classified by nobody. The walk follows the `$ref` and reads the marker on the target;
     the guard gains the matching rule (a scalar component schema carries a marker).
   The rejected alternative is a name list in the orchestrator (`accessToken`, `bootstrapSecret`, …): it is the
   hand-listed set ADR 0053 §2 exists to avoid, and the 20th token field would be forgotten out of it.
3. **Which surfaces?** *Recommended:* **every text surface the sweeps read when this CR lands** — the per-suite
   database dump, the store keys and bytes, and (once built) the logs CR's boot and container logs and the
   history CR's statement log and mailbox — **with two stated exemptions, both derived:**
   - **the mailbox, for a token the recording shows the suite first read out of a mail** (member-invite and
     recovery secrets: mail *is* their channel, ADR 0049/0054). Every other token in a mail is a hit.
   - **presigned-URL signatures** (`PresignedUrl.url`, class `token`) **in the store's own request log**: the
     store is their verifier and logs the URI it was handed (logs CR I5); they expire with the presign TTL. They
     are swept everywhere else. The concern states the exemption and its bound rather than hiding it.
4. **Positive control?** *Recommended:* **the hash, on the same matcher.** Per suite whose token set is non-empty,
   at least one token's hex SHA-256 must be found in that suite's dump — which proves the dump was read and the
   token set belongs to that database. Measured only on the retained backup dumps so far (T10: 4 hits); phase 2
   measures it per suite before relying on it, and a suite where every token row is gone by dump time (purged,
   revoked) is reported by name, never silently exempted.
5. **Order?** *Recommended:* **last of the three** — logs CR → history CR → this CR, so the second needle set is
   applied to every surface at once with one bite per surface. #203 is not a live defect (question 1), so
   nothing argues for jumping the queue.

## Goal

[ADR 0004](../docs/decisions/0004-session-tokens.md) decided that the server keeps only a hash of each opaque
token so that "a DB dump yields no usable session", and every later capability secret (contributor, invite,
recovery, pairing) copied that shape. Nothing observes it: the wire sweep's needles are the plaintext the client
sealed, and a raw token is not a sealed value. After this CR the orchestrator derives a **second needle set** — every
capability token the run handled, read out of the HTTP recording by the contract's `token` class — and asserts
that none of them appears raw on any surface the sweeps read, proven armed by finding their hashes where the
server is supposed to keep them.

## Inventory (measured 2026-09-28 against `64c1f65` and the main checkout's retained run `.e2e-out/1790576564`, unless marked remembered)

Code locators are relative to `backend/src/main/kotlin/com/veyq/app/`.

| # | Token kind | Where it is minted / stored today | Locator | Kind |
|---|---|---|---|---|
| T1 | access (bearer) | `Tokens.random()` → `tokenHash = Tokens.hash(access)` → `veyq_access_token.token_hash`; lookups by `Tokens.hash` | `grep -n 'Tokens\.' features/account/login/SessionService.kt` | measured |
| T2 | refresh | same, `veyq_refresh_token.token_hash`; web transport is an `httpOnly` cookie | same; `grep -n 'veyq_refresh' features/account/login/SessionCookie.kt` | measured |
| T3 | contributor | `Tokens.random()` → `Tokens.hash(token)` → `veyq_share_identity.token_hash` | `grep -n 'Tokens\.' features/sharing/SharingStore.kt` | measured |
| T4 | invite bootstrap secret (owner + member, reissue, reassign) | `Tokens.random()` then `Tokens.hash(token)` passed into every `TenantStore` mint; accept looks up `Tokens.hash(bootstrapSecret)` | `grep -n 'Tokens\.' features/admin/tenant/TenantHandler.kt features/admin/tenant/TenantAcceptHandler.kt` | measured |
| T5 | pairing handle (client-derived) | `store.open(Tokens.hash(handle), …)`; lookups by hash | `grep -n 'Tokens\.' features/account/pairing/PairingHandler.kt` | measured |
| T6 | recovery (reset) | `Tokens.random()` → `store.mint(…, Tokens.hash(token), …)`; lookups by hash | `grep -n 'Tokens\.' features/account/recovery/RecoveryHandler.kt` | measured |
| T7 | share password token (`X-Share-Password`) | only `pw_verifier` = SHA-256 of the token | `grep -n 'pw_verifier' backend/src/main/resources/db/migration/V39__share_password.sql` | measured |
| T8 | tenant SMTP password (`TenantMailSettingsUpdate.password`, class `token`) | sealed under the config-secret KEK (`password_ciphertext`, `password_nonce`) | `grep -n 'password_' backend/src/main/resources/db/migration/V42__tenant_mail_settings.sql` | measured |
| T9 | logs | no log call interpolates a token, link or secret (the four hits of the grep carry ids, status and `e.message`); the no-SMTP invite mailer writes the raw token only to an opt-in dev file, after a `LaunchMode.NORMAL` early return | locator L1 below; `grep -n 'LaunchMode.NORMAL\|appendDevFile' features/admin/tenant/TenantInviteMailer.kt` | measured |
| T10 | retained evidence | 46 raw tokens from 12 recordings; 0 raw hits in 115 other retained files (7.4 MB, incl. 3 `database.sql`); hex SHA-256 of 2 access tokens and 2 bootstrap secrets found in the backup dumps. **0** refresh tokens and **0** pairing handles in the recordings — no suite exercises either | the refine's probe `token_probe.py` (below) | measured (one run) |
| T11 | existing tests | backend tests look rows up **by** hash (`find("tokenHash", Tokens.hash(…))`); none asserts a raw value absent; `TenantInviteMailerDevFileTest` pins one bean's no-log | `grep -rn 'Tokens.hash(' backend/src/test`; `ls backend/src/test/kotlin/com/veyq/app/features/admin/tenant/TenantInviteMailerDevFileTest.kt` | measured |
| T12 | contract `token` class | 19 properties: `refreshToken` ×5, `accessToken` ×2, recovery `token` ×2, `PresignedUrl.url` ×2, `contributorToken` ×4, `bootstrapSecret` ×3, SMTP `password` ×1 | `grep -rn 'x-veyq-visibility: token' contracts/api` | measured |
| T13 | header parameters | 3 declared `in: header` (`If-None-Match`, `X-Contributor-Token`, `X-Share-Password`), none marked; the field-visibility guard checks body properties only | `grep -rn -B2 'in: header' contracts/api`; the guard's docstring § Scope | measured |
| T14 | the recorder | records request headers and textual bodies; **no response headers**, so a `Set-Cookie` refresh token is never observed (re-measured 2026-10-01 on `7a34c57`: `WireEntry` has no response-header field) | `grep -n 'requestHeaders\|responseBody' web/src/test/wireRecorder.ts` | measured |
| T15 | a design-level raw capability half | the read/contributor share id is stored raw as `veyq_share.id` by ADR 0029's decision ("half the capability"; the other half is the fragment key) and is classified `id`, not `token` | `grep -n 'half the capability' backend/src/main/resources/db/migration/V31__share.sql docs/decisions/0029-public-viewer-share.md` | measured |
| T17 | web sign-ins deliver the refresh token as a cookie only | `LoginResource.verifyLogin` answers a request with `sessionScope` by `cookie.issue(…)` and a body with `refreshToken = null`; the web client always sends `sessionScope`. The retained run `.e2e-out/1790576564` holds 27 `POST /api/login/verify` 200 (1 × 401) across 12 recordings, 28 requests carrying `sessionScope: SESSION`, 0 `token/refresh` and 0 `logout` calls — node's `fetch` keeps no cookie jar, so the cookie is never sent back | `grep -n 'cookie.issue\|noBodyToken' features/account/login/LoginResource.kt`; `grep -n 'sessionScope' web/src/features/account/login/login.ts`; the fold's probe `rec.py` (below) | measured (2026-10-01) |
| T18 | node's `fetch` exposes `Set-Cookie`, and one idiom loses it | under node 23.11.0, `Response.headers.getSetCookie()` returns both cookies of a two-cookie response, also on a `clone()`; `forEach` into an object (the shape of `headersOf`) and `Object.fromEntries(headers)` keep only the **last** one — the clearing cookie, given `SessionCookie.issue`'s order | the fold's probes `setcookie.mjs`, `fe.mjs` (below); `grep -n 'fun issue' -A2 features/account/login/SessionCookie.kt` | measured (2026-10-01) |
| T19 | response headers declared in the contract | 5: `Set-Cookie` on `verifyLogin`, `refreshSession`, `adoptSession`, `logout` (the last one clears only), `ETag` on the media list (a revision counter); none carries a marker | `grep -rn -A1 '^ *headers:$' contracts/api` | measured (2026-10-01) |
| T16 | an unclassified scalar | `InviteBootstrapSecret` is a component schema of bare `type: string` with no marker, `$ref`'d by the two response-side `bootstrapSecret` properties; the field-visibility guard's container rule lets both through | `grep -n -A2 'InviteBootstrapSecret:' contracts/api/tenants.openapi.yaml`; `grep -n 'InviteBootstrapSecret' contracts/api/tenants.openapi.yaml` | measured |

L1 — kept out of the table, where its pipes would split the cell:

```sh
grep -rnE '\b(Log|LOG|log|logger)\.(debug|trace|info|warn|error)f?\(' --include='*.kt' backend/src/main | grep -iE 'token|secret|handle|link|password'
```

The probe is the refine's scratch script, not shipped: it pulls token-named fields and capability headers out of
every `http-*.jsonl`, searches every other retained file for the raw values, and every `*.sql` for their hex
SHA-256. Re-derive it at implement time; the shipped derivation is by contract marker (question 2), not by name.

The #226 fold's three probes (2026-10-01, scratch, not shipped) are equally short to re-derive: `rec.py` counts
per `(method, path, status)` the `login`/`token`/`session` calls in every `http-*.jsonl` of a run and the
`sessionScope` values their request bodies carry; `setcookie.mjs` starts a `node:http` server on an ephemeral
port that answers with two `Set-Cookie` headers and reads them back through the global `fetch` four ways (`get`,
`getSetCookie()`, `forEach`, `clone().headers.getSetCookie()`); `fe.mjs` builds a `Headers` with the two cookies
in `SessionCookie.issue`'s order and flattens it the `headersOf` way.

## Decisions

- **D1 · A second needle set, not a second sweep.** `token_needles(recording, contract)` returns the run's
  capability tokens; each existing sweep takes it alongside the canaries and reports a hit as `token:<kind>` so a
  reader never confuses a leaked token with a leaked canary. Tokens are base64url/hex ASCII with no space, quote
  or backslash, so the raw form is the only needle (`canary_needles` would return it unchanged — asserted, not
  assumed, in the buildless guard).
- **D2 · Derivation is by marker over the recording** (question 2): walk every recorded JSON body against its
  operation's schema (the walk `sweep_http` already does for opacity), take every `token`-classed value; add the
  `Authorization` bearer and every header parameter marked `token`. The kind is the schema property name.
- **D2a · A response header marked `token` contributes its cookie values** (question 6). For each recorded
  response whose operation declares a `token`-marked `Set-Cookie`, every recorded `set-cookie` entry is cut at
  the first `;`, then at the first `=`, and a **non-empty** value is a needle of kind `Set-Cookie` (D2's rule
  — the kind is the name of the marked thing — applied to a header). The clearing cookies (`Max-Age=0`, empty value) yield none,
  which is why `logout`'s marker is harmless. Cookie values here are base64url (`Tokens.random()`), so no
  unquoting is needed — asserted beside D1's `canary_needles` assertion, not assumed.
- **D3 · Refresh is swept from the cookie; pairing stays a code-level fact, stated.** *(Changed by the #226
  fold.)* Every web sign-in yields a refresh token through the recorded `Set-Cookie` (T17, D2a, D6), so after
  phase 1b it is a needle like any other — measured 27 per run on the retained one. No suite drives a pairing
  (T10); the concern names it as swept-for-nothing rather than implying coverage, and a suite that drives one
  extends the set for free. A suite that drives `token/refresh` would have to forward the cookie by hand (node's
  `fetch` keeps no jar, T17) — not built here.
- **D4 · The raw share id is out of scope, by ADR 0029** (T15) — it is `id`-classed and stored raw by decision.
  Whether a raw share id at rest is an acceptable *write* capability on contributor and uploader shares is a
  design question for a separate inbox row (proposed with this refine, not added by it), not a sensor gap.
- **D5 · The retained recording carries the raw tokens.** `.e2e-out/<stamp>/http-*.jsonl` already holds them
  (T10) — throwaway credentials of throwaway instances; after phase 1b the refresh tokens too. Stated in the
  concern, because once `.e2e-out/` is uploaded from CI (inbox #205) they leave the machine.
- **D6 · The recorder records every response header** (question 6). `WireEntry` gains `responseHeaders:
  Record<string, string | string[]>`, read from the response **before** it is returned: every header through
  `forEach`, except `set-cookie`, which is taken **only** from `getSetCookie()` as an array (T18 — the
  `headersOf` idiom would keep the clearing cookie and drop the token). `sweep_http`'s canary haystack gains the
  response headers, so the HTTP canary sweep widens by the same edit. The module comment's surface list says so.

## Phases

- [ ] **1 · Contract** · Done when: the two header parameters carry `x-veyq-visibility: token`, the five
      `handle` properties are `token`-classed, `InviteBootstrapSecret` carries `token`, the five declared response
      headers carry theirs (four `Set-Cookie` → `token`, `ETag` → `structure`; T19, question 6),
      `validate_field_visibility.py` requires a marker on every `in: header` parameter, every declared response
      header and every scalar component schema (each bitten: strip one → red naming it), codegen regenerated on
      both sides, `./gradlew check` in `backend/` and `npm run check` in `web/` green, ADR 0044 carries the dated
      note (its scope note named neither header kind).
- [ ] **1b · The recorder records response headers** (D6; inbox #226) · Done when: `WireEntry.responseHeaders`
      recorded as D6 says, the recorder's first unit test exists and B5 went red first, `sweep_http`'s haystack
      includes the response headers, and one `run_web_e2e.py` run prints per suite the count of non-empty
      `Set-Cookie` values recorded (the retained run predicts 27 in total — a 0 on a suite that signs in is the
      tell that the trap of T18 came back).
      Locators: `grep -n 'type WireEntry' -A8 web/src/test/wireRecorder.ts`;
      `grep -n 'haystack = ' -A3 scripts/run_web_e2e.py`.
- [ ] **2 · Needles + the DB and store sweeps** · Done when: `token_needles` in the orchestrator; a run green
      printing per suite the token count by kind, the raw hits (0) and the hash control met; B1, B2, B3, B6 red first.
- [ ] **3 · The logs, statement log and mailbox** (whichever of the two sibling CRs have landed — each is named in
      the phase note when this starts) · Done when: a run green printing the token count swept per surface; B4
      red first; the mailbox exemption (question 3) printed with the tokens it exempted.
- [ ] **4 · State and harvest** · Done when: `validate_doc_links.py`, `validate_changes.py`,
      `validate_field_visibility.py` green, and the whole glob green reporting how many ran.
  - Concern: a § *Capability tokens* section — the second needle set, its derivation, its two exemptions with
    their bounds, the hash control; § *What a green sweep does not prove* gains D3 and D5.
  - `docs/inbox.md` #203 and #226 → done.

## Bite tests, by kind

- **B1 · injection — the red-first demonstration.** Lever: in `SessionService`, add a second column write of the
  raw access token (a scratch column added by a throwaway migration, both under snapshot-and-restore). Red must
  name the suite, `token:accessToken`, and the table. A unit test would not see this; the existing slice tests
  stay green under the lever — run them first, that is the measurement behind question 1's "nothing pins it".
- **B2 · injection — the control.** Point the hash control at SHA-1 instead of SHA-256 → red naming the hash
  control, never "0 raw hits".
- **B3 · injection — derivation.** Remove the `token` marker from `ShareIdentityCreated.contributorToken` in a
  scratch copy of the contract the orchestrator reads → the printed token count for the contributor suites drops
  to the request-side copies only; strip all four → `token:contributorToken` absent from the count, and the phase
  records that the count, not a hit, is the tell (a marker removed is a visible diff — the concern already says so
  of canaries).
- **B4 · injection — a raw lookup.** Lever: `SessionService.resolve` queries by the raw token **and** by its hash
  (a scratch `OR`). The dump sweep stays green; the statement-log sweep goes red naming `token:accessToken`. Only
  runnable once the history CR's statement log exists; until then it is recorded as owed, not skipped silently.
- **B5 · injection — the recorder's cookie trap** (phase 1b). A `// @vitest-environment node` unit test arms
  `installWireRecorder` with `VEYQ_HTTP_RECORD_FILE` in a temp dir over a stub `fetch` that answers with two
  `Set-Cookie` headers in `SessionCookie.issue`'s order (token cookie, then the clearing one) and asserts both
  are recorded, token first. Lever: read `set-cookie` through the `forEach` loop with the other headers → red,
  only the clearing cookie recorded (T18 measured that this is what the idiom does). Second lever: drop the
  response-header field → red. The test also asserts the caller's `Response` body is still readable — the
  recorder's existing clone-before-read promise, which nothing tests today.
- **B6 · injection — the derivation reaches the cookie.** On the phase-2 run, the printed `token:Set-Cookie`
  count is non-zero for every suite that signs in; strip the `token` marker from `verifyLogin`'s `Set-Cookie`
  in a scratch copy of the contract → that count drops to 0 while the suite stays green, recorded as B3 is (the
  count, not a hit, is the tell).

## Definition of done

`scripts/run_web_e2e.py` green with the token needle set applied to every live surface, printing per surface the
token count by kind and the hash control met; B1–B3, B5, B6 (and B4 where its surface exists) went red first; the contract
edits landed contract-first with both surfaces' checks green; the guard glob green with its run count reported.
Proof is a local run over the committed tree (no remote, [changes/README](README.md) § trust-chain guards).

## Retrospective

<!-- filled at close -->
