# ADR 0025 — Forward-secrecy policy per scope type (when to rotate `K_SC`)

- Status: Accepted
- Built: yes
- Date: 2026-07-05
- Model: [keys-grants-and-trust](../crosscutting/keys-grants-and-trust.md)
- Replaced in part: § 3 by ADR 0060 (built); § 2 by ADR 0111 (built)

## Context

[ADR 0024](0024-scope-key-rotation.md) gives the rotation **mechanism** (a backward-linked content-key epoch chain;
remove-a-member = mint `E_{n+1}`, re-grant the remaining members, delete the removed membership) and notes it is
"scope-generic, group-first" — but deliberately leaves the **policy** open: *which* scopes rotate on removal, and
*when*. Its first and only consumer so far is group member-removal.
This ADR settles the policy, because the tenant scope forces the question.

**The tenant scope is veyq's primary shared-content home** (groups are secondary), and a tenant can hold **hundreds
of members**. That collides with a cost that is fundamental, not incidental:

- **Rotation-on-removal is inherently `O(N)`.** For each remaining member to keep reading **new** content, the new
  epoch must be `asymWrap`ped to **their** identity key — the epoch chain only unwraps *downward*, so a member
  holding only the old epoch **cannot self-derive** the new one. Removing 1 of `N` therefore re-grants `N-1`
  members. At `N=300` that is ~299 hybrid (X25519 + ML-KEM-768) wraps — roughly **half a second to a second** of
  compute plus **~400 KB** of grant ciphertext, concentrated on the **removing** device, and it grows **linearly**
  (thousands → untenable).
- **There is no cheaper flat scheme.** "Wrap the secret under `N` keys so any one opens it" **is** this `O(N)` —
  each recipient is one wrap; it does not avoid the cost, it *is* the cost. **`m-of-n` threshold** (Shamir) is a
  *different access structure* — it requires `m` parties to **collude to read**, which is the opposite of our model
  (each member reads **alone**); it reduces nothing here. Sub-linear removal needs either a **key tree**
  (MLS/TreeKEM, `O(log N)`) or exotic broadcast encryption (non-standard, pairing-based — against our
  vendor-neutral-standards rule) — a major architecture lift.

**What rotation actually buys, and against whom.** Deleting the membership is an **instant, `O(1)`** access cut (a
403 on the scope): against an **honest-but-curious operator** the ex-member already gets nothing new. Rotation adds
exactly one thing on top: forward-confidentiality against a **malicious operator who colludes with the ex-member**,
feeding them post-removal ciphertext to decrypt with a retained `K_SC`. "**Operator-blind**" (our hard invariant)
means the operator **alone** can never read — it does **not**, by itself, promise forward-secrecy against
operator-plus-ex-member collusion. So the `O(N)` rotation is a **liveness/defense-in-depth cost for a narrow,
stronger threat**, not part of the security-critical path of a removal.

