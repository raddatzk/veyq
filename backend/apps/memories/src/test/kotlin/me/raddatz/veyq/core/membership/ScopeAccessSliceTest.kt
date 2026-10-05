package me.raddatz.veyq.core.membership

import me.raddatz.veyq.core.admin.tenant.TenantInviteEntity
import me.raddatz.veyq.core.tasks.JobEntity
import me.raddatz.veyq.core.crypto.Tokens
import me.raddatz.veyq.core.membership.ScopeAccessWorld.Companion.B64
import me.raddatz.veyq.core.membership.ScopeAccessWorld.Companion.JOB_TAG
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The scope-access gate where its answer is a **filter** or a **token holder's view** rather than a refusal
 * (uc.spaces.scope-access A4/A5/A7, A2) — the operations `OperatorBlindProbeTest` names on its exclusion list, and
 * the lease and restore rules its personas do not reach. Each negative carries its control on the same predicate.
 */
@QuarkusTest
class ScopeAccessSliceTest {

    private fun world() = ScopeAccessWorld().build()

    @Test
    fun claimHandsOutNoJobInASuspendedTenantOrADeletingGroup() {
        val w = world()
        val (live, deleting, suspended) = listOf(w.live, w.deleting, w.suspended).map { pendingJob(it.scopeId) }

        fun claim(s: ScopeAccessWorld.Space) = given().header("Authorization", bearer(s.ownerId)).contentType(ContentType.JSON)
            .body("""{"capabilityTags":["$JOB_TAG"],"scopeId":"${s.scopeId}"}""").`when`().post("/api/tasks/claim")

        claim(w.suspended).then().statusCode(204)
        claim(w.deleting).then().statusCode(204)
        claim(w.live).then().statusCode(200).body("jobId", equalTo(live)) // control: the same job in a live group
        listOf(deleting, suspended).forEach { job ->
            QuarkusTransaction.requiringNew().run { check(JobEntity.findById(job)!!.status == JobEntity.PENDING) { "$job was leased" } }
        }
    }

    @Test
    fun myTenantsListsASuspendedTenantWithNeitherGrantNorIcon() {
        val w = world()
        QuarkusTransaction.requiringNew().run {
            listOf(w.live.tenantId, w.suspended.tenantId).forEach { ScopeEntity.update("sealedIcon = ?1 where id = ?2", B64, it) }
        }
        given().header("Authorization", bearer(w.suspended.ownerId)).`when`().get("/api/scopes/my-tenants")
            .then().statusCode(200)
            .body("find { it.tenantId == '${w.suspended.tenantId}' }.status", equalTo("SUSPENDED"))
            .body("find { it.tenantId == '${w.suspended.tenantId}' }.wrappedGrant", nullValue())
            .body("find { it.tenantId == '${w.suspended.tenantId}' }.sealedIcon", nullValue())
        // control: an active tenant carries both
        given().header("Authorization", bearer(w.live.ownerId)).`when`().get("/api/scopes/my-tenants")
            .then().statusCode(200)
            .body("find { it.tenantId == '${w.live.tenantId}' }.status", equalTo("ACTIVE"))
            .body("find { it.tenantId == '${w.live.tenantId}' }.wrappedGrant", notNullValue())
            .body("find { it.tenantId == '${w.live.tenantId}' }.sealedIcon", equalTo(B64))
    }

    @Test
    fun aShareInASuspendedTenantOrADeletingGroupAnswersAsARevokedLink() {
        val w = world()
        for (paused in listOf(w.suspended, w.deleting)) {
            given().`when`().get("/api/shares/${paused.shareId}").then().statusCode(404).body("code", equalTo("share_not_found"))
            given().`when`().get("/api/shares/${paused.shareId}/terms").then().statusCode(404).body("code", equalTo("share_not_found"))
            given().`when`().get("/api/shares/${paused.shareId}/blobs/${paused.blobId}").then().statusCode(404).body("code", equalTo("share_not_found"))
            given().`when`().get("/api/shares/${paused.shareId}/media").then().statusCode(404).body("code", equalTo("share_not_found"))
        }
        // control: the live group's link serves, so the 404s above are the space's state and not a broken link
        given().`when`().get("/api/shares/${w.live.shareId}").then().statusCode(200)
        given().`when`().get("/api/shares/${w.live.shareId}/terms").then().statusCode(200)
        given().`when`().get("/api/shares/${w.live.shareId}/media").then().statusCode(200)
        // and nothing was written onto the share: restoring the group brings its link back unchanged
        given().header("Authorization", bearer(w.deleting.ownerId)).`when`().post("/api/scopes/${w.deleting.scopeId}/restore").then().statusCode(204)
        given().`when`().get("/api/shares/${w.deleting.shareId}").then().statusCode(200)
    }

    @Test
    fun anInviteToASuspendedTenantAnswersAsADeadInvite() {
        val w = world()
        val paused = memberInvite(w.suspended.tenantId)
        val live = memberInvite(w.live.tenantId)
        given().contentType(ContentType.JSON).body("""{"bootstrapSecret":"$paused"}""").`when`().post("/api/tenants/accept/preview")
            .then().statusCode(410).body("code", equalTo("invite_no_longer_valid"))
        given().contentType(ContentType.JSON).body("""{"bootstrapSecret":"$live"}""").`when`().post("/api/tenants/accept/preview")
            .then().statusCode(200) // control
    }

