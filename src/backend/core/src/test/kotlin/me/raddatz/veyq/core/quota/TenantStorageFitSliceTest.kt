package me.raddatz.veyq.core.quota

import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.membership.TenantRoots
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.tenant.TenantEntity
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import java.time.Instant
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Test

/**
 * The member's storage-fit preflight (`checkTenantStorageFit`, ADR 0082): "would these bytes still fit my tenant's quota?",
 * asked by ANY member, answered yes or no and never a number, bounded per member and per tenant.
 *
 * The rule is the one an upload start applies, so it is pinned the same way as `TenantStorageQuotaSliceTest`: refused at
 * `usage + 1` over, admitted exactly at the quota, on an open upload session's declared bytes. The limits are lowered by
 * [LowFitLimits] so a limiter trip is reachable; the per-tenant bucket is larger than the per-member one on purpose, so
 * the member bound is what trips first and the tenant bound is shown separately.
 */
@QuarkusTest
@TestProfile(TenantStorageFitSliceTest.LowFitLimits::class)
class TenantStorageFitSliceTest {

    class LowFitLimits : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf(
            "veyq.ratelimit.storage-fit.per-member-max-requests" to "4",
            "veyq.ratelimit.storage-fit.per-tenant-max-requests" to "6",
            "veyq.ratelimit.storage-fit.window-seconds" to "3600",
        )
    }

    private data class Fixture(val tenantId: String, val ownerId: String, val memberId: String, val otherMemberId: String, val scopeId: String)

    private fun fixture(quota: Long? = null): Fixture {
        val owner = seedUser()
        val member = seedUser()
        val other = seedUser()
        val tenantId = UUID.randomUUID().toString()
        val scopeId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            TenantEntity().apply {
                id = tenantId; name = "Fam"; domain = "fit-$tenantId.example.com"
                ownerEmail = "o-$tenantId@example.com"; status = TenantEntity.ACTIVE; storageQuotaBytes = quota
            }.persist()
            ScopeEntity().apply { id = scopeId; this.tenantId = tenantId; kind = ScopeEntity.PERSONAL }.persist()
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); userId = owner; this.tenantId = tenantId
                this.scopeId = scopeId; role = MembershipEntity.OWNER; wrappedGrant = "Z3JhbnQ="; createdAt = Instant.now()
            }.persist()
            TenantRoots.join(owner, tenantId, MembershipEntity.OWNER)
            TenantRoots.join(member, tenantId, MembershipEntity.MEMBER)
            TenantRoots.join(other, tenantId, MembershipEntity.MEMBER)
        }
        seedNode(scopeId, "MEMORY", wrappedContentKey = CONTENT_KEY)
        return Fixture(tenantId, owner, member, other, scopeId)
    }

    private fun ask(userId: String, tenantId: String, bytes: String): Response =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"bytes":$bytes}""").`when`().post("/api/tenants/$tenantId/storage-fit")

    private fun setQuota(tenantId: String, bytes: Long?) =
        QuarkusTransaction.requiringNew().run { TenantEntity.findById(tenantId)!!.storageQuotaBytes = bytes }

    /** An open upload session for [blob]: the tenant now holds its declared bytes as a reservation. */
    private fun reserve(f: Fixture, blob: TestBlob) {
        given().header("Authorization", bearer(f.ownerId)).contentType(ContentType.JSON)
            .body("""{"items":[${blob.declare("b3JpZw==", ownerId = UUID.randomUUID().toString(), role = "ORIGINAL")}]}""")
            .`when`().post("/api/scopes/${f.scopeId}/blob-uploads-batch").then().statusCode(200)
    }

    @Test
    fun aMemberWhoIsNotTheOwnerGetsAYesOrANoAtTheExactQuotaBoundary() {
        val f = fixture(quota = 1_000)
        ask(f.memberId, f.tenantId, "1000").then().statusCode(200).body("fits", equalTo(true))
        ask(f.memberId, f.tenantId, "1001").then().statusCode(200).body("fits", equalTo(false))
    }

    @Test
    fun whatTheTenantAlreadyHoldsCountsOpenSessionsIncluded() {
        val s = TestBlob.SMALL
        val f = fixture(quota = null)
        reserve(f, s)
        setQuota(f.tenantId, s.byteSize + 100)
        ask(f.memberId, f.tenantId, "100").then().body("fits", equalTo(true))
        ask(f.memberId, f.tenantId, "101").then().body("fits", equalTo(false))
    }

    @Test
    fun aTenantWithoutAQuotaFitsAnything() {
        val f = fixture(quota = null)
        ask(f.memberId, f.tenantId, "9007199254740991").then().statusCode(200).body("fits", equalTo(true))
    }

    @Test
    fun theAnswerCarriesNothingButTheYesOrNo() {
        val f = fixture(quota = 1_000)
        val body = ask(f.memberId, f.tenantId, "10").then().statusCode(200).extract().body().jsonPath().getMap<String, Any>("")
        org.junit.jupiter.api.Assertions.assertEquals(setOf("fits"), body.keys)
    }

    @Test
    fun aStrangerIsNotFoundAndSpendsNoTokenOfTheTenant() {
        val f = fixture(quota = 1_000)
        val stranger = seedUser()
        repeat(10) { ask(stranger, f.tenantId, "1").then().statusCode(404).body("code", equalTo("tenant_not_found")) }
        // the members' budget is untouched by the stranger's questions
        ask(f.memberId, f.tenantId, "1").then().statusCode(200)
    }

    @Test
    fun aMemberPastItsAllowanceIsThrottledAndAnotherMemberIsNot() {
        val f = fixture(quota = 1_000)
        repeat(4) { ask(f.memberId, f.tenantId, "1").then().statusCode(200) }
        ask(f.memberId, f.tenantId, "1").then().statusCode(429)
            .body("code", equalTo("rate_limited")).header("Retry-After", notNullValue())
        ask(f.otherMemberId, f.tenantId, "1").then().statusCode(200)
    }

    @Test
    fun theTenantsAggregateBoundTripsEvenWhenEachMemberIsWithinItsOwn() {
        val f = fixture(quota = 1_000)
        repeat(3) { ask(f.memberId, f.tenantId, "1").then().statusCode(200) }
        repeat(3) { ask(f.otherMemberId, f.tenantId, "1").then().statusCode(200) }
        ask(f.ownerId, f.tenantId, "1").then().statusCode(429).body("code", equalTo("rate_limited"))
    }

    @Test
    fun noTokenOrAMalformedByteCountIsRefusedBeforeAnyAnswer() {
        val f = fixture(quota = 1_000)
        given().contentType(ContentType.JSON).body("""{"bytes":1}""").`when`().post("/api/tenants/${f.tenantId}/storage-fit").then().statusCode(401)
        ask(f.memberId, f.tenantId, "-1").then().statusCode(400)
        ask(f.memberId, f.tenantId, "null").then().statusCode(400)
        given().header("Authorization", bearer(f.memberId)).contentType(ContentType.JSON).body("{}")
            .`when`().post("/api/tenants/${f.tenantId}/storage-fit").then().statusCode(400)
    }
}
