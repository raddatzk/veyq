package com.veyq.core.blobstore

import com.veyq.core.blobstore.BlobStore.Companion.chunkKey
import com.veyq.core.blobstore.BlobStore.Companion.chunkKeys
import com.veyq.core.config.VeyqConfig
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * [StoreReclaimSweep] — the only component in veyq that deletes store objects (ADR 0062 Decision 3).
 *
 * **What these cases exist to catch is a green that means nothing.** Before Decision 2, `BlobStore.delete`
 * returned `Unit` and [S3BlobStore] swallowed every per-key failure, so a sweep built on that signature would
 * have cleared its reclaim rows whether or not the bytes went — and it would have passed every test here,
 * because [RecordingBlobStore] *threw* on a refused key where the real store could not fail at all. The test
 * seam was stronger than production. So each case below asserts on the reclaim row's own state, which is the
 * thing a swallowed failure would silently get wrong, and each pairs its negative with a live control on the
 * same tick — otherwise "the sweep held the failure" is indistinguishable from "the sweep did not run".
 *
 * [RecordingBlobStore.failOnKeyMarker] is an `@ApplicationScoped` singleton in a JVM shared across test classes:
 * every case that arms it restores it in a `finally`, or every later reclaim in this JVM dies on a marker
 * nobody can see.
 */
@QuarkusTest
class StoreReclaimSweepSliceTest {

    @Inject
    lateinit var reclaim: StoreReclaimStore

    @Inject
    lateinit var sweep: StoreReclaimSweep

    @Inject
    lateinit var blobStore: BlobStore

    @Inject
    lateinit var config: VeyqConfig

    private val recorder: RecordingBlobStore get() = blobStore as RecordingBlobStore

    /**
     * The not-before (cr-reclaim-waits-out-the-presign-ttl). Several paths drop the row naming a key inside the
     * presign TTL of the last window signed for it — a media purged straight from the trash, a replaced or
     * cleared cover — and a PUT on such a URL still lands after the drop. A sweep that deleted at the next tick
     * would let that PUT re-create the object under a key nothing names, which no quota counts and no sweep ever
     * deletes. So a recorded key is not due until `veyq.media.upload.retention` — the bound that already says
     * "after this, no signed PUT can still land" — has passed.
     *
     * Three rows on one tick, each the others' control:
     * - recorded **now**: the property itself;
     * - recorded **TTL − 1s ago**: a URL signed the instant before its row was dropped is still valid, so this one
     *   pins the floor at the TTL rather than at "some delay" — a bound one second shorter deletes it;
     * - **already due** (`nextAttemptAt` in the past): the positive control on the same predicate, without which
     *   "nothing was deleted" is indistinguishable from "the sweep did not run".
     * Then the first row is aged one second past the bound and must go, keys and row both.
     */
    @Test
    fun aRecordedKeyIsNotDeletedWhileAPutSignedForItCanStillLand() {
        val bound = config.media().upload().retention()
        val ttl = Duration.ofSeconds(config.blobStore().presignTtlSeconds().toLong())
        val freshKey = UUID.randomUUID().toString()
        val insideTtlKey = UUID.randomUUID().toString()
        val dueKey = UUID.randomUUID().toString()
        clearBacklog()
        val freshId = seed(freshKey, chunkCount = 2)
        val insideTtlId = seed(insideTtlKey, chunkCount = 1)
        backdateReclaimRows(ttl.minusSeconds(1)) { it.id == insideTtlId }
        val dueId = seed(dueKey, chunkCount = 1)
        makeDue(dueId)

        recorder.reset()
        sweep.run()

        // the control: a due row on the same tick is deleted and settled
        assertNull(row(dueId), "a row past its not-before must be settled")
        assertTrue(chunkKey(dueKey, 0) in recorder.deletedKeys) {
            "a due row's keys must reach the store; handed: ${recorder.deletedKeys}"
        }
        // the property: neither row whose key a signed PUT can still reach was touched
        assertTrue(recorder.deletedKeys.none { it.startsWith("$freshKey/") }) {
            "a key recorded just now must not be deleted while a PUT signed for it can still land; " +
                "handed: ${recorder.deletedKeys}"
        }
        assertTrue(recorder.deletedKeys.none { it.startsWith("$insideTtlKey/") }) {
            "a key recorded TTL − 1s ago must not be deleted: a URL signed just before its row drop is still " +
                "valid; handed: ${recorder.deletedKeys}"
        }
        assertEquals(0, requireRow(freshId, "a row inside its not-before must stay on the list").attempts)
        assertEquals(0, requireRow(insideTtlId, "a row inside the TTL must stay on the list").attempts)

        // past the bound, the same row goes — the wait is a wait, not a drop
        backdateReclaimRows(bound.plusSeconds(1)) { it.id == freshId }
        recorder.reset()
        sweep.run()
        assertNull(row(freshId), "once no signed PUT can land, the row must be settled")
        assertTrue(recorder.deletedKeys.containsAll(chunkKeys(freshKey, 2))) {
            "every chunk of the aged row must reach the store; handed: ${recorder.deletedKeys}"
        }
        QuarkusTransaction.requiringNew().run { StoreReclaimEntity.deleteById(insideTtlId) }
    }

