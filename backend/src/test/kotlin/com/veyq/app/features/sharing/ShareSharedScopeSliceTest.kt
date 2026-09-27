package com.veyq.app.features.sharing

import com.veyq.app.shared.TestIds
import com.veyq.app.shared.membership.MembershipEntity
import com.veyq.app.shared.membership.TestAdmission
import com.veyq.app.features.content.NodeEntity
import com.veyq.app.shared.scope.ScopeEntity
import com.veyq.app.testing.TestFixtures.CONTENT_KEY
import com.veyq.app.testing.TestFixtures.bearer
import com.veyq.app.testing.TestFixtures.seedNode
import com.veyq.app.testing.TestFixtures.seedTenant
import com.veyq.app.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.hasItem
import org.hamcrest.CoreMatchers.not
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Slice tests for shared-scope public shares (uc.sharing.share-shared-scope, cr-0104, ADR 0029 §6): any member of
 * a GROUP or TENANT scope may public-share a Memory there (lifting the personal-scope-only gate); removing a
 * member soft-revokes the shares that member created **in that scope** (their links then 404), leaving other
 * members' shares untouched; any remaining member may re-activate a deactivated share, which **transfers its
 * ownership**. `K_mem` is untouched — deactivation is the honest-server revocation layer. Distinct domains keep
 * the JVM-lived H2 mem DB from bleeding state between tests.
 */
@QuarkusTest
class ShareSharedScopeSliceTest {

    private val wrap = "d3JhcC1rLW1lbS11bmRlci1zaGFyZQ=="
    private val sealedName = "c2VhbGVkLW5hbWU="

    @Test
    fun groupMemberCanShareAndAnonymousResolveWorks() {
        val (tenantId, _) = seedActiveTenant("g-share.example.com")
        val member = seedTenantMember(tenantId, "m@g-share.example.com")
        val group = seedGroup(tenantId, member) // the member is (owns) the group scope
        val memory = seedNode(group, NodeEntity.MEMORY, parentId = group, wrappedContentKey = CONTENT_KEY)

        val shareId = createShare(member, memory)

        // A group Memory now shares (no more illegal_scope) and the anonymous resolve round-trips operator-blind.
        given().`when`().get("/api/shares/$shareId")
            .then().statusCode(200)
            .body("role", equalTo("VIEWER"))
            .body("wrap", equalTo(wrap))
            .body("sealedName", equalTo(sealedName))
            .body("nodes.find { it.id == '$memory' }.parentId", equalTo(null))
    }

    @Test
    fun removingGroupMemberDeactivatesOnlyTheirShares() {
        val (tenantId, owner) = seedActiveTenant("g-rm.example.com")
        val member = seedTenantMember(tenantId, "m@g-rm.example.com")
        val group = seedGroup(tenantId, owner)
        addMember(tenantId, group, member)
        val ownerShare = createShare(owner, seedNode(group, NodeEntity.MEMORY, parentId = group, wrappedContentKey = CONTENT_KEY))
        val memberShare = createShare(member, seedNode(group, NodeEntity.MEMORY, parentId = group, wrappedContentKey = CONTENT_KEY))

        given().`when`().get("/api/shares/$ownerShare").then().statusCode(200)
        given().`when`().get("/api/shares/$memberShare").then().statusCode(200)

        // the owner removes the member from the group — a removal-rotation, the one way a keyed member leaves
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body(
                """{"epoch":1,"wrappedPrev":"cHJldg==","grants":[{"userId":"$owner","wrappedGrant":"Z3JhbnQ=",${TestAdmission.SIGNATURE_JSON}}],""" +
                    """${TestAdmission.openJson(group)},"removesUserId":"$member"}""",
            )
            .`when`().post("/api/scopes/$group/epochs").then().statusCode(201)

        // the removed member's share is soft-revoked (404); the owner's own share is untouched
        given().`when`().get("/api/shares/$memberShare").then().statusCode(404).body("code", equalTo("share_not_found"))
        given().`when`().get("/api/shares/$ownerShare").then().statusCode(200)
    }

    @Test
    fun removingTenantMemberDeactivatesTheirTenantScopeShares() {
        val (tenantId, owner) = seedActiveTenant("t-rm.example.com")
        val member = seedTenantMember(tenantId, "m@t-rm.example.com")
        val memory = seedNode(tenantId, NodeEntity.MEMORY, parentId = tenantId, wrappedContentKey = CONTENT_KEY) // the TENANT scope id == tenantId
        val share = createShare(member, memory)

        given().`when`().get("/api/shares/$share").then().statusCode(200)

        given().header("Authorization", bearer(owner))
            .`when`().delete("/api/tenants/$tenantId/members/$member").then().statusCode(204)

        given().`when`().get("/api/shares/$share").then().statusCode(404).body("code", equalTo("share_not_found"))
    }

