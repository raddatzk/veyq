package com.veyq.app.features.tasks

import com.veyq.app.shared.membership.TestAdmission
import com.veyq.app.shared.membership.MembershipEntity
import com.veyq.app.shared.membership.admit
import com.veyq.app.shared.scope.ScopeEntity
import com.veyq.app.testing.TestFixtures.bearer
import com.veyq.app.testing.TestFixtures.seedTenant
import com.veyq.app.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Slice tests for the operator-blind background-task queue (ADR 0021, cr-0076): the atomic claim/lease
 * (first-wins), lazy reclaim of an expired lease, dead-letter on exhausted attempts, and the first consumer —
 * grant-delivery originated on a **seeded grant-less membership**, claimed, and completed idempotently by
 * posting a wrap (the server writes the opaque ciphertext onto the target membership, never a key). Seeds use
 * UUID-unique ids so the JVM-lived H2 mem DB (shared across test classes) can't bleed state.
 */
@QuarkusTest
class TasksSliceTest {

    // --- grant-delivery: the first, trivially-blind consumer -------------------------------------------------

    @Test
    fun grantDeliveryOriginatedOnGrantlessMembershipThenClaimedAndCompletedIdempotently() {
        val scopeId = seedTenant()
        val worker = seedUser()
        seedGrantedMembership(worker, scopeId, "owner-grant")
        val target = seedUser(identityPublicKey = "dGFyZ2V0LXB1YmtleQ==") // "target-pubkey"
        val targetMembership = seedGrantlessMembership(target, scopeId)

        // The worker pulls its scopes with the grant-delivery tag → the server originates + leases the job,
        // resolving the delivery target (userId + identity pubkey — operational metadata, never a key).
        val jobId = given().header("Authorization", bearer(worker)).contentType(ContentType.JSON)
            .body("""{"capabilityTags":["${CapabilityTags.GRANT_DELIVERY}"]}""")
            .`when`().post("/api/tasks/claim")
            .then().statusCode(200)
            .body("capabilityTag", equalTo(CapabilityTags.GRANT_DELIVERY))
            .body("scopeId", equalTo(scopeId))
            .body("resourceRef", equalTo(targetMembership))
            .body("grantDelivery.targetUserId", equalTo(target))
            .body("grantDelivery.targetMembershipId", equalTo(targetMembership))
            .body("grantDelivery.identityPublicKey", equalTo("dGFyZ2V0LXB1YmtleQ=="))
            .body("jobId", notNullValue())
            .extract().path<String>("jobId")

        // The worker wraps K_SC to the target and completes: the backend writes the opaque grant onto the
        // target membership (the first wrap to a foreign identity) and marks the job done.
        val wrap = "d3JhcC10by1mb3JlaWdu" // "wrap-to-foreign"
        given().header("Authorization", bearer(worker)).contentType(ContentType.JSON)
            .body("""{"sealedResult":"$wrap","epoch":0,${TestAdmission.SIGNATURE_JSON}}""")
            .`when`().post("/api/tasks/$jobId/complete")
            .then().statusCode(204)

        assertEquals(wrap, grantOf(targetMembership), "the grant was delivered onto the target membership")

        // Idempotent + first-online-wins: nothing more is claimable (job DONE, member now granted → no
        // re-origination), and re-completing the same job is a success no-op that does not overwrite the grant.
        given().header("Authorization", bearer(worker)).contentType(ContentType.JSON)
            .body("""{"capabilityTags":["${CapabilityTags.GRANT_DELIVERY}"]}""")
            .`when`().post("/api/tasks/claim").then().statusCode(204)

        given().header("Authorization", bearer(worker)).contentType(ContentType.JSON)
            .body("""{"sealedResult":"c29tZXRoaW5nLWVsc2U="}""")
            .`when`().post("/api/tasks/$jobId/complete").then().statusCode(204)
        assertEquals(wrap, grantOf(targetMembership), "a second completion must not overwrite the delivered grant")
    }

