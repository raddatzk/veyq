# ADR 0020 — Distributing a scope grant to another member (invite + wrap-to-foreign identity)

- Status: Accepted
- Date: 2026-07-04

## Context

Every scope key veyq holds today is wrapped to the caller's **own** identity: the personal scope auto-birth,
the tenant scope's cold birth ([ADR 0006](0006-cold-tenant-scope-birth.md)), and the group scope's warm birth
all call `asymWrap(hybridPublic(self), K_SC, "scope-grant")` and store the ciphertext as that member's own
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

1. **Wrap-to-foreign identity — the one new crypto move.** The granter calls
   `asymWrap(hybridPublic(target), K_SC, "scope-grant")` — the *same* hybrid KEM, context, and wire shape as a
   self-grant, only the recipient pubkey is someone else's. The result is posted as the target's
   `MembershipEntity.wrappedGrant`; the target opens it with their **own** identity private on next entry
   (the existing self-unwrap path, unchanged). No new primitive, no new context string. (On scope-key rotation
   the granter wraps the scope's **current epoch key** rather than a single `K_SC` — the same call and context;
   [ADR 0024](0024-scope-key-rotation.md).)

2. **A key directory — the one new read surface.** An authorized granter can list a scope's memberships
   together with each member's **identity public key** and whether they already hold a grant (granted vs.
   *pending*). The identity pubkey is operational metadata the server already stores; exposing it to a granter
   who may wrap to it does not widen the operator's view (it never yields a key). This is a directory scoped to
   *a scope's own members*, not a global user-search.

3. **A two-phase handshake, but only one human action** — the newcomer has no identity until they join, and
   only a key-holder (not the server) can wrap, so there are two *moments*; there need not be two *decisions*:
   - **(a) Onboard.** An OWNER mints a **member invite** (email + `role = MEMBER`), reusing the tenant-invite
     machinery that today mints only
     the owner. The invitee **cold-registers** — their identity is born on-device and its public bundle is
     published — becoming a tenant **member with no grant**. They land in the **Personal fallback** (cr-0075)
     until step (b).
   - **(b) Deliver — device-automatic, first-online-wins.** Any existing key-holder's client — **every** tenant
     member already holds `K_SC`, not just the inviter — **eagerly** wraps `K_SC` to each pending member the
     moment their pubkey is visible and posts the grant (move 1); the **first** online key-holder wins, the rest
     no-op. There is **no second human action**: the human consented once at the invite, and under *Plain* the
     wrap carries no judgement to make (move 4), so the device completes it silently. The member reaches the
     shared space on their next entry. Existing grants are untouched — this only **adds**.

   The two moments are crypto-forced; the second human confirmation is not — it would only regain a purpose once
   fingerprint verification is switched on (move 4). The "role, no grant yet" interval is the hollow membership
   ADR 0007 already blesses and cr-0075 already renders — not a new state, just the first flow that produces it
   on purpose. **Delivery is realized as a `grant-delivery` job on the operator-blind background-task mechanism**
   ([ADR 0021](0021-operator-blind-background-tasks.md)) — this use-case is that mechanism's first, trivially-blind
   consumer (a universal tag, no lease, idempotent). The "pending = grant-less membership, reconciled by any
   key-holder" discovery is that mechanism's pull/reconcile. **Scope-key rotation re-grants existing members the
   same way** — only the reconcile predicate widens from *grant-less* to *grant-less **or** stale-epoch*
   ([ADR 0024](0024-scope-key-rotation.md) — a removed member's departure mints a new epoch the remaining members
   are re-granted).

4. **Authenticity: this flow wraps to the server-returned key; the trust decision is layered on by
   [ADR 0028](0028-identity-authenticity-tofu-verification.md), not here.** A malicious operator could substitute
   its own key and read future wraps; in the default **self-hosted** deployment the operator is the user, so the
   gap is moot ([security-model](../crosscutting/security-model.md)). The grant-distribution *mechanism* is
   unchanged by authenticity — the granter still `asymWrap`s to a directory pubkey — but **which** key it trusts is
   now governed by **TOFU pinning + safety-number verification** (ADR 0028): a first-seen key is pinned + used, a
   changed key is a UI notice / a worker-block, and the **fingerprint / safety-number** (a near-free digest of the
   pubkey) is compared out-of-band to elevate to *verified*. Key Transparency remains the end-state. This flow
   inherits that model without reshaping.

