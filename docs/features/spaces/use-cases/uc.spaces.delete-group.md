# UC: Delete a group (soft-delete, grace period, restore)

- **ID:** uc.spaces.delete-group
- **Primary actor:** **GroupOwner** (`OWNER` on the GROUP scope). The eventual hard-purge is run by a **scheduled
  server job** — operator-blind-safe, because it only deletes ciphertext + opaque rows + blob objects (it never
  needs a key or sees a name), unlike the key rotation that removal triggers.
- **Goal:** let a GroupOwner **retire a group** without instant, irreversible loss — the group enters a **grace
  period** in which it is clearly marked as deleting, everyone can still look through it, and any owner can undo —
  after which it is **erased** (memories + files) from the server.
- **Preconditions:** The caller is an **OWNER** of the group ([ADR 0014](../../../decisions/0014-group-scopes-and-governance.md) §4/§6).
  The scope is a **GROUP** (personal/tenant scopes have their own lifecycles).
- **Postconditions (success):** The group is in **pending-deletion** (a `deletedAt` marker + a `purgeAfter` date); it
  stays readable but **read-only** for every member and is flagged as deleting. **Restore** (any owner, in-window)
  returns it to fully active. At `purgeAfter`, the scheduled job **hard-erases** the group and all its content —
  nodes, their blob objects in the store, memberships, epoch links, favorites, queued background jobs, shares, and
  the scope row. The server saw only a lifecycle timestamp + opaque deletions — never a key or a name.

## Main flow

1. In the group's **settings** page ([uc.spaces.manage-group-members](uc.spaces.manage-group-members.md) is the
   sibling roster view), the GroupOwner opens the **danger zone** and chooses **Delete group**, then confirms.
2. The server marks the scope **pending-deletion** — sets `deletedAt = now`, `purgeAfter = now + retention`
   (**30 days**, `P30D`). Grants and memberships are **left untouched** (so restore is trivial).
3. **During the grace window**, the group stays in every member's space list and is **enterable + viewable +
   downloadable**, but **clearly marked** "*wird am &lt;date&gt; gelöscht*" and **read-only** — no new Memories,
   uploads, renames, or key rotations (it is on its way out). Any **owner** additionally sees a **Restore** action.
4. **Restore** (any owner, while `deletedAt` is set and before `purgeAfter`): clears `deletedAt`/`purgeAfter`; the
   group is **fully active** again for everyone, exactly as before (nothing was moved).
5. **At `purgeAfter`**, a scheduled server job **hard-purges** the scope: it reuses the existing blob-aware delete
   paths ([BlobStore.delete] + node-subtree erasure, cr-0073) to remove every node's **blob objects from the
   store**, then the media/node rows, memberships, epoch-chain links, favorites, queued background jobs, shares,
   and finally the scope row. **Irreversible** — nothing is recoverable after this.

   **Erasure is total, and that is a schema-level rule rather than a list to keep in step:** every table that
   references `veyq_scope` is either named by the purge or carries `ON DELETE CASCADE`; a table that is neither
   holds the scope row hostage on its foreign key and the purge can never complete. The list above is therefore
   the *consequence* and not the requirement — `scripts/validate_scope_erasure_arity.py` is what binds it, and a
   new table referencing the scope must satisfy the guard before it lands. **A share is erased, not retained:**
   it is a capability record for a scope that no longer exists, so there is nothing left to revoke, and its row
   holds the owner, the node and a sealed name belonging to a group whose erasure the owner asked for.

   **One scope that cannot be erased must not stop the others.** The sweep isolates each scope in its own
   transaction, so a failure is confined to that scope and the rest of the tick still completes; a tick-wide
   transaction turns any single stuck scope into a permanent, silent halt of *all* reclamation.

## Alternate / exception flows

- **A1 — restore after purge.** Once the scheduled job has run, the group is **gone** (`404 scope_not_found`); the
  grace window is the *only* recovery path. There is no post-purge undo (the point of erasure).
- **A2 — a non-owner attempts delete or restore:** rejected — owner-gated (`403 not_scope_owner`, enforced by code,
  [ADR 0008](../../../decisions/0008-centralized-http-auth.md)).
- **A3 — delete via this path on a non-GROUP scope** (personal/tenant): rejected (`409` — group-only; the tenant
  and personal scopes have their own lifecycles, not this owner-initiated delete).
