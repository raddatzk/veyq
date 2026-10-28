package me.raddatz.veyq.core.sharing

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
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import org.hamcrest.CoreMatchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Slice tests for shared-scope public shares (uc.sharing.share-shared-scope, cr-0104, ADR 0029 §6): any member of
 * a GROUP or TENANT scope may public-share a Memory there (lifting the personal-scope-only gate); removing a
 * member soft-revokes the shares that member created **in that scope** (their links then 404), leaving other
 * members' shares untouched; any remaining member may re-activate a deactivated share, which **transfers its
 * ownership** — unless a departed member may hold its link, which is then only re-issued (ADR 0075: a new id and
 * key, the old one off, one transaction), and a removal can opt to turn off every link of the group. `K_node` is
 * untouched — deactivation is the honest-server revocation layer. Distinct domains keep the JVM-lived H2 mem DB
 * from bleeding state between tests.
 *
 * **Split from `ShareSharedScopeSliceTest`** (cr-platform-gradle-modules step 8a): these methods run with core alone, so they live in
 * `:core`; the rest of the class is `ShareSharedScopeSliceTest` in `:apps:memories`. The bodies moved verbatim.
 */
@QuarkusTest
class ShareSharedScopeCoreSliceTest {

    @Inject
    lateinit var sharing: SharingStore

    private companion object {
        const val ROUNDS = 5
    }

    /** Both removal paths count on every scope they cut, and a rotation that removes no one does not. The group
     *  path is the one where the count can be lost: the rotation also writes the scope row it locked, and only
     *  `ScopeEntity`'s `@DynamicUpdate` keeps that write from putting the count it read back. */
    @Test
    fun everyRemovalPathCountsOnTheScopesItCuts() {
        val (tenantId, owner) = seedActiveTenant("t-count.example.com")
        val member = seedTenantMember(tenantId, "m@t-count.example.com")
        val coMember = seedTenantMember(tenantId, "c@t-count.example.com")
        val group = seedGroup(tenantId, owner)
        addMember(tenantId, group, member)
        addMember(tenantId, group, coMember)

        removeByRotation(owner, group, member = null).then().statusCode(201)
        assertEquals(0, memberRemovalsOf(group)) // a plain rotation removes no one
        removeByRotation(owner, group, member).then().statusCode(201)
        assertEquals(1, memberRemovalsOf(group))

        removeFromTenant(owner, tenantId, coMember).then().statusCode(204)
        assertEquals(1, memberRemovalsOf(tenantId))
        assertEquals(2, memberRemovalsOf(group)) // the co-shared group the cascade cut
    }

    // --- re-issue (ADR 0075, B2) ---

    // --- delete (ADR 0029 §7, B2) ---

    // --- a removal that turns off every link (ADR 0075, B3) ---

    // --- the tenant half (ADR 0075, B4) ---

    // --- a tenant removal marks every link the removed member could copy (ADR 0075 point 4, the removal counter) ---

    // --- a standalone switch-off by a scope owner (ADR 0075 point 5) ---

    // --- a removal racing a write of the removed member's link (inbox #224) ---

    // --- helpers ---

    private fun delete(userId: String, shareId: String) =
        given().header("Authorization", bearer(userId)).`when`().delete("/api/shares/$shareId")

    private fun memberRemovalsOf(scopeId: String): Int =
        QuarkusTransaction.requiringNew().call { ScopeEntity.findById(scopeId)!!.memberRemovals }

    private fun revoke(userId: String, shareId: String) {
        given().header("Authorization", bearer(userId)).`when`().post("/api/shares/$shareId/revoke").then().statusCode(204)
    }

    /** The tenant owner removes [member] (the ACL-revoke cascade); [revokeLinks] is the ADR 0075 opt-in, omitted when null. */
    private fun removeFromTenant(owner: String, tenantId: String, member: String, revokeLinks: Boolean? = null) =
        given().header("Authorization", bearer(owner))
            .apply { revokeLinks?.let { queryParam("revokeLinks", it) } }
            .`when`().delete("/api/tenants/$tenantId/members/$member")

    /** The owner removes [member] from [group] — a removal-rotation to the next epoch, the one way a keyed member
     *  leaves (ADR 0024 Decision 4); [revokeLinks] is the ADR 0075 opt-in, omitted when null. */
    private fun removeByRotation(owner: String, group: String, member: String?, revokeLinks: Boolean? = null) =
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body(
                """{"epoch":${TestGrants.epochOf(group) + 1},"wrappedPrev":"cHJldg==",""" +
                    """"grants":[{"userId":"$owner","wrappedGrant":"Z3JhbnQ=",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"${TestGrants.digestOf(owner)}"}],""" +
                    TestGrants.openInvitesJson(group) + "," + TestGrants.othersPendingJson(group, owner, member) +
                    (member?.let { ""","removesUserId":"$it"""" } ?: "") +
                    (revokeLinks?.let { ""","revokeLinks":$it""" } ?: "") + "}",
            )
            .`when`().post("/api/scopes/$group/epochs")

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
                encryptedName = "bmFtZQ=="
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