    @Test
    fun grantDeliveryServesTheTargetsAdmissionAndRecordsTheWorkerAsGranter() {
        // ADR 0020 move 6 / ADR 0028 Decision 6: the target carries its admission so the worker can verify it
        // before it wraps, and the grant it posts is written with the worker as granter and its signature.
        val scopeId = seedTenant()
        val worker = seedUser()
        seedGrantedMembership(worker, scopeId, "owner-grant")
        val targetMembership = seedGrantlessMembership(seedUser(), scopeId)
        val admission = TestAdmission.admission()
        QuarkusTransaction.requiringNew().run { MembershipEntity.findById(targetMembership)!!.admit(admission) }

        val jobId = claim(worker, CapabilityTags.GRANT_DELIVERY).then().statusCode(200)
            .body("grantDelivery.admissionId", equalTo(admission.id))
            .body("grantDelivery.admissionEpoch", equalTo(0))
            .body("grantDelivery.admissionTag", equalTo(TestAdmission.TAG))
            .extract().path<String>("jobId")

        // No signature → 400, nothing written; the same completion signed → delivered, granter recorded.
        given().header("Authorization", bearer(worker)).contentType(ContentType.JSON)
            .body("""{"sealedResult":"d3JhcA==","epoch":0}""")
            .`when`().post("/api/tasks/$jobId/complete")
            .then().statusCode(400).body("code", equalTo("invalid_request"))
        assertNull(grantOf(targetMembership))
        given().header("Authorization", bearer(worker)).contentType(ContentType.JSON)
            .body("""{"sealedResult":"d3JhcA==","epoch":0,${TestAdmission.SIGNATURE_JSON}}""")
            .`when`().post("/api/tasks/$jobId/complete")
            .then().statusCode(204)
        val (granter, signature) = QuarkusTransaction.requiringNew().call {
            MembershipEntity.findById(targetMembership)!!.let { it.granterUserId to it.grantSignature }
        }
        assertEquals(worker to TestAdmission.SIGNATURE, granter to signature)
    }

    @Test
    fun grantDeliveryCompletionWithoutAWrapIs400() {
        val scopeId = seedTenant()
        val worker = seedUser()
        seedGrantedMembership(worker, scopeId, "owner-grant")
        seedGrantlessMembership(seedUser(), scopeId)

        val jobId = claimGrantDelivery(worker)
        given().header("Authorization", bearer(worker)).contentType(ContentType.JSON).body("{}")
            .`when`().post("/api/tasks/$jobId/complete")
            .then().statusCode(400).body("code", equalTo("invalid_request"))
    }

    @Test
    fun grantDeliveryIntoARotatedScopeRecordsTheDeliveredEpoch() {
        // A member added to an ALREADY-ROTATED scope: the worker wraps E_current (here E_1), so the membership must
        // be recorded at the current epoch — else the client peels the chain from the wrong base (treats E_1 as E_0)
        // and cannot read post-rotation content (cr-0093).
        val scopeId = seedTenant()
        setScopeEpoch(scopeId, 1) // the scope has rotated to E_1
        val worker = seedUser()
        seedGrantedMembershipRow(worker, scopeId, "owner-grant", grantedEpoch = 1) // current → not itself behind
        val target = seedUser(identityPublicKey = "dGFyZ2V0LXB1YmtleQ==")
        val targetMembership = seedGrantlessMembership(target, scopeId) // grantedEpoch defaults to 0

        val jobId = claimGrantDelivery(worker)
        given().header("Authorization", bearer(worker)).contentType(ContentType.JSON)
            .body("""{"sealedResult":"d3JhcC1FMQ==","epoch":1,${TestAdmission.SIGNATURE_JSON}}""")
            .`when`().post("/api/tasks/$jobId/complete").then().statusCode(204)

        assertEquals(1, grantedEpochOf(targetMembership), "grant-delivery must record the delivered (current) epoch")
    }

