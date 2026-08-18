package com.veyq.app.shared.blobstore

import com.veyq.app.shared.config.VeyqConfig
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import java.util.Collections

/**
 * Test-scope seam that makes the **store half** of a purge assertable (cr-abandoned-upload-session-reclaim).
 *
 * Every scheduled purge in `features/media` is specified as two halves — delete the rows, then delete the
 * objects those rows pointed at — and until this bean existed only the first half was ever asserted. The
 * hermetic tier runs on [InMemoryBlobStore], whose `delete` is an empty no-op, so a purge that silently dropped
 * its `blobStore.delete(…)` call passed every test: [com.veyq.app.features.media.MediaTrashPurge]'s object
 * delete was proven by nothing at all, and the invariant *"a sweep that deletes a row carrying a `storage_key`
 * also deletes the objects under it"* — where a row-only delete is strictly worse than no GC, because it
 * orphans the bytes permanently — was the one half no test covered.
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
 * **[failOnKeyMarker] is the second thing this seam is for: making a sweep's error path reachable.** A purge
 * that isolates one item's failure from the rest of its tick can only be proven by an item that actually
 * fails, and after `cr-scope-erasure-fk-closure` closed the FK closure below `veyq_scope` no *schema* can
 * poison a scope any more — by construction, which is what the guard exists to keep true. The failure that
 * remains is the store's, and it is the honest one to pin: an S3 outage mid-sweep is the real-world shape of
 * "erasing this one throws". Being a singleton, it is subject to the same rule as [deletedKeys] and one
 * stronger: a test that sets it **restores it in a `finally`**, or every later purge in the shared JVM dies
 * on a marker nobody can see.
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

    /** When set, [delete] throws for any batch holding a key that contains this marker — and records nothing
     *  for it, because a store that refuses deleted nothing. `@Volatile`: a `@Scheduled` sweep can read it from
     *  a scheduler thread while the test that armed it wrote from its own. */
    @Volatile
    var failOnKeyMarker: String? = null

    fun reset() = deletedKeys.clear()

    override val canPresign: Boolean get() = delegate.canPresign
    override fun presignPut(key: String): String? = delegate.presignPut(key)
    override fun presignGet(key: String): String? = delegate.presignGet(key)
    override fun stat(key: String): Long? = delegate.stat(key)

    override fun delete(keys: List<String>) {
        failOnKeyMarker?.let { marker ->
            if (keys.any { marker in it }) throw IllegalStateException("blob store refused a key marked `$marker`")
        }
        deletedKeys.addAll(keys)
        delegate.delete(keys)
    }
}
