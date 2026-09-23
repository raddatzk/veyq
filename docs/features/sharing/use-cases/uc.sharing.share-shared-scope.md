# UC: Public-share a Memory in a shared scope (group / tenant)

- **ID:** uc.sharing.share-shared-scope
- **Primary actor:** **any member** of a group or tenant scope (holds the scope grant → the Memory's `K_mem`).
- **Secondary actor:** **an anonymous visitor** (viewer / contributor / uploader — the existing roles), and the
  **remaining members** of the scope (who inherit the governance below).
- **Goal:** lift the v1 **personal-scope-only** restriction so a member can public-share a **group or tenant** Memory
  with the same operator-blind link mechanism — while keeping shared-scope content honestly revocable when a member
  leaves.
- **Preconditions:** an **authenticated, unlocked** caller who is a **member of the target Memory's scope** (personal,
  group, or tenant) and can open it (holds `K_mem`); the instance advertises the role's capability
  (`sharing.public-links` / `sharing.uploader-links`, [ADR 0032](../../../decisions/0032-capability-discovery-version-gating.md)).
- **Postconditions (success):** a share record exists (opaque, owned by its creator), and the link opens the Memory
  subtree per its role — until revoked, expired, **or auto-deactivated by the creator's removal from the scope**.

This extends the sharing slices ([ADR 0029](../../../decisions/0029-public-viewer-share.md) §6, this revision) from
personal-scope to **any scope the caller belongs to**, across all three built roles
([viewer](uc.sharing.share-public-viewer.md) / [contributor](uc.sharing.share-contributor.md) /
[uploader](uc.sharing.share-uploader.md)). The crypto and the anonymous access path are **unchanged** — only the
create-gate (scope) and a member-removal hook are new.

## Main flow

1. **Create (any member).** A member opens a group/tenant Memory → **Share** → picks a role. The client mints
   `K_share`, computes `symWrap(K_share, K_mem)`, seals the name under `K_share`, and `POST`s the share record.
   The create-gate is now **"a MEMORY in a scope the caller is a member of"** (not "personal-scope MEMORY the caller
   owns") — `AND`-ed with the role's instance capability. **Any member may publish**, not only an owner: shared
   content belongs to all members, and a member could already reach an outsider by other means.
2. **Hand off / open / view.** Identical to the personal-scope flow — `K_share` rides the fragment, the anonymous
   resolve + gated direct-from-store blob reads are unchanged; the server stays blind.
3. **Follows new content automatically.** Because `K_mem` is **stable and never re-keyed**
   ([ADR 0024](../../../decisions/0024-scope-key-rotation.md): no content re-encryption), photos a member adds to
   the shared Memory **after** a scope-key rotation seal under the **same** `K_mem` — so the existing share shows
   them, the URL never changes, and no re-wrap is needed.

## Alternate / exception flows

- **A1 — member removed → their shares deactivate.** Removing a member from the scope **soft-revokes every share
  that member created there** (`enabled = false`): the resolve + gated blob endpoint `404`. This is the
  **honest-server forward-secrecy layer** for a public link — since `K_mem` cannot be rotated away, the server-side
  deactivation *is* the revocation. It runs alongside (and independently of) the group scope-key rotation the
  removal also triggers ([ADR 0024](../../../decisions/0024-scope-key-rotation.md)); for a **tenant** (ACL-revoke,
  no rotation — [ADR 0025](../../../decisions/0025-forward-secrecy-policy-per-scope.md)) it is the *only* forward
  layer for the link.
- **A2 — re-activate a deactivated share → ownership transfers.** Any **remaining member** may re-enable a
  deactivated share; doing so **transfers its ownership** (`owner_account`) to the re-activating member. An active
  share is thus always tied to a *current* member's responsibility, never orphaned to a removed one — and a
  later removal of the new owner would deactivate it again. Re-activating is a publication like creating, so it
  passes the same checks: a current member (not an unaccepted invitee, not during the group's deletion grace,
  [uc.spaces.scope-access](../../spaces/use-cases/uc.spaces.scope-access.md)) **and** the instance's sharing
  capability for the share's role — `422 capability_disabled` when the operator has switched that kind of link
  off ([ADR 0032](../../../decisions/0032-capability-discovery-version-gating.md)).
- **A3 — a creator who has left the scope** keeps nothing: editing or revoking their former share answers them
  as a non-member (404). Ownership is a claim of a *current* member; the share's `owner_account` alone never
  authorizes.
- **E1 — non-member / lost grant:** a caller who is not a member of the scope (or cannot open `K_mem`) cannot
  create a share there — the same 404/authorization path as any non-member scope access (no reveal).

## Quality notes

- **Operator-blind, unchanged.** The share record is the same opaque shape (`shareId`, `symWrap(K_share, K_mem)`,
  `role`, sealed name, `nodeId`, `owner_account`, `enabled`); a shared-scope share adds **no** new operator-visible
  signal. The server never sees `K_share`, `K_mem`, names, or the visitor.
- **Already-read is not retractable.** Deactivation stops *new* access; a removed member who kept both the link
  and the ciphertext they already fetched can replay *that* — and, because they kept `K_mem`, could decrypt further
  ciphertext only via a **colluding malicious operator**. This is the same accepted limit as
  [ADR 0024](../../../decisions/0024-scope-key-rotation.md) / the [security model](../../../crosscutting/security-model.md);
  the honest-server 404 is the instant layer.
- **Who may publish = any member.** A deliberately permissive governance choice (symmetric to member capabilities),
  distinct from the per-instance `sharing.*` capability and the per-scope membership gate — three orthogonal checks.

## What this means for you / Was das für dich bedeutet

**EN —** Shared spaces get the same public links your personal memories already have: anyone in the group or
household can turn a shared memory into a browser link for people outside. And it stays honest when someone leaves —
the moment a member is removed, the links *they* made stop working; if the group still wants one alive, another
member re-activates it and it becomes theirs.

**DE —** Geteilte Bereiche bekommen dieselben öffentlichen Links wie deine persönlichen Erinnerungen: jede:r in der
Gruppe oder im Haushalt kann eine geteilte Erinnerung in einen Browser-Link für Außenstehende verwandeln. Und es
bleibt ehrlich, wenn jemand geht — sobald ein Mitglied entfernt wird, hören *seine* Links auf zu funktionieren;
will die Gruppe einen weiter offen halten, aktiviert ihn ein anderes Mitglied und übernimmt ihn damit.

## Related

- Feature: [Sharing](../_index.md)
- Roles extended: [uc.sharing.share-public-viewer](uc.sharing.share-public-viewer.md),
  [uc.sharing.share-contributor](uc.sharing.share-contributor.md),
  [uc.sharing.share-uploader](uc.sharing.share-uploader.md)
- Decisions: [ADR 0029](../../../decisions/0029-public-viewer-share.md) §6 (scope + removal governance),
  [ADR 0024](../../../decisions/0024-scope-key-rotation.md) (`K_mem` stable across rotation),
  [ADR 0025](../../../decisions/0025-forward-secrecy-policy-per-scope.md) (tenant = ACL-revoke, group = rotation),
  [ADR 0032](../../../decisions/0032-capability-discovery-version-gating.md) (the `sharing.*` capability gate)
- Cross-cutting: [Security Model](../../../crosscutting/security-model.md),
  [Scopes & Tenancy](../../../crosscutting/scopes-and-tenancy.md)
