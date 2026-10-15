# UC: A tenant's storage quota — the ceiling is set by whoever pays for the store

- **ID:** uc.admin.tenant-storage-quota
- **Primary actor:** **the Server Owner**, in `admin-web` or the `veyq-admin` CLI, through the admin channel
  ([uc.admin.admin-channel](uc.admin.admin-channel.md)).
- **Secondary actors:** **every uploader into the tenant** — members, and link visitors — and **the tenant owner**,
  who sees the quota next to the tenant's usage.
- **Goal:** cap how much of the instance's store one tenant can use, so the operator who pays for the store decides
  how it is shared between tenants — and no tenant owner can raise their own ceiling.
- **Preconditions:** the tenant exists (any status). The tenant's objects go to the instance's store — today the
  only kind there is.
- **Postconditions (success):** the tenant's quota is stored on the tenant row; from the next upload on, the
  tenant's usage stays within it.

The rule — whose ceiling it is, what counts, how the check reserves — is
[ADR 0073](../../../decisions/0073-storage-quota-belongs-to-whoever-pays-for-the-store.md). The tenant owner's
distribution below it is [uc.sharing.share-link-storage-limit](../../sharing/use-cases/uc.sharing.share-link-storage-limit.md).

## Main flow

1. The Server Owner sees, per tenant, its storage usage and its quota — none by default.
2. They set, change or clear a tenant's quota.
3. Every upload into the tenant's spaces — a member's photo, a cover, an upload through a link — reserves its sealed
   size at `start`, and is admitted only while the tenant's usage plus the request stays within the quota. Usage is
   everything the tenant holds in the store: originals, derivatives and covers, trash and spaces in their deletion
   grace until purged, and uploads still in flight.
4. When an upload does not fit, it is refused before a byte is sent. A member's upload is marked **failed** in the
   uploads tray with the reason — no space left; trash counts until emptied — and is not retried automatically.
5. The tenant owner sees the quota beside the tenant's usage in the tenant settings.

## Alternate / exception flows

- **A1 — no quota set** (the default) → the tenant is unbounded; the store's disk is the limit. Link uploads are
  still bounded by the per-link limit.
- **A2 — the quota is lowered below current usage** → nothing is deleted; every upload into the tenant is refused
  until usage drops (a purge, a contributor's own delete, a cover cleared).
- **A3 — a link upload into a tenant at its quota** → refused exactly like a full link: the visitor is never told
  which limit was reached (uc.sharing.share-link-storage-limit A5).
- **A4 — the operation is called on the public origin** → `404`, like every Server-Owner operation
  ([uc.admin.admin-channel](uc.admin.admin-channel.md)). No tenant-owner operation writes the quota.
- **A5 — many uploads into one tenant at once** → reservations are taken one after another under a lock on the
  tenant, so together they cannot exceed the remainder.
- **A6 — a tenant on its own bucket** (inbox #41, not built) → that tenant pays its own storage bill, so its ceiling
  is the tenant owner's to set, or absent; this operation would not apply to it (ADR 0073 §1).

## Quality notes

- **Operator-blind, unchanged.** The quota is enforced on sizes the server already sees
  ([security-model](../../../crosscutting/security-model.md) § What is encrypted vs. server-visible). The Server
  Owner sees one total per tenant; the tenant owner sees their own tenant's total; nobody sees a figure per member
  or per space.
- **Suspension and quota are independent.** A suspended tenant keeps its quota; suspension refuses its uploads by
  its own rule ([uc.admin.suspend-tenant](uc.admin.suspend-tenant.md)), which the quota neither replaces nor needs.

## What this means for you / Was das für dich bedeutet

**EN —** If you run the server, you can give each space a storage limit, and see how much each one uses. Nobody
inside a space can raise their own limit. If someone tries to upload into a full space, the upload stops before
anything is sent, and they're told there's no room left — deleted photos only free space once the trash is emptied.
Without a limit, a space can use whatever your disk holds. We only ever count sizes, which the server sees anyway —
never what's in your photos.

**DE —** Wer den Server betreibt, kann jedem Bereich eine Speichergrenze geben und sehen, wie viel jeder belegt.
Niemand in einem Bereich kann die eigene Grenze anheben. Wer in einen vollen Bereich hochladen will, wird gestoppt,
bevor etwas übertragen wird, und erfährt, dass kein Platz mehr frei ist — gelöschte Fotos geben Platz erst frei,
wenn der Papierkorb geleert ist. Ohne Grenze kann ein Bereich so viel belegen, wie die Festplatte hergibt. Gezählt
werden nur Größen, die der Server ohnehin sieht — nie, was auf deinen Fotos ist.

## Related

- **Feature:** [admin](../_index.md)
- **Use-cases:** [uc.sharing.share-link-storage-limit](../../sharing/use-cases/uc.sharing.share-link-storage-limit.md)
  (the tenant owner's distribution below it), [uc.admin.admin-channel](uc.admin.admin-channel.md) (where the
  operation answers), [uc.admin.create-tenant](uc.admin.create-tenant.md) (the tenant it bounds),
  [uc.memory.upload](../../memory/use-cases/uc.memory.upload.md) (the member upload it can refuse).
- **Decisions:** [ADR 0073](../../../decisions/0073-storage-quota-belongs-to-whoever-pays-for-the-store.md),
  [ADR 0065](../../../decisions/0065-admin-ui-cli-separate-service.md) (the Server-Owner surface),
  [ADR 0067](../../../decisions/0067-store-provisioning-from-a-declared-type.md) (one store connection today).
- Run area of the docs site: [Plan your instance](../../../../site/src/content/docs/run/plan.md)
- End-user view (docs site): [Storage and upload links](../../../../site/src/content/docs/space-owners/storage.mdx)
