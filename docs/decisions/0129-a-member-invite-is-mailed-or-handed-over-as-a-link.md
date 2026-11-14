# ADR 0129 — A member invite is mailed, or handed over by its owner as a link that opens by itself

- Status: Accepted
- Built: yes
- Date: 2026-11-12
- Model: [scopes-and-tenancy](../crosscutting/scopes-and-tenancy.md)

## Context

A member invite today is bound to an address and travels one way: the server mails `…/tenant/accept#<token>.<sealed>`,
and the owner passes the code that opens the seal (or a password they chose) outside veyq
([ADR 0055](0055-mail-is-a-precondition-not-a-capability.md) § 2,
[ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md) decision 1).
Two channels for every invite are a chore the owner wants to be optional: most invitations go out in a messenger the
owner already uses with that person, and the code then has to follow in the same messenger anyway.

The second channel exists because the server mails the link: the operator holds every mailed sealed part, and the code
is the only thing it lacks. A link that the owner's own client builds and the owner hands over never passes the
server, so the code can ride in it.

The address binding does more than address the mail (`TenantAcceptHandler`, measured 2026-11-12): a new account is
born for the invite's address, an existing one must have it (`403 not_invited_owner`), and the redemption marks the
address confirmed, because a mailed token is the proof of the mailbox
([ADR 0074](0074-address-control-is-recorded-where-nothing-else-proves-it.md) decision 2). A link bound to nobody loses
all three.

## Decision

**1. Two ways to invite a member, both from the owner's unlocked vault, both a placeholder member (ADR 0111).**
- **By mail** — unchanged: an address, the server mails the link, the owner passes on a generated code or a chosen
  password; the response carries no secret.
- **As a link** — no address: the server mints the invite, sends nothing and answers the single-use token to the
  owner's client only. The client builds `…/tenant/accept#<token>.<sealed>.<code>` and offers it to copy (as a short
  message the owner may edit), to the system share sheet, and to the owner's own mail program (`mailto:`). The code is
  always a generated one; a password would ride in the same link and add nothing. The seal stays, so there is one link
  format and one opening path; it no longer guards anything against whoever holds the link, and the server never
  receives the sealed part or the code, so nothing is left for the operator to guess offline. Token, seal and code ride
  the URL fragment, which a link preview (a messenger fetching the page) never sends; the accept page strips it on load.
  A separate operation mints it, so the mailed path's "no secret in the response" stays a property of its operation.

**2. A link invite is bound to nobody.** Whoever opens it registers with an address they type, or signs in with any
account of this instance; an account already a member is refused at accept. Its open invite counts against the
tenant's mint budget, not a recipient's, and several may be open at once (supersede-on-mint is per address and has
none). The pending list names it by its expiry, re-issue gives a new link (new token, key and code) and revoke works as
for a mailed one.

**3. Mail stays a precondition for inviting at all** (owner 2026-11-12). A tenant that cannot send refuses both ways
before any mint (`503 mail_unavailable`), so every account a link creates can be sent its address confirmation at once.
ADR 0055 § 1 stands; only its § 2's "mail is the only channel, no member surface offers a link to copy" is replaced.

**4. A link redemption proves no address.** An account it creates starts **unconfirmed**; an existing account's
confirmation is left as it was. Right after a new account joins this way its client asks for the confirmation mail
(ADR 0074 decision 4, the account's own session). ADR 0074 decision 2's member-invite event is keyed on the redeemed
invite having been **mailed**, not on the role. ADR 0074 decision 3's gate — a path that creates an account for an
address other than a mailed invite proves control *before* the account exists — is narrowed to paths open to anyone
(an open self-registration): a link is issued by a tenant owner, single-use and expiring, so the squat it allows is
bounded by the owner's links and undone by erasure ([ADR 0101](0101-an-account-is-erased-on-request-and-only-what-the-law-keeps-stays.md)).

**5. An unconfirmed account carries a persistent banner** (owner 2026-11-12) on every page of the signed-in app, until
its address is confirmed: it names the address, sends the confirmation mail again, and cannot be dismissed. Where the
tenant cannot send, it says so and that an owner of the space must set up mail. This replaces ADR 0074 decision 3's
"surfaced in the security settings and once in the tenant's mail settings, nowhere else", whose reason — only bootstrap
owners stay unconfirmed — no longer holds.

**6. A redeemed link says so.** The preview answers a consumed invite with its own refusal (`410 invite_redeemed`),
distinct from a revoked or expired one, and the accept page tells the person to tell whoever invited them. That is how
a link someone else took first is noticed; it is detection, not prevention. The row is kept until the terminal-invite
purge, so this holds for the retention window after expiry; after it the token is unknown.

## Consequences

- **A link is a bearer credential.** Whoever holds it joins with the tenant key, and what they read before the owner
  removes them stays read; removal rotates the tenant for what follows. The owner chooses the channel and its
  readers (the messenger, the mail provider, the screen). A taker who redeems first is noticed by the real invitee
  (decision 6); a taker who names themselves like the invitee is caught only so, or by the safety number.
- **The operator sees less, not more, of a link invite** than of a mailed one: no address, no sealed part, nothing to
  guess. It still learns that an invite exists, when it was minted, redeemed and by which account.
- **An address a link registration binds may be one its holder does not read.** Recovery then cannot reach them;
  the banner (decision 5) and the confirmation mail sent at once make that loud on day one.
- **Rotation cost is unchanged in bound:** every open invite is re-granted at a rotation (ADR 0111 decision 2), and
  open invites are bounded by the same per-tenant mint budget and lifetime as before.
- **Rejected:**
  - **The code in the server-mailed link.** The operator would hold seed and grant: it derives the invite key and
    opens the grant to the tenant key without redeeming anything, so the single use never fires, and while the invite
    is open every rotation re-grants it the next key. The loss is every member's, not the inviting owner's, so a
    warning to the owner cannot consent to it.
  - **A member pending until the address is confirmed.** The invitee holds the tenant key from the moment it grants
    itself (ADR 0111 decision 3), so "pending" could only withhold ciphertext, and a confirmation that never comes would
    need a removal rotation; a new membership state would reach every read, the member list and every rotation.
  - **Proving the address before the account exists** (a mailed code at registration). It keeps ADR 0074 decision 3
    whole, at the cost of a second step for every invitee — the chore this ADR removes from the owner.
  - **Link invites only for existing accounts.** It would leave the new person, the common case, on the two-channel way.

## Related

- [uc.spaces.add-member](../features/spaces/use-cases/uc.spaces.add-member.md),
  [uc.spaces.manage-member-invites](../features/spaces/use-cases/uc.spaces.manage-member-invites.md),
  [uc.account.confirm-email-address](../features/account/use-cases/uc.account.confirm-email-address.md)
- [ADR 0055](0055-mail-is-a-precondition-not-a-capability.md) § 2,
  [ADR 0074](0074-address-control-is-recorded-where-nothing-else-proves-it.md) decisions 2 and 3,
  [ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md) decision 1
- [cc.mail](../crosscutting/cc.mail.md), [security-model](../crosscutting/security-model.md)
- Security area of the docs site: [Threat model](../../site/src/content/docs/security/threat-model.md)
