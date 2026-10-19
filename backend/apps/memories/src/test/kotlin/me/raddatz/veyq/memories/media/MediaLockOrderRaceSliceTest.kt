package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.identity.PrincipalKey
import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.blob.StoredObjectLocks
import me.raddatz.veyq.core.items.NodeItemsRevisions
import me.raddatz.veyq.core.testing.OVERLAP_HOLD_MS
import me.raddatz.veyq.core.testing.Overlap
import me.raddatz.veyq.core.testing.holdForOverlap
import me.raddatz.veyq.core.testing.raceRounds
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.memories.banner.BannerKit
import me.raddatz.veyq.memories.banner.MemoryBannerOutcome
import me.raddatz.veyq.memories.banner.MemoryBannerStore
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.arc.Arc
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The media writes' one lock order, **bucket → media → node** (inbox #313, #319; `MediaStore.ensurePrimaryBucket`'s
 * KDoc): a sub-bucket delete ([MediaStore.deleteBucketReparent]) and a move ([MediaStore.moveMedia]) take the bucket,
 * then the media (sorted), then the node row; a trash ([MediaStore.trashMedia]) and a guest's delete
 * (`MediaShareGuestWrites.deleteOwn`) take a medium and then the node. Two writers that took two of these in opposite
 * orders could each hold what the other waits for: a deadlock, which MariaDB breaks by failing one of them (a 500) and
 * H2 by a lock timeout.
 *
 * Each race holds one writer **between** its two locks ([between]) — the first taken and flushed, the second not yet —
 * and runs the production writer whose order is pinned inside that window, so it reaches its own first lock while the
 * held one waits to take its second. In the order above both finish; with the pinned writer's two locks swapped each
 * holds what the other wants, every round. Deterministic, not likely: the interleaving is forced. Each has a control:
 * the same production write with nothing held lands the same way.
 *
 * The same holds for blob rows across stored objects (`StoredObjectLocks`): every transaction that locks the rows of
 * several objects locks them all at once, ascending by storage key - the banner's removal against a measure-taker below.
 * Bite (run 2026-10-19, then restored): the removal's lock-all taken out of `MemoryBannerStore.remove` reddens
 * [aBannerRemovalAndAMeasureTakerOnItsTwoObjectsBothFinish].
 */
@QuarkusTest
class MediaLockOrderRaceSliceTest {

    @Inject lateinit var store: MediaStore
    @Inject lateinit var items: NodeItemsRevisions
    @Inject lateinit var banners: MemoryBannerStore

    /**
     * A trash's order (media → node) against a sub-bucket delete: the reparent must lock its media BEFORE the
     * primary's node row. Taken the other way round (#301's order, before #313), it held the node while waiting for
     * the medium the trash held, and the trash waited for that node to bump its revision.
     */
    @Test
    fun aSubBucketDeleteAndATrashOfItsMediumBothFinish() {
        raceRounds { round ->
            val f = fixture()
            val (held, other) = between(
                first = { MediaEntity.findById(f.mediaId, LockModeType.PESSIMISTIC_WRITE) }, // trashMedia's first lock
                second = { items.bump(f.scopeId, f.nodeId, deleted = true) }, // ...and its last, on the node row
                other = { store.deleteBucketReparent(f.subBucketId) },
            )
            assertNull(held, "round $round: the trash's side failed: $held")
            assertNull(other, "round $round: the sub-bucket delete failed: $other")
            assertReparented(f, "round $round")
        }
        val f = fixture()
        store.deleteBucketReparent(f.subBucketId)
        assertReparented(f, "control")
    }

    /**
     * A sub-bucket delete's order (bucket → media) against a move into that bucket: the move must lock its target
     * bucket BEFORE the media. Taken the other way round, it held the medium the delete was about to carry and waited
     * for the bucket the delete held.
     */
    @Test
    fun aMoveIntoASubBucketAndThatBucketsDeleteBothFinish() {
        raceRounds { round ->
            val f = fixture()
            val (held, other) = between(
                first = { BucketEntity.findById(f.subBucketId, LockModeType.PESSIMISTIC_WRITE) }, // deleteBucketReparent's first lock
                second = { MediaEntity.find("bucketId = ?1 order by id", f.subBucketId).withLock(LockModeType.PESSIMISTIC_WRITE).list() }, // ...its second
                other = { check(store.moveMedia(f.scopeId, f.nodeId, listOf(f.mediaId), f.subBucketId)) { "the move refused" } },
            )
            assertNull(held, "round $round: the delete's side failed: $held")
            assertNull(other, "round $round: the move failed: $other")
            assertEquals(f.subBucketId, tx { MediaEntity.findById(f.mediaId)!!.bucketId }, "round $round")
        }
        val f = fixture()
        assertEquals(true, store.moveMedia(f.scopeId, f.nodeId, listOf(f.mediaId), f.subBucketId), "control")
    }

    /**
     * The one lock order on stored objects (`StoredObjectLocks`: ascending storage key, every object at once): a
     * measure-taker (`MeasureTargets.survivingObjects`, the one locker of blob rows that holds no scope lock) holding the
     * lower of a banner's two objects and about to take the higher, against the banner's removal, whose slot order
     * deletes the banner's row (the higher object) before the poster's (the lower). Slot by slot, the removal held the
     * higher while it waited for the lower, which the taker held while it waited for the higher: a deadlock, every round.
     * Locked at once in the one order, the removal waits on the lower holding nothing, and both finish.
     */
    @Test
    fun aBannerRemovalAndAMeasureTakerOnItsTwoObjectsBothFinish() {
        repeat(RACE_ROUNDS) { round ->
            val f = bannerOnTwoObjects()
            val (held, other) = between(
                first = { StoredObjectLocks.lock(listOf(f.low)) }, // the taker's first lock
                second = { StoredObjectLocks.lock(listOf(f.high)) }, // ...and its second
                other = { check(removeBanner(f) == MemoryBannerOutcome.Done) { "the removal refused" } },
            )
            assertNull(held, "round $round: the taker's side failed: $held")
            assertNull(other, "round $round: the banner's removal failed: $other")
            assertEquals(null to null, BannerKit.slots(f.memory), "round $round: the banner is gone")
        }
        val f = bannerOnTwoObjects()
        assertEquals(MemoryBannerOutcome.Done, removeBanner(f), "control")
    }

