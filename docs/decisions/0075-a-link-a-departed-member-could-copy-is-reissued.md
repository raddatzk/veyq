# ADR 0075 — A link a departed member could copy is re-issued, never revived

- Status: Accepted
- Date: 2026-10-01

## Context

A public share link is a bearer capability: `/s/{shareId}#k=K_share`. `K_mem` never re-keys
([ADR 0012](0012-content-key-hierarchy.md) *Rotation / revocation*), so a scope-key rotation does not touch a link,
and a link keeps showing photos added after a removal ([ADR 0029](0029-public-viewer-share.md) Consequences).

Who may hold a link is wider than "the people it was handed to":

- **Every member can rebuild every link of a Memory.** Any member lists every share on a Memory, and each share's
  recovery copy of `K_share` is sealed under the scope key ([ADR 0036](0036-editable-share-note-recoverable-share-key.md)
  point 4), so the manage panel rebuilds the full link of each one, a co-member's included, and offers it for
  copying. A member removed from a group may therefore hold every link that existed on the group's Memories while
  they belonged — the server cannot know which ones they copied.
- **Using a link needs no dump.** Against an honest server the link alone works. A removed member who also holds a
  database dump already holds `K_mem` and reads the Memory whatever the link does; that is the ADR 0012 class and
  this decision does not move it. What is decided here is the **honest-server** cut.
- **Re-activation revived the removed member's own link.** A removal soft-revokes the removed member's shares
  (ADR 0029 §6), and re-activation by a remaining member turned the very same link — same id, same fragment — back
  on.

Shares exist in personal, group and tenant scopes (ADR 0029 §6). A group rotates on removal, the tenant does not,
personal has no removal ([ADR 0025](0025-forward-secrecy-policy-per-scope.md)). So the scope epoch is no removal
signal on its own: a tenant's epoch never moves on a removal — it moves only when a member's account key rotation
queues a rotation of every shared scope they belong to ([ADR 0060](0060-key-rotation-after-compromise.md) §5) — and a
group a tenant removal cuts the member from has its rotation only *queued* to another key-holder, so until a device
runs it that group's epoch has not moved either. A removed tenant member's reach is still a removed group member's:
every enabled link on every tenant-scope Memory, rebuildable from its recovery copy.

## Decision

1. **Re-issue: a new link, the old one deleted, in one request.** `POST /api/shares/{shareId}/reissue` creates a
   new share with a fresh id and a fresh `K_share` and deletes the old one — a tombstone, [ADR 0029](0029-public-viewer-share.md)
   §7 — in the **same transaction**; any refusal writes nothing. A replaced link has nothing left to come back for,
   and a tombstone is never re-activated, whoever left or stayed. The server takes the Memory, the scope and the **role** from the old
   row, so a re-issue cannot move a link or flip it into or out of WRITE_ONLY. It requires the recovery copy at the
   scope's current epoch (`409 stale_epoch`, [ADR 0024](0024-scope-key-rotation.md) Decision 6) and the capability
   for the role ([ADR 0032](0032-capability-discovery-version-gating.md)). An **enabled** share is re-issued only
   by its owner (as revoke); a **disabled** one by any member, who becomes the new share's owner (as re-activation
   adopts). A share the caller may not re-issue is the same non-enumerable `404` as an unknown one.
   **A share is re-issued at most once.** The old share is spent by a checked compare-and-set
   ([developer-styleguide §12](../crosscutting/developer-styleguide.md)): the transaction's first write sets its
   deletion where none is set yet, and the row count decides. Of two concurrent re-issues exactly one mints a link;
   the other — like a later re-issue of a share already re-issued or deleted — is `409 share_deleted`, nothing
   written, and the client re-reads the list, where the one new link is. Two links minted from one would leave the
   curator handing out one while the other stayed live and unknown. The conflict is a member's answer: a non-member
   gets the `404`, so a tombstone stays as non-enumerable as an unknown id. The old row is read only after the
   spend, under the row lock that write holds, so the role the new share takes is the one the tombstone replaced.
2. **What a re-issue keeps.** The client opens the old share through its recovery copy and re-seals under the new
   `K_share`: the **note, private label, expiry and password** carry over (the password with a fresh salt), and
   the curator may change any of them except the role. A WRITE_ONLY link keeps the Memory's **existing drop key**
   ([ADR 0031](0031-uploader-public-share.md) point 7) — every upload so far is sealed to it. **Guest identities do
   not carry over:** they hang off the share id, so photos and names stay in the Memory, readable by members and
   the new link, while a returning guest names themself again and can no longer delete their earlier uploads. The
   per-link storage budget ([ADR 0073](0073-storage-quota-belongs-to-whoever-pays-for-the-store.md) §4) starts at
   zero — no new power, since a member can already mint a fresh link.
