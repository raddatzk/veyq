# UC: Change your password while signed in

- **ID:** uc.account.change-password
- **Primary actor:** an authenticated account holder changing a password they still know. Someone who has
  **forgotten** it goes to [uc.account.recover-access](uc.account.recover-access.md) instead, whichever wrap
  their session was opened by — see § *One entry point*.
- **Goal:** set a **new password** without losing any content, by re-wrapping the *same* Master Key under the
  new password and re-registering the auth keypair it derives — the authenticated sibling of
  [uc.account.recover-access](uc.account.recover-access.md), reached from a live session instead of a mailed
  token. The server hands out ciphertext it cannot open and never learns the old or new password, the Secret
  Key, or the Master Key ([ADR 0003](../../../decisions/0003-operator-blind-password-auth.md)).
- **Preconditions:** an authenticated, **unlocked** caller (the Master Key must already be open — this flow
  re-wraps it, it does not derive it from scratch). The actor holds the **Secret Key** — the vault does not
  retain it past unlock, so it is typed in fresh for this flow (`web/src/app/vault.ts` holds
  `masterKey`/`identityPrivate`, never `secretKey`) — **and the current password**. The account has a
  `PASSWORD` floor to rewrite, and **that pair is proven against it before anything is derived or sent**
  ([ADR 0060](../../../decisions/0060-key-rotation-after-compromise.md) Decision 2 — this flow mints a cold
  credential, so it is gated exactly as rotation is).
- **Postconditions (success):** the account has a **new `PASSWORD` wrap** and a **new `auth_public_key`**;
  every wrap but `PASSWORD` and `RECOVERY` is gone (today every `DEVICE` and `DEVICE_KEY`; the survivor rule is
  [en.account.key-model](../enablers/en.account.key-model.md)'s) and every *other* session is revoked (never the
  caller's own — see Quality notes). The **Master Key, identity keypair, scope keys and all content are unchanged** — nothing
  is re-encrypted. The Emergency Kit is **still valid**, and that is now something the flow *checked* rather
  than something it assumed about what the actor typed: the new floor is built under the very Secret Key that
  just opened the old one. The session is marked **cold** — it has proven password + Secret Key, whatever wrap
  originally opened it.

## Main flow

1. The actor, already signed in and unlocked, opens **"Change password"** from account settings.
2. The client asks for the **Secret Key** through the shared control
   ([uc.account.unlock](uc.account.unlock.md) § *One Secret-Key control*) — it is typed in even on a session
   that was opened cold, since the vault never retained it past unlock.
3. The actor enters their **current password**.
4. **The client proves the pair against the stored floor before anything else**: it reads the account's
   `PASSWORD` wrap, derives its KEK from the password and Secret Key just typed, opens it, and requires the
   result to equal the Master Key the vault is holding. This is the same live proof — the same implementation —
   that [uc.account.rotate-keys](uc.account.rotate-keys.md) §2 runs (E1). Nothing is derived or sent until it
   passes.
5. The client derives the **old** auth keypair from the current password (the same derivation login uses,
   [ADR 0003](../../../decisions/0003-operator-blind-password-auth.md)) and signs the **new** auth public key
   with the old auth private key — an attestation that whoever is changing the password still controls the
   password being replaced.
6. The actor chooses a **new password**. The client derives the new Ed25519 auth keypair and builds a fresh
   `PASSWORD` wrap over the *same* Master Key (already open in the vault) with a new salt and nonce, exactly as
   [uc.account.recover-access](uc.account.recover-access.md) step 8 does.
7. The client signs the change with the **Secret-Key proof key**, derived from the Secret Key typed in step 2
   ([ADR 0060](../../../decisions/0060-key-rotation-after-compromise.md) Decision 2). It sends the new wrap, the
   new auth public key, the attestation and that signature in **one authenticated request**.
8. The server verifies the attestation against the account's **current** `auth_public_key` and the proof
   signature against the account's registered proof public key. If both verify, it applies, in a single
   transaction: replace the `PASSWORD` wrap, overwrite `auth_public_key`, delete every
   wrap but `PASSWORD` and `RECOVERY`, and revoke every session **except the one the request arrived on**.
9. The actor's current device asks for the Secret Key once more (step 2 already did this) and may **re-enrol**
   its own `DEVICE`/`DEVICE_KEY` wrap in a follow-up call to the existing
   [uc.account.trust-device](uc.account.trust-device.md) endpoint, so it is not left signed out of its own
   change. Every *other* device is signed out and, its wrap deleted in step 8, must sign in fresh with the new
   password **and** the Secret Key.

## Alternate / exception flows

- **E1 — the current password and Secret Key do not open the account's floor (step 4):** refused **on the
  device**, before any derivation and before any request. The actor may retry. This is the part of the proof a
  blind server cannot make: it holds only ciphertext there. It also refuses a *stale* proof — a floor rewritten
  underneath this session — because the opened Master Key is compared against the one the vault holds, not
  merely required to decrypt.
- **E2 — the attestation signature does not verify:** the caller typed the current password wrong. Refused;
  nothing is written. The actor may retry with the current password. A request carrying **no** attestation is
  not a flow at all: the field is required by the contract, so it is refused at the boundary with `400`.
- **E3 — the account has no `PASSWORD` floor to rewrite:** refused; nothing is written — mirrors
  [uc.account.rotate-keys](uc.account.rotate-keys.md)'s `rotation_unavailable` shape for the same reason (a
  half-applied credential change is a lockout, not a degraded state).

