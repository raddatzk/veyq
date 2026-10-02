# UC: Remove a member from a tenant (with the group orphan rule)

- **ID:** uc.spaces.remove-tenant-member
- **Primary actor:** **TenantOwner** (`OWNER` on the TENANT scope, i.e. `scopeId == tenantId`). Rotating the groups
  the removed member merely co-shared is completed later by **any remaining group key-holder's device** (operator-
  blind — the server cannot rotate).
- **Goal:** remove a member from the tenant — **cut their access to shared + group content immediately**, hand over or
  wind down the groups they leave behind, and give them a **grace window to read their own memories** before those
  are erased. Not: snatch away everything they own the moment they leave.
- **Preconditions:** The caller is an **OWNER** of the tenant ([ADR 0014](../../../decisions/0014-group-scopes-and-governance.md) §4,
  [ADR 0007](../../../decisions/0007-membership-role-grant-relation.md)). The target is a current tenant member and
  **not the last tenant OWNER**.
- **Postconditions (success):** The target's **tenant-scope + shared-group** memberships are deleted (instant 403 on
  shared content). Groups they **solely owned** are **transferred** to the oldest remaining member, else
  **grace-tombstoned**. The target's **own** scopes (their personal scope + any group they were the *only* member
  of) are **grace-tombstoned but still readable by them** until the scheduled purge erases them. The tenant scope
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
4. **Orphan rule — groups the target *solely* owned** (they were OWNER, no other owner remains,
   [ADR 0014](../../../decisions/0014-group-scopes-and-governance.md) §6):
   - **Other members remain** → the **oldest remaining member** (earliest `createdAt`) is auto-promoted to OWNER; the
     group carries on under new ownership (a **server rule**, not a tenant-owner power — the owner never gains access).
   - **No member remains** (a solo group) → handled by step 5 (it's the target's own).
5. **The target's *own* scopes — grace-tombstone, keep read access.** The target's **personal scope** and any
   **group they were the sole member of** are **soft-deleted** (the cr-0090 grace + read-only + scheduled purge) —
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
  ([ADR 0021](../../../decisions/0021-operator-blind-background-tasks.md)); the next online remaining key-holder
  mints `E_{n+1}` + re-grants the survivors and completes it (reusing the cr-0089 rotation crypto). One live job
  per scope **coalesces** multiple removals; idempotent + first-online-wins. Not a hole: layer-1 already blocks new
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
  auto-transfers to the **oldest** remaining member (deterministic, no owner choice) or the group winds down
  ([ADR 0014](../../../decisions/0014-group-scopes-and-governance.md) §6).
- **Scope-generic reuse.** Soft-delete/grace/purge (`ScopeEntity.deletedAt`, `ScopePurge`, cr-0090) apply to the
  personal scope and solo groups unchanged — the only twist is **keeping the target's membership** so they retain
  read access during grace.

## What this means for you / Was das für dich bedeutet

**EN —** Removing someone from your space cuts their account off from everything shared straight away — the shared
tenant space and every group they were in. Public links are different: they could have copied any link to a shared
memory, and a link works for whoever holds it. Those links can't simply be switched back on any more, only renewed —
and when you remove someone you can turn every link of the space off in the same step, or at any later time. A group they alone ran passes to its longest-standing member so it's never left
leaderless; if they were its only person, it winds down on the same 30-day timer as a deleted group. Their **own**
private memories aren't snatched away the instant they leave: they keep **read-only** access to look through and save
them for 30 days, and only then are they erased. You never gain access to anyone's private group — the handover is
automatic.

**DE —** Jemanden aus deinem Bereich zu entfernen trennt sie sofort von allem Geteilten — dem gemeinsamen
Tenant-Bereich und jeder Gruppe, in der sie waren. Bei öffentlichen Links ist es anders: Jeden Link zu einer geteilten
Erinnerung können sie kopiert haben, und ein Link funktioniert für alle, die ihn haben. Solche Links lassen sich
nicht mehr einfach wieder einschalten, nur erneuern — und beim Entfernen kannst du im selben Schritt alle Links des
Bereichs abschalten, oder jederzeit später. Eine Gruppe, die sie allein geführt haben, geht an ihr
langjährigstes Mitglied, damit sie nie führungslos zurückbleibt; waren sie die einzige Person, läuft sie auf
demselben 30-Tage-Timer aus wie eine gelöschte Gruppe. Ihre **eigenen** privaten Erinnerungen werden ihnen nicht im
Moment des Entfernens entrissen: sie behalten **30 Tage** lesenden Zugriff zum Durchsehen und Speichern, erst danach
werden sie gelöscht. Du bekommst nie Zugriff auf die private Gruppe von jemandem — die Übergabe ist automatisch.

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
