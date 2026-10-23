package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.identity.PrincipalKey
import me.raddatz.veyq.core.testing.RACE_ROUNDS
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.path.json.JsonPath
import jakarta.inject.Inject
import jakarta.persistence.LockModeType
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The keyset a live gallery pages and polls on must order rows the way they COMMIT (cr-gallery-list-paging § Measured
 * substrate, Q1): a client that has passed a cursor never sees a row land behind it later. Two writes in one node, the
 * first stamped before the second but committed after it — here forced: a third transaction holds the first medium's
 * row, so its restore has taken its stamp and waits while a meta edit of the second edits, bumps and commits. (Not two
 * meta edits: each takes the node row's lock first since ADR 0109 point 6's epoch gate, so they cannot overlap.) A client polls in
 * between, advances its cursor past the second edit, and polls again once the first has committed: the first edit
 * must arrive. Deterministic, not likely: the order is forced, so one round already shows a defect; it races
 * [RACE_ROUNDS] rounds on fresh rows anyway, and runs on MariaDB through `scripts/run_backend_races_on_mariadb.py`.
 */
@QuarkusTest
class MediaKeysetRaceSliceTest {

    @Inject
    lateinit var store: MediaStore

    @Test
    fun anEditStampedBeforeAnotherButCommittedAfterItStillReachesTheNextPoll() {
        repeat(RACE_ROUNDS) { round ->
            val (userId, scopeId) = seedMemberScope()
            val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
            val (first, second) = MediaSeed.photos(scopeId, memory, 2, derivatives = false)
            store.trashMedia(scopeId, first, userId)
            var held = poll(userId, scopeId, memory, ifNoneMatch = null, since = null)

            val locked = CountDownLatch(1)
            val release = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val holder = pool.submit {
                    QuarkusTransaction.requiringNew().run {
                        MediaEntity.findById(first, LockModeType.PESSIMISTIC_WRITE)
                        locked.countDown()
                        release.await(10, TimeUnit.SECONDS)
                    }
                }
                assertTrue(locked.await(10, TimeUnit.SECONDS), "round $round: the holder took the row")
                // The first write (a restore, which takes no node lock first) takes its stamp and then waits for the held row.
                val slow = pool.submit { store.restoreMedia(scopeId, first) }
                Thread.sleep(WAIT_MS) // the slow edit is stamped and waiting, and the clock has moved on
                store.setMeta(scopeId, second, "c2Vjb25k", 0, 0, PrincipalKey.User(userId)) // stamped later, committed first

                held = poll(userId, scopeId, memory, ifNoneMatch = held.revision, since = held.cursor)
                assertEquals(listOf(second), held.ids, "round $round: the poll between the two commits sees the second edit")

                release.countDown()
                holder.get(10, TimeUnit.SECONDS)
                slow.get(10, TimeUnit.SECONDS)
            } finally {
                release.countDown()
                pool.shutdownNow()
            }

            val after = poll(userId, scopeId, memory, ifNoneMatch = held.revision, since = held.cursor)
            assertEquals(200, after.status, "round $round: the first edit's commit moved the revision")
            assertEquals(listOf(first), after.ids, "round $round: the first write, committed last, reaches the next poll")
        }
    }

    private data class Poll(val status: Int, val ids: List<String>, val revision: String?, val cursor: String?)

    private fun poll(userId: String, scopeId: String, nodeId: String, ifNoneMatch: String?, since: String?): Poll {
        val r = given().header("Authorization", bearer(userId))
            .apply { if (ifNoneMatch != null) header("If-None-Match", ifNoneMatch) }
            .apply { if (since != null) queryParam("since", since) }
            .`when`().get("/api/scopes/$scopeId/nodes/$nodeId/media")
        if (r.statusCode == 304) return Poll(304, emptyList(), ifNoneMatch, since)
        val j: JsonPath = r.then().statusCode(200).extract().jsonPath()
        val ids = j.getList<String>("media.mediaId")
        val cursor = j.getString("cursor") ?: since
        return Poll(200, ids, j.getInt("mediaRevision").toString(), cursor)
    }

    private companion object {
        const val WAIT_MS = 300L
    }
}