    // --- helpers ---

    /**
     * Run [first] in a transaction of its own and flush it, so its lock is taken; then, while [other] runs on another
     * thread, hold [OVERLAP_HOLD_MS] and run [second] in the same transaction, and commit. Returns what each side
     * failed with (null: it finished). [other] opens its own transaction (a `@Transactional` store method). A round
     * in which [other] was not waiting on the held side when the hold ended interleaved nothing: it throws
     * [me.raddatz.veyq.core.testing.OrderNotForced], and [raceRounds] runs it again (inbox #512).
     */
    private fun between(first: () -> Unit, second: () -> Unit, other: () -> Unit): Pair<Throwable?, Throwable?> {
        val firstTaken = CountDownLatch(1)
        val overlap = AtomicReference<Overlap>()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val held = pool.submit(
                Callable {
                    runCatching {
                        QuarkusTransaction.requiringNew().run {
                            first()
                            Arc.container().instance(EntityManager::class.java).get().flush()
                            firstTaken.countDown()
                            overlap.set(holdForOverlap()) // the other writer reaches its first lock meanwhile
                            second()
                        }
                    }.exceptionOrNull().also { firstTaken.countDown() }
                },
            )
            check(firstTaken.await(10, TimeUnit.SECONDS)) { "the held side never took its first lock" }
            val overlapping = pool.submit(Callable { runCatching { other() }.exceptionOrNull() })
            val failures = held.get(60, TimeUnit.SECONDS) to overlapping.get(60, TimeUnit.SECONDS)
            overlap.get()?.requireForced()
            return failures
        } finally {
            pool.shutdownNow()
        }
    }

    private data class Fixture(
        val scopeId: String, val nodeId: String, val primaryBucketId: String, val subBucketId: String, val mediaId: String,
    )

    /** A member's photo filed in a sub-bucket under the member's primary, in one Memory. */
    private fun fixture(): Fixture {
        val (userId, scopeId) = seedMemberScope()
        val nodeId = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val ownerKey = PrincipalKey.User(userId).format()
        val primary = UUID.randomUUID().toString()
        val sub = UUID.randomUUID().toString()
        val mediaId = UUID.randomUUID().toString()
        tx {
            val blobId = UUID.randomUUID().toString()
            BlobEntity().apply {
                id = blobId; this.scopeId = scopeId; storageKey = UUID.randomUUID().toString()
                chunkCount = TestBlob.SMALL.chunkCount; byteSize = TestBlob.SMALL.byteSize; wrappedBlobKey = "Ymxvyi13cmFw"
            }.persist()
            BucketEntity().apply {
                id = primary; this.scopeId = scopeId; this.nodeId = nodeId; this.ownerKey = ownerKey; isPrimary = true
                createdAt = Instant.now()
            }.persist()
            BucketEntity().apply {
                id = sub; this.scopeId = scopeId; this.nodeId = nodeId; this.ownerKey = ownerKey; isPrimary = false
                parentBucketId = primary; sealedName = "c3Vi"; createdAt = Instant.now()
            }.persist()
            MediaEntity().apply {
                id = mediaId; this.scopeId = scopeId; this.nodeId = nodeId; this.blobId = blobId
                createdBy = ownerKey; bucketId = sub; updatedAt = Instant.now()
            }.persist()
        }
        return Fixture(scopeId, nodeId, primary, sub, mediaId)
    }

    private fun assertReparented(f: Fixture, label: String) {
        assertEquals(f.primaryBucketId, tx { MediaEntity.findById(f.mediaId)!!.bucketId }, "$label: the medium moves to the primary")
        assertNull(tx { BucketEntity.findById(f.subBucketId) }, "$label: the sub-bucket is gone")
    }

    private fun <T> tx(body: () -> T): T = QuarkusTransaction.requiringNew().call(body)

    /** A banner on the higher of a medium's two objects and its poster on the lower, so the slot order is the reverse. */
    private data class BannerFixture(
        val userId: String, val scopeId: String, val memory: String, val banner: String, val poster: String,
        val high: String, val low: String,
    )

    private fun bannerOnTwoObjects(): BannerFixture {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val m = SharedObjectKit.medium(userId, scopeId, memory)
        val (high, low) = listOf(m.original, m.preview).sortedByDescending { it.storageKey }
        val banner = UUID.randomUUID().toString()
        val poster = UUID.randomUUID().toString()
        SharedObjectKit.setBanner(
            userId, scopeId, memory, SharedObjectKit.shared(high.blobId, banner), SharedObjectKit.shared(low.blobId, poster),
        ).statusCode(204)
        return BannerFixture(userId, scopeId, memory, banner, poster, high.storageKey, low.storageKey)
    }

    private fun removeBanner(f: BannerFixture): MemoryBannerOutcome =
        banners.remove(f.scopeId, f.memory, f.banner, f.poster, "bWV0YQ==", 1, PrincipalKey.User(f.userId))
}