3. **The old link 404s, with no redirect.** It answers exactly as a revoked one, and leaves the manage list. A redirect from the old id would
   hand the new id to the very person the re-issue is for; the curator hands out the new link.
4. **Re-activation refuses a link a departed member may hold** — `409 reissue_required` — when any one of these
   holds, as one pure predicate (`ReissuePolicy`):
   - the share was turned off by a **bulk switch-off** (point 5's `reissue_only` mark);
   - its **owner** is no longer a member of its scope — they hold their own link;
   - its recovery copy is sealed **below the scope's current epoch**, or the scope has lost an accepted member
     **since the share was minted** — every member at that time could rebuild the link from the copy, one removed
     since included.

   The last test needs a removal signal that holds on every scope kind, so each scope carries a **removal counter**
   (`member_removals`), bumped in the transaction of every removal of an accepted member — the group's
   removal-rotation and the tenant cascade, for the TENANT scope and for each GROUP it cuts — and each share
   stamps the counter it was minted under (`member_removals_at_mint`). Rotating the tenant on removal instead was
   rejected by ADR 0025 (`O(N)` on the primary scope); comparing a share's `createdAt` with a scope's last removal
   was rejected because two replicas stamp with their own clocks, misfiling a share made seconds around a removal.
   The counter is exact and one integer, and it tells the operator nothing the membership deletes did not.
   The epoch test stays beside it: its one "false positive", an account key rotation after a compromise, is a case
   where a stolen device could list and rebuild every link as well as a departed member could.

   A share with **no** recovery copy is judged by the first two tests alone: no other member could have rebuilt its
   link. The server computes the predicate once per share for both readers — the refusal and the manage list's
   `reissueRequired` flag — so the panel's mark and the refusal cannot disagree; the client no longer compares
   epochs itself. The panel offers a re-issue where it is set, and its hint names both causes: *made before someone
   left this space or replaced their keys*. This supersedes ADR 0029 §6's "any remaining member may re-activate it".
5. **Turning off every link of a scope sticks.** Every bulk switch-off goes through one store body: it sets
   `enabled = false` on every enabled share of the scope **and** the per-row mark `reissue_only` on **every** share
   of it, and each comes back only by re-issue (a re-issue mints a new row, so the mark never travels). A switch-off
   says *a link may have leaked*, and a link with no recovery copy leaks like any other — without the mark, the
   removal counter would let any member re-activate such a link with one click after a switch-off that removed
   nobody. A link its owner had turned off just before is marked too: it may have leaked as well, and "turn off
   every link" means every link. The count it answers is still the links it turned off. A mark on the rows it
   writes is exact and needs no event model; a share minted afterwards is untouched by construction.
   Three paths reach it, each unchecked by default behind a confirmation that names the effect:
   - a **group removal**: the removal-rotation's opt-in `revokeLinks`, valid only with `removesUserId` (`400`
     otherwise), in the removal's own transaction;
   - a **tenant removal**: the same `revokeLinks`, as a query flag on `removeTenantMember` (a `DELETE` carries no
     body), in the cascade's transaction and nothing written if the removal refuses. It reaches the **TENANT scope's**
     shares only: the tenant owner need not be a member of a co-shared group and cannot see its links; there the
     removal counter marks every link at once and the group's owners re-issue;
   - a **standalone switch-off** by any **owner of the scope** — group owners, tenant owners, the personal scope's
     owner, one gate (`isScopeOwner`) — through `revokeScopeShares`, which answers how many links it turned off. The
     web offers it in the group and tenant panels, not in the personal space, where no one else can revive a link
     and a per-link revoke already does everything it would.

   A forced switch-off on every removal was rejected — it breaks every guest's link even on an amicable removal;
   offering only a re-issue was rejected because revoke is owner-gated, so the remover could not act on a
   co-member's link. The scope-owner power this adds is dominated by one a group owner holds already: deleting the
   group turns off every link in it.

## Consequences

- **What this buys is honest-server only.** Revocation is layer 1: a malicious operator can keep serving a revoked
  id. Against a dump, a new `K_share` keeps only the *new* share's later password, label and note edits out of a
  departed member's reach; the Memory's content under `K_mem` stays the ADR 0012 class.
- **The operator learns nothing new.** A re-issue is a create plus a tombstone of the old row, whose deletion the
  operator serves anyway; the removal counter counts membership deletes the operator already performs, and `reissue_only` records a
  switch-off it already served.
- **A re-issue breaks a link for its guests by design.** The product copy says so: the old link stops working,
  hand out the new one, guests keep their photos but give their name again.
- That curators actually redistribute the new link is product copy, not something any test can prove.
