# ADR 0073 — A storage quota belongs to whoever pays for the store

- Status: Accepted
- Date: 2026-09-24

## Context

Nothing bounds how much a tenant, a member or a share link stores. `veyq.blob.max-bytes` bounds **a file**, never
a total ([ADR 0013 §3](0013-blob-storage-presigned-direct-to-store.md)). Contributor and uploader links
([ADR 0030](0030-contributor-public-share.md), [ADR 0031](0031-uploader-public-share.md)) are anonymous and often
shared widely, so anyone holding one can fill the instance's store until the disk runs out.

What already exists, measured at refine on `main` @ `a494436`:

- **One door into the store.** `MediaHandler.startOne` is the only caller of `presignPut`
  (`grep -rn presignPut backend/src/main/kotlin`). Every stored object starts as a `veyq_upload_session` row with
  a declared size.
- **Every stored object has a row that reaches its tenant.** Originals, derivatives and covers each get a
  `veyq_blob` (`scope_id` → `veyq_scope.tenant_id`). A blob row is deleted in the same transaction that writes its
  reclaim tombstone ([ADR 0062](0062-store-object-reclaim-is-a-durable-work-list.md)).
- **What a link stored is on its media.** `veyq_media.share_identity_id` names the contributing identity, and
  derivatives hang off their media.
- **Sizes are server-visible by design.** [security-model](../crosscutting/security-model.md) § What is encrypted
  vs. server-visible lists them, so that the server can "authorize and bill" without decrypting. A quota computed
  from them adds nothing the operator does not already see.

