# ADR 0024 — Scope-key rotation: content-key epochs, rotate-forward on removal

- Status: Accepted
- Date: 2026-07-05

## Context

Removing a member from a group must make **new** content unreadable to them — the confidentiality point of
"remove" ([ADR 0014](0014-group-scopes-and-governance.md) §6). [ADR 0012](0012-content-key-hierarchy.md) already
frames the defense ("rotate `K_SC`, re-wrap to the remaining members; already-read content cannot be retracted")
but leaves the **mechanism** open. This ADR nails it.

Two constraints shape the mechanism:

- **A removed member keeps every key they ever held**, plus any ciphertext they already fetched. So no scheme can
  retract *already-seen* content — an accepted limit ([security-model](../crosscutting/security-model.md) revocation).
- **`K_SC` is the navigation root** ([ADR 0012](0012-content-key-hierarchy.md)): node **names**, `slug_token`s and
  the (deferred) `dedup_token` key off it. A naïve full rotation would therefore have to re-seal the whole tree —
  and to deny a removed member *existing* media it would have to change every `K_mem` and **re-encrypt + re-upload
  every blob** (they hold the old `K_SC`, so the old `K_mem`-wrap still opens for them). That is gigabytes re-keyed
  on every leave, for the narrow gain of protecting *existing-but-unopened* content against a **malicious operator**
  — a threat veyq otherwise punts on pre-key-transparency. Rejected.

## Decision

**`K_SC` becomes a forward chain of content-key epochs. Removal mints a new epoch granted only to the remaining
members; new content seals under it. Nothing is re-encrypted.**

1. **Content-key epochs, backward-linked.** The scope's content key is a sequence `E0, E1, E2, …` of random
   32-byte keys. Each new epoch **wraps its predecessor**: `symWrap(E_{n+1}, E_n)` (stored server-side as an opaque
   epoch-chain link). So **holding the latest epoch derives every earlier one** (peel the chain); holding an old
   epoch can **never** derive a newer one. A member's grant wraps the **latest epoch they are entitled to**:
   `asymWrap(memberIdentityPub, E_latest, "scope-grant")` — same primitive, purpose and wire shape as
   [ADR 0020](0020-grant-distribution-invite-wrap.md)'s, only the plaintext is the latest epoch key, and the seal
   binds the scope and that epoch ([ADR 0069](0069-a-seal-binds-its-object.md)).

2. **Content is tagged with the epoch it was sealed under.** Node **names**, `slug_token`s and each Memory's
   `K_mem`-wrap seal under the **current** epoch at creation time; every node carries a **server-visible epoch
   index** (a small integer). A member reads a node of epoch `k` iff the highest epoch they hold ≥ `k` (they peel
   down to `E_k`). The epoch index is not sensitive: the operator already sees membership changes + node
   `createdAt`, so "which key generation" is inferable from timing — exposing it adds no semantic leak and saves an
   unwrap on every access. Slug navigation stays correct because the client holds the whole chain and computes each
   node's `slug_token` under **that node's** epoch.

3. **Dedup keys off a STABLE, non-rotating sub-key — it does not rotate.** A rotating dedup key would give the same
   blob a different `dedup_token` per epoch, breaking cross-epoch dedup. So when dedup is built (it is **deferred
   today**, [ADR 0013](0013-blob-storage-presigned-direct-to-store.md) — the `dedup_token` vector is pinned but unused), it keys off a
   **stable per-scope sub-key** established at scope birth, never rotated. A removed member retains the ability to
   *test-for-existence* of a blob they can supply (the same equality leak ADR 0013 already weighs for convergent
   dedup) — not a content leak. This is a forward-compatibility rule, not live code.

4. **Remove a member = rotate forward.** The removing owner's device:
   (a) mints `E_{n+1}`, posts the chain-link `symWrap(E_{n+1}, E_n)`;
   (b) re-grants `E_{n+1}` to the remaining members it can reach (`asymWrap` to each identity), carrying each
   one's admission tag forward — verified under `E_n`, re-tagged under `E_{n+1}`
   ([ADR 0020](0020-grant-distribution-invite-wrap.md) move 6);
   (c) the server **deletes the removed member's membership** (instant layer-1 access cut — a 403 on the scope).
   New content now seals under `E_{n+1}`. The removed member keeps ≤ `E_n` → reads pre-removal content, **never**
   post-removal content, even against a malicious operator.

5. **Re-granting existing members reuses grant-delivery, generalized.** Today grant-delivery reconciles
   **grant-less** memberships ([ADR 0020](0020-grant-distribution-invite-wrap.md)/[ADR 0021](0021-operator-blind-background-tasks.md)).
   Rotation makes remaining members' grants **stale** (they wrap an old epoch), not absent. The reconciler
   generalizes its trigger from *"grant is null"* to *"grant is null **or** its epoch < the scope's current
   epoch"*; any online key-holder re-wraps the current epoch to a stale/absent member, **first-online-wins,
   idempotent** — unchanged discovery, one widened predicate. No server-side wrapping (operator stays blind).

**Guarantee.** *Forward-confidential for post-rotation content, even against a malicious operator.* Already-read
and pre-rotation content is not retractable (the stated limit); honest-server access-control (the deleted
membership → 403) is the instant first layer for everything future.

## Consequences

- **Cheap rotation.** A removal costs **one random key + one chain-wrap + one `asymWrap` and one re-tagged
  admission per remaining member** — no content re-seal, **no media re-encryption**. Sequential removals just
  extend the chain.
- **Members carry a key-history**, realized as the chain (they hold one key, derive the rest); new server state is
  the per-scope **epoch counter**, the opaque **epoch-chain links**, a **granted-epoch** marker per membership, and
  a **epoch index** per node. All opaque/operational — no key, no plaintext.
- **`K_SC` is no longer literally "one key."** [ADR 0012](0012-content-key-hierarchy.md) §1 + its rotation
  paragraph are edited to say "the scope's current content-epoch key" and point here; the dedup keying split is
  recorded there too.
- **Grant-delivery gains a second trigger** (stale epoch), noted in [ADR 0020](0020-grant-distribution-invite-wrap.md)
  / [ADR 0021](0021-operator-blind-background-tasks.md).
- **Scope-generic mechanism; the *policy* is per scope type** ([ADR 0025](0025-forward-secrecy-policy-per-scope.md)).
  This ADR is only the mechanism; *when* to apply it is decided there: **groups always rotate on removal** (its
  first consumer, G3, [uc.spaces.manage-group-members](../features/spaces/use-cases/uc.spaces.manage-group-members.md));
  the **tenant** (the primary, potentially large content home) revokes via ACL by default and rotates only on an
  explicit re-key, because removal-rotation is inherently `O(N)` re-grants; **personal** scopes never rotate (one member).
- **Rejected — full re-encryption** (re-seal the tree + re-encrypt every blob): gigabytes re-keyed per leave for a
  gain (existing-unopened content vs a malicious operator) that is both narrow and outside veyq's defended threat
  model. **Rejected — a flat key-set** (hold every epoch key independently): `O(epochs)` wraps to onboard each new
  member; the backward chain makes onboarding one wrap. **Rejected — single-key rotation without epochs**: to keep
  existing content readable it must re-seal it under the new key — the same re-encryption cost.