**Industry reference.** 1Password wraps a per-vault key to each member's public key (identical shape to our grants)
and, on member removal, **revokes via ACL and does not auto-rotate** the vault key; rotation ("regenerate the
vault") is an explicit, rare action. This is the established practice for per-user-wrapped shared secrets.

## Decision

**Forward-secrecy on removal is a policy that depends on the scope type.**

1. **Group — always rotate on removal** ([ADR 0024](0024-scope-key-rotation.md)).
   Small `N`, intentional and often sensitive sharing; the `O(N)` burst is small and rotation delivers
   post-removal forward-confidentiality, against a malicious operator as far as
   [ADR 0024](0024-scope-key-rotation.md) § Guarantee states — a rotation queued to another owner, as a
   tenant removal that cuts a co-shared group is, holds against an honest operator only. For the compromise case with
   nobody to remove — a member's device lost — a group owner gives the group the same explicit **"re-key this space"**
   as a tenant owner (§ 2): a plain rotation of the group from its members panel, over the members then present
   ([uc.spaces.manage-group-members](../features/spaces/use-cases/uc.spaces.manage-group-members.md) A6). A removal
   needs none, it already rotates.

2. **Tenant — ACL-revoke by default; no automatic content-key rotation on routine removal.** Removing a member
   **deletes the membership** (instant 403) and stops there. Content-key rotation is an **explicit "re-key this
   space"**, reserved for the **compromise case** (a member's key leaked, a device lost, a removed member who may have
   kept the key), where the `O(N)` re-grant is justified and rare. It has two callers, and both take the one rotation
   path ([ADR 0024](0024-scope-key-rotation.md) §4):
   - **A tenant owner**, from the tenant's members panel — on its own, or as an opt-in on a removal, unchecked by
     default so a routine removal stays ACL-revoke. With a removal, the removal lands first and the rotation follows
     over the members then remaining, the removed one excluded by the owner's own choice: it is never wrapped to them.
     A failed rotation leaves the removal standing and says so.
   - **An account rotation** ([ADR 0060](0060-key-rotation-after-compromise.md)): the securing account re-keys the
     tenant from its own device
     ([ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
     decision 8).

   Either rotator grants every member it anchors from its own device and declares the rest, and every open invite it
   cannot vouch for, pending (ADR 0111 decisions 2 and 9); only the node re-key is spread across members' devices, and
   a member declared pending is brought current by grant-delivery ([ADR 0024](0024-scope-key-rotation.md) §5).
   The flow is [uc.spaces.remove-tenant-member](../features/spaces/use-cases/uc.spaces.remove-tenant-member.md) A8–A9.

3. **Personal — never on a removal** (a single member; nothing to rotate away from). It rotates when its account is
   secured, self-served: there the old identity is exactly the somebody to rotate away from
   ([ADR 0060](0060-key-rotation-after-compromise.md) Decision 5).

## Consequences

- **Routine tenant removal is `O(1)` and scales to any `N`** — the common operation on the largest, primary scope
  stays cheap. The `O(N)` cost is paid only on an explicit, rare re-key, by the device that commits it.
- **Forward-secrecy is scope-dependent by design:** strong (auto-rotate) for groups; ACL + explicit-rotate for the
  tenant. Both honor **operator-blind** (the operator alone can never read).
- **Accepted residual (tenant), stated honestly.** An ex-tenant-member who hoarded `K_SC` plus already-fetched
  ciphertext, **and** finds a malicious operator to feed them post-removal ciphertext, could decrypt it until an
  explicit re-key — the same residual [security-model](../crosscutting/security-model.md) already books for
  revocation, and the same one 1Password accepts. If this becomes unacceptable for some tenants, the **only**
  escalation that keeps auto-rotate-on-every-removal affordable at large `N` is a **key tree** (MLS/TreeKEM,
  `O(log N)` re-wraps) — a large, deferred architecture change, taken up only if tenants prove large **and** churny.
- **No rotation means no epoch signal for a public link.** A link a removed tenant member could copy cannot be
  "rotated away" (a rotation re-wraps it to the new node key, [ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md)
  point 4), and the tenant's epoch does not move on a removal, so the scope instead
  counts its removals and each share stamps the count it was minted under; a link minted before a removal is
  re-issued, never re-activated, and the removal can turn every tenant link off in the same act
  ([ADR 0075](0075-a-link-a-departed-member-could-copy-is-reissued.md) points 4–5). A counter, not a key event —
  the `O(1)` removal stands.
- **Rejected — apply the group policy uniformly (always-rotate the tenant):** puts an `O(N)` burst on *every*
  routine removal from the largest, primary scope, for a threat (operator + ex-member collusion) outside the default
  posture — the wrong default for the primary content home. **Rejected — `m-of-n` threshold as a cost reducer:**
  wrong access model (collusion-to-read), saves nothing for per-member individual access.

Explained for security researchers on the docs site: [Key hierarchy](../../site/src/content/docs/security/key-hierarchy.md).
