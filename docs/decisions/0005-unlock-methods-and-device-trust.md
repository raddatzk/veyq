# ADR 0005 — Account unlock: one Master Key, many wraps; device trust, re-lock & method precedence

- Status: Accepted
- Date: 2026-06-25

## Context

[ADR 0003](0003-operator-blind-password-auth.md) split **auth** from **unlock**;
[ADR 0004](0004-session-tokens.md) settled **sessions**. This ADR settles the **unlock** side: how the
Master Key is opened on a device, how "stay unlocked" / device trust works **without** weakening 2SKD or
operator-blindness, and how a client chooses among several unlock methods.

Forces: operator-blind (the server holds only ciphertext wraps); the **2SKD floor** (password + Secret
Key); the **Secret Key must stay cold** (never at rest on a device); good UX (don't retype the Secret
Key, ideally passwordless on trusted devices); **reload ≠ session end**.

## Decision

1. **One Master Key, many wraps.** Exactly one Master Key per account; **one `MasterKeyWrap` per unlock
   method** (`PASSWORD`, `DEVICE`, `PASSKEY`, `RECOVERY`). Adding/removing a method adds/removes **one
   wrap**; content is never re-encrypted. (Binding basis: [en.account.key-model](../features/account/enablers/en.account.key-model.md).)

2. **The wrap blob is server-side (portable); the KEK source is what binds.** Every wrap (ciphertext)
   lives on the blind server and is fetchable by any authenticated client. What differs is whether a
   client can produce the **KEK** to open it — so a wrap is usable **only where its KEK source is
   present**.

3. **The Secret Key is never at rest on a device.** The `PASSWORD` wrap
   (`KEK = Argon2id(password + Secret Key)`) is the **floor** and the **first-unlock** path on a new
   device. The Secret Key authorizes creating a convenience wrap **once**, then is forgotten — it lives
   only in the Emergency Kit.

4. **Convenience wraps are device-local.**
   - **DEVICE wrap** — `KEK = device-bound key + password` → *"only password"*. Backing: a
     **non-extractable WebCrypto key in IndexedDB**. Device-local; does **not** sync — each device enrols
     its own.
   - **Passwordless** — a random key held by the device, ungated (L2 below).
   - **Portability spectrum of the KEK backing:** device-local key (1 device) → OS-synced passkey (one
     ecosystem + its browser) → third-party-manager passkey (cross-ecosystem). More portable = less
     hardware-bound. Wraps are **combinable** on the same Master Key.

5. **"Non-extractable ≈ HSM *interface*, not HSM *grade*."** A non-extractable WebCrypto key is a
   **software** boundary (browser-enforced) that beats XSS *exfiltration*, not a hardware root. Nothing in
   the browser gives one; a passkey's authenticator would. This is stated so the guarantee is never
   overclaimed — the wrap is as strong as the browser's key store, and that is a software promise.

6. **Re-lock policy — two triggers, no periodic expiry.** The unlocked Master Key is cached for the
   session in a **worker** (survives reload, non-extractable, dies on real close). Re-lock on **(a) idle
   timeout** and **(b) session end** (tab/app close). **Reload ≠ session end.** (Periodic trusted-device
   expiry was considered and **dropped**.)

7. **Method precedence is client-side; the server never picks.** The client offers the wraps it can open
   **locally**; if more than one, the default order is **device key → device + password → password +
   Secret Key**. **User preference overrides and is remembered per device**; the **password + Secret Key
   floor is always reachable** ("unlock another way").

## Consequences

- **Operator-blind preserved.** The server holds only ciphertext wraps + opaque metadata — no KEK, Master
  Key, or Secret Key is ever server-side.
- **2SKD intact.** The Secret Key is never persisted; convenience replaces it with a *device-bound factor
  + password*, or with a device-held key alone.