    @Test
    fun myShareListDropsSharesInSpacesTheCallerMayNoLongerRead() {
        val w = world()
        // the suspended tenant's own member no longer sees its share in "my shares" …
        given().header("Authorization", bearer(w.suspended.ownerId)).`when`().get("/api/shares")
            .then().statusCode(200).body("shareId", not(hasItem(w.suspended.shareId)))
        // … nor does a creator who left the group
        QuarkusTransaction.requiringNew().run {
            MembershipEntity.delete("userId = ?1 and scopeId = ?2", w.deleting.ownerId, w.deleting.scopeId)
        }
        given().header("Authorization", bearer(w.deleting.ownerId)).`when`().get("/api/shares")
            .then().statusCode(200).body("shareId", not(hasItem(w.deleting.shareId)))
        // control: a current member of a live group still does
        given().header("Authorization", bearer(w.live.ownerId)).`when`().get("/api/shares")
            .then().statusCode(200).body("shareId", hasItem(w.live.shareId))
    }

    @Test
    fun onlyTheLeaseHolderCompletesOrFailsAJobAndADoneJobStaysANoOp() {
        val w = world()
        val other = keyHolderIn(w.live) // a member holding the key, but not the lease
        fun post(user: String, verb: String) = given().header("Authorization", bearer(user)).contentType(ContentType.JSON)
            .body("{}").`when`().post("/api/tasks/${w.live.jobId}/$verb")

        post(other, "complete").then().statusCode(409).body("code", equalTo("not_lease_holder"))
        post(other, "fail").then().statusCode(409).body("code", equalTo("not_lease_holder"))
        post(w.live.ownerId, "complete").then().statusCode(204) // control: the holder completes
        post(w.live.ownerId, "complete").then().statusCode(204) // a done job is a no-op …
        post(other, "complete").then().statusCode(204) // … checked before the lease, for anyone the gate lets in
        post(w.live.ownerId, "fail").then().statusCode(409).body("code", equalTo("not_lease_holder")) // a done job is not failed
    }

    @Test
    fun aFormerTenantMemberReadsTheirGraceSpaceButCannotRestoreIt() {
        val w = world()
        // the tenant owner removes the deleting group's owner from the tenant: their solo group stays readable to them
        given().header("Authorization", bearer(w.live.ownerId))
            .`when`().delete("/api/tenants/${w.live.tenantId}/members/${w.deleting.ownerId}").then().statusCode(204)
        val former = bearer(w.deleting.ownerId)

        given().header("Authorization", former).`when`().get("/api/scopes/${w.deleting.scopeId}/nodes").then().statusCode(200)
        given().header("Authorization", former).`when`().post("/api/scopes/${w.deleting.scopeId}/restore")
            .then().statusCode(404).body("code", equalTo("scope_not_found"))
        given().header("Authorization", former).`when`().get("/api/tenants/${w.live.tenantId}/terms")
            .then().statusCode(404).body("code", equalTo("tenant_not_found"))
        // control: an owner who still belongs to the tenant restores a deleting group
        val stillMember = bearer(w.live.ownerId)
        given().header("Authorization", stillMember).`when`().delete("/api/scopes/${w.live.scopeId}").then().statusCode(204)
        given().header("Authorization", stillMember).`when`().post("/api/scopes/${w.live.scopeId}/restore").then().statusCode(204)
    }

    private fun pendingJob(scopeId: String): String {
        val id = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            JobEntity().apply {
                this.id = id; this.scopeId = scopeId; capabilityTag = JOB_TAG; status = JobEntity.PENDING
                attempts = 0; maxAttempts = 5; sealedBody = B64; createdAt = Instant.now()
            }.persist()
        }
        return id
    }

    /** A MEMBER of the space (and its tenant) who holds the key — accepted and granted. */
    private fun keyHolderIn(s: ScopeAccessWorld.Space): String {
        val userId = seedUser()
        QuarkusTransaction.requiringNew().run {
            listOf(s.tenantId, s.scopeId).forEach { scope ->
                MembershipEntity().apply {
                    id = UUID.randomUUID().toString(); this.userId = userId; tenantId = s.tenantId; scopeId = scope
                    role = MembershipEntity.MEMBER; wrappedGrant = B64; createdAt = Instant.now()
                }.persist()
            }
        }
        return userId
    }

    /** A live MEMBER invite to [tenantId]; returns its raw secret. */
    private fun memberInvite(tenantId: String): String {
        val secret = Tokens.random()
        QuarkusTransaction.requiringNew().run {
            TenantInviteEntity().apply {
                id = UUID.randomUUID().toString(); this.tenantId = tenantId; ownerEmail = "i-$tenantId@example.com"
                tokenHash = Tokens.hash(secret); status = TenantInviteEntity.ACTIVE; role = TenantInviteEntity.MEMBER
                expiresAt = Instant.now().plusSeconds(3600); createdAt = Instant.now()
            }.persist()
        }
        return secret
    }
}
