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
none). The pending list names it by its label (decision 7), else as a link invitation, with its expiry; re-issue gives a new link (new token, key and code) and revoke works as
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

**7. A link invite carries a private label** (owner 2026-11-15: with several open links the owner loses track of whom
each was for). Optional, set at mint and changeable later by any owner of the tenant, and the pending row's title. Only
a link invite has one: a mailed invite is named by its address. The label is content, so it is sealed as an
[ADR 0115](0115-a-scope-key-is-replaced-never-chained.md) value: a value key of its own, wrapped under the tenant scope's
current key, field purpose `member-invite-label`, its value address bound to the tenant and the invite's id, so a
server that moves a label onto another invite gets one that does not open. Every owner opens it. Every member holds the
key that would, so the server hands it out only in the owner-gated pending list, never in `listOpenInvites`. A tenant
rotation re-wraps its value key like any other tenant value's (the rotator is handed the wrap, never the sealed label).
An invite that ends drops its label in the statement that drops its key and grant. A re-issue mints a new invite id,
so the owner's client seals the label anew for it. Its last editor is recorded
([ADR 0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) § 8).

## The apps (built 2026-11-27)

Inviting and joining are in the four apps too (owner 2026-11-26: every app does what the web does). Decided as follows:

1. **The suite is the Kotlin core's, held to the vectors.** `crypto/Invites.kt` ports `invite.ts` line for line: the
   invite key, the owner's signature, the accept proof, the code, the link KEK, the version-2 payload and its seal, the
   sealed part's one-profile parse, the fragment and `checkInvite`. `Conformance` runs `#invite_key`, `#invite_link` and
   `#join_record` on the JVM, over libsodium and in `@veyq/core-js`. `InviteCheckTest` ports `inviteCheck.test.ts`, each
   refusal beside its control. A sealed part with another profile is refused as `InviteLinkInvalid("profile")`, the web's
   `KdfProfileRefused`.
2. **Inviting is the core's** (`session/TenantInvites`, `TenantSession.invites`): a link invite (decision 1) or a mailed
   one, the pending list with each link invite's label opened under the tenant key (decision 7), re-issue, revoke and the
   label, from Settings' Members. The link and the code are answered once, to the call that made them, and never kept
   or sent in an update. They are made to be handed on, as a share link and its password are, so they are not covered
   as secrets on screen (`src/mobile/CLAUDE.md`).
3. **An invite enters the app through *Open a link* and a shared text, never through `veyq:`.** `ReceivedLink.inviteOf`
   reads `<origin>/tenant/accept#…`, also as the one link of a pasted message (the result's *Copy* copies the whole
   message). A `veyq:` link carries nothing (ADR 0150 § O1), and the later deep links of inbox #1415 leave invites out
   (owner 2026-11-27). **Residual:** on the desktops, a person who is signed in reaches *Open a link* only after a sign-out
   (no share sheet there).
4. **The join is the core's** (`session/TenantJoin`, the web's `TenantJoinView`): the preview, the code, the checks
   before anything is sent, a new account born on the device with its Emergency Kit written by the core, or an existing
   one signed in cold with its password and Secret Key; the `409 invite_changed` / `stale_epoch` re-check, and an OWNER
   invite's birth and a handover as on the web. The join signs in on its own: to accept for an existing account, and once
   after a registration to hold the accepted key (ADR 0111 decision 23) and to ask a link registration's confirmation mail
   (decision 4). It then lets that account go. *Open the space* signs in through the app's own session model with what the
   join holds in memory. A second sign-in was chosen over handing the join's account to the session model, which is
   generic over its door. The inviting owner is pinned from the served chain (`Anchoring.anchorServedChain`).
5. ~~**Not in the apps:** the web's optional picture at the join.~~ Superseded 2026-11-27: the apps' join takes it too
   ([ADR 0148](0148-the-apps-account-the-webs-flows-ported-into-the-core-the-kit-written-by-the-core-into-the-file-the-person-chose.md)
   § Members decision 7). A join whose server is not reached ends as an invalid invite, as the web's catch-all does
   (`TenantJoinLoadTest`; found 2026-11-27, it stayed on "Checking your invitation…").

Proved by `InviteCheckTest`, the conformance runners, `InviteLinkTest` and the bridge's `WireTest`, `SecretFreeSchemaTest`
and `DispatchTest` (each new check bitten), and live by `InvitesLiveTest` as a throwaway: a link invite labelled, renamed,
re-issued, joined by a new account that opens the space, a mailed invite revoked, the joined account erased. It is bitten
by an accept proof signed with the wrong key, which the server refuses. The passes are in native-apps § Built, by area.

## Consequences

- **A link is a bearer credential.** Whoever holds it joins with the tenant key, and what they read before the owner
  removes them stays read; removal rotates the tenant for what follows. The owner chooses the channel and its
  readers (the messenger, the mail provider, the screen). A taker who redeems first is noticed by the real invitee
  (decision 6); a taker who names themselves like the invitee is caught only so, or by the safety number.
- **The operator sees less, not more, of a link invite** than of a mailed one: no address, no sealed part, nothing to
  guess. It still learns that an invite exists, when it was minted, redeemed and by which account, and whether it
  carries a label and roughly how long it is (decision 7), as for a share's label.
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
  - **The label under the invite's own key, or in the link** (decision 7). A re-issue replaces that key, and the link's
    sealed part reaches whoever takes it, who would then read what the owner called them.
  - **The label under the minting owner's own key** (decision 7). Co-owners, who manage the same pending list, would
    see none.
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
