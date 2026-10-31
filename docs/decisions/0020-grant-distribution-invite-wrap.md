# ADR 0020 — Distributing a scope grant to another member (invite + wrap-to-foreign identity)

- Status: Accepted
- Built: yes
- Date: 2026-07-04
- Model: [keys-grants-and-trust](../crosscutting/keys-grants-and-trust.md)
- Replaced in part: move 3, move 5, move 6 and two rejected alternatives of § Consequences by ADR 0111 (built)

## Context

Every scope key veyq held was wrapped to the caller's **own** identity: the personal scope auto-birth, the tenant
scope's cold birth ([ADR 0006](0006-cold-tenant-scope-birth.md)), and the group scope's warm birth all sealed `K_SC`
to the caller's own X-Wing identity key under the `scope-grant` purpose and stored it as that member's own grant. There
was **no path where member A wraps a scope key to member B**, and **no endpoint that returned another member's
identity public key** — pubkeys were only uploaded at registration, never looked up.

That made a shared space shareable-in-name-only. The tenant and group scope enablers opened whatever grant the caller
already held and deferred *adding other members*, so the tenant was **owner-reachable only** and every other member
saw the Personal fallback ([ADR 0019](0019-token-less-tenant-home.md)). The membership model already anticipated
this: role gates management, the **grant** gates content, and a role without a grant is a deliberate "hollow"
membership ([ADR 0007](0007-membership-role-grant-relation.md)).

So the missing capability was **grant distribution**: giving a *second* member the key to a shared scope,
operator-blind. It reduces to two moves the codebase had never made — obtaining another member's identity
public key, and `asymWrap`-ing a scope key to it — plus one risk: the blind server mediates the pubkey, so a
malicious operator could substitute its own key (key-substitution / MITM) and silently join the scope
([security-model → key-directory trust](../crosscutting/security-model.md)).

This ADR pins the reusable primitive. The two shared scopes consume it: the **tenant**
([uc.spaces.add-member](../features/spaces/use-cases/uc.spaces.add-member.md)) and **groups** (the member-add of
[ADR 0014](0014-group-scopes-and-governance.md)). It does **not** decide group governance (that stays ADR 0014).

## Decision

**A member gains access to a shared scope when an existing key-holder wraps that scope's key to the member's
identity public key. The server only ever relays pubkeys and stores opaque grant ciphertext — it never holds a
key, wraps, or unwraps.** Concretely:

1. **Wrap-to-foreign identity — the one new crypto move.** The granter seals `K_SC` to the target's X-Wing
   identity key under the `scope-grant` purpose — the *same* hybrid KEM, purpose, and wire shape as a
   self-grant, only the recipient pubkey is someone else's. The purpose is bound to the scope and epoch the grant
   belongs to ([ADR 0072](0072-a-seal-binds-its-object.md)), and the grant carries its granter's signature
   ([ADR 0028](0028-identity-authenticity-tofu-verification.md) Decision 6). The result is stored as the target's
   membership grant, which the target opens with their **own** identity private key, as any self-grant. No new
   primitive. (On scope-key rotation the granter wraps the scope's **current epoch key** rather than a single `K_SC` —
   the same call and purpose; [ADR 0024](0024-scope-key-rotation.md).)

2. **A key directory — the one new read surface.** An authorized granter can list a scope's memberships
   together with each member's **identity public key** (with its key-chain head and, in the tenant scope, its sealed
   join record — ADR 0111 decisions 3 and 7). The identity pubkey is operational metadata the server already stores;
   exposing it to a granter who may wrap to it does not widen the operator's view (it never yields a key). This is a
   directory scoped to *a scope's own members*, not a global user-search.

