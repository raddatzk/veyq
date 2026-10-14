# ADR 0074 — Address control is recorded where nothing else proves it, and gates nothing

- Status: Accepted
- Date: 2026-09-28

## Context

An account's email is its login identifier, the input of its deterministic auth salt
([ADR 0003](0003-operator-blind-password-auth.md)) and the only channel a password reset can reach
([ADR 0054](0054-password-recovery-two-factor-release.md)). It is globally unique (`ux_veyq_user_email`) and
stored in plaintext, which the security model already concedes. Nothing records whether the person holding the
account can read that mailbox. Whether that matters depends on who can make an account for an address, so this
record starts from the paths that exist, not from the generic "verify on sign-up" pattern.

**Every path that binds an address to an account** (one writer, `AccountStore.createAccount`, two callers):

| Path | Who types the address | Address control proven? |
|---|---|---|
| Member invite, redeemed by a new or an existing account | the tenant owner | **Yes.** A member invite travels by mail only ([ADR 0055](0055-mail-is-a-precondition-not-a-capability.md) §2), and the account is born for, or must already hold, the invite's address. The token that arrives is the proof. |
| The tenant's first owner, redeeming the bootstrap invite | the Server Owner | **No.** This is the one surviving copy-link (ADR 0055 §3). The account is born for the invite's address, but whoever holds the link redeems it, and the link never went through the mailbox. |
| First-run setup (the Server Owner) | the operator, for themselves | **No.** Nothing is ever mailed to it; it has no recovery until it joins a tenant (ADR 0054 §4). |
| Changing an account's address | — | No such path exists. |
| Open self-registration | — | No such path exists. |

Against that inventory, the threats the generic pattern defends against sort as follows.

- **Account takeover through a typo'd or foreign address does not need this.** A reset needs the mailed token
  *and* the Secret Key (ADR 0054 §1). Whoever reads a mailbox that is not the account holder's receives a wrap
  they cannot open. ADR 0054 proves address control at the moment it is used. A flag stored months earlier adds
  nothing to that gate.
- **Squatting an address, which poisons its auth-salt binding for the rightful owner, is not open to an
  attacker.** The two unproven paths are both started by the Server Owner, and the Server Owner already writes
  the database. Squatting becomes an attacker's move only when an account can be created for an address by
  someone who does not read it. Only open self-registration would allow that.
- **What is left is a durability defect, and it is silent.** The tenant owner, the account most likely to hold
  everything, is the one account class whose address nothing ever tested. A typo, or an address the owner does
  not read, stays invisible until the day a reset is needed. That day the reset cannot reach them, and a
  forgotten password plus an unreachable mailbox is permanent loss
  ([architecture](../architecture/README.md) §10). The recovery mail for that account also goes to whoever owns
  the wrong address.

Two further facts shape the answer. Nothing can prove the bootstrap owner's address at redemption, because a
`PENDING` tenant has no mail settings by construction and a server-level mailer was rejected (ADR 0055 §3). The
earliest proof is the moment that owner's tenant can send. And "verified" is already a term in this codebase:
it names identity verification ([uc.spaces.verify-identity](../features/spaces/use-cases/uc.spaces.verify-identity.md))
and the login `verify` step. This record therefore says **confirm** for the address.

## Decision

