package me.raddatz.veyq.core.tasks

import com.fasterxml.jackson.databind.ObjectMapper
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.membership.TestGrants
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.Callable
import me.raddatz.veyq.core.testing.raceRounds
import me.raddatz.veyq.core.testing.OVERLAP_HOLD_MS
import me.raddatz.veyq.core.testing.holdForOverlap

/**
 * The queued scope rotation's lifecycle (uc.spaces.complete-queued-rotation): who is offered the job, when a later
 * event may join it, what a completion must prove, and that a completion which lost its lease writes nothing.
 *
 * Every job here is originated through the real tenant-removal path (a tenant owner cutting a co-shared group
 * member), claimed through the real claim, and its rotation committed through the real `POST …/epochs` — a seeded
 * row would test the reader, not the path. The two races hold one transaction open on a latch and fire the other
 * party inside that window; H2 in `MODE=MariaDB` blocks the second writer on the first's row lock and re-evaluates
 * its `WHERE` after the first commits, which is what makes them deterministic rather than likely.
 */
@QuarkusTest
class QueuedRotationSliceTest {

    @Inject lateinit var store: TasksStore
    @Inject lateinit var handler: TasksHandler

    // --- D1: owners only (#180) ------------------------------------------------------------------------------

    @Test
    fun aNonOwnerIsNeverOfferedARotationJob() {
        val t = tenant()
        val owner = t.member()
        val plain = t.member()
        val cut = t.member()
        val group = t.group(owner)
        t.join(group, plain)
        t.join(group, cut)
        t.remove(cut)
        val job = liveRotationJobs(group).single()

        // The mixed-tag leg first, while the rotation job is still unclaimed: a member behind the epoch makes the plain
        // member's claim reconcile a delivery job, which they may work; the rotation job is never theirs.
        t.joinBehind(group, seedUser())
        claim(plain, CapabilityTags.GRANT_DELIVERY, CapabilityTags.SCOPE_ROTATION).then().statusCode(200)
            .body("capabilityTag", equalTo(CapabilityTags.GRANT_DELIVERY))
        claim(plain, CapabilityTags.GRANT_DELIVERY, CapabilityTags.SCOPE_ROTATION).then().statusCode(204)

        claim(plain, CapabilityTags.SCOPE_ROTATION).then().statusCode(204)
        // Positive control on the same job: an owner is offered exactly it.
        claim(owner, CapabilityTags.SCOPE_ROTATION).then().statusCode(200).body("jobId", equalTo(job))
    }

    // --- D3: an event joins only an unclaimed job (#178 (2)) --------------------------------------------------

    @Test
    fun aRemovalDuringALeaseGetsItsOwnJob() {
        val t = tenant()
        val worker = t.member()
        val first = t.member()
        val second = t.member()
        val group = t.group(worker)
        t.join(group, first)
        t.join(group, second)
        t.remove(first)
        val leased = claimJob(worker)

        t.remove(second)

        val live = liveRotationJobs(group)
        assertEquals(2, live.size, "a removal while the job is leased queues a job of its own")
        val fresh = live.single { it != leased }
        assertEquals(JobEntity.PENDING, statusOf(fresh))

        // The leased job completes on its holder's own commit; the fresh one is still real work for an owner.
        commit(worker, group, listOf(worker)).then().statusCode(201)
        complete(worker, leased).then().statusCode(204)
        assertEquals(listOf(fresh), liveRotationJobs(group))
        claim(worker, CapabilityTags.SCOPE_ROTATION).then().statusCode(200).body("jobId", equalTo(fresh))
    }

