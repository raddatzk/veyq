package me.raddatz.veyq.core.spaces.scope

import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.ShareWriteProof
import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.membership.TestAdmission
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.tenant.TenantEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Slice tests for a member's scopes (en.spaces.personal-scope, cr-0026): a member births their PERSONAL scope
 * (client-supplied ciphertext grant) and lists their scopes; one personal scope per (member, tenant); the
 * active tenant is resolved from the Host (X-Forwarded-Host); operator-blind (the stored grant is exactly the
 * ciphertext the client sent). Seeds use UUID-unique domains/emails so the JVM-lived H2 mem DB (shared across
 * test classes) can't bleed state.
 *
 * **Split from `ScopesSliceTest`** (cr-platform-gradle-modules step 8a): these methods need Memories in the application at runtime, so they live in
 * `:apps:memories`; the rest of the class is `ScopesSliceTest` in `:core`. The bodies moved verbatim.
 */
@QuarkusTest
class ScopesMemoriesSliceTest {

    @Inject
    lateinit var purge: ScopePurge

    @Inject
    lateinit var scopes: ScopesStore

    private val groupGrant = "Z3JvdXAtc2NvcGUtZ3JhbnQ=" // "group-scope-grant"

    private val groupName = "c2VhbGVkLWdyb3VwLW5hbWU=" // "sealed-group-name"

    /**
     * B5 (ADR 0029 §7) — the purge must delete a group's share rows (the scope's foreign key requires it), which used
     * to FREE their ids: anyone holding a dead link's id and `K_share` could mint a share under it on a Memory of
     * their own, and every other holder of that dead link would open the minter's Memory. The id is retired instead.
     */
    @Test
    fun aPurgedGroupsShareIdsStayDead() {
        val (owner, domain) = seedMember()
        val scopeId = createGroupAs(owner, domain)
        val nodeId = createNodeIn(owner, scopeId, "retired-memory")
        val shareId = TestIds.shareId()
        QuarkusTransaction.requiringNew().run {
            ShareEntity().apply {
                id = shareId; ownerAccount = owner; this.scopeId = scopeId; this.nodeId = nodeId
                role = ShareEntity.READ; wrap = "c2hhcmUtd3JhcA=="; sealedName = "c2VhbGVkLW1lbW9yeQ=="
                createdAt = Instant.now()
            }.persist()
        }
        given().header("Authorization", bearer(owner)).`when`().delete("/api/scopes/$scopeId").then().statusCode(204)
        QuarkusTransaction.requiringNew().run {
            ScopeEntity.findById(scopeId)!!.apply { deletedAt = Instant.now().minus(Duration.ofDays(31)) }.persist()
        }
        purge.purge(scopeId)
        QuarkusTransaction.requiringNew().run { assertNull(ShareEntity.findById(shareId), "the row itself is erased") }

        // a member elsewhere, on a Memory of their own, mints a share under the dead link's id
        val (elsewhere, personal) = seedMemberScope()
        val memory = seedNode(personal, MemoriesNodeKinds.MEMORY, parentId = personal, wrappedContentKey = CONTENT_KEY)
        given().header("Authorization", bearer(elsewhere)).contentType(ContentType.JSON)
            .body("""{"shareId":"$shareId",${ShareWriteProof.field(shareId)},"nodeId":"$memory","role":"READ","wrap":"c2hhcmUtd3JhcA==","sealedName":"c2VhbGVkLW1lbW9yeQ==",${ShareWriteProof.recovery()}}""")
            .`when`().post("/api/shares")
            .then().statusCode(409).body("code", equalTo("id_taken"))
    }

    // --- a removal and its rotation are one request (ADR 0024 Decision 4) ---

    // --- every grant says who sealed it (ADR 0028 Decision 6) ---

    // --- a rotation carries every open admission forward (ADR 0020 move 6, ADR 0024 §4) ---

    // --- rename a group (uc.spaces.rename-group, cr-0097) ---

    // --- space icon (uc.spaces.set-space-icon) ---

    // --- helpers ---

    /** A minimal FOLDER-node create body; `slug` becomes a base64 slug token (unique per parent). */
    private fun nodeBody(slug: String) =
        """{"nodeId":"${UUID.randomUUID()}","kind":"FOLDER","encryptedName":"$groupName","slugToken":"${Base64.getEncoder().encodeToString(slug.toByteArray())}","orderIndex":0,"wrappedContentKey":"a21lbQ=="}"""

    /** Create a FOLDER node in a scope (a write) → the new nodeId. */
    private fun createNodeIn(owner: String, scopeId: String, slug: String): String =
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body(nodeBody(slug)).`when`().post("/api/scopes/$scopeId/nodes")
            .then().statusCode(201).extract().path("nodeId")

    /** Create a GROUP scope as the caller (the seeded name/grant are opaque here) → the new scopeId. */
    private fun createGroupAs(userId: String, domain: String): String =
        given().header("Authorization", bearer(userId))
            .header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"scopeId":"${TestIds.uuid()}","encryptedName":"$groupName","wrappedGrant":"$groupGrant",${TestAdmission.SIGNATURE_JSON},${TestAdmission.json()}}""")
            .`when`().post("/api/scopes/groups")
            .then().statusCode(201).extract().path("scopeId")

    /** Seed a user + an ACTIVE tenant (unique domain) + the user's TENANT membership (post-activation state).
     *  Returns the userId and the tenant's domain (use it as the X-Forwarded-Host). */
    private fun seedMember(): Pair<String, String> {
        val userId = seedUser()
        val tenantId = UUID.randomUUID().toString()
        val domain = "t-$tenantId.example.com"
        QuarkusTransaction.requiringNew().run {
            TenantEntity().apply {
                id = tenantId
                name = "Fam"
                this.domain = domain
                ownerEmail = "owner-$tenantId@example.com"
                status = TenantEntity.ACTIVE
            }.persist()
            ScopeEntity().apply {
                id = tenantId
                this.tenantId = tenantId
                kind = ScopeEntity.TENANT
            }.persist()
            MembershipEntity().apply {
                id = UUID.randomUUID().toString()
                this.userId = userId
                this.tenantId = tenantId
                scopeId = tenantId
                role = MembershipEntity.OWNER
                wrappedGrant = "dGVuYW50LWdyYW50" // "tenant-grant"
                createdAt = Instant.now()
            }.persist()
        }
        return userId to domain
    }
}