    @Test
    fun grantDeliveryStatingAnEpochBelowCurrentIsRefusedAndWritesNothing() {
        // The grant is sealed bound to (scope, epoch) (ADR 0072), so the server must record exactly the epoch the
        // worker sealed — and a worker whose epoch is not the current one (a rotation landed between its claim and
        // its completion) delivers a grant the target cannot use. Refused, nothing written, the lease released
        // without spending an attempt so the job re-runs; the control is the same job completed at the current epoch.
        val scopeId = seedTenant()
        setScopeEpoch(scopeId, 2)
        val worker = seedUser()
        seedGrantedMembershipRow(worker, scopeId, "owner-grant", grantedEpoch = 2)
        val target = seedUser(identityPublicKey = "dGFyZ2V0LXB1YmtleQ==")
        val targetMembership = seedGrantlessMembership(target, scopeId)

        val jobId = claimGrantDelivery(worker)
        given().header("Authorization", bearer(worker)).contentType(ContentType.JSON)
            .body("""{"sealedResult":"d3JhcC1FMQ==","epoch":1,${TestAdmission.SIGNATURE_JSON}}""")
            .`when`().post("/api/tasks/$jobId/complete")
            .then().statusCode(409).body("code", equalTo("stale_epoch"))
        assertEquals(null, grantOf(targetMembership), "a grant sealed at a stale epoch is never written")
        assertEquals(JobEntity.PENDING, statusOf(jobId), "the job is released to run again, not done")
        assertEquals(0, attemptsOf(jobId), "a stale completion does not spend an attempt")

        assertEquals(jobId, claimGrantDelivery(worker), "the same job is claimable again")
        given().header("Authorization", bearer(worker)).contentType(ContentType.JSON)
            .body("""{"sealedResult":"d3JhcC1FMg==","epoch":2,${TestAdmission.SIGNATURE_JSON}}""")
            .`when`().post("/api/tasks/$jobId/complete").then().statusCode(204)
        assertEquals("d3JhcC1FMg==", grantOf(targetMembership), "the current-epoch grant is written")
        assertEquals(2, grantedEpochOf(targetMembership), "at exactly the epoch the worker stated")
    }

    @Test
    fun staleMemberIsReconciledAndReDeliveredAtTheCurrentEpoch() {
        // A remaining member left stale by a rotation (grant present but grantedEpoch < currentEpoch — e.g. their
        // re-wrap failed at rotation time, en.spaces.scope-key-rotation §E2) is re-originated by reconcile and
        // re-delivered at the current epoch, instead of being stranded on "awaiting access" (cr-0093).
        val scopeId = seedTenant()
        setScopeEpoch(scopeId, 2)
        val worker = seedUser()
        seedGrantedMembershipRow(worker, scopeId, "owner-grant", grantedEpoch = 2) // current → not itself behind
        val stale = seedUser(identityPublicKey = "c3RhbGUtcHVia2V5")
        val staleMembership = seedGrantedMembershipRow(stale, scopeId, "old-grant", grantedEpoch = 1) // holds E_1, scope at E_2

        val jobId = claimGrantDelivery(worker) // reconcile-on-claim originates a delivery for the stale member
        given().header("Authorization", bearer(worker)).contentType(ContentType.JSON)
            .body("""{"sealedResult":"d3JhcC1FMg==","epoch":2,${TestAdmission.SIGNATURE_JSON}}""")
            .`when`().post("/api/tasks/$jobId/complete").then().statusCode(204)

        assertEquals(2, grantedEpochOf(staleMembership), "the stale member is re-granted at the current epoch")
        assertEquals("d3JhcC1FMg==", grantOf(staleMembership), "the stale grant is overwritten with the current wrap")
    }

    // --- the generic mechanism: claim / reclaim / dead-letter / heartbeat / fail / enqueue -------------------

    @Test
    fun claimIsAtomicFirstWins() {
        val scopeId = seedTenant()
        val worker = seedUser()
        seedGrantedMembership(worker, scopeId, "owner-grant")
        val tag = "deadbeef"
        seedJob(scopeId, tag, JobEntity.PENDING, attempts = 0)

        claim(worker, tag).then().statusCode(200)     // first claim leases it
        claim(worker, tag).then().statusCode(204)      // now leased → nothing claimable
    }

    @Test
    fun expiredLeaseIsLazilyReclaimed() {
        val scopeId = seedTenant()
        val worker = seedUser()
        seedGrantedMembership(worker, scopeId, "owner-grant")
        val tag = "deadbeef"
        // A worker crashed holding this job; its lease is already in the past → another worker reclaims it.
        val jobId = seedJob(scopeId, tag, JobEntity.LEASED, attempts = 1, leaseExpiresAt = Instant.now().minusSeconds(60))

        claim(worker, tag).then().statusCode(200).body("jobId", equalTo(jobId)).body("attempts", equalTo(2))
    }

    @Test
    fun exhaustedExpiredJobIsDeadLetteredNotClaimed() {
        val scopeId = seedTenant()
        val worker = seedUser()
        seedGrantedMembership(worker, scopeId, "owner-grant")
        val tag = "deadbeef"
        val jobId = seedJob(
            scopeId, tag, JobEntity.LEASED, attempts = 5, maxAttempts = 5,
            leaseExpiresAt = Instant.now().minusSeconds(60),
        )

        claim(worker, tag).then().statusCode(204) // exhausted → swept, not handed out
        assertEquals(JobEntity.FAILED, statusOf(jobId), "an exhausted expired job is dead-lettered")
    }

