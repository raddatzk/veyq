# ADR 0036 — A curator note on a share, editable in place via a member-recoverable share key

- Status: Accepted
- Date: 2026-07-10

## Context

A curator turns a Memory into a share link (`/s/{shareId}#k=<K_share>`, [ADR 0029](0029-public-viewer-share.md)).
Today a share is **write-once**: the API is `createShare` / `revokeShare` / `reactivateShare` — there is **no
update operation**, and every sealed field (`sealedName` = the Memory name re-sealed under **`K_share`**, AAD
`node-name`) is fixed at mint. `K_share` is minted client-side (`randomBytes(32)`), shown to the curator **once**
in the copyable link, and **never stored server-side** — so after minting, the curator no longer holds `K_share`
for that share and the share-management list cannot even *show* a minted share's sealed content (only the fresh
link does).

We want to add a **curator note** — a short message/instruction the sharer attaches to a share, shown on the
share page to whoever opens the link (a READ greeting "here are our vacation photos", a WRITE_ONLY instruction
"please upload your pics here"). It must be **operator-blind** (E2EE), **per-share**, one-way (curator → viewer),
and — the product requirement — **editable after the share is created without invalidating the link**.

That last requirement collides with two facts: (1) the natural per-share key is `K_share` (it mirrors `sealedName`
exactly and binds the note to *this* link), but (2) `K_share` isn't retained, so a `K_share`-sealed field can't be
re-sealed later. Sealing under `K_mem` instead would be editable (the curator always re-derives `K_mem` from their
scope) but keys the note to the *Memory*, not the share, and still leaves every other sealed field unshowable in
the management list.

## Decision

Make the share key **recoverable by the curator**, then seal the note under `K_share` and add an update op. Three
parts.

1. **Recoverable share key (the enabler).** At `createShare`, in addition to the existing outer wrap
   `wrap = symWrap(K_share, K_mem)` (lets a link-holder unwrap `K_mem`), store a recovery copy of `K_share`
   **`sealed_share_key = seal(share-key-recovery, K_SC(epoch), K_share)`** on the share row — sealed under the
   scope key at the scope's current write epoch, bound to (scope, Memory, share, epoch)
   ([ADR 0072](0072-a-seal-binds-its-object.md)), with that epoch stored beside it (`sealed_share_key_epoch`).
   Every **scope member** holds the epoch chain, so the curator and every co-member can **recover `K_share`
   anytime** and thereby read or re-seal **any `K_share`-sealed field** of a minted share. The server stores only
   this ciphertext; it never sees `K_share` in the clear (it lacks `K_SC`). Why `K_SC` and not `K_mem`: point 4.

2. **The curator note.** Add **`sealed_note`** to the share — the note text sealed under **`K_share`** with a
   dedicated AAD context **`share-note`** (mirrors `sealName`/`openName` byte-for-byte, just a distinct context).
   It is **optional** (a share with no note shows no banner), set at `createShare`, and returned by the anonymous
   `resolveShare` (the share page unwraps it with the fragment `K_share`). It is **rendered as markdown** on the
   share page — reusing the safe escape-first renderer added for tenant terms (`web/src/shared/markdown.ts`,
   [ADR 0035](0035-server-terms-of-service.md) follow-up). For a WRITE_ONLY share (no gallery) it is the primary
   instruction above the name field; for READ/READ_WRITE it is a banner under the title.

3. **In-place edit.** Add an **owner-gated `updateShare`** (`PATCH /api/shares/{shareId}`, `@Authenticated`,
   `owner_account`-checked, and only for an owner who is still a member of the share's scope —
   [uc.spaces.scope-access](../features/spaces/use-cases/uc.spaces.scope-access.md)) that accepts a new **`sealedNote`**. The curator, editing from the Memory's share
   dialog (which holds the scope's epoch keys), lists their shares (the list now also returns `sealed_note` +
   `sealed_share_key` with its epoch),
   recovers `K_share`, re-seals the new note, and PATCHes it — **the link stays valid**. The op is deliberately
   extensible: cr-0116 added the private **`sealedShareName`** (both sealed fields are a full replace), and
   **cr-0119** added an optional **`role`** — the first *grant-affecting* field, a **pure server-side authorization
   change** (no re-crypto: READ and READ_WRITE share one `symWrap(K_share, K_mem)`, so the wrap/`K_share`/link
   are untouched). The role change is restricted to **READ ↔ READ_WRITE** (a `422 invalid_role_change` rejects
   setting `role = WRITE_ONLY` or changing a WRITE_ONLY share's role — a write-only drop-box's "cannot see" promise
   can't be honestly flipped on already-distributed links, and a WRITE_ONLY share carries the Memory's drop public
   key instead of that wrap, [ADR 0031](0031-uploader-public-share.md) point 7). `role` is omitted for a plain note/name edit.

