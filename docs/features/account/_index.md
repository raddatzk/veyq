# Feature: Account & auth

Account lifecycle and authentication: logging in, unlocking the Master Key, trusting devices, passkeys,
changing the password / Secret Key, and recovering access. **Operator-blind throughout** — the server
holds only **public** keys and ciphertext, never the password and never a content key.

- **Cross-cutting:** [Security Model](../../crosscutting/security-model.md)
  (operator-blind, non-enumerability), [ADR 0003](../../decisions/0003-operator-blind-password-auth.md)
  (password-derived keypair + challenge-response), [ADR 0005](../../decisions/0005-unlock-methods-and-device-trust.md)
  (unlock methods, device trust, re-lock & precedence).
- **Note:** *auth* (proving who you are to the server) and *unlock* (opening your Master Key on the
  device) are deliberately **separate layers** — they appear as separate use-cases here.

## Enablers

- ★ [en.account.key-model](enablers/en.account.key-model.md) — the account key model: identity + the one
  **Master Key** (many wraps), the unlock methods + KEK derivation, and recovery; the **root** the scope/content
  keys extend.
- [en.account.identity-pinning](enablers/en.account.identity-pinning.md) — the operator-blind, cross-device
  **TOFU pin store** (one Master-Key-sealed blob) that remembers each contact's identity key, so a substituted
  key is caught on change (**built (web + backend), cr-0098**).
- [en.account.device-register](enablers/en.account.device-register.md) — the **sealed device register** (one
  Master-Key-sealed blob, same shape): a stable client-side device id, its renameable label, and the
  device↔session-family join, so unnamed families can be *shown* named without the operator gaining anything
  to correlate (**built (web + backend)**; the sessions list reads its names).

## Use-cases

- ★ [Account login (password)](use-cases/uc.account.login.md) — **built (backend + web)**
- ★ [Unlock the Master Key (password + Secret Key)](use-cases/uc.account.unlock.md) — **built (web + backend)** (L0 — the floor)
- [Change the sign-in address](use-cases/uc.account.change-email.md) — **built** (backend + web, cr-change-email; inbox #197, ADR 0074 Decision 8)

**Device unlock** — what a device may remember, so the Secret Key is typed once per device
([ADR 0005 §8–13](../../decisions/0005-unlock-methods-and-device-trust.md)):

- ★ [Trust this device (only password)](use-cases/uc.account.trust-device.md) — **built (web + backend)** (the DEVICE wrap — password still required, Secret Key not)
- ★ [Sign in & remember this device](use-cases/uc.account.remember-this-device.md) — **built (web + backend)** (the combined sign-in+unlock screen; a "remember me" toggle auto-enrols the DEVICE wrap or the password-less device-key wrap; [ADR 0005 §17–20](../../decisions/0005-unlock-methods-and-device-trust.md))
- Log in with a passkey — planned; nothing writes or opens a `PASSKEY` wrap yet (alternate auth + PRF-derived unlock; [ADR 0005](../../decisions/0005-unlock-methods-and-device-trust.md))
- ★ [Onboard a device from one you are already signed in on](use-cases/uc.account.onboard-device.md) — **built (web + backend)** (two directions — *add a device* and *sign in with a QR code*; the grant carries the Master Key **and** a session, so no password and no Secret Key is typed on the new device; [ADR 0059](../../decisions/0059-device-linking.md))
- [uc.account.change-password](use-cases/uc.account.change-password.md) — **built (backend + web)**
  (`cr-authenticated-password-change`, then `cr-change-password-cold-credential-gate`): change a password you
  still know from a live session — the current password **and** the Secret Key are proven against the
  `PASSWORD` floor before anything is sent; mirrors
  [uc.account.recover-access](use-cases/uc.account.recover-access.md)'s rewrite but spares the caller's own
  session. A forgotten password goes to recover-access, whichever wrap opened the session
- [uc.account.recover-access](use-cases/uc.account.recover-access.md) — **built (web)** (cr-account-recovery): reset a
  forgotten password by releasing the withheld `RECOVERY` wrap against **two** factors — a mailed one-time
  token (address control) and the Secret Key (possession, proven by a signature of the Secret-Key proof key,
  [ADR 0060](../../decisions/0060-key-rotation-after-compromise.md) Decision 2).
  [ADR 0054](../../decisions/0054-password-recovery-two-factor-release.md). Note there is **no separate
  recovery code** in veyq: the recovery factor is the Secret Key from the Emergency Kit, and a reset does not
  re-issue the kit. An instance that cannot send mail refuses the request with `503 mail_unavailable` rather than
  a silent `202` ([ADR 0055](../../decisions/0055-mail-is-a-precondition-not-a-capability.md)); the sign-in screen
  offers the link unconditionally and lets it fail honestly
- [uc.account.confirm-email-address](use-cases/uc.account.confirm-email-address.md) — **built (web)**
  (cr-email-address-confirmation). Confirm the address a reset is sent to. The record is set by a mailed member
  invite, a completed reset, or a confirmation link opened while signed in. It is shown to the holder in the
  Security settings and gates nothing. It exists for the tenant's first owner, the one account whose address
  nothing proves ([ADR 0074](../../decisions/0074-address-control-is-recorded-where-nothing-else-proves-it.md)).
- [Erase an account](use-cases/uc.account.erase-account.md) — **built** (cr-account-erasure;
  [ADR 0101](../../decisions/0101-an-account-is-erased-on-request-and-only-what-the-law-keeps-stays.md), GDPR Art. 17):
  self-service and on the Server Owner's door, pending for a window of its own (14 days,
  `veyq.account.erasure.window`), then erased with its own scopes and only what the law keeps staying; the last owner's choice per tenant; a restore erases it again (the erasure ledger, ADR 0056
  decision 8).
- [Rotate my keys after a compromise](use-cases/uc.account.rotate-keys.md) — **built (backend + web)**
  (`cr-account-key-rotation-after-compromise`; end a compromise: the Master Key **and** the identity are replaced together and every scope re-keyed; gated
  on the Secret Key, because that is the one thing a phished device never received; forward secrecy, **not**
  repair; [ADR 0060](../../decisions/0060-key-rotation-after-compromise.md))
- [Sign out — and every other way a session ends](use-cases/uc.account.sign-out.md) — **built (web)** (user sign-out,
  a rejected refresh and another tab's sign-out run one ending that loads the sign-in page as a new document;
  [ADR 0070](../../decisions/0070-a-session-ends-with-a-new-document.md))
- [Manage active sessions](use-cases/uc.account.sessions.md) — **built (web + backend)** (see every login and end any of them remotely; the set + the revoke need only a session, the **names** come from a Master-Key-sealed device register and need unlock; [ADR 0004](../../decisions/0004-session-tokens.md))

**Terms gate** — an *operational* gate that sits on a **session**, above unlock ([ADR 0035](../../decisions/0035-server-terms-of-service.md)):

- ★ [Accept the tenant's terms](use-cases/uc.account.accept-terms.md) — **built (web, cr-0114)** (a hard member gate on a stale `accepted_terms_revision`; server-side, re-accept on a bump; the space owner sets the revision in [uc.admin.tenant-terms](../admin/use-cases/uc.admin.tenant-terms.md))
