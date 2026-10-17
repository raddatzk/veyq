package me.raddatz.veyq.core.blobstore

import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.transaction.Transactional

/**
 * Test-scope seam that makes the reconcile's claim query fail (cr-bucket-reconcile B10): a database that cannot answer
 * which rows claim a page's keys must cost that page its count, never classify it as unclaimed — the one failure that
 * would otherwise turn every object on the page into an orphan.
 *
 * Behaviour-identical to [StoreReconcileStore] while [failClaims] is false. Like [RefusingStoreReclaimStore] it wins the
 * injection point globally and is an `@ApplicationScoped` singleton in a JVM shared across test classes: a test that
 * arms it disarms it in a `finally`.
 */
@Alternative
@Priority(1)
@ApplicationScoped
class RefusingStoreReconcileStore : StoreReconcileStore() {

    @Volatile
    var failClaims: Boolean = false

    /** `@Transactional` re-declared: an override does not inherit its base method's annotation. */
    @Transactional
    override fun claims(storageKeys: Collection<String>): Map<String, Int> {
        if (failClaims) throw IllegalStateException("the claim query was refused")
        return super.claims(storageKeys)
    }
}