4. **The recovery copy is for the Memory's members only — never for a link.** A READ or READ_WRITE link
   carries `K_mem` (it is what the link exists to hand over), so a recovery copy sealed under `K_mem` would let
   that link's holder, with a database dump, open **every other share of the same Memory**: its `K_share`, and
   through it the share's password in plain text ([ADR 0037](0037-password-protected-shares.md)), its private
   label, its note, and — the dump holds every share id in clear — the whole link. `K_SC` is the key no share
   carries ([ADR 0012](0012-content-key-hierarchy.md)), so sealing under it closes all four at once and keeps
   everything a member does today: reveal, edit, copy link.
   - **The epoch is the scope's current one.** The server refuses a create whose `sealedShareKeyEpoch` is not the
     scope's current epoch (`409 stale_epoch`, nothing written — [ADR 0024](0024-scope-key-rotation.md)
     Decision 6); the client re-opens its scope key and retries once. A key without an epoch, or an epoch without
     a key, is a `400`. `updateShare` and re-activation do not touch the copy, so they need no epoch.
   - **A copy that does not open degrades to "no label, no note, no password, no link"** in the manage list; it is
     not a refusal. Its only honest producer is a share minted before this decision on an instance that was not
     reset, and that share's guests are unaffected.
   - **The residual, stated.** `K_share` does not change for the life of a link, and every member can rebuild
     every link of a Memory from its copy — so a member removed from a group may hold every link that existed on
     the group's Memories while they belonged, and uses one with no dump at all (with a dump they also read its
     later password, label or note edits). Re-sealing the copy under a new epoch on rotation would not help;
     cutting such a member off an existing link means **re-issuing** it — a new id and a new `K_share`
     ([ADR 0075](0075-a-link-a-departed-member-could-copy-is-reissued.md)). Shares minted after the removal are
     out of their reach. The Memory's content under `K_mem` stays the class of `K_mem` not rotating on removal
     (ADR 0012 *Rotation / revocation*).
   - **Rejected alternatives.** Storing only the password verifier (drop the reveal) closes the password, even
     against members, but leaves the label, the note and the link; sealing only the password under `K_SC` costs
     the same machinery and closes the same one field. Either can be layered on this one; neither replaces it.
     Folding the password into the key (the crypto-gate [ADR 0037](0037-password-protected-shares.md) defers)
     answers a different threat and needs a new link format.

**Roles + scope.** All three roles (READ / READ_WRITE / WRITE_ONLY) carry a note, in every scope a share can
live in — personal, group or tenant ([ADR 0029](0029-public-viewer-share.md) §6).

## Operator-blindness

Unaffected. The server gains one new opaque column, `sealed_share_key = seal(share-key-recovery, K_SC(epoch),
K_share)`, the plain epoch it is sealed at, plus the opaque `sealed_note`. It holds **only ciphertext**: `K_share`
is never stored in the clear, and `sealed_share_key` is openable **only with `K_SC`** — which neither the operator
nor any share link holds (point 4). The note text is E2EE like any content; the
server sees its size and existence, never its plaintext. `updateShare` swaps one ciphertext for another — no key
crosses the boundary.

## Consequences

- **`K_share` becomes derivable by every scope member** (a conscious relaxation). Before, only the link-holder
  had `K_share`; now a **scope member** (who holds `K_SC`) can recover `K_share` for any share of that Memory —
  e.g. reconstruct a co-member's link. This grants **nothing new in practice**: a scope member already decrypts
  the Memory's content, and **revoke stays `owner_account`-gated** (a member can't revoke another's share; an
  owner who has left the scope can't either). It is **not** "every `K_mem`-holder": a READ or READ_WRITE link
  holds `K_mem` too, and it recovers nothing (point 4). Recorded in the
  [security model](../crosscutting/security-model.md).
- **Fixes a real limitation** (a bonus): the share-management list can now **show and edit every minted share's
  sealed content** (recover `K_share` through the scope's epoch keys), not just the one fresh link — closing the
  gap noted in the current `ShareDialog`.
- **Shares stop being strictly write-once.** `updateShare` introduces controlled mutability into the capability;
  it is owner-gated, swaps ciphertext for ciphertext, and doesn't touch `K_share`/`K_mem`/`role`/the wrap — the
  capability's *identity and grant* stay immutable, only its curator-authored decoration changes. The shared root's
  own `sealedName` is the one sealed field outside this gate: when any member renames the root, that member re-seals
  it through this recovery copy with `setShareSealedName`, which every member with write access may call
  ([ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md) Decision 14).

## Alternatives considered

- **(A) `K_share`, note immutable (edit = revoke + re-share).** Cleanest and zero new crypto, consistent with the
  write-once model — but a note typo means a **new link** you must redistribute. Rejected: poor UX for the exact
  thing (a message) people tweak, and it leaves the management list unable to show minted shares' content.
- **(B) `K_mem`-sealed note + `updateShare`.** Editable (curator always has `K_mem`), but the note is keyed to the
  **Memory**, not the share (weaker per-share binding), and it does **not** make other `K_share`-sealed fields
  (name, future settings) recoverable/editable — so the management-visibility gap remains. Rejected: solves less
  for the same new op.
- **(C, chosen) recoverable `K_share` + `K_share`-sealed note + `updateShare`.** Per-share binding **and** durable
  in-place editing **and** it generalizes (every `K_share`-sealed field becomes curator-editable), for the cost of
  one extra opaque column and its epoch. The members' derivability of `K_share` is the only price, and it is
  harmless as above — as long as the recovery key is one no link carries (point 4).

## Related

- [ADR 0029](0029-public-viewer-share.md) (the share model + `K_share` in the fragment), [ADR 0030](0030-contributor-public-share.md)
  (READ_WRITE + the `K_mem`-sealed display name — a *different* sealed field), [ADR 0031](0031-uploader-public-share.md)
  (WRITE_ONLY write-only), [ADR 0012](0012-content-key-hierarchy.md) (`K_SC`/`K_mem` hierarchy).
- Use-case: [uc.sharing.share-note](../features/sharing/use-cases/uc.sharing.share-note.md).
- [security-model](../crosscutting/security-model.md) (the new `sealed_share_key` operational-metadata line + the
  `K_share`-derivability note), [ADR 0035](0035-server-terms-of-service.md) (the reused markdown renderer).
