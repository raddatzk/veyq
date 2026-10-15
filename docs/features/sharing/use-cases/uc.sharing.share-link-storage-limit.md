# UC: A share link's storage limit — no one link can fill the server

- **ID:** uc.sharing.share-link-storage-limit
- **Primary actor:** **the tenant owner**, signed in (a session; no unlocked vault needed — a byte count is
  operational, not content).
- **Secondary actor:** **a link visitor** — the anonymous holder of a READ_WRITE
  ([uc.sharing.share-contributor](uc.sharing.share-contributor.md)) or WRITE_ONLY
  ([uc.sharing.share-uploader](uc.sharing.share-uploader.md)) link.
- **Goal:** bound how much any one upload link can put into the store, so a link that travels further than intended
  cannot fill the server — while an ordinary event still fits without anyone touching a setting.
- **Preconditions:** the tenant is ACTIVE; for the visitor, the link is enabled, unexpired, and its role may upload.
- **Postconditions (success):** every upload link of the tenant stores at most the tenant's per-link limit; an
  upload that does not fit leaves nothing in the store and no session behind.

The rule — what counts, why the check reserves at `start`, why a request is admitted whole — is
[ADR 0073](../../../decisions/0073-storage-quota-belongs-to-whoever-pays-for-the-store.md). This use-case is the
link half of it; the tenant's own ceiling is [uc.admin.tenant-storage-quota](../../admin/use-cases/uc.admin.tenant-storage-quota.md).

## Main flow

1. The tenant owner opens the tenant settings. A **storage** section shows the tenant's total usage and the
   **per-link limit** — one value that applies to each upload link separately, 10 GiB until changed.
2. They change the limit and save. It binds every upload link of the tenant from its next upload on; nothing
   already stored is touched.
3. A visitor adds photos through a link. Each upload asks the server to reserve its sealed size; the server adds it
   to what the link already holds — its contributions, their derivatives, and uploads still in flight — and admits
   it when the sum stays within the limit.
4. When it would not, the upload is refused before a byte is sent. The visitor's tile says the link takes no more
   uploads right now, and offers no retry.

## Alternate / exception flows

- **A1 — a non-owner reads or edits the limit** → `403 not_tenant_owner`; a non-member is `404` (indistinguishable,
  ADR 0042). The settings rail does not list the **storage** section for anyone but the tenant's owner, and its address leads a
  member back to `/settings` without mounting the page, so a member is never taken to a page that can only refuse
  them (the server's refusal stays the gate).
- **A2 — the limit is lowered below what a link already holds** → nothing is deleted; that link's next upload is
  refused until its usage drops below the limit.
- **A3 — the limit is `0`** → no link of the tenant can upload. There is no "unlimited": the anonymous path is
  always bounded.
- **A4 — room is made** → a contributor who deletes their own upload frees its bytes at once. A member who trashes
  a contribution frees it only at purge: trash still occupies the store.
- **A5 — the tenant is at its quota** ([uc.admin.tenant-storage-quota](../../admin/use-cases/uc.admin.tenant-storage-quota.md))
  → the same refusal, the same message. The visitor is never told which limit was reached.
- **A6 — many uploads at once through one link** → the reservations are taken one after another under a lock, so
  together they cannot exceed the remainder.
- **E1 — an upload abandoned mid-way** → its reservation holds until the stale-session purge reclaims it
  ([en.memory.media](../../memory/enablers/en.memory.media.md) E3), then frees.

## Quality notes

- **Operator-blind, unchanged.** The limit is enforced on sizes the server already sees
  ([security-model](../../../crosscutting/security-model.md) § What is encrypted vs. server-visible). The tenant
  owner sees the tenant's total, never a figure per member, scope or link.
- **One limit per tenant, not per link.** The share dialog gains nothing; a per-link override can sit on top later
  (ADR 0073 §4).

## What this means for you / Was das für dich bedeutet

**EN —** A link that lets people add photos can end up anywhere, so each one has a size limit. By default a link
can take 10 GB — plenty for a party or a wedding. If you run the space, you can change that number for all links at
once. When a link is full, people using it see that it isn't taking uploads right now; nothing half-uploaded is left
behind. Deleting photos makes room once the trash is emptied.

**DE —** Ein Link, über den andere Fotos hinzufügen können, kann überall landen — deshalb hat jeder Link eine
Obergrenze. Standardmäßig nimmt ein Link 10 GB auf — genug für eine Feier oder eine Hochzeit. Wer den Bereich
betreibt, kann diesen Wert für alle Links auf einmal ändern. Ist ein Link voll, sehen alle, die ihn nutzen, dass er
gerade nichts mehr annimmt; halb Hochgeladenes bleibt nicht zurück. Gelöschte Fotos schaffen Platz, sobald der
Papierkorb geleert ist.

## Related

- **Feature:** [sharing](../_index.md)
- **Use-cases:** [uc.sharing.share-contributor](uc.sharing.share-contributor.md),
  [uc.sharing.share-uploader](uc.sharing.share-uploader.md) (the links this bounds),
  [uc.admin.tenant-storage-quota](../../admin/use-cases/uc.admin.tenant-storage-quota.md) (the ceiling above it),
  [uc.admin.tenant-mail-settings](../../admin/use-cases/uc.admin.tenant-mail-settings.md) (the tenant-settings
  pattern this mirrors).
- **Decisions:** [ADR 0073](../../../decisions/0073-storage-quota-belongs-to-whoever-pays-for-the-store.md),
  [ADR 0013](../../../decisions/0013-blob-storage-presigned-direct-to-store.md) §3 (the per-file ceiling beside it),
  [ADR 0030](../../../decisions/0030-contributor-public-share.md) §6,
  [ADR 0031](../../../decisions/0031-uploader-public-share.md) (the abuse controls it joins).
- End-user view (docs site): [Storage and upload links](../../../../site/src/content/docs/space-owners/storage.mdx)
