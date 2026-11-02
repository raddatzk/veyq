# UC: Public-share a Memory in a shared scope (group / tenant)

- **ID:** uc.sharing.share-shared-scope
- **Primary actor:** **any member** of a group or tenant scope (holds the scope grant → the Memory's `K_node`).
- **Secondary actor:** **an anonymous visitor** (viewer / contributor / uploader — the existing roles), and the
  **remaining members** of the scope (who inherit the governance below).
- **Goal:** public-share a **group or tenant** Memory with the same operator-blind link as a personal one — while
  keeping shared-scope content honestly revocable when a member leaves.
- **Preconditions:** an **authenticated, unlocked** caller who is a **member of the target Memory's scope** (personal,
  group, or tenant) and can open it (holds `K_node`); the instance advertises the role's capability
  (`core.public-shares`, `.contribute` or `.drop-box`, [ADR 0032](../../../decisions/0032-capability-discovery-version-gating.md)).
- **Postconditions (success):** a share record exists (opaque, owned by its creator), and the link opens the Memory
  subtree per its role — until revoked, expired, **or auto-deactivated by the creator's removal from the scope**.

A link lives in **any scope the caller belongs to**, in all three roles
([viewer](uc.sharing.share-public-viewer.md) / [contributor](uc.sharing.share-contributor.md) /
[uploader](uc.sharing.share-uploader.md); [ADR 0029](../../../decisions/0029-public-viewer-share.md) §6). What a shared
scope adds is governance: removal, re-activation, renew and delete below. The record, the keys and the lifecycle are
[en.sharing.public-link](../enablers/en.sharing.public-link.md).

## Main flow

1. **Create (any member).** A member opens a group/tenant Memory → **Share** → picks a role. The client mints the
   link's key and stores the share record; the server checks that the caller is a member of the node's scope and that
   the role's capability is on. **Any member may publish**, not only an owner: shared content belongs to all members,
   and a member could already reach an outsider by other means.
2. **Hand off / open / view.** As in a personal scope — the key rides the fragment, the server stays blind.
3. **Follows new content automatically.** Between rotations `K_node` stays, so photos a member adds to the shared
   Memory seal under the key the link already opens. A scope rotation re-keys every node and **re-wraps each open
   share's root key** under the share's unchanged `K_share`, opened from its recovery copy
   ([ADR 0109](../../../decisions/0109-a-scope-rotation-re-keys-every-node-below-it.md) point 4,
   [uc.spaces.rekey-after-rotation](../../spaces/use-cases/uc.spaces.rekey-after-rotation.md)) — so the existing
   share shows what is added after it too, and the URL never changes. The link keeps the epoch it was minted at, so
   it is marked `reissueRequired` (A2): every member of the time could have rebuilt the same `K_share` from its
   recovery copy and, with a dump, reads through it what is added below its root while it is open, until it is
   renewed (A4).

## Alternate / exception flows

