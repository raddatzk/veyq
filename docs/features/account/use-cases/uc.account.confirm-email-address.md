# UC: Confirm the address a password reset is sent to

- **ID:** uc.account.confirm-email-address
- **Primary actor:** A signed-in account holder, typically a **tenant's first owner**. Theirs is the one account
  class whose address nothing has proven, because they were admitted by the Server Owner's copy-link
  ([ADR 0055](../../../decisions/0055-mail-is-a-precondition-not-a-capability.md) §3). Any account holder may
  confirm, and may confirm again.
- **Goal:** Show, while it can still be acted on, that the mailbox a reset would reach is one they read. A wrong
  or unread address then surfaces now, not on the day a forgotten password needs it
  ([ADR 0074](../../../decisions/0074-address-control-is-recorded-where-nothing-else-proves-it.md)).
- **Preconditions:**
  - The actor has a session. Unlock is not required.
  - The actor is a member of the tenant the request's `Host` resolves to.
  - That tenant has a sendable mail transport ([ADR 0048](../../../decisions/0048-provider-neutral-smtp-mail-transport.md)).
- **Postconditions (success):** `email_confirmed_at` is set on the account, for the address the link was mailed
  to, and the token is spent. Nothing else changes: this record gates no request
  ([ADR 0074](../../../decisions/0074-address-control-is-recorded-where-nothing-else-proves-it.md) Decision 3).

## Main flow

1. The actor opens their account's security settings. The tenant owner can also start from the mail settings
   after a successful save ([uc.admin.tenant-mail-settings](../../admin/use-cases/uc.admin.tenant-mail-settings.md)).
   The screen shows the address **as the server holds it**, which is the address a reset will mail, and whether
   it is confirmed.
2. The actor asks for a confirmation mail.
3. The server takes these steps in order:
   - It checks that the `Host`'s tenant can send.
   - It checks that the actor is a member of that tenant.
   - It charges the per-account send budget.
   - It mints a one-time token, stores only its hash with the mailed address and a `PT24H` expiry, and
     supersedes any outstanding token.
   - It mails the link through that tenant's own relay.

   The mail carries link, domain and expiry only, in veyq-fixed EN+DE copy
   ([ADR 0049](../../../decisions/0049-email-delivery-boundary.md)). The token rides the URL fragment.
4. The actor opens the link on a device where they are signed in as the same account. The page reads the token
   from the fragment and removes it from the address bar.
5. The server confirms only if every one of these holds:
   - the token is live;
   - it was minted for the caller's account;
   - the account's address still equals the one mailed.

   It then spends the token with a compare-and-set and stamps `email_confirmed_at`.
6. The settings screen now shows the address as confirmed.

Two other events set the same record without this flow:

- **Redeeming a member invite**, which only ever arrives by mail.
- **Completing a password reset** ([uc.account.recover-access](uc.account.recover-access.md)).

## Alternate / exception flows

- **A1 — This tenant cannot send mail.**
  - Step 3 answers `503 mail_unavailable` and mints nothing.
  - No link is offered to copy, because a link shown on the actor's own screen proves nothing about their
    mailbox.
  - The screen says that this space cannot send mail, so password reset is unavailable here too, and that the
    space owner has to set mail up.
- **A2 — The actor is not a member of the `Host`'s tenant.** Step 3 is refused with the tenant's not-found
  answer, and nothing is mailed. A relay may carry a link only for its own tenant's members. One case is the
  Server Owner before they join any tenant, who can neither confirm nor recover.
- **A3 — Send budget exhausted.** `429`, and no mail is sent. The budget exists so an account cannot turn its
  tenant's relay into a mail cannon aimed at its own address.
- **A4 — No session on the device that opened the link.** Nothing is confirmed, and the token is not stored on
  the device. The page asks the actor to sign in there, or to open the link where they are signed in. The
  token stays live until it expires.
- **A5 — Signed in as a different account.** Refused with the same answer as an unknown token. The token stays
  live for its owner. This is the case the session requirement exists for: whoever reads a mistyped mailbox
  must not be able to mark that wrong address confirmed.
- **A6 — Token expired, superseded, already spent, or unknown.** Refused generically, and the actor asks for a
  fresh mail.
- **A7 — The mail never arrives.** The screen keeps showing *not confirmed* next to the address it mailed.
  Either the address is wrong or the relay does not reach it, and both are exactly what this flow exists to
  surface. **No flow can correct an account's address today**, so the actor can see the defect but not fix it
  themselves (ADR 0074 § Consequences).

## Quality notes

- **A fact, not a gate.** No request is refused because an account is unconfirmed. Recovery proves address
  control at use time, and gating it here would strand the very accounts this flow is for.
- **Bound to the address.** The token row records the address it was mailed to, and the confirmation stamps only
  that address. A future address change clears the stamp in the same write.
- **Operator-visible, like the address itself.** The timestamp and the send-fact are operational metadata in the
  [security-model](../../../crosscutting/security-model.md) inventory. They defend against a mistake, not
  against the operator.

## What this means for you / Was das für dich bedeutet

**EN —** If you ever forget your password, veyq sends the reset link to your email address, and you'll also need
your Emergency Kit. So it matters that the address is really yours and that you read it. If you were invited
by email, it's already confirmed. If you set up your space through a link someone handed you, confirm it once:
veyq sends you a mail, and you open the link while signed in. If this space can't send email yet, you can't
confirm, and you couldn't reset your password either. Ask whoever runs the space to set up email.

**DE —** Wenn du dein Passwort vergisst, schickt veyq den Link zum Zurücksetzen an deine E-Mail-Adresse, und du
brauchst zusätzlich dein Notfall-Kit. Deshalb ist wichtig, dass die Adresse wirklich deine ist und du sie liest.
Wenn du per E-Mail eingeladen wurdest, ist sie schon bestätigt. Hast du deinen Bereich über einen Link
eingerichtet, den dir jemand gegeben hat, bestätige sie einmal: veyq schickt dir eine Mail, und du öffnest den
Link, während du angemeldet bist. Kann dieser Bereich noch keine E-Mails senden, kannst du nicht bestätigen, und
auch kein Passwort zurücksetzen. Bitte die Person, die den Bereich betreibt, E-Mail einzurichten.

## Related

- **Feature:** [account](../_index.md)
- **Use-cases:** [uc.account.recover-access](uc.account.recover-access.md) (the flow this keeps reachable, and
  one of the three events that set the record),
  [uc.spaces.add-member](../../spaces/use-cases/uc.spaces.add-member.md) (the mailed member invite, another of
  the three), [uc.admin.create-tenant](../../admin/use-cases/uc.admin.create-tenant.md) (the copy-link
  bootstrap that leaves the first owner unconfirmed),
  [uc.admin.tenant-mail-settings](../../admin/use-cases/uc.admin.tenant-mail-settings.md) (where the owner is
  prompted).
- **Cross-cutting:** [security-model](../../../crosscutting/security-model.md) (the address, the stamp and the
  send-fact as server-visible metadata).
- **Decisions:** [ADR 0074](../../../decisions/0074-address-control-is-recorded-where-nothing-else-proves-it.md)
  (what the record means, what sets it, what it gates),
  [ADR 0054](../../../decisions/0054-password-recovery-two-factor-release.md) (the token lifecycle and relay rule
  it copies), [ADR 0055](../../../decisions/0055-mail-is-a-precondition-not-a-capability.md) (no mail, no link),
  [ADR 0049](../../../decisions/0049-email-delivery-boundary.md) (what the mail may carry).