- **The KEK source is an adapter.** The backend and contracts know only the wrap; what produces the KEK is
  a client concern, so a new method (a passkey's PRF) is an added adapter and an added wrap — not a change
  to the model.
- **UX.** Secret Key only on a *new* device; thereafter the password, or nothing at all; a reload costs
  nothing.
- **Costs / limits.** Device-local wraps don't travel (cross-device = a passkey or per-device enrolment);
  synced / 3rd-party passkeys trade hardware-binding for reach; non-extractable is a *software* boundary;
  a live in-session XSS can still abuse an unlocked key / read plaintext — wraps protect against **key
  theft**, not **session misuse**.
- **Rejected — raw Secret Key in localStorage:** portable, exfiltratable, no real gain over a device-bound
  key. **Rejected — periodic trusted-device expiry:** dropped per product choice. **Rejected —
  server-side method selection:** the server would have to know which methods a device can open, which is
  exactly the metadata blindness forbids — so the client picks.

## Decision — device unlock levels (the per-device slider)

§7 settled *automatic precedence among openable wraps*; the clients expose that as a **per-device "unlock
level"** the user picks themselves, after the first floor unlock, from an **ordered, mutually-exclusive**
set — the *security of my own device is the user's call*, the *server is not*.

8. **Three ordered levels; the user chooses one per device.**
   - **L0 — Secret Key each time** (no convenience wrap; the bare floor). The default state of a fresh
     device.
   - **L1 — Only password** — the **DEVICE** wrap (`KEK = Argon2id(device-bound factor + password)`).
     Type the password each sign-in; the Secret Key goes back in the kit.
     ([uc.account.trust-device](../features/account/use-cases/uc.account.trust-device.md))
   - **L2 — No login** — a **device-held random key** wrap, held **ungated**, plus a persisted session;
     the client opens straight to its content, gated only by whatever locks the device itself.
     ([uc.account.remember-this-device](../features/account/use-cases/uc.account.remember-this-device.md))

   A fourth level sat between L1 and L2 while a secure-element gate existed to hold the device key: the key
   was stored, but released only after a live user-verification gesture. **A passkey (WebAuthn + PRF) is not
   that level** — its PRF output *is* the KEK, derived per use rather than gating a stored key, so it is a
   different mechanism and would be its own level and its own use-case (§16). Until one is built, the scale
   is these three.

9. **Mutually exclusive per device — narrows §4's "combinable" for UX clarity.** A device carries **one**
   convenience wrap at a time (plus the always-present floor). Switching level **removes** the old
   convenience wrap and **adds** the new one — never re-encrypts content. (The crypto still permits
   multiple wraps; the *level* abstraction deliberately exposes one, so the user has a single clear
   "how does this device unlock" answer.)

10. **No default — the user must choose.** After the first floor unlock the client **asks** which level to
    apply, with **nothing pre-selected**; until the user chooses, the device stays at **L0** (the floor).
    This refines §7: rather than silently applying a precedence order, the convenience level is an
    **explicit, informed per-device choice**. The **floor stays always reachable** at every level
    (automatic fallback when a KEK source or persisted session is unusable).

11. **The passwordless level (L2) requires a persisted session.** Without the password the client cannot
    mint a fresh session (the password signs the login challenge, ADR 0003), so the **refresh token** is
    persisted alongside the device key. Token expiry / rotation-out falls back automatically to the floor
    (L0). L1 needs **no** persistence — the password login mints a fresh session each time. This is the
    clean implementation seam between the "L1 only" slice and the L2 slice.

12. **Operator-blindness is untouched at every level — including L2.** The level choice relaxes **only
    local** device defense-in-depth, never server trust. Even at L2 the server holds only ciphertext wraps
    + an opaque session token; it never gains the KEK, Master Key, Secret Key, or password. The
    justification is the **threat-model asymmetry**: the *server* is an **unknown, global** adversary (why
    operator-blind is non-negotiable), while the user's *own device* is a **smaller, known-circle** risk
    ("handed over / left unlocked"), so trading local friction for convenience there is legitimately the
    user's decision.

13. **L2 trades away app-level defense-in-depth entirely — explicit opt-in, never a default.** At L2 the
    only gate left is whatever locks the device; a found *unlocked* device opens the content. It is offered
    only as a clearly-labelled choice, and presumes the device itself is locked. Considered and **declined**
    as any kind of default.

## Decision — product shape (L0 not a routine choice; "remember me")

14. **L0 is not a routine, selectable level; it stays the floor (bootstrap + emergency fallback).** Asking
    for the Secret Key on *every* unlock is the wrong friction for a memory-sharing app (not a secrets
    vault). The Secret-Key floor (§3) remains, but only as (a) the **first-unlock / bootstrap** on a device
    (typed **once per device**, until cross-device onboarding lands) and (b) the **always-reachable
    emergency fallback** when a convenience method's KEK source is lost — operator-blindness means **no
    operator-side reset**, so removing it would mean permanent lockout. §8/§10's chooser therefore **does
    not offer "Secret Key each time"** as a standing option.

15. **The levels are presented as "auth method + remember me", not a slider.** Auth method = **password**
    (Model B, ADR 0003) or **passkey** (WebAuthn + PRF — its own future UC, §16). **"Remember me"** = the
    opt-in into **L2** ([uc.account.remember-this-device](../features/account/use-cases/uc.account.remember-this-device.md)):
    an ungated persisted session (the [ADR 0004](0004-session-tokens.md) cookie) + an ungated device-held
    key wrapping the Master Key → the device opens straight in across a full browser restart. **Off** =
    re-authenticate each launch (no persisted session/vault). This is a presentation of the same wraps
    (§9: one convenience wrap per device). L2's opt-in + warning (§13) is exactly the toggle's trade-off.

16. **Passkey auth and QR cross-device onboarding are deferred to their own use-cases/ADRs.** Passkey is a
    new WebAuthn **auth** path beyond ADR 0003's Model B (plus PRF for the unlock KEK, §4); QR onboarding is
    the cross-device bootstrap already noted below in *Related / future*. Neither is part of the
    session-persistence increment (the cookie + remember-me on the existing password path).

## Decision — one combined login/unlock screen, auto-enrol; web's single "remember me" toggle

This concretizes §15: the login "remember me" and unlock "trust this device" choices are two views of the
*same* per-device decision (§9).

17. **Login + unlock collapse into one screen wherever a device wrap exists.** The same password both
    **authenticates** (the Model B auth keypair, ADR 0003) **and** derives the **DEVICE-wrap KEK** (§4) — so
    entering email + password does login *and* unlock in a single step. Hence the adaptive screen: the
    **first** sign-in on a device adds the **Secret Key** field (the floor, §3); once a wrap exists it is
    **email + password only**; a remembered device shows **no screen at all**. So the **Secret Key is typed
    once per device** (§3, §14).

18. **"Remember me" IS the per-device choice — it replaces both prior checkboxes.** On a successful first
    unlock the client **auto-enrols** a convenience wrap from the in-memory Master Key (the Secret Key is
    never stored):
    - **Off** → a **DEVICE wrap** (`KEK = Argon2id(device-bound key + password)`) → next launch: **password
      only**, no Secret Key. (The L1 mechanism, but *automatic* — not a second opt-in.)
    - **On** → a **password-less device-key wrap** (§20) + a persisted session ([ADR 0004](0004-session-tokens.md)
      cookie) → next launch: **opens straight in**. (L2.)

19. **No "remember nothing" / public-device level — "every device is mine" (refines §14).** Even
    remember-me-off enrols the password DEVICE wrap; there is **no** L0 "Secret Key each time" option.
    Justification: a stolen device + its DEVICE wrap is **useless without the password** (still gated), and a
    genuinely untrusted machine is covered by the browser's **private/incognito** mode (no persisted
    IndexedDB / cookie). The Secret-Key floor stays only as the **first-device bootstrap + emergency
    recovery** (§14), never a routine pick.