- **A1 — member removed → their shares deactivate.** Removing a member from the scope **switches off every share
  that member created there** (`enabled = false`): the resolve + gated blob endpoint `404`, and each switch-off is a
  **close**, which re-keys the subtree below the link's root, so those links open nothing added after it even with a
  dump ([en.sharing.public-link § Lifecycle](../enablers/en.sharing.public-link.md#lifecycle)). It runs alongside (and independently of) the group scope-key
  rotation the removal also triggers ([ADR 0024](../../../decisions/0024-scope-key-rotation.md)); for a **tenant**
  (ACL-revoke, no rotation — [ADR 0025](../../../decisions/0025-forward-secrecy-policy-per-scope.md)) it is the *only*
  forward layer for the link, and the removed member keeps the tenant key until an owner re-keys the tenant.
- **A2 — re-activate a deactivated share → ownership transfers, unless a departed member may hold its link.** Any
  **remaining member** may re-enable a deactivated share; doing so **transfers its ownership** (`owner_account`) to
  the re-activating member, and the **same** link works again. So re-activation is refused (`409
  reissue_required`, nothing written) for a link a departed member may hold
  ([ADR 0075](../../../decisions/0075-a-link-a-departed-member-could-copy-is-reissued.md) point 4): a bulk
  switch-off turned it off (A5); the share's owner is no longer a member of the scope (in a tenant too: a removed
  tenant member's own links); or it carries a recovery copy and was minted below the scope's current epoch **or
  before its last member removal** — every member of the time could rebuild the link from its copy, one since removed
  included. The removal
  count is what carries this on a tenant, whose epoch never moves on a removal, and on a group whose rotation a
  tenant removal only queued. A share with no copy is judged by the switch-off and its owner alone. The server
  serves its verdict on every row of the manage list (`reissueRequired`), from the same predicate the refusal runs;
  the panel marks such a link — *"made before someone left this space or replaced their keys"*, since a key
  rotation after a compromise moves the epoch too — and offers **renew** (A4) in place of re-activate. Re-activating is a publication like creating, so it
  passes the same checks: a current member (not an unaccepted invitee, not during the group's deletion grace,
  [uc.spaces.scope-access](../../spaces/use-cases/uc.spaces.scope-access.md)) **and** the instance's sharing
  capability for the share's role — `422 capability_disabled` when the operator has switched that kind of link
  off ([ADR 0032](../../../decisions/0032-capability-discovery-version-gating.md)). A re-activation **reopens** the
  link, so it carries what the close's re-key left out: the member's device opens `K_share` from the recovery copy and
  sends the root's current key and name sealed under it, and the link reads again everything added while it was
  closed; a link with no recovery copy comes back only by renew
  ([ADR 0114](../../../decisions/0114-closing-a-link-re-keys-the-subtree-below-it.md) decision 7).
- **A3 — a creator who has left the scope** keeps nothing: editing or revoking their former share answers them
  as a non-member (404). Ownership is a claim of a *current* member; the share's `owner_account` alone never
  authorizes. A current one still revokes their link while the group waits for its deletion — the one link write
  the grace window allows, since it only closes exposure, and the link stays off if the deletion is stopped
  ([uc.spaces.scope-access](../../spaces/use-cases/uc.spaces.scope-access.md) A1); a new link, an edit, a
  re-activation, a renew and a delete wait for a restore.
- **A4 — renew (re-issue) a link.** One request makes a **new** share — a new id, a fresh `K_share` — carrying the
  old one's note, private label, expiry and password (re-sealed under the new key, the password with a fresh salt),
  and **deletes** the old one (A7) in the **same transaction**; any refusal writes nothing. The role and the Memory come
  from the old share; an uploader link keeps the Memory's drop key, since every upload so far is sealed to it. The
  owner renews their own share, live or off; any member renews a share that is off, and owns the new one. The old
  link `404`s exactly as a revoked one — no redirect, which would hand the new link to the person it is meant to cut
  off — can never come back, and the curator hands out the new one. Renewing closes the old link, so the subtree below
  its root is re-keyed and the new link follows the new key. Guests keep the photos they added; a returning guest names themself
  again on the new link and can no longer delete their earlier uploads (a guest's session is per link), and the new
  link's storage budget starts at zero. A link is renewed **at most once**: of two members renewing the same link at
  the same moment, exactly one gets a new link; the other — like anyone renewing a link already renewed or deleted —
  is told so (`409 share_deleted`, nothing made), and the panel re-reads the list, where the one new link is.
- **A5 — turning off every link of a space sticks.** Three paths turn off every enabled share of a space — whoever
  made it — and **mark** every link of the space, one its owner had already switched off included, so each comes
  back only by renew (A4), never re-activate: a group removal's opt-in
  ([uc.spaces.manage-group-members](../../spaces/use-cases/uc.spaces.manage-group-members.md)), a tenant removal's
  ([uc.spaces.remove-tenant-member](../../spaces/use-cases/uc.spaces.remove-tenant-member.md) A5), and a scope
  owner's switch-off with no removal (A6 there, and in the group panel). Each switch-off is a close and re-keys the
  subtree below the link's root (A1). Each share's owner renews the ones still wanted. The mark is what makes it stick: without it, a link with no recovery copy whose owner is still a member
  would come back with one click from any member. Forcing a switch-off on every removal would break every guest's
  link even when someone leaves on good terms; a group owner already holds the stronger power of deleting the group.
- **A7 — delete a link.** A switched-off link stays in the manage panel — folded under a count — until someone
  **deletes** it, behind a confirm ([ADR 0029](../../../decisions/0029-public-viewer-share.md) §7). The server keeps
  a **tombstone**: the id, scope, Memory, role and creation time — not when it was deleted — every ciphertext (sealed name, note, label, recovery copy,
  password fields, the wrap or drop key) cleared. The link `404`s, can never be re-activated, renewed or edited, and
  is in no listing; its id is never minted again. The photos its guests added **stay** in the Memory, readable as
  before — an uploader guest's photos open only through their identity's wrapped key, which goes only once nothing
  of theirs remains. The share's **owner** deletes it at any time (an enabled one is switched off in the same
  transaction, a close that re-keys its subtree as A1's does; the panel offers delete on switched-off links only); **another member** only once the owner is no
  longer a member of the scope — a co-member's parked link is theirs, note and password copy included. The server
  serves its verdict on every row (`deletable`) from the predicate the delete runs. Erasing a Memory deletes its
  links (`uc.memory.delete-node` E4); erasing a whole space removes its links and **retires** their ids.
- **A8 — a link no guest is served.** A link can be on and still serve no guest: its end date passed (which also
  closes it: a server sweep re-keys its subtree as A1's close does), its space is paused or being deleted, the Server Owner or the space's owner switched its kind of link off, or it misses a
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
  `role`, sealed name, `nodeId`, `owner_account`, `enabled`; a deleted link keeps a flag, not its deletion time); a
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
- Enabler: [en.sharing.public-link](../enablers/en.sharing.public-link.md) (the record, keys, gates and lifecycle)
- Roles extended: [uc.sharing.share-public-viewer](uc.sharing.share-public-viewer.md),
  [uc.sharing.share-contributor](uc.sharing.share-contributor.md),
  [uc.sharing.share-uploader](uc.sharing.share-uploader.md)
- Decisions: [ADR 0029](../../../decisions/0029-public-viewer-share.md) §6 (scope + removal governance), §7 (delete),
  [ADR 0075](../../../decisions/0075-a-link-a-departed-member-could-copy-is-reissued.md) (re-issue, never revive),
  [ADR 0109](../../../decisions/0109-a-scope-rotation-re-keys-every-node-below-it.md) (a rotation re-keys the node and re-wraps each link under its own key),
  [ADR 0025](../../../decisions/0025-forward-secrecy-policy-per-scope.md) (tenant = ACL-revoke, group = rotation),
  [ADR 0032](../../../decisions/0032-capability-discovery-version-gating.md) (the `sharing.*` capability gate)
- Cross-cutting: [Security Model](../../../crosscutting/security-model.md),
  [Scopes & Tenancy](../../../crosscutting/scopes-and-tenancy.md)
- End-user view (docs site): [Change, switch off, renew and delete links](../../../../site/src/content/docs/sharing/manage-links.mdx)