    @Test
    fun failBelowMaxRequeuesThenAtMaxDeadLetters() {
        val scopeId = seedTenant()
        val worker = seedUser()
        seedGrantedMembership(worker, scopeId, "owner-grant")
        val tag = "deadbeef"
        val jobId = seedJob(scopeId, tag, JobEntity.PENDING, attempts = 0, maxAttempts = 2)

        claim(worker, tag).then().statusCode(200) // attempts → 1
        given().header("Authorization", bearer(worker)).contentType(ContentType.JSON).body("{}")
            .`when`().post("/api/tasks/$jobId/fail")
            .then().statusCode(200).body("status", equalTo("pending")).body("attempts", equalTo(1))

        claim(worker, tag).then().statusCode(200) // requeued, claimed again → attempts → 2 (== max)
        given().header("Authorization", bearer(worker)).contentType(ContentType.JSON).body("{}")
            .`when`().post("/api/tasks/$jobId/fail")
            .then().statusCode(200).body("status", equalTo("failed")).body("attempts", equalTo(2))
    }

    @Test
    fun declineReleasesJobToOtherWorkersButNeverBackToTheDecliner() {
        // cr-0098 / ADR 0028: a worker that refuses a job (a conflicting identity pin) releases it back to
        // PENDING — still claimable by OTHER workers — but is itself never re-offered it (per KEY-HOLDER, not a
        // global veto; the exclusion is keyed on the account, so the decliner's other devices stay out too).
        // The declined attempt is rolled back, so a legitimate decline never dead-letters the job.
        val scopeId = seedTenant()
        val workerA = seedUser()
        val workerB = seedUser()
        seedGrantedMembership(workerA, scopeId, "a-grant")
        seedGrantedMembership(workerB, scopeId, "b-grant")
        val tag = "deadbeef"
        val jobId = seedJob(scopeId, tag, JobEntity.PENDING, attempts = 0)

        claim(workerA, tag).then().statusCode(200).body("jobId", equalTo(jobId)).body("attempts", equalTo(1))
        // A declines → back to pending, attempt rolled back to 0 (not a failed attempt).
        given().header("Authorization", bearer(workerA)).contentType(ContentType.JSON)
            .body("""{"reason":"identity_changed"}""")
            .`when`().post("/api/tasks/$jobId/decline")
            .then().statusCode(200).body("status", equalTo("pending")).body("attempts", equalTo(0))

        // A is excluded from its own decline → nothing claimable for A.
        claim(workerA, tag).then().statusCode(204)
        // B has no conflicting decline → the job is still delivered to B (attempts freshly 1).
        claim(workerB, tag).then().statusCode(200).body("jobId", equalTo(jobId)).body("attempts", equalTo(1))
    }

    @Test
    fun acceptingAChangedKeyClearsTheDeclineAndMakesTheJobClaimableAgain() {
        // ADR 0028 (2) promised that on accept "the wrap then proceeds", and nothing made it true: no code
        // deleted a decline row, so the accept authorised a wrap that never happened (ADR 0060 Decision 7).
        // This is the failure mode that strands a rotated account — its re-admission is exactly a delivery to a
        // contact somebody already declined.
        val scopeId = seedTenant()
        val worker = seedUser()
        seedGrantedMembership(worker, scopeId, "worker-grant")
        val target = seedUser()
        seedGrantlessMembership(target, scopeId)

        val jobId = claimGrantDelivery(worker)
        given().header("Authorization", bearer(worker)).contentType(ContentType.JSON)
            .body("""{"reason":"identity_changed"}""")
            .`when`().post("/api/tasks/$jobId/decline").then().statusCode(200)
        claim(worker, CapabilityTags.GRANT_DELIVERY).then().statusCode(204)

        // The control the positive needs: clearing for a DIFFERENT contact must change nothing. Without it, a
        // clear that simply dropped every row of the caller's would pass the happy path identically.
        clearDeclines(worker, seedUser()).then().statusCode(200).body("cleared", equalTo(0))
        claim(worker, CapabilityTags.GRANT_DELIVERY).then().statusCode(204)

        clearDeclines(worker, target).then().statusCode(200).body("cleared", equalTo(1))
        claim(worker, CapabilityTags.GRANT_DELIVERY).then().statusCode(200).body("jobId", equalTo(jobId))
    }

