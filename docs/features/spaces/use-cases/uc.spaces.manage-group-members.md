# UC: Manage a group's members (promote / demote / remove)

- **ID:** uc.spaces.manage-group-members
- **Primary actor:** **GroupOwner** (`OWNER` on the GROUP scope). The removing owner's device performs the key
  rotation, in the same request as the removal (operator-blind — the server cannot).
- **Goal:** run a group's roster — **promote** a member to co-owner, **demote** a co-owner back to member, and
  **remove** a member — within the ownership rules, so a removed member loses access to **new** content.
- **Preconditions:** The caller is an **OWNER** of the group ([ADR 0014](../../../decisions/0014-group-scopes-and-governance.md) §4).
  The target is a current member of the group.
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
   - Members the rotation could not re-grant (a changed key, an unverified admission) get the new epoch later by
     grant-delivery; until then their scope reads as awaiting access and they write nothing (A4). New content
     seals under the new epoch. The removed member keeps the old epoch → reads pre-removal content, **never**
     post-removal content — against a malicious operator as far as ADR 0024 § Guarantee states.

## Alternate / exception flows

- **A1 — last-owner guard.** The **last remaining OWNER** cannot be demoted or removed (a group needs ≥1 owner,
  [ADR 0007](../../../decisions/0007-membership-role-grant-relation.md) / [ADR 0014](../../../decisions/0014-group-scopes-and-governance.md) §4).
  The action is rejected; the caller must first promote another member or delete the group.
- **A2 — the remover holds no key.** A tenant owner's removal that cuts a co-shared group they do not hold
  ([uc.spaces.remove-tenant-member](uc.spaces.remove-tenant-member.md) A4) cannot rotate: the rotation is *queued*
  as a `SCOPE_ROTATION` job until a remaining key-holder is next online, who mints `E_{n+1}` + re-grants the
  survivors on-device (the **async rotation backstop**, cr-0092,
  [en.spaces.scope-key-rotation](../enablers/en.spaces.scope-key-rotation.md) §E1). That device did not remove
  anyone and re-grants the directory the server returns, so this path holds against an honest operator only
  (ADR 0024 § Guarantee). A group owner's own removal never queues: it is step 4's one request.
- **A3 — a non-owner attempts an action:** rejected (owner-gated, enforced by code, [ADR 0008](../../../decisions/0008-centralized-http-auth.md)).
- **A4 — a member whose grant is older than the current epoch** (a rotation skipped them, or its re-grant has not
  arrived): their client reads the scope as awaiting access and writes nothing into it until re-granted, and the
  server refuses a node create tagged with an older epoch (`409 stale_epoch`) — content under an old epoch would
  be readable by every member removed since ([ADR 0024](../../../decisions/0024-scope-key-rotation.md) Decision 6).
- **E1 — already-read content:** a removed member keeps whatever they already decrypted — **not retractable** by
  any design (the stated limit). Removal protects **future** content only.

> **Sibling — group lifecycle (G3b):** deleting a group (soft-delete + 30-day grace + restore, then a scheduled
> hard-purge) is [uc.spaces.delete-group](uc.spaces.delete-group.md). The **orphan rule** (when a sole owner leaves
> the tenant, ownership auto-transfers to the **oldest remaining member**, else grace-tombstone,
> [ADR 0014](../../../decisions/0014-group-scopes-and-governance.md) §6) rides with **tenant** member-removal
> ([ADR 0025](../../../decisions/0025-forward-secrecy-policy-per-scope.md)), which is future work.

## Quality notes

- **Operator-blind.** Role changes + membership deletion are operational metadata the server already holds; the
  rotation stores only opaque epoch links + grants + a per-node epoch index — never a key or a name.
- **Admission stays owner-gated; delivery is any key-holder.** Only an OWNER may change roles or remove; a
  re-grant the removal's rotation had to skip is completed by any online key-holder (first-online-wins), matching
  the add-member split ([ADR 0020](../../../decisions/0020-grant-distribution-invite-wrap.md)).
- **Roles are per-scope.** A group OWNER/MEMBER is independent of any tenant role — a GroupOwner need not be the
  tenant owner, and the tenant owner has no say over group roles ([ADR 0014](../../../decisions/0014-group-scopes-and-governance.md) §4).

## What this means for you / Was das für dich bedeutet

**EN —** Your group's roster is yours to run: make a trusted member a co-owner so you're not the only one who can
manage it, step someone back down, or remove someone who no longer belongs. When you remove someone, two things
happen at once — they immediately lose access, and the group quietly re-keys so that **everything you add from
then on is sealed away from them**, even if the server itself were turned against you. What they already saw, they
saw — no app can reach into their memory — but nothing new. A group always keeps at least one owner, so it can
never be left with no one in charge.

**DE —** Die Mitgliederliste deiner Gruppe gehört dir: mach ein vertrautes Mitglied zum Mit-Owner, damit nicht nur
du verwalten kannst, stufe jemanden wieder herab, oder entferne jemanden, der nicht mehr dazugehört. Beim Entfernen
passieren zwei Dinge gleichzeitig — die Person verliert sofort den Zugang, und die Gruppe wechselt still den
Schlüssel, sodass **alles, was du ab dann hinzufügst, für sie versiegelt bleibt** — selbst wenn der Server gegen
dich gerichtet würde. Was sie schon gesehen hat, hat sie gesehen — das holt keine App zurück — aber nichts Neues.
Eine Gruppe behält immer mindestens eine:n Owner, kann also nie führungslos zurückbleiben.

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
