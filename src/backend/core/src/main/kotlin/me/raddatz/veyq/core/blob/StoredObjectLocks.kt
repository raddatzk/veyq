package me.raddatz.veyq.core.blob

import jakarta.persistence.LockModeType

/**
 * **The one lock order on stored objects** (ADR 0013 § 5): a transaction that locks the blob rows of a stored object
 * locks the rows of **every** object it will touch here, **before the first of them**, ascending by storage key and,
 * within one key, by id. Two such transactions then meet on the first object they share - one waits holding none the
 * other wants - and never each hold one the other waits for (a deadlock, which MariaDB breaks by failing one of them).
 * A later lock on a row already held is no wait, so the per-object locks after this one ([BlobUploadStore.deleteBlobRow],
 * [SharedBlobs.persist]) take nothing new.
 *
 * Who takes it: [BlobUploadStore.deleteBlobRow] and [BlobUploadStore.deleteBlobRows], [SharedBlobs.persist],
 * `AttachmentStore.attachShared`, the banner's set and remove (`MemoryBannerStore`), the media purges
 * (`MediaStore.purgeMediaRows`, `purgeTrashedBefore`), the node subtree purge (`NodeSubtreePurge`, every eraser's
 * objects at once), `ScopePurge`, and the measure-taker's `MeasureTargets.survivingObjects` - the one that holds no
 * scope lock, and so the one the order is for. **Item rows come before blob rows** (a medium's row, then its objects),
 * the order every purge takes; a lock taken here comes after the item rows of the same transaction.
 * `MediaLockOrderRaceSliceTest` forces the crosswise case.
 */
object StoredObjectLocks {
    /** Ids per statement: one statement per chunk, each in the order above, the chunks themselves ascending. */
    private const val CHUNK = 500

    /** Lock every blob row naming one of [storageKeys], in the one order; answers them, in that order. */
    fun lock(storageKeys: Collection<String>): List<BlobEntity> =
        storageKeys.distinct().sorted().chunked(CHUNK).flatMap { chunk ->
            BlobEntity.find("storageKey in ?1 order by storageKey, id", chunk).withLock(LockModeType.PESSIMISTIC_WRITE).list()
        }

    /**
     * [lock] the objects of the blobs [blobIds] of [scopeId] (an id of another scope, or of no blob, names none). A
     * storage key never changes, so the plain read that finds it is enough; the lock is on the rows it names.
     */
    fun lockOf(scopeId: String, blobIds: Collection<String?>): List<BlobEntity> {
        val ids = blobIds.filterNotNull().distinct()
        if (ids.isEmpty()) return emptyList()
        val keys = ids.chunked(CHUNK).flatMap { chunk ->
            BlobEntity.getEntityManager()
                .createQuery("select b.storageKey from BlobEntity b where b.id in ?1 and b.scopeId = ?2", String::class.java)
                .setParameter(1, chunk).setParameter(2, scopeId).resultList
        }
        return lock(keys)
    }
}
