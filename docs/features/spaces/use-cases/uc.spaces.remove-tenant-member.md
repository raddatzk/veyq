# UC: Remove a member from a tenant (with the group orphan rule)

- **ID:** uc.spaces.remove-tenant-member
- **Primary actor:** **TenantOwner** (`OWNER` on the TENANT scope, i.e. `scopeId == tenantId`). Rotating the groups
  the removed member merely co-shared is completed later by **a remaining group owner's device** (operator-blind —
  the server cannot rotate).
- **Goal:** remove a member from the tenant — **cut their access to shared + group content immediately**, hand over or
  wind down the groups they leave behind, and give them a **grace window to read their own memories** before those
  are erased. Not: snatch away everything they own the moment they leave.
- **Preconditions:** The caller is an **OWNER** of the tenant ([ADR 0014](../../../decisions/0014-group-scopes-and-governance.md) §4,
  [ADR 0007](../../../decisions/0007-membership-role-grant-relation.md)). The target is a current tenant member and
  **not the last tenant OWNER**.
- **Postconditions (success):** The target's **tenant-scope + shared-group** memberships are deleted (instant 403 on
  shared content). Groups they were the **only accepted owner** of are **transferred** to the oldest remaining accepted
  member, else **grace-tombstoned**; either way their open consent proposals have expired. The target's **own** scopes
  (their personal scope + any group with no other *accepted* member) are **grace-tombstoned but still readable by them** until the scheduled purge erases them. The tenant scope
  is **not** rotated ([ADR 0025](../../../decisions/0025-forward-secrecy-policy-per-scope.md) — ACL-revoke). Every
  scope the removal cut has **counted it**, so a public link on it that the removed member could have rebuilt — any
  link minted before the removal that carries a recovery copy, a co-member's included — can no longer be
  re-activated, only renewed: until a member renews it or a switch-off (A5) turns it off, such a link **keeps
  working** for whoever holds it ([ADR 0075](../../../decisions/0075-a-link-a-departed-member-could-copy-is-reissued.md)
  point 4). The server saw only membership deletions, a removal count and a lifecycle timestamp — never a key.

## Main flow

1. In the tenant's **members** panel ([uc.spaces.add-member](uc.spaces.add-member.md) directory), the TenantOwner
   picks a member and removes them.
2. The server checks the caller is a tenant **OWNER** and the target is **not the last tenant OWNER**
   (last-owner guard — a tenant keeps ≥1 owner, [ADR 0007](../../../decisions/0007-membership-role-grant-relation.md)).
3. **Immediate cut from shared spaces.** Delete the target's **TENANT-scope** membership and every **GROUP**
   membership where the group is **shared** (has other members). The target gets an instant 403 on all of it (ADR 0025 —
   the tenant content key is **not** rotated; ACL revocation is the layer).
4. **Orphan rule — groups the target *solely* owned** (they were OWNER, no other accepted owner remains,
   [ADR 0014](../../../decisions/0014-group-scopes-and-governance.md) §6). Only **accepted** rows count: a consent
   proposal is no member and is never handed a group, however old. The group's **open proposals expire** (deleted) —
   the owner who made them is leaving:
   - **Other accepted members remain** → the **oldest remaining accepted member** (earliest `createdAt`) is
     auto-promoted to OWNER; the group carries on under new ownership (a **server rule**, not a tenant-owner power —
     the owner never gains access). Its queued rotation (A4) takes away the key the departed owner and the expired
     proposals held. "Solely" is decided under the lock of every scope the target is in, so a co-owner who steps down
     while the removal runs is counted, and the group is handed on instead of being left without an owner.
   - **No accepted member remains** (a solo group, perhaps with open proposals) → handled by step 5 (it's the
     target's own); its proposals are deleted, and nothing rotates — nobody is left to hold a key. An invitee's
     accept takes the group's lock too, so one that overlaps the removal waits for it and finds the invitation gone
     (`404 invitation_not_found`): it never joins a group the removal read as the target's own (inbox #599).
5. **The target's *own* scopes — grace-tombstone, keep read access.** The target's **personal scope** and any
   **group with no other accepted member** are **soft-deleted** (the cr-0090 grace + read-only + scheduled purge) —
   but the target **keeps their grant** on them. So the removed member can still sign in to the tenant and
   **read + download** their own memories (read-only) for the grace window; then the scheduled purge erases them.
   *Their own stuff isn't taken the moment they leave; shared stuff is.* Read-only means exactly that: no write,
   **no restore** — un-deleting their own spaces would undo the removal — and every tenant route answers them as
   a non-member ([uc.spaces.scope-access](uc.spaces.scope-access.md) A2).

## Alternate / exception flows

- **A1 — last-owner guard.** The **last tenant OWNER** cannot be removed (a tenant needs ≥1 owner); the action is
  rejected. Promote another member to co-owner first.
- **A2 — a non-owner attempts a removal:** rejected — tenant-owner-gated (`403 not_tenant_owner`, enforced by code,
  [ADR 0008](../../../decisions/0008-centralized-http-auth.md)).
- **A3 — the target is not a tenant member:** `404 member_not_found`.
- **A4 — co-shared groups rotate via the async backstop (cr-0092).** For a group the target merely **co-shared**,
  the layer-1 cut (deleted membership → 403) is immediate, but the group's forward-secrecy **rotation** must run on
  a key-holder's device (client-driven — the removing tenant owner isn't necessarily even in that group). The
  removal **queues a `SCOPE_ROTATION` job** on the operator-blind task queue
  ([ADR 0021](../../../decisions/0021-operator-blind-background-tasks.md)); the next online remaining **owner** of
  the group mints `E_{n+1}` + re-grants the survivors and completes it (reusing the cr-0089 rotation crypto). A
  removal **joins** the group's still-unclaimed job, so removals before anyone is online cost one rotation; one
  that arrives after an owner claimed the job gets its own; first-online-wins
  ([uc.spaces.complete-queued-rotation](uc.spaces.complete-queued-rotation.md)). Not a hole: layer-1 already blocks new
  fetches; until rotation, the ex-member's old grant reads only pre-departure epochs (the "rotation queued until a
  key-holder is online" limit accepted for group removal, [en.spaces.scope-key-rotation](../enablers/en.spaces.scope-key-rotation.md) §E1).