    @Test
    fun clearingDeclinesNeverFreesARotationExclusion() {
        // The two mechanisms share a table, so the accept path could trivially have unblocked the rotation
        // exclusion that keeps a rotator off their own re-grant (ADR 0060 Decision 4) — the takeover's finishing
        // move, handed over by a cleanup. It cannot, because a scope-rotation job has no delivery target for a
        // membership id to resolve to; this asserts that in the one run where both rows belong to one worker.
        val scopeId = seedTenant()
        val rotator = seedUser()
        seedGrantedMembership(rotator, scopeId, "rotator-grant")
        val target = seedUser()
        seedGrantlessMembership(target, scopeId)

        val rotationJob = seedJob(scopeId, CapabilityTags.SCOPE_ROTATION, JobEntity.PENDING, attempts = 0, resourceRef = scopeId)
        seedDecline(rotationJob, rotator, "rotation_originator")

        val deliveryJob = claimGrantDelivery(rotator)
        given().header("Authorization", bearer(rotator)).contentType(ContentType.JSON)
            .body("""{"reason":"identity_changed"}""")
            .`when`().post("/api/tasks/$deliveryJob/decline").then().statusCode(200)

        clearDeclines(rotator, target).then().statusCode(200).body("cleared", equalTo(1))

        // The delivery is unblocked; the rotation exclusion is not.
        claim(rotator, CapabilityTags.GRANT_DELIVERY).then().statusCode(200).body("jobId", equalTo(deliveryJob))
        claim(rotator, CapabilityTags.SCOPE_ROTATION).then().statusCode(204)
    }

    @Test
    fun declineRequiresTheLease() {
        val scopeId = seedTenant()
        val holder = seedUser()
        val other = seedUser()
        seedGrantedMembership(holder, scopeId, "holder-grant")
        seedGrantedMembership(other, scopeId, "other-grant")
        val tag = "deadbeef"
        val jobId = seedJob(scopeId, tag, JobEntity.PENDING, attempts = 0)
        claim(holder, tag).then().statusCode(200)

        // A key-holder in the scope who does NOT hold the lease cannot decline it → 409.
        given().header("Authorization", bearer(other)).contentType(ContentType.JSON).body("{}")
            .`when`().post("/api/tasks/$jobId/decline")
            .then().statusCode(409).body("code", equalTo("not_lease_holder"))
    }

    @Test
    fun heartbeatExtendsTheLeaseForTheHolderOnly() {
        val scopeId = seedTenant()
        val worker = seedUser()
        seedGrantedMembership(worker, scopeId, "owner-grant")
        val tag = "deadbeef"
        val jobId = seedJob(scopeId, tag, JobEntity.PENDING, attempts = 0)
        claim(worker, tag).then().statusCode(200)

        given().header("Authorization", bearer(worker))
            .`when`().post("/api/tasks/$jobId/heartbeat")
            .then().statusCode(200).body("leaseExpiresAt", notNullValue())

        // A non-holder key-holder in the same scope cannot extend a lease they do not own → 409.
        val other = seedUser()
        seedGrantedMembership(other, scopeId, "other-grant")
        given().header("Authorization", bearer(other))
            .`when`().post("/api/tasks/$jobId/heartbeat")
            .then().statusCode(409).body("code", equalTo("not_lease_holder"))
    }

    @Test
    fun enqueueRequiresScopeMembership() {
        val scopeId = seedTenant()
        val member = seedUser()
        seedGrantedMembership(member, scopeId, "member-grant")

        given().header("Authorization", bearer(member)).contentType(ContentType.JSON)
            .body("""{"scopeId":"$scopeId","capabilityTag":"9f3a1c04","sealedBody":"AQ=="}""")
            .`when`().post("/api/tasks/enqueue")
            .then().statusCode(201).body("jobId", notNullValue())

        // An outsider cannot enqueue there — and gets the *scope-not-found* answer, not a "forbidden" that would
        // confirm the scope exists (membership is the first guard, ADR 0042 / cr-0162).
        val outsider = seedUser()
        given().header("Authorization", bearer(outsider)).contentType(ContentType.JSON)
            .body("""{"scopeId":"$scopeId","capabilityTag":"9f3a1c04","sealedBody":"AQ=="}""")
            .`when`().post("/api/tasks/enqueue")
            .then().statusCode(404).body("code", equalTo("scope_not_found"))
    }

