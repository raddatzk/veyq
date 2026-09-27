package com.veyq.app.features.admin.tenant

import com.veyq.app.features.media.TestBlob
import com.veyq.app.shared.membership.MembershipEntity
import com.veyq.app.shared.tenant.TenantEntity
import com.veyq.app.testing.TestFixtures.bearer
import com.veyq.app.testing.TestFixtures.seedTenant
import com.veyq.app.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

/**
 * The tenant owner's storage settings (uc.sharing.share-link-storage-limit, ADR 0073 §4, §6) over the full HTTP layer,
 * in the mail-settings shape: the owner reads the tenant's usage and writes the per-link limit; a non-owner member
 * is `403 not_tenant_owner`, a non-member `404`. The limit's effect on link starts is `ShareLinkStorageLimitSliceTest`.
 */
@QuarkusTest
class TenantStorageSliceTest {

    @Test
    fun theOwnerReadsUsageAndWritesTheLimit() {
        val (tenantId, ownerId) = seedActiveTenant()
        given().header("Authorization", bearer(ownerId))
            .`when`().get("/api/tenants/$tenantId/storage")
            .then().statusCode(200)
            .body("usedBytes", equalTo(0))
            .body("shareLinkLimitBytes", equalTo(TenantEntity.DEFAULT_SHARE_LINK_LIMIT_BYTES)) // 10 GiB until changed

        // usage is every blob + every open session in the tenant's scopes — here one open member upload
        val blob = TestBlob.ofChunks(2)
        given().header("Authorization", bearer(ownerId)).contentType(ContentType.JSON)
            .body("""{"items":[${blob.declare("d3JhcA==")}]}""")
            .`when`().post("/api/scopes/$tenantId/blob-uploads-batch")
            .then().statusCode(200)

        given().header("Authorization", bearer(ownerId)).contentType(ContentType.JSON)
            .body("""{"shareLinkLimitBytes":5368709120}""")
            .`when`().put("/api/tenants/$tenantId/storage")
            .then().statusCode(200)
            .body("shareLinkLimitBytes", equalTo(5_368_709_120L))
            .body("usedBytes", equalTo(blob.byteSize.toInt()))
        assertEquals(5_368_709_120L, QuarkusTransaction.requiringNew().call { TenantEntity.findById(tenantId)!!.shareLinkLimitBytes })
        given().header("Authorization", bearer(ownerId))
            .`when`().get("/api/tenants/$tenantId/storage")
            .then().statusCode(200).body("shareLinkLimitBytes", equalTo(5_368_709_120L))
    }

    @Test
    fun aNegativeLimitIs400() {
        val (tenantId, ownerId) = seedActiveTenant()
        given().header("Authorization", bearer(ownerId)).contentType(ContentType.JSON)
            .body("""{"shareLinkLimitBytes":-1}""")
            .`when`().put("/api/tenants/$tenantId/storage")
            .then().statusCode(400)
        given().header("Authorization", bearer(ownerId)).contentType(ContentType.JSON)
            .body("""{"shareLinkLimitBytes":0}""") // control: 0 is a limit (link uploads off), not an error
            .`when`().put("/api/tenants/$tenantId/storage")
            .then().statusCode(200).body("shareLinkLimitBytes", equalTo(0))
    }

    @Test
    fun aNonOwnerMemberIs403OnReadAndWrite() {
        val (tenantId, _) = seedActiveTenant()
        val member = seedUser()
        seedMembership(tenantId, member, MembershipEntity.MEMBER)
        given().header("Authorization", bearer(member))
            .`when`().get("/api/tenants/$tenantId/storage")
            .then().statusCode(403).body("code", equalTo("not_tenant_owner"))
        given().header("Authorization", bearer(member)).contentType(ContentType.JSON)
            .body("""{"shareLinkLimitBytes":1}""")
            .`when`().put("/api/tenants/$tenantId/storage")
            .then().statusCode(403).body("code", equalTo("not_tenant_owner"))
        assertEquals(TenantEntity.DEFAULT_SHARE_LINK_LIMIT_BYTES, QuarkusTransaction.requiringNew().call { TenantEntity.findById(tenantId)!!.shareLinkLimitBytes })
    }

    @Test
    fun aNonMemberIs404OnReadAndWrite() {
        val (tenantId, _) = seedActiveTenant()
        val outsider = seedUser()
        given().header("Authorization", bearer(outsider))
            .`when`().get("/api/tenants/$tenantId/storage")
            .then().statusCode(404).body("code", equalTo("tenant_not_found"))
        given().header("Authorization", bearer(outsider)).contentType(ContentType.JSON)
            .body("""{"shareLinkLimitBytes":1}""")
            .`when`().put("/api/tenants/$tenantId/storage")
            .then().statusCode(404).body("code", equalTo("tenant_not_found"))
    }

    // --- helpers (mirror TenantMailSettingsSliceTest) ---

    private fun seedActiveTenant(): Pair<String, String> {
        val ownerId = seedUser()
        return seedTenant(owner = ownerId) to ownerId
    }

    private fun seedMembership(tenantId: String, userId: String, role: String) =
        QuarkusTransaction.requiringNew().run {
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); this.userId = userId; this.tenantId = tenantId
                scopeId = tenantId; this.role = role; wrappedGrant = "Z3JhbnQ="; createdAt = Instant.now()
            }.persist()
        }
}