- **A5 — turn off every link of the space in the same act.** The removal confirmation carries an unchecked box,
  *"also turn off every link in this space"*; ticked, the removal also turns off every enabled public link of the
  **tenant** space — whoever made it — in its own transaction, and marks each so it comes back only as a renewed
  link ([uc.sharing.share-shared-scope](../../sharing/use-cases/uc.sharing.share-shared-scope.md) A4). Not the
  co-shared groups' links: the tenant owner need not be in those groups and cannot see them; there the removal count
  marks every link and the group's owners renew. A refused removal (A1–A3) turns nothing off
  ([ADR 0075](../../../decisions/0075-a-link-a-departed-member-could-copy-is-reissued.md) point 5).
- **A6 — turn off every link of the space, with no removal.** A TenantOwner may turn off every public link of the
  tenant space at any time, from the members panel, behind a confirmation that says they come back only as renewed
  links; the result names how many went off. The same switch-off as A5, marked the same way; a non-owner is refused
  (`403 not_scope_owner`).
- **A7 — the target is only *proposed* to a group.** Their open consent proposal goes as a decline's does
  ([uc.spaces.consent-group-add](uc.spaces.consent-group-add.md)): the row is deleted and, since it held the key, the
  group's rotation is queued; no removal is counted on the group (a proposal was served nothing of it,
  [ADR 0075](../../../decisions/0075-a-link-a-departed-member-could-copy-is-reissued.md) point 4), and the group keeps
  its own owner — it is never tombstoned or handed on for a proposal. An accept of that proposal that overlaps the
  removal waits on the group's lock and finds the proposal gone, so a removed member never stays in the group as an
  accepted one (inbox #599).
- **E1 — already-downloaded content:** the target keeps whatever they saved locally — **not retractable** by any
  design. Removal (and the grace purge) erase the **server** copy, not what already left it.

## Quality notes

- **Operator-blind — and the server may do this work.** Removal is access-control + (for the target's own scopes)
  erasure, which the blind server may perform: it deletes memberships and flips `deletedAt` — **no key, no name, no
  plaintext**. The **tenant content key is not rotated** ([ADR 0025](../../../decisions/0025-forward-secrecy-policy-per-scope.md) —
  the tenant is the primary, potentially large content home; O(N) rotation-per-removal is the wrong default);
  groups rotate later, client-driven.
- **Humane erasure.** Reusing the cr-0090 grace + read-only + purge, the target's **own** memories (personal + any
  solo group) get a **read-only grace window** — they can look through + export them before they're gone. Only
  **shared** content is cut instantly.
- **Orphan rule = a rule, not a power.** The tenant owner never gains access to a group's content; ownership
  auto-transfers to the **oldest** remaining accepted member (deterministic, no owner choice) or the group winds down
  ([ADR 0014](../../../decisions/0014-group-scopes-and-governance.md) §6).
- **Scope-generic reuse.** Soft-delete/grace/purge (`ScopeEntity.deletedAt`, `ScopePurge`, cr-0090) apply to the
  personal scope and solo groups unchanged — the only twist is **keeping the target's membership** so they retain
  read access during grace.

## User impact

You can remove someone from your shared space and cut them off from everything shared at once, while their own private
memories stay readable to them for 30 days to save.

## Related

- Feature: [Spaces (scopes)](../_index.md)
- Decisions: [ADR 0014](../../../decisions/0014-group-scopes-and-governance.md) (§6 orphan rule + lifecycle),
  [ADR 0025](../../../decisions/0025-forward-secrecy-policy-per-scope.md) (tenant = ACL-revoke, no rotation; groups
  rotate), [ADR 0075](../../../decisions/0075-a-link-a-departed-member-could-copy-is-reissued.md) (the removal count,
  turning every link off), [ADR 0007](../../../decisions/0007-membership-role-grant-relation.md) (roles + last-owner),
  [ADR 0008](../../../decisions/0008-centralized-http-auth.md) (owner-gating)
- Sibling use-cases: [uc.spaces.delete-group](uc.spaces.delete-group.md) (the lifecycle reused),
  [uc.spaces.manage-group-members](uc.spaces.manage-group-members.md) (group removal + rotation),
  [uc.spaces.add-member](uc.spaces.add-member.md) (the tenant roster's other half)
- End-user view (docs site): [Invite and manage members](../../../../site/src/content/docs/space-owners/who-gets-into-your-space.mdx)