    @Test
    fun aClaimRacingAJoinLandsAfterTheEvent() {
        // D3's lock: the join reads the PENDING job under PESSIMISTIC_WRITE, so a claim's compare-and-set on that
        // row waits for the event to commit — the claim, and the directory read after it, come after the event.
        raceRounds(ROUNDS) { round ->
            val t = tenant()
            val worker = t.member()
            val cut = t.member()
            val group = t.group(worker)
            t.join(group, cut)
            t.remove(cut)
            val pending = liveRotationJobs(group).single()

            val joined = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val event = pool.submit(
                    Callable {
                        QuarkusTransaction.requiringNew().call {
                            store.originateScopeRotation(group, MAX_ATTEMPTS)
                            joined.countDown()
                            holdForOverlap() // the event's commit, held back
                        }
                    },
                )
                joined.await()
                val claim: Future<Response> = pool.submit<Response> { claim(worker, CapabilityTags.SCOPE_ROTATION) }
                Thread.sleep(OVERLAP_HOLD_MS / 2)
                assertFalse(claim.isDone, "round $round: the claim returned while the event joining its job was uncommitted")
                val overlap = event.get(5, TimeUnit.SECONDS)
                val answer = claim.get(5, TimeUnit.SECONDS)
                overlap.requireForced()
                assertEquals(200, answer.statusCode(), "round $round")
                assertEquals(pending, answer.path<String>("jobId"), "round $round: the claim holds the job the event joined")
                assertEquals(listOf(pending), liveRotationJobs(group), "round $round: the join created no second job")
            } finally {
                pool.shutdownNow()
            }
        }
    }

    // --- D8: a completion needs its holder's commit during the lease (#207) -----------------------------------

    @Test
    fun aRotationCompletionWithoutTheHoldersCommitIsRefused() {
        val t = tenant()
        val worker = t.member()
        val other = t.member()
        val cut = t.member()
        val group = t.group(worker)
        t.join(group, other, MembershipEntity.OWNER)
        t.join(group, cut)
        t.remove(cut)
        val job = claimJob(worker)

        complete(worker, job).then().statusCode(409).body("code", equalTo("rotation_not_committed"))
        assertEquals(JobEntity.LEASED to worker, leaseOf(job), "a refused completion keeps the lease")

        // Somebody else's commit during the lease is not the holder's.
        commit(other, group, listOf(worker, other)).then().statusCode(201)
        complete(worker, job).then().statusCode(409).body("code", equalTo("rotation_not_committed"))
        assertEquals(JobEntity.LEASED to worker, leaseOf(job))
    }

    @Test
    fun aCommitBeforeTheLeaseDoesNotCompleteTheJob() {
        val t = tenant()
        val worker = t.member()
        val first = t.member()
        val second = t.member()
        val group = t.group(worker)
        t.join(group, first)
        t.join(group, second)
        t.remove(first)
        val held = claimJob(worker)
        t.remove(second) // queues a second, still PENDING job beside the leased one
        val pending = liveRotationJobs(group).single { it != held }

        commit(worker, group, listOf(worker)).then().statusCode(201) // during `held`'s lease, before `pending`'s
        complete(worker, held).then().statusCode(204)
        assertEquals(pending, claimJob(worker))
        complete(worker, pending).then().statusCode(409).body("code", equalTo("rotation_not_committed"))

        // Positive control: a commit during this lease completes it, and a repeated completion is the no-op.
        commit(worker, group, listOf(worker)).then().statusCode(201)
        complete(worker, pending).then().statusCode(204)
        assertEquals(JobEntity.DONE, statusOf(pending))
        complete(worker, pending).then().statusCode(204)
    }

    @Test
    fun aReclaimedJobKeepsTheCommitItsEarlierHolderMade() {
        // The mark is not cleared on reclaim: every holder's lease starts after the first claim, and every joined
        // event committed before it, so any holder's commit during any lease of the job is after all its events.
        val t = tenant()
        val worker = t.member()
        val other = t.member()
        val cut = t.member()
        val group = t.group(worker)
        t.join(group, other, MembershipEntity.OWNER)
        t.join(group, cut)
        t.remove(cut)
        val job = claimJob(worker)
        commit(worker, group, listOf(worker, other)).then().statusCode(201)
        expireLease(job)

        assertEquals(job, claimJob(other))
        complete(other, job).then().statusCode(204)
        assertEquals(JobEntity.DONE, statusOf(job))
    }

    // --- D11: a completion lands only for the worker holding the lease when it lands (#235) -------------------

    @Test
    fun aDeliveryCompletionThatLostItsLeaseWritesNothing() {
        raceRounds(ROUNDS) { round ->
            val t = tenant()
            val stale = t.member(MembershipEntity.OWNER)
            val reclaimer = t.member(MembershipEntity.OWNER)
            val target = t.joinBehind(t.id, seedUser())
            val job = claimJob(stale, CapabilityTags.GRANT_DELIVERY)
            expireLease(job)

            val answer = raceTheReclaim(reclaimer, CapabilityTags.GRANT_DELIVERY) {
                given().header("Authorization", bearer(stale)).contentType(ContentType.JSON)
                    .body("""{"sealedResult":"$LOST_GRANT","epoch":1,${TestGrants.SIGNATURE_JSON}}""")
                    .`when`().post("/api/tasks/$job/complete")
            }

            assertEquals(409, answer.statusCode(), "round $round: the stale holder's completion")
            assertEquals("not_lease_holder", answer.path<String>("code"), "round $round")
            assertEquals(JobEntity.LEASED to reclaimer, leaseOf(job), "round $round: the reclaimer keeps the job")
            assertEquals(GRANT, grantOf(target), "round $round: a lost completion writes no grant")
        }
        // Positive control, same harness minus the reclaim: the holder's completion lands.
        val t = tenant()
        val holder = t.member(MembershipEntity.OWNER)
        val target = t.joinBehind(t.id, seedUser())
        val job = claimJob(holder, CapabilityTags.GRANT_DELIVERY)
        given().header("Authorization", bearer(holder)).contentType(ContentType.JSON)
            .body("""{"sealedResult":"$LOST_GRANT","epoch":1,${TestGrants.SIGNATURE_JSON}}""")
            .`when`().post("/api/tasks/$job/complete").then().statusCode(204)
        assertEquals(LOST_GRANT, grantOf(target))
    }

    @Test
    fun aRotationCompletionThatLostItsLeaseWritesNothing() {
        raceRounds(ROUNDS) { round ->
            val t = tenant()
            val stale = t.member()
            val reclaimer = t.member()
            val cut = t.member()
            val group = t.group(stale)
            t.join(group, reclaimer, MembershipEntity.OWNER)
            t.join(group, cut)
            t.remove(cut)
            val job = claimJob(stale)
            commit(stale, group, listOf(stale, reclaimer)).then().statusCode(201) // the mark, set in its own lease
            expireLease(job)

            val answer = raceTheReclaim(reclaimer, CapabilityTags.SCOPE_ROTATION) { complete(stale, job) }

            assertEquals(409, answer.statusCode(), "round $round: the stale holder's completion")
            assertEquals(JobEntity.LEASED to reclaimer, leaseOf(job), "round $round: the reclaimer keeps the job")
        }
    }

    // --- D6: server-originated tags are refused at enqueue ------------------------------------------------------

    @Test
    fun everyServerOriginatedTagIsRefusedAtEnqueue() {
        // Derived from the registry, never listed: a third server-side tag is refused without anyone remembering it.
        val tags = ObjectMapper().readTree(File("../../contracts/tasks/tags/v1.json")).path("tags")
        val serverTags = tags.fields().asSequence()
            .filter { it.value.path("originator").asText().startsWith("server-") }
            .map { it.value.path("tag").asText() }.toList()
        assertTrue(CapabilityTags.SCOPE_ROTATION in serverTags && CapabilityTags.GRANT_DELIVERY in serverTags, "$serverTags")

        val t = tenant()
        val worker = t.member(MembershipEntity.OWNER)
        serverTags.forEach { tag ->
            enqueue(worker, t.id, tag).then().statusCode(400).body("code", equalTo("invalid_request"))
            assertEquals(0L, jobsTagged(t.id, tag), "$tag: a refused enqueue writes no job")
        }
        // Positive control: a content tag enqueues.
        enqueue(worker, t.id, "deadbeef").then().statusCode(201)
        assertEquals(1L, jobsTagged(t.id, "deadbeef"))
    }

    // --- harness --------------------------------------------------------------------------------------------

    /**
     * [reclaimer] claims [tag] — the lazy reclaim of an expired lease — through the real handler inside a transaction
     * this test holds open, and [stale] fires inside that window. Returns [stale]'s answer, which can only arrive
     * once the reclaim has committed.
     */
    private fun raceTheReclaim(reclaimer: String, tag: String, stale: () -> Response): Response {
        val reclaimed = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val reclaim = pool.submit(
                Callable {
                    QuarkusTransaction.requiringNew().call {
                        assertNotNull(handler.claim(reclaimer, listOf(tag), null), "the reclaim must win the job")
                        reclaimed.countDown()
                        holdForOverlap() // the reclaim's commit, held back
                    }
                },
            )
            reclaimed.await()
            val answer = stale()
            reclaim.get(5, TimeUnit.SECONDS).requireForced() // a stale side that came after the commit raced nothing
            return answer
        } finally {
            pool.shutdownNow()
        }
    }

    /** A fresh ACTIVE tenant with an owner of its root, and the moves the tests make in it. */
    private fun tenant(): Tenant {
        val owner = seedUser()
        return Tenant(seedTenant(owner = owner), owner)
    }

    private inner class Tenant(val id: String, val owner: String) {
        /** A tenant member (root membership, granted). */
        fun member(role: String = MembershipEntity.MEMBER): String = seedUser().also { membership(it, id, role) }

        /** A GROUP owned by [ownerId], who holds its grant. */
        fun group(ownerId: String): String {
            val scopeId = UUID.randomUUID().toString()
            QuarkusTransaction.requiringNew().run {
                ScopeEntity().apply {
                    this.id = scopeId; tenantId = this@Tenant.id; kind = ScopeEntity.GROUP
                    encryptedName = "bmFtZQ=="
                }.persist()
            }
            membership(ownerId, scopeId, MembershipEntity.OWNER)
            return scopeId
        }

        fun join(scopeId: String, userId: String, role: String = MembershipEntity.MEMBER) = membership(userId, scopeId, role)

        /**
         * A membership on [scopeId] (and the root, for a group) BEHIND its epoch — a delivery target, the one state a
         * delivery serves now that every membership is born with a grant (ADR 0111). A scope still at `E_0` is first
         * rotated to `E_1`, its grant-holders re-granted as a rotation would; the member keeps its `E_0` [GRANT].
         * Returns its id.
         */
        fun joinBehind(scopeId: String, userId: String): String {
            if (scopeId != id) membership(userId, id, MembershipEntity.MEMBER)
            QuarkusTransaction.requiringNew().run {
                val scope = ScopeEntity.findById(scopeId)!!
                if (scope.currentEpoch == 0) {
                    scope.currentEpoch = 1
                    MembershipEntity.update("grantedEpoch = 1 where scopeId = ?1 and wrappedGrant is not null", scopeId)
                }
            }
            return membership(userId, scopeId, MembershipEntity.MEMBER)
        }

        /** The tenant owner removes [userId] from the tenant — the real cut that queues a co-shared group's rotation. */
        fun remove(userId: String) {
            given().header("Authorization", bearer(owner)).`when`().delete("/api/tenants/$id/members/$userId")
                .then().statusCode(204)
        }

        private fun membership(userId: String, scopeId: String, role: String, grant: String = GRANT): String {
            val membershipId = UUID.randomUUID().toString()
            QuarkusTransaction.requiringNew().run {
                MembershipEntity().apply {
                    this.id = membershipId; this.userId = userId; tenantId = this@Tenant.id; this.scopeId = scopeId
                    this.role = role; wrappedGrant = grant; createdAt = Instant.now()
                }.persist()
            }
            return membershipId
        }
    }

    private fun claim(userId: String, vararg tags: String) =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body(tags.joinToString(",", prefix = """{"capabilityTags":[""", postfix = "]}") { "\"$it\"" })
            .`when`().post("/api/tasks/claim")

    private fun claimJob(userId: String, tag: String = CapabilityTags.SCOPE_ROTATION): String =
        claim(userId, tag).then().statusCode(200).extract().path("jobId")

    private fun complete(userId: String, jobId: String) =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON).body("{}")
            .`when`().post("/api/tasks/$jobId/complete")

    private fun enqueue(userId: String, scopeId: String, tag: String) =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"scopeId":"$scopeId","capabilityTag":"$tag","sealedBody":"AQ=="}""")
            .`when`().post("/api/tasks/enqueue")

    /** A well-formed rotation commit at the scope's next epoch, granting [grantees]. */
    private fun commit(userId: String, scopeId: String, grantees: List<String>) =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body(
                """{"epoch":${TestGrants.epochOf(scopeId) + 1},"wrappedPrev":"cHJldg==","grants":[${
                    grantees.joinToString(",") { """{"userId":"$it","wrappedGrant":"$GRANT",${TestGrants.SIGNATURE_JSON}}""" }
                }],${TestGrants.openInvitesJson(scopeId)}}""",
            )
            .`when`().post("/api/scopes/$scopeId/epochs")

    private fun liveRotationJobs(scopeId: String): List<String> = QuarkusTransaction.requiringNew().call {
        JobEntity.find(
            "scopeId = ?1 and capabilityTag = ?2 and status in ?3 order by createdAt",
            scopeId, CapabilityTags.SCOPE_ROTATION, listOf(JobEntity.PENDING, JobEntity.LEASED),
        ).list().map { it.id }
    }

    private fun jobsTagged(scopeId: String, tag: String): Long =
        QuarkusTransaction.requiringNew().call { JobEntity.count("scopeId = ?1 and capabilityTag = ?2", scopeId, tag) }

    private fun statusOf(jobId: String): String = QuarkusTransaction.requiringNew().call { JobEntity.findById(jobId)!!.status }

    private fun leaseOf(jobId: String): Pair<String, String?> =
        QuarkusTransaction.requiringNew().call { JobEntity.findById(jobId)!!.let { it.status to it.leaseHolder } }

    private fun expireLease(jobId: String) = QuarkusTransaction.requiringNew().run {
        JobEntity.update("leaseExpiresAt = ?1 where id = ?2", Instant.now().minusSeconds(60), jobId)
    }

    private fun grantOf(membershipId: String): String? =
        QuarkusTransaction.requiringNew().call { MembershipEntity.findById(membershipId)?.wrappedGrant }

    private companion object {
        const val GRANT = "Z3JhbnQ="
        const val LOST_GRANT = "bG9zdC1ncmFudA=="
        const val MAX_ATTEMPTS = 5
        const val ROUNDS = 3
    }
}
