# Feature: Sharing

Reaching people **outside** a scope with **one node and what lies below it**. Sharing *with* a registered veyq
user is what [groups / scope grants](../spaces/_index.md) already do; sharing's own job is the **public link** —
handing one node to anyone, including **anonymous visitors with no account**. A share is rooted at a **Memory**, a
**chapter** or a **folder** ([ADR 0080](../../decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)
decisions 6, 9 and 13): it rides that node's own content key ([ADR 0012](../../decisions/0012-content-key-hierarchy.md);
every node carries one since ADR 0080 decision 1) and adds an **independent second wrap** of it, so it exposes **neither the
rest of the scope (`K_SC`) nor anything beside or above the shared node** — least-privilege is crypto-enforced, and
the server stays blind. Which roles a root kind takes and what each lets a guest do (a folder takes `READ` and
`READ_WRITE`, never `WRITE_ONLY`; a folder contributor organises inside it and adds photos to the Memories below) is
the role matrix in [`docs/apps/memories.md`](../../apps/memories.md#what-a-guest-may-do--the-role-matrix). When a
member renames a shared node, their client re-seals the name of every share on it (ADR 0080 decision 14).

- **Cross-cutting:** [Security Model](../../crosscutting/security-model.md) (key hierarchy + the client-integrity
  trust tier + the T&S "decryptable-on-report" corollary for public shares),
  [Scopes & Tenancy](../../crosscutting/scopes-and-tenancy.md).
- **Decisions:** [ADR 0029](../../decisions/0029-public-viewer-share.md) (the public viewer share — the sealed
  per-share key + the anonymous access path), [ADR 0012](../../decisions/0012-content-key-hierarchy.md) (the
  `symWrap(K_share, K_mem)` share keying), [ADR 0015](../../decisions/0015-operator-blind-readable-urls.md) (the
  key in the URL fragment), [ADR 0013](../../decisions/0013-blob-storage-presigned-direct-to-store.md) (gated
  presigned blob reads), [ADR 0026](../../decisions/0026-client-trust-tiers.md) (why an operator-served no-install
  viewer is passive-safe, not operator-proof).

## Use-cases

- [uc.sharing.share-public-viewer](use-cases/uc.sharing.share-public-viewer.md) — **built (web)** (cr-0100: publish
  one of your own Memories as a read-only, anonymous, revocable public link `/s/{shareId}#k=…`; the share key rides
  the fragment, the server stays blind; v1 = **personal-scope Memories, READ only**)
- [uc.sharing.share-contributor](use-cases/uc.sharing.share-contributor.md) — **built (web)** (cr-0101: a
  **READ_WRITE** link where an anonymous visitor can **view + add photos** after naming themselves — the name
  **sealed under `K_mem`** → an anonymous share identity, the buckets hook; direct-append, personal-scope only;
  [ADR 0030](../../decisions/0030-contributor-public-share.md))
- [uc.sharing.share-uploader](use-cases/uc.sharing.share-uploader.md) — **built (web)** (cr-0102: a **WRITE_ONLY**
  write-only drop-box — add photos but **cannot see** the Memory or others'; the resolve returns no media (the
  `canView` gate, no server-side gallery filtering), the own-view is **client-local, session-only** from local
  previews (never a download), delete-your-own is **token-gated + ownership-checked**, and the ephemerality is
  warned in four places; personal-scope only; [ADR 0031](../../decisions/0031-uploader-public-share.md))
- [uc.sharing.share-shared-scope](use-cases/uc.sharing.share-shared-scope.md) — **built (web)** (cr-0104: lifts
  the personal-scope-only restriction so **any member** of a **group/tenant** scope may public-share a Memory (since
  ADR 0080 also a chapter or a folder) there,
  across all three roles; **member removal soft-revokes** the removed member's shares, **re-activation transfers
  ownership**; `K_mem` is stable across rotation so the share follows new content and the server-side deactivation
  is the honest-server revocation; [ADR 0029](../../decisions/0029-public-viewer-share.md) §6)

- [uc.sharing.share-note](use-cases/uc.sharing.share-note.md) — **built (web, cr-0115 + cr-0116)** (an optional
  **editable curator note** on a share, sealed under `K_share` + rendered (markdown) on the share page; **editable
  in place** via a member-recoverable share key `sealed_share_key` (`K_share` under `K_SC(epoch)`, which no link
  carries — ADR 0036 point 4) + an owner-gated
  `updateShare` — the link stays valid. **cr-0116** made the note **per-locale** (`{de,en}`, viewer picks locale),
  added an optional **private share name** (`sealed_share_name`, management-only), and **redesigned the dialogs**
  — list-only manage + separate create, copy-only links, with the **uploader as its own
  capability-gated memory action + dialog** (the same `ShareDialog`/`ShareCreateDialog` reused via an `uploader`
  flag — identical by construction, its only difference the absent role switcher). The anonymous acceptance gate is
  its own use-case now ([uc.sharing.share-accept](use-cases/uc.sharing.share-accept.md)), not the curator note;
  [ADR 0036](../../decisions/0036-editable-share-note-recoverable-share-key.md))

