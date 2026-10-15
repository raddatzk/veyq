# UC: Recover access after a forgotten password

- **ID:** uc.account.recover-access
- **Primary actor:** Account holder who still has their **Emergency Kit** (the Secret Key) and can read the
  mailbox their account is registered under, but no longer knows their password — unauthenticated, on any
  device
- **Goal:** Set a **new password** without losing any content, by opening the withheld **`RECOVERY`
  `MasterKeyWrap`** with the Secret Key alone and re-wrapping the *same* Master Key under the new password. The
  server hands out ciphertext it cannot open and never learns the new password, the Secret Key, or the Master
  Key ([ADR 0054](../../../decisions/0054-password-recovery-two-factor-release.md)).
- **Preconditions:** The tenant the request arrives on has a **working mail transport**
  ([ADR 0048](../../../decisions/0048-provider-neutral-smtp-mail-transport.md)), and the account **is a member of
  that tenant**. Without both there is no recovery: the link travels only through a relay of the account's own
  tenant, so an account in no tenant (the Server Owner, as first-run setup leaves them) has none until it joins
  one ([ADR 0054](../../../decisions/0054-password-recovery-two-factor-release.md) §4). The account was born with a
  `RECOVERY` wrap and with the public half of its **Secret-Key proof key** registered
  ([ADR 0060](../../../decisions/0060-key-rotation-after-compromise.md) Decision 2). The actor holds the
  **Secret Key**. No session is required, and none helps. The actor can **read the account's mailbox** — which is
  what [uc.account.confirm-email-address](uc.account.confirm-email-address.md) shows in advance, and which a
  completed reset records in turn; recovery itself never asks whether it was shown.
