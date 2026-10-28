# ADR 0073 — A storage quota belongs to whoever pays for the store

- Status: Accepted
- Built: partly (not built: decision 1, the ceiling of a tenant on its own bucket)
- Date: 2026-09-24
- Model: [cc.storage.object-lifecycle](../crosscutting/cc.storage.object-lifecycle.md)

## Context

Nothing bounded how much a tenant, a member or a share link stores. `veyq.blob.max-bytes` bounds **a file**, never a
total ([ADR 0013 §3](0013-blob-storage-presigned-direct-to-store.md)). Contributor and uploader links
([ADR 0030](0030-contributor-public-share.md), [ADR 0031](0031-uploader-public-share.md)) are anonymous and often shared
widely, so anyone holding one could fill the store until the disk runs out.

What the decision builds on: every stored object starts as an upload session with a declared size, through the one
door of a presigned window; every stored object has a row that reaches its tenant; what a link stored is on its media;
and sizes are server-visible by design ([security-model](../crosscutting/security-model.md)), so a quota computed from
them shows the operator nothing new. Who pays for the store differs by deployment: today there is one connection, the
instance's ([ADR 0067](0067-store-provisioning-from-a-declared-type.md)); a SaaS tenant on its own bucket
(inbox #41) would pay its own bill.

## Decision

1. **The ceiling belongs to whoever pays for the store connection a tenant's objects go to**, not to a role. For the
   instance's store, the Server Owner sets each tenant's **quota** on the admin channel
   ([ADR 0065](0065-admin-ui-cli-separate-service.md)); the Tenant Owner distributes below it and can never raise it —
   structurally, since only a Server-Owner operation writes it and every `start` checks it independently of the Tenant
   Owner's limits. A tenant on its own bucket would set its own ceiling, or none; that is not built, and #41 decides who
   writes it. **Default: no quota** — on a self-hosted family instance a ceiling nobody chose is a bug, and the
   anonymous path is closed by the link default (4).

2. **Everything in the store attributable to the tenant counts:** every blob in its scopes, derivatives included (the
   client produces them, so an uploader decides their size); every open upload session at its declared size, as a
   **reservation**; and trashed items and scopes in their grace until purged — otherwise delete, re-upload, restore would
   overshoot. Reclaim rows are not counted: they carry no scope by design
   ([ADR 0062](0062-store-object-reclaim-is-a-durable-work-list.md) § 6) and the sweep's lag bounds them. Material kept
   in the database is not counted: the contract bounds each field. The size is the declared one, which `complete` makes
   the stored one (ADR 0013 §3).

3. **Usage is derived at the check, never counted.** Six paths delete blobs or sessions; a counter each must decrement
   correctly is one that drifts.

4. **The per-link limit is the Tenant Owner's first distribution.** One value per tenant applies to every link that
   takes uploads, separately. **Default: 10 GiB** — enough for an event's photos, too little for a leaked link to eat a
   disk. There is no "unlimited", so the anonymous path is always bounded; `0` switches link uploads off. A link's usage
   is what its identities stored and opened, in every application, counted on every start of the link whatever node it
   is for — measured per application, a folder link's guests would hold the limit once per application. A contributor's
   own delete frees usage at once, a member's trash at purge. A limit chosen per link could sit on top later.

5. **The check runs at `start` and reserves; a request is admitted whole or refused whole.** `start` is the only door,
   so the check bounds what a client may `PUT` at all; a check at `complete` would find the bytes already stored. Windows
   and `complete` do not check again; a lowered limit binds from the next `start`. Admitting item by item would leave an
   original reserved without its derivatives. Concurrent starts into one tenant are serialized by a pessimistic lock on
   the tenant row, held for the check and the inserts, not for presigning — without it, N parallel starts through one
   link each see the same remainder.

6. **What the uploader is told.** A per-item `quota_exceeded`, distinct from `budget_exceeded` (the per-file ceiling)
   and `shape_invalid`, with no retry, since one cannot help. A link visitor reads that the link takes no more uploads
   and is never told which limit was hit, so an anonymous visitor learns nothing about the tenant's usage. A member reads
   that no space is left and that trash counts. The Tenant Owner sees their tenant's total and quota, the Server Owner
   every tenant's; nobody sees a figure per member or per scope.

7. **A quota per member comes later, as its own decision** (inbox #179). The tenant quota already protects the disk,
   attribution has gaps (covers record no uploader, early media carry none), and a figure per member would show a peer
   how much someone keeps in their own space — a new disclosure.

## Consequences

- **Operator-blind holds.** The quota reads sizes the server already sees and shows only aggregates.
- **Starts into one tenant serialize**, each costing a SUM over indexed joins while the lock is held — only where there
  is something to check. A maintained counter is the fallback only against a *measured* cost, with a reconciliation
  against the SUM.
- **Reservations from abandoned sessions hold until the stale-session purge**, so a client that abandons uploads blocks
  its own link for a while: conservative, and it heals itself.
- **Limits can be overbooked.** A lowered limit deletes nothing, and one tenant's link limits may add up to more than its
  quota; first come, first served, and the quota still holds.
- **`max-bytes` stays:** it bounds a file, the quota a total. The reservation is also what bounds open sessions, for
  every link and every tenant with a quota; a member of a tenant without one is bounded per file only — the default's
  price.
- **Rejected:** a maintained counter (3); a check at `complete` (5); item-by-item admission (5); not counting trash or
  derivatives (2); an unlocked, optimistic check, whose overshoot multiplies with parallelism; telling a link visitor
  which limit they hit; a ceiling tied to the Server-Owner role, which would put a bring-your-own-bucket tenant's own
  bill under the operator's control.

Use-cases: [uc.sharing.share-link-storage-limit](../features/sharing/use-cases/uc.sharing.share-link-storage-limit.md),
[uc.admin.tenant-storage-quota](../features/admin/use-cases/uc.admin.tenant-storage-quota.md). Run area of the docs site:
[Plan your instance](../../site/src/content/docs/run/plan.md).
