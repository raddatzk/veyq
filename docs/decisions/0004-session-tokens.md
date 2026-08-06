# ADR 0004 — Session tokens: opaque, server-stored, rotating access + refresh in named families

- Status: Accepted
- Date: 2026-06-25
- Implementation: the **opaque two-token rotating scheme** (access + refresh, named families, reuse
  detection ⇒ family revocation) is **built** (`features/account/login/*`, `V5__login_sessions.sql`). The
  **E2EE named-sessions half** — `PUT …/sessions/{id}/name` + the unlocked "active sessions" decrypting
  view (`uc.account.sessions`) — is **not yet built** (the contract reserves the family id; the name is
  "attached later"). This ADR records the agreed design for that half.

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
- **Session family = one login/device.** A successful login creates a family. **Refresh rotates:** each
  refresh mints a new access + new refresh token and supersedes the prior refresh (numbered generations).
- **Reuse detection.** Presentation of a **superseded** refresh token (an older generation) is a clone
  signal ⇒ **revoke the entire family**. This catches the undetected-theft case that proactive revocation
  misses. To avoid a denial-of-service where a guessed `familyId` force-revokes a family, the superseded
  token's **secret is verified** before revoking — the server keeps enough state (current + previous
  generation hash) to verify the realistic reuse window; an older, unverifiable token is merely *invalid*,
  not a family-killer.
- **Refresh expiry ⇒ full re-login** (challenge-response). Sliding lifetime via refresh; idle-expiry on
  the refresh token.
- **Named families, E2EE, operator-blind.** A login creates a family **without a name** — the server
  assigns only an opaque **family id**. The naming is **client-built and end-to-end encrypted**: once the
  device is **unlocked**, it derives a best-effort display name from its own environment
  (`navigator.userAgent` → a browser + platform guess), encrypts it **under the account
  key** and stores the ciphertext on the family (`PUT …/sessions/{id}/name`). A later "active sessions"
  view (any unlocked device of the account) lists families and **decrypts** each name; the locked view
  shows only the id + a coarse created-date. Constraints that fall out of this:
  - **Why post-unlock, not at login.** Auth produces only a signature, no symmetric key — the account key
    exists only after **unlock** (auth ≠ unlock). The encrypted name is therefore attached **after**
    unlock, not in the login call. (The plaintext name is known locally at login for immediate *local*
    display; the server copy — which exists so *other* devices can show this session — is written once
    unlocked.)
  - **Re-encrypt fresh per login**, never resend a cached ciphertext: an identical name blob reused across
    families would let the operator **correlate** a device's re-logins, re-linking otherwise-unlinkable
    families. A fresh nonce each time keeps families unlinkable.
  - **Best-effort, not a contract.** A browser cannot read the machine/hostname, so the name is coarse — a
    browser + platform guess, never a device identity. Two of the account's laptops can name themselves
    identically; that is accepted, not a bug to fix server-side.
  - The operator sees only **ciphertext name blobs**, opaque family ids, coarse timestamps and a device
    *count* — **never** a cleartext device/user-agent string. (A server-generated cleartext codename was
    considered and **rejected** — superseded by this E2EE scheme.)
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
- **Operator stays blind.** The server sees only ciphertext name blobs, opaque family ids, coarse
  timestamps, and a device *count* — never a cleartext device fingerprint. Revoking a session works at the
  **auth layer** (no unlock needed); **reading** the names needs unlock (they are E2EE), so the locked
  sessions view shows only id + coarse created-date.
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
  unlock-free, but superseded by the E2EE scheme, which gives **real, recognizable** names at no blindness
  cost. Not worth carrying a second naming mechanism.
