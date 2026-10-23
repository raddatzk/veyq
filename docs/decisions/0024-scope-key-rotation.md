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
  and to deny a removed member *existing* media it would have to change every `K_node` and **re-encrypt + re-upload
  every blob** (they hold the old `K_SC`, so the old `K_node`-wrap still opens for them). That is gigabytes re-keyed
  on every leave, for the narrow gain of protecting *existing-but-unopened* content against a **malicious operator**
  — a threat veyq otherwise punts on pre-key-transparency. Rejected. (Replacing every `K_node` **without**
  re-encrypting a blob — re-wrapping each `K_blob` and re-sealing the database fields under the new keys — is what
  keeps *post-rotation* content inside existing folders and Memories out of reach, and every rotation does it:
  [ADR 0108](0108-a-scope-rotation-re-keys-every-node-below-it.md).)

## Decision

**`K_SC` becomes a forward chain of content-key epochs. Removal mints a new epoch granted only to the remaining
members; new content seals under it. Nothing is re-encrypted.**

1. **Content-key epochs, backward-linked.** The scope's content key is a sequence `E0, E1, E2, …` of random
   32-byte keys. Each new epoch **wraps its predecessor**: `symWrap(E_{n+1}, E_n)` (stored server-side as an opaque
   epoch-chain link). So **holding the latest epoch derives every earlier one** (peel the chain); holding an old
   epoch can **never** derive a newer one. A member's grant wraps the **latest epoch they are entitled to**:
   `asymWrap(memberIdentityPub, E_latest, "scope-grant")` — same primitive, purpose and wire shape as
   [ADR 0020](0020-grant-distribution-invite-wrap.md)'s, only the plaintext is the latest epoch key, and the seal
   binds the scope and that epoch ([ADR 0072](0072-a-seal-binds-its-object.md)).

2. **Content is tagged with the epoch it was sealed under.** Node **names**, `slug_token`s and each Memory's
   `K_node`-wrap seal under the **current** epoch at creation time; every node carries a **server-visible epoch
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

4. **Remove a member = rotate forward, in one act.** The removing owner's device:
   (a) mints `E_{n+1}`, posts the chain-link `symWrap(E_{n+1}, E_n)`;
   (b) re-grants `E_{n+1}` to the remaining members — the members it read **before** the removal, minus the one
   it removes (`asymWrap` to each identity) — carrying each one's admission tag forward, verified under `E_n` and
   re-tagged under `E_{n+1}` ([ADR 0020](0020-grant-distribution-invite-wrap.md) move 6);
   (c) names the removed member **in the same request**, and the server deletes that membership and commits the
   new epoch in one transaction (instant layer-1 access cut — a 403 on the scope).
   The removed member is excluded by the remover's own choice, never by a member list read afterwards: a server
   that re-lists them would otherwise receive `E_{n+1}` wrapped to their genuine key, because their admission tag
   verifies under `E_n`. One request also means a closed tab can no longer leave a removal without its rotation.

   **The commit states the epoch it creates, and the server takes it only as the scope's current epoch + 1**;
   anything else is `409 stale_epoch` and nothing lands. The device builds the link from **its own grant**, not
   from the scope's epoch, so a rotation that lost a race to another, or rode a grant a previous rotation skipped,
   would otherwise link the new epoch to a key that is not its predecessor, and every epoch skipped that way would
   be unreachable for every member. A refused rotation is rebuilt once from a re-opened grant and a **fresh** key.
   The refused attempt's wraps already left the device, and one of them may be addressed to a member removed in
   the meantime, so reusing that key would give the server a grant it could hand them.

   New content now seals under `E_{n+1}`, and the same device then **re-keys every node of the scope**
   ([ADR 0108](0108-a-scope-rotation-re-keys-every-node-below-it.md)), so what is added inside an existing folder
   or Memory seals under a key the removed member never held. The removed member keeps ≤ `E_n` and the node keys of
   before → reads pre-removal content, **never** post-removal content — against whom, § Guarantee states.

5. **Re-granting existing members reuses grant-delivery, generalized.** Today grant-delivery reconciles
   **grant-less** memberships ([ADR 0020](0020-grant-distribution-invite-wrap.md)/[ADR 0021](0021-operator-blind-background-tasks.md)).
   Rotation makes remaining members' grants **stale** (they wrap an old epoch), not absent. The reconciler
   generalizes its trigger from *"grant is null"* to *"grant is null **or** its epoch < the scope's current
   epoch"*; any online key-holder re-wraps the current epoch to a stale/absent member, **first-online-wins,
   idempotent** — unchanged discovery, one widened predicate. No server-side wrapping (operator stays blind).

