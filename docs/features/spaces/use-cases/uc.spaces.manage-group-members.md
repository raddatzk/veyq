# UC: Manage a group's members (promote / demote / remove)

- **ID:** uc.spaces.manage-group-members
- **Primary actor:** **GroupOwner** (`OWNER` on the GROUP scope). The removing owner's device performs the key
  rotation, in the same request as the removal (operator-blind — the server cannot).
- **Goal:** run a group's roster — **promote** a member to co-owner, **demote** a co-owner back to member, and
  **remove** a member — within the ownership rules, so a removed member loses access to **new** content.
- **Preconditions:** The caller is an **OWNER** of the group ([ADR 0014](../../../decisions/0014-group-scopes-and-governance.md) §4).
  The target is a current, **accepted** member of the group: a consent proposal is no member until it accepts, so
  promoting one is `404 member_not_found` — the owner rescinds it instead.
- **Postconditions (success):** The target's role/membership reflects the action. On **removal**, the membership is
  deleted (instant 403) **and**, in the same transaction, the scope rotates its content key forward so the removed
  member can't read post-removal content ([en.spaces.scope-key-rotation](../enablers/en.spaces.scope-key-rotation.md)). The server
  saw only role/membership metadata + opaque epoch links/grants — never a key.

## Main flow

1. In the group's **members** view ([uc.spaces.add-group-member](uc.spaces.add-group-member.md) directory), the
   GroupOwner picks a member and an action.
2. **Promote → co-owner:** the member's role becomes `OWNER`. Pure role change — no key move (they already hold
   `K_SC`). Groups have only OWNER / MEMBER; **multiple owners allowed** ([ADR 0007](../../../decisions/0007-membership-role-grant-relation.md)).