    /**
     * The retry path, and its positive control on the same tick. A store that refuses one key must leave that
     * row standing — counted and deferred — while the row next to it is reclaimed and dropped. Without the
     * second half, a sweep that did nothing at all would satisfy the first perfectly.
     */
    @Test
    fun aRefusedKeyKeepsItsRowCountedAndDeferredWhileTheRestOfTheTickIsReclaimed() {
        val marker = "rf${UUID.randomUUID().toString().take(8)}" // short: storage_key is VARCHAR(64)
        val refusedKey = "$marker-${UUID.randomUUID()}"
        val cleanKey = UUID.randomUUID().toString()
        clearBacklog()
        val refusedId = seed(refusedKey, chunkCount = 2)
        val cleanId = seed(cleanKey, chunkCount = 1)
        ageReclaimRowsPastThePresignBound(config)

        recorder.reset()
        recorder.failOnKeyMarker = marker
        val tick = Instant.now()
        try {
            sweep.run()

            // the control: the unmarked row went through, keys and all
            assertNull(row(cleanId), "a row the store accepted must be settled and gone")
            assertTrue(chunkKey(cleanKey, 0) in recorder.deletedKeys) {
                "the accepted key must reach the store; handed: ${recorder.deletedKeys}"
            }

            // the assertion: the refused row SURVIVES, with the attempt counted and the retry pushed out
            val held = requireRow(refusedId, "a row the store refused must not be settled")
            assertEquals(1, held.attempts, "the refusal must be counted on the row")
            assertNull(held.abandonedAt, "one refusal is far below the give-up threshold")
            // against the tick, not against `createdAt`: an aged row's not-before already lies after its
            // recording, so that comparison would hold with no deferral at all
            assertTrue(held.nextAttemptAt.isAfter(tick)) {
                "the retry must be deferred past the tick that was refused, not left due on the next tick"
            }
            // and NOTHING of it was recorded as handed over — a store that refuses deleted nothing
            assertTrue(recorder.deletedKeys.none { it.startsWith(refusedKey) }) {
                "a refused key must not be recorded as deleted; handed: ${recorder.deletedKeys}"
            }
        } finally {
            recorder.failOnKeyMarker = null
        }

        // disarm and run again: the same row now settles and its keys arrive. This is what makes the case above
        // a statement about the REFUSAL rather than about this row being unreachable for some other reason.
        recorder.reset()
        makeDue(refusedId)
        sweep.run()
        assertNull(row(refusedId), "once the store accepts, the retried row is settled")
        assertTrue(recorder.deletedKeys.containsAll(chunkKeys(refusedKey, 2))) {
            "every chunk of the retried row must reach the store; handed: ${recorder.deletedKeys}"
        }
    }

