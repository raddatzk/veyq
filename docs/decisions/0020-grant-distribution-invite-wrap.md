# ADR 0020 — Distributing a scope grant to another member (invite + wrap-to-foreign identity)

- Status: Accepted, and built as stated below. [ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
  decisions 1–5 (built 2026-10-28) replaced moves 3, 5 and 6 for new members: an invite is a placeholder member holding
  a grant to an owner-signed invite key, the invitee grants itself and writes an owner-rooted join record, a group add
  wraps directly, and the admission tag, the grant delivery to new members and *awaiting access* are gone. Those moves
  are written below as built, and two alternatives rejected below are now taken.
- Date: 2026-07-04

## Context

Every scope key veyq holds today is wrapped to the caller's **own** identity: the personal scope auto-birth,
the tenant scope's cold birth ([ADR 0006](0006-cold-tenant-scope-birth.md)), and the group scope's warm birth
all seal `K_SC` to the caller's own X-Wing identity key (`sealTo`, purpose `scope-grant`) and store the ciphertext as that member's own
`MembershipEntity.wrappedGrant`. There is **no path where member A wraps a scope key to member B**, and **no
endpoint that returns another member's identity public key** — pubkeys are only uploaded at registration, never
looked up.

That is exactly what makes a shared space shareable-in-name-only. [en.spaces.tenant-scope](../features/spaces/enablers/en.spaces.tenant-scope.md)
and [en.spaces.group-scope](../features/spaces/enablers/en.spaces.group-scope.md) both **open** whatever grant
the caller already holds and both explicitly defer *adding other members* as "the member-add slice"; until it
lands the tenant is **owner-reachable only** and every other member sees the Personal fallback
([ADR 0019](0019-token-less-tenant-home.md)). The
membership model already anticipates this: role gates management, the **grant** gates content, and a role
without a grant is a deliberate "hollow" membership ([ADR 0007](0007-membership-role-grant-relation.md)).

So the missing capability is **grant distribution**: giving a *second* member the key to a shared scope,
operator-blind. It reduces to two moves the codebase has never made — obtaining another member's identity
public key, and `asymWrap`-ing a scope key to it — plus one risk: the blind server mediates the pubkey, so a
malicious operator could substitute its own key (key-substitution / MITM) and silently join the scope
([security-model → key-directory trust](../crosscutting/security-model.md)).

This ADR pins the reusable primitive. The two shared scopes consume it: the **tenant** (this refinement's first
consumer, [uc.spaces.add-member](../features/spaces/use-cases/uc.spaces.add-member.md)) and **groups** later
(the ADR 0014 "G2" member-add). It does **not** decide group governance (that stays [ADR 0014](0014-group-scopes-and-governance.md)).

## Decision

**A member gains access to a shared scope when an existing key-holder wraps that scope's key to the member's
identity public key. The server only ever relays pubkeys and stores opaque grant ciphertext — it never holds a
key, wraps, or unwraps.** Concretely:

1. **Wrap-to-foreign identity — the one new crypto move.** The granter seals `K_SC` to the target's X-Wing
   identity key under the `scope-grant` purpose — the *same* hybrid KEM, purpose, and wire shape as a
   self-grant, only the recipient pubkey is someone else's. The purpose is bound to the scope and epoch the grant
   belongs to ([ADR 0072](0072-a-seal-binds-its-object.md)), and the grant carries its granter's signature
   ([ADR 0028](0028-identity-authenticity-tofu-verification.md) Decision 6). The result is posted as the target's
   `MembershipEntity.wrappedGrant`; the target opens it with their **own** identity private on next entry
   (the existing self-unwrap path). No new primitive. (On scope-key rotation the granter wraps the scope's
   **current epoch key** rather than a single `K_SC` — the same call and purpose;
   [ADR 0024](0024-scope-key-rotation.md).)

2. **A key directory — the one new read surface.** An authorized granter can list a scope's memberships
   together with each member's **identity public key** (and, in the tenant scope, its sealed join record, ADR 0111
   decision 3). The identity pubkey is operational metadata the server already stores; exposing it to a granter
   who may wrap to it does not widen the operator's view (it never yields a key). This is a directory scoped to
   *a scope's own members*, not a global user-search.

