# ADR 0004 — Session tokens: opaque, server-stored, rotating access + refresh in revocable families

- Status: Accepted
- Date: 2026-06-25
- Implementation: the **opaque two-token rotating scheme** (access + refresh, families, reuse
  detection ⇒ family revocation) is **built** (`features/account/login/*`, `V5__login_sessions.sql`). The
  **management half** — listing the account's families and revoking one remotely
  ([uc.account.sessions](../features/account/use-cases/uc.account.sessions.md)) — is **not yet built**: the
  contract reserves the family id, and today only the caller's own family can be ended, by logout. This ADR
  records the agreed design for that half.

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
  other. **Refresh rotates:** each refresh mints a new access + new refresh token and supersedes the prior
  refresh (numbered generations).
- **Reuse detection.** Presentation of a **superseded** refresh token (an older generation) is a clone
  signal ⇒ **revoke the entire family**. This catches the undetected-theft case that proactive revocation
  misses. To avoid a denial-of-service where a guessed `familyId` force-revokes a family, the superseded
  token's **secret is verified** before revoking — the server keeps enough state (current + previous
  generation hash) to verify the realistic reuse window; an older, unverifiable token is merely *invalid*,
  not a family-killer.
- **Refresh expiry ⇒ full re-login** (challenge-response). Sliding lifetime via refresh; idle-expiry on
  the refresh token.
- **Unnamed families — the name lives off the family entirely.** A login creates a family the server can
  identify only by an opaque **family id**, and it stores **no name for it, not even ciphertext**. The
  display name a user sees comes from the client's **Master-Key-sealed device register** — the same
  sealed-synced-blob shape as
  [en.account.identity-pinning](../features/account/enablers/en.account.identity-pinning.md) — which also
  carries the device↔family join. So an "active sessions" view on any **unlocked** device shows names, while
  the **locked** view shows the id + a coarse created-date; listing and revoking themselves need only a
  session. What this settles:
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
  - The operator sees opaque family ids, coarse timestamps and a device *count* — **never** a cleartext
    device/user-agent string, and no per-family blob at all. (A server-generated cleartext codename was
    considered and **rejected** — operator-meaningless, and superseded by client-side naming.)
- **Transport.** The refresh token rides an httpOnly + Secure + SameSite **cookie**. The contract still
  permits it in the request body (`RefreshRequest.refreshToken`) and the server accepts either, but **no
  client uses the body path** — it is unused surface, kept only because removing it is a contract break
  nobody has needed yet.

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

## Consequences

- **Instant revocation** is intrinsic (delete / mark the family revoked) — logout, "log out everywhere,"
  and theft response are all one operation.
- **Undetected theft is caught** by rotation + reuse detection — the gap a single revocable token leaves
  open.
- **Operator stays blind.** The server sees opaque family ids, coarse timestamps, and a device *count* —
  never a cleartext device fingerprint, and no name in any form. Listing and revoking work at the **auth
  layer** (no unlock needed); **reading** the names needs unlock (they are sealed under the Master Key), so
  the locked sessions view shows only id + coarse created-date.
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
- **Rejected — cleartext device names server-side** (e.g. storing the user-agent as the session label):
  an operator-blind violation. Names are **E2EE** (client-built, encrypted under the account key).
- **Rejected — server-generated cleartext codename** (e.g. `violet-otter`): operator-meaningless and
  unlock-free, but superseded by client-side naming, which gives **real, recognizable** names at no
  blindness cost. Not worth carrying a second naming mechanism.
- **Rejected — an encrypted name stored per family** (`PUT …/sessions/{id}/name`): operator-blind on its
  face, and it was this ADR's own earlier design, but it re-introduces a linkability surface that only a
  never-forgotten per-login re-encryption keeps shut — and it is the *second* naming mechanism, next to the
  device register that has to exist anyway for the device↔family join. One naming mechanism, off the family.
