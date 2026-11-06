# Feature: Sharing

Reaching people **outside** a scope with **one node and what lies below it**. Sharing *with* a registered veyq
user is what [groups / scope grants](../spaces/_index.md) already do; sharing's own job is the **public link** —
handing one node to anyone, including **anonymous visitors with no account**. A link exposes **neither the rest of
the scope (`K_SC`) nor anything beside or above the shared node** — least-privilege is crypto-enforced, and the server
stays blind. How a link is stored, keyed, gated, re-keyed and ended is one model,
[en.sharing.public-link](enablers/en.sharing.public-link.md); which roles a node kind takes and what each lets a guest
do there is each application's role matrix ([Memories](../../apps/memories.md#what-a-guest-may-do--the-role-matrix)).

- **Cross-cutting:** [Security Model](../../crosscutting/security-model.md) (the client-integrity trust tier and the
  "decryptable-on-report" corollary for public links), [Scopes & Tenancy](../../crosscutting/scopes-and-tenancy.md)
  (who may do what), [keys, grants and trust](../../crosscutting/keys-grants-and-trust.md) (the chain a link hangs off).
- **Decisions:** the topic's ADRs are listed in the [ADR index](../../decisions/README.md#topics) under *Sharing:
  public links and guests*.

## Enablers

- ★ [en.sharing.public-link](enablers/en.sharing.public-link.md) — **built** (the share record, the key path per
  role, the gates, guest identities and the write proof, and the link's lifecycle including rotation, re-key,
  re-issue and deletion)

## Use-cases

- [uc.sharing.share-public-viewer](use-cases/uc.sharing.share-public-viewer.md) — **built** (a read-only, anonymous,
  revocable link to one node; [ADR 0029](../../decisions/0029-public-viewer-share.md))
- [uc.sharing.share-contributor](use-cases/uc.sharing.share-contributor.md) — **built** (a `READ_WRITE` link: an
  anonymous visitor views and adds after naming themselves; [ADR 0030](../../decisions/0030-contributor-public-share.md))
- [uc.sharing.share-uploader](use-cases/uc.sharing.share-uploader.md) — **built** (a `WRITE_ONLY` link: add photos
  without seeing the Memory or anyone else's; [ADR 0031](../../decisions/0031-uploader-public-share.md))
- [uc.sharing.share-shared-scope](use-cases/uc.sharing.share-shared-scope.md) — **built** (any member of a personal,
  group or tenant scope shares there; a removal switches off the removed member's links, and a link someone who left
  could hold comes back only by re-issue; [ADR 0029](../../decisions/0029-public-viewer-share.md) §6,
  [ADR 0075](../../decisions/0075-a-link-a-departed-member-could-copy-is-reissued.md))
- [uc.sharing.share-note](use-cases/uc.sharing.share-note.md) — **built** (an optional, per-locale curator note and a
  private label, editable in place without a new link; [ADR 0036](../../decisions/0036-editable-share-note-recoverable-share-key.md))
- [uc.sharing.share-password](use-cases/uc.sharing.share-password.md) — **built** (an optional password so a leaked
  link alone is not enough — an access-control gate, not a decryption factor;
  [ADR 0037](../../decisions/0037-password-protected-shares.md))
- [uc.sharing.share-link-storage-limit](use-cases/uc.sharing.share-link-storage-limit.md) — **built** (every
  `READ_WRITE` and `WRITE_ONLY` link stores at most the tenant owner's per-link limit;
  [ADR 0073](../../decisions/0073-storage-quota-belongs-to-whoever-pays-for-the-store.md))
- [uc.sharing.share-accept](use-cases/uc.sharing.share-accept.md) — **built** (an accountless visitor accepts the
  space's terms before viewing or contributing, recorded on the device only;
  [ADR 0035](../../decisions/0035-server-terms-of-service.md) §3)
- [uc.sharing.close-link-rekeys-subtree](use-cases/uc.sharing.close-link-rekeys-subtree.md) — **built** (closing a
  viewable link — switch-off, delete, re-issue, bulk or removal switch-off, end date — re-keys everything below it;
  [ADR 0114](../../decisions/0114-closing-a-link-re-keys-the-subtree-below-it.md))

- [uc.sharing.keep-a-received-link](use-cases/uc.sharing.keep-a-received-link.md) — **built** (a link someone sent,
  of this or another instance, kept as a bookmark node with its password, sealed; it opens and is reported where the
  share lives; [ADR 0120](../../decisions/0120-a-received-link-is-kept-as-a-bookmark-node-that-opens-and-reports-where-the-share-lives.md))

- [uc.sharing.browse-a-received-share-in-place](use-cases/uc.sharing.browse-a-received-share-in-place.md) — **planned**
  (a bookmarked share of any instance browsed and written in place;
  [ADR 0121](../../decisions/0121-a-received-share-is-browsed-in-place-through-its-own-api.md),
  `changes/cr-browse-a-received-share-in-place.md`)

## Deferred

- **Buckets** are built, as Memories' per-uploader grouping: [uc.memory.buckets](../memory/use-cases/uc.memory.buckets.md),
  [en.memory.buckets](../memory/enablers/en.memory.buckets.md). A camera filter stays a later slice.
- **A manager role and review before publish** — a guest role with edit and delete over others' uploads, and
  moderation of contributions before they show; and a client-side id list so an uploader can delete after a reload.
- **A password that keys the link** — folding `Argon2id(passphrase)` into the key, against a malicious operator
  ([ADR 0037](../../decisions/0037-password-protected-shares.md), alternative A).
- **A received link browsed in place** (inbox #829) and **kept in the account** (inbox #435); **person-resolution** and
  the **pre-auth login-page terms** surface.
- **A foreign-operator-proof no-install viewer** — needs the future trusted-web client.