20. **A distinct `MasterKeyWrap.Method` for the password-less device-key wrap.** The existing `DEVICE` method
    mixes the password (`Argon2id(deviceFactor + password)`); the remember-me wrap is the **device key alone**
    (`symWrap(K, masterKey)` — high-entropy, no password, no KDF). A distinct construction earns a distinct
    method: `DEVICE_KEY`. The difference that matters is **what gates the key**, and here nothing does (§8-9).

## Decision — "off" is session-scoped (survives a reload, dies on browser close)

This refines §15/§18's "**Off**": it persists for the **browser session** — a no-"remember me" session
survives page reloads and ends only when the browser closes, matching the conventional web norm (rather than an
in-memory-only session that dies on the first reload).

22. **Web "off" = the conventional session tier; "on" = persistent.** Off persists for the **browser session**,
    on persists across a **browser restart**:
    - **Off** → a **session refresh cookie** (no `Max-Age`, [ADR 0004](0004-session-tokens.md)) **+** the
      unlocked Master Key cached in **`sessionStorage`**. A reload re-opens the vault with **no prompt**; closing
      the browser drops both. Off **also** keeps its password **DEVICE** wrap (§18), so the *next launch after a
      close* is **email + password only** — never the Secret Key. **Except where there is no password to keep a
      wrap with:** a device linked by a grant ([ADR 0059](0059-device-linking.md) §10) never typed one, so its
      Off tier is this one *minus* the `DEVICE` wrap — closing the browser ends it and the next visit is a fresh
      pairing, not email + password. Do not reach for this bullet's recipe wholesale there; half of it has no
      ingredients.
    - **On** → the **persistent** cookie + the password-less **`DEVICE_KEY`** wrap in IndexedDB (§18-20) → opens
      straight in across a full restart.
    - The Secret-Key floor is still typed **once per device** (§3, §14, §17); neither tier re-asks it.

