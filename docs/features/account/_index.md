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
  key is caught on change (**web built, cr-0098**).
- [en.account.device-register](enablers/en.account.device-register.md) — the **sealed device register** (one
  Master-Key-sealed blob, same shape): a stable client-side device id, its renameable label, and the
  device↔session-family join, so unnamed families can be *shown* named without the operator gaining anything
  to correlate (**refined, not yet built**).

## Use-cases

- ★ [Account login (password)](use-cases/uc.account.login.md) — **refined**
- ★ [Unlock the Master Key (password + Secret Key)](use-cases/uc.account.unlock.md) — **refined** (L0 — the floor)

**Device unlock** — what a device may remember, so the Secret Key is typed once per device
([ADR 0005 §8–13](../../decisions/0005-unlock-methods-and-device-trust.md)):

- ★ [Trust this device (only password)](use-cases/uc.account.trust-device.md) — **refined** (the DEVICE wrap — password still required, Secret Key not; built)
- ★ [Sign in & remember this device](use-cases/uc.account.remember-this-device.md) — **refined** (the combined sign-in+unlock screen; a "remember me" toggle auto-enrols the DEVICE wrap or the password-less device-key wrap; [ADR 0005 §17–21](../../decisions/0005-unlock-methods-and-device-trust.md))
- Log in with a passkey — planned (alternate auth + PRF-derived unlock; [ADR 0005](../../decisions/0005-unlock-methods-and-device-trust.md))
- ★ [Onboard a device from one you are already signed in on](use-cases/uc.account.onboard-device.md) — **refined** (two directions — *add a device* and *sign in with a QR code*; the grant carries the Master Key **and** a session, so no password and no Secret Key is typed on the new device; [ADR 0059](../../decisions/0059-device-linking.md))
- Change password / Secret Key — planned (re-register auth key, re-wrap Master Key)
- [uc.account.recover-access](use-cases/uc.account.recover-access.md) — **built (web)** (cr-account-recovery): reset a
  forgotten password by releasing the withheld `RECOVERY` wrap against **two** factors — a mailed one-time
  token (address control) and the Secret Key (possession, proven by reproducing `recoveryAuthTag`).
  [ADR 0054](../../decisions/0054-password-recovery-two-factor-release.md). Note there is **no separate
  recovery code** in veyq: the recovery factor is the Secret Key from the Emergency Kit, and a reset does not
  re-issue the kit. An instance that cannot send mail refuses the request with `503 mail_unavailable` rather than
  a silent `202` ([ADR 0055](../../decisions/0055-mail-is-a-precondition-not-a-capability.md)); the sign-in screen
  offers the link unconditionally and lets it fail honestly
- [Manage active sessions](use-cases/uc.account.sessions.md) — **refined** (see every login and end any of them remotely; the set + the revoke need only a session, the **names** come from a Master-Key-sealed device register and need unlock; [ADR 0004](../../decisions/0004-session-tokens.md))

**Terms gate** — an *operational* gate that sits on a **session**, above unlock ([ADR 0035](../../decisions/0035-server-terms-of-service.md)):

- ★ [Accept the tenant's terms](use-cases/uc.account.accept-terms.md) — **built (web, cr-0114)** (a hard member gate on a stale `accepted_terms_version`; server-side, re-accept on a bump; the operator sets the version in [uc.admin.tenant-terms](../admin/use-cases/uc.admin.tenant-terms.md))
