# UC: Change the address an account signs in with

- **ID:** uc.account.change-email
- **Status:** built (backend + web, cr-change-email); decided 2026-10-14 by the coordinator on the owner's behalf (inbox #197). The whole flow ran green against a real backend in an e2e suite (request, the link read from the
  mailbox, confirm, the old address's notice, a spent link refused, the old address's sign-in refused, the new one signs in,
  unlocks with the same Secret Key and opens content). In a real browser (2026-10-14) only the form was seen (new address,
  password, Secret Key with the kit import) - not submitted, because the dev login is tied to the seeded address.
- **Primary actor:** A signed-in, unlocked account holder who belongs to a tenant with a working mail relay.
- **Goal:** Sign in with a new address from now on, without losing the account, its keys or its data - and without
  letting a stolen session move the address a password reset is sent to.
- **Preconditions:** a session and the vault unlocked; the actor knows their password and holds their Secret Key; the
  request's tenant can send mail ([ADR 0055](../../../decisions/0055-mail-is-a-precondition-not-a-capability.md)).
- **Postconditions (success):** the account's email is the new address, recorded as confirmed; its auth keypair is the
  one derived under the new address's salt; every other session is signed out; the old address got a notice. The Master
  Key, every wrap, the Secret Key and the content are untouched.

## Main flow

1. In Settings → Security, in the address section that shows the sign-in address, the actor chooses **Change sign-in
   address** and enters the new address, their password and their Secret Key. The client proves both against the stored
   `PASSWORD` floor first, as a password change does, and refuses the address the account already signs in with.
2. The client derives the new auth keypair (`Argon2id(password, authSalt(newEmail))`, ADR 0003 - the email is the auth
   salt's input; the KEKs use stored random salts and do not change), and sends the new address, the new public key, an
   attestation signed with the **current** auth key over the account, the address and the new key
   (`veyq/v1/auth-attestation/change-email`) and a Secret-Key proof over the current key, the new key and the address
   (`veyq/v1/secret-key-proof/change-email` — the password change's statement shape with the address where that one
   binds the wrap digest; both pinned by `contracts/crypto/vectors/v1.json`).
3. The server stores a **pending change** and mails a confirmation link to the **new** address through the tenant's
   relay. It answers the same way whether or not the address is free (no enumeration): "If the address can be used, a
   link was sent to it."
4. The actor opens the link **in a session of the same account**; the server checks the address is still free, then in
   one transaction swaps the email and the auth public key, sets the address confirmed (the link proved it), carries a
   tenant's record of the address its owner invite named along to the new address (no copy of the old one stays,
   [ADR 0101](../../../decisions/0101-an-account-is-erased-on-request-and-only-what-the-law-keeps-stays.md) decision 4),
   revokes every other session, and mails the **old** address a fixed notice ("your sign-in address was changed", the domain,
   no new address - [ADR 0049](../../../decisions/0049-email-delivery-boundary.md)'s closed variable set).
5. The app says the change is done and offers a **new Emergency Kit** (the old one prints the old address; its Secret
   Key is still valid): the password and the Secret Key are entered on that page (the key typed, pasted, or imported
   from the old kit), and the kit — carrying the new address — is offered only once they pass the same password +
   Secret-Key check the request ran, against the stored floor; a failed check says so and prints nothing, because a kit
   printed from a mistyped key fails exactly when it is needed. This session and this device's remembered sign-in name
   the account by the new address from here on.

## Alternate / exception flows

- **E1 — the address is taken** (by any account of the instance): nothing is said at the request; the confirmation
  fails with a neutral "this address cannot be used" and nothing changes.
- **E2 — no mail relay** for the tenant: `503 mail_unavailable` before anything is stored; no copy-link. A send that
  fails withdraws the pending change. At the confirmation the relay is a precondition too (the notice must be able to
  leave): `503`, nothing changed.
- **E3 — wrong password or Secret Key:** the proof fails; nothing is stored.
- **E4 — the link expired, was used, or is opened in another account's session:** refused; nothing changes. A newer
  request replaces an older pending one.
- **E5 — a Server Owner in no tenant:** no relay to mail through; not offered (as recovery). The request and the
  confirmation are refused `404 tenant_not_found` outside a tenant the account belongs to.
- **E6 — open tenant invites addressed to the old address** can no longer be accepted by this account (they match by
  address); said in the confirmation step's result.

## Quality notes

- **A session alone cannot move the reset address.** The request needs the password and the Secret Key; the old address
  is told.
- **Operator-blind, unchanged.** The server sees the new address (it is the login identifier) and a new public key; no
  password, no key material.
- **Non-enumerable.** The request's answer is the same for a free and a taken address; uniqueness is enforced at confirm,
  under the account's lock and the address's unique index (two accounts confirming one address: one wins, the other
  gets the neutral refusal).
- **One mail budget.** A request spends the per-account budget the address confirmation spends: one account aiming its
  space's relay, whichever mail it asks for.

## User impact

You can move your sign-in to a new email address without losing your account, your keys or anything you keep.

## Related

- Feature: [Account](../_index.md)
- **End-user view (docs site):** [Your email address](../../../../site/src/content/docs/account/change-email.mdx)
- Use-cases: [uc.account.confirm-email-address](uc.account.confirm-email-address.md) (the confirmation record this sets),
  [uc.account.change-password](uc.account.change-password.md) (the proof and the session revocation it mirrors),
  [uc.account.recover-access](uc.account.recover-access.md) (what the address is for)
- Decisions: [ADR 0074](../../../decisions/0074-address-control-is-recorded-where-nothing-else-proves-it.md) Decision 8,
  [ADR 0003](../../../decisions/0003-operator-blind-password-auth.md), [ADR 0049](../../../decisions/0049-email-delivery-boundary.md),
  [ADR 0055](../../../decisions/0055-mail-is-a-precondition-not-a-capability.md)
