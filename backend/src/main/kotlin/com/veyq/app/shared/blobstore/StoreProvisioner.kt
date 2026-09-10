package com.veyq.app.shared.blobstore

/**
 * Brings a configured object store from **running** to **usable** (ADR 0067).
 *
 * **This is deliberately not a [BlobStore] method** (ADR 0067 Decision 2), and the reason is sharper than
 * tidiness: `scripts/validate_store_reclaim_arity.py` pins [StoreReclaimSweep] as the *single* caller of
 * `blobStore.delete`, so object deletion has exactly one arm. A provisioner hanging off the same port is how
 * that would quietly become two. Bucket and IAM calls are also simply not what a blob store *does* — the port
 * stays presign / stat / delete, and `BlobStore.kt` is untouched by ADR 0067's work.
 *
 * **Every step is individually idempotent — there is no short-circuit on "the bucket exists"** (ADR 0067
 * Decision 3). The shape this replaces, `infra/garage-init.sh`, returned early the moment `bucket info`
 * succeeded. That was correct for a script whose five steps always shipped together and wrong the moment the
 * step set grew: a store provisioned before the CORS step existed would never have received a policy. So each
 * step checks its own precondition and acts on its own, and running the whole thing against a fully
 * provisioned store is a clean no-op that still repairs any single missing piece.
 *
 * **It touches metadata only, never an object's bytes.** Layout, bucket, access key, grant and the bucket CORS
 * policy — nothing here reads, writes or deletes an object, so operator-blindness is untouched by
 * construction rather than by promise.
 *
 * Implementations throw on failure. The bounded retry, the give-up and the "never gate the boot" property all
 * live in [StoreProvisioningRunner], which is the only caller.
 */
interface StoreProvisioner {

    /**
     * Run every bring-up step this store type supports, in dependency order, each one idempotent.
     *
     * @throws Exception when any step could not be completed. The caller decides whether that is worth another
     *   attempt; this reports failure and nothing more.
     */
    fun provision()
}
