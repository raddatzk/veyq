package me.raddatz.veyq.core.blobstore

import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.s3.model.S3Exception
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The bucket reconcile ([StoreReconcileSweep], ADR 0062 § Consequences) against a bucket the test writes by hand
 * ([ScriptedBucket] through [RecordingBlobStore.bucket]) and the store's clock the test sets. `%test` sets the grace to
 * two hours, the gap bound to six, the safe age is `veyq.blob.upload.retention`'s hour, and a page holds three keys.
 *
 * **Every negative here has its positive on the same predicate**, because "nothing was counted" and "nothing was put on
 * the reclaim list" are also what a tick that did not run produces: each "0" stands beside a "1" in the same pass, and
 * each "not recorded" beside the same key recorded once the one condition under test is lifted. The reclaim list is
 * asserted by the case's own keys, never by a count — every purge test before this one leaves rows behind.
 *
 * The bites are written beside each case as the lever that turns it red; each was pulled once (the CR's retrospective).
 */
@QuarkusTest
class StoreReconcileSliceTest {

    @Inject
    lateinit var sweep: StoreReconcileSweep

    @Inject
    lateinit var reconcile: StoreReconcileStore

    @Inject
    lateinit var reclaim: StoreReclaimStore

    @Inject
    lateinit var blobStore: BlobStore

    private val recorder: RecordingBlobStore get() = blobStore as RecordingBlobStore

    private lateinit var bucket: ScriptedBucket

    private val t0: Instant = Instant.parse("2026-10-17T12:00:00Z")

    /** Older than the safe age (one hour) at [t0]. */
    private val old: Instant = t0.minus(Duration.ofHours(2))

    @BeforeEach
    fun freshBucket() {
        QuarkusTransaction.requiringNew().run {
            StoreOrphanEntity.deleteAll()
            StoreReconcileEntity.deleteAll()
        }
        bucket = ScriptedBucket(digest = "bucket-${UUID.randomUUID()}", storeTime = t0)
        recorder.bucket = bucket
    }

    @AfterEach
    fun forgetBucket() {
        recorder.bucket = null
        (reconcile as RefusingStoreReconcileStore).failClaims = false
    }

    // --- what an orphan is (B1–B8) ---

    /** B1–B3. Lever: drop a table from `StoreReconcileStore.CLAIM_TABLES` → that table's key reads as an orphan. */
    @Test
    fun anObjectAnyClaimRowNamesIsNoOrphanAndAnUnclaimedOneIs() {
        val (userId, scopeId) = seedMemberScope()
        val session = key()
        val blob = key()
        val pending = key()
        val unclaimed = key()
        tx {
            UploadSessionEntity().apply {
                id = UUID.randomUUID().toString(); this.scopeId = scopeId; storageKey = session; chunkCount = 2
                byteSize = TestBlob.SMALL.byteSize; wrappedBlobKey = "Ymxvyi13cmFw"; openerUserId = userId
                lastWindowAt = Instant.now()
            }.persist()
            BlobEntity().apply {
                id = UUID.randomUUID().toString(); this.scopeId = scopeId; storageKey = blob; chunkCount = 1
                byteSize = TestBlob.SMALL.byteSize; wrappedBlobKey = "Ymxvyi13cmFw"
            }.persist()
            reclaim.record(pending, 1)
        }
        listOf(session, blob, pending, unclaimed).forEach { bucket.put("$it/0", 10, old) }

        val pass = completePass()

        assertEquals(4, pass.completedObjects)
        assertEquals(1, pass.completedOrphanObjects, "only the key no row names is an orphan")
        assertEquals(10, pass.completedOrphanBytes)
        assertEquals(setOf(unclaimed), candidates())
    }

    /** B4, B5. Levers: delete the probe case → not-veyq's = 4; widen the pattern to `[^/]+/\d+` → orphans > 0. */
    @Test
    fun theProbeIsIgnoredAndAKeyNotShapedLikeVeyqsIsNeverAnOrphan() {
        bucket.put(StoreCheck.PROBE_KEY, 17, old)
        bucket.put("veyq-pool/captures/2026-10-17", 100, old)
        bucket.put("x/0", 1, old)
        bucket.put("${key()}/01", 1, old)
        val control = key()
        bucket.put("$control/0", 1, old)

        val pass = completePass()

        assertEquals(5, pass.completedObjects)
        assertEquals(3, pass.completedNotVeyqObjects, "the pool's ledger, a non-UUID key, a leading-zero index")
        assertEquals(1, pass.completedOrphanObjects, "the control: a veyq-shaped key no row names")
        assertEquals(setOf(control), candidates())
    }

    /**
     * B6, B18. An object past its claimed count is an orphan — and its key is never a candidate, even confirmed and past
     * the grace, because a reclaim row deletes `0 … n−1` and would take the claimed chunks. Lever for B6: drop
     * `claimed > key.index` → 0 orphans; for B18: let a claimed key become a candidate → it reaches the reclaim list.
     */
    @Test
    fun anObjectPastItsClaimedCountIsCountedAndItsKeyIsNeverEnqueued() {
        val (_, scopeId) = seedMemberScope()
        val claimed = key()
        tx {
            BlobEntity().apply {
                id = UUID.randomUUID().toString(); this.scopeId = scopeId; storageKey = claimed; chunkCount = 2
                byteSize = TestBlob.SMALL.byteSize; wrappedBlobKey = "Ymxvyi13cmFw"
            }.persist()
        }
        (0..2).forEach { bucket.put("$claimed/$it", 10, old) }
        val unclaimed = key()
        bucket.put("$unclaimed/0", 10, old)
        confirm()

        assertEquals(2, completePass().completedOrphanObjects, "chunk 2 of the claimed key, and the unclaimed key's")
        passAt(t0.plus(Duration.ofHours(1)))
        passAt(t0.plus(Duration.ofHours(2)))

        assertTrue(recorded(unclaimed), "the control: an unclaimed key past the grace is enqueued")
        assertFalse(recorded(claimed), "a claimed key is never enqueued")
    }

    /**
     * B7. The bound is exact — one second short is young — and it is the store's clock that reads it: [t0] lies hours
     * behind this process's clock whenever the suite runs. Lever: read the age off `Instant.now()` → the young object
     * is hours old by this process's clock, and counted.
     */
    @Test
    fun theSafeAgeIsReadOnTheStoresClock() {
        val young = key()
        val ofAge = key()
        bucket.put("$young/0", 1, t0.minus(Duration.ofHours(1)).plusSeconds(1))
        bucket.put("$ofAge/0", 1, t0.minus(Duration.ofHours(1)))
        bucket.storeTime = t0

        val pass = completePass()

        assertEquals(1, pass.completedOrphanObjects)
        assertEquals(setOf(ofAge), candidates())
    }

    /** B8. Lever: skip the `stat` → the drained object is counted. */
    @Test
    fun anObjectGoneWhenAskedAgainIsNoOrphan() {
        val drained = key()
        val present = key()
        bucket.put("$drained/0", 1, old)
        bucket.put("$present/0", 1, old)
        bucket.goneAtStat += "$drained/0"

        assertEquals(1, completePass().completedOrphanObjects)
        assertEquals(setOf(present), candidates())
    }

    // --- failures (B9–B12) ---

    /** B9. Lever: reset the cursor on a failure → page 1 counted twice, 12 objects instead of 9. */
    @Test
    fun aFailedPageKeepsThePassesPlaceAndTheNextTickResumesIt() {
        val keys = (1..9).map { key() }.sorted()
        keys.forEach { bucket.put("$it/0", 1, old) }
        val afterPageOne = "${keys[2]}/0"
        bucket.onList = { startAfter -> if (startAfter == afterPageOne) throw IllegalStateException("store went away") }

        sweep.tick()

        val failed = state()
        assertEquals(ReconcileFailure.STORE_UNREACHABLE.name, failed.failure)
        assertEquals(afterPageOne, failed.passCursor)
        assertEquals(3, failed.passObjects)
        assertNull(failed.completedAt, "a failed pass publishes nothing")

        bucket.onList = null
        sweep.tick()

        val done = state()
        assertEquals(9, done.completedObjects)
        assertEquals(9, done.completedOrphanObjects)
        assertNull(done.failure, "a page that succeeds clears the failure")
    }

    /** B10. Lever: swallow the claim query's failure and judge the page against no claims → orphans > 0. */
    @Test
    fun aClaimQueryThatFailsCountsNothingAndCompletesNothing() {
        bucket.put("${key()}/0", 1, old)
        (reconcile as RefusingStoreReconcileStore).failClaims = true

        sweep.tick()

        val row = state()
        assertEquals(ReconcileFailure.DATABASE.name, row.failure)
        assertEquals(0, row.passOrphanObjects)
        assertNull(row.completedAt)
        assertTrue(candidates().isEmpty())
    }

    /** B11. Lever: fall back to this process's clock → the page is judged and counted. */
    @Test
    fun aListingWithoutTheStoresClockIsNotJudged() {
        bucket.put("${key()}/0", 1, old)
        bucket.withoutClock = true

        sweep.tick()

        val row = state()
        assertEquals(ReconcileFailure.NO_STORE_CLOCK.name, row.failure)
        assertEquals(0, row.passObjects)
        assertNull(row.completedAt)
    }

    /** B12. No store that lists → NO_STORE; a store that refuses the listing (HTTP 403) → LIST_DENIED. */
    @Test
    fun aStoreThatCannotOrMayNotListIsNamed() {
        recorder.bucket = null
        sweep.tick()
        assertEquals(ReconcileFailure.NO_STORE.name, state().failure)

        recorder.bucket = bucket
        bucket.put("${key()}/0", 1, old)
        bucket.onList = { throw S3Exception.builder().statusCode(403).message("Access Denied").build() }
        sweep.tick()
        assertEquals(ReconcileFailure.LIST_DENIED.name, state().failure)
        assertEquals(0, state().passObjects)
    }

    // --- the three conditions (B13–B17) ---

    /**
     * B13. An orphan past the grace on an unconfirmed bucket is only reported; confirmed, the same key is enqueued (the
     * control). Lever: drop the confirmation from `StoreReconcileEntity.armed` → enqueued while unconfirmed.
     */
    @Test
    fun withoutTheExclusiveBucketConfirmationNothingIsEnqueued() {
        val orphan = key()
        bucket.put("$orphan/0", 1, old)
        passAt(t0)
        passAt(t0.plus(Duration.ofHours(1)))
        passAt(t0.plus(Duration.ofHours(2)))

        assertFalse(recorded(orphan), "unconfirmed: reported only")
        assertEquals(setOf(orphan), candidates(), "and still a candidate, its clock running")

        confirm()
        passAt(t0.plus(Duration.ofHours(2)))

        assertTrue(recorded(orphan), "confirmed: enqueued at the next completed pass")
        assertTrue(candidates().isEmpty())
        assertEquals(1, state().completedEnqueuedKeys)
    }

    /**
     * B13, its second half. A confirmation is for the bucket it was given for: another bucket voids it, and restarts
     * every clock (STORE_CHANGED). Lever: keep the confirmation across a digest change → enqueued.
     */
    @Test
    fun aConfirmationIsVoidOnceAnotherBucketIsListed() {
        val orphan = key()
        bucket.put("$orphan/0", 1, old)
        confirm()
        passAt(t0)
        bucket.digest = "another-bucket-${UUID.randomUUID()}"
        passAt(t0.plus(Duration.ofHours(1)))
        passAt(t0.plus(Duration.ofHours(3)))
        passAt(t0.plus(Duration.ofHours(4)))

        assertFalse(recorded(orphan))
        val row = state()
        assertNull(row.exclusiveConfirmedAt, "the confirmation was for the other bucket")
        assertEquals(ClocksRestart.STORE_CHANGED.name, row.clocksRestartReason)
        assertEquals(setOf(orphan), candidates(), "counted again, from a clock that started with this bucket")
    }

    /** B14. Lever: measure the grace from the object's `lastModified` instead of first seen → enqueued at the first pass. */
    @Test
    fun aKeyIsDueOnlyOnceTheGraceHasPassedSinceItWasFirstSeen() {
        val orphan = key()
        bucket.put("$orphan/0", 1, t0.minus(Duration.ofDays(400)))
        confirm()
        passAt(t0)
        passAt(t0.plus(Duration.ofHours(1)))
        passAt(t0.plus(Duration.ofHours(2)).minusSeconds(1))
        assertFalse(recorded(orphan), "a second short of the grace since first seen")

        passAt(t0.plus(Duration.ofHours(2)))
        assertTrue(recorded(orphan), "at the grace")
        assertEquals(StoreObjectRef(orphan, 1), reclaimRef(orphan))
    }

    /**
     * B15. A claim that reappears takes the key out: at the next pass's completion (the row is not seen again), and at
     * the enqueue's own re-check when it appears after the key's page was listed. Lever: drop the re-check → the second
     * key is enqueued with a row claiming it.
     */
    @Test
    fun aClaimThatReappearsRemovesTheCandidate() {
        val (_, scopeId) = seedMemberScope()
        val reclaimed = "0" + key().drop(1)
        val late = "1" + key().drop(1)
        val fillers = (1..3).map { "f" + key().drop(1) }
        listOf(reclaimed, late).forEach { bucket.put("$it/0", 1, old) }
        fillers.forEach { bucket.put("$it/0", 1, old) }
        confirm()
        passAt(t0)
        assertTrue(candidates().containsAll(listOf(reclaimed, late)))

        // A restore of the right dump brings a row for the first key back.
        seedBlob(scopeId, reclaimed)
        passAt(t0.plus(Duration.ofHours(1)))
        assertFalse(reclaimed in candidates(), "explained again: no longer a candidate")

        // The second key's row appears after its page (page one) was listed, before the completing page.
        bucket.onList = { startAfter ->
            if (startAfter != null) {
                bucket.onList = null
                seedBlob(scopeId, late)
            }
        }
        bucket.storeTime = t0.plus(Duration.ofHours(2))
        sweep.tick()
        bucket.onList = null

        assertFalse(recorded(reclaimed))
        assertFalse(recorded(late), "the enqueue asked the claim tables again")
        fillers.forEach { assertTrue(recorded(it), "the control: the fillers were due and enqueued in the same pass") }
    }

    /**
     * B16 (backend half — the restore tool's half is `instance_backup_selftest.py`). Two listed pages further apart than
     * the gap bound restart every clock. Lever: skip the gap rule → enqueued at the first pass after the gap.
     */
    @Test
    fun aGapLongerThanTheBoundRestartsEveryClock() {
        val orphan = key()
        bucket.put("$orphan/0", 1, old)
        confirm()
        passAt(t0)
        passAt(t0.plus(Duration.ofHours(7)))

        assertFalse(recorded(orphan), "the clock restarted at the gap")
        assertEquals(ClocksRestart.GAP.name, state().clocksRestartReason)

        passAt(t0.plus(Duration.ofHours(8)))
        assertFalse(recorded(orphan))
        passAt(t0.plus(Duration.ofHours(9)))
        assertTrue(recorded(orphan), "the control: due two hours after the restart")
    }

    /** B17. Lever: leave the pause out of `StoreReconcileEntity.armed` → enqueued while paused. */
    @Test
    fun aPauseStopsTheEnqueueingAndKeepsTheCounting() {
        val orphan = key()
        bucket.put("$orphan/0", 1, old)
        confirm()
        reconcile.pause(true, Instant.now())
        passAt(t0)
        passAt(t0.plus(Duration.ofHours(1)))
        passAt(t0.plus(Duration.ofHours(2)))

        assertFalse(recorded(orphan))
        assertEquals(1, state().completedOrphanObjects, "still counted")
        assertEquals(StoreReconcileView.Verdict.PAUSED, view().verdict)

        reconcile.pause(false, Instant.now())
        passAt(t0.plus(Duration.ofHours(3)))
        assertTrue(recorded(orphan), "resumed: enqueued at the next completed pass")
    }

    /**
     * B19. A page another tick wrote first is not written again: the second tick runs whole inside the first one's
     * listing, and the first one's write loses the compare-and-set. Lever: drop the compare-and-set → 10 objects counted.
     */
    @Test
    fun aPageAnotherTickWroteFirstIsCountedOnce() {
        (1..5).forEach { bucket.put("${key()}/0", 1, old) }
        bucket.onList = { _ ->
            bucket.onList = null
            sweep.tick()
        }

        sweep.tick()

        assertEquals(5, state().completedObjects)
        assertNull(state().passStartedAt)
    }

    /** The read the console renders: the candidates with their first deletion date, beside the verdict. */
    @Test
    fun theReadCarriesTheCandidatesAndTheirDueDate() {
        val orphan = key()
        bucket.put("$orphan/0", 7, old)
        bucket.put("$orphan/1", 5, old)
        passAt(t0)

        val view = view()
        assertEquals(StoreReconcileView.Verdict.NOT_CONFIRMED, view.verdict)
        assertEquals(1, view.candidates.keys)
        assertEquals(2, view.candidates.objects)
        assertEquals(12, view.candidates.bytes)
        assertEquals(t0, view.candidates.oldestFirstSeenAt)
        assertEquals(t0.plus(Duration.ofHours(2)), view.candidates.firstDueAt)
        assertNotNull(view.lastPass)

        confirm()
        assertEquals(StoreReconcileView.Verdict.ARMED, view().verdict)
    }

    // --- helpers ---

    private fun key(): String = UUID.randomUUID().toString()

    private fun <T> tx(body: () -> T): T = QuarkusTransaction.requiringNew().call(body)

    private fun state(): StoreReconcileEntity = tx { StoreReconcileEntity.findById(StoreReconcileEntity.ID)!! }

    private fun candidates(): Set<String> = tx { StoreOrphanEntity.listAll().map { it.storageKey }.toSet() }

    private fun recorded(storageKey: String): Boolean = tx { StoreReclaimEntity.count("storageKey", storageKey) > 0 }

    private fun reclaimRef(storageKey: String): StoreObjectRef =
        tx { StoreReclaimEntity.find("storageKey", storageKey).list().single().let { StoreObjectRef(it.storageKey, it.chunkCount) } }

    private fun confirm() = reconcile.confirmExclusive(bucket.digest, Instant.now())

    private fun view(): StoreReconcileView = reconcile.view(bucket.digest, Duration.ofHours(2), t0)

    private fun seedBlob(scopeId: String, storageKey: String) = tx {
        BlobEntity().apply {
            id = UUID.randomUUID().toString(); this.scopeId = scopeId; this.storageKey = storageKey; chunkCount = 1
            byteSize = TestBlob.SMALL.byteSize; wrappedBlobKey = "Ymxvyi13cmFw"
        }.persist()
    }

    /** One whole pass at the store time [at]: the test config starts a pass at every tick, and these buckets fit one. */
    private fun passAt(at: Instant): StoreReconcileEntity {
        bucket.storeTime = at
        return completePass()
    }

    private fun completePass(): StoreReconcileEntity {
        val before = bucket.listings
        sweep.tick()
        val row = state()
        assertTrue(bucket.listings > before, "the tick listed the bucket")
        assertNull(row.passStartedAt, "the pass completed within the tick")
        assertNull(row.failure)
        return row
    }
}
