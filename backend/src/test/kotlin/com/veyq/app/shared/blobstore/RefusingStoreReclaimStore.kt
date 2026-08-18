package com.veyq.app.shared.blobstore

import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.transaction.Transactional

/**
 * Test-scope seam that makes a reclaim **insert** fail, so a purge's per-item transaction isolation stays
 * provable after ADR 0062 (cr-store-object-reclaim-list).
 *
 * **Why it had to exist.** Every isolation property of this shape needs one item the sweep cannot process while
 * its neighbour goes through, and the honest injection point kept moving. `cr-scope-erasure-fk-closure` closed
 * the foreign-key closure under `veyq_scope`, so no *schema* can poison a scope any more — by construction, which
 * is what `validate_scope_erasure_arity.py` keeps true. The failure that remained was the store's, and
 * [RecordingBlobStore.failOnKeyMarker] is what
 * `ScopesSliceTest.purgeIsolatesAScopeItCannotEraseFromTheRestOfTheTick` levered on:
 * `ScopePurge.eraseScope` deleted objects first, inside the per-scope transaction, so a refusing store threw
 * there. ADR 0062 Decision 4 took that call away — the purge now *records* keys and
 * [StoreReclaimSweep] deletes them — and with it the last thing in `eraseScope` that could throw. The property is
 * permanent; its lever had a one-CR life.
 *
 * So the injection moved one step, to the write that replaced the delete. `eraseScope` still touches something
 * fallible in its own transaction — the reclaim insert — and a DB refusal there is the realistic residual
 * failure of a job whose remaining work is entirely database writes.
 *
 * **It refuses, it does not record.** A `@Priority`-enabled `@Alternative` wins the [StoreReclaimStore] injection
 * point globally, the same shape as [RecordingBlobStore]; with [failOnKeyMarker] unset it is behaviour-identical
 * to the bean it replaces. Being an `@ApplicationScoped` singleton in a JVM shared across test classes, it is
 * subject to the same rule: a test that arms it **restores it in a `finally`**, or every later reclaim in this
 * JVM dies on a marker nobody can see.
 */
@Alternative
@Priority(1)
@ApplicationScoped
class RefusingStoreReclaimStore : StoreReclaimStore() {

    /** When set, [record] throws for any batch holding a storage key that contains this marker, writing nothing
     *  for the whole batch — a rejected insert is rejected, not partially applied. `@Volatile`: a `@Scheduled`
     *  purge can read it from a scheduler thread while the test that armed it wrote from its own. */
    @Volatile
    var failOnKeyMarker: String? = null

    /** `@Transactional` is **re-declared**, not inherited: a method annotation does not carry to an override, so
     *  without this line the `MANDATORY` guarantee — the whole point of the base method — would silently be off
     *  in every test, and a caller that recorded outside a transaction would pass. */
    @Transactional(Transactional.TxType.MANDATORY)
    override fun record(refs: Collection<StoreObjectRef>) {
        failOnKeyMarker?.let { marker ->
            if (refs.any { marker in it.storageKey }) {
                throw IllegalStateException("the reclaim insert refused a storage key marked `$marker`")
            }
        }
        super.record(refs)
    }
}