    @Test
    fun authedButNoGrantClaimIs204() {
        // A user with no granted membership anywhere holds no scope key → nothing is ever claimable for them.
        val nobody = seedUser()
        claim(nobody, CapabilityTags.GRANT_DELIVERY).then().statusCode(204)
    }

    @Test
    fun claimWithEmptyTagsIs400() {
        val worker = seedUser()
        given().header("Authorization", bearer(worker)).contentType(ContentType.JSON)
            .body("""{"capabilityTags":[]}""")
            .`when`().post("/api/tasks/claim")
            .then().statusCode(400)
    }

    @Test
    fun actingOnAnUnknownJobIs404() {
        val worker = seedUser()
        val ghost = UUID.randomUUID().toString()
        given().header("Authorization", bearer(worker)).contentType(ContentType.JSON).body("""{"sealedResult":"AQ=="}""")
            .`when`().post("/api/tasks/$ghost/complete").then().statusCode(404).body("code", equalTo("job_not_found"))
        given().header("Authorization", bearer(worker))
            .`when`().post("/api/tasks/$ghost/heartbeat").then().statusCode(404).body("code", equalTo("job_not_found"))
        given().header("Authorization", bearer(worker)).contentType(ContentType.JSON).body("{}")
            .`when`().post("/api/tasks/$ghost/fail").then().statusCode(404).body("code", equalTo("job_not_found"))
        given().header("Authorization", bearer(worker)).contentType(ContentType.JSON).body("{}")
            .`when`().post("/api/tasks/$ghost/decline").then().statusCode(404).body("code", equalTo("job_not_found"))
    }

    @Test
    fun enqueueRejectsStructuralGrantDeliveryTag() {
        // Security (cr-0081): a client cannot forge a grant-delivery job — structural tags are server-originated
        // only, so the enqueue front door rejects them (400) instead of minting an arbitrary-target delivery.
        val scopeId = seedTenant()
        val worker = seedUser()
        seedGrantedMembership(worker, scopeId, "grant")
        given().header("Authorization", bearer(worker)).contentType(ContentType.JSON)
            .body("""{"scopeId":"$scopeId","capabilityTag":"${CapabilityTags.GRANT_DELIVERY}","sealedBody":"AQ=="}""")
            .`when`().post("/api/tasks/enqueue")
            .then().statusCode(400).body("code", equalTo("invalid_request"))
    }

    @Test
    fun completeCannotWriteAGrantOntoAMembershipInAnotherScope() {
        // Security (cr-0081): the grant write is bound to the job's own scope. An attacker holding a grant in
        // scope A cannot use a grant-delivery job in A to write a wrappedGrant onto a membership in scope B.
        val scopeA = seedTenant()
        val attacker = seedUser()
        seedGrantedMembership(attacker, scopeA, "attacker-grant")

        val scopeB = seedTenant()
        val victimMembership = seedGrantlessMembership(seedUser(), scopeB)

        // A grant-delivery job in scope A (attacker's) pointing at the victim's grant-less membership in scope B,
        // leased to the attacker — completing needs the lease (ADR 0021 §6), so this is the strongest position the
        // attacker can hold.
        val jobId = seedJob(
            scopeA, CapabilityTags.GRANT_DELIVERY, JobEntity.LEASED, attempts = 1, resourceRef = victimMembership,
            leaseExpiresAt = Instant.now().plusSeconds(300), leaseHolder = attacker,
        )

        // The attacker completes it (they hold the lease and a grant in A) — the job is marked done, but the
        // cross-scope write is refused: the victim's membership in B stays grant-less.
        given().header("Authorization", bearer(attacker)).contentType(ContentType.JSON)
            .body("""{"sealedResult":"YXR0YWNrZXItd3JhcA==","epoch":0,${TestAdmission.SIGNATURE_JSON}}""")
            .`when`().post("/api/tasks/$jobId/complete").then().statusCode(204)

        assertNull(grantOf(victimMembership), "a cross-scope resourceRef must not receive a grant")
    }

    @Test
    fun claimWithoutAuthIs401() {
        given().contentType(ContentType.JSON).body("""{"capabilityTags":["deadbeef"]}""")
            .`when`().post("/api/tasks/claim")
            .then().statusCode(401).body("code", equalTo("authentication_failed"))
    }