3. **One human action, and the newcomer holds the key from its accept on.** The newcomer has no identity until they
   join, so the owner wraps to a placeholder instead: an **invite key** the owner signed, holding the tenant's current
   epoch like a member; the invitee **grants itself** that epoch (move 1, the granter being the newcomer) and writes a
   join record; a **group add** wraps directly to the target's anchored key; and a **`grant-delivery` job** on the
   operator-blind background-task mechanism ([ADR 0021](0021-operator-blind-background-tasks.md)) stays only for a
   member who is **behind** — how each works is [ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
   decisions 1–4 and 9, the current picture
   [keys-grants-and-trust § Grants](../crosscutting/keys-grants-and-trust.md#grants-who-wraps-a-scope-key-to-whom).
   The owner decides once (the invite, from an unlocked vault — the grant needs the epoch key, the signature the
   owner's identity), and no other member needs to be online: there is no *awaiting access* state for a new member,
   only a member whose grant is behind until a key-holder delivers. Existing grants are untouched — this only **adds**.

4. **Authenticity: this flow wraps to the server-returned key; which key it trusts is
   [ADR 0028](0028-identity-authenticity-tofu-verification.md)'s.** A malicious operator could substitute its own key
   and read future wraps; in the default **self-hosted** deployment the operator is the user, so the gap is moot
   ([security-model](../crosscutting/security-model.md)). The mechanism is unchanged by authenticity — the granter
   still `asymWrap`s to a directory key — and a key ADR 0028's anchor check refuses is never wrapped to.

5. **Who may invite is the owner; the key comes from the invite.** Minting a member invite stays **owner-only** in
   v1 — server policy, enforced by an honest operator. The key-holder half is cryptographic: only a client holding the
   tenant's current epoch key can grant the invite key, and only the owner's identity can sign it so that the
   invitee's check passes. Because an open invite holds a grant, every tenant rotation carries it (ADR 0111
   decision 2). Group member-add follows the same split ([ADR 0014](0014-group-scopes-and-governance.md) — the
   GroupOwner adds and wraps), over an *existing* tenant member, so it needs no invite. Delegated *inviters* are
   deferred.

6. **The admission tag** — replaced by the owner-signed invite key and join record of
   [ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md) decision 5.

## Consequences

- **The shared space becomes shareable.** The tenant goes from owner-only to genuinely multi-member; groups
  inherit the same primitive. This is the keystone both space enablers were waiting on.
- **First foreign wrap + first directory.** Two capabilities the codebase never had. Both stay operator-blind:
  the directory returns only already-stored operational metadata (pubkeys), and the wrap happens client-side —
  the server relays ciphertext.
- **The active-operator gap (cost, stated honestly).**
  - **The operator can invent a member only as an owner.** A membership row the server writes for a non-owner has
    no join record an owner signed, so every member's client treats it as an anomaly and never wraps to it
    (ADR 0028 Decision 2). Who is an owner is the server's word, and a tenant's first owner is trusted on first use
    ([ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
    § What stays open). An owner's session without the vault — a phished password — mints no invite (ADR 0111
    decision 1).
  - **Redeeming a genuine invite with a key of its own** needs the code, which the operator, holding the link's sealed
    part, can guess offline (ADR 0111 decision 19 and § Consequences).

  Judging a *changed* key is ADR 0028's, and one key-holder's refusal of a delivery is a per-key-holder signal, not a
  global veto (ADR 0028 § Consequences). Key Transparency remains the end-state. Not hidden.
- **Removal and rotation** are [ADR 0024](0024-scope-key-rotation.md)'s. This record only adds a grant; what a tenant
  rotation must carry forward besides its members is every open invite (move 5).
- **Rejected — server-mediated key handoff / escrow.** Letting the server transport or re-wrap the scope key
  would break operator-blindness outright. The key never leaves the client key-holders.
- **Rejected — invite carries the newcomer's pubkey so the owner wraps at invite time.** Impossible: a new
  person has no identity until they register, so there is nothing to wrap to at invite time. The two-phase
  handshake is not a choice, it is forced by cold-birth.
- **The invite carrying the wrapped key, and the owner signing the admission** are decided by
  [ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
  decisions 1 and 3, whose § Alternatives rejected weighs them. A removed owner's old signatures staying valid without
  signed removals — a membership protocol — is ADR 0111 § What stays open.
- **Rejected — hard-gate the grant behind manual fingerprint verification in v1.** Stronger than the
  security-model asks for today and heavy friction for a self-hosted family instance. Its opt-in form is ADR 0028
  Decision 4, decided in principle and not built
  ([uc.spaces.require-verified-identities](../features/spaces/use-cases/uc.spaces.require-verified-identities.md)),
  with the seam kept open (move 4).
- **Rejected — a global user-search directory.** Returning any user's pubkey by username/email across the
  instance is a broader surface (and enumeration risk) than the feature needs; the directory is scoped to a
  scope's own membership. A username-reachable variant is the group case's concern under ADR 0014, still scoped
  to the tenant.