    /**
     * The give-up state — the row that makes the leak countable (Decision 5). A permanently unreachable store
     * still loses the bytes; what this pins is that the loss stops being invisible.
     *
     * The negative control is the tick before last: at `maxAttempts - 1` refusals the row must NOT be abandoned,
     * or "abandons at the threshold" is indistinguishable from "abandons on the first refusal".
     *
     * "Logged once" is asserted structurally rather than through a log harness: the abandoned row leaves the due
     * set, so a further tick cannot re-enter the branch that logs. The last two assertions are that property.
     */
    @Test
    fun aRowRefusedToTheThresholdIsAbandonedAndThenLeftAlone() {
        val marker = "rf${UUID.randomUUID().toString().take(8)}" // short: storage_key is VARCHAR(64)
        val key = "$marker-${UUID.randomUUID()}"
        val maxAttempts = config.storeReclaim().maxAttempts()
        clearBacklog()
        val id = seed(key, chunkCount = 1)

        recorder.failOnKeyMarker = marker
        try {
            // one tick short of the threshold: refused every time, and still a live work item
            repeat(maxAttempts - 1) {
                makeDue(id)
                sweep.run()
            }
            val control = requireRow(id, "a row below the threshold must still be on the list")
            assertEquals(maxAttempts - 1, control.attempts)
            assertNull(control.abandonedAt) { "$maxAttempts attempts is the threshold, not ${maxAttempts - 1}" }

            makeDue(id)
            sweep.run()

            val abandoned = requireRow(id, "an abandoned row is KEPT — it is what makes the leak countable")
            assertEquals(maxAttempts, abandoned.attempts)
            assertNotNull(abandoned.abandonedAt, "at the threshold the sweep gives up and says so on the row")

            // and it is now inert: a further tick neither retries it nor re-logs the give-up
            sweep.run()
            val after = requireRow(id, "the abandoned row stays on the list")
            assertEquals(maxAttempts, after.attempts, "an abandoned row must not be attempted again")
            assertEquals(abandoned.abandonedAt, after.abandonedAt, "nor re-stamped, which is where a re-log would come from")
        } finally {
            recorder.failOnKeyMarker = null
            QuarkusTransaction.requiringNew().run { StoreReclaimEntity.deleteById(id) }
        }
    }

    // --- helpers ---

    private fun seed(storageKey: String, chunkCount: Int): String {
        QuarkusTransaction.requiringNew().run { reclaim.record(storageKey, chunkCount) }
        return QuarkusTransaction.requiringNew().call { StoreReclaimEntity.find("storageKey", storageKey).list().single().id }
    }

    private fun row(id: String): StoreReclaimEntity? = QuarkusTransaction.requiringNew().call { StoreReclaimEntity.findById(id) }

    /** JUnit's `assertNotNull` is `void` in Kotlin, so it cannot narrow the type the assertions below read. */
    private fun requireRow(id: String, why: String): StoreReclaimEntity = row(id) ?: fail(why)

    /** Simulate the backoff having elapsed — the same shape as the purge tests backdating `created_at`. */
    private fun makeDue(id: String) = QuarkusTransaction.requiringNew().run {
        StoreReclaimEntity.findById(id)!!.apply { nextAttemptAt = Instant.now().minusSeconds(1) }.persist()
    }

    /**
     * The sweep takes a bounded batch of the OLDEST due rows and every purge in every earlier test class of this
     * JVM leaves rows behind, so a case's own rows are the newest in the table and can be crowded out of the
     * batch. Dropping the backlog first is what keeps these assertions about the sweep rather than about how
     * many tests ran before them.
     */
    private fun clearBacklog() = QuarkusTransaction.requiringNew().run { StoreReclaimEntity.deleteAll() }
}
