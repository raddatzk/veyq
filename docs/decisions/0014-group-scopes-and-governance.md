# ADR 0014 — Group scopes & governance: shared scopes, private even from the tenant owner

- Status: Accepted
- Built: yes
- Date: 2026-06-28
- Model: [scopes-and-tenancy](../crosscutting/scopes-and-tenancy.md)
- Replaced in part: § 2, § 5 by ADR 0111 (built); § 3 by ADR 0027 (built); § 7 by ADR 0068 (built)

## Context

The [spaces feature](../features/spaces/_index.md) always planned **groups** ("create / join a group"). The
foundation is in place: scope identity (opaque `scopeId` + `kind` TENANT/GROUP/PERSONAL,
[ADR 0002](0002-personal-scope-per-tenant.md) + [en.spaces.personal-scope](../features/spaces/enablers/en.spaces.personal-scope.md)),
membership = role + grant ([ADR 0007](0007-membership-role-grant-relation.md)), the hybrid-KEM grant
([ADR 0017](0017-crypto-primitives-and-post-quantum-strategy.md)), and the content-key hierarchy
([ADR 0012](0012-content-key-hierarchy.md), where a scope's `K_SC` is already "one key, many wraps"). A group
is therefore **mostly a multi-member scope** — little new crypto. What is *not* yet settled, and what this ADR
nails, is the **governance**: who may do what, how members are added operator-blind, the group lifecycle, and
— the question that drove the refinement — **what relationship, if any, a tenant owner has to groups**.

Considered and rejected during refinement:

- **Tenant owner can see all groups in the tenant (read-only oversight).** Rejected. Visibility without any
  capability is **surveillance, not governance** — and group existence + membership are *already* server-side
  metadata the invariant sanctions (the server must hold scope + membership rows to route grants), which on a
  **self-hosted** instance the tenant owner — frequently also the operator — can read at the DB level anyway.
  A product feature would add UI + a "the admin watches your groups" signal for **zero new power**, while the
  real guarantee (names + content sealed) holds regardless. Dropped entirely.
- **Tenant owner can force-delete a group, or add themselves to one.** Rejected — both break the core promise
  that a group is confidential *even from a privileged tenant role*. The only legitimate need behind them —
  **orphaned groups** when an owner leaves the tenant — is met by an automatic rule, not an admin power (below).
- **Plaintext group name stored server-side.** Rejected — a leak; the name is sealed like any node name.
- **Email-invite outsiders as the first way to add a member.** Deferred (not rejected) — start with
  **username search of existing tenant members** (their identity public key already exists); email-invite
  onboarding is a later, heavier flow.
- **Server-computed group navigation / a server-side group slug.** Rejected — like the personal scope, groups
  are addressed and navigated **client-side**: the `@group` URL fragment resolves against the member's own
  decrypted group list ([ADR 0015](0015-operator-blind-readable-urls.md)).

## Decision

**A group is a `kind=GROUP` scope, born warm by an existing tenant member, whose members each hold a grant —
and which is confidential even from the tenant owner.**

1. **Birth (warm, client-driven).** An existing, unlocked tenant member (the **GroupOwner**) mints it like a
   personal scope ([en.spaces.personal-scope](../features/spaces/enablers/en.spaces.personal-scope.md)) but
   **named + multi-member-capable**: generate a random `K_SC`, **seal the group name** under the name key derived
   from it (§ 3), and
   `asymWrap` `K_SC` to their **own** identity public key (the self-grant). `POST` the ciphertext name + grant
   under a scope id the client minted ([ADR 0072](0072-a-seal-binds-its-object.md));
   the server records the `GROUP` scope under it + the creator's `role=OWNER` membership. No new crypto — the KEM grant of
   [ADR 0017](0017-crypto-primitives-and-post-quantum-strategy.md), the content keys of [ADR 0012](0012-content-key-hierarchy.md).

2. **Key distribution = one grant per member.** A member holds the group iff a membership row carries `K_SC`
   `asymWrap`'d to *their* identity public key. Adding a member is exactly: the adding owner's client wraps the
   group's current epoch to the target's key and `POST`s that grant with the add
   ([ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
   decision 4). The server never holds `K_SC`.

3. **The name is sealed under a key derived from `K_SC`** — the name key `K_name` of the epoch the name was sealed
   at ([ADR 0027](0027-group-invite-name-shared-key.md)). Only members, and an invitee handed that one key, decrypt
   it. The server — and anyone with server/DB access,
   including a self-hosting tenant owner — sees an **unnamed** group: opaque `scopeId`, `kind`, members. This is
   what makes a group **private even from the tenant owner**.

4. **Authority model.**

   | Role | May |
   |---|---|
   | **GroupOwner** (`OWNER` on a GROUP scope) | create the group · **add members via username search** · **remove a member, with a rotation** · **promote a member to co-owner / demote a co-owner** · rename it · switch off every link in it · **delete the group with a grace period**, and restore it |
   | **Member** (any role with a grant) | read + write the group's content (holds `K_SC`) |
   | **TenantOwner** | **nothing** with respect to groups — groups are **not surfaced** to them, and they cannot delete a group or add themselves |

   Only two roles — **OWNER / MEMBER** (no `ADMIN`; an admin-over-groups would be surveillance for zero power,
   above). A group follows the cross-cutting ownership rule ([ADR 0007](0007-membership-role-grant-relation.md)):
   **multiple owners allowed, at least one required** — the last owner cannot be removed or demoted (the
   last-owner guard); a sole owner leaving the tenant triggers the orphan rule (§6).

5. **Adding a member = username search + consent.** The GroupOwner searches by **username** — a **per-tenant,
   unique-within-tenant** handle ([ADR 0023](0023-per-tenant-member-identity.md)), not email; the server resolves
   it to the target's **identity public key**.
   - **The resolve is owner-gated, but that is not an enumeration barrier.** Any tenant member can create a group
     and become its owner in O(1), so the username → identity-public-key → consent-flag resolve is effectively
     available to *every* tenant member. This is deliberate and within the metadata model (per-tenant usernames +
     identity pubkeys are tenant-visible, [ADR 0023](0023-per-tenant-member-identity.md); the consent flag is the
     one honest low-sensitivity exception). The owner-gate is convenience, not confidentiality — code and contract
     must not present it as the latter.
   - **Default = direct add.** The owner picks the member → the owner's client wraps the group's current epoch to
     the target's key after the anchor check, and the server records the GROUP membership holding that grant, refusing
     one at a stale epoch or naming another key ([ADR 0020](0020-grant-distribution-invite-wrap.md) move 3). They
     simply find themselves in the group.
   - **Optional consent (a per-user "ask before adding me" toggle) makes the add a proposal.** A non-auto-join
     member is *proposed*, held as a **soft** membership that **holds the grant** from the add on, exactly as a direct
     add does; the server serves the group to the invitee only after **Accept**, and consenting changes nothing about
     keys. Until then the proposal is no member to any role decision: it cannot be made an owner (`404
     member_not_found`) and counts as no owner to the last-owner guard ([ADR 0007](0007-membership-role-grant-relation.md)). Because the target's key could open the grant from the add on, a **Decline** — or the owner rescinding the
     proposal — queues the group's rotation, as a removal does (ADR 0111 decision 4).
     To let them decide informed, the owner's device hands the invitee the group's **derived name-key `K_name`**
     (wrapped to their identity, `asymWrap(inviteePub, K_name, "group-name-key")`) so the invitee opens the scope's
     **canonical** sealed name — the same ciphertext the members read — and **cannot** be shown a divergent,
     owner-typed name; the derivation and the rejected alternatives are [ADR 0027](0027-group-invite-name-shared-key.md)'s. The invitee sees the (group-consistent) name +
     who added them.
   - **Threat:** a malicious operator could return a public key it controls (MITM) and read the group.
     **Defense:** the **safety-number / fingerprint** of that identity. The verification stance is settled in
     [ADR 0028](0028-identity-authenticity-tofu-verification.md): **TOFU-pin on first use**, a **change** is a UI
     notice (accept → re-pin) or a **hard block for the human-less grant-delivery worker**, and out-of-band
     **safety-number verification** (QR compare) elevates *pinned* → *verified*; a **hard gate** before the first
     grant is available as an **opt-in strict mode**. The pin store is client-side + sealed (operator-blind).

6. **Lifecycle** ([uc.spaces.delete-group](../features/spaces/use-cases/uc.spaces.delete-group.md)):
   - **Owner delete = soft + grace.** Any GroupOwner deletes; the group enters **pending-deletion** (`deletedAt`
     marker + a `purgeAfter` date) and is hard-purged by a scheduled job after a **30-day grace period**. **During
     grace the group stays
     viewable + downloadable but read-only, clearly marked as deleting** (members can see their pictures one last
     time); grants/memberships are untouched, so **any owner can restore** in-window (trivial: clear the marker).
     The **hard-purge erases content + blob objects** from the store (reusing the blob-aware node-subtree delete),
     not just DB rows — the erasure the [security-model](../crosscutting/security-model.md) mandates. Purge is a
     **server job** (operator-blind-safe: it deletes only ciphertext + opaque rows + blobs, never a key/name),
     unlike removal's key rotation.
   - **Orphans = an automatic rule, not an admin power.** When a user is removed from a tenant, a group they
     **solely** own is **transferred** — ownership passes automatically to the **oldest remaining member**
     (the **oldest remaining member** by membership `createdAt`, promoted to OWNER); if **no** member remains, it is
     **grace-tombstoned** (same soft-delete as above). A server rule, so no tenant-owner snooping power is needed.
     Its trigger is **tenant member-removal** ([uc.spaces.remove-tenant-member](../features/spaces/use-cases/uc.spaces.remove-tenant-member.md)):
     removing a member cuts their **shared** access at once (ADR 0025 — no tenant rotation), but their **own** scopes
     (personal + any solo group) are **grace-tombstoned with read access kept**, so they can read/export their own
     memories for the window before erasure — their stuff isn't taken the instant they leave. The rotation of groups
     they merely co-shared is client-driven and completed later by a remaining member (the async backstop).
   - **Removal → rotation.** A removed member keeps the content key they already held; confidentiality of **new**
     content is restored by **rotating the scope key forward into a new epoch** — re-grant it to the remaining
     members, re-key every node below it, **nothing existing is re-encrypted** (the epoch chain,
     [ADR 0024](0024-scope-key-rotation.md), [ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md));
     already-read content cannot be retracted (a stated limitation).

7. **A member's Memory favorites** are [ADR 0068](0068-favorites-two-confidentiality-levels.md)'s: `K_SC` is shared in a
   group, so a member's private favorites seal under their own master key, never under it.

## Consequences

- **Groups reuse the content stack.** The Memory tree ([ADR 0012](0012-content-key-hierarchy.md)), browse, and
  favorites work per-scope already — a group is "another scope" to them. Favorites are the one place that needed
  more than "another scope": see §7 and [ADR 0068](0068-favorites-two-confidentiality-levels.md).
- **Confidential even from the tenant owner.** The strongest practical privacy statement veyq makes: a group's
  *content and name* are sealed; only its *existence + membership* are visible to whoever runs the server, and
  the product surfaces neither to the tenant owner.
- **No new crypto, no new operator trust.** Member grants are the same hybrid KEM as the tenant's
  ([ADR 0020](0020-grant-distribution-invite-wrap.md)); the only new server state is the GROUP scope row's sealed name
  + the per-member grants + the pending-deletion lifecycle.
- **Not built:** adding someone who is not yet a tenant member to a group by email.

Explained for security researchers on the docs site: [What the server sees](../../site/src/content/docs/security/what-the-server-sees.md).
