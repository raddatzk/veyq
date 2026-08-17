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

    fun reset() = deletedKeys.clear()

    override val canPresign: Boolean get() = delegate.canPresign
    override fun presignPut(key: String): String? = delegate.presignPut(key)
    override fun presignGet(key: String): String? = delegate.presignGet(key)
    override fun stat(key: String): Long? = delegate.stat(key)

    override fun delete(keys: List<String>) {
        deletedKeys.addAll(keys)
        delegate.delete(keys)
    }
}
