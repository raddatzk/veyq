# ADR 0036 — A curator note on a share, editable in place via a K_mem-recoverable share key

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
share page to whoever opens the link (a VIEWER greeting "here are our vacation photos", an UPLOADER instruction
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
   `wrap = symWrap(K_share, K_mem)` (lets a link-holder unwrap `K_mem`), store a second, **inverse** wrap
   **`sealed_share_key = symWrap(K_mem, K_share)`** (AAD context **`share-key-recovery`**, bound to the share's
   id — [ADR 0072](0072-a-seal-binds-its-object.md)) on the share row. The
   **curator always holds `K_mem`** (they opened the Memory from their scope), so they can **recover `K_share`
   anytime** — `K_share = symUnwrap(K_mem, sealed_share_key, "share-key-recovery")` — and thereby read or re-seal
   **any `K_share`-sealed field** of a minted share. The server stores only this ciphertext; it never sees
   `K_share` in the clear (it lacks `K_mem`).

2. **The curator note.** Add **`sealed_note`** to the share — the note text sealed under **`K_share`** with a
   dedicated AAD context **`share-note`** (mirrors `sealName`/`openName` byte-for-byte, just a distinct context).
   It is **optional** (a share with no note shows no banner), set at `createShare`, and returned by the anonymous
   `resolveShare` (the share page unwraps it with the fragment `K_share`). It is **rendered as markdown** on the
   share page — reusing the safe escape-first renderer added for tenant terms (`web/src/shared/markdown.ts`,
   [ADR 0035](0035-server-terms-of-service.md) follow-up). For an UPLOADER share (no gallery) it is the primary
   instruction above the name field; for VIEWER/CONTRIBUTOR it is a banner under the title.

3. **In-place edit.** Add an **owner-gated `updateShare`** (`PATCH /api/shares/{shareId}`, `@Authenticated`,
   `owner_account`-checked, and only for an owner who is still a member of the share's scope —
   [uc.spaces.scope-access](../features/spaces/use-cases/uc.spaces.scope-access.md)) that accepts a new **`sealedNote`**. The curator, editing from the Memory's share
   dialog (which holds `K_mem`), lists their shares (the list now also returns `sealed_note` + `sealed_share_key`),
   recovers `K_share`, re-seals the new note, and PATCHes it — **the link stays valid**. The op is deliberately
   extensible: cr-0116 added the private **`sealedShareName`** (both sealed fields are a full replace), and
   **cr-0119** added an optional **`role`** — the first *grant-affecting* field, a **pure server-side authorization
   change** (no re-crypto: VIEWER and CONTRIBUTOR share one `symWrap(K_share, K_mem)`, so the wrap/`K_share`/link
   are untouched). The role change is restricted to **VIEWER ↔ CONTRIBUTOR** (a `422 invalid_role_change` rejects
   setting `role = UPLOADER` or changing an UPLOADER share's role — a write-only drop-box's "cannot see" promise
   can't be honestly flipped on already-distributed links, and an UPLOADER share carries the Memory's drop public
   key instead of that wrap, [ADR 0031](0031-uploader-public-share.md) point 7). `role` is omitted for a plain note/name edit.

**Roles + scope.** All three roles (VIEWER / CONTRIBUTOR / UPLOADER) carry a note. **v1 = personal-scope shares**
(the current share cut); the note rides the group/tenant lift with no extra work.

## Operator-blindness

Unaffected. The server gains one new opaque column, `sealed_share_key = symWrap(K_mem, K_share)`, plus the opaque
`sealed_note`. It holds **only ciphertext**: `K_share` is never stored in the clear, and `sealed_share_key` is
openable **only with `K_mem`**, which the operator does not have. The note text is E2EE like any content; the
server sees its size and existence, never its plaintext. `updateShare` swaps one ciphertext for another — no key
crosses the boundary.

## Consequences

- **`K_share` becomes derivable by any `K_mem`-holder** (a conscious relaxation). Before, only the link-holder
  had `K_share`; now a **scope member** (who holds `K_mem`) can recover `K_share` for any share of that Memory —
  e.g. reconstruct a co-member's link. This grants **nothing new in practice**: a `K_mem`-holder can already
  decrypt the Memory's content directly, and **revoke stays `owner_account`-gated** (a member can't revoke
  another's share; an owner who has left the scope can't either). In the **v1 personal-scope** cut the scope has a single member (the owner), so it is moot; in
  the group/tenant lift it is the same "co-members already see the content" posture the security model documents.
  Recorded in the [security model](../crosscutting/security-model.md) operational-metadata list.
- **Fixes a real limitation** (a bonus): the share-management list can now **show and edit every minted share's
  sealed content** (recover `K_share` via `K_mem`), not just the one fresh link — closing the gap noted in the
  current `ShareDialog`.
- **Shares stop being strictly write-once.** `updateShare` introduces controlled mutability into the capability;
  it is owner-gated, swaps ciphertext for ciphertext, and doesn't touch `K_share`/`K_mem`/`role`/the wrap — the
  capability's *identity and grant* stay immutable, only its curator-authored decoration changes.

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
  one extra opaque column. The `K_mem`-derivability of `K_share` is the only price, and it is harmless as above.

## Related

- [ADR 0029](0029-public-viewer-share.md) (the share model + `K_share` in the fragment), [ADR 0030](0030-contributor-public-share.md)
  (CONTRIBUTOR + the `K_mem`-sealed display name — a *different* sealed field), [ADR 0031](0031-uploader-public-share.md)
  (UPLOADER write-only), [ADR 0012](0012-content-key-hierarchy.md) (`K_SC`/`K_mem` hierarchy).
- Use-case: [uc.sharing.share-note](../features/sharing/use-cases/uc.sharing.share-note.md).
- [security-model](../crosscutting/security-model.md) (the new `sealed_share_key` operational-metadata line + the
  `K_share`-derivability note), [ADR 0035](0035-server-terms-of-service.md) (the reused markdown renderer).
