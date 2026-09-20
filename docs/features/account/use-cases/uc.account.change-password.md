# UC: Change your password while signed in

- **ID:** uc.account.change-password
- **Primary actor:** an authenticated account holder — either **deliberately** changing a password they still
  know, or someone who has **forgotten it** but is still signed in (most often via a password-less `DEVICE_KEY`
  "remember me" wrap).
- **Goal:** set a **new password** without losing any content, by re-wrapping the *same* Master Key under the
  new password and re-registering the auth keypair it derives — the authenticated sibling of
  [uc.account.recover-access](uc.account.recover-access.md), reached from a live session instead of a mailed
  token. The server hands out ciphertext it cannot open and never learns the old or new password, the Secret
  Key, or the Master Key ([ADR 0003](../../../decisions/0003-operator-blind-password-auth.md)).
- **Preconditions:** an authenticated, **unlocked** caller (the Master Key must already be open — this flow
  re-wraps it, it does not derive it from scratch). The actor holds the **Secret Key** — the vault does not
  retain it past unlock, so it is typed in fresh for this flow regardless of entry point (`web/src/app/vault.ts`
  holds `masterKey`/`identityPrivate`, never `secretKey`). The account has a `PASSWORD` floor to rewrite.
- **Postconditions (success):** the account has a **new `PASSWORD` wrap** and a **new `auth_public_key`**;
  every `DEVICE` and `DEVICE_KEY` wrap is gone and every *other* session is revoked (never the caller's own —
  see Quality notes). The **Master Key, identity keypair, scope keys and all content are unchanged** — nothing
  is re-encrypted. The Emergency Kit is **still valid**: the Secret Key did not change.

## Main flow

1. The actor, already signed in and unlocked, opens **"Change password"** from account settings.
2. The client asks for the **Secret Key** through the shared control
   ([uc.account.unlock](uc.account.unlock.md) § *One Secret-Key control*) — required regardless of entry
   point, since the vault never retained it past unlock.
3. The actor enters their **current password** (this is the deliberate-change entry; A1 covers the forgotten
   case).
4. The client derives the **old** auth keypair from the current password (the same derivation login uses,
   [ADR 0003](../../../decisions/0003-operator-blind-password-auth.md)) and signs the **new** auth public key
   with the old auth private key — an attestation that whoever is changing the password still controls the
   password being replaced.
5. The actor chooses a **new password**. The client derives the new Ed25519 auth keypair and builds a fresh
   `PASSWORD` wrap over the *same* Master Key (already open in the vault) with a new salt and nonce, exactly as
   [uc.account.recover-access](uc.account.recover-access.md) step 8 does.
6. The client sends the new wrap, the new auth public key and the attestation signature in **one authenticated
   request**.
7. The server verifies the attestation against the account's **current** `auth_public_key`. On a match it
   applies, in a single transaction: replace the `PASSWORD` wrap, overwrite `auth_public_key`, delete every
   `DEVICE` and `DEVICE_KEY` wrap, and revoke every session **except the one the request arrived on**.
8. The actor's current device asks for the Secret Key once more (step 2 already did this) and may **re-enrol**
   its own `DEVICE`/`DEVICE_KEY` wrap in a follow-up call to the existing
   [uc.account.trust-device](uc.account.trust-device.md) endpoint, so it is not left signed out of its own
   change. Every *other* device is signed out and must sign in fresh with the new password.

## Alternate / exception flows

- **A1 — the actor has forgotten the current password (but is signed in via `DEVICE_KEY`):** step 3 is skipped
  and no attestation is sent — the client omits the signature field entirely rather than sending an empty one.
  The live session plus the freshly-typed Secret Key (step 2) is the re-authentication, at the same trust level
  [ADR 0060](../../../decisions/0060-key-rotation-after-compromise.md) Decision 2 already accepts for rotation:
  the server cannot see which wrap opened the vault, so this gate is enforced by the **client** choosing to
  reach this screen only from an unlocked session, not by anything the server can verify. Everything else in
  steps 4–8 is identical (step 4 does not run).
- **E1 — the attestation signature is missing where it is required:** not applicable — the server never
  *requires* it; its presence is what makes the change a verified deliberate one instead of a
  session-authenticated one. Both are accepted; see Quality notes for why that is safe.
- **E2 — the attestation signature does not verify:** the caller typed the current password wrong. Refused;
  nothing is written. The actor may retry with the current password.
- **E3 — the account has no `PASSWORD` floor to rewrite:** refused; nothing is written — mirrors
  [uc.account.rotate-keys](uc.account.rotate-keys.md)'s `rotation_unavailable` shape for the same reason (a
  half-applied credential change is a lockout, not a degraded state).

## Quality notes

- **Two entry points, one mechanism — not two mechanisms.** The only difference between "I know my old
  password" and "I forgot it" is whether an attestation signature rides along; the server-side rewrite (step 7)
  is identical either way. This mirrors [uc.account.recover-access](uc.account.recover-access.md), which is
  also one mechanism reached differently (a mailed token instead of a live session).
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
- **`DEVICE_KEY`/`DEVICE` wraps still die, on both entry points.** They carry no password input, so a wrap
  minted under the *old* password would keep granting password-less (or Secret-Key-less) entry after a change
  meant to close exactly that door — the same reasoning
  [uc.account.recover-access](uc.account.recover-access.md) already applies. The acting device may immediately
  re-enrol its own (step 8); no other device may, since only the acting device is present to ask.
- **Not a rotation.** [uc.account.rotate-keys](uc.account.rotate-keys.md) replaces the identity keypair too and
  cascades into a scope re-key for every shared scope — the compromise-response tool, deliberately heavier than
  a password change needs. Its own request shape says so: `RotateAccountKeysRequest` carries **no**
  `authPublicKey`, because rotation is built to leave the password untouched. Reusing it here would rotate
  identity keys nobody asked to rotate.
- **Operator-blind throughout.** The request carries only ciphertext, a public key and a signature — never a
  password, the Secret Key, or the Master Key.

## What this means for you / Was das für dich bedeutet

**EN —** You can change your password any time you're signed in — you don't need to wait for a "forgot
password" email. If you remember your current password, enter it along with your Secret Key and your new
password. If you've forgotten your current password but you're still signed in (for example your phone
remembered you), you only need your Secret Key. Either way, your other devices are signed out and will need
the new password to sign back in — this one stays signed in and just asks you to reconfirm you want it
remembered.

**DE —** Du kannst dein Passwort jederzeit ändern, während du angemeldet bist — du musst nicht auf eine
"Passwort vergessen"-Mail warten. Wenn du dein aktuelles Passwort noch weißt, gib es zusammen mit deinem
Secret Key und deinem neuen Passwort ein. Hast du dein aktuelles Passwort vergessen, bist aber noch angemeldet
(zum Beispiel hat dein Handy dich sich gemerkt), brauchst du nur deinen Secret Key. So oder so werden deine
anderen Geräte abgemeldet und müssen sich mit dem neuen Passwort neu anmelden — dieses Gerät bleibt angemeldet
und fragt dich nur, ob es sich weiter merken soll.

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
- [ADR 0060](../../../decisions/0060-key-rotation-after-compromise.md) — the client-side cold-credential gate
  this flow's A1 mirrors
