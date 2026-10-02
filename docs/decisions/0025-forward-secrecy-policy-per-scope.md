# ADR 0025 — Forward-secrecy policy per scope type (when to rotate `K_SC`)

- Status: Accepted
- Date: 2026-07-05

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
   [ADR 0024](0024-scope-key-rotation.md) § Guarantee states — a rotation queued to another key-holder, as a
   tenant removal that cuts a co-shared group is, holds against an honest operator only. **Unchanged.**

2. **Tenant — ACL-revoke by default; no automatic content-key rotation on routine removal.** Removing a member
   **deletes the membership** (instant 403) and stops there. Content-key rotation is offered as an **explicit,
   owner-triggered "re-key this space"** action, reserved for the **compromise case** (a member's key leaked or
   device stolen), where the `O(N)` re-grant is justified and rare. When invoked it runs **async and distributed**
   via grant-delivery ([ADR 0021](0021-operator-blind-background-tasks.md) / [ADR 0024](0024-scope-key-rotation.md) §5 —
   the reconciler already re-grants *stale-epoch* members, first-online-wins), so no single device bears a
   synchronous `O(N)` burst.

3. **Personal — never** (a single member; nothing to rotate away from).

## Consequences

- **Routine tenant removal is `O(1)` and scales to any `N`** — the common operation on the largest, primary scope
  stays cheap. The `O(N)` cost is paid only on an explicit, rare re-key, and even then is amortized across devices.
- **Forward-secrecy is scope-dependent by design:** strong (auto-rotate) for groups; ACL + explicit-rotate for the
  tenant. Both honor **operator-blind** (the operator alone can never read).
- **Accepted residual (tenant), stated honestly.** An ex-tenant-member who hoarded `K_SC` plus already-fetched
  ciphertext, **and** finds a malicious operator to feed them post-removal ciphertext, could decrypt it until an
  explicit re-key — the same residual [security-model](../crosscutting/security-model.md) already books for
  revocation, and the same one 1Password accepts. If this becomes unacceptable for some tenants, the **only**
  escalation that keeps auto-rotate-on-every-removal affordable at large `N` is a **key tree** (MLS/TreeKEM,
  `O(log N)` re-wraps) — a large, deferred architecture change, taken up only if tenants prove large **and** churny.
- **Built to this policy (cr-0091).** Tenant member-removal is the ACL-revoke cascade (`rotateAfter = false`,
  `TenantHandler`) — O(1), no rotation, as decided here. The rare explicit **re-key** rides the generic
  `rotateScope` endpoint (live for groups); a dedicated tenant re-key flow is not separately wired. The group
  path is already live.
- **No rotation means no epoch signal for a public link.** A link a removed tenant member could copy cannot be
  "rotated away" (`K_mem` never re-keys), and the tenant's epoch does not move on a removal, so the scope instead
  counts its removals and each share stamps the count it was minted under; a link minted before a removal is
  re-issued, never re-activated, and the removal can turn every tenant link off in the same act
  ([ADR 0075](0075-a-link-a-departed-member-could-copy-is-reissued.md) points 4–5). A counter, not a key event —
  the `O(1)` removal stands.
- **Edits in place:** [ADR 0024](0024-scope-key-rotation.md)'s "scope-generic, group-first" consequence and the
  [security-model](../crosscutting/security-model.md) revocation section point here for the per-scope policy.
- **Rejected — apply the group policy uniformly (always-rotate the tenant):** puts an `O(N)` burst on *every*
  routine removal from the largest, primary scope, for a threat (operator + ex-member collusion) outside the default
  posture — the wrong default for the primary content home. **Rejected — `m-of-n` threshold as a cost reducer:**
  wrong access model (collusion-to-read), saves nothing for per-member individual access.
