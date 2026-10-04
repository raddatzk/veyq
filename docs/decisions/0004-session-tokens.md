# ADR 0004 — Session tokens: opaque, server-stored, rotating access + refresh in revocable families

- Status: Accepted
- Date: 2026-06-25
- Implementation: the **opaque two-token rotating scheme** (access + refresh, families, reuse
  detection ⇒ family revocation) is **built** (`features/account/login/*`, `veyq_session_family` / `veyq_refresh_token` / `veyq_access_token`). The
  **management half** — listing the account's families and revoking one remotely
  ([uc.account.sessions](../features/account/use-cases/uc.account.sessions.md)) — is **built**:
  `SessionService.listActiveFamilies` / `revokeFamilyForUser` / `revokeOtherFamilies` behind `SessionResource`
  (`GET /api/sessions`, `DELETE /api/sessions/{id}`, `POST /api/sessions/revoke-others`, contract
  `auth.openapi.yaml`), covered by `SessionManagerSliceTest`; the web client is
  `web/src/features/account/sessions/`. The **one-generation grace** below (refined 2026-09-21 from inbox
  #123) is **built** in `SessionService.rotate`: a `SUPERSEDED` token exactly one generation behind `CURRENT`
  re-mints under the orphan's own generation, any other distance revokes the family — pinned by
  `LoginSliceTest` (the grace block, plus `replayedSupersededRefreshRevokesFamily` for distance two). The family
  row lock that decides the distance is pinned by `LoginSliceTest.theOrphanAndTheCurrentTokenPresentedTogetherCannotForkTheChain`
  and `SessionManagerSliceTest.aRefreshRacingARevokeCannotBringTheFamilyBack`.

## Context

After challenge-response authentication ([ADR 0003](0003-operator-blind-password-auth.md)) the server
issues a **session**. The session form was deliberately left open by
[uc.account.login](../features/account/use-cases/uc.account.login.md). Forces on the choice:

- **Operator-blind.** A session gates *a session + server role over ciphertext* — it **never** unlocks
  content (auth ≠ unlock, see [Account & Key Model](../features/account/enablers/en.account.key-model.md)). So a stolen
  session yields metadata + the ability to operate over ciphertext, **never plaintext**. The session
  layer must not become a metadata leak either (no cleartext device fingerprints server-side).
- **Self-hostable, single-instance default.** Stateless horizontal scaling — the one real argument for
  JWTs — carries little weight here.
- **The refresh token must not be readable by script.** An httpOnly cookie is what gives that; a token in
  the response body is one XSS away from exfiltration.
- **Theft detection.** Instant revocation handles the *detected* compromise; we also want to catch the
  **undetected** one (a cloned token we were never told about).

## Decision

**Opaque, high-entropy tokens, server-stored as hashes**, in a **two-token rotating scheme** grouped
into **named session families**. No JWTs.

- **Two tokens.** A short-lived **access token** rides every request; a long-lived **refresh token** is
  sent only to the refresh endpoint. Both are opaque and validated against server state; the server
  stores only their **hashes** (a DB dump yields no usable session — consistent with `authPublicKey`
  being a non-credential).
- **Session family = one login/device.** A successful login creates a family — and, since
  [ADR 0059](0059-device-linking.md) §2, so does a **device-linking grant**: an unlocked session may mint one
  further family for its own account and seal its refresh token to the device it is onboarding, because veyq's
  auth keypair is password-derived and a device that never learns the password could otherwise never reach the
  server. The second origin changes nothing else — such a family is listed, rotated and revoked like any
  other — and it is **indistinguishable**: the family row records no origin, so no endpoint can key a rule on
  one. That is a decision, not an omission, and it is what settles the transport question below: cookie
  issuance cannot be narrowed to delegated families without first making them a recognisable class.
  **Refresh rotates:** each refresh mints a new access + new refresh token and supersedes the prior
  refresh (numbered generations).
- **Reuse detection.** Presentation of a **superseded** refresh token is a clone signal ⇒ **revoke the
  entire family** — with the one-generation exception decided below, which is what separates a clone from a
  client that never received its successor. This catches the undetected-theft case that proactive revocation
  misses. To avoid a denial-of-service where a guessed `familyId` force-revokes a family, the superseded
  token's **secret is verified** before revoking: only a presented token whose *hash* matches a stored row
  can revoke anything, so a family cannot be killed by anyone who does not already hold one of its tokens.
  **Retention is the full refresh TTL, not two generations.** Every superseded row survives until
  `expiresAt` (30 days) and each one is a family-killer at any distance but one from the family's current
  generation; the purge matches strictly on expiry. An earlier wording here claimed the server kept only *"current + previous
  generation hash"* and that anything older was "merely invalid" — that was never built, and it is corrected
  rather than deferred. What the grace decision below restores is its *intent*: the pair (current, previous)
  is the only distinction the rule needs, and it is now the distinction the code actually draws.
- **Refresh expiry ⇒ full re-login** (challenge-response). Sliding lifetime via refresh; idle-expiry on
  the refresh token.
- **Unnamed families — the name lives off the family entirely.** A login creates a family the server can
  identify only by an opaque **family id**, and it stores **no name for it, not even ciphertext**. The
  display name a user sees comes from the client's **Master-Key-sealed device register** — the same
  sealed-synced-blob shape as
  [en.account.identity-pinning](../features/account/enablers/en.account.identity-pinning.md) — which also
  carries the device↔family join. So the "active sessions" view, which is shown only on an **unlocked**
  device, shows names; listing and revoking themselves need only a session, which is what lets a **locked**
  device sign every device out without a list
  ([uc.account.sessions](../features/account/use-cases/uc.account.sessions.md) E3). What this settles:
  - **A per-family name ciphertext must not come back.** It was the earlier design here, and it carried a
    standing obligation: a name blob resent unchanged across logins lets the operator **correlate** a
    device's re-logins and re-link otherwise-unlinkable families, so every login had to re-encrypt with a
    fresh nonce — a rule no test can see being broken. Keeping the name in the register removes the
    mechanism instead of guarding it: with no per-family ciphertext there is nothing to correlate.
  - **Why the register, not the family.** Auth produces only a signature, no symmetric key — the account key
    exists only after **unlock** (auth ≠ unlock), so a name can never be attached in the login call wherever
    it is stored. And a **device outlives its logins**: naming the device rather than each family is one
    write instead of one per login, and it survives the family it was written during.
  - **Best-effort as a seed, not a contract.** A browser cannot read the machine/hostname, so the initial
    label is coarse — a browser + platform guess, never a device identity. Because it lives in a
    user-owned register rather than on a family, the user can **rename it**; two identically-guessed laptops
    are a starting point, not a dead end.
  - The operator sees opaque family ids, two timestamps per family (created, last used — not rounded) and a
    device *count* — **never** a cleartext
    device/user-agent string, and no per-family blob at all. (A server-generated cleartext codename was
    considered and **rejected** — operator-meaningless, and superseded by client-side naming.)
- **Transport — a cookie for a browser, and one body-carried exception that exists to be spent.** The refresh
  token rides an httpOnly + Secure + SameSite **cookie**; for a browser that is the only form it is ever kept
  in, because a token in the response body is one XSS away from exfiltration (§ Context). `/api/token/refresh`
  answers in kind — cookie in, cookie out; body in, body out — and it still permits
  `RefreshRequest.refreshToken`, which **no client sends**.
  What is no longer unused is the body *form*: a device-linking grant (§ Session family, and
  [ADR 0059](0059-device-linking.md) §2) hands a refresh token to a device that holds no cookie yet, so a body
  is the only way it can arrive. That device converts it once, at **`POST /api/token/adopt`**, which rotates
  the presented token and returns its successor in a cookie. The handed-over token is therefore a **one-shot
  bearer** — readable by script for the span of a single request, and superseded by the time the response
  lands. Adoption changes **transport, never authority**: its caller must already present a valid CURRENT
  refresh token, which is exactly what refresh demands, and what comes back is strictly less exfiltrable than
  what went in. So it is deliberately **not** gated on how the family was born — it could not be, and the
  rejected alternative below says why that is the right way round.

## Decision — the one-generation grace: a benign re-presentation rotates, it does not revoke

**A presented `SUPERSEDED` refresh token whose generation is exactly one behind its family's `CURRENT`
rotates — re-minting the successor under the *same* generation number and superseding the orphaned one.
A distance of zero or of two or more stays a clone signal and revokes the family.** The bound is the
**generation distance**, deliberately not a time window.

| distance | who is presenting | outcome |
|---|---|---|
| 1 | a client that never received its successor | rotate, re-minting at the successor's own number |
| **0** | someone holding a successor that a grace rotation already replaced | **revoke** |
| ≥ 2 | an older generation — an ordinary replay | revoke |

**Distance zero is the detector, and it is what makes the rule closed rather than merely lenient.** It can
only arise after a grace rotation has happened, which is exactly the moment two parties may hold tokens for
one family: the one that re-rotated, and the one holding the successor it orphaned. The second to present is
at zero and takes the family down.

**Why this is not a softening of reuse detection.** Whoever holds the orphaned successor is caught on their
*next* refresh: the grace re-mint superseded it under its own number, so presenting it lands at distance
**zero** and revokes the family. Detection is therefore not removed but **deferred by at most one access
token's lifetime** (15 minutes), because that is how long a holder can operate before it must refresh. A
time window buys exactly the same deferral and loses the case below, which is why it is rejected.

**The case that forces this, measured** (2026-09-21, `main` @ `c0add4e`, live backend on MariaDB 11.4).
A refresh whose **response never reaches the client** already committed server-side: the rows go to
`[gen 1 SUPERSEDED, gen 2 CURRENT]` while the browser still holds gen 1 in its cookie. The next page load
presents gen 1 — and before this grace that was read as a clone and **revoked the family**. So a single
dropped response permanently ended a session, with no attacker, no second tab and no concurrency involved.
The same shape arrives by concurrency: six simultaneous refreshes of one token yielded `1 × 200` and
`5 × 401`, and all five losers are at distance 1. A genuine replay is not: after a chain of three
rotations the oldest token sits at distance 3 and still revokes, as it must. **The distance is therefore the
exact discriminator between the benign set and the clone set** — that is the measurement this decision
rests on, not an intuition about windows.

**Why not a time window.** The benign late case is a person coming back — a reload minutes or hours after
the dropped response. Any window short enough to keep theft detection meaningful is far too short for that,
so a window would fix the concurrency half and leave the half that motivated the rule.

**Why the grace does not increment, and how that was found.** The first form of this decision said the
grace *"rotates normally, superseding the orphaned successor"* — i.e. incremented. Traced against the
measured concurrency before any code was written, it fails the case it exists for. Six simultaneous
refreshes of one token all reach the row (measured: `1 × 200`, `5 × 401`), so under an incrementing grace the
second presentation mints generation 3 and **the third is at distance 2 and revokes** — the rule collapses at
three concurrent callers. The same increment breaks the security half: two parties refreshing alternately
would each sit at distance 1 forever and never be detected. Re-minting under the successor's own number fixes
both at once: every further presentation of the same stale token stays at distance 1, and the party holding
the orphaned successor lands on zero.

**So generations are NOT unique within a family, and the rule does not need them to be.** It reads one row —
the family's `CURRENT` — and compares one integer. What *is* invariant is that there is exactly one `CURRENT`
row per family: `mint` has two call sites (a new family at generation 1, and rotation), and rotation
supersedes before it mints.

**The distance is decided under the family's row lock.** The rule reads two rows, the presented token and the
family's `CURRENT`, and the presented token's own row lock orders only presentations of that same token. An
orphan (distance 1) and the current token lock different rows, so without a common lock both measured a
`CURRENT` the other was replacing: both rotated, the family held two live chains, and neither holder ever landed
on distance zero — the detector above silenced exactly when an attacker presents a stolen orphan alongside the
victim's refresh. Measured on 2026-10-01 against H2: 3 of 10 rounds forked with a barrier alone, while either
serial order revokes (distance 0 or 2). The same unlocked read let a refresh racing a revoke save the family back
to `ACTIVE` after the revoke had answered `204`. So `SessionService.rotate` locks the family row **first**, then
the token rows, on every path. Every rotation and every revoke of a family write that row, so they all queue on
it: the distance is computed against committed state, and a revoke lands either before the rotation reads the
family (the refresh answers `401`) or after it commits (the family ends `REVOKED`). The reverse order — token row,
then family — deadlocks when the orphan and the current token arrive together, and nothing retries that deadlock:
the fork race test reds on it. The `CURRENT` row is read with a lock as well, though the family lock already
orders its writers: a plain read at MariaDB's REPEATABLE READ saw the snapshot fixed before the lock wait, so the
loser of a same-token race found the presented row itself as `CURRENT` and revoked the family (800 of 800 races,
inbox #302). READ COMMITTED ([ADR 0078](0078-production-reads-committed-like-its-tests.md)) closes that alone; the
locking read keeps the decision correct at any isolation, and costs nothing the grace does not take anyway — it
writes that row.

**No schema change.** The distance is `CURRENT.generation - presented.generation`, read via
`family_id + status`, an index that already exists. This is also the first time `generation` is **read**:
before the grace it was written at mint and rotation and compared nowhere, which is why the drift corrected
above could sit in the ADR unnoticed.

**It reaches `POST /api/token/adopt` too, because adoption *is* a rotation.** A device whose adopt response
is lost is in precisely the position this rule exists for, and recovers by the same distance-1 path. What
changes is the prose, not the ceremony: the grant token stays a one-shot bearer, and a second presentation is
still fatal — at distance zero rather than as an unconditional clone signal. The contract text saying a second
presentation revokes the family is therefore narrowed, not deleted.

**What this does not settle.** Clients still owe a shared in-flight refresh per tab — this decision makes a
concurrent refresh *harmless*, not *free*, and a client that fires six of them still wastes five rotations.
The deadlock the same concurrency once provoked in the unique index over token hashes (a `SQLSTATE 40001` that
reached the client as a `500`) no longer forms: two refreshes of one token queue on the family row before either
touches that index — 0 deadlocks in 1 600 family-first refreshes on MariaDB 11.4, against 17 token-first (inbox
#262). The bounded retry that once answered it is gone, so a deadlock here is a lock-order defect and looks like one.

## Decision — web refresh cookie lifetimes (session vs persistent)

The web cookie transport comes in **two lifetimes**, chosen by the client at login via the optional
`LoginVerifyRequest.sessionScope` (`SESSION` | `PERSISTENT`).

- **`PERSISTENT`** — `Max-Age` = the refresh TTL; survives a browser restart. This is web "remember me".
- **`SESSION`** — **no `Max-Age`** (a *session cookie*): survives page reloads but is dropped when the browser
  closes. This is the web default ("until I close the browser"), matching the conventional web norm.

Because a cookie does not report its own `Max-Age` back to the server, the lifetime is encoded in the **cookie
name** — `veyq_refresh` (persistent) vs `veyq_refresh_session` (session). Refresh re-sets the **same-named**
cookie (so a rotation never silently upgrades/downgrades the lifetime); logout clears **both**. The real expiry
backstop remains the **server-side refresh TTL** + reuse detection — not the cookie lifetime, which a browser's
session-restore ("continue where you left off") may extend across a restart anyway. `remember` stays in the
contract as the superseded flag and is honoured (→ `PERSISTENT`) only when `sessionScope` is absent. Both lifetimes carry the **same opaque token** — no key material — so the server stays operator-blind.
The web client (cr-0055) is fully cookie-based (it keeps no body refresh token); see ADR 0005 §22 for how the
**vault key** is persisted to match each lifetime.

**Adoption is the only place besides login where a lifetime is *chosen*.** A device onboarded by a grant never
answered "stay signed in", and it holds no cookie to inherit a lifetime from — so `POST /api/token/adopt`
carries the same `sessionScope` login does, and the device **being onboarded** answers it, never the one
granting: only the person holding the new machine knows whether it is theirs (uc.account.onboard-device E5).
Refresh is untouched by this and still cannot raise or lower a lifetime, which is precisely why the choice
needed its own verb instead of a new field on `RefreshRequest` — a `sessionScope` there would let *every*
refresh re-decide its own lifetime, breaking the same-named-cookie rule this section rests on.

## Consequences

- **Instant revocation** is intrinsic (delete / mark the family revoked) — logout, "log out everywhere,"
  and theft response are all one operation.
- **Undetected theft is caught** by rotation + reuse detection — the gap a single revocable token leaves
  open, now with the theft's *discovery* deferred by at most one access-token lifetime (§ the one-generation
  grace) in exchange for sessions no longer dying of a dropped response.
- **A lost or raced refresh costs a rotation, not the session.** Before the grace rule, every interrupted
  refresh response and every pair of simultaneous refreshes ended the family; a client could provoke it by
  ordinary means and a flaky network by none at all.
- **Operator stays blind.** The server sees opaque family ids, two timestamps per family, and a device
  *count* — never a cleartext device fingerprint, and no name in any form. Listing and revoking work at the
  **auth layer** (no unlock needed); **reading** the names needs unlock (they are sealed under the Master Key),
  and the sessions view is shown only unlocked — a locked device gets sign-out-everywhere, not a list.
- **Smaller blast radius.** The powerful long-lived credential travels only to the refresh endpoint, not
  on every request.
- **Cost:** more moving parts than a single token — a refresh endpoint, rotation/generation bookkeeping,
  and client retry-on-`401`→refresh logic. Accepted for the theft-detection + session-management value,
  which the user explicitly wanted in the foundation rather than as later hardening.
- **Rejected — stateless JWT access tokens:** non-revocable, so they need a short TTL **plus** a denylist
  anyway (re-introducing state), with `alg`/key-management pitfalls; the only upside (stateless scale)
  is irrelevant for a self-hosted instance.
- **Rejected — single opaque token (no refresh):** simplest and already revocable, but no
  undetected-theft detection, and the long-lived credential rides every request. The family/rotation
  machinery — and the named-session management it enables — justified building it now.
- **Rejected — an `origin`/`kind` column on the family, so that adoption could accept only delegated tokens:**
  it would turn the second origin into a recognisable class, which the family bullet deliberately refuses, and
  it buys nothing that can be named — whoever presents a valid CURRENT refresh token already holds that
  session, and adoption hands back a *less* reachable form of it. The narrower variant, keying adoption on the
  pairing channel instead of on the family, is not merely undesirable but **impossible**: collecting the grant
  marks the channel spent, so by the time a device could redeem, the row it would be checked against is already
  terminal. Restricting adoption would therefore cost a migration, a contradiction, and a change to the pairing
  ceremony's single-use rule, to remove an exposure nobody could state.
- **Rejected — a time-bounded grace window instead of the generation distance:** it covers the concurrent
  case and misses the one that motivated the rule. The benign late re-presentation is a person reloading
  minutes or hours after a dropped response, and any window short enough to keep theft detection meaningful
  is far shorter than that. It also buys no extra safety: both forms defer discovery by the same one access
  lifetime.
- **Rejected — answering `401` without revoking, inside the window:** it saves the family and not the
  session. The client's cookie still holds the dead token, so every subsequent attempt fails the same way and
  the person must sign in again regardless — the family surviving is not an outcome anyone can observe.
- **Rejected — keeping only two generations and purging the rest,** which is what this ADR wrongly claimed
  for a year: it would make an older token *silently invalid* instead of a clone signal, deleting the
  tripwire the reuse-detection bullet exists for, and buying only a little storage.
- **Rejected — cleartext device names server-side** (e.g. storing the user-agent as the session label):
  an operator-blind violation. Names are **E2EE** (client-built, encrypted under the account key).
- **Rejected — server-generated cleartext codename** (e.g. `violet-otter`): operator-meaningless and
  unlock-free, but superseded by client-side naming, which gives **real, recognizable** names at no
  blindness cost. Not worth carrying a second naming mechanism.
- **Rejected — an encrypted name stored per family** (`PUT …/sessions/{id}/name`): operator-blind on its
  face, and it was this ADR's own earlier design, but it re-introduces a linkability surface that only a
  never-forgotten per-login re-encryption keeps shut — and it is the *second* naming mechanism, next to the
  device register that has to exist anyway for the device↔family join. One naming mechanism, off the family.