- [uc.sharing.share-password](use-cases/uc.sharing.share-password.md) — **built (web, cr-0118)** (an optional
  **password** on a share so a leaked link alone isn't enough — an **operator-blind access-control gate** (not a
  crypto gate): the content stays E2EE under the unchanged `K_share` fragment, and the password gates **what the
  server serves**, reusing the [contributor-token](use-cases/uc.sharing.share-contributor.md) shape. The client
  sends `Argon2id(pw)` (never the raw password); the server stores a verifier + salt + a `K_share`-sealed copy the
  curator can **reveal**; a locked resolve serves only the password challenge. **Set / change / remove in place**,
  same link; all roles. Honest limit: defends a leaked link against an **honest** operator, not a malicious one —
  stated plainly; [ADR 0037](../../decisions/0037-password-protected-shares.md))

- [uc.sharing.share-link-storage-limit](use-cases/uc.sharing.share-link-storage-limit.md) — **built** (every
  READ_WRITE and WRITE_ONLY link stores at most the tenant owner's per-link limit, 10 GiB until changed; a `start`
  that does not fit is refused whole, and the visitor learns only that the link is full;
  [ADR 0073](../../decisions/0073-storage-quota-belongs-to-whoever-pays-for-the-store.md))

- [uc.sharing.share-accept](use-cases/uc.sharing.share-accept.md) — **built (contracts + backend + web,
  cr-anonymous-accept-gate)** (the
  accountless-visitor half of the two-tier terms gate, [ADR 0035](../../decisions/0035-server-terms-of-service.md)
  §3: before viewing/contributing, an account-less share visitor **accepts the operator's tenant terms**, stored
  **client-side** (localStorage, per tenant + version) — the server records nothing, anonymity preserved. The terms
  reach the accountless client from the **share** (not the member endpoint, not the Host — a share is
  Host-independent): a guest **`GET /api/shares/{shareId}/terms`** keyed on the non-enumerable `shareId`, so **no
  `tenantId` existence oracle**. On the share page the gate sits **password → terms → name → content**, and it
  **fails open** on a fetch error — an operational gate, not enforcement, honest about being bypassable)

## Deferred (later slices, each with its own dependency)

- **Buckets** — the per-uploader grouping (each contributor's uploads under their sealed name) is now refined as
  [uc.memory.buckets](../memory/use-cases/uc.memory.buckets.md) +
  [en.memory.buckets](../memory/enablers/en.memory.buckets.md)
  ([ADR 0034](../../decisions/0034-media-uploader-buckets.md)) — the contributor identity becomes the
  `share:{shareIdentityId}` **`owner_key`**, read with no migration. **cr-0113 (done)** shipped the full bucket
  data layer + backend + the **web** organizer (the always-visible Beiträge tab, sub-bucket create/rename/delete,
  move + batch, virtual-primary); a **camera filter** stays a later slice.
- **MANAGER role + moderation + persistent uploader deletes** — a manager role with view-all + edit/delete;
  **moderation** (review-before-publish) over the direct-append default; and persisting an uploader's id list
  client-side for **post-reload delete** (still no download).
- **Password-protected shares** — fold `Argon2id(passphrase)` into the wrap key for link-leak defense.
- **Persisted shares / person-resolution** — cross-scope surfaces. **Tenant terms** are built
  ([ADR 0035](../../decisions/0035-server-terms-of-service.md); the operator sets versioned per-locale terms in
  [uc.admin.tenant-terms](../admin/use-cases/uc.admin.tenant-terms.md), members accept server-side in
  [uc.account.accept-terms](../account/use-cases/uc.account.accept-terms.md)), and the accountless-visitor
  acceptance is now its own refined use-case
  ([uc.sharing.share-accept](use-cases/uc.sharing.share-accept.md), above). What stays deferred on terms is only the
  **pre-auth login-page** terms surface (a Host-served public read — a different mechanism from the share-keyed
  guest read).
- **A foreign-operator-proof no-install viewer** — needs the future trusted-web client (SaaS-era).