Who pays for the store differs by deployment. Today there is one store connection, the instance's
([ADR 0067](0067-store-provisioning-from-a-declared-type.md)). A SaaS tenant that brings its own bucket
(`docs/inbox.md` #41) would pay its own storage bill.

## Decision

1. **The ceiling belongs to whoever pays for the store connection that a tenant's objects go to.** It is not tied
   to a role.
   - **The instance's store** (the only kind today): the Server Owner sets each tenant's **quota**, through the
     admin channel ([ADR 0065](0065-admin-ui-cli-separate-service.md)). The Tenant Owner distributes below it and
     can never raise it. This holds structurally, not by validation: only a Server-Owner operation writes the
     quota, and every `start` checks it independently of every limit the Tenant Owner sets.
   - **A tenant on its own bucket** pays its own bill, so its ceiling is the Tenant Owner's to set, or absent.
     This is not built. The quota lives on the tenant row, and #41 decides who writes it once a tenant can have a
     connection of its own.
   - **Default: no quota.** A tenant has none until the Server Owner sets one. On a self-hosted family instance a
     ceiling nobody chose is a bug, and the disk is the limit. The anonymous path is closed by the link default
     (4), not by this one.

2. **What counts is every object in the store that can be attributed to the tenant.**
   - Every `veyq_blob` in the tenant's scopes: originals, derivatives and covers. Derivatives count because the
     client produces them, so an uploader decides their size.
   - Every open upload session in those scopes, at its declared size. That is a **reservation**.
   - Trashed media, trashed nodes, and scopes in their deletion grace keep counting until they are purged. The
     bytes are still in the store, and if trash did not count, delete → re-upload → restore would overshoot the
     quota.
   - **Not counted:**
     - Reclaim tombstones. They carry no scope by design (ADR 0062 §6), so they cannot be attributed, and the
       sweep's lag bounds them.
     - Material kept in the database: icons, share notes, job results, identity pins. It is not in the store, and
       the contract bounds each field.
   - The size is the declared `byteSize`. `complete` admits a blob only when every chunk is present at exactly
     its derived length (ADR 0013 §3), so the declared size is the stored size.

3. **Usage is derived at the check, never counted.** It is a SUM over those rows, taken when a `start` is checked.
   There is no maintained counter. Six paths delete blobs or sessions: media purge, node purge, scope purge, a
   contributor's own delete, cover replacement, and the stale-session purge. A counter that each of them must
   decrement correctly is one that drifts.

4. **The per-link limit is the Tenant Owner's first distribution.**
   - The Tenant Owner sets one value per tenant in the tenant settings. It applies to every CONTRIBUTOR and
     UPLOADER link separately.
   - **Default: 10 GiB.** That is enough for an event's photos, and too little for a leaked link to eat a disk.
   - There is no "unlimited", so the anonymous path is always bounded. `0` switches link uploads off.
   - A link's usage is:
     - the blobs of the media its identities contributed, with those media's derivatives;
     - the sessions its identities opened.
   - Freeing that usage:
     - A contributor's delete of their own upload frees it at once.
     - A member's trash frees it at purge.
   - A limit chosen per link at creation could sit on top of this one later. It is not decided here.

5. **The check runs at `start` and reserves. A request is admitted whole or refused whole.**
   - `start` is the only door into the store. A session's declared size counts from the moment the session
     exists, so the check bounds what a client may `PUT` at all. A check at `complete` would find the bytes
     already stored.
   - Windows and `complete` do not check again. A lowered limit binds from the next `start` on.
   - If the admissible items of one request do not fit, every one of them is refused with `quota_exceeded`, and
     no session is created. The web client sends one medium per request (the original plus its derivatives), so
     a photo fits entirely or not at all. Admitting item by item would leave an original reserved without its
     derivatives until the stale-session purge.
   - Concurrent `start`s into one tenant are **serialized by a pessimistic lock on the tenant row**. It is held
     for the check and the session inserts, not for presigning. Without it, N parallel starts through one link
     each see the same remainder and together admit N times that much.

6. **What the uploader is told.** A new per-item code, `quota_exceeded`. It is distinct from `budget_exceeded`
   (the file is over the per-blob ceiling) and from `shape_invalid`. No retry is made or offered, because one
   cannot help.
   - **A link visitor** reads that the link takes no more uploads right now. They are never told which limit was
     hit, so an anonymous visitor learns nothing about the tenant's usage.
   - **A member** reads that there is no space left, and that trash counts until it is emptied.
   - **The Tenant Owner** sees their tenant's total usage, and its quota, in the tenant settings.
   - **The Server Owner** sees every tenant's usage and quota in `admin-web`.
   - No one sees a figure per member or per scope.

7. **A quota per member comes later, as its own decision** (`docs/inbox.md` #179).
   Members are invited, authenticated people, and the tenant quota already protects the disk. Two things are
   still open:
   - **Attribution has gaps today.** Covers record no uploader, and media from before `created_by` existed carry
     `null`.
   - **A figure per member is a new disclosure.** It would show a peer, the Tenant Owner, how much someone keeps
     in their own personal space. That warrants its own decision.

## Consequences

- **Operator-blind holds.** The quota reads sizes the server already sees, and shows only aggregates of them:
  per tenant to the Server Owner, who already sees every size, and each Tenant Owner only their own tenant's.
- **Starts into one tenant serialize.** Each one costs a SUM over indexed joins while the lock is held.
  `veyq_media.share_identity_id` has no index today (it was added bare in V32), and the link SUM wants one. A
  maintained counter is the fallback only against a *measured* cost, and it would come with a reconciliation
  against the SUM.
- **Reservations from failed or abandoned sessions hold until the stale-session purge.** That runs
  `veyq.media.upload.retention` after the last window. A client that abandons uploads therefore blocks its own
  link for a while. This is accepted: the rule is conservative and heals itself.
- **Limits can be overbooked.** A lowered limit deletes nothing; it refuses the next `start`. The link limits of
  one tenant may add up to more than its quota. First come, first served, and the quota still holds.
- **`max-bytes` stays.** It bounds a file. The quota bounds a total. The session reservation is also what bounds
  open sessions, which ADR 0013 §3 leaves to this ADR. It bounds them for every link, and for every tenant that
  has a quota. A member of a tenant without a quota is bounded only per file, and that is the default's price
  (§1).
- **Rejected:**
  - A maintained counter (3).
  - A check at `complete` (5).
  - Item-by-item admission (5).
  - Not counting trash (2).
  - Not counting derivatives (2).
  - An unlocked, optimistic check. The overshoot multiplies with parallelism.
  - Telling a link visitor which limit they hit.
  - A ceiling tied to the Server-Owner role. It would put a bring-your-own-bucket tenant's own bill under the
    operator's control.

## Related

- [ADR 0013](0013-blob-storage-presigned-direct-to-store.md) §3 — the per-file ceiling, declare-then-verify, and
  the stale-session purge the reservation relies on.
- [ADR 0062](0062-store-object-reclaim-is-a-durable-work-list.md) — why a tombstone cannot be attributed.
- [ADR 0065](0065-admin-ui-cli-separate-service.md) — the admin channel that writes the quota.
- [ADR 0067](0067-store-provisioning-from-a-declared-type.md) — one store connection today; the per-tenant
  question it leaves to #41.
- [ADR 0030](0030-contributor-public-share.md), [ADR 0031](0031-uploader-public-share.md) — the anonymous links
  the per-link limit bounds.
- Use-cases: [uc.sharing.share-link-storage-limit](../features/sharing/use-cases/uc.sharing.share-link-storage-limit.md),
  [uc.admin.tenant-storage-quota](../features/admin/use-cases/uc.admin.tenant-storage-quota.md).
