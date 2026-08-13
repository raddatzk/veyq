---
id: cr-delegated-session-cookie-transport
title: A delegated session is adopted into the cookie transport every other web session uses
status: done
satisfies: [uc.account.onboard-device]
adrs: ["0004", "0059", "0005", "0051", "0044", "0026"]
invariants:
  - Operator-blind E2EE — this endpoint moves an opaque token; no key material, no new server-side fact
  - A session grants no plaintext (auth ≠ unlock, ADR 0003)
  - A refresh token is not readable by script on the web (ADR 0004 § Context)
  - A refresh never raises or lowers a cookie's lifetime (ADR 0004 § lifetimes)
  - A session family records no origin, so nothing may gate on one (ADR 0004 § Session family)
  - No code ever names a server (ADR 0059 §4) — `validate_pairing_shapes.py` stays green **and** unchanged
---

## Goal

Give the session minted by a device-linking grant the same transport every other web session gets. Today
`POST /api/pairing/delegate` returns its refresh token in the response **body**, and a browser cannot keep one:
`boot()` revives a session only from the httpOnly cookie, and `TokenResource` sets a cookie only for a request
that already carried one. An onboarded device would be signed in for exactly one page load, holding a
`DEVICE_KEY` wrap it can never reach again and no password to fall back to.

This adds one verb — **`POST /api/token/adopt`** — that takes the handed-over token, rotates it, and returns
the successor in a cookie plus the session's own family. It is a **contracts → backend** correction to the
built server half. It builds no UI; it is what unblocks phase 3 of `cr-pairing-add-a-device-web`.

## Context & constraints

The durable state this builds on, all of it already written — **the decisions live there, not here**:

- [ADR 0004](../docs/decisions/0004-session-tokens.md) — § *Transport* names `adopt` and its one-shot-bearer
  rule; § *Session family* says a family records no origin **by decision**, which is what makes narrowing
  impossible; § *web refresh cookie lifetimes* says adoption is the only place besides login where a lifetime
  is chosen, and why that could not be a field on `RefreshRequest`; § *Consequences* carries the rejected
  origin column and the rejected channel-keyed narrowing.
- [ADR 0059](../docs/decisions/0059-device-linking.md) §2 — the granted device adopts **before** it unlocks,
  registers or enrols, and answers *"stay signed in"* before its session exists.
- [uc.account.onboard-device](../docs/features/account/use-cases/uc.account.onboard-device.md) — main flow
  step 6, E5, and the criterion this CR owes:
  `the-onboarded-device-keeps-the-session-it-was-granted`.
- [ADR 0005](../docs/decisions/0005-unlock-methods-and-device-trust.md) §22 — the Off tier, now carrying the
  password-less carve-out a linked device needs.
- [ADR 0051](../docs/decisions/0051-declarative-rate-limit-posture.md) — every endpoint declares a posture.
- `contracts/api/auth.openapi.yaml` tag `token` — the wire this CR extends. The `pairing` tag is **untouched**.

**Three things are inherited and are not re-decided here.** If one is wrong, that is an escalation:

1. `PairingDelegateResult` stays `{refreshToken}` — the grant token is still handed over in a body, because a
   device with no cookie has no other way to receive one.
2. `collect` spends the channel, so the channel is terminal before a device could redeem against it. This is
   why adoption is keyed on the token and not on the handle.
3. Reuse detection is unchanged. Adoption rotates through the existing `SessionService.refresh`, so a second
   presentation of the spent grant token revokes the family — deliberately.

**Measured facts about the built halves** (substrate: `main` @ `28d4591`, clean tree):