## Quality notes

- **One entry point, and the proof is checked in two places.** This screen mints a **cold
  credential** — a `PASSWORD` floor plus the `auth_public_key` derived from it — so
  [ADR 0060](../../../decisions/0060-key-rotation-after-compromise.md) Decision 2 applies to it exactly as it
  applies to rotation: it may not be reachable without the material an attacker lacks. The server checks each
  factor for itself: the attestation proves the current password (it holds `auth_public_key`), the proof
  signature the Secret Key (it holds the proof public key). Only the device can check that the pair opens this
  account's current floor (the server holds ciphertext there), so the client does that first. **No part is
  optional.** This flow once offered a second entry point — *"I don't remember my current password"* — which
  sent no attestation and proved no Secret Key; a stolen unlocked phone could mint a credential for itself, and
  from there rotation's own gate passed for the attacker. It is deleted, not gated: what a forgotten password
  needs is [uc.account.recover-access](uc.account.recover-access.md), which proves control of the address
  instead of waiving the proof.
- **What removing it costs — weighed and accepted, not overlooked.** It lands on exactly one population: an
  instance with **no mail transport**, where recovery refuses with `503 mail_unavailable`
  ([ADR 0055](../../../decisions/0055-mail-is-a-precondition-not-a-capability.md), § A2 there), and someone who
  has forgotten their password but is still signed in via `DEVICE_KEY`. They had a self-service route and no
  longer do. That removes a lucky escape hatch rather than creating a lockout class — on such an instance that
  person is already one sign-out from a permanent lockout — and the hatch was the attacker's ramp in the same
  breath. The alternative would make the Secret Key checkable **without** the current password, which needs
  either releasing the `RECOVERY` wrap to an authenticated session (dropping cold unlock from two factors to
  one, amending [ADR 0054](../../../decisions/0054-password-recovery-two-factor-release.md)) or a server-side
  check of the Secret Key alone. The second now exists — the Secret-Key proof key — but reopening the hatch on
  it is its own decision, and it has not been taken.
- **The attestation is a signed statement, not a fresh server challenge.** It signs the **new** auth public key
  directly, not a server-minted nonce — there is nothing to replay it *into*: a captured signature only ever
  re-authorizes the exact new key it was made for, which the legitimate actor already approved. This is
  deliberately simpler than the login challenge/response
  ([uc.account.login](uc.account.login.md)), which needs freshness because a replayed login signature would
  mint a *new* session; a replayed attestation here just reapplies an already-approved change.
- **Never touches the caller's own session.** Unlike
  [uc.account.recover-access](uc.account.recover-access.md), which revokes *every* session because it starts
  from no session at all, this flow starts from one and preserves it — the acting device is mid-flow, not lost.
  Every *other* session is still cut, because the new password is the only credential that should open a new
  one from here on. Mirrors [uc.account.rotate-keys](uc.account.rotate-keys.md)'s `presentedAccessToken`-spared
  revoke, not [uc.account.recover-access](uc.account.recover-access.md)'s revoke-everything.
- **`DEVICE_KEY`/`DEVICE` wraps still die.** They carry no password input, so a wrap
  minted under the *old* password would keep granting password-less (or Secret-Key-less) entry after a change
  meant to close exactly that door — the same reasoning
  [uc.account.recover-access](uc.account.recover-access.md) already applies. The acting device may immediately
  re-enrol its own (step 8); no other device may, since only the acting device is present to ask.
- **Not a rotation.** [uc.account.rotate-keys](uc.account.rotate-keys.md) replaces the identity keypair too and
  cascades into a scope re-key for every shared scope — the compromise-response tool, deliberately heavier than
  a password change needs. Its own request shape says so: `RotateAccountKeysRequest` carries **no**
  `authPublicKey`, because rotation is built to leave the password untouched. Reusing it here would rotate
  identity keys nobody asked to rotate.
- **Operator-blind throughout.** The request carries only ciphertext, a public key and two signatures — never a
  password, the Secret Key, or the Master Key.

## User impact

You can change your password whenever you like without losing anything you keep, and someone holding your unlocked
device cannot change it without your Secret Key.

## Related

- [en.account.key-model](../enablers/en.account.key-model.md) — the wrap inventory this flow reads and rewrites
- [uc.account.recover-access](uc.account.recover-access.md) — the unauthenticated sibling this mirrors (mailed
  token instead of a live session; revokes every session instead of sparing the caller's own)
- [uc.account.rotate-keys](uc.account.rotate-keys.md) — the compromise-response tool this is deliberately
  **not**: identity keys + scope re-key are out of scope here
- [uc.account.trust-device](uc.account.trust-device.md) — the re-enrolment the acting device may run immediately
  after (step 8), and every other device runs on its next sign-in
- [ADR 0003](../../../decisions/0003-operator-blind-password-auth.md) — why a new password means a new auth
  keypair
- [ADR 0060](../../../decisions/0060-key-rotation-after-compromise.md) Decision 2 — the cold-credential gate
  this flow runs, in the same shared implementation rotation runs
- End-user view (docs site): [Change your password](../../../../site/src/content/docs/account/change-password.mdx)