    @Test
    fun reactivateByAnotherMemberTransfersOwnershipAndResolves() {
        val (tenantId, owner) = seedActiveTenant("g-react.example.com")
        val member = seedTenantMember(tenantId, "m@g-react.example.com")
        val group = seedGroup(tenantId, owner)
        addMember(tenantId, group, member)
        val shareId = createShare(member, seedNode(group, NodeEntity.MEMORY, parentId = group, wrappedContentKey = CONTENT_KEY))

        // the creator disables it (revoke); the guest path 404s
        given().header("Authorization", bearer(member)).`when`().post("/api/shares/$shareId/revoke").then().statusCode(204)
        given().`when`().get("/api/shares/$shareId").then().statusCode(404)

        // another current member (the owner) re-activates it → 204, it resolves again, and it is now theirs
        given().header("Authorization", bearer(owner)).`when`().post("/api/shares/$shareId/reactivate").then().statusCode(204)
        given().`when`().get("/api/shares/$shareId").then().statusCode(200)

        // ownership transferred: the new owner now lists it; the original creator no longer does
        given().header("Authorization", bearer(owner)).`when`().get("/api/shares")
            .then().statusCode(200).body("find { it.shareId == '$shareId' }.enabled", equalTo(true))
        given().header("Authorization", bearer(member)).`when`().get("/api/shares")
            .then().statusCode(200).body("shareId", not(hasItem(shareId)))
    }

    @Test
    fun reactivateByNonMemberIs404() {
        val (tenantId, owner) = seedActiveTenant("g-react404.example.com")
        val group = seedGroup(tenantId, owner)
        val shareId = createShare(owner, seedNode(group, NodeEntity.MEMORY, parentId = group, wrappedContentKey = CONTENT_KEY))
        val outsider = seedUser(email = "out@g-react404.example.com")

        given().header("Authorization", bearer(outsider))
            .`when`().post("/api/shares/$shareId/reactivate")
            .then().statusCode(404).body("code", equalTo("share_not_found"))
    }

    @Test
    fun listForNodeShowsEveryMembersSharesWithMineFlag() {
        val (tenantId, owner) = seedActiveTenant("g-list.example.com")
        val member = seedTenantMember(tenantId, "m@g-list.example.com")
        val group = seedGroup(tenantId, owner)
        addMember(tenantId, group, member)
        val memory = seedNode(group, NodeEntity.MEMORY, parentId = group, wrappedContentKey = CONTENT_KEY)
        val ownerShare = createShare(owner, memory)
        val memberShare = createShare(member, memory)

        // the owner lists the Memory's shares scoped by nodeId — sees BOTH, `mine` distinguishing them (cr-0104)
        given().header("Authorization", bearer(owner)).`when`().get("/api/shares?nodeId=$memory")
            .then().statusCode(200)
            .body("find { it.shareId == '$ownerShare' }.mine", equalTo(true))
            .body("find { it.shareId == '$memberShare' }.mine", equalTo(false))

        // a non-member of the scope sees nothing (no reveal)
        val outsider = seedUser(email = "out@g-list.example.com")
        given().header("Authorization", bearer(outsider)).`when`().get("/api/shares?nodeId=$memory")
            .then().statusCode(200).body("size()", equalTo(0))
    }

    // --- helpers ---

    private fun createShare(userId: String, nodeId: String): String =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"shareId":"${TestIds.shareId()}","nodeId":"$nodeId","role":"VIEWER","wrap":"$wrap","sealedName":"$sealedName"}""")
            .`when`().post("/api/shares")
            .then().statusCode(201).body("shareId", notNullValue())
            .extract().path("shareId")

    /** An ACTIVE tenant + its TENANT scope + a granted OWNER. Returns (tenantId, ownerUserId). */
    private fun seedActiveTenant(domain: String): Pair<String, String> {
        val ownerId = seedUser(email = "owner@$domain")
        return seedTenant(domain = domain, ownerEmail = "owner@$domain", owner = ownerId) to ownerId
    }

    private fun seedTenantMember(tenantId: String, email: String): String {
        val id = seedUser(email = email)
        QuarkusTransaction.requiringNew().run { membership(id, tenantId, tenantId, MembershipEntity.MEMBER) }
        return id
    }

    /** A GROUP scope in the tenant + an OWNER membership for [ownerId]. Returns the scopeId. */
    private fun seedGroup(tenantId: String, ownerId: String): String {
        val scopeId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            ScopeEntity().apply {
                id = scopeId; this.tenantId = tenantId; kind = ScopeEntity.GROUP
                encryptedName = "bmFtZQ=="; createdAt = Instant.now()
            }.persist()
            membership(ownerId, tenantId, scopeId, MembershipEntity.OWNER)
        }
        return scopeId
    }

    private fun addMember(tenantId: String, scopeId: String, userId: String, role: String = MembershipEntity.MEMBER) =
        QuarkusTransaction.requiringNew().run { membership(userId, tenantId, scopeId, role) }

    /** Persist a membership. Must run inside an active transaction. */
    private fun membership(userId: String, tenantId: String, scopeId: String, role: String) {
        MembershipEntity().apply {
            id = UUID.randomUUID().toString(); this.userId = userId; this.tenantId = tenantId
            this.scopeId = scopeId; this.role = role; wrappedGrant = "Z3JhbnQ="; createdAt = Instant.now()
        }.persist()
    }
}
