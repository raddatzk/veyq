---
title: Known limitations
description: Every residual of the built system in one place — why it remains, where it is decided, and what would close it.
sources:
  - docs/decisions/0104-the-identity-pin-store-is-one-sealed-entry-per-pin-under-a-sealed-head.md
  - docs/crosscutting/security-model.md
  - docs/decisions/0028-identity-authenticity-tofu-verification.md
  - docs/decisions/0024-scope-key-rotation.md
  - docs/decisions/0072-a-seal-binds-its-object.md
  - docs/decisions/0068-favorites-two-confidentiality-levels.md
  - docs/decisions/0075-a-link-a-departed-member-could-copy-is-reissued.md
  - docs/features/sharing/enablers/en.sharing.public-link.md
  - docs/decisions/0109-a-scope-rotation-re-keys-every-node-below-it.md
---

This page collects what veyq does **not** protect against today, in one place. Each entry says why it remains, where
it is decided, and what would close it. The ceiling for every statement here is
[security-model § What holds today on a client the operator does not serve](https://github.com/raddatzk/veyq/blob/main/docs/crosscutting/security-model.md#what-holds-today-on-a-client-the-operator-does-not-serve)
and its list *What stays open*; nothing on this page claims more.

:::caution[Read the tier first]
Most entries below concern an **actively malicious operator** on a client that operator does **not** serve. On a
web client the operator serves, the answer is simpler: veyq protects against a **passive** operator only, whatever
the rest of this page says ([first entry](#the-operator-served-web-client-is-passive-operator-only)). Self-hosted, the
operator is you, and the active-operator entries do not apply.
:::

## Overview

| Limitation | Who can use it | Decided in | Closed by |
|---|---|---|---|
| [Operator-served web is passive-only](#the-operator-served-web-client-is-passive-operator-only) | the operator who serves your client | ADR 0026 | serving your own client; [trusted third-party client](/veyq/security/roadmap/) |
| [TOFU's first-use gap](#identity-keys-tofus-first-use-gap) | active operator | ADR 0028, ADR 0111 | a safety-number compare (built); [Key Transparency, strict mode](/veyq/security/roadmap/) |
| [Freshness only where a device keeps a mark](#freshness-only-where-a-device-keeps-a-mark) | active operator | ADR 0072, ADR 0028 Decision 7 | nothing recorded beyond the three marks |
| [Forks and queued rotations](#scope-rotation-forks-queued-rotations-and-an-invented-member) | active operator; a rotator, to junk a value | ADR 0024, ADR 0115 | nothing recorded; a junked value is visible |
| [A member the operator presents as an owner](#identity-keys-tofus-first-use-gap) | active operator | ADR 0111 | signed roles and removals, not designed (a non-owner without a join record is an anomaly, built) |
| [QR reader who also runs the relay](#device-linking-an-attacker-who-reads-the-qr-and-runs-the-relay) | operator who also sees the QR | ADR 0059 §11 | nothing recorded |
| [Item favorites' count](#item-favorites-a-count-as-honest-as-the-operator) | the operator; with a member, forged entries | ADR 0068, ADR 0115 | a freshness floor covering them (ADR 0068 §9) |
| [Departed members and copied links](#departed-members-and-the-links-they-could-copy) | removed member | ADR 0075, ADR 0109, ADR 0114 | a close or a re-issue (built; also against a dump of what is added after) |
| [Guest names vs. a viewer link](#guest-names-and-an-operator-holding-a-link) | operator holding a link | ADR 0030 point 8 | accepted |
| [Share password is access control](#a-share-password-is-access-control-not-cryptography) | operator colluding with a link holder | ADR 0037 | [cryptographic share password](/veyq/security/roadmap/) |
| [Revocation cannot retract what was read](#revocation-cannot-retract-what-was-already-read) | any former recipient | security-model, ADR 0024, ADR 0025, ADR 0109, ADR 0114, ADR 0115 | inherent; links by a close or a re-issue |
| [Server-visible metadata](#metadata-the-server-sees) | operator | security-model | by design |
| [Append-only entries can be dropped or reordered](#applications-entries-are-not-bound-to-their-neighbours) | operator | security-model, ADR 0072 | a sequence number or hash chain per application |
| [Per-instance rate limiting](#rate-limiting-is-per-instance) | online attacker | cc.security.rate-limit | deferred hardening |
| [One seal implementation, no external audit](#assurance-one-implementation-no-external-audit) | — | ADR 0017 | a second independent implementation |
| [Losing every way back is final](#losing-every-way-back-is-final) | — | security-model § Recovery | by design (no escrow) |

## The operator-served web client is passive-operator only

Confidentiality is enforced by the client, so it holds against an *actively* malicious operator only on a client
whose code that operator cannot silently alter. A web client loaded from the operator's own origin is re-served on
every load, and an active operator can serve tampered code that captures keys or plaintext. On that client veyq
protects against a **passive** operator (honest serving, data at rest) and says so in the client; it is never
claimed operator-proof. An operator-owned domain is always operator-served, because the operator controls its DNS.
The no-install share viewer and contributor page are operator-served web and fall under this too.

A stronger unlock method does not change this: a passkey's secret is consumed by code the operator ships, so
*unlock convenience and client integrity are orthogonal*.

**Decided in:** [ADR 0026](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0026-client-trust-tiers.md),
  [security-model § Client integrity & trust tiers](https://github.com/raddatzk/veyq/blob/main/docs/crosscutting/security-model.md#client-integrity--trust-tiers--what-a-malicious-operator-can-do).

**Closed by:** self-hosting (the operator is you), or serving the web client yourself against someone else's
  server — the tier the rest of this page describes. In a browser on someone else's instance, only code from an
  origin the operator does not control would close it: the [trusted third-party client](/veyq/security/roadmap/)
  on the Roadmap.

## Identity keys: TOFU's first-use gap

To share with a member, a client wraps a key to that member's identity public key (a hybrid post-quantum encryption
key and an Ed25519 signing key), which the server relays from its directory. What is built:

- **Pin on first use.** The first time a client meets a member's identity, it pins the fingerprint of the full public
  bundle and the head of the member's **key chain** in a pin store that is client-side, synced across the user's
  devices and sealed under the Master Key. The server never sees a pin.
- **In a tenant, the join record anchors.** Every newcomer writes a join record at their invite's accept: their
  identity and Secret-Key proof key, signed by the invite key the owner signed and by the newcomer. A client wraps in
  a tenant only to a member whose join record verifies under an owner the server lists and this client anchors; the
  member's chain is walked from the step the record names, whatever the server serves before it. A non-owner without
  a join record is an anomaly; an owner without one (the tenant's first) is trusted on first use
  ([ADR 0111](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
  decisions 12 and 15).
- **A new key is accepted only through the chain.** A member who secures their account adds a step signed by their
  old identity **and** their old Secret-Key proof key; a client accepts the new key when the chain reaches it from the
  head it pinned, with no review. Any other key is an anomaly (*Can't confirm this key*): nobody wraps to it, a
  rotation declares that member pending, and the grant-delivery worker declines the job back to the server
  ([Protocols](/veyq/security/protocols/#7-the-key-chain-and-securing-an-account)).
- **Safety numbers and chain codes.** Each member's profile shows their safety number, the chain code of their key
  chain's head and a QR of both; another member scans the QR or compares the digits, and a match writes a
  verification the verifier signs into the tenant, which every member there counts while it names the subject's
  current head. A step withheld from one side shows as a different chain code.
- **Grants are signed.** Every grant carries its granter's Ed25519 signature, which the recipient verifies before
  opening it: in the personal scope only under its own key, in a shared scope under a key of the granter's pinned
  chain (a retired one only for an epoch at or below the device's epoch floor).
- **A tenant invite is anchored by its link.** The invitee's client derives the invite key from the seed sealed in the
  link and walks the owner's key chain from the head sealed beside it to the key that signed the invite, so the
  operator, which mails the link, cannot swap the key or the owner in it, and an owner who secured their account since
  is followed through their chain. The tenant key it is handed must be the owner's own grant kept beside the invite,
  or be reached from it through the tenant's succession statements, so the operator cannot hand the newcomer a key of its choosing. The seal opens only with a code
  the owner passes on outside veyq (50 bits behind Argon2id), or a password the owner chose
  ([ADR 0111](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
  decisions 1 and 19).

**What remains.** TOFU cannot detect a lie told before first sight. If the operator substitutes a key *before*
anyone pinned the real one, and re-wraps genuine content too, nothing looks wrong until the two members compare safety
numbers. Inside a tenant that is left for owners, whom no join record anchors; outside one, for every first contact.
The same gap covers further cases:

- **A device with no trust-state mark yet** — one that signed in with the password and Secret Key, or lost its local
  storage — trusts the first pin store it is served (ADR 0028 Decision 7; see
  [Freshness](#freshness-only-where-a-device-keeps-a-mark)).
- **A member the operator presents as an owner.** A membership the operator writes as an ordinary member has no join
  record and is an anomaly nobody wraps to. But who is an owner is the server's word — roles are not signed — so a
  membership it writes as an owner is a root, trusted on first use, and can root keys of its choosing. Before it
  mints an invite, the server checks that the invite key carries the caller's own owner signature, so a stolen owner
  password without the unlocked vault cannot invite an account of the attacker's (ADR 0111 § What stays open).
- **A tenant invite guessed open.** The operator holds every mailed link, sealed part included, and can guess the code
  offline; a weak password the owner chose instead falls faster.
- **A shared-scope grant signed by a granter identity the operator fabricated**, where the pin alone decides: the
  tenant scope's own grant, and a group's grant outside the active tenant — in a device's first grant of that scope:
  a later epoch must be reached through succession statements from the key the device held, which the operator never
  held, and the device that accepted
  an invite holds the key it checked there from the accept on. That first grant pins the
  granter on first use, so an operator that fabricates both the granter and the key is not detected on the spot —
  only by the next verification of that granter's safety number. Someone who holds the device's key (a member, or one
  removed while the tenant has not re-keyed since) can, with the operator, still sign a next epoch of its own. In the active tenant a group grant from a granter without an
  owner-rooted join record is refused. The signature rules out a grant presented under the identity of someone the
  recipient has already pinned (ADR 0028 Decision 6).
- **A group's first grant from any member of its tenant.** A device that holds no key of a group yet — a newcomer's
  after accepting the proposal, a new or linked device — opens a grant from any member the tenant anchors, in the
  group or not. With the operator, such a member has the device write under a key of its choosing. Accepted, because it
  gains nothing over a group of that member's own: which groups you are listed in, who else is in one and who proposed
  you are the server's word, so the operator can present that group as this one. Nothing on the device tells the two
  apart; closing it needs signed group membership (ADR 0111 § What stays open).

**Related residuals of the same mechanism:**

- **The operator can install an identity of its own on an account.** It writes the directory itself. Through the
  API, a session or a password no longer suffices — rotation, change-password and recovery need a signature from the
  Secret-Key proof key — but that binds every caller except the operator. A member who pinned the account's head, and
  in a tenant every member where the account joined with a join record, sees the installed key as an anomaly, because
  no chain step the operator can sign reaches it; outside that, a member who never pinned it trusts it on first use
  until Key Transparency exists
  ([ADR 0060](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0060-key-rotation-after-compromise.md)
  Decision 2).
- **A worker's decline is per key-holder, not a global veto.** A key-holder whose pinned chain does not reach the
  key declines; a different key-holder who never saw the member has no pin, trusts the served head on first use and
  delivers. Deliberate: the blind server must not adjudicate a cryptographic claim, and a global quarantine on
  one worker's say-so would be a denial of service.
- **Verification is advisory.** Nothing enforces it before a first share. A withheld step is seen by a device that
  already saw a newer one, or by two members whose chain codes differ.
- **The server sees who verified whom.** A verification's content is sealed under the tenant key, so the server cannot
  read, fake or tell which ones count; but each is stored under its tenant, verifier and subject with the time it was
  written. Decided, not an oversight: the server authenticates every write, so it knows the verifier whatever the row
  is keyed by, and a compare's two writes arrive seconds apart, so hiding the subject would hide little.
- **A later epoch planted for a newcomer by someone who held the anchor's.** When a rotation by someone other than the
  inviting owner carried an open invite, a party who held the epoch the invite is anchored at — a member, a former
  member, or a thief of a member's device — could, with the operator, serve the newcomer a later epoch of its own,
  succeeding it under a statement that party signs. The owner's own rotation moves the anchor past it.
- **The owner's securing withheld from a new account.** A newcomer that pinned nothing cannot tell a chain served only
  up to the head in the link from the owner's whole chain, so a thief of the owner's retired key could, with the
  operator, pass the invite's check under that key. The newcomer's join record then names the retired key, which
  counts only with the owner's endorsement by their current key, so every co-member reads it as an anomaly; an honest
  server refuses the accept.
- **A moderation statement's recipients are sealed to unchecked.** The keys `getModerationStatementRecipients` returns
  are sealed to without the anchor gate: the moderation surface holds no pin store, and the recipients may share no
  tenant with the moderator, so there is nothing to anchor them by. An operator that substitutes a key reads that
  statement's facts text, beside the measure's kind, target, legal ground and provision, which it reads anyway.
  Accepted: the law asks for the statement's delivery, not for its secrecy from the provider.

**Decided in:** [ADR 0028](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0028-identity-authenticity-tofu-verification.md),
  [security-model § Key-directory trust](https://github.com/raddatzk/veyq/blob/main/docs/crosscutting/security-model.md#key-directory-trust--tofu-pinning--out-of-band-verification-adr-0028).

**Closed by:** the join record closes it inside a tenant for every member but an owner; a safety-number compare
  closes it for the members who make one. Signed roles are not designed. Enforcement
  ([strict verification mode](/veyq/security/roadmap/)) and the end-state
  ([Key Transparency](/veyq/security/roadmap/)) are on the Roadmap.

## Freshness only where a device keeps a mark

Every seal binds its purpose and the identity of the object it is stored as — the scope, the node, media, blob,
bucket, share or member, and the epoch or revision where the wire carries one. A ciphertext served for another object
therefore fails to open. Binding makes an epoch or revision **authentic**; it does not make it **fresh**. Which
authentic version of an object, and which epoch's grant, a client receives is still the server's choice
([ADR 0072](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0072-a-seal-binds-its-object.md) Decision 5).
The server also keeps every capability it had over which objects exist and where they sit in the tree.

Refusing an older authentic version needs a floor the client holds itself. A device keeps one in exactly three
places:

| Mark | What the device refuses | Decided in |
|---|---|---|
| Pin store | a store below the highest revision it opened, a missing store once it held one, a store that does not open, an entry withheld, added, rolled back or moved under its head — before every read and every write | ADR 0028 Decision 7, ADR 0104 |
| Device register | the same rule | ADR 0028 Decision 7 |
| Per-scope epoch floor, and the succession public key of the epoch key it held | a grant below the highest epoch it has held for that scope, and one at or above it that the succession statements do not lead to from the key it held; it never seals under either | ADR 0024 Decision 6, ADR 0111 decision 23, ADR 0115 decision 5 |

The marks are per device, local and never synced (a synced mark is one more thing the server could roll back). They
are filed under a one-way derivation of the Master Key, not under an id the server names. A device linked by another
inherits the linker's marks inside the authenticated pairing envelope.

**What remains:**

- **Everything else.** For every other object the server chooses which authentic version it serves — including,
  for item favorites, an older genuine version of the caller's own set
  ([below](#item-favorites-a-count-as-honest-as-the-operator)).
- **A node rolled back past a link's close.** The epoch floor watches the scope's epoch, which a close does not
  move, so an active operator can serve a member the subtree as it stood before the close's re-key, and that member's
  device seals under the old node key again. A per-node mark is not built
  ([ADR 0114](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0114-closing-a-link-re-keys-the-subtree-below-it.md) § Consequences).
- **A device with no mark** trusts the first store it is served — the first-use gap applied to trust state. After
  an account-key rotation, every device other than the rotating one unlocks afresh and has no marks unless it is
  linked again.
- **A consented downgrade.** A refused document has one way back: the user starts it over, empty, after a
  confirmation that names the cost (every contact first-seen again). No server response
  reaches that write, but a server that keeps a document refused can wait for the user to press it. The downgrade
  is visible and consented, not silent. On a client the operator serves, it could ship a client that writes the
  empty store itself.
- **A fork** — a newer version the server keeps from a device that never held it
  ([next section](#scope-rotation-forks-queued-rotations-and-an-invented-member)).


**Closed by:** nothing beyond the three marks. ADR 0072 names a floor for no other object.

## Scope rotation: forks, queued rotations and an invented member

Removing a member from a group replaces the scope key in the same request, wrapped only to the members the
remover's own client picked, minus the one it removes; every scope-level value's own key is re-wrapped under it in the
commit (a rotation never rewrites a value), and new content seals under it. Against an active operator, on a
client it does not serve, this holds for content sealed by any client that has received the new epoch
([ADR 0024](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0024-scope-key-rotation.md) § Guarantee).
Four things it does not cover:

- **A withheld rotation (a fork).** The operator can keep a member from ever learning of the new epoch. That
  member's client keeps writing under the old one, and nothing on the wire tells it otherwise; the epoch floor only
  stops a client going *back* from what it has seen. ADR 0024 states that detecting a fork needs a transparency
  mechanism.
- **Rotations queued to another key-holder.** A tenant-member removal that cuts a co-shared group, and an account
  rotation after a compromise, are completed by whichever owner of the scope is next online. That client knows only
  the member list the server returns and has nothing of its own to exclude by, so these hold against an honest
  operator only.
- **A member the server adds to the list as an owner.** A rotation wraps only to members whose join record an
  anchored owner's invite rooted, and to owners without one, so a membership the operator writes as an owner before a
  rotation is re-granted by it (see [TOFU's first-use gap](#identity-keys-tofus-first-use-gap)).
- **A rotator junking a value.** A rotation re-wraps each value's own key and never rewrites a value: the stage
  carries wraps only, the server refuses one that writes a value, and a wrap of a key of the rotator's choosing opens
  no value sealed under another, so a rotator — an owner, or a member securing their account — cannot forge a name, a
  picture, a join record, a verification, an item mark, a group's name or icon, or a link's recovery copy. It can stage
  a stand-in for a wrap it could open, which makes that value unreadable, and a junked recovery copy closes its link,
  key lost: an insider's denial of service, visible as a missing value or a closed link, that reads nothing it could
  not read already.

**Decided in:** ADR 0024 § Guarantee;
  [ADR 0115](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0115-a-scope-key-is-replaced-never-chained.md)
  decision 2 (the junked value);
  [ADR 0111](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
  § What stays open (the invented member).

**Closed by:** ADR 0024 records no closing mechanism for the fork or the queued rotations; an invented ordinary member
  is closed by the join records (ADR 0111 decisions 12 and 15), an invented owner needs signed roles, not designed.

## Device linking: an attacker who reads the QR and runs the relay

Linking a device runs over a server relay. The QR code carries 32 random bytes the relay never receives; both
devices derive the relay's routing handle and a channel key from it, and both relayed messages carry a MAC under
that key. The trusted device seals only to an answer whose MAC verifies, and the new device opens only an envelope
whose MAC verifies, so the relay alone cannot link a device of its own.

**What remains:** an attacker who **both** reads the QR **and** runs the relay holds the channel key, and can again
grind the transcribed code or the compared number. A PAKE was considered and rejected: it would close the
sign-in-with-a-QR direction only, not the add-a-device direction, and it needs a construction the suite does not have
and no maintained library ships.

**Decided in:** [ADR 0059](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0059-device-linking.md) §11.

**Closed by:** nothing recorded.

## Item favorites: a count as honest as the operator

A member's marks on individual media items (the gallery's shared favorites) are sealed under a value key of their own
whose wrap the scope key holds, re-wrapped at every rotation like every scope-level value's, so a member removed by a
rotation reads no set written after it from a dump taken after it — only one whose value key they extracted before,
until that member writes it again. A tenant's routine removal does not rotate, and that member keeps the tenant key until an explicit
re-key. Beyond that:

- **The per-tile count is exactly as honest as the operator.** On its own the operator can withhold entries (down to
  zero), serve an earlier version of a member's set, keep serving a removed member's set, and serve stored sets again
  as extra entries. With any member, or a former one who still holds the current key, it can forge entries with any
  content.
- **Which entry is the caller's own** is proven by a self-tag only the caller's client computes, so another member's
  marks are never shown as the caller's and a toggle never writes another member's set into the caller's row. The
  operator can still serve the caller an older genuine version of their own set.
- **"Only mine" is a filter, not a promise.** Co-members' clients receive the per-member sets and could name who
  marked what; the UI shows only an aggregate.

None of this reaches content confidentiality. The private node pin (the sidebar Favorites) is a different object,
sealed under the member's Master Key.

**Decided in:** [ADR 0068](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0068-favorites-two-confidentiality-levels.md)
  §9 and § Consequences; [ADR 0115](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0115-a-scope-key-is-replaced-never-chained.md) decision 2 (the re-wrap).

**Closed by:** ADR 0068 §9 names the condition for re-opening the count's design: marks no longer sealed under the
  first epoch, which holds, and a freshness floor covering them, which is not built.

## Departed members and the links they could copy

A public share link is a bearer capability: `/s/{shareId}#k=K_share`, with the key in the fragment. Every member can
rebuild every link of a Memory from its recovery copy, so a member removed from a group or the tenant may hold every
link that existed on its Memories while they belonged. Using one needs no dump: against an honest server an open link
alone works, and a rotation re-wraps an open link to the Memory's new key rather than closing it
([ADR 0109](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0109-a-scope-rotation-re-keys-every-node-below-it.md) point 4).

What is built: closing a viewable link — a switch-off, a delete, its end date, a re-issue — re-keys every node below
its root ([ADR 0114](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0114-closing-a-link-re-keys-the-subtree-below-it.md)), and a **re-issue** (one request, one transaction: a new id and a fresh
`K_share` carrying the old settings, the old link closed) cuts a departed member off while the link lives on.
Re-activating a link a departed member may hold is refused (`reissue_required`); a removal turns off the removed
member's own links, and it — or any owner of the space later — can turn every link of the space off at once.

**What remains:**

- **The instant cut is honest-server only.** A malicious operator can keep serving a closed id, but once its subtree
  is re-keyed only what existed at the close.
- **Until a link is closed or re-issued, the departed member can keep using it**, with no dump.
- **With a dump**, they also read the share's later password, label or note edits, closed or not (the curator's
  words to that link's holders), and, while it is open, what is added below the link's root through its `K_share`,
  after a rotation too. A closed link keeps [the residuals of a close](#revocation-cannot-retract-what-was-already-read).
  Without a rotation (a tenant member's routine removal), they keep the tenant key, so the Memory's content stays
  readable to them with a dump whatever the link does.

**Decided in:** [ADR 0075](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0075-a-link-a-departed-member-could-copy-is-reissued.md),
  [ADR 0114](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0114-closing-a-link-re-keys-the-subtree-below-it.md).

**Closed by:** a close or a re-issue, against an honest server and, once the subtree is re-keyed, against a dump of
  what is added afterwards too. For a removed tenant member, who keeps the tenant key, the dump case is the class
  [ADR 0012](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0012-content-key-hierarchy.md) accepts until the next rotation.

## Guest names and an operator holding a link

No share link is served the name any guest gave; members read the names. But the names stay sealed as they are — a
contributor's under a field key derived from `K_node`, an uploader's from its own key, which a link opens through the Memory's drop key. So a
viewer or contributor link **plus a database dump** reads every guest's name; in practice, that is an operator
holding a link. Accepted: that link already decrypts every medium of the Memory, and a name is less than the photos
it is attached to. A key only members hold would close it and was rejected as more machinery than that difference is
worth.

**Decided in:** [ADR 0030](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0030-contributor-public-share.md)
  point 8.

**Closed by:** accepted; the one closing design (a name key only members hold) was rejected.

## A share password is access control, not cryptography

An optional share password gates **what the server serves**: a locked share returns only the challenge until the
guest presents a valid token. The content stays end-to-end encrypted under the unchanged `K_share` in the fragment,
and the server holds only a salt, a verifier and one sealed copy, never the raw password. It defends a leaked link
against an **honest** operator. A malicious operator can ignore the check and serve the ciphertext to a colluding
link holder, who decrypts with the fragment. Password strength is enforced only by the client, and an attacker with
the stored verifier can brute-force offline at one Argon2id per guess.

**Decided in:** [ADR 0037](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0037-password-protected-shares.md)
  § Operator-blindness & threat model.

**Closed by:** the [cryptographic share password](/veyq/security/roadmap/) on the Roadmap.

## Revocation cannot retract what was already read

Revocation has two layers. **Layer 1** is access control: the server stops honouring the membership or share and
serves nothing further — instant, but a defence only against an honest operator. **Layer 2** replaces the scope key:
a fresh one for the remaining members, every scope-level value key re-wrapped under it and every node re-keyed, so new content
is sealed out of the removed member's reach and their old key opens nothing in a dump taken after the rotation. Neither
can retract content a recipient already downloaded and decrypted; rotation protects only future content.

- **Layer 2 is a per-scope policy.** Groups rotate on removal; the **tenant** uses layer 1 alone by default and
  rotates only on an explicit re-key; a personal scope has one member. A revoked tenant member who both hoarded the
  key and ciphertext **and** colludes with a malicious operator keeps that narrow residual until an explicit re-key.
  A revoked or expired tenant invite is the same class: its link and code still open the tenant keys it was granted,
  useful only with a dump taken before the tenant next rotates (ADR 0111 § Consequences).
- **A rotation closes what is added afterwards, not everything.** It re-keys every live and trashed node of the
  scope ([ADR 0109](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0109-a-scope-rotation-re-keys-every-node-below-it.md)); a member's write into a node not yet re-keyed re-keys its path first, a guest's waits. What stays in the excluded party's reach:
  - what existed at the rotation, in a dump from before it;
  - a blob whose `K_blob` they extracted, in a later dump too: a re-key re-wraps keys and re-encrypts no content
    ([ADR 0115](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0115-a-scope-key-is-replaced-never-chained.md) § Consequences);
  - a scope-level value — a member's name, picture or join record, a verification, an item-mark set, a group's name or
    icon, a link's recovery copy — whose value key `K_v` they extracted, in a later dump until its author writes it
    again: the rotation re-wraps `K_v` and leaves the value;
  - every open link they could rebuild, until it is closed or re-issued: the re-key re-wraps it to the new key
    ([above](#departed-members-and-the-links-they-could-copy));
  - a drop box's guest identity from before: its `K_up` is re-sealed to the new drop key, not replaced (only the
    guest holds it), so whoever held the old drop key reads what that guest adds to their identity folder afterwards,
    until the uploader link is re-issued;
  - a queued rotation before an owner runs it (*waiting*, *stalled*).
- **A member a rotation could not grant at once reads nothing** of the scope until re-granted.
- **Without a rotation, the tenant key keeps reading.** A removal that does not rotate (the tenant's routine one)
  leaves the member the tenant key, so with a dump they read what is added in the tenant until an explicit re-key.
- **A closed link reads what existed at the close.** Closing a viewable link re-keys every node below its root, so
  with a dump it reads nothing added after ([ADR 0114](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0114-closing-a-link-re-keys-the-subtree-below-it.md)). What stays: a write landing between an end
  date and the server sweep that closes the link (every minute by default); the later uploads of an uploader link's
  guest, whose `K_up` the viewable link's holder opened through the old drop private key, until the uploader link is
  re-issued; a removed tenant member, who keeps the tenant key, so their links' close cuts only the other holders;
  and an active operator rolling a member's view of the subtree back
  ([Freshness](#freshness-only-where-a-device-keeps-a-mark)). A pause re-keys nothing, and neither does closing an
  uploader link, whose key opens only what its own guests uploaded.
- **Account-key rotation after a compromise is forward secrecy, not repair.** What was already read stays read.

**Decided in:** [security-model § Revocation](https://github.com/raddatzk/veyq/blob/main/docs/crosscutting/security-model.md#revocation--two-layers),
  ADR 0024,
  [ADR 0025](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0025-forward-secrecy-policy-per-scope.md),
  [ADR 0114](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0114-closing-a-link-re-keys-the-subtree-below-it.md), [ADR 0115](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0115-a-scope-key-is-replaced-never-chained.md).

**Closed by:** inherent; no scheme can retract what was seen.

## Metadata the server sees

The server never sees content, but it does see structure: ids, scope and membership, sizes, server timestamps to the
millisecond, the node `kind`, and per-parent blinded tokens. Several concessions are deliberate:
a member's per-tenant username and identity public key are enumerable by any fellow tenant member; the account email
is plaintext (it is the login identifier); range-streamed video playback shows the object store which chunks a
playing client fetches and when; a public share's guest learns the shared node's scope id, so two links into one
space are linkable by anyone holding both. The automatic breached-password check sends a 5-hex SHA-1 prefix to Have
I Been Pwned, and the browser's `Origin` header names the veyq instance to that service. Expenses' automatic day
exchange rates send the device's date to the rate provider (on a new instance Frankfurter), at most once per device
and day, with the same `Origin`, so that provider learns that this address writes a foreign-currency entry that day.

**Decided in:** [security-model § What is encrypted vs. server-visible](https://github.com/raddatzk/veyq/blob/main/docs/crosscutting/security-model.md#what-is-encrypted-vs-server-visible).

**In full:** [What the server sees](/veyq/security/what-the-server-sees/).

## Applications: entries are not bound to their neighbours

A seal binds an entry to its object, not to the entries beside it. For an append-only application (a ledger), the
server can therefore **drop or reorder** entries unless a sequence number or hash chain is bound into each entry's
associated data. This is the same limit as freshness. No built application is append-only today (Memories and Depot
are built; an append-only ledger application exists only as a concept), and each such application states the limit
in its concept doc.

**Decided in:** [security-model § Applications are compiled in](https://github.com/raddatzk/veyq/blob/main/docs/crosscutting/security-model.md#applications-are-compiled-in--never-loaded).

**Closed by:** per application, a sequence number or hash chain in each entry's associated data.

## Rate limiting is per instance

The public authentication endpoints are rate-limited per IP with a token bucket (`429 rate_limited`). It is
defence-in-depth against online brute force, credential stuffing and denial of service, and honestly limited: every
bucket is in memory and per instance, so replicas behind a load balancer count independently and a restart clears
every bucket. The key is the rightmost `X-Forwarded-For` entry that the trusted-proxies list does not name, which a
proxy the request passed wrote; a client's own entries to its left are never read. That holds only as far as the
network confines who reaches the application: on Kubernetes without an enforced NetworkPolicy any pod can write the
whole header and forge the key. A NAT'd source, carrier-grade NAT included, shares one bucket. It raises the cost of automated abuse; it does not make it impossible.
Offline brute force of a password is a separate property, answered cryptographically by the Secret Key.

**Decided in:** [cc.security.rate-limit § Known limits](https://github.com/raddatzk/veyq/blob/main/docs/crosscutting/cc.security.rate-limit.md#known-limits-honest-defense-in-depth--not-a-hard-guarantee).

**Closed by:** a distributed store, the deferred hardening named there. The forgeable key is not closed by a setting:
on Kubernetes it closes only with a NetworkPolicy the cluster enforces
([ADR 0094](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0094-a-clients-address-is-the-hop-the-network-vouches-for.md) decision 6).

## Assurance: one implementation, no external audit

- **One seal implementation.** The conformance vectors in
  [`contracts/crypto/vectors/v1.json`](https://github.com/raddatzk/veyq/blob/main/contracts/crypto/vectors/v1.json)
  define the suite's bytes, and
  [`packages/crypto/src/v1.ts`](https://github.com/raddatzk/veyq/blob/main/src/packages/crypto/src/v1.ts) is pinned to
  them by its conformance test. That TypeScript test is the only stack asserting the file's seals, so it is a
  single-implementation **regression pin** — it stops a refactor from silently moving the bytes — not a
  cross-implementation parity proof. The backend performs no content crypto; it asserts the vectors only for the
  Secret-Key proof statements and the change-email attestation, which both stacks build.
- **No external audit.** veyq's cryptography and protocols have not been reviewed by an external auditor.

**Decided in:** [ADR 0017](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0017-crypto-primitives-and-post-quantum-strategy.md)
  rule 4 and § Consequences.

**Closed by:** ADR 0017 says the cross-stack role of the vectors is dormant until a second independent
  implementation exists.

## Losing every way back is final

There is no key escrow: the property that stops the operator reading data stops it rescuing data. The ways back are
another enrolled device, the Emergency Kit with the password, or the Emergency Kit alone after a mailed recovery link.
Losing all of them is the documented, accepted loss. Losing the Emergency Kit also means losing the ability to rotate
account keys after a compromise.

**Decided in:** [security-model § Recovery](https://github.com/raddatzk/veyq/blob/main/docs/crosscutting/security-model.md#recovery--no-escrow),
  [ADR 0060](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0060-key-rotation-after-compromise.md)
  § Consequences.

**Closed by:** by design; escrow would break operator-blindness.
