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
personal has no removal ([ADR 0025](0025-forward-secrecy-policy-per-scope.md)).

## Decision

1. **Re-issue: a new link, the old one off, in one request.** `POST /api/shares/{shareId}/reissue` creates a new
   share with a fresh id and a fresh `K_share` and turns the old one off (`enabled = false`) in the **same
   transaction**; any refusal writes nothing. The server takes the Memory, the scope and the **role** from the old
   row, so a re-issue cannot move a link or flip it into or out of UPLOADER. It requires the recovery copy at the
   scope's current epoch (`409 stale_epoch`, [ADR 0024](0024-scope-key-rotation.md) Decision 6) and the capability
   for the role ([ADR 0032](0032-capability-discovery-version-gating.md)). An **enabled** share is re-issued only
   by its owner (as revoke); a **disabled** one by any member, who becomes the new share's owner (as re-activation
   adopts). A share the caller may not re-issue is the same non-enumerable `404` as an unknown one.
2. **What a re-issue keeps.** The client opens the old share through its recovery copy and re-seals under the new
   `K_share`: the **note, private label, expiry and password** carry over (the password with a fresh salt), and
   the curator may change any of them except the role. An UPLOADER link keeps the Memory's **existing drop key**
   ([ADR 0031](0031-uploader-public-share.md) point 7) — every upload so far is sealed to it. **Guest identities do
   not carry over:** they hang off the share id, so photos and names stay in the Memory, readable by members and
   the new link, while a returning guest names themself again and can no longer delete their earlier uploads. The
   per-link storage budget ([ADR 0073](0073-storage-quota-belongs-to-whoever-pays-for-the-store.md) §4) starts at
   zero — no new power, since a member can already mint a fresh link.
3. **The old link 404s, with no redirect.** It answers exactly as a revoked one. A redirect from the old id would
   hand the new id to the very person the re-issue is for; the curator hands out the new link.
4. **Re-activation refuses a link a departed member may hold** — `409 reissue_required` when the share's owner is
   no longer a member of its scope, **or** its recovery copy is sealed below the scope's current epoch. A share
   with no recovery copy is judged by the first test alone: no member could have rebuilt its link. The UI offers a
   re-issue there instead. This supersedes ADR 0029 §6's "any remaining member may re-activate it". The epoch half
   has one false positive: a key rotation after a compromise
   ([ADR 0060](0060-key-rotation-after-compromise.md) §5) moves the epoch of every scope the account is in, so the
   panel's hint reads "made before the group's key last changed", not "before someone left".
5. **A group removal can turn every link off.** The removal-rotation takes an opt-in `revokeLinks`, valid only with
   `removesUserId` (`400` otherwise), unchecked by default in the removal confirmation. In the removal's own
   transaction it turns off every enabled share of the scope; each share's owner then re-issues the ones they still
   want. A forced switch-off on every removal was rejected — it breaks every guest's link even on an amicable
   removal; offering only a re-issue was rejected because revoke is owner-gated, so the remover could not act on a
   co-member's link. The group-owner power this adds is dominated by one they hold already: deleting the group
   turns off every link in it.
6. **Tenant scopes** are covered only by Decisions 1 and 4: re-issue works there, and a removed tenant member's own
   links cannot be re-activated. A tenant removal does not rotate the scope (ADR 0025), so the epoch half never
   fires on one; a tenant-side switch-off is a separate decision.

## Consequences

- **What this buys is honest-server only.** Revocation is layer 1: a malicious operator can keep serving a revoked
  id. Against a dump, a new `K_share` keeps only the *new* share's later password, label and note edits out of a
  departed member's reach; the Memory's content under `K_mem` stays the ADR 0012 class.
- **The operator learns nothing new.** A re-issue is a create plus `enabled = false`, and `enabled` is already
  plain.
- **A re-issue breaks a link for its guests by design.** The product copy says so: the old link stops working,
  hand out the new one, guests keep their photos but give their name again.
- That curators actually redistribute the new link is product copy, not something any test can prove.