- **A4 — a content write while pending-deletion** (create a Memory/folder, rename, or delete a node): rejected
  (`409 scope_deleting`) — the group is **read-only** while deleting. View + download stay open so members can still
  see their pictures. (As built, cr-0090: the server guard covers the node mutations; media-upload-to-an-existing-
  node + key rotation are left unguarded as a minor, documented exception — new Memories are already blocked.)
- **A5 — delete an already-deleting group / restore an active one:** rejected (`409 scope_already_deleted` /
  nothing to restore) — the state machine is idempotent-guarded.
- **E1 — locally-saved copies.** A member who **downloads** content during the grace window keeps those local files
  — **not retractable** by any design (the stated limit). Purge erases the **shared** group from the server, not
  what already left it.

> **Sibling — the orphan rule (deferred).** [ADR 0014](../../../decisions/0014-group-scopes-and-governance.md) §6
> also defines an **automatic** path: when a user is **removed from the tenant** and **solely** owns a group,
> ownership auto-transfers to the **oldest remaining member**, else the group is **grace-tombstoned** (the same
> soft-delete as here). Its trigger is **tenant member-removal**, which is future work
> ([ADR 0025](../../../decisions/0025-forward-secrecy-policy-per-scope.md)) — the orphan rule lands **with** it, not
> in this owner-initiated slice.

## Quality notes

- **Operator-blind — and here the server *may* do the work.** Deletion + purge are **access-control + erasure**,
  which the blind server is allowed to perform (it reads no content): it flips a `deletedAt` timestamp and, at
  purge, deletes opaque rows + blob objects. No key, no name, no plaintext — unlike the **rotation** on member
  removal, which must run on a key-holder's device. `deletedAt`/`purgeAfter` are operational metadata the operator
  already sees the shape of (membership + timestamps).
- **Grace = 30 days (`P30D`), reused from the tenant-invite retention precedent** — one retention-constant family
  (purge pattern). Irreplaceable shared memories deserve
  a generous, recoverable window, and pending-deletion only holds **ciphertext**, so the cost of waiting is nil.
- **Multiple owners: any owner deletes, any owner restores.** Delete is a destructive-but-reversible-in-window act;
  the grace window is the mutual safety net, so a single owner's mistake (or pique) is undoable by any co-owner. The
  **last-owner guard does not apply** — delete is the intentional whole-group teardown, not a roster change.
- **Read-only while deleting** is the honest state ("on its way out") and keeps restore trivial — there is nothing
  to reconcile, because nothing changed during the window.

## What this means for you / Was das für dich bedeutet

**EN —** Deleting a group isn't instant destruction — it starts a 30-day countdown. Everyone can still open it and
look through the pictures one last time, clearly told it's on its way out, and **any owner can undo the deletion**
during that window. Nothing new can be added while it winds down. After 30 days it's gone for good: the memories and
their files are **erased from the server**, not just hidden. Whatever someone saved to their own device in the
meantime is theirs — but the shared group is truly gone.

**DE —** Eine Gruppe zu löschen ist keine sofortige Zerstörung — es startet ein 30-Tage-Countdown. Alle können sie
noch öffnen und die Bilder ein letztes Mal durchsehen, klar gekennzeichnet, dass sie ausläuft, und **jede:r Owner
kann die Löschung** in diesem Fenster rückgängig machen. Während sie ausläuft, kann nichts Neues hinzukommen. Nach
30 Tagen ist sie endgültig weg: die Erinnerungen und ihre Dateien werden **vom Server gelöscht**, nicht nur
versteckt. Was jemand in der Zwischenzeit auf dem eigenen Gerät gespeichert hat, gehört ihm — aber die geteilte
Gruppe ist wirklich fort.

## Related

- Feature: [Spaces (scopes)](../_index.md)
- Enablers: [en.spaces.group-scope](../enablers/en.spaces.group-scope.md) (the group being deleted)
- Decisions: [ADR 0014](../../../decisions/0014-group-scopes-and-governance.md) (§6 lifecycle: soft-delete + grace,
  orphan rule), [ADR 0025](../../../decisions/0025-forward-secrecy-policy-per-scope.md) (why the orphan half waits
  for tenant removal), [ADR 0008](../../../decisions/0008-centralized-http-auth.md) (owner-gating)
- Sibling use-cases: [uc.spaces.manage-group-members](uc.spaces.manage-group-members.md) (roster — the other owner
  power), [uc.spaces.add-group-member](uc.spaces.add-group-member.md)
