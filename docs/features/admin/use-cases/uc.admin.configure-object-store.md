# UC: Connect and check the instance's object store

- **ID:** uc.admin.configure-object-store
- **Primary actor:** The **Server Owner**, signed in to the admin console (admin-web) or using its CLI
- **Goal:** Give the instance the object store its media live in — the bundled store or any S3-compatible one —
  and know it works **before** the first tenant relies on it, not when the first photo fails to upload
  ([ADR 0089](../../../decisions/0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md),
  [ADR 0067](../../../decisions/0067-store-provisioning-from-a-declared-type.md)).
- **Preconditions:** The instance is set up and the Server Owner holds a session on the admin channel
  ([uc.admin.admin-channel](uc.admin.admin-channel.md)). The config-secret key is available
  ([ADR 0050](../../../decisions/0050-operator-config-secret-encryption.md)).
- **Postconditions (success):** The instance holds one store setting, its credentials sealed under the
  config-secret key, and that setting has passed its check; tenants can be created, and the store the backend
  signs against, the app shell's CSP and the tenant ingress all follow it without a restart.

## Main flow

1. The Server Owner opens **Server → Object store** in the admin console. With no store set, it says so, and says that
   tenants cannot be created until a store has passed its check.
2. The Server Owner picks a **type**: *S3-compatible* (the default) or *Garage*. Where the recipe passed a
   bundled-store hint, the console also offers **Use the bundled store**.
3. For *S3-compatible* they enter the endpoint, the public endpoint browsers reach, the bucket, the region, the
   access key and the secret key; for *Garage* also its admin endpoint and admin token; for the bundled store
   nothing — the setting refers to the hint.
4. The Server Owner **saves**. The setting is stored as **pending**: nothing the instance does changes yet. The
   secrets are sealed under the config-secret key with that key's fingerprint and never returned; the console shows
   only that they are set.
5. The Server Owner **checks** the store — the pending setting. For Garage, veyq provisions it first (layout, bucket, key, grant,
   CORS). The check then confirms the bucket exists, writes one fixed probe object under `veyq-check/` through a
   presigned PUT against the public endpoint and reads it back through a presigned GET, and sends a CORS preflight
   with the instance's public origin.
6. The console shows each step's result. When all pass, the pending setting is **in use**: tenants can be created,
   and the backend uses the store at once, without a restart.

## Alternate / exception flows

- **A1 — A check step fails.** The console names the step and the store's answer (no bucket, access denied, the
  public endpoint unreachable, CORS not answered with the origin) and keeps tenant creation closed. Nothing about
  the store's configuration was changed by the check.
- **A2 — The setting changes after it was checked.** The new setting replaces the old one only once it passes its
  own check; until then the instance keeps using the checked one, and the console shows the change as pending
  beside it. A failed check leaves it pending; the Server Owner corrects it and saves again, or **discards** it.
  Saving the setting in use unchanged leaves nothing pending.
- **A3 — Blobs already exist.** The store type, endpoint and bucket can no longer be changed; credentials can
  (a rotated key). Moving an instance's blobs to another store is out of scope.
- **A4 — The config-secret key is absent, or is not the one that sealed the store's credentials.** The console
  says the store's credentials cannot be opened and asks for them again; media are unreachable until the
  re-entered credentials pass their check (A2's path: saved as pending, then put in use).
  The bundled store is unaffected, because its setting refers to the hint and seals nothing.
- **A5 — No store is set.** The instance runs and answers `/api/setup`; uploads are refused with a reason. The
  in-memory store is never used outside tests.
- **A6 — The caller is not the Server Owner.** No surface reads or writes the store setting for anyone else, and
  none returns its secrets to anyone, the Server Owner included.
- **A7 — Objects no row claims.** A background sweep lists the store in use page by page and compares every object
  with the rows that name a storage key ([ADR 0062](../../../decisions/0062-store-object-reclaim-is-a-durable-work-list.md)).
  The **Object store** page (and `veyq-admin store reconcile`) shows its last full pass — the objects the store holds,
  how many no row claims and their size, how many are not veyq's (the probe, anything not shaped like a veyq object) —
  beside the reclaim list's pending and abandoned counts and the failure that stopped a pass. After an in-place
  rollback, the unclaimed objects are the failed release's uploads. **Nothing is deleted until the Server Owner
  confirms that the bucket holds only this instance's objects**; once confirmed, an object that stayed unclaimed in
  every pass for the grace period (30 days by default, from when it was first seen) goes onto the reclaim list, whose
  sweep deletes it. The page shows those candidates, their age and the date the first will go; **pause** stops the
  deletion and keeps the counting, **resume** lets it go on. A restore or a rollback restarts every grace period and
  withdraws the confirmation, and so does a changed store setting. The sweep itself never deletes an object.

## Quality notes

- **Nothing here touches content confidentiality.** The store holds ciphertext only; its credentials let the
  server sign upload and download URLs, which it does today from environment variables.
- **An unknown store is never provisioned.** The probe object is the ordinary data plane in the bucket veyq was
  pointed at; creating a bucket or writing a CORS policy stays Garage-only (ADR 0067 decision 1). The probe is
  overwritten by every check and never deleted, so object deletion keeps its one arm.
- **The secrets are write-only** on every surface, like a tenant's SMTP password.

## User impact

A misconfigured store shows up on the operator's screen before anyone uploads, instead of as a photo of yours that
never arrives — and the store only ever holds encrypted data.

## Related

- **Feature:** [admin](../_index.md)
- **Use-cases:** [uc.admin.create-tenant](uc.admin.create-tenant.md) (gated on a checked store),
  [uc.admin.setup-server](uc.admin.setup-server.md), [uc.admin.back-up-and-restore-instance](uc.admin.back-up-and-restore-instance.md)
  (the restored setting and the backup tool's credentials).
- **Decisions:** [ADR 0089](../../../decisions/0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md),
  [ADR 0067](../../../decisions/0067-store-provisioning-from-a-declared-type.md),
  [ADR 0050](../../../decisions/0050-operator-config-secret-encryption.md),
  [ADR 0013](../../../decisions/0013-blob-storage-presigned-direct-to-store.md).
- Run area of the docs site: [First-run setup and the admin console](../../../../site/src/content/docs/run/first-run.mdx)
- Run area of the docs site: [Troubleshooting](../../../../site/src/content/docs/run/troubleshooting.md)
- Run area of the docs site: [Operations](../../../../site/src/content/docs/run/operations.md) (A7: objects no record
  claims, the confirmation, the grace and the pause)