**1. The record is a fact about the address, not a permission.** `veyq_user.email_confirmed_at` is a nullable
timestamp. It means *the account holder showed, at that time, that they read the mail sent to this address*. It
is bound to the address it confirmed: any future path that changes an account's address clears it in the same
write. The code reads only its presence (the "confirmed" state); the time is kept on purpose, as the record of
*when* control was shown — the one stored time a data-minimisation pass (inbox #398, 2026-10-10) left as a time
by decision rather than narrowing it to a flag.

**2. Exactly three events set it:**

- **Redeeming a `MEMBER` invite**, on both the register and the sign-in branch. It is keyed on the invite's role,
  not on its recorded delivery, because the role is what ADR 0055 §2 makes mail-only by construction.
- **Completing a password reset**, because ADR 0054's reset is the mailed token plus the Secret Key, which is the
  account holder reading the mailbox.
- **Confirming an address-confirmation link** (Decision 4).

The bootstrap `OWNER` redemption and first-run setup set nothing. Before V1 there is no installed base to
backfill, so every existing row starts unconfirmed.

**3. It gates nothing today.** No request is refused because an account is unconfirmed:

- **Recovery.** It proves address control at use time (ADR 0054 §1). Refusing it to an unconfirmed account
  would lock out exactly the accounts this record exists for.
- **Invites.** Redemption is itself the proof.
- **Sending mail.** The only mail sent to an account's own address is recovery and confirmation, and the account
  holder asks for each of them.

The record is **surfaced** instead. The account holder sees the address the server holds, which is the address
a reset will mail, and whether it is confirmed, while it can still be acted on. It is shown in two places and no
third: the account's **security settings**, and a one-time offer in the **mail settings** right after a save that
leaves the relay able to send. There is **no app-wide banner**, because the only unconfirmed accounts left are the
bootstrap owners and a Server Owner who joined as one — tenant owners who stand in that mail-settings panel
anyway. **One gate is decided now, for
later:** a path that lets someone create an account for an address, other than an invite redeemed by mail, must
prove address control *before* the account exists. That covers open self-registration. The reason is that
creation binds the globally unique address and its auth salt, and a squat cannot be undone by confirming later.

**4. The confirmation token copies the recovery token's lifecycle** (ADR 0054 § Consequences, `veyq_account_recovery`):

- **Minting.** A 256-bit `Tokens.random()`. Only its SHA-256 `Tokens.hash()` is stored, on a row that also
  binds the account id and the **address it was mailed to**.
- **Lifetime.** A `PT24H` expiry: long enough to reach a mailbox read once a day, and short because nothing waits
  on it.
- **Supersession.** A fresh request supersedes the outstanding token.
- **Single use.** Consumption flips the status with a compare-and-set (`… where status = PENDING`).
- **Cleanup.** An expire-then-purge job, and a per-account send budget.

A database dump yields no token that confirms anything. That is hygiene, not a boundary against the operator,
who can write the column directly (Decision 7).

**5. Confirming needs the account's own session.** The link opens a page that confirms only for a caller signed
in as the account the token was minted for, and only while the account's address still equals the one mailed.
The proof this records is *the account holder* reads the mailbox. *Somebody* reading it is not enough. An
anonymous link would let whoever owns a mistyped address click it and mark the wrong address confirmed, which
silences the very defect this exists to show. Unlock is not needed, because this is operational state, not
content.

**6. The mail follows the recovery mail's rules:**

- **Relay.** It travels only through the relay of the tenant the request's `Host` resolves to, and only for a
  member of that tenant (ADR 0054 §4, same reasoning).
- **Content.** It carries link, domain and expiry and nothing else, rendered over the closed `MailContext`
  ([ADR 0049](0049-email-delivery-boundary.md)).
- **Copy.** The text is veyq-fixed and cannot be written by the owner (ADR 0054 §5).

**No mail, no confirmation, and no copy-link.** A tenant that cannot send answers `503 mail_unavailable`
(ADR 0055 §4). A copy-link would hand the token to the person already looking at the screen, and so would prove
nothing. It is refused because it is meaningless, and not only because it is unsafe. Two consequences follow:

- The tenant owner's natural moment is right after saving working mail settings. A confirmation to themselves
  is also the first proof that their relay reaches anyone.
- A Server Owner in no tenant cannot confirm, which matches not being able to recover (ADR 0054 §4).

**7. Operator-blind posture.** The address is already server-visible plaintext. The confirmation timestamp and
the send-fact of a confirmation mail are operational metadata of the same class and join the security-model
inventory. No content, key or sealed field is involved. The record defends against a mistake, and against an
honest operator's database being read. It does not defend against a malicious operator, who can set or clear it
at will. None of that matters, because nothing is gated on it (Decision 3).

## Consequences

- **The one silent account class becomes visible.** Member accounts are confirmed as they are born. The
  bootstrap owner is prompted where they already are, in the mail settings they must configure anyway
  ([uc.admin.tenant-mail-settings](../features/admin/use-cases/uc.admin.tenant-mail-settings.md)).
- **What this does not solve, stated plainly:**
  - **An address confirmed once and later abandoned** is caught only if the holder confirms again. Repeating a
    confirmation is allowed for that reason.
  - **A wrong address has no correction path.** No flow changes an account's address. A detected mistake can be
    named but not fixed until one exists, and that is its own piece of work, deliberately after V1: re-deriving
    the auth keypair under the new salt (which needs the password), confirming the new address before it takes
    effect, clearing the stamp, and the stale address inside an existing Emergency Kit. The V1 owner types their
    own address and then sees it on the join screen, in the Emergency Kit and at every sign-in, so a typo that
    survives all of that is rare; an address abandoned years later is the real case, and it is the later one.
  - **A member invite mailed to a wrong address** can still be redeemed by that mailbox's owner. The
    key-holder's admission step is the human check there ([ADR 0020](0020-grant-distribution-invite-wrap.md)),
    not this record.
- **Recovery gates on the mailed token, never on this record** (Decision 3); ADR 0054 § Consequences states the
  same from its side. A reader who expects "verification" to gate recovery, as an earlier reading of #112 did,
  will find the reason there and here.
- **Rejected:**
  - **Gating recovery on confirmation.** It adds no factor, because the mailed token already proves the mailbox
    at use time, and it strands the accounts it is meant to protect.
  - **An anonymous confirmation link.** See Decision 5.
  - **A short numeric code typed into the session.** It works across devices and binds to the session by
    construction. It also opens a guessing surface that needs its own attempt counter, and it departs from the
    link that ADR 0049 §1 fixes for every veyq mail. The link plus the session requirement gets the binding
    without either cost.
  - **A "send a test mail to me" button with no stored state.** It is cheaper and proves the same thing once. It
    leaves no record to surface later and nothing for the self-registration gate (Decision 3) to build on.
  - **Proving the bootstrap owner's address at redemption through a server-level mailer.** Rejected in ADR 0055
    §3 for the instance-wide mail secret it would reintroduce. This record waits for the tenant's own relay
    instead.

## Addendum (2026-10-14): changing the address

Decided by the coordinator on the owner's behalf, 2026-10-14 (inbox #197;
[uc.account.change-email](../features/account/use-cases/uc.account.change-email.md)); not yet built (cr-change-email).
The address is the login identifier and the auth salt's input, so a change re-derives the auth keypair on the client and
installs it only when the new address is proven:

1. **The client sends the new auth public key with the request**, attested by the current auth key and accompanied by a
   Secret-Key proof (as a password change); the server keeps it **pending** and swaps email and key together when the
   link mailed to the new address is opened in the same account's session. One password prompt; a stolen session alone
   cannot move the reset address.
2. **The confirmation is this ADR's record:** the swap sets `email_confirmed_at`, because the link proved the address.
3. **The old address gets a fixed notice** within ADR 0049's closed variable set (no new address in it); no cancel link
   in this slice.
4. **Every other session is revoked**, as a password change does.
5. **Non-enumerable:** the request answers alike for a free and a taken address; uniqueness is enforced at the swap,
   under a lock, and a taken address fails the confirmation neutrally.
6. **The Emergency Kit:** the old kit's Secret Key stays valid; the app offers a new kit with the new address.