23. **The session-scoped relaxation is conscious and bounded.** Within an open browser session, off re-opens the
    vault without the password — the device-held key opens it (the convenience this tier exists for). The Master
    Key is sealed under a **non-extractable** AES-GCM key in IndexedDB and only the **ciphertext** sits in
    `sessionStorage`. So, like every other device tier, the key's bytes never enter JS: an XSS payload can
    decrypt while it runs but
    cannot exfiltrate the key, and **nothing plaintext is ever written to `sessionStorage`** — which matters
    because the browser's session-restore may persist `sessionStorage` to disk. Browser close clears
    `sessionStorage` → the ciphertext is gone and the orphaned (un-exfiltratable, now-useless) key is dropped on
    the next boot. This is the standard web-session posture; for a **memory-sharing** app (not a secrets vault,
    §14) it is the right friction. **Operator-blind is untouched** — the server still holds only ciphertext wraps
    + an opaque cookie token; the relaxation is purely local. A genuinely untrusted machine still uses
    **private/incognito** (§19).

## Related / future

- **Cross-device onboarding (QR / push):** a new device is onboarded by an existing **unlocked** device
  over an **E2E channel the blind server only relays** — the QR/push carry the new device's *public* key
  (no secrets), the existing device encrypts the Master Key / a fresh wrap to it. Its own use-case + ADR;
  must carry the **E2E unlock-transfer**, **QR-login phishing** and **non-enumerability** (ADR 0003)
  constraints.
- **Device unlock levels** (§8–13 above) — the user-facing flow that picks one is
  [uc.account.remember-this-device](../features/account/use-cases/uc.account.remember-this-device.md); the
  per-level mechanics are [uc.account.unlock](../features/account/use-cases/uc.account.unlock.md) (L0) and
  [uc.account.trust-device](../features/account/use-cases/uc.account.trust-device.md) (L1).