| Fact | Where |
|---|---|
| `TokenResource` sets a cookie **iff** the request carried one: `if (fromCookie != null)` | `backend/.../login/TokenResource.kt:36` |
| `@PermitAll` + `@RateLimit(key = RateLimitKey.IP)` sit on `TokenResource` at **class** level | `backend/.../login/TokenResource.kt:23-25` |
| `SessionCookie.issue(token, persistent)` already writes one cookie and clears the other lifetime | `backend/.../login/SessionCookie.kt:40-41` |
| Cookie `Path` is `/api/token/refresh` — a `Set-Cookie` from another path still lands there | `backend/.../login/SessionCookie.kt:66-77` |
| `issueDelegated` is a projection of `issue()`; the family gets no origin, and no migration adds one | `backend/.../login/SessionService.kt:80-84`; `V5`, `V46` |
| `sessions.refresh(token)` rotates and revokes the family on a `SUPERSEDED` presentation | `backend/.../login/SessionService.kt:93-108` |
| `boot()` revives only via `refreshSession()` with an empty body | `web/src/app/boot.ts:43`, `web/src/features/account/login/login.ts:50-53` |
| Write moment 1's device↔family join is keyed by family id, and `session.familyId` is set **only at login** | `web/src/features/account/deviceRegister.ts:292,300`; `web/src/app/session.ts:14-20` |

## Decisions

### D1 — `POST /api/token/adopt`, a second method on `TokenResource`, tagged `token`

Request `TokenAdoptRequest { refreshToken, sessionScope }`; response **`SessionResponse`** + `Set-Cookie`.
Body:

1. `sessions.refresh(presented)` — the existing rotation, unchanged. Null → `401`, the same failure refresh
   gives, with no distinction between unknown, expired and superseded.
2. `Response.ok(SessionResponse(accessToken, family, refreshToken = null))
   .cookie(*cookie.issue(rotated.refreshToken, persistent = scope == "PERSISTENT"))`.

A **cookie present on the request is refused**, not honoured: adoption is for a device that has no session
here, and a browser that already holds one would otherwise silently swap its own session for another. That is
a `400`, and it is the one behaviour this endpoint does not inherit from refresh.

*Rejected — the pairing tag.* `validate_pairing_shapes.py` pins seven schema names and iterates **only** over
those, so an eighth pairing schema would be **unchecked** and ADR 0059 §4's "no code names a server"
guarantee would gain a silent hole. On `token` there is no such expectation to undermine, `@PermitAll` and
`@RateLimit(IP)` are inherited from the class rather than re-invented per method (measured above), and the
verb genuinely is a token-transport operation: it names no handle and touches no channel.

### D2 — The response is `SessionResponse`, not `TokenPair`, and the reason is a measured need

`TokenPair` carries no family id. Write moment 1 writes `reg.families[familyId] = thisDeviceId` — ADR 0004's
device↔family join — and `session.familyId` is set only at login, never by a refresh. An adopting device that
learned no family id would write a register entry that no session row can find, so it would never appear
**named** in `uc.account.sessions`, which the use-case states as a postcondition. `PairingDelegateResult`
cannot supply it either: it is pinned to `{refreshToken}` and widening it turns `validate_pairing_shapes.py`
red.

`SessionResponse` is what login already returns, which is the right reading of this endpoint: **it is the
login-equivalent for a device that has no password.** It embeds `SessionFamily`, whose property set
`validate_session_family_shape.py` pins — reusing it adds nothing there, so that guard stays green and
untouched.

### D3 — `sessionScope` is required, and the onboarded device answers it

The enum is the existing `SESSION | PERSISTENT`. It is **required** here where it is optional on
`LoginVerifyRequest`, because there is no legacy caller to be lenient towards and no cookie to inherit a
lifetime from — a default would be the server quietly answering a question ADR 0059 §2 assigns to the person
holding the new machine. `SESSION` → `veyq_refresh_session`; `PERSISTENT` → `veyq_refresh`. The legacy
`remember` boolean is **not** carried over.

### D4 — Not narrowed to delegated families, and the alternatives are dead rather than merely worse

ADR 0004 § Consequences owns this decision; what binds here is that the endpoint performs **no origin check**,
and that no migration is written. The two narrowings and why they are not built:

- **an `origin` column on the family** — contradicts ADR 0004's own "the second origin changes nothing else",
  and buys nothing statable: the caller already holds a valid CURRENT refresh token, and adoption returns a
  *less* exfiltrable form of it.
- **keying on the pairing handle** — impossible, not undesirable. `collect` marks the row `COLLECTED`,
  `TERMINAL` contains `COLLECTED`, and `isLive` excludes terminal rows, so the channel is dead the instant the
  grant is picked up.

### D5 — The one-shot property is rotation's, not a new mechanism

Nothing new enforces single use. The presented token is superseded by the rotation, so a second adoption — or
an adoption after the device already refreshed once — is the clone case and takes the family down. That is the
correct outcome and it is asserted, not merely allowed to happen. Its live control is the adopted session
refreshing normally **first**, since a family that revokes on everything satisfies the negative just as well.

The narrow failure this leaves is stated rather than solved: if the connection drops after the server rotated
but before the response lands, the grant token is spent and no cookie was set, so the device needs a fresh
pairing. That is formally identical to a lost login response, and a retry mechanism would have to weaken
exactly the single-use property this decision rests on.

### D6 — The contract documents the `Set-Cookie` on this operation, and only on this one

*Measured:* `grep -n "Set-Cookie" contracts/api/auth.openapi.yaml` returns **nothing** — the entire web
transport is absent from the source of truth, mentioned only in prose descriptions. This CR does not fix that
sweep, but it will not add a **second** undocumented cookie-setting operation: `adopt` declares the response
header, because setting that cookie *is* the operation's whole purpose. Whether login and refresh should be
retrofitted is a separate question and gets an inbox row, not a phase here.

## Inventory

Locators, not tallies — run them at implement time and read the list.

| What | Command |
|---|---|
| The class-level posture `adopt` inherits | `rg -n "@PermitAll\|@RateLimit\|class TokenResource" backend/src/main/kotlin/com/veyq/app/features/account/login/TokenResource.kt` |
| The cookie API and its lifetime split | `rg -n "fun issue\|fun read\|NAME_PERSISTENT\|NAME_SESSION\|PATH =" backend/src/main/kotlin/com/veyq/app/features/account/login/SessionCookie.kt` |
| How login builds the same response shape | `rg -n "SessionResponse\|sessionScope\|cookie.issue" backend/src/main/kotlin/com/veyq/app/features/account/login/LoginResource.kt` |
| Rotation + reuse detection, reused unchanged | `rg -n "fun refresh\|SUPERSEDED\|REVOKED\|withLock" backend/src/main/kotlin/com/veyq/app/features/account/login/SessionService.kt` |
| **Every guard anchoring on what this touches** | `rg -ln "auth.openapi\|SessionFamily\|x-veyq-visibility\|Pairing" scripts/*.py` |
| The exact property sets two guards pin | `rg -n "ALLOWED\|EXPECTED" scripts/validate_session_family_shape.py scripts/validate_pairing_shapes.py` |
| Visibility classes a new property must pick from | `rg -n "^CLASSES\|\": \"" scripts/validate_field_visibility.py` |
| Existing token/session slice tests + the concurrency idiom to copy | `rg -n "fun refresh\|fun replayed\|CyclicBarrier\|rememberLoginSets" backend/src/test/kotlin/com/veyq/app/features/account/login/LoginSliceTest.kt` |
| Whether any `Set-Cookie` is declared anywhere in the specs | `rg -n "Set-Cookie" contracts/` |
| Flow surfaces naming this work | `rg -n "adopt\|delegated session\|body token" docs/inbox.md changes/` |

## Phases

- [x] **1 contracts — the verb.** `POST /api/token/adopt` on `contracts/api/auth.openapi.yaml`, tag `token`:
      `TokenAdoptRequest { refreshToken (required), sessionScope (required, SESSION|PERSISTENT) }`, `200` →
      the existing `SessionResponse` with the `Set-Cookie` response header declared (D6), `400` for a request
      that already carries a session cookie, `401` for a rejected token. Both new properties carry
      `x-veyq-visibility` (`token` for the refresh token, `enum` for the scope) — that guard is the one this
      phase actually trips.
      *Done when:* `for f in scripts/validate_*.py; do python3 "$f"; done` green, **and**
      `validate_pairing_shapes.py` + `validate_session_family_shape.py` are green with **no edit to either** —
      that pair being untouched is the structural claim of D1/D2, so a diff on them is a failed phase, not a
      fixup.