    @Test
    fun grantLessWorkerCannotClaim() {
        // Eligibility is holding the scope key: a grant-less member is a *target*, not a worker.
        val scopeId = seedTenant()
        val pending = seedUser()
        seedGrantlessMembership(pending, scopeId)
        claim(pending, CapabilityTags.GRANT_DELIVERY).then().statusCode(204)
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    private fun claim(userId: String, tag: String) =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"capabilityTags":["$tag"]}""")
            .`when`().post("/api/tasks/claim")

    private fun clearDeclines(userId: String, targetUserId: String) =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"targetUserId":"$targetUserId"}""")
            .`when`().post("/api/tasks/declines/clear")

    private fun seedDecline(jobId: String, workerId: String, reason: String) = QuarkusTransaction.requiringNew().run {
        JobDeclineEntity().apply {
            id = UUID.randomUUID().toString(); this.jobId = jobId; this.workerId = workerId
            this.reason = reason; declinedAt = Instant.now()
        }.persist()
    }

    private fun claimGrantDelivery(userId: String): String =
        claim(userId, CapabilityTags.GRANT_DELIVERY).then().statusCode(200).extract().path("jobId")

    private fun seedGrantedMembership(userId: String, scopeId: String, grant: String) =
        QuarkusTransaction.requiringNew().run {
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); this.userId = userId; tenantId = scopeId
                this.scopeId = scopeId; role = MembershipEntity.OWNER
                wrappedGrant = java.util.Base64.getEncoder().encodeToString(grant.toByteArray())
                createdAt = Instant.now()
            }.persist()
        }

    /** A pending member: a role, no grant — the state the delivery job reconciles. Returns the membership id. */
    private fun seedGrantlessMembership(userId: String, scopeId: String): String {
        val membershipId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            MembershipEntity().apply {
                id = membershipId; this.userId = userId; tenantId = scopeId
                this.scopeId = scopeId; role = MembershipEntity.MEMBER; wrappedGrant = null
                createdAt = Instant.now()
            }.persist()
        }
        return membershipId
    }

    private fun seedJob(
        scopeId: String, tag: String, status: String, attempts: Int, maxAttempts: Int = 5,
        leaseExpiresAt: Instant? = null, resourceRef: String? = null, leaseHolder: String = "crashed",
    ): String {
        val jobId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            JobEntity().apply {
                id = jobId; this.scopeId = scopeId; capabilityTag = tag; this.status = status
                this.attempts = attempts; this.maxAttempts = maxAttempts; this.resourceRef = resourceRef
                this.leaseExpiresAt = leaseExpiresAt; this.leaseHolder = if (status == JobEntity.LEASED) leaseHolder else null
                createdAt = Instant.now()
            }.persist()
        }
        return jobId
    }

    private fun grantOf(membershipId: String): String? =
        QuarkusTransaction.requiringNew().call { MembershipEntity.findById(membershipId)?.wrappedGrant }

    private fun statusOf(jobId: String): String =
        QuarkusTransaction.requiringNew().call { JobEntity.findById(jobId)!!.status }

    private fun attemptsOf(jobId: String): Int =
        QuarkusTransaction.requiringNew().call { JobEntity.findById(jobId)!!.attempts }

    /** Simulate a rotated scope: bump its current content-key epoch (cr-0093). */
    private fun setScopeEpoch(scopeId: String, epoch: Int) =
        QuarkusTransaction.requiringNew().run { ScopeEntity.findById(scopeId)!!.apply { currentEpoch = epoch }.persist() }

    private fun grantedEpochOf(membershipId: String): Int =
        QuarkusTransaction.requiringNew().call { MembershipEntity.findById(membershipId)!!.grantedEpoch }

    /** A granted membership with a known id + explicit grantedEpoch — to seed a *stale* member (cr-0093). */
    private fun seedGrantedMembershipRow(userId: String, scopeId: String, grant: String, grantedEpoch: Int): String {
        val membershipId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            MembershipEntity().apply {
                id = membershipId; this.userId = userId; tenantId = scopeId; this.scopeId = scopeId
                role = MembershipEntity.OWNER; wrappedGrant = grant; this.grantedEpoch = grantedEpoch
                createdAt = Instant.now()
            }.persist()
        }
        return membershipId
    }
}
