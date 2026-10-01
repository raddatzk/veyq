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
  - API-first — the three contract edits (question 2) land in `contracts/` first and are regenerated, not
    hand-written.
---

## Open for the owner

**Answered by the owner, 2026-10-01: every recommendation below is taken, and implementation has the go.** Read each question's recommendation as its decision. Where a question proposed a separate inbox row, that row exists in `docs/inbox.md`.

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
| T14 | the recorder | records request headers and textual bodies; **no response headers**, so a `Set-Cookie` refresh token is never observed | `grep -n 'requestHeaders\|responseBody' web/src/test/wireRecorder.ts` | measured |
| T15 | a design-level raw capability half | the read/contributor share id is stored raw as `veyq_share.id` by ADR 0029's decision ("half the capability"; the other half is the fragment key) and is classified `id`, not `token` | `grep -n 'half the capability' backend/src/main/resources/db/migration/V31__share.sql docs/decisions/0029-public-viewer-share.md` | measured |
| T16 | an unclassified scalar | `InviteBootstrapSecret` is a component schema of bare `type: string` with no marker, `$ref`'d by the two response-side `bootstrapSecret` properties; the field-visibility guard's container rule lets both through | `grep -n -A2 'InviteBootstrapSecret:' contracts/api/tenants.openapi.yaml`; `grep -n 'InviteBootstrapSecret' contracts/api/tenants.openapi.yaml` | measured |

L1 — kept out of the table, where its pipes would split the cell:

```sh
grep -rnE '\b(Log|LOG|log|logger)\.(debug|trace|info|warn|error)f?\(' --include='*.kt' backend/src/main | grep -iE 'token|secret|handle|link|password'
```

The probe is the refine's scratch script, not shipped: it pulls token-named fields and capability headers out of
every `http-*.jsonl`, searches every other retained file for the raw values, and every `*.sql` for their hex
SHA-256. Re-derive it at implement time; the shipped derivation is by contract marker (question 2), not by name.

## Decisions

- **D1 · A second needle set, not a second sweep.** `token_needles(recording, contract)` returns the run's
  capability tokens; each existing sweep takes it alongside the canaries and reports a hit as `token:<kind>` so a
  reader never confuses a leaked token with a leaked canary. Tokens are base64url/hex ASCII with no space, quote
  or backslash, so the raw form is the only needle (`canary_needles` would return it unchanged — asserted, not
  assumed, in the buildless guard).
- **D2 · Derivation is by marker over the recording** (question 2): walk every recorded JSON body against its
  operation's schema (the walk `sweep_http` already does for opacity), take every `token`-classed value; add the
  `Authorization` bearer and every header parameter marked `token`. The kind is the schema property name.
- **D3 · Refresh and pairing stay code-level facts, stated.** No suite yields either (T10), and the refresh
  cookie is invisible to the recorder (T14). The concern names both as swept-for-nothing rather than implying
  coverage; a suite that drives a refresh or a pairing extends the set for free, and recording `set-cookie` is
  proposed as its own inbox row with this refine, not built here.
- **D4 · The raw share id is out of scope, by ADR 0029** (T15) — it is `id`-classed and stored raw by decision.
  Whether a raw share id at rest is an acceptable *write* capability on contributor and uploader shares is a
  design question for a separate inbox row (proposed with this refine, not added by it), not a sensor gap.
- **D5 · The retained recording carries the raw tokens.** `.e2e-out/<stamp>/http-*.jsonl` already holds them
  (T10) — throwaway credentials of throwaway instances. Stated in the concern, because once `.e2e-out/` is
  uploaded from CI (inbox #205) they leave the machine.

## Phases

- [ ] **1 · Contract** · Done when: the two header parameters carry `x-veyq-visibility: token`, the five
      `handle` properties are `token`-classed, `InviteBootstrapSecret` carries `token`,
      `validate_field_visibility.py` requires a marker on every `in: header` parameter and every scalar component
      schema (each bitten: strip one → red naming it), codegen regenerated on both sides,
      `./gradlew check` in `backend/` and `npm run check` in `web/` green, ADR 0044 carries the dated note.
- [ ] **2 · Needles + the DB and store sweeps** · Done when: `token_needles` in the orchestrator; a run green
      printing per suite the token count by kind, the raw hits (0) and the hash control met; B1, B2, B3 red first.
- [ ] **3 · The logs, statement log and mailbox** (whichever of the two sibling CRs have landed — each is named in
      the phase note when this starts) · Done when: a run green printing the token count swept per surface; B4
      red first; the mailbox exemption (question 3) printed with the tokens it exempted.
- [ ] **4 · State and harvest** · Done when: `validate_doc_links.py`, `validate_changes.py`,
      `validate_field_visibility.py` green, and the whole glob green reporting how many ran.
  - Concern: a § *Capability tokens* section — the second needle set, its derivation, its two exemptions with
    their bounds, the hash control; § *What a green sweep does not prove* gains D3 and D5.
  - `docs/inbox.md` #203 → done.

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

## Definition of done

`scripts/run_web_e2e.py` green with the token needle set applied to every live surface, printing per surface the
token count by kind and the hash control met; B1–B3 (and B4 where its surface exists) went red first; the contract
edits landed contract-first with both surfaces' checks green; the guard glob green with its run count reported.
Proof is a local run over the committed tree (no remote, [changes/README](README.md) § trust-chain guards).

## Retrospective

<!-- filled at close -->