- [x] **2 backend — the handler.** A second method on `TokenResource` (no new resource class, so the class's
      `@PermitAll` + `@RateLimit(IP)` carry over; do not re-declare them). Rotate via the existing
      `sessions.refresh`, refuse a request that carries a session cookie, issue via the existing
      `SessionCookie.issue`. No migration, no new column, no change to `SessionService`, `PairingHandler` or
      `PairingResource`.
      *Done when:* `./gradlew check` green — the **full** run, not the touched class, because the `%test` H2
      mem DB is JVM-shared — with slice tests for:
      - the pair that is D2: the response carries a **family id** and **no** body `refreshToken`, and it sets
        exactly one refresh cookie whose name matches the requested scope — asserted **per scope**, with the
        other lifetime's clearing cookie present;
      - the cookie is `HttpOnly`, anchored on the attribute, never on "a cookie was set";
      - the adopted session then refreshes **through the cookie alone** (the live control that adoption
        produced a working session, without which every negative below is vacuous);
      - re-presenting the spent grant token revokes the family (D5) — ordered **after** that control, and
        asserting the family is gone rather than that "it failed";
      - a request bearing a session cookie is refused (D1), with the same request minus the cookie succeeding
        as its positive control on the same predicate;
      - `@RateLimit` posture: `RateLimitPostureSweepTest` reddens by itself if the new method loses one — do
        not hand-assert it.
- [x] **3 coverage.** `docs/coverage/features/account/use-cases/uc.account.onboard-device.yaml` — move
      `the-onboarded-device-keeps-the-session-it-was-granted` in the backend gap from *"owed and not built"* to
      the ADR-0040 wording the rest of that gap already uses (the suite exists and passes locally; the missing
      thing is a CI run), and name the new tests so the later flip is mechanical. **Keep `status: planned` and
      both gaps** — `validate_coverage.py` forbids test refs under `planned`, and the standing reason (no CI
      run has ever executed here) is unchanged by this CR.
      *Done when:* `for f in scripts/validate_*.py; do python3 "$f"; done` green.

## Definition of done

`./gradlew check` green (full run), the coverage file updated as phase 3 states, and the whole guard glob
green: `for f in scripts/validate_*.py; do python3 "$f"; done`. `validate_pairing_shapes.py` and
`validate_session_family_shape.py` must be green **and unchanged** — this CR touches neither the pairing wire
nor the session-family read model, and a diff on either means a decision was made that this CR did not make.
The run is local over the **committed** tree; `trust-chain.yml` does not exist on `main`, so "green in CI" is
not claimable here (see `changes/README.md` § which jobs can even run).

**No new dependency. No migration.** If either appears, D4 was quietly reversed.

**On promoting a proof to a guard:** no new `scripts/` validator is added, and this sentence is the reason.
The invariants at risk are already covered — the two contract-shape guards named above stay green and
untouched by construction, and `validate_field_visibility.py` catches an undeclared new property. The one
thing genuinely unguarded is the **cookie's own attributes** (measured: `cookie` matches **zero** files in
`scripts/`), and that is not this CR's to close: a `scripts/` validator cannot see a runtime response header,
and the honest guard for it is the slice assertion on `HttpOnly` that phase 2 already owes. If cookie
attributes deserve a permanent check, it is a backend sweep test over `SessionCookie`'s call sites and it
belongs to its own CR, not smuggled in here.