3. **Demote co-owner → member:** the OWNER's role becomes `MEMBER`. Blocked by the **last-owner guard** (below).
4. **Remove a member — one request** ([ADR 0024](../../../decisions/0024-scope-key-rotation.md) Decision 4):
   - The owner's device mints a new content-epoch
     ([en.spaces.scope-key-rotation](../enablers/en.spaces.scope-key-rotation.md)) and wraps it to the remaining
     members — the directory the owner **picked the member from**, minus that member, by the owner's own choice;
     never a list read after the removal. The request names the member it removes.
   - The server **deletes** the membership — the instant **layer-1** cut (a 403 on the scope) — and commits the
     epoch in **one transaction**. It refuses a request whose grants name the removed member, and applies the
     last-owner guard. A failed removal leaves the member in place; there is no removal without its rotation.
   - Members the rotation could not re-grant (a key the owner's client cannot anchor) are declared pending in the
     request, which the server requires (`409 rotation_incomplete` otherwise), and get the new epoch later by
     grant-delivery from any member who anchors them; until then
     their scope reads as *waiting for the current key* and they write nothing (A4). A pending consent proposal is
     re-granted like any member: it holds the group's key from its add on. New content
     seals under the new epoch. The removed member keeps the old epoch → reads pre-removal content, **never**
     post-removal content — against a malicious operator as far as ADR 0024 § Guarantee states.

## Alternate / exception flows

- **A1 — last-owner guard.** The **last remaining OWNER** cannot be demoted or removed (a group needs ≥1 owner,
  [ADR 0007](../../../decisions/0007-membership-role-grant-relation.md) / [ADR 0014](../../../decisions/0014-group-scopes-and-governance.md) §4).
  The action is rejected; the caller must first promote another member or delete the group. Only accepted owners
  count: a proposal whose row says OWNER is none. The guard holds when
  changes overlap: every role change, removal-rotation and tenant removal decides under the group row's lock, so two
  co-owners demoting each other end with one owner, and the second is told what a retry would tell it.
- **A2 — the remover holds no key.** A tenant owner's removal that cuts a co-shared group they do not hold
  ([uc.spaces.remove-tenant-member](uc.spaces.remove-tenant-member.md) A4) cannot rotate: the rotation is *queued*
  as a `SCOPE_ROTATION` job until a remaining **owner** of the group is next online, who mints `E_{n+1}` +
  re-grants the survivors on-device (the **async rotation backstop**, cr-0092,
  [en.spaces.scope-key-rotation](../enablers/en.spaces.scope-key-rotation.md) §E1;
  [uc.spaces.complete-queued-rotation](uc.spaces.complete-queued-rotation.md) owns its lifecycle). That device did not remove
  anyone and re-grants the directory the server returns, so this path holds against an honest operator only
  (ADR 0024 § Guarantee). A group owner's own removal never queues: it is step 4's one request.
- **A3 — a non-owner attempts an action:** rejected (owner-gated, enforced by code, [ADR 0008](../../../decisions/0008-centralized-http-auth.md)).
  A removal-rotation is a group's only: naming a member to remove on a tenant or personal scope is `400
  invalid_request` — a tenant member leaves by [uc.spaces.remove-tenant-member](uc.spaces.remove-tenant-member.md)'s
  cascade.
- **A4 — a member whose grant is older than the current epoch** (a rotation skipped them, or its re-grant has not
  arrived): their client reads the scope as waiting for the current key and writes nothing into it until re-granted, and the
  server refuses a node create tagged with an older epoch (`409 stale_epoch`) — content under an old epoch would
  be readable by every member removed since ([ADR 0024](../../../decisions/0024-scope-key-rotation.md) Decision 6).
- **A5 — turn off every link of the group.** An owner may turn off every public link of the group at once — on a
  removal, through the confirmation's unchecked *"also turn off every link"*
  ([uc.sharing.share-shared-scope](../../sharing/use-cases/uc.sharing.share-shared-scope.md) A5), or at any time
  from the group panel, behind a confirmation that says they come back only as renewed links; the result names how
  many went off. Either way each link is marked so it comes back only by renewal, never by re-activation
  ([ADR 0075](../../../decisions/0075-a-link-a-departed-member-could-copy-is-reissued.md) point 5). A non-owner is
  refused (`403 not_scope_owner`); a group in its deletion grace is read-only (`409 scope_deleting`).
- **A6 — give the group a new lock, with no removal.** For the compromise case with nobody to remove — a member's
  device lost — a GroupOwner may re-key the group at any time from its members panel
  ([ADR 0025](../../../decisions/0025-forward-secrecy-policy-per-scope.md) § 1), behind a confirmation that says what
  it protects (a holder of only the old key cannot read what is added from now on) and what it does not (what was
  already seen; links made before keep working until renewed or turned off, a rotation re-wraps them). It is the
  tenant owner's action ([uc.spaces.remove-tenant-member](uc.spaces.remove-tenant-member.md) A9) on the group: a plain
  rotation as any rotation is
  ([keys, grants and trust § Rotating a scope](../../../crosscutting/keys-grants-and-trust.md#rotating-a-scope)), no
  member named for removal — the next epoch minted on-device over the directory read then, every member it anchors
  re-granted, a pending consent proposal included, the rest declared pending — and the device then re-keys the
  group's nodes ([uc.spaces.rekey-after-rotation](uc.spaces.rekey-after-rotation.md)). Not offered on a removal, which
  already rotates, nor while the group is in its deletion grace; a co-owner may run it, a non-owner is refused
  (`403 not_scope_owner`).
- **E1 — already-read content:** a removed member keeps whatever they already decrypted — **not retractable** by
  any design (the stated limit). Removal protects **future** content only.

> **Sibling — group lifecycle (G3b):** deleting a group (soft-delete + 30-day grace + restore, then a scheduled
> hard-purge) is [uc.spaces.delete-group](uc.spaces.delete-group.md). The **orphan rule** (a group whose only accepted
> owner leaves the tenant, [ADR 0014](../../../decisions/0014-group-scopes-and-governance.md) §6) rides with **tenant**
> member-removal: [uc.spaces.remove-tenant-member](uc.spaces.remove-tenant-member.md) step 4.

## Quality notes

- **Operator-blind.** Role changes + membership deletion are operational metadata the server already holds; the
  rotation stores only opaque epoch links + grants + a per-node epoch index — never a key or a name.
- **Membership stays owner-gated; a skipped re-grant is any key-holder's.** Only an OWNER may change roles or
  remove; a re-grant the removal's rotation had to skip is completed by any online key-holder (first-online-wins)
  ([ADR 0020](../../../decisions/0020-grant-distribution-invite-wrap.md)).
- **Roles are per-scope.** A group OWNER/MEMBER is independent of any tenant role — a GroupOwner need not be the
  tenant owner, and the tenant owner has no say over group roles ([ADR 0014](../../../decisions/0014-group-scopes-and-governance.md) §4).

## User impact

You run your group's roster yourself — share ownership, step someone back, or remove them so that nothing added from
then on reaches them — and a group is never left without an owner.

## Related

- Feature: [Spaces (scopes)](../_index.md)
- Enablers: [en.spaces.scope-key-rotation](../enablers/en.spaces.scope-key-rotation.md) (the rotation substrate
  removal triggers), [en.spaces.group-scope](../enablers/en.spaces.group-scope.md) (the group being managed)
- Decisions: [ADR 0014](../../../decisions/0014-group-scopes-and-governance.md) (§4 authority + last-owner guard,
  §6 removal → rotation + orphan/delete), [ADR 0024](../../../decisions/0024-scope-key-rotation.md) (the epoch
  rotation), [ADR 0007](../../../decisions/0007-membership-role-grant-relation.md) (role vs. grant, multiple
  owners), [ADR 0020](../../../decisions/0020-grant-distribution-invite-wrap.md) / [ADR 0021](../../../decisions/0021-operator-blind-background-tasks.md)
  (re-grant on rotation)
- Sibling use-case: [uc.spaces.add-group-member](uc.spaces.add-group-member.md) (add — the roster's other half)
- End-user view (docs site): [People in a group](../../../../site/src/content/docs/spaces/group-members.mdx)
