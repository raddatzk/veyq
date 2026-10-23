# UC: Public-share a Memory in a shared scope (group / tenant)

- **ID:** uc.sharing.share-shared-scope
- **Primary actor:** **any member** of a group or tenant scope (holds the scope grant → the Memory's `K_node`).
- **Secondary actor:** **an anonymous visitor** (viewer / contributor / uploader — the existing roles), and the
  **remaining members** of the scope (who inherit the governance below).
- **Goal:** lift the v1 **personal-scope-only** restriction so a member can public-share a **group or tenant** Memory
  with the same operator-blind link mechanism — while keeping shared-scope content honestly revocable when a member
  leaves.
- **Preconditions:** an **authenticated, unlocked** caller who is a **member of the target Memory's scope** (personal,
  group, or tenant) and can open it (holds `K_node`); the instance advertises the role's capability
  (`core.public-shares`, `.contribute` or `.drop-box`, [ADR 0032](../../../decisions/0032-capability-discovery-version-gating.md)).
- **Postconditions (success):** a share record exists (opaque, owned by its creator), and the link opens the Memory
  subtree per its role — until revoked, expired, **or auto-deactivated by the creator's removal from the scope**.

This extends the sharing slices ([ADR 0029](../../../decisions/0029-public-viewer-share.md) §6, this revision) from
personal-scope to **any scope the caller belongs to**, across all three built roles
([viewer](uc.sharing.share-public-viewer.md) / [contributor](uc.sharing.share-contributor.md) /
[uploader](uc.sharing.share-uploader.md)). The crypto and the anonymous access path are **unchanged** — only the
create-gate (scope) and a member-removal hook are new.

## Main flow