5. **Admission and delivery are separate concerns.** *Admission* — who may invite a new member at all — is the
   real policy gate and stays **owner-only** in v1: only the tenant OWNER mints invites. *Delivery* — producing
   the wrap for an already-admitted, onboarded member — needs **no special authority**: every member holds
   `K_SC`, so **any** key-holder's device may (and automatically does) wrap it (move 3b). A non-inviting member
   cannot decide *who* joins (they cannot mint invites); they only help deliver a key to someone the owner
   already admitted. Delegated *inviters* are deferred. Group member-add reuses this split exactly
   ([ADR 0014](0014-group-scopes-and-governance.md) — the GroupOwner admits, any group member delivers), over an
   *existing* tenant member (so the group case skips the email-onboarding half of step 3 and is pure invite-wrap).

## Consequences

- **The shared space becomes shareable.** The tenant goes from owner-only to genuinely multi-member; groups
  inherit the same primitive. This is the keystone both space enablers were waiting on.
- **First foreign wrap + first directory.** Two capabilities the codebase never had. Both stay operator-blind:
  the directory returns only already-stored operational metadata (pubkeys), and the wrap happens client-side —
  the server relays ciphertext.
- **One human action; delivery is automatic and un-bottlenecked.** The owner decides once (the invite);
  thereafter **any** member's device wraps the key automatically, first-online-wins — so access lands as soon as
  *any* key-holder is next online, which in an active shared space is near-immediate. The owner is not a
  bottleneck, and no one is asked to confirm the same person twice.
- **A short "awaiting access" window remains (cost, inherent to E2EE).** A newly-onboarded member is grantless
  (Personal fallback) until *some* key-holder is online to wrap — the server cannot wrap on their behalf without
  holding the key. Surfaced as "awaiting access" so it reads as a step, not a bug. It is bounded by "any member
  online," not "the owner online."
- **The active-operator gap is real but bounded (cost, stated honestly).** Trusting the server-returned pubkey
  means a malicious operator *can* key-substitute. Broadening *delivery* from owner-only to any key-holder does
  **not** widen this: it already only took one honest device (the owner's) to auto-wrap a server-injected member;
  more eligible wrappers is a negligible amplification, and moot for an honest/self-hosted operator. The hardening
  that reintroduces a human checkpoint only on a *changed* key is now defined by
  [ADR 0028](0028-identity-authenticity-tofu-verification.md) (TOFU pinning + safety-number verification); note
  the first-online-wins fan-out means one pinning key-holder's refusal is a per-device signal, not a global veto
  (ADR 0028 § Consequences). Key Transparency remains the end-state. Not hidden.
- **Revocation & rotation are out of scope.** Removing a member, rotating `K_SC` so a removed member can't read
  future content, and re-inviting after removal are a later slice (as [ADR 0014](0014-group-scopes-and-governance.md)
  already defers for groups). Adding a grant is strictly additive here.
- **Rejected — server-mediated key handoff / escrow.** Letting the server transport or re-wrap the scope key
  would break operator-blindness outright. The key never leaves the client key-holders.
- **Rejected — invite carries the newcomer's pubkey so the owner wraps at invite time.** Impossible: a new
  person has no identity until they register, so there is nothing to wrap to at invite time. The two-phase
  handshake is not a choice, it is forced by cold-birth.
- **Rejected — the invite carries the *wrapped key* so the newcomer self-serves (no key-holder needed later).**
  This would remove even the "some key-holder online" requirement: the owner wraps `K_SC` to a secret derived
  from the invite at invite time, and the joiner unwraps it and re-wraps to their own identity. But it makes the
  **invite link itself key-bearing** — anyone who intercepts the (emailed) invite gains scope content, not just
  the ability to join. That downgrades the invite channel from "admission token" to "the key," which an
  operator-blind product must not do over an insecure channel. Deferred unless paired with an out-of-band second
  factor; the automatic first-online-wins delivery (move 3b) covers the latency concern without it.
- **Rejected — hard-gate the grant behind manual fingerprint verification in v1.** Stronger than the
  security-model asks for today and heavy friction for a self-hosted family instance; deferred to the hardening
  slice, with the seam kept open (move 4).
- **Rejected — a global user-search directory.** Returning any user's pubkey by username/email across the
  instance is a broader surface (and enumeration risk) than the feature needs; the directory is scoped to a
  scope's own membership. A username-reachable variant is the group case's concern under ADR 0014, still scoped
  to the tenant.