- **Postconditions (success):** The account has a **new `PASSWORD` wrap** and a **new `auth_public_key`**; every
  wrap but `PASSWORD` and `RECOVERY` is gone (today every `DEVICE` and `DEVICE_KEY`; the survivor rule is
  [en.account.key-model](../enablers/en.account.key-model.md)'s) and every session is revoked; the recovery token
  is spent. The **Master Key, identity keypair, scope keys and all content are unchanged** — nothing was
  re-encrypted. The Emergency Kit is **still valid**: the Secret Key did not change.

## Main flow

1. On the sign-in screen the actor chooses **"Forgot password"** and enters their email address.
2. The server answers **identically whether or not the address is known**, in content and in timing, so it is
   not an enumeration oracle ([ADR 0004](../../../decisions/0004-session-tokens.md)). That promise covers the
   *address*; whether this tenant can send mail at all is a property of the deployment and is answered plainly
   instead (A2). The answer comes as soon as the transport and the rate limits are checked, and everything below
   happens after it ([ADR 0054](../../../decisions/0054-password-recovery-two-factor-release.md) §6). If the
   address belongs to an account that is a member of this tenant, the server mints a one-time token, stores
   only its SHA-256 hash with a short expiry, commits, and *then* mails the raw token in the link's **URL
   fragment**. The mail carries link, domain and expiry and nothing else
   ([ADR 0049](../../../decisions/0049-email-delivery-boundary.md)).
3. The actor opens the link. The token stays in the fragment, so it is never sent to the server as part of a
   navigation and never lands in a server log.
4. The client presents the token to **release** the `RECOVERY` wrap. The server looks the token up by hash,
   checks it is live and unspent, and returns the wrap (plus the suite id and the wrapped identity private key).
   This is the only path on which that wrap is ever served — the ordinary key-material read filters it out.
5. The client asks for the **Secret Key** through the shared control
   ([uc.account.unlock](uc.account.unlock.md) § *One Secret-Key control*), which also names the expected
   account here — the recovery material carries the e-mail the reset is for.
6. The client derives the recovery KEK `= Argon2id(Secret Key, wrap KDF params)` — **no password input** — and
   unwraps the **Master Key**. A wrong Secret Key fails here, on the device, with no server round trip.
7. The client derives the **Secret-Key proof key** from the Secret Key. Only a holder of the Secret Key can
   sign with it.
8. The actor chooses a **new password**. The client derives the new Ed25519 **auth keypair**
   ([ADR 0003](../../../decisions/0003-operator-blind-password-auth.md)) and builds a fresh **`PASSWORD` wrap**
   over the *same* Master Key with a new salt and nonce.
9. The client signs the new wrap and the new auth public key with the proof key, and sends the token, that
   signature, the new wrap and the new auth public key in **one request**.
10. The server verifies the signature against the account's registered proof public key. If it verifies, the
    server applies, **in a single transaction**: replace the `PASSWORD` wrap, overwrite `auth_public_key`,
    delete every wrap but `PASSWORD` and `RECOVERY`, revoke all sessions, and mark the token spent.
11. The actor signs in with the new password. Because all device trust was dropped, this device asks for the
    Secret Key once more and then re-enrols ([uc.account.trust-device](uc.account.trust-device.md)).

## Alternate / exception flows

- **A1 — Address unknown here.** The address has no account, or its account is not a member of this tenant.
  Step 2 answers success anyway and no mail is sent. The actor learns nothing about who is registered on the
  instance. Neither does this tenant's owner, whose relay would otherwise have carried another tenant's link
  ([ADR 0054](../../../decisions/0054-password-recovery-two-factor-release.md) §4).
- **A2 — No mail transport configured.** Mail is per tenant: the account's tenant has no sendable SMTP settings
  ([uc.admin.tenant-mail-settings](../../admin/use-cases/uc.admin.tenant-mail-settings.md) — set by a tenant
  owner; there is no instance-wide fallback). The request is refused with **`503 mail_unavailable`** and nothing is
  minted — the instance says so plainly instead of offering a control that silently fails. There is deliberately
  **no copy-link fallback**: passing a recovery link through the operator would make them a participant in a flow
  built to exclude them ([ADR 0054](../../../decisions/0054-password-recovery-two-factor-release.md) § Decision 4).
  Saying it out loud is not an oracle — it is a fact about the deployment, not about any address — and the check
  runs **before** the rate limiter precisely so the two answers cannot be paired into one
  ([ADR 0055](../../../decisions/0055-mail-is-a-precondition-not-a-capability.md) §4/§5). No capability is
  advertised: the client asks nothing in advance and may always offer the link.
- **A3 — Token expired, already spent, or unknown.** The release in step 4 is refused generically. The actor
  starts over; a new request supersedes any outstanding token for that address.
- **A4 — Wrong Secret Key.** Step 6 fails as an AEAD error on the device. Nothing was sent, the token is still
  live, and the actor may retry — the server cannot tell the difference between a typo and a walk-away.
- **A5 — Correct token, no Secret Key.** The actor holds the wrap and cannot open it. Step 7 is unreachable, so
  step 10 never runs. This is the attacker-with-mailbox-access case, and it ends here.
- **A6 — The signature does not verify at step 10.** The server refuses. The request was not signed by the
  Secret Key this account registered, or it carries something other than what was signed. Nothing is written.
- **A7 — Password *and* Emergency Kit both lost.** Not recoverable, by design. There is no escrow and no
  operator override; the content stays encrypted forever. Stated openly rather than softened.

## Quality notes

- **Two independent factors, and neither is sufficient.** Address control authorizes the *release*; the Secret
  Key authenticates the *actor*. A mailbox takeover yields ciphertext; a stolen Emergency Kit yields no token.
- **The server gains no power it did not have.** It already stores the `RECOVERY` wrap; recovery only lets it
  hand that ciphertext to a party who must already hold the opening key. Recovery is **not** escrow.
- **The password must not be silently demoted.** Releasing the wrap to any authenticated session would let a
  live session plus the Secret Key open the vault without the password. That is why release is gated on a mailed
  token and never on a session.
- **All-or-nothing.** The three writes in step 10 are one transaction: a partial apply produces an account that
  logs in but cannot unlock, or unlocks but cannot log in. Both are lockouts.
- **`DEVICE_KEY` wraps must die with the rest.** They carry no password input, so they would survive a reset
  and keep granting password-less entry to a device the actor may be resetting *because of*.

## What this means for you / Was das für dich bedeutet

**EN —** If you forget your password, you are not locked out — as long as you still have your Emergency Kit.
Ask for a reset link, open the mail we send you, and enter the Secret Key from your kit. Asking is always
offered — and if your space cannot send email yet, it tells you so right there, instead of leaving you waiting
for a link that was never going to arrive (an owner of your space sets email up, in its settings). You choose a new
password, and everything you have saved is still there: your memories are never re-encrypted, and your kit keeps
working afterwards. Two things have to come together, on purpose: reading your mail, and holding your kit.
Someone who breaks into your mailbox gets nothing they can open. What we cannot do is help if both the password
*and* the kit are gone — there is no master key on our side, and no back door. That is the same promise that
keeps your memories private, seen from its uncomfortable end. After a reset every device signs in fresh, so you
will enter your Secret Key once more on each of them.

**DE —** Wenn du dein Passwort vergisst, bist du nicht ausgesperrt — solange du dein Notfall-Kit noch hast.
Fordere einen Link an, öffne die Mail, die wir dir schicken, und gib den Secret Key aus deinem Kit ein. Anfordern
kannst du immer — und kann dein Bereich noch keine E-Mails verschicken, bekommst du das direkt gesagt, statt
auf einen Link zu warten, der nie gekommen wäre (E-Mail richtet eine Person ein, der dein Bereich gehört, in
dessen Einstellungen). Du
wählst ein neues Passwort, und alles Gespeicherte ist weiterhin da: deine Erinnerungen werden nie neu
verschlüsselt, und dein Kit funktioniert danach weiter. Zwei Dinge müssen zusammenkommen, und das ist Absicht:
Zugriff auf dein Postfach und das Kit in der Hand. Wer in dein Postfach einbricht, bekommt nur etwas, das er
nicht öffnen kann. Nicht helfen können wir, wenn Passwort **und** Kit weg sind — bei uns liegt kein
Hauptschlüssel, und eine Hintertür gibt es nicht. Das ist dasselbe Versprechen, das deine Erinnerungen privat
hält, nur von seiner unbequemen Seite. Nach einem Zurücksetzen meldet sich jedes Gerät neu an, du gibst deinen
Secret Key dort also noch einmal ein.

## Related

- [en.account.key-model](../enablers/en.account.key-model.md) — the wrap inventory this flow reads and rewrites
- [uc.account.unlock](uc.account.unlock.md) — the ordinary two-secret unlock this restores the actor to
- [uc.account.trust-device](uc.account.trust-device.md) — the re-enrolment every device goes through afterwards
- [ADR 0054](../../../decisions/0054-password-recovery-two-factor-release.md) — the two-factor release decision
- [ADR 0049](../../../decisions/0049-email-delivery-boundary.md) — what a veyq mail may contain
- [ADR 0003](../../../decisions/0003-operator-blind-password-auth.md) — why a new password means a new auth keypair
- End-user view (docs site): [Is my data safe?](../../../../site/src/content/docs/is-my-data-safe.md)
- End-user view (docs site): [Account keys & recovery](../../../../site/src/content/docs/account-keys-and-recovery.md)
