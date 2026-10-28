# ADR 0074 — Address control is recorded where nothing else proves it, and gates nothing

- Status: Accepted
- Built: yes
- Date: 2026-09-28
- Model: [cc.mail](../crosscutting/cc.mail.md)

## Context

An account's email is its login identifier, the input of its auth salt
([ADR 0003](0003-operator-blind-password-auth.md)) and the only channel a password reset can reach
([ADR 0054](0054-password-recovery-two-factor-release.md)). It is globally unique and stored in plaintext. Nothing
records whether the account holder can read that mailbox. Whether that matters depends on who can make an account for
an address, so this starts from the paths that exist, not from the generic "verify on sign-up" pattern.

| Path that binds an address | Who types it | Address control proven? |
|---|---|---|
| Member invite, redeemed by a new or an existing account | the tenant owner | **Yes** — the invite travels by mail only ([ADR 0055](0055-mail-is-a-precondition-not-a-capability.md) § 2), so the token that arrives is the proof. |
| The tenant's first owner, redeeming the bootstrap invite | the Server Owner | **No** — the one copy-link (ADR 0055 § 3): whoever holds the link redeems it. |
| First-run setup (the Server Owner) | the operator, for themselves | **No** — nothing is mailed to it. |
| Changing an account's address | the account holder | **Yes** — the new address takes effect only when the link mailed to it is opened (Decision 8). |
| Open self-registration | — | No such path exists. |

Against that inventory:

- **Account takeover through a wrong address does not need this.** A reset needs the mailed token *and* the Secret Key
  (ADR 0054 § 1); whoever reads a mailbox that is not the holder's receives a wrap they cannot open. ADR 0054 proves
  address control when it is used, and a flag stored months earlier adds nothing to that gate.
- **Squatting an address is not open to an attacker.** Both unproven paths are started by the Server Owner, who already
  writes the database. Only open self-registration would let someone who does not read an address create an account
  for it.
- **What is left is a silent durability defect.** The tenant owner, the account most likely to hold everything, is the
  one class whose address nothing ever tested. A typo stays invisible until the day a reset is needed and cannot reach
  them — and a forgotten password plus an unreachable mailbox is permanent loss.

Nothing can prove the bootstrap owner's address at redemption: a `PENDING` tenant has no relay, and the instance's mail
carries only the host's notices, never a tenant's mail
([ADR 0097](0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md) decision 3). The earliest proof is the
moment that owner's tenant can send. And "verified" already names identity verification and the login `verify` step, so
this record says **confirm**.

## Decision

**1. The record is a fact about the address, not a permission.** `veyq_user.email_confirmed_at` means *the account
holder showed, at that time, that they read the mail sent to this address*. It is bound to that address: a path that
changes the address clears it in the same write, unless the change is itself the proof (Decision 8). The code reads only
its presence; the time is kept on purpose, as the record of *when* control was shown.

**2. Exactly four events set it:** redeeming a `MEMBER` invite (keyed on the role, which ADR 0055 § 2 makes mail-only by
construction), completing a password reset (the mailed token plus the Secret Key), confirming an address-confirmation
link (Decision 4), and confirming an address change (Decision 8). The bootstrap `OWNER` redemption and first-run setup
set nothing; every row before V1 starts unconfirmed.

**3. It gates nothing.** No request is refused because an account is unconfirmed: recovery proves address control at
use time and refusing it would lock out exactly the accounts this exists for; an invite's redemption is itself the
proof; and every mail to an account's address is set off by the holder's own request. The record is **surfaced**
instead — in the account's security settings, and once in the tenant's mail settings after a save that leaves the relay
able to send — and nowhere else: the only unconfirmed accounts left are bootstrap owners, who stand in that panel
anyway, so an app-wide banner would speak to nobody else. **One gate is decided for later:** any path that lets someone
create an account for an address, other than a mailed invite, must prove address control *before* the account exists,
because creation binds the unique address and its auth salt and a squat cannot be undone by confirming later.