3. **One human action, and the newcomer holds the key from its accept on** (ADR 0111 decisions 1–4, built
   2026-10-28). The newcomer has no identity until they join, so the owner wraps to a placeholder instead:
   - **A tenant member invite is a placeholder member.** An OWNER's client mints an **invite key** from a seed, signs
     it with the owner's identity, wraps the tenant's **current epoch** to it, and seals the seed with the owner's
     fingerprint into the mailed link under a code (or password) the owner passes on outside veyq. The server stores
     the invite's public key, the signature and the grant, as it stores a membership's.
   - **The invitee grants itself.** It **cold-registers** (or signs in with its Secret Key), opens the link with the
     code, checks the invite key and the owner's signature against the fingerprint in the seal, opens the grant, and
     wraps the epoch to its **own** identity (move 1, the granter being the newcomer). It is a member with the key at
     once; no key-holder needs to be online.
   - **A group add wraps directly** — the target already has an account, so the group owner's client wraps the
     group's current epoch to the target's directory key after the anchor check (ADR 0028 Decision 2), and the membership (a consent proposal
     too) holds that grant from the add on.
   - **Delivery stays only for a member who is behind.** A **`grant-delivery` job on the operator-blind
     background-task mechanism** ([ADR 0021](0021-operator-blind-background-tasks.md)) — any key-holder's device,
     first-online-wins, idempotent — brings current a membership whose grant is older than the scope's epoch: one a
     rotation skipped ([ADR 0024](0024-scope-key-rotation.md)), or a newcomer whose invite a rotation declared
     pending. Existing grants are untouched — this only **adds**.

4. **Authenticity: this flow wraps to the server-returned key; the trust decision is layered on by
   [ADR 0028](0028-identity-authenticity-tofu-verification.md), not here.** A malicious operator could substitute
   its own key and read future wraps; in the default **self-hosted** deployment the operator is the user, so the
   gap is moot ([security-model](../crosscutting/security-model.md)). The grant-distribution *mechanism* is
   unchanged by authenticity — the granter still `asymWrap`s to a directory pubkey — but **which** key it trusts is
   now governed by **TOFU pinning + safety-number verification** (ADR 0028): a first-seen key is pinned at its
   key-chain head and used, a key the chain reaches from the pinned head is accepted, one it does not reach is never
   wrapped to (a rotation declares that member pending, a worker declines), and the **fingerprint / safety-number** (a near-free digest of the
   pubkey) is compared out-of-band to elevate to *verified*. Key Transparency remains the end-state. This flow
   inherits that model without reshaping.

5. **Who may invite is the owner; the key comes from the invite.** Minting a member invite stays **owner-only** in
   v1 — server policy, enforced by an honest operator. The key-holder half is cryptographic: only a client holding the
   tenant's current epoch key can grant the invite key, and only the owner's identity can sign it so that the
   invitee's check passes. Every member reads the open invites' keys (`listOpenInvites`, never an email) so that any
   member's rotation carries them: **every rotation of the tenant re-grants each open invite or declares it pending**,
   and the server refuses one that leaves an invite out (`409 rotation_incomplete`). Group member-add follows the same
   split ([ADR 0014](0014-group-scopes-and-governance.md) — the GroupOwner adds and wraps), over an *existing* tenant
   member, so it needs no invite. Delegated *inviters* are deferred.