6. **No client writes under an epoch it knows is stale.** A rotation only protects what is sealed *after* it, so
   the writer's epoch is the whole guarantee. Two sources of knowledge, each against its own operator:
   - **The scope's current epoch, as the server reports it.** A member whose grant is older (skipped for a
     changed key, or still awaiting re-grant) does not write; the scope reads as *awaiting the current key*. The
     server refuses an epoch-tagged write below the current epoch. Both are layer 1 — they are what an honest
     operator needs, and without them a stale member's new content sits under an epoch the removed member holds.
   - **A per-device floor.** Each device remembers the highest epoch it has held for each scope and never seals
     under a lower one, whatever it is served. This is what an active operator cannot argue with; the mechanism
     is the device-local high-water mark of [ADR 0028](0028-identity-authenticity-tofu-verification.md)
     Decision 7. The epoch it compares is authentic because every grant binds its epoch
     ([ADR 0072](0072-a-seal-binds-its-object.md) Decision 5: binding makes a label authentic, the floor makes
     it fresh).

**Guarantee.** *Forward-confidential for post-rotation content* — against an honest operator and a database or
store dump once every writer holds the new epoch, which Decision 6 makes the condition for writing, **and once the
rotation's re-key has reached every node** ([ADR 0108](0108-a-scope-rotation-re-keys-every-node-below-it.md), which
holds every write into a node not yet re-keyed). **Until `changes/cr-scope-rotation-rekeys-nodes.md` lands it holds
only for new scope-root entries:** node keys are not re-keyed yet, so what is added inside a folder or Memory that
existed before the rotation seals under a key the excluded party holds (inbox #518). A link they could rebuild stays
their way in until it is re-issued (ADR 0108 point 4). Against an
**active** operator, on a client it does not serve ([ADR 0026](0026-client-trust-tiers.md)), it holds for content
sealed by any client that has received the new epoch: the removal's own rotation cannot be redirected to the
removed member (Decision 4), and no client goes back below an epoch it has held (Decision 6). Three things it
does not cover, stated rather than hidden:

- **A withheld rotation (a fork).** An operator can keep a member from ever learning of the new epoch. That
  member's client keeps writing under the old one, and nothing on the wire tells it otherwise; a floor only stops
  a client going *back* from what it has seen. Detecting a fork needs a transparency mechanism.
- **Rotations queued to another key-holder.** A tenant-member removal that cuts a co-shared group, and an
  account rotation after a compromise ([ADR 0060](0060-key-rotation-after-compromise.md)), are completed by
  whichever **owner** of the scope is next online, and that client knows only the member list the server returns.
  There is nothing of its own to exclude by, so these hold against an honest operator only. Within that bound the
  queue keeps two promises: an event **joins** a queued job only while nobody has claimed it (a claimed job's
  worker may already have read the directory the event changed, so a later event gets a job of its own), and a
  job is `done` only once its lease holder committed an epoch while holding the lease — never on the worker's
  word alone.
- **A departing member colluding with the operator** — a sockpuppet admission tagged under `E_n` before the
  removal ([ADR 0020](0020-grant-distribution-invite-wrap.md) § Consequences). Closing it needs a member roster
  the client holds itself, a key-tree-sized change not taken.

One object sits outside the guarantee by choice: item favorites seal under `E_0`
([ADR 0068](0068-favorites-two-confidentiality-levels.md)). Already-read and pre-rotation content is not
retractable (the stated limit); honest-server access-control (the deleted membership → 403) is the instant first
layer for everything future.

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

## What is still open

Decision 4's single request and both halves of Decision 6 are built. The floor is one of the device's
trust-state marks ([ADR 0028](0028-identity-authenticity-tofu-verification.md) Decision 7): a grant this device
opened, or a rotation it made, raises it, and a grant below it — whatever `currentEpoch` the server pairs it
with — opens as the same *awaiting the current key* state as layer 1. No read-only open: one closed state for
both halves (owner decision, 2026-09-28), so a rolled-back scope shows neither old nor new content. What the floor
cannot see is the § Guarantee list above — above all the fork, a member who never learnt of the newer epoch.

Explained for security researchers on the docs site: [Threat model](../../site/src/content/docs/security/threat-model.md), [Key hierarchy](../../site/src/content/docs/security/key-hierarchy.md), [Known limitations](../../site/src/content/docs/security/known-limitations.md).