**4. The confirmation token copies the recovery token's lifecycle** (ADR 0054): a random 256-bit token stored only as
its hash, bound to the account and the address it was mailed to, a 24-hour expiry, superseded by a fresh request, used
once, purged, and a per-account send budget ([cc.mail](../crosscutting/cc.mail.md) § Address control). A database dump
yields no token that confirms anything — hygiene, not a boundary against the operator, who can write the column
(Decision 7).

**5. Confirming needs the account's own session**, and an address still equal to the one mailed. The proof is that *the
account holder* reads the mailbox; an anonymous link would let whoever owns a mistyped address mark it confirmed and
silence the very defect this exists to show. Unlock is not needed: this is operational state, not content.

**6. The mail follows the recovery mail's rules:** only through the relay of the tenant the `Host` resolves to, only for
a member of it (ADR 0054 § 4), carrying link, domain and expiry ([ADR 0049](0049-email-delivery-boundary.md)) in
veyq-fixed copy. **No mail, no confirmation, no copy-link:** a tenant that cannot send answers `503 mail_unavailable`
(ADR 0055 § 4); a copy-link would hand the token to the person already at the screen and prove nothing. So the tenant
owner's natural moment is right after saving working mail settings — a confirmation to themselves is also the first proof
that their relay reaches anyone — and a Server Owner in no tenant cannot confirm, as they cannot recover.

**7. Operator-blind posture.** The confirmation time and the send-fact are operational metadata of the address's own
class and join the security model's inventory; no content, key or sealed field is involved. The record defends against a
mistake and against an honest operator's database being read, not against a malicious operator, who can set or clear
it — which does not matter, because nothing is gated on it.

**8. Changing the address re-derives the auth key and takes effect only once the new address is proven.** The address
is the auth salt's input, so a change installs a new auth public key, derived by the client under the new address. The
request carries both proofs a password change has — the current auth key's attestation and a Secret-Key proof — so a
stolen session alone cannot move the address a reset is mailed to; the change stays **pending** until the link mailed
to the new address is opened in the same account's session, then swaps address and key together and stamps the new
address confirmed (Decision 2). The old address gets a fixed notice with no new address and no cancel link; every other
session is revoked; the request answers alike for a free and a taken address. The flow is
[uc.account.change-email](../features/account/use-cases/uc.account.change-email.md).

## Consequences

- **The one silent account class becomes visible.** Member accounts are confirmed as they are born; the bootstrap owner
  is prompted in the mail settings they must configure anyway
  ([uc.admin.tenant-mail-settings](../features/admin/use-cases/uc.admin.tenant-mail-settings.md)).
- **What this does not solve:** an address confirmed once and later abandoned is caught only if confirmed again
  (repeating is allowed); a wrong address is corrected only by its holder, who needs the password and the Secret Key for
  it; the old address is told, not asked; the old Emergency Kit names the old address (its Secret Key still works, a new
  kit is offered); open invites to the old address can no longer be accepted, because an invite matches by address; and
  a member invite mailed to a wrong address can be redeemed by that mailbox's owner, where the key-holder's admission
  step is the human check ([ADR 0020](0020-grant-distribution-invite-wrap.md)).
- **Recovery gates on the mailed token, never on this record** (Decision 3); ADR 0054 says the same from its side.
- **Rejected:**
  - **Gating recovery on confirmation.** It adds no factor — the mailed token proves the mailbox at use time — and
    strands the accounts it is meant to protect.
  - **An anonymous confirmation link.** Decision 5.
  - **A short numeric code typed into the session.** It binds to the session by construction, but opens a guessing
    surface with its own attempt counter and departs from the link every tenant mail carries (ADR 0049 § 1). The link
    plus the session requirement gets the binding without either cost.
  - **A "send me a test mail" button with no stored state.** Cheaper, and proves the same once, but leaves nothing to
    surface later and nothing for the self-registration gate to build on.
  - **Proving the bootstrap owner's address through the instance's mail.** The instance's mail is never a channel for a
    tenant's mail (ADR 0097 decision 3), and the confirmation would then prove nothing about the tenant's own relay —
    the second thing a tenant owner's confirmation proves (Decision 6).
  - **Changing the address on the session alone, or before the new address is proven.** A stolen session would move
    the address a reset is mailed to, and an unproven address could be a typo — the silent defect this record exists to
    show.