1. **Create (any member).** A member opens a group/tenant Memory → **Share** → picks a role. The client mints
   `K_share`, computes `symWrap(K_share, K_node)`, seals the name under a field key derived from `K_share`, and `POST`s the share record.
   The create-gate is now **"a MEMORY in a scope the caller is a member of"** (not "personal-scope MEMORY the caller
   owns") — `AND`-ed with the role's instance capability. **Any member may publish**, not only an owner: shared
   content belongs to all members, and a member could already reach an outsider by other means.
2. **Hand off / open / view.** Identical to the personal-scope flow — `K_share` rides the fragment, the anonymous
   resolve + gated direct-from-store blob reads are unchanged; the server stays blind.
3. **Follows new content automatically.** Between rotations `K_node` stays, so photos a member adds to the shared
   Memory seal under the key the link already opens. A scope rotation re-keys every node and **re-wraps each share's
   root key** under the share's unchanged `K_share`, opened from its recovery copy
   ([ADR 0109](../../../decisions/0109-a-scope-rotation-re-keys-every-node-below-it.md) point 4,
   [uc.spaces.rekey-after-rotation](../../spaces/use-cases/uc.spaces.rekey-after-rotation.md)) — so the existing
   share shows what is added after it too, and the URL never changes. The recovery copy stays at its epoch, so the
   link is marked `reissueRequired` (A2): whoever held an older epoch rebuilds the same `K_share` and, with a dump,
   reads through it what is added below its root until it is renewed (A4).

## Alternate / exception flows

- **A1 — member removed → their shares deactivate.** Removing a member from the scope **soft-revokes every share
  that member created there** (`enabled = false`): the resolve + gated blob endpoint `404`. This is the
  **honest-server forward-secrecy layer** for a public link — a rotation re-wraps every link to the new node key
  (main flow 3), so the server-side deactivation, and then a renew (A4), *is* the revocation. It runs alongside (and independently of) the group scope-key rotation the
  removal also triggers ([ADR 0024](../../../decisions/0024-scope-key-rotation.md)); for a **tenant** (ACL-revoke,
  no rotation — [ADR 0025](../../../decisions/0025-forward-secrecy-policy-per-scope.md)) it is the *only* forward
  layer for the link.
- **A2 — re-activate a deactivated share → ownership transfers, unless a departed member may hold its link.** Any
  **remaining member** may re-enable a deactivated share; doing so **transfers its ownership** (`owner_account`) to
  the re-activating member, and the **same** link works again. So re-activation is refused (`409
  reissue_required`, nothing written) for a link a departed member may hold
  ([ADR 0075](../../../decisions/0075-a-link-a-departed-member-could-copy-is-reissued.md) point 4): a bulk
  switch-off turned it off (A5); the share's owner is no longer a member of the scope (in a tenant too: a removed
  tenant member's own links); or it carries a recovery copy that predates the scope's current epoch **or its last
  member removal** — every member could rebuild every link from its copy, one since removed included. The removal
  count is what carries this on a tenant, whose epoch never moves on a removal, and on a group whose rotation a
  tenant removal only queued. A share with no copy is judged by the switch-off and its owner alone. The server
  serves its verdict on every row of the manage list (`reissueRequired`), from the same predicate the refusal runs;
  the panel marks such a link — *"made before someone left this space or replaced their keys"*, since a key
  rotation after a compromise moves the epoch too — and offers **renew** (A4) in place of re-activate. Re-activating is a publication like creating, so it
  passes the same checks: a current member (not an unaccepted invitee, not during the group's deletion grace,
  [uc.spaces.scope-access](../../spaces/use-cases/uc.spaces.scope-access.md)) **and** the instance's sharing
  capability for the share's role — `422 capability_disabled` when the operator has switched that kind of link
  off ([ADR 0032](../../../decisions/0032-capability-discovery-version-gating.md)).
- **A3 — a creator who has left the scope** keeps nothing: editing or revoking their former share answers them
  as a non-member (404). Ownership is a claim of a *current* member; the share's `owner_account` alone never
  authorizes.
- **A4 — renew (re-issue) a link.** One request makes a **new** share — a new id, a fresh `K_share` — carrying the
  old one's note, private label, expiry and password (re-sealed under the new key, the password with a fresh salt),
  and **deletes** the old one (A7) in the **same transaction**; any refusal writes nothing. The role and the Memory come
  from the old share; an uploader link keeps the Memory's drop key, since every upload so far is sealed to it. The
  owner renews their own share, live or off; any member renews a share that is off, and owns the new one. The old
  link `404`s exactly as a revoked one — no redirect, which would hand the new link to the person it is meant to cut
  off — can never come back, and the curator hands out the new one. Guests keep the photos they added; a returning guest names themself
  again on the new link and can no longer delete their earlier uploads (a guest's session is per link), and the new
  link's storage budget starts at zero. A link is renewed **at most once**: of two members renewing the same link at
  the same moment, exactly one gets a new link; the other — like anyone renewing a link already renewed or deleted —
  is told so (`409 share_deleted`, nothing made), and the panel re-reads the list, where the one new link is.
- **A5 — turning off every link of a space sticks.** Three paths turn off every enabled share of a space — whoever
  made it — and **mark** every link of the space, one its owner had already switched off included, so each comes
  back only by renew (A4), never re-activate: a group removal's opt-in
  ([uc.spaces.manage-group-members](../../spaces/use-cases/uc.spaces.manage-group-members.md)), a tenant removal's
  ([uc.spaces.remove-tenant-member](../../spaces/use-cases/uc.spaces.remove-tenant-member.md) A5), and a scope
  owner's switch-off with no removal (A6 there, and in the group panel). Each share's owner renews the ones still
  wanted. The mark is what makes it stick: without it, a link with no recovery copy whose owner is still a member
  would come back with one click from any member. Forcing a switch-off on every removal would break every guest's
  link even when someone leaves on good terms; a group owner already holds the stronger power of deleting the group.
- **A7 — delete a link.** A switched-off link stays in the manage panel — folded under a count — until someone
  **deletes** it, behind a confirm ([ADR 0029](../../../decisions/0029-public-viewer-share.md) §7). The server keeps
  a **tombstone**: the id, scope, Memory, role and creation time — not when it was deleted — every ciphertext (sealed name, note, label, recovery copy,
  password fields, the wrap or drop key) cleared. The link `404`s, can never be re-activated, renewed or edited, and
  is in no listing; its id is never minted again. The photos its guests added **stay** in the Memory, readable as
  before — an uploader guest's photos open only through their identity's wrapped key, which goes only once nothing
  of theirs remains. The share's **owner** deletes it at any time (an enabled one is switched off in the same
  transaction; the panel offers delete on switched-off links only); **another member** only once the owner is no
  longer a member of the scope — a co-member's parked link is theirs, note and password copy included. The server
  serves its verdict on every row (`deletable`) from the predicate the delete runs. Erasing a Memory deletes its
  links (`uc.memory.delete-node` E4); erasing a whole space removes its links and **retires** their ids.
- **A8 — a link no guest is served.** A link can be on and still serve no guest: its end date passed, its space is
  paused or being deleted, the Server Owner or the space's owner switched its kind of link off, or it misses a
  requirement in effect (a password, an end date within N days of when it was made). Every guest then gets the `404` an
  unknown link gets ([ADR 0088](../../../decisions/0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md)
  decision 5), and the manage panel says so on the row, with every reason: the server serves them on every row
  (`guestStops`, inbox #457) from the one predicate every guest path passes, so the row and the guest's answer cannot
  disagree. Only a member who may list the link reads the reasons; a guest is told none of them. Memories', the
  folder's (and a file's) and the drop box's panel say it alike.
  **A missed requirement is fixed on the same link** (owner, 2026-10-16): the link's owner gives it a password, or an end
  date within the days counted from when it was made, right from the row, and the link keeps its address and key; its
  guests then meet the password prompt, or the link, instead of the `404`. The server judges the link as the edit leaves
  it, so a write toward the requirement is never refused by it, and one that still misses a requirement is
  (`422 link_password_required` / `link_expiry_required`, nothing written). Switching a link back on is not refused by a
  requirement either: it comes back on and says what it still lacks. A link older than the lifetime has no end date
  that fits, so the row says only a renewed link helps, a new link with a new address (ADR 0075); Memories' and the
  folder's panel offer that renew, the drop box's makes a new link from its form. A switch is no member's to fix: the
  row names who can switch it back on, whoever runs the server or the space's owner.
- **E1 — non-member / lost grant:** a caller who is not a member of the scope (or cannot open `K_node`) cannot
  create a share there — the same 404/authorization path as any non-member scope access (no reveal).

## Quality notes

- **Operator-blind, unchanged.** The share record is the same opaque shape (`shareId`, `symWrap(K_share, K_node)`,
  `role`, sealed name, `nodeId`, `owner_account`, `enabled`, and a deleted link's `deleted_at`, to the second); a
  shared-scope share adds **no** new operator-visible signal. The server never sees `K_share`, `K_node`, names, or the visitor.
- **Already-read is not retractable.** Deactivation stops *new* access; a removed member who kept both the link
  and the ciphertext they already fetched can replay *that* — and, because they kept `K_node`, could decrypt further
  ciphertext only via a **colluding malicious operator**, until the scope next rotates (a group's removal does at
  once; ADR 0109 re-keys every node then), and after it only through a link made before. A link they could copy works against an honest server
  with **no dump at all** until it is renewed (A4) — what renewing buys is that honest-server cut, not dump
  resistance ([ADR 0075](../../../decisions/0075-a-link-a-departed-member-could-copy-is-reissued.md)). This is the
  same accepted limit as
  [ADR 0024](../../../decisions/0024-scope-key-rotation.md) / the [security model](../../../crosscutting/security-model.md);
  the honest-server 404 is the instant layer.
- **Who may publish = any member.** A deliberately permissive governance choice (symmetric to member capabilities),
  distinct from the per-instance `sharing.*` capability and the per-scope membership gate — three orthogonal checks.

## User impact

Members of a group or shared space can share its Memories through links too; when someone is removed, their links
switch off at once, and any remaining member can renew or delete them.

## Related

- Feature: [Sharing](../_index.md)
- Roles extended: [uc.sharing.share-public-viewer](uc.sharing.share-public-viewer.md),
  [uc.sharing.share-contributor](uc.sharing.share-contributor.md),
  [uc.sharing.share-uploader](uc.sharing.share-uploader.md)
- Decisions: [ADR 0029](../../../decisions/0029-public-viewer-share.md) §6 (scope + removal governance), §7 (delete),
  [ADR 0075](../../../decisions/0075-a-link-a-departed-member-could-copy-is-reissued.md) (re-issue, never revive),
  [ADR 0024](../../../decisions/0024-scope-key-rotation.md) (`K_node` stable across rotation),
  [ADR 0025](../../../decisions/0025-forward-secrecy-policy-per-scope.md) (tenant = ACL-revoke, group = rotation),
  [ADR 0032](../../../decisions/0032-capability-discovery-version-gating.md) (the `sharing.*` capability gate)
- Cross-cutting: [Security Model](../../../crosscutting/security-model.md),
  [Scopes & Tenancy](../../../crosscutting/scopes-and-tenancy.md)
- End-user view (docs site): [Change, switch off, renew and delete links](../../../../site/src/content/docs/sharing/manage-links.mdx)