6. **The admission tag is gone** ([ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
   decision 5, built 2026-10-28). It was an HMAC under a key derived from the scope's current epoch key, over the
   scope, the epoch and an admission id, that every client checked before wrapping to anyone else: it stopped a
   session without an unlocked vault, and the operator, from making a membership any honest device wraps to. It bound
   no user and no key, so the operator could still redeem a genuine admission with a key of its own. Its place in the
   invite is the owner-signed invite key (move 3); its place in the members' wraps is the owner-rooted join record
   each newcomer writes, which no wrap reads yet (ADR 0111 decisions 12 and 15) — so today every wrap to another member
   takes the anchor check against the wrapper's own pin only (§ Consequences).

## Consequences

- **The shared space becomes shareable.** The tenant goes from owner-only to genuinely multi-member; groups
  inherit the same primitive. This is the keystone both space enablers were waiting on.
- **First foreign wrap + first directory.** Two capabilities the codebase never had. Both stay operator-blind:
  the directory returns only already-stored operational metadata (pubkeys), and the wrap happens client-side —
  the server relays ciphertext.
- **One human action, and no waiting.** The owner decides once (the invite, from an unlocked vault — the grant needs
  the epoch key and the signature the owner's identity); the newcomer holds the key from its accept on, with no other
  member online. There is no *awaiting access* state for a new member. What remains is a member whose grant is
  **behind** — a rotation skipped them, or their invite was declared pending — until a key-holder delivers.
- **The active-operator gap (cost, stated honestly).**
  - **The operator can invent a member.** Without the tag, a membership row the server writes is wrapped to by a
    rotation, a delivery or a group add whose key-holder never pinned that account — TOFU's first use. A departing
    member's help is no longer needed for it. An honest server keeps the session half of the tag's job: it verifies
    the invite key's owner signature under the caller's identity key before it mints, so an owner's session without
    the vault — a phished password — mints no invite (`TenantHandler.signedByCaller`). Closing it needs the join record read as an anchor (ADR 0111 decisions
    12 and 15, with decision 18 for members from before it).
  - **Redeeming a genuine invite with a key of its own** now needs the code: the operator mails the link and holds its
    sealed part, so it can guess the code offline, against 50 bits behind Argon2id (or the strength of a password the
    owner chose). The newcomer's view of its inviting owner is anchored by the fingerprint inside the seal.

  The human checkpoint on a *changed* key is ADR 0028's (TOFU pinning + safety-number verification). The
  first-online-wins fan-out of a delivery means one pinning key-holder's refusal is a per-key-holder signal, not a
  global veto (ADR 0028 § Consequences). Key Transparency remains the end-state. Not hidden.
- **Removal and rotation** are [ADR 0024](0024-scope-key-rotation.md)'s. This record only adds a grant; what a tenant
  rotation must carry forward besides its members is every open invite (move 5).
- **Rejected — server-mediated key handoff / escrow.** Letting the server transport or re-wrap the scope key
  would break operator-blindness outright. The key never leaves the client key-holders.
- **Rejected — invite carries the newcomer's pubkey so the owner wraps at invite time.** Impossible: a new
  person has no identity until they register, so there is nothing to wrap to at invite time. The two-phase
  handshake is not a choice, it is forced by cold-birth.
- **Taken (was rejected) — the invite carries the wrapped key, so the newcomer self-serves.** Rejected here because
  it makes the emailed link key-bearing: whoever intercepts it gains scope content, not just the ability to join.
  [ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
  decision 1 took it for two reasons: the link already admitted whoever redeemed it, and a key-holder's device then
  wrapped to them with no further human decision, so it was in effect the key already; and the link is now **always
  sealed** under a code or password the owner passes on outside veyq, which the operator — the one party that holds
  every mailed link, mail being the only channel ([ADR 0055](0055-mail-is-a-precondition-not-a-capability.md)) — never
  sees. What it costs is a link that keeps its reach after a revoke (ADR 0111 § Consequences).
- **Taken (was rejected) — the owner signs the admission with their identity key.** Rejected here because a worker
  learns *who the owner is* from the server, so an operator could name an identity it generated as owner. ADR 0111
  decision 3 took it in another form: the newcomer learns the owner's fingerprint from the link, inside its seal, not
  from the server. The further point made here — a removed owner's old signatures stay valid without signed removals,
  a membership protocol — stays open (ADR 0111 § What stays open).
- **Rejected — hard-gate the grant behind manual fingerprint verification in v1.** Stronger than the
  security-model asks for today and heavy friction for a self-hosted family instance; deferred to the hardening
  slice, with the seam kept open (move 4).
- **Rejected — a global user-search directory.** Returning any user's pubkey by username/email across the
  instance is a broader surface (and enumeration risk) than the feature needs; the directory is scoped to a
  scope's own membership. A username-reachable variant is the group case's concern under ADR 0014, still scoped
  to the tenant.
