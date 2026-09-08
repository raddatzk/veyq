package com.veyq.app.shared.blobstore

import com.veyq.app.shared.config.VeyqConfig
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import java.util.Collections

/**
 * Test-scope seam that makes the **store half** of a reclaim assertable (cr-abandoned-upload-session-reclaim).
 *
 * Reclaiming a row that carries a `storage_key` is two halves — delete the rows, then delete the objects those
 * rows pointed at — and until this bean existed only the first half was ever asserted. The hermetic tier runs on
 * [InMemoryBlobStore], whose `delete` is an empty no-op, so code that silently dropped its `blobStore.delete(…)`
 * call passed every test: [com.veyq.app.features.media.MediaTrashPurge]'s object delete was proven by nothing at
 * all, and the invariant *"a path that deletes a row carrying a `storage_key` also deletes the objects under
 * it"* — where a row-only delete is strictly worse than no GC, because it orphans the bytes permanently — was
 * the one half no test covered.
 *
 * **Since ADR 0062 the two halves are two components, and this seam sits under the second one.** A purge writes
 * `veyq_store_reclaim` rows inside its transaction and [StoreReclaimSweep] is the only caller of [delete], so a
 * test that wants to see keys arrive drives the purge *and then the sweep*. Asserting a purge alone against this
 * recorder now proves nothing — which is the point of the split, not a regression in the seam.
 *
 * **It records and delegates; it does not replace.** A `@Priority`-enabled `@Alternative` wins the [BlobStore]
 * injection point globally (the same shape as `SmtpTenantInviteMailer`), which includes
 * [com.veyq.app.features.media.BlobBatchE2ETest] — whose `@TestProfile` is pure config overrides, so the
 * delegate built from that config is the real [S3BlobStore] there and the opt-in byte-transport e2e is
 * unaffected. Everywhere else the delegate is the in-memory stub and behaviour is bit-for-bit what it was.
 *
 * **[reset] before the action under test, never after.** This is an `@ApplicationScoped` singleton and the
 * `%test` JVM is shared across test classes, so recordings accumulate; a test that asserts on a leftover from
 * its predecessor is the flake this comment exists to prevent. Assert on *containment of the keys you seeded*
 * rather than on the list's size, so the claim survives a neighbour anyway.
 *
 * **[failOnKeyMarker] is the second thing this seam is for: making the sweep's error path reachable.** A retry
 * that holds a failed key on the list can only be proven by a key that actually fails, and an S3 outage
 * mid-tick is the real-world shape of it — which the real store, having swallowed every per-key failure, could
 * not produce even in principle before ADR 0062 Decision 2. Since that decision it refuses by **returning** the
 * key rather than throwing, because that is now the port's contract.
 *
 * **It is no longer the lever for a *purge's* isolation**, which it was while `ScopePurge.eraseScope` deleted
 * objects inside its transaction. That call is gone (Decision 4) and the injection moved with the fallible work,
 * to [RefusingStoreReclaimStore]. Reach for this one to break a *delete*, that one to break a *record*.
 *
 * Being a singleton, it is subject to the same rule as [deletedKeys] and one stronger: a test that sets it
 * **restores it in a `finally`**, or every later reclaim in the shared JVM dies on a marker nobody can see.
 */
@Alternative
@Priority(1)
@ApplicationScoped
class RecordingBlobStore(
    producer: BlobStoreProducer,
) : BlobStore {

    private val delegate: BlobStore = producer.blobStore()

    /** Every key handed to [delete] since the last [reset], in call order. Synchronized: a `@Scheduled` sweep
     *  can in principle fire from a scheduler thread while a test reads this. */
    val deletedKeys: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** When set, [delete] **refuses** every key containing this marker: it returns them as undeleted and
     *  records none of them, because a store that refuses deleted nothing. It refuses only those keys — the rest
     *  of the batch goes through, which is what makes a partial-failure tick assertable at all. `@Volatile`: a
     *  `@Scheduled` sweep can read it from a scheduler thread while the test that armed it wrote from its own. */
    @Volatile
    var failOnKeyMarker: String? = null

    fun reset() = deletedKeys.clear()

    override val canPresign: Boolean get() = delegate.canPresign
    override fun presignPut(key: String, domain: String?): String? = delegate.presignPut(key, domain)
    override fun presignGet(key: String, domain: String?): String? = delegate.presignGet(key, domain)
    override fun stat(key: String): Long? = delegate.stat(key)

    /**
     * Refuses the marked keys by **returning** them, not by throwing — the port's contract since ADR 0062
     * Decision 2. That change is not cosmetic: while this seam threw and the real [S3BlobStore] swallowed every
     * per-key failure, the test seam was *stronger than production*, and any retry path built on it would have
     * been provable in the hermetic suite and dead in the field. Refusing per key rather than per batch mirrors
     * the real store, where one unreachable object does not stop the others.
     */
    override fun delete(keys: List<String>): List<String> {
        val marker = failOnKeyMarker
        val (refused, accepted) = if (marker == null) emptyList<String>() to keys else keys.partition { marker in it }
        deletedKeys.addAll(accepted)
        return refused + delegate.delete(accepted)
    }
}