**What this CR deliberately does not do.** It does not touch the web surface — phase 3 of
`cr-pairing-add-a-device-web` consumes it and is where `adopt` gets a caller. It does not fix inbox **#123**
(nothing serializes refresh in the web client): that defect predates device linking, touches every web
session, and the adopted session inherits it exactly as every other session already does. Fixing it here would
hide a general defect inside a feature correction.

## Retrospective

**Planning — the CR contradicted itself, and the contradiction was one `grep` away.** D2's whole argument is
*"the response must carry the family id"*; phase 2's constraint list says *"no change to `SessionService`"*.
But `RotatedTokens` is `(accessToken, refreshToken)` and `familyIdOf` is **private** — so the family id is
unreachable from the adapter, and the two statements cannot both hold. The refine session measured
`sessions.refresh` twice (the facts table cites `SessionService.kt:93-108`) and read it for *rotation
semantics*, never for *what it returns* — the exact shape of `changes/README.md` § upstream of every trap: the
claim did not feel like a claim, because "rotate via the existing refresh" reads as settled. The locator that
would have caught it is not exotic: **D2 names a value the CR never asked where it comes from.** Resolved by
escalation rather than silently — `RotatedTokens` gained `familyId`, which is additive, costs no read (the
rotation already holds the family row) and changes no rotation semantics, so it honours the constraint's
*intent* while breaking its letter. That call was the user's, not the implementer's, which is the right
place for it.

**Planning — D1's rationale describes a hazard that cannot occur.** *"A browser that already holds one would
otherwise silently swap its own session for another"* is false as written: the cookie's `Path` is
`/api/token/refresh`, so RFC 6265 path-matching means no browser ever sends it to `/api/token/adopt`. The CR
had that fact in its own measured-facts table one screen earlier and used it for the opposite direction (a
`Set-Cookie` from another path still *lands*). Nothing checks a document against itself — the same failure
`changes/README.md` records for `cr-instance-backup-and-restore`. The **rule** survives the correction (it is
a server-side rule against non-browser callers and against a later `Path` widening, and it is what keeps that
`Path` from quietly becoming load-bearing), so it was built as specified with the reason corrected in the
code comment rather than the wrong reason carried forward.

**Execution — I added ceremony on a remembered rule, then measured it away.** D3's whole claim is that
`sessionScope` is *required*, and the generated model carries no `@NotNull` — so I added one via
`x-field-extra-annotation`, reasoning from `backend/CLAUDE.md`'s "validation belongs in the spec". Removing it
again and re-running left the boundary test **green**: the generated property is non-null, so deserialization
already refuses a missing scope before the handler. The explicit annotations elsewhere on that spec are
`@NotBlank`, which catches a *blank* string nullability cannot see; an enum has no blank form. Kept the
measurement, dropped the annotation, and wrote the reason at both sites — the rule was right, its application
here was not, and *"is this already enforced?"* is a 35-second build, not a judgement call.

**What went right, and is worth keeping.** The CR's own completeness proof worked exactly as advertised:
after phase 1, `TokenResource` failed to compile naming the missing method, so "did I build all of it?" was
answered by the compiler rather than by reading. And the three bite tests (drop the cookie refusal; force one
lifetime for both scopes; answer with a family id the rotation did not produce) each went red **by name**,
each restored byte-identically against the snapshot — the uncommitted-baseline form, not `git checkout`.

**Process & collaboration.** Two directions. Mine: I nearly widened the phase-1 diff on my own judgement (the
now-stale `SessionResponse.refreshToken` description) without saying so up front — small and defensible, but
the honest move is to name a scope decision when making it, not in the summary afterwards. The user's: the
session prompt did the heavy lifting the CR could not — naming the three inherited forms, the guard traps, the
ordering constraint and the *"report, don't repair"* rule up front is what turned the `RotatedTokens`
collision into a two-minute escalation instead of a silent design decision. That framing should be the default
for implement sessions, not something re-derived each time.

**Next time:** at refine, for every value a decision says the response must carry, run one line that asks
**where that value comes from** — the function that produces it and its return type — and paste the output.
D2 named `family id` five times and never once looked at the type `refresh` returns.
