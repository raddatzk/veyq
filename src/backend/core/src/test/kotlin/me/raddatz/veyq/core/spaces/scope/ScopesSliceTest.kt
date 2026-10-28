package me.raddatz.veyq.core.spaces.scope

import me.raddatz.veyq.core.blob.BlobUploadStore
import me.raddatz.veyq.core.blob.UploadOpener
import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.ShareIdentityEntity
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.tasks.CapabilityTags
import me.raddatz.veyq.core.tasks.JobDeclineEntity
import me.raddatz.veyq.core.tasks.JobEntity
import me.raddatz.veyq.core.tasks.TasksStore
import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.blobstore.RefusingStoreReclaimStore
import me.raddatz.veyq.core.blobstore.StoreReclaimEntity
import me.raddatz.veyq.core.blobstore.StoreReclaimStore
import me.raddatz.veyq.core.identity.TenantProfileEntity
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.membership.SignedGrant
import me.raddatz.veyq.core.membership.TestGrants
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.tenant.TenantEntity
import me.raddatz.veyq.core.testing.TestFixtures.IDENTITY_PUBLIC_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import me.raddatz.veyq.core.testing.whileUncommitted
import me.raddatz.veyq.core.testing.raceRounds
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
import org.hamcrest.CoreMatchers.hasItems
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Slice tests for a member's scopes (en.spaces.personal-scope, cr-0026): a member births their PERSONAL scope
 * (client-supplied ciphertext grant) and lists their scopes; one personal scope per (member, tenant); the
 * active tenant is resolved from the Host (X-Forwarded-Host); operator-blind (the stored grant is exactly the
 * ciphertext the client sent). Seeds use UUID-unique domains/emails so the JVM-lived H2 mem DB (shared across
 * test classes) can't bleed state.
 *
 * **Split** (cr-platform-gradle-modules step 8a): the methods that need Memories in the application at runtime are `ScopesMemoriesSliceTest` in `:apps:memories`.
 */
@QuarkusTest
class ScopesSliceTest {

    @Inject
    lateinit var purge: ScopePurge

    @Inject
    lateinit var tasks: TasksStore

    @Inject
    lateinit var reclaimStore: StoreReclaimStore

    @Inject
    lateinit var blobStore: BlobUploadStore

    @Inject
    lateinit var scopes: ScopesStore

    @Inject
    lateinit var handler: ScopesHandler

    private val personalGrant = "cGVyc29uYWwtc2NvcGUtZ3JhbnQ=" // "personal-scope-grant"

    private val groupGrant = "Z3JvdXAtc2NvcGUtZ3JhbnQ=" // "group-scope-grant"

    private val groupName = "c2VhbGVkLWdyb3VwLW5hbWU=" // "sealed-group-name"

    private val rotPrev = "cm90LXByZXY=" // "rot-prev" (symWrap(E1, E0))

    private val newGrant = "bmV3LWVwb2NoLWdyYW50" // "new-epoch-grant"

    private val renamedName = "cmVuYW1lZC1uYW1l" // "renamed-name" — re-sealed under K_name of the current epoch (cr-0097)

    private val sealedIcon = "c2VhbGVkLWljb24=" // "sealed-icon" — opaque here; the constant-length invariant is a web unit test

    private val replacedIcon = "cmVwbGFjZWQ=" // "replaced"

    /** Add a seeded tenant member (by username) to a group via the API, with the owner's grant to their key. */
    private fun addToGroup(owner: String, domain: String, groupId: String, username: String) {
        given().header("Authorization", bearer(owner))
            .header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"username":"$username",${addJson(groupId, username)}}""")
            .`when`().post("/api/scopes/$groupId/members").then().statusCode(201)
    }

    /** A group add's grant fields for the tenant member named [username] (ADR 0111 decision 4) — of the group's
     *  current epoch, naming that member's key; any key for a name nobody holds (refused before a key is read). */
    private fun addJson(groupId: String, username: String): String {
        val target = QuarkusTransaction.requiringNew().call {
            val tenantId = ScopeEntity.findById(groupId)!!.tenantId
            TenantProfileEntity.find("tenantId = ?1 and username = ?2", tenantId, username).firstResult()?.userId
        }
        return if (target != null) TestGrants.groupAddJson(groupId, target) else TestGrants.grantJson(TestGrants.epochOf(groupId))
    }

    @Test
    fun promoteDemoteAndLastOwnerGuard() {
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)
        val partner = seedTenantMember(tenantIdOf(domain), "partner")
        addToGroup(owner, domain, groupId, "partner")

        // Promote partner → OWNER (co-owner).
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{"role":"OWNER"}""")
            .`when`().patch("/api/scopes/$groupId/members/$partner")
            .then().statusCode(200).body("role", equalTo("OWNER"))

        // Demote the original owner → MEMBER (allowed — partner is now an owner too).
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{"role":"MEMBER"}""")
            .`when`().patch("/api/scopes/$groupId/members/$owner")
            .then().statusCode(200).body("role", equalTo("MEMBER"))

        // partner is now the LAST owner → cannot be demoted (last-owner guard).
        given().header("Authorization", bearer(partner)).contentType(ContentType.JSON)
            .body("""{"role":"MEMBER"}""")
            .`when`().patch("/api/scopes/$groupId/members/$partner")
            .then().statusCode(409).body("code", equalTo("last_owner"))
    }

    // --- a removal and its rotation are one request (ADR 0024 Decision 4) ---

    /** A removal-rotation by [caller] of [scopeId] from its current epoch, removing [removes] and granting [grantees]. */
    private fun removeRotating(caller: String, scopeId: String, removes: String, grantees: List<String>) =
        given().header("Authorization", bearer(caller)).contentType(ContentType.JSON)
            .body(
                """{"epoch":${TestGrants.epochOf(scopeId) + 1},"wrappedPrev":"$rotPrev","grants":[""" +
                    grantees.joinToString(",") { """{"userId":"$it","wrappedGrant":"$newGrant",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"${TestGrants.digestOf(it)}"}""" } +
                    """],${TestGrants.openInvitesJson(scopeId)},"removesUserId":"$removes"}""",
            )
            .`when`().post("/api/scopes/$scopeId/epochs")

    /** Whether [userId] still holds a membership row on [scopeId], read straight from the DB. */
    private fun isMember(userId: String, scopeId: String): Boolean =
        QuarkusTransaction.requiringNew().call { MembershipEntity.count("userId = ?1 and scopeId = ?2", userId, scopeId) > 0 }

    @Test
    fun aRemovalRotationWhoseGrantsNameTheRemovedMemberIsRefusedAndChangesNothing() {
        // Whatever list the server served the remover, the new epoch must never be wrapped to the member it removes.
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)
        val partner = seedTenantMember(tenantIdOf(domain), "partner")
        addToGroup(owner, domain, groupId, "partner")

        removeRotating(owner, groupId, removes = partner, grantees = listOf(owner, partner))
            .then().statusCode(422).body("code", equalTo("removed_member_granted"))
        assertTrue(isMember(partner, groupId), "a refused removal-rotation leaves the member in place")
        assertEquals(0, TestGrants.epochOf(groupId), "…and the epoch unmoved")

        // Control on the same predicate: without their grant the member is gone and the epoch is n+1, in one request.
        removeRotating(owner, groupId, removes = partner, grantees = listOf(owner)).then().statusCode(201).body("epoch", equalTo(1))
        assertTrue(!isMember(partner, groupId), "the removal-rotation deleted the membership")
        assertEquals(1, TestGrants.epochOf(groupId))
        given().header("Authorization", bearer(owner))
            .`when`().get("/api/scopes/$groupId/members")
            .then().statusCode(200).body("find { it.username == 'partner' }", equalTo(null))
    }

    @Test
    fun aRemovalRotationThatFailsAtCommitLeavesTheMemberInPlace() {
        // Atomicity. The lever: a chain link already stored at the epoch this rotation creates, so the rotation passes
        // every check and dies on `ux_scope_epoch` when the transaction flushes — after the membership was deleted
        // in it. One transaction rolls both back; a removal committed on its own would leave the member gone and
        // the group unrotated, the defect a closed tab produced.
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)
        val partner = seedTenantMember(tenantIdOf(domain), "partner")
        addToGroup(owner, domain, groupId, "partner")
        QuarkusTransaction.requiringNew().run {
            ScopeEpochEntity().apply {
                id = UUID.randomUUID().toString(); scopeId = groupId; epoch = 1; wrappedPrev = rotPrev
            }.persist()
        }

        val status = removeRotating(owner, groupId, removes = partner, grantees = listOf(owner)).then().extract().statusCode()
        assertTrue(status >= 500, "the commit failed (got $status)")
        assertTrue(isMember(partner, groupId), "a removal-rotation that failed after validation leaves the member in place")
        assertEquals(0, TestGrants.epochOf(groupId))
    }

    @Test
    fun aRemovalRotationKeepsTheOwnerGateAndTheLastOwnerGuard() {
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)
        val partner = seedTenantMember(tenantIdOf(domain), "partner")
        addToGroup(owner, domain, groupId, "partner")

        // A non-owner (partner, a MEMBER) cannot remove anyone.
        removeRotating(partner, groupId, removes = owner, grantees = emptyList())
            .then().statusCode(403).body("code", equalTo("not_scope_owner"))
        // The last owner cannot be removed.
        removeRotating(owner, groupId, removes = owner, grantees = listOf(partner))
            .then().statusCode(409).body("code", equalTo("last_owner"))
        // Nor can someone who is not a member.
        removeRotating(owner, groupId, removes = seedUser(), grantees = listOf(owner))
            .then().statusCode(404).body("code", equalTo("member_not_found"))
        assertTrue(isMember(owner, groupId) && isMember(partner, groupId))
        assertEquals(0, TestGrants.epochOf(groupId))
    }

    @Test
    fun aRemovalRotationOnATenantScopeIsRefusedSoTheCascadeIsTheOnlyWayOut() {
        // Inbox #595, measured on f9b83edeb: this answered 201 and deleted the tenant row only. The member kept every
        // group membership and read those groups on (READ passes without the tenant root), their personal scope stayed
        // live, no group rotation was queued, and a group they solely owned kept an owner who could no longer act.
        // A tenant member leaves only by the removal cascade (ADR 0025, `removeTenantMember`).
        val (owner, domain) = seedMember()
        val tenantId = tenantIdOf(domain)
        val partner = seedTenantMember(tenantId, "partner")
        val groupId = createGroupAs(partner, domain)

        removeRotating(owner, tenantId, removes = partner, grantees = listOf(owner))
            .then().statusCode(400).body("code", equalTo("invalid_request"))
        assertTrue(isMember(partner, tenantId), "the refused removal-rotation leaves the member in the tenant")
        assertTrue(isMember(partner, groupId), "…and in their group")
        assertEquals(0, TestGrants.epochOf(tenantId), "…and the tenant's epoch unmoved")

        // Control on the same scope: without a removal the tenant rotates as before.
        rotate(owner, tenantId).then().statusCode(201).body("epoch", equalTo(1))
    }

    @Test
    fun aPersonalScopeRotatesForItsOwnerAndRefusesARemoval() {
        // Inbox #595: no test pinned a PERSONAL rotation. Its owner — the securing account, ADR 0025 point 3 — rotates it
        // like any scope; a removal is refused as on a tenant (a personal scope's one member is its owner).
        val (owner, domain) = seedMember()
        val personal = given().header("Authorization", bearer(owner))
            .header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"scopeId":"${TestIds.uuid()}","wrappedGrant":"$personalGrant",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"${TestGrants.FIXTURE_DIGEST}"}""")
            .`when`().post("/api/scopes").then().statusCode(201).extract().path<String>("scopeId")

        removeRotating(owner, personal, removes = owner, grantees = emptyList())
            .then().statusCode(400).body("code", equalTo("invalid_request"))
        removeRotating(owner, personal, removes = seedUser(), grantees = listOf(owner))
            .then().statusCode(400).body("code", equalTo("invalid_request"))
        assertEquals(0, TestGrants.epochOf(personal), "a refused removal stores nothing")

        // A tenant member who is not its owner holds no row on it: the same 404 as a scope that does not exist.
        val other = seedTenantMember(tenantIdOf(domain), "other")
        rotate(other, personal).then().statusCode(404).body("code", equalTo("scope_not_found"))

        // The owner's rotation commits the next epoch and re-grants them.
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{"epoch":1,"wrappedPrev":"$rotPrev","grants":[{"userId":"$owner","wrappedGrant":"$newGrant",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"${TestGrants.digestOf(owner)}"}],${TestGrants.openInvitesJson(personal)}}""")
            .`when`().post("/api/scopes/$personal/epochs")
            .then().statusCode(201).body("epoch", equalTo(1))
        given().header("Authorization", bearer(owner)).header("X-Forwarded-Host", domain)
            .`when`().get("/api/scopes")
            .then().statusCode(200)
            .body("find { it.id == '$personal' }.currentEpoch", equalTo(1))
            .body("find { it.id == '$personal' }.grantedEpoch", equalTo(1))
            .body("find { it.id == '$personal' }.wrappedGrant", equalTo(newGrant))
    }

    @Test
    fun theMemberDeleteRefusesAKeyedMemberAndRescindsOnlyAProposal() {
        // A keyed member leaves only by a removal-rotation — a bare delete would end the removal without its rotation.
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)
        val partner = seedTenantMember(tenantIdOf(domain), "partner")
        addToGroup(owner, domain, groupId, "partner")
        // Key the partner: a rotation that re-grants them.
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{"epoch":1,"wrappedPrev":"$rotPrev","grants":[{"userId":"$owner","wrappedGrant":"$newGrant",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"${TestGrants.digestOf(owner)}"},{"userId":"$partner","wrappedGrant":"$newGrant",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"${TestGrants.digestOf(partner)}"}],${TestGrants.openInvitesJson(groupId)}}""")
            .`when`().post("/api/scopes/$groupId/epochs").then().statusCode(201)

        given().header("Authorization", bearer(owner))
            .`when`().delete("/api/scopes/$groupId/members/$partner")
            .then().statusCode(409).body("code", equalTo("removal_requires_rotation"))
        assertTrue(isMember(partner, groupId), "the refused delete deleted nothing")

        // Control: a pending consent proposal was never keyed, so the delete rescinds it without a rotation.
        val invitee = seedTenantMember(tenantIdOf(domain), "invitee")
        QuarkusTransaction.requiringNew().run {
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); userId = invitee; tenantId = tenantIdOf(domain); scopeId = groupId
                role = MembershipEntity.MEMBER; pendingConsent = true; createdAt = Instant.now()
            }.persist()
        }
        given().header("Authorization", bearer(owner))
            .`when`().delete("/api/scopes/$groupId/members/$invitee")
            .then().statusCode(204)
        assertTrue(!isMember(invitee, groupId), "the proposal is gone")
        assertEquals(1, TestGrants.epochOf(groupId), "…and nothing rotated")
    }

    @Test
    fun aNodeCreateAtAStaleEpochIsRefused() {
        // ADR 0024 Decision 6, layer 1: a writer whose grant lags a rotation would seal under a key the removed
        // member holds. The server refuses an epoch-tagged write below the current epoch.
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)
        rotate(owner, groupId).then().statusCode(201)
        val create = { epoch: Int ->
            given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
                .body(nodeBody("stale-$epoch").dropLast(1) + ""","epoch":$epoch}""")
                .`when`().post("/api/scopes/$groupId/nodes")
        }
        create(0).then().statusCode(409).body("code", equalTo("stale_epoch"))
        // Control: at the current epoch the create lands.
        create(1).then().statusCode(201)
    }

    @Test
    fun rotateBumpsEpochAndReGrantsRemaining() {
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)

        // Initial: epoch 0.
        given().header("Authorization", bearer(owner)).header("X-Forwarded-Host", domain)
            .`when`().get("/api/scopes")
            .then().statusCode(200)
            .body("find { it.id == '$groupId' }.currentEpoch", equalTo(0))
            .body("find { it.id == '$groupId' }.grantedEpoch", equalTo(0))

        // Rotate: owner mints E1 (chain-link) + re-grants self to the new epoch.
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{"epoch":1,"wrappedPrev":"$rotPrev","grants":[{"userId":"$owner","wrappedGrant":"$newGrant",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"${TestGrants.digestOf(owner)}"}],${TestGrants.openInvitesJson(groupId)}}""")
            .`when`().post("/api/scopes/$groupId/epochs")
            .then().statusCode(201).body("epoch", equalTo(1))

        // The scope now reports epoch 1, the owner re-granted (grantedEpoch 1 + the new grant).
        given().header("Authorization", bearer(owner)).header("X-Forwarded-Host", domain)
            .`when`().get("/api/scopes")
            .then().statusCode(200)
            .body("find { it.id == '$groupId' }.currentEpoch", equalTo(1))
            .body("find { it.id == '$groupId' }.grantedEpoch", equalTo(1))
            .body("find { it.id == '$groupId' }.wrappedGrant", equalTo(newGrant))

        // The epoch-chain link is readable (opaque ciphertext, verbatim).
        given().header("Authorization", bearer(owner))
            .`when`().get("/api/scopes/$groupId/epochs")
            .then().statusCode(200)
            .body("[0].epoch", equalTo(1))
            .body("[0].wrappedPrev", equalTo(rotPrev))
    }

    @Test
    fun listEpochsSeparatesTheMembershipBoundaryFromTheKeyHolderCapability() {
        // The path cr-0162 fixed, and the reason the leak survived: it had no test at all. Three actors, three
        // answers — a stranger must not be able to tell the real group from an invented id (ADR 0042).
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)

        // A member who holds no grant is inside the boundary → 403, not 404: they already know the group exists, so
        // refusing them discloses nothing. Every membership is born with a grant now (ADR 0111); the column stays
        // nullable for rows written before a key exists, which is the state seeded here.
        val grantless = seedTenantMember(tenantIdOf(domain), "grantless")
        QuarkusTransaction.requiringNew().run {
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); userId = grantless; tenantId = tenantIdOf(domain); scopeId = groupId
                role = MembershipEntity.MEMBER; wrappedGrant = null; createdAt = Instant.now()
            }.persist()
        }
        given().header("Authorization", bearer(grantless))
            .`when`().get("/api/scopes/$groupId/epochs")
            .then().statusCode(403).body("code", equalTo("not_key_holder"))

        // A stranger is outside it → 404, and byte-identical to the answer for a group id that does not exist.
        val stranger = seedUser()
        given().header("Authorization", bearer(stranger))
            .`when`().get("/api/scopes/$groupId/epochs")
            .then().statusCode(404).body("code", equalTo("scope_not_found"))
        given().header("Authorization", bearer(stranger))
            .`when`().get("/api/scopes/${UUID.randomUUID()}/epochs")
            .then().statusCode(404).body("code", equalTo("scope_not_found"))
    }

    @Test
    fun rotateNonOwnerIs403() {
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)
        val partner = seedTenantMember(tenantIdOf(domain), "partner")
        addToGroup(owner, domain, groupId, "partner") // partner is a MEMBER, not owner

        given().header("Authorization", bearer(partner)).contentType(ContentType.JSON)
            .body("""{"epoch":1,"wrappedPrev":"$rotPrev","grants":[],${TestGrants.openInvitesJson(groupId)},${TestGrants.othersPendingJson(groupId)}}""")
            .`when`().post("/api/scopes/$groupId/epochs")
            .then().statusCode(403).body("code", equalTo("not_scope_owner"))
    }

    @Test
    fun aRotationBuiltOnASupersededEpochIs409AndLeavesTheChainIntact() {
        // Two owners each build a rotation from epoch 0: a fresh key, a chain-link wrapping E_0, and re-grants.
        // The first commits epoch 1. The second link also wraps E_0, so storing it as epoch 2 would send every
        // holder of E_2 peeling to E_0 while believing it E_1, and everything sealed under E_1 would be
        // unreachable. The server cannot tell a race from a lagging grant; both are this request.
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)
        val coOwner = seedTenantMember(tenantIdOf(domain), "coowner")
        addToGroup(owner, domain, groupId, "coowner")
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{"role":"OWNER"}""")
            .`when`().patch("/api/scopes/$groupId/members/$coOwner").then().statusCode(200)
        val linkByOwner = "bGluay1ieS1vd25lcg==" // "link-by-owner" (symWrap(E_1, E_0))
        val linkByCoOwner = "bGluay1ieS1jby1vd25lcg==" // "link-by-co-owner" (symWrap(E_x, E_0), built on 0)
        val coOwnerGrantFromOwner = "Y28tb3duZXItZ3JhbnQ=" // "co-owner-grant"

        // Control on the same predicate: a rotation stating the next epoch commits.
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body(
                """{"epoch":1,"wrappedPrev":"$linkByOwner","grants":[""" +
                    """{"userId":"$owner","wrappedGrant":"$newGrant",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"${TestGrants.digestOf(owner)}"},{"userId":"$coOwner","wrappedGrant":"$coOwnerGrantFromOwner",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"${TestGrants.digestOf(coOwner)}"}],${TestGrants.openInvitesJson(groupId)}}""",
            )
            .`when`().post("/api/scopes/$groupId/epochs")
            .then().statusCode(201).body("epoch", equalTo(1))

        // The co-owner's rotation, built on epoch 0, arrives second. It must not commit.
        given().header("Authorization", bearer(coOwner)).contentType(ContentType.JSON)
            .body("""{"epoch":1,"wrappedPrev":"$linkByCoOwner","grants":[{"userId":"$owner","wrappedGrant":"$groupGrant",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"${TestGrants.digestOf(owner)}"}],${TestGrants.openInvitesJson(groupId)}}""")
            .`when`().post("/api/scopes/$groupId/epochs")
            .then().statusCode(409).body("code", equalTo("stale_epoch"))

        // Nothing it carried landed: one link, the owner's; the owner's re-grants stand.
        given().header("Authorization", bearer(owner))
            .`when`().get("/api/scopes/$groupId/epochs")
            .then().statusCode(200)
            .body("size()", equalTo(1))
            .body("[0].wrappedPrev", equalTo(linkByOwner))
        given().header("Authorization", bearer(owner)).header("X-Forwarded-Host", domain)
            .`when`().get("/api/scopes")
            .then().statusCode(200)
            .body("find { it.id == '$groupId' }.currentEpoch", equalTo(1))
            .body("find { it.id == '$groupId' }.wrappedGrant", equalTo(newGrant))
        given().header("Authorization", bearer(coOwner)).header("X-Forwarded-Host", domain)
            .`when`().get("/api/scopes")
            .then().statusCode(200)
            .body("find { it.id == '$groupId' }.grantedEpoch", equalTo(1))
            .body("find { it.id == '$groupId' }.wrappedGrant", equalTo(coOwnerGrantFromOwner))

        // Re-opened at epoch 1 (the grant the owner's rotation gave it), the co-owner's retry commits epoch 2.
        // Without this, a scope that refused every second rotation would pass the refusal above.
        given().header("Authorization", bearer(coOwner)).contentType(ContentType.JSON)
            .body("""{"epoch":2,"wrappedPrev":"$linkByCoOwner","grants":[],${TestGrants.openInvitesJson(groupId)},${TestGrants.othersPendingJson(groupId)}}""")
            .`when`().post("/api/scopes/$groupId/epochs")
            .then().statusCode(201).body("epoch", equalTo(2))
    }

    @Test
    fun twoConcurrentRotationsStatingTheSameEpochCommitOnce() {
        // The same race as above, but in flight together: both commits read the scope before either writes. The
        // row lock serializes them, so the loser reads the winner's epoch and is a clean `409 stale_epoch` — the
        // answer the client retries. Without the lock both pass the policy and the loser dies on `ux_scope_epoch`.
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)
        val barrier = java.util.concurrent.CyclicBarrier(2)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val answers = pool.invokeAll(
                listOf(rotPrev, newGrant).map { link ->
                    java.util.concurrent.Callable {
                        val request = given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
                            .body("""{"epoch":1,"wrappedPrev":"$link","grants":[],${TestGrants.openInvitesJson(groupId)},${TestGrants.othersPendingJson(groupId)}}""")
                        barrier.await() // release both commits as tightly together as possible
                        request.`when`().post("/api/scopes/$groupId/epochs").then().extract()
                            .let { it.statusCode() to it.path<String?>("code") }
                    }
                },
            ).map { it.get() }
            assertEquals(1, answers.count { it.first == 201 }, "exactly one concurrent rotation may commit epoch 1, got $answers")
            assertEquals(1, answers.count { it == (409 to "stale_epoch") }, "the loser must be a clean 409 stale_epoch, got $answers")
        } finally {
            pool.shutdownNow()
        }
        given().header("Authorization", bearer(owner))
            .`when`().get("/api/scopes/$groupId/epochs")
            .then().statusCode(200).body("size()", equalTo(1))
    }

    // --- every grant says who sealed it (ADR 0028 Decision 6) ---

    @Test
    fun aGrantIsServedWithItsGranterSignatureAndKey() {
        // The recipient verifies before it opens, so the read hands over all three: the granter's id, their
        // signature over the grant, and their current identity key — the server verifies none of it.
        val (owner, domain) = seedMember()
        given().header("Authorization", bearer(owner)).header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"scopeId":"${TestIds.uuid()}","wrappedGrant":"$personalGrant",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"${TestGrants.FIXTURE_DIGEST}"}""")
            .`when`().post("/api/scopes").then().statusCode(201)
        given().header("Authorization", bearer(owner)).header("X-Forwarded-Host", domain)
            .`when`().get("/api/scopes").then().statusCode(200)
            .body("find { it.kind == 'PERSONAL' }.granterUserId", equalTo(owner))
            .body("find { it.kind == 'PERSONAL' }.grantSignature", equalTo(TestGrants.SIGNATURE))
            .body("find { it.kind == 'PERSONAL' }.granterIdentityPublicKey", equalTo(IDENTITY_PUBLIC_KEY))
            // A grant written before grants were signed (the seeded tenant root) carries none — the client refuses it.
            .body("find { it.kind == 'TENANT' }.granterUserId", nullValue())
            .body("find { it.kind == 'TENANT' }.grantSignature", nullValue())
    }

    @Test
    fun aGroupAddGrantedAtAStaleEpochIsRefused() {
        // A rotation that committed between the owner's wrap and the add could not have re-granted the new member, so
        // the add is refused rather than stored with a key the group already left; at the current epoch it lands.
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)
        val late = seedTenantMember(tenantIdOf(domain), "late")
        rotate(owner, groupId).then().statusCode(201)
        val add = { epoch: Int ->
            given().header("Authorization", bearer(owner)).header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
                .body("""{"username":"late",${TestGrants.grantJson(epoch, TestGrants.digestOf(late))}}""").`when`().post("/api/scopes/$groupId/members")
        }
        add(0).then().statusCode(409).body("code", equalTo("stale_epoch"))
        assertTrue(!isMember(late, groupId), "nothing written")
        add(1).then().statusCode(201)
        assertEquals(1, QuarkusTransaction.requiringNew().call {
            MembershipEntity.find("userId = ?1 and scopeId = ?2", late, groupId).firstResult()!!.grantedEpoch
        })
    }

    /** A rotation of [scopeId] to epoch 1 that re-grants nobody and declares every open invite pending (a TENANT's). */
    private fun rotate(owner: String, scopeId: String) =
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{"epoch":1,"wrappedPrev":"$rotPrev","grants":[],${TestGrants.openInvitesJson(scopeId)},${TestGrants.othersPendingJson(scopeId)}}""")
            .`when`().post("/api/scopes/$scopeId/epochs")

    // --- rename a group (uc.spaces.rename-group, cr-0097) ---

    @Test
    fun renameGroupUpdatesEncryptedNameAndNameEpoch() {
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)

        // Born at name-epoch 0 with the creation name.
        given().header("Authorization", bearer(owner)).header("X-Forwarded-Host", domain)
            .`when`().get("/api/scopes")
            .then().statusCode(200)
            .body("find { it.id == '$groupId' }.encryptedName", equalTo(groupName))
            .body("find { it.id == '$groupId' }.nameEpoch", equalTo(0))

        // Owner renames at the current epoch (0) → 204.
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{"encryptedName":"$renamedName","nameEpoch":0}""")
            .`when`().post("/api/scopes/$groupId/name")
            .then().statusCode(204)

        // The row now carries the new sealed name, still at name-epoch 0.
        given().header("Authorization", bearer(owner)).header("X-Forwarded-Host", domain)
            .`when`().get("/api/scopes")
            .then().statusCode(200)
            .body("find { it.id == '$groupId' }.encryptedName", equalTo(renamedName))
            .body("find { it.id == '$groupId' }.nameEpoch", equalTo(0))
    }

    /** ADR 0093 § 8 (en.platform.field-editor): a group's name and icon record the member who last wrote them — the
     *  creator, then whoever renames or sets the icon — and a cleared icon has no editor. The rename lands on a name a
     *  stand-in holds, so the write is seen to move it. */
    @Test
    fun aGroupsNameAndIconRecordTheMemberWhoWroteThemAndAClearedIconHasNone() {
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)
        fun editors() = QuarkusTransaction.requiringNew().call {
            ScopeEntity.findById(groupId)!!.let { it.nameEditorUserId to it.iconEditorUserId }
        }
        assertEquals(owner to null, editors(), "the creator named it; no icon, no editor of it")

        val standIn = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run { ScopeEntity.findById(groupId)!!.nameEditorUserId = standIn }
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{"encryptedName":"$renamedName","nameEpoch":0}""")
            .`when`().post("/api/scopes/$groupId/name").then().statusCode(204)
        assertEquals(owner to null, editors())

        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{"sealedIcon":"$sealedIcon","iconEpoch":0}""")
            .`when`().post("/api/scopes/$groupId/icon").then().statusCode(204)
        assertEquals(owner to owner, editors())
        given().header("Authorization", bearer(owner)).`when`().delete("/api/scopes/$groupId/icon").then().statusCode(204)
        assertEquals(owner to null, editors(), "a cleared icon has no editor")
    }

    @Test
    fun renameNonOwnerIs403() {
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)
        val partner = seedTenantMember(tenantIdOf(domain), "partner")
        addToGroup(owner, domain, groupId, "partner") // a MEMBER, not the name authority

        given().header("Authorization", bearer(partner)).contentType(ContentType.JSON)
            .body("""{"encryptedName":"$renamedName","nameEpoch":0}""")
            .`when`().post("/api/scopes/$groupId/name")
            .then().statusCode(403).body("code", equalTo("not_scope_owner"))
    }

    @Test
    fun renameStaleEpochIs409AndLeavesTheNameUnchanged() {
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)

        // currentEpoch is 0; a rename claiming epoch 1 is stale → 409 stale_epoch.
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{"encryptedName":"$renamedName","nameEpoch":1}""")
            .`when`().post("/api/scopes/$groupId/name")
            .then().statusCode(409).body("code", equalTo("stale_epoch"))

        given().header("Authorization", bearer(owner)).header("X-Forwarded-Host", domain)
            .`when`().get("/api/scopes")
            .then().statusCode(200).body("find { it.id == '$groupId' }.encryptedName", equalTo(groupName))
    }

    @Test
    fun renameAfterRotationSealsUnderTheNewEpoch() {
        // Forward-secrecy (metadata level): after a rotation to E1 the rename must carry nameEpoch=1 (the old
        // epoch 0 is now stale) and the row's name_epoch tracks the current epoch — so a member removed at E0
        // (holding only E0) cannot derive K_name of E1 for the new name (the crypto FS itself is asserted web-side).
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{"epoch":1,"wrappedPrev":"$rotPrev","grants":[{"userId":"$owner","wrappedGrant":"$newGrant",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"${TestGrants.digestOf(owner)}"}],${TestGrants.openInvitesJson(groupId)}}""")
            .`when`().post("/api/scopes/$groupId/epochs").then().statusCode(201).body("epoch", equalTo(1))

        // A rename at the OLD epoch 0 is now stale.
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{"encryptedName":"$renamedName","nameEpoch":0}""")
            .`when`().post("/api/scopes/$groupId/name").then().statusCode(409).body("code", equalTo("stale_epoch"))

        // A rename at the CURRENT epoch 1 succeeds and the row's name_epoch advances to 1.
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{"encryptedName":"$renamedName","nameEpoch":1}""")
            .`when`().post("/api/scopes/$groupId/name").then().statusCode(204)
        given().header("Authorization", bearer(owner)).header("X-Forwarded-Host", domain)
            .`when`().get("/api/scopes")
            .then().statusCode(200)
            .body("find { it.id == '$groupId' }.encryptedName", equalTo(renamedName))
            .body("find { it.id == '$groupId' }.nameEpoch", equalTo(1))
    }

    @Test
    fun aRenameWhoseGatePassedBeforeARotationCommittedIsStale() {
        // The epoch gate (`nameEpoch == currentEpoch`) reads the epoch before the write's transaction: a rotation that
        // commits in between let a name sealed under the pre-rotation epoch land — readable by a member the rotation
        // removed (ADR 0024). The write itself must re-check the epoch and answer StaleEpoch (inbox #224, #275).
        raceRounds { round ->
            val (owner, scopeId) = seedMemberScope(ScopeEntity.GROUP)
            val outcome = whileUncommitted(
                change = { rotateToEpochOne(owner, scopeId, round) },
                overlapping = { handler.renameGroup(owner, scopeId, renamedName, 0) },
            )
            assertEquals(RenameGroupOutcome.StaleEpoch, outcome, "round $round")
            QuarkusTransaction.requiringNew().run {
                val scope = ScopeEntity.findById(scopeId)!!
                assertNull(scope.encryptedName, "round $round: a name sealed under the old epoch landed")
                assertEquals(1, scope.currentEpoch, "round $round")
            }
            // Control: the same rename at the current epoch lands.
            assertEquals(RenameGroupOutcome.Ok, handler.renameGroup(owner, scopeId, renamedName, 1), "round $round")
            QuarkusTransaction.requiringNew().run { assertEquals(renamedName, ScopeEntity.findById(scopeId)!!.encryptedName) }
        }
    }

    @Test
    fun anIconSetWhoseGatePassedBeforeARotationCommittedIsStale() {
        raceRounds { round ->
            val (owner, scopeId) = seedMemberScope(ScopeEntity.GROUP)
            val outcome = whileUncommitted(
                change = { rotateToEpochOne(owner, scopeId, round) },
                overlapping = { handler.setScopeIcon(owner, scopeId, sealedIcon, 0) },
            )
            assertEquals(SetScopeIconOutcome.StaleEpoch, outcome, "round $round")
            QuarkusTransaction.requiringNew().run {
                assertNull(ScopeEntity.findById(scopeId)!!.sealedIcon, "round $round: an icon sealed under the old epoch landed")
            }
            // Control: the same set at the current epoch lands.
            assertEquals(SetScopeIconOutcome.Ok, handler.setScopeIcon(owner, scopeId, sealedIcon, 1), "round $round")
            QuarkusTransaction.requiringNew().run { assertEquals(sealedIcon, ScopeEntity.findById(scopeId)!!.sealedIcon) }
        }
    }

    /** The real rotation commit (ADR 0024 §4) of a group whose only member is its owner, from epoch 0 to 1. */
    private fun rotateToEpochOne(owner: String, scopeId: String, round: Int) {
        val decision = scopes.rotate(
            scopeId, 1, rotPrev, owner, listOf(RotationGrant(owner, SignedGrant(newGrant, TestGrants.SIGNATURE), TestGrants.digestOf(owner))),
            emptyList(), emptySet(),
        )
        assertTrue(decision is RotationDecision.Commit, "round $round: $decision")
    }

    @Test
    fun anIconClearOverlappingARotationKeepsTheNewEpoch() {
        // A managed write of the scope row that read it before a rotation committed must not write the old
        // `currentEpoch` back: the counter would step backwards and the next rotation would issue an epoch number
        // already in use (inbox #224). The clear stays a managed write, so it is this race's writer — the rename and
        // the icon set are epoch-gated updates of their own columns.
        raceRounds { round ->
            val (owner, scopeId) = seedMemberScope(ScopeEntity.GROUP)
            QuarkusTransaction.requiringNew().run { ScopeEntity.findById(scopeId)!!.sealedIcon = sealedIcon }
            whileUncommitted(
                change = { rotateToEpochOne(owner, scopeId, round) },
                overlapping = { scopes.clearIcon(scopeId) },
            )
            QuarkusTransaction.requiringNew().run {
                val scope = ScopeEntity.findById(scopeId)!!
                assertEquals(1, scope.currentEpoch, "round $round: the icon clear rolled the epoch back")
                // Control: the clear itself landed.
                assertNull(scope.sealedIcon, "round $round")
            }
        }
    }

    @Test
    fun renamePendingDeletionIs409() {
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)
        given().header("Authorization", bearer(owner))
            .`when`().delete("/api/scopes/$groupId").then().statusCode(204) // enters the grace period

        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{"encryptedName":"$renamedName","nameEpoch":0}""")
            .`when`().post("/api/scopes/$groupId/name")
            .then().statusCode(409).body("code", equalTo("scope_deleting"))
    }

    // --- space icon (uc.spaces.set-space-icon) ---

    @Test
    fun setScopeIconUpdatesSealedIconAndIconEpochThenClearResets() {
        val (owner, domain) = seedMember()
        val tenantId = tenantIdOf(domain)

        // Born with no icon.
        given().header("Authorization", bearer(owner)).header("X-Forwarded-Host", domain)
            .`when`().get("/api/scopes").then().statusCode(200)
            .body("find { it.id == '$tenantId' }.sealedIcon", nullValue())
            .body("find { it.id == '$tenantId' }.iconEpoch", equalTo(0))

        // The tenant owner sets the icon at the current epoch (0) → 204.
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{"sealedIcon":"$sealedIcon","iconEpoch":0}""")
            .`when`().post("/api/scopes/$tenantId/icon").then().statusCode(204)

        // It shows on the tenant's own scope row.
        given().header("Authorization", bearer(owner)).header("X-Forwarded-Host", domain)
            .`when`().get("/api/scopes").then().statusCode(200)
            .body("find { it.id == '$tenantId' }.sealedIcon", equalTo(sealedIcon))
            .body("find { it.id == '$tenantId' }.iconEpoch", equalTo(0))

        // And on the widened my-tenants read (the switcher's cross-tenant path), with the caller's own grant.
        given().header("Authorization", bearer(owner))
            .`when`().get("/api/scopes/my-tenants").then().statusCode(200)
            .body("[0].sealedIcon", equalTo(sealedIcon))
            .body("[0].iconEpoch", equalTo(0))
            .body("[0].wrappedGrant", notNullValue())

        // Replace it (still epoch 0) → 204.
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{"sealedIcon":"$replacedIcon","iconEpoch":0}""")
            .`when`().post("/api/scopes/$tenantId/icon").then().statusCode(204)
        given().header("Authorization", bearer(owner)).header("X-Forwarded-Host", domain)
            .`when`().get("/api/scopes").then().statusCode(200)
            .body("find { it.id == '$tenantId' }.sealedIcon", equalTo(replacedIcon))

        // Remove it → 204; every member falls back to the initial (sealedIcon null again).
        given().header("Authorization", bearer(owner))
            .`when`().delete("/api/scopes/$tenantId/icon").then().statusCode(204)
        given().header("Authorization", bearer(owner)).header("X-Forwarded-Host", domain)
            .`when`().get("/api/scopes").then().statusCode(200)
            .body("find { it.id == '$tenantId' }.sealedIcon", nullValue())
            .body("find { it.id == '$tenantId' }.iconEpoch", equalTo(0))
    }

    @Test
    fun setScopeIconNonOwnerIs403EvenWithAPersonalScopeOwnerRow() {
        // The owner gate keys on the tenant ROOT scope (isScopeOwner(caller, scopeId == tenantId), ADR 0007). A
        // plain member also OWNS their personal scope, whose membership carries the SAME tenantId — a gate keyed on
        // tenantId would read that OWNER row and wrongly admit them (cr-0163's escalation shape). Seed exactly that
        // and assert 403: reverting the gate to a tenantId key reds this test. (The role bug itself is #93, already
        // fixed + tested; this pins the *icon* gate against the same trap.)
        val (_, domain) = seedMember()
        val tenantId = tenantIdOf(domain)
        val member = seedUser()
        QuarkusTransaction.requiringNew().run {
            val personalId = UUID.randomUUID().toString()
            ScopeEntity().apply {
                id = personalId; this.tenantId = tenantId; kind = ScopeEntity.PERSONAL
            }.persist()
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); this.userId = member; this.tenantId = tenantId
                scopeId = personalId; role = MembershipEntity.OWNER
                wrappedGrant = "cGVyc29uYWwtZ3JhbnQ=" // "personal-grant"
                createdAt = Instant.now()
            }.persist()
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); this.userId = member; this.tenantId = tenantId
                scopeId = tenantId; role = MembershipEntity.MEMBER
                wrappedGrant = "bWVtYmVyLWdyYW50" // "member-grant"
                createdAt = Instant.now()
            }.persist()
        }
        given().header("Authorization", bearer(member)).contentType(ContentType.JSON)
            .body("""{"sealedIcon":"$sealedIcon","iconEpoch":0}""")
            .`when`().post("/api/scopes/$tenantId/icon")
            .then().statusCode(403).body("code", equalTo("not_scope_owner"))
    }

    @Test
    fun setScopeIconStaleEpochIs409AndLeavesTheIconUnset() {
        val (owner, domain) = seedMember()
        val tenantId = tenantIdOf(domain)
        // currentEpoch is 0; a set claiming epoch 1 is stale → 409 stale_epoch.
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{"sealedIcon":"$sealedIcon","iconEpoch":1}""")
            .`when`().post("/api/scopes/$tenantId/icon")
            .then().statusCode(409).body("code", equalTo("stale_epoch"))
        given().header("Authorization", bearer(owner)).header("X-Forwarded-Host", domain)
            .`when`().get("/api/scopes").then().statusCode(200)
            .body("find { it.id == '$tenantId' }.sealedIcon", nullValue())
    }

    @Test
    fun clearScopeIconIsIdempotentAndOwnerGated() {
        val (owner, domain) = seedMember()
        val tenantId = tenantIdOf(domain)
        // Idempotent: clearing an already-iconless scope → 204.
        given().header("Authorization", bearer(owner))
            .`when`().delete("/api/scopes/$tenantId/icon").then().statusCode(204)
        // A plain tenant member cannot clear → 403.
        val member = seedTenantMember(tenantId, "member")
        given().header("Authorization", bearer(member))
            .`when`().delete("/api/scopes/$tenantId/icon").then().statusCode(403).body("code", equalTo("not_scope_owner"))
        // A non-member gets the uniform 404 (membership first, ADR 0042).
        val outsider = seedUser()
        given().header("Authorization", bearer(outsider))
            .`when`().delete("/api/scopes/$tenantId/icon").then().statusCode(404)
    }

    @Test
    fun setScopeIconOnAPersonalScopeIs404() {
        // The residual of the old TENANT-only guard (the group slice lifted it for GROUP): a PERSONAL scope has
        // no icon — nothing tells it apart from its owner — so the kind guard rejects it with the same uniform
        // 404 a non-member gets, never leaking that the scope exists. Reverting the guard to `!= TENANT` reds the
        // GROUP tests below; reverting it to `!= GROUP` reds this one.
        val (userId, domain) = seedMember()
        val personalId = given().header("Authorization", bearer(userId))
            .header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"scopeId":"${TestIds.uuid()}","wrappedGrant":"$personalGrant",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"${TestGrants.FIXTURE_DIGEST}"}""")
            .`when`().post("/api/scopes").then().statusCode(201).extract().path<String>("scopeId")
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"sealedIcon":"$sealedIcon","iconEpoch":0}""")
            .`when`().post("/api/scopes/$personalId/icon").then().statusCode(404)
    }

    @Test
    fun setScopeIconOnAGroupByOwnerThenCoOwnerUpdatesSealedIcon() {
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)

        // The group owner sets the icon at the current epoch (0) → 204, and it shows on the group's scope row.
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{"sealedIcon":"$sealedIcon","iconEpoch":0}""")
            .`when`().post("/api/scopes/$groupId/icon").then().statusCode(204)
        given().header("Authorization", bearer(owner)).header("X-Forwarded-Host", domain)
            .`when`().get("/api/scopes").then().statusCode(200)
            .body("find { it.id == '$groupId' }.sealedIcon", equalTo(sealedIcon))
            .body("find { it.id == '$groupId' }.iconEpoch", equalTo(0))

        // A promoted co-owner (G3/ADR 0014) is an OWNER too and may replace it → 204 — the owner gate needs no
        // change (isScopeOwner is true for any OWNER-role membership on the scope, the same gate renameGroup uses).
        val partner = seedTenantMember(tenantIdOf(domain), "partner")
        addToGroup(owner, domain, groupId, "partner")
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{"role":"OWNER"}""").`when`().patch("/api/scopes/$groupId/members/$partner").then().statusCode(200)
        given().header("Authorization", bearer(partner)).contentType(ContentType.JSON)
            .body("""{"sealedIcon":"$replacedIcon","iconEpoch":0}""")
            .`when`().post("/api/scopes/$groupId/icon").then().statusCode(204)
        given().header("Authorization", bearer(owner)).header("X-Forwarded-Host", domain)
            .`when`().get("/api/scopes").then().statusCode(200)
            .body("find { it.id == '$groupId' }.sealedIcon", equalTo(replacedIcon))
    }

    @Test
    fun setScopeIconOnAGroupNonOwnerIs403AndNonMemberIs404() {
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)
        val member = seedTenantMember(tenantIdOf(domain), "member")
        addToGroup(owner, domain, groupId, "member")

        // A plain group member (not an owner) → 403 not_scope_owner.
        given().header("Authorization", bearer(member)).contentType(ContentType.JSON)
            .body("""{"sealedIcon":"$sealedIcon","iconEpoch":0}""")
            .`when`().post("/api/scopes/$groupId/icon").then().statusCode(403).body("code", equalTo("not_scope_owner"))

        // A non-member gets the uniform 404 (membership first, ADR 0042) — never leaks that the group exists.
        val outsider = seedUser()
        given().header("Authorization", bearer(outsider)).contentType(ContentType.JSON)
            .body("""{"sealedIcon":"$sealedIcon","iconEpoch":0}""")
            .`when`().post("/api/scopes/$groupId/icon").then().statusCode(404)
    }

    @Test
    fun setScopeIconOnAGroupAfterRotationIsStaleAtOldEpochThenSealsAtNew() {
        // The group-only interaction a tenant can't exercise (a tenant never rotates, so its icon epoch is always
        // 0). A member-removal rotation (ADR 0024) advances currentEpoch; a set claiming the OLD epoch is stale
        // (409), a set at the NEW epoch seals and the row reports that iconEpoch (a later-epoch reader peels the
        // chain down to it). Rotation itself never touches sealed_icon/icon_epoch.
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)

        // Rotate 0 → 1 (owner mints E1 + re-grants self).
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{"epoch":1,"wrappedPrev":"$rotPrev","grants":[{"userId":"$owner","wrappedGrant":"$newGrant",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"${TestGrants.digestOf(owner)}"}],${TestGrants.openInvitesJson(groupId)}}""")
            .`when`().post("/api/scopes/$groupId/epochs").then().statusCode(201).body("epoch", equalTo(1))

        // A set at the OLD epoch 0 is now stale → 409; the icon stays unset.
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{"sealedIcon":"$sealedIcon","iconEpoch":0}""")
            .`when`().post("/api/scopes/$groupId/icon").then().statusCode(409).body("code", equalTo("stale_epoch"))
        given().header("Authorization", bearer(owner)).header("X-Forwarded-Host", domain)
            .`when`().get("/api/scopes").then().statusCode(200)
            .body("find { it.id == '$groupId' }.sealedIcon", nullValue())

        // A set at the CURRENT epoch 1 succeeds; the row reports iconEpoch 1.
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{"sealedIcon":"$sealedIcon","iconEpoch":1}""")
            .`when`().post("/api/scopes/$groupId/icon").then().statusCode(204)
        given().header("Authorization", bearer(owner)).header("X-Forwarded-Host", domain)
            .`when`().get("/api/scopes").then().statusCode(200)
            .body("find { it.id == '$groupId' }.sealedIcon", equalTo(sealedIcon))
            .body("find { it.id == '$groupId' }.iconEpoch", equalTo(1))
    }

    @Test
    fun setScopeIconOnAGroupPendingDeletionIs409() {
        // A group has a grace-delete a tenant lacks — a set during the grace period writes epoch-current
        // ciphertext and is rejected (unlike a clear, which is allowed; see clearScopeIconOnAGroup...).
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)
        given().header("Authorization", bearer(owner))
            .`when`().delete("/api/scopes/$groupId").then().statusCode(204) // enters the grace period
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{"sealedIcon":"$sealedIcon","iconEpoch":0}""")
            .`when`().post("/api/scopes/$groupId/icon")
            .then().statusCode(409).body("code", equalTo("scope_deleting"))
    }

    @Test
    fun clearScopeIconOnAGroupIsIdempotentAndRefusedDuringGrace() {
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)
        // Set then clear → 204, back to null.
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{"sealedIcon":"$sealedIcon","iconEpoch":0}""")
            .`when`().post("/api/scopes/$groupId/icon").then().statusCode(204)
        given().header("Authorization", bearer(owner))
            .`when`().delete("/api/scopes/$groupId/icon").then().statusCode(204)
        given().header("Authorization", bearer(owner)).header("X-Forwarded-Host", domain)
            .`when`().get("/api/scopes").then().statusCode(200)
            .body("find { it.id == '$groupId' }.sealedIcon", nullValue())
        // Idempotent: clearing an already-iconless group → 204.
        given().header("Authorization", bearer(owner))
            .`when`().delete("/api/scopes/$groupId/icon").then().statusCode(204)
        // Like every write, a clear is refused once the group enters its deletion grace: the window promises that
        // nothing about the space changes, its icon included (uc.spaces.scope-access).
        given().header("Authorization", bearer(owner))
            .`when`().delete("/api/scopes/$groupId").then().statusCode(204) // grace
        given().header("Authorization", bearer(owner))
            .`when`().delete("/api/scopes/$groupId/icon").then().statusCode(409).body("code", equalTo("scope_deleting"))
    }

    @Test
    fun createPersonalScopeThenListReturnsBoth() {
        val (userId, domain) = seedMember()
        val scopeId = given().header("Authorization", bearer(userId))
            .header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"scopeId":"${TestIds.uuid()}","wrappedGrant":"$personalGrant",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"${TestGrants.FIXTURE_DIGEST}"}""")
            .`when`().post("/api/scopes")
            .then().statusCode(201)
            .body("kind", equalTo("PERSONAL"))
            .body("scopeId", notNullValue())
            .extract().path<String>("scopeId")

        // The list now shows the TENANT root + the new PERSONAL scope; the personal grant is the verbatim
        // ciphertext the client sent (operator-blind — the server stored it without interpreting it).
        given().header("Authorization", bearer(userId))
            .header("X-Forwarded-Host", domain)
            .`when`().get("/api/scopes")
            .then().statusCode(200)
            .body("kind", hasItems("TENANT", "PERSONAL"))
            .body("find { it.id == '$scopeId' }.wrappedGrant", equalTo(personalGrant))
    }

    @Test
    fun listMyScopesCarriesTheCallersRole() {
        // The scope list surfaces the caller's OWN role per scope (cr-0087) — so the client can list groups it
        // owns without a directory call. The tenant owner sees OWNER; a seeded plain member sees MEMBER.
        val (owner, domain) = seedMember()
        val memberId = seedTenantMember(tenantIdOf(domain), "partner")

        given().header("Authorization", bearer(owner))
            .header("X-Forwarded-Host", domain)
            .`when`().get("/api/scopes")
            .then().statusCode(200)
            .body("find { it.kind == 'TENANT' }.role", equalTo("OWNER"))

        given().header("Authorization", bearer(memberId))
            .header("X-Forwarded-Host", domain)
            .`when`().get("/api/scopes")
            .then().statusCode(200)
            .body("find { it.kind == 'TENANT' }.role", equalTo("MEMBER"))
    }

    @Test
    fun secondPersonalScopeIs409() {
        val (userId, domain) = seedMember()
        given().header("Authorization", bearer(userId))
            .header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"scopeId":"${TestIds.uuid()}","wrappedGrant":"$personalGrant",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"${TestGrants.FIXTURE_DIGEST}"}""")
            .`when`().post("/api/scopes").then().statusCode(201)

        given().header("Authorization", bearer(userId))
            .header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"scopeId":"${TestIds.uuid()}","wrappedGrant":"$personalGrant",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"${TestGrants.FIXTURE_DIGEST}"}""")
            .`when`().post("/api/scopes")
            .then().statusCode(409).body("code", equalTo("personal_scope_exists"))
    }

    @Test
    fun createGroupThenListShowsItWithSealedNameAndSelfGrant() {
        val (userId, domain) = seedMember()
        val scopeId = given().header("Authorization", bearer(userId))
            .header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"scopeId":"${TestIds.uuid()}","encryptedName":"$groupName","wrappedGrant":"$groupGrant",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"${TestGrants.FIXTURE_DIGEST}"}""")
            .`when`().post("/api/scopes/groups")
            .then().statusCode(201)
            .body("kind", equalTo("GROUP"))
            .body("scopeId", notNullValue())
            .extract().path<String>("scopeId")

        // The list now carries the GROUP scope with the verbatim ciphertext name + the caller's self-grant —
        // both are exactly what the client sent (operator-blind: the server stored, never interpreted, them).
        given().header("Authorization", bearer(userId))
            .header("X-Forwarded-Host", domain)
            .`when`().get("/api/scopes")
            .then().statusCode(200)
            .body("kind", hasItems("TENANT", "GROUP"))
            .body("find { it.id == '$scopeId' }.encryptedName", equalTo(groupName))
            .body("find { it.id == '$scopeId' }.wrappedGrant", equalTo(groupGrant))
            // TENANT/PERSONAL scopes carry no sealed name.
            .body("find { it.kind == 'TENANT' }.encryptedName", equalTo(null))
    }

    @Test
    fun createGroupNonMemberIs404() {
        // A tenant exists, but this caller holds no membership in it → cannot create a group there.
        val (_, domain) = seedMember()
        val outsider = seedUser()
        given().header("Authorization", bearer(outsider))
            .header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"scopeId":"${TestIds.uuid()}","encryptedName":"$groupName","wrappedGrant":"$groupGrant",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"${TestGrants.FIXTURE_DIGEST}"}""")
            .`when`().post("/api/scopes/groups")
            .then().statusCode(404).body("code", equalTo("tenant_not_found"))
    }

    @Test
    fun addGroupMemberByUsernameRecordsAGrantedMember() {
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)
        val partner = seedTenantMember(tenantIdOf(domain), "partner")

        // The owner adds an existing tenant member by username → a MEMBER row holding the grant the owner's client
        // wrapped to their key (ADR 0111 decision 4); the response never exposes email (operator-blind: ciphertext only).
        given().header("Authorization", bearer(owner))
            .header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"username":"partner",${addJson(groupId, "partner")}}""")
            .`when`().post("/api/scopes/$groupId/members")
            .then().statusCode(201)
            .body("username", equalTo("partner"))
            .body("role", equalTo("MEMBER"))
            .body("hasGrant", nullValue())
            .body("email", nullValue())
        QuarkusTransaction.requiringNew().run {
            val m = MembershipEntity.find("userId = ?1 and scopeId = ?2", partner, groupId).firstResult()!!
            assertEquals(TestGrants.WRAPPED to owner, m.wrappedGrant to m.granterUserId)
        }

        // The group directory now lists the member.
        given().header("Authorization", bearer(owner))
            .`when`().get("/api/scopes/$groupId/members")
            .then().statusCode(200)
            .body("find { it.username == 'partner' }.role", equalTo("MEMBER"))
    }

    @Test
    fun addGroupMemberByANonMemberOfTheGroupIs404() {
        // A fellow *tenant* member who does not belong to the group cannot admit anyone — and gets 404, the same
        // answer as for a group that does not exist. Belonging to the tenant does not entitle you to learn which
        // groups exist inside it (membership is the first guard, ADR 0042 / cr-0162).
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)
        val nonMember = seedTenantMember(tenantIdOf(domain), "partner")
        seedTenantMember(tenantIdOf(domain), "third")
        given().header("Authorization", bearer(nonMember))
            .header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"username":"third",${addJson(groupId, "third")}}""")
            .`when`().post("/api/scopes/$groupId/members")
            .then().statusCode(404).body("code", equalTo("scope_not_found"))
    }

    @Test
    fun addGroupMemberUnknownUsernameIs404() {
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)
        given().header("Authorization", bearer(owner))
            .header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"username":"nobody",${addJson(groupId, "nobody")}}""")
            .`when`().post("/api/scopes/$groupId/members")
            .then().statusCode(404).body("code", equalTo("member_not_found"))
    }

    @Test
    fun addGroupMemberAlreadyMemberIs409() {
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)
        seedTenantMember(tenantIdOf(domain), "partner")
        val add = {
            given().header("Authorization", bearer(owner))
                .header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
                .body("""{"username":"partner",${addJson(groupId, "partner")}}""").`when`().post("/api/scopes/$groupId/members")
        }
        add().then().statusCode(201)
        add().then().statusCode(409).body("code", equalTo("already_member"))
    }

    @Test
    fun unknownHostIs404() {
        val (userId, _) = seedMember()
        given().header("Authorization", bearer(userId))
            .header("X-Forwarded-Host", "nobody-${UUID.randomUUID()}.example.com").contentType(ContentType.JSON)
            .body("""{"scopeId":"${TestIds.uuid()}","wrappedGrant":"$personalGrant",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"${TestGrants.FIXTURE_DIGEST}"}""")
            .`when`().post("/api/scopes")
            .then().statusCode(404).body("code", equalTo("tenant_not_found"))
    }

    @Test
    fun nonMemberIs404() {
        // A tenant exists, but this caller holds no membership in it → cannot birth a personal scope there.
        val (_, domain) = seedMember()
        val outsider = seedUser()
        given().header("Authorization", bearer(outsider))
            .header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"scopeId":"${TestIds.uuid()}","wrappedGrant":"$personalGrant",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"${TestGrants.FIXTURE_DIGEST}"}""")
            .`when`().post("/api/scopes")
            .then().statusCode(404).body("code", equalTo("tenant_not_found"))
    }

    @Test
    fun unauthenticatedIs401() {
        given().header("X-Forwarded-Host", "x-${UUID.randomUUID()}.example.com").contentType(ContentType.JSON)
            .body("""{"scopeId":"${TestIds.uuid()}","wrappedGrant":"$personalGrant",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"${TestGrants.FIXTURE_DIGEST}"}""")
            .`when`().post("/api/scopes")
            .then().statusCode(401).body("code", equalTo("authentication_failed"))
    }

    @Test
    fun listMyTenantsReturnsTheCallersTenants() {
        // Host-independent: lists every tenant the caller belongs to (name + domain + role), for the switcher.
        val (userId, domain) = seedMember()
        given().header("Authorization", bearer(userId))
            .`when`().get("/api/scopes/my-tenants")
            .then().statusCode(200)
            .body("size()", equalTo(1))
            .body("[0].name", equalTo("Fam"))
            .body("[0].domain", equalTo(domain))
            .body("[0].role", equalTo("OWNER"))

        // an outsider with no membership sees an empty list — only your own tenants (operator-blind).
        val outsider = seedUser()
        given().header("Authorization", bearer(outsider))
            .`when`().get("/api/scopes/my-tenants")
            .then().statusCode(200).body("size()", equalTo(0))
    }

    @Test
    fun myTenantsRoleKeysOnRootScopeNotAPersonalOwnerRow() {
        // A plain member also OWNS their personal scope, whose membership carries the SAME tenantId. Grouping by
        // tenantId then taking `.first().role` could surface that OWNER row → a MEMBER shown as OWNER (cr-0163's
        // shape, in the switcher). The role must key on the tenant ROOT scope (scopeId == tenantId). #93.
        val (_, domain) = seedMember()
        val tenantId = domain.removePrefix("t-").removeSuffix(".example.com")
        val member = seedUser()
        QuarkusTransaction.requiringNew().run {
            // Personal-scope OWNER row FIRST — under H2's insertion scan order this is what a buggy `.first()`
            // returns, so the assertion below bites the pre-fix code deterministically.
            val personalId = UUID.randomUUID().toString()
            ScopeEntity().apply {
                id = personalId
                this.tenantId = tenantId
                kind = ScopeEntity.PERSONAL
            }.persist()
            MembershipEntity().apply {
                id = UUID.randomUUID().toString()
                this.userId = member
                this.tenantId = tenantId
                scopeId = personalId
                role = MembershipEntity.OWNER
                wrappedGrant = "cGVyc29uYWwtZ3JhbnQ=" // "personal-grant"
                createdAt = Instant.now()
            }.persist()
            // Then the tenant ROOT MEMBER row (scopeId == tenantId) — the one the role must come from.
            MembershipEntity().apply {
                id = UUID.randomUUID().toString()
                this.userId = member
                this.tenantId = tenantId
                scopeId = tenantId
                role = MembershipEntity.MEMBER
                wrappedGrant = "bWVtYmVyLWdyYW50" // "member-grant"
                createdAt = Instant.now()
            }.persist()
        }
        given().header("Authorization", bearer(member))
            .`when`().get("/api/scopes/my-tenants")
            .then().statusCode(200)
            .body("size()", equalTo(1))
            .body("[0].role", equalTo("MEMBER"))
    }

    @Test
    fun deleteGroupSoftThenRestoreWithReadOnlyGrace() {
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)
        createNodeIn(owner, groupId, "n1") // a write, allowed while active

        // Owner soft-deletes → 204; the scope stays listed but now carries deletedAt + purgeAfter (pending-deletion).
        given().header("Authorization", bearer(owner))
            .`when`().delete("/api/scopes/$groupId").then().statusCode(204)
        given().header("Authorization", bearer(owner)).header("X-Forwarded-Host", domain)
            .`when`().get("/api/scopes").then().statusCode(200)
            .body("find { it.id == '$groupId' }.deletedAt", notNullValue())
            .body("find { it.id == '$groupId' }.purgeAfter", notNullValue())

        // Read-only during grace: a content write is rejected 409 scope_deleting.
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body(nodeBody("n2")).`when`().post("/api/scopes/$groupId/nodes")
            .then().statusCode(409).body("code", equalTo("scope_deleting"))

        // Restore → 204; the marker clears and writes work again.
        given().header("Authorization", bearer(owner))
            .`when`().post("/api/scopes/$groupId/restore").then().statusCode(204)
        given().header("Authorization", bearer(owner)).header("X-Forwarded-Host", domain)
            .`when`().get("/api/scopes").then().statusCode(200)
            .body("find { it.id == '$groupId' }.deletedAt", equalTo(null))
        createNodeIn(owner, groupId, "n3") // write allowed again
    }

    @Test
    fun deleteRestoreGuards() {
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)
        val partner = seedTenantMember(tenantIdOf(domain), "partner")
        addToGroup(owner, domain, groupId, "partner") // partner = MEMBER

        // A non-owner cannot delete; restoring an active group is 409 not_deleted; deleting the TENANT scope is 409 not_group.
        given().header("Authorization", bearer(partner))
            .`when`().delete("/api/scopes/$groupId").then().statusCode(403).body("code", equalTo("not_scope_owner"))
        given().header("Authorization", bearer(owner))
            .`when`().post("/api/scopes/$groupId/restore").then().statusCode(409).body("code", equalTo("not_deleted"))
        given().header("Authorization", bearer(owner))
            .`when`().delete("/api/scopes/${tenantIdOf(domain)}").then().statusCode(409).body("code", equalTo("not_group"))

        // Soft-delete, then a second delete → 409 scope_deleting: a deleting group refuses the delete like any write.
        given().header("Authorization", bearer(owner)).`when`().delete("/api/scopes/$groupId").then().statusCode(204)
        given().header("Authorization", bearer(owner))
            .`when`().delete("/api/scopes/$groupId").then().statusCode(409).body("code", equalTo("scope_deleting"))
    }

    @Test
    fun purgeErasesExpiredScopeAndContentButKeepsFreshOnes() {
        val (owner, domain) = seedMember()
        val expired = createGroupAs(owner, domain)
        createNodeIn(owner, expired, "doomed")
        val fresh = createGroupAs(owner, domain)

        // Soft-delete both; backdate the "expired" one past the 30-day retention window.
        listOf(expired, fresh).forEach { given().header("Authorization", bearer(owner)).`when`().delete("/api/scopes/$it").then().statusCode(204) }
        QuarkusTransaction.requiringNew().run {
            ScopeEntity.findById(expired)!!.apply { deletedAt = Instant.now().minus(Duration.ofDays(31)) }.persist()
        }

        // The scheduled sweep hard-erases the expired group + its content; the fresh (in-window) one survives.
        purge.run()

        QuarkusTransaction.requiringNew().run {
            assertNull(ScopeEntity.findById(expired), "expired group hard-purged")
            assertEquals(0, NodeEntity.count("scopeId", expired), "its nodes erased")
            assertEquals(0, MembershipEntity.count("scopeId", expired), "its memberships erased")
            assertNotNull(ScopeEntity.findById(fresh), "fresh group still in its grace window")
        }
    }

    /**
     * The job arm of uc.spaces.delete-group's total erasure, and the one that was **live**: a group that had ever
     * enqueued a background job could not be erased at all. `veyq_job.scope_id` references `veyq_scope(id)` with
     * no `ON DELETE` clause (`fk_job_scope`) and nothing anywhere deleted a job row, so
     * `eraseScope`'s final `ScopeEntity.deleteById` raised `FK_JOB_SCOPE` — on every scheduled tick, forever,
     * with the group's bytes never reclaimed on the operator's own disk. That is what this reddened on before the
     * fix, which is why it is written as a group that *ever ran* a job rather than as a row count.
     *
     * `clean` is the positive control on the same predicate: without it a red is equally produced by a purge that
     * is simply broken. It also measured the blast radius, which was worse than the defect report assumed —
     * before the tick was isolated, `run()` wrapped the whole sweep in one transaction and this one scope took
     * `clean` down with it, so a single stuck group halted all reclamation.
     *
     * **The job is DECLINED, so one test covers the whole `veyq_job` -> `veyq_job_decline` chain and cannot go
     * green on half a fix.** Deleting jobs without their declines only moves the violation one table down — the
     * correction the closure forced on a defect report that had asked one-hop and answered two where the answer
     * is four. The decline goes through the real claim-then-decline path rather than a hand-written row, so the
     * arrangement is a thing the product actually produces.
     */
    @Test
    fun purgeErasesAGroupThatEverEnqueuedAJob() {
        val (owner, domain) = seedMember()
        val poisoned = createGroupAs(owner, domain)
        val clean = createGroupAs(owner, domain)

        // The real enqueue path (the member-removal rotation backstop, cr-0092) — not a hand-written row.
        assertTrue(tasks.originateScopeRotation(poisoned, 3), "a rotation job was enqueued on the group")
        val claimed = tasks.claim(owner, mapOf(CapabilityTags.SCOPE_ROTATION to setOf(poisoned)), Duration.ofMinutes(5), 3)
        assertNotNull(claimed, "the owner holds a grant on the group, so the rotation job is claimable")
        tasks.decline(owner, claimed!!.jobId, "bite-test")
        QuarkusTransaction.requiringNew().run {
            assertEquals(1, JobDeclineEntity.count("jobId", claimed.jobId), "the decline row landed")
        }

        listOf(poisoned, clean).forEach {
            given().header("Authorization", bearer(owner)).`when`().delete("/api/scopes/$it").then().statusCode(204)
        }
        QuarkusTransaction.requiringNew().run {
            listOf(poisoned, clean).forEach {
                ScopeEntity.findById(it)!!.apply { deletedAt = Instant.now().minus(Duration.ofDays(31)) }.persist()
            }
        }

        try {
            purge.run()

            QuarkusTransaction.requiringNew().run {
                assertNull(ScopeEntity.findById(poisoned), "the group that ran a job is hard-purged")
                assertEquals(0, JobEntity.count("scopeId", poisoned), "its job rows are erased with it")
                assertEquals(0, JobDeclineEntity.count("jobId", claimed.jobId), "and the declines under them")
                assertNull(ScopeEntity.findById(clean), "a co-purgeable group is not taken down with it")
            }
        } finally {
            // Leave the JVM-shared H2 DB clean, and keep it that way when this test is next made to bite: with
            // the fix reverted the sweep rolls back, `poisoned` survives as still-purgeable, and every later
            // `purge.run()` in this JVM dies on it — which took the neighbouring
            // purgeErasesExpiredScopeAndContentButKeepsFreshOnes down with it on the very first run. A bite-test
            // must name itself, not redden its neighbours.
            QuarkusTransaction.requiringNew().run {
                JobDeclineEntity.delete("jobId", claimed.jobId)
                JobEntity.delete("scopeId", poisoned)
            }
            purge.purge(poisoned)
        }
    }

    /**
     * BITE-TEST for uc.spaces.delete-group's *"one scope that cannot be erased must not stop the others"* — a
     * **live defect** on the untouched tree, where `run()` is `@Transactional` over the whole sweep: the first
     * scope whose erasure throws takes the transaction down and every other purgeable scope in that tick with
     * it, permanently and on every later tick.
     *
     * **The lever is a refused reclaim INSERT, and it is the second lever this case has had.** Once the closure
     * below `veyq_scope` is complete, no *schema* can poison a scope any more — `validate_scope_erasure_arity`
     * exists to keep that true — so a bite levered on the FK stopped biting the moment the fix beneath it landed.
     * The lever then became a refusing object store, because `eraseScope` deleted objects first, inside the
     * transaction. ADR 0062 Decision 4 removed that call too: the purge now records key prefixes and
     * `StoreReclaimSweep` deletes them. The property outlived both levers, which is the lesson worth keeping —
     * so the injection follows the fallible work rather than the other way round, and what `eraseScope` still
     * does in its own transaction is the reclaim write. [RefusingStoreReclaimStore] makes that write refuse, and
     * it lands before any row of `poisoned` is touched, exactly as the store refusal used to.
     *
     * `clean` is the assertion and `poisoned` is its control: without the second, a purge that simply erases
     * everything regardless would pass, and without the first the defect is invisible.
     *
     * **`clean` also carries a second property, added by cr-scope-purge-reclaim-assertion**: that its own
     * successful erasure records a reclaim row for the blob it deletes (ADR 0062 Decision 1 — no other test
     * exercised the success arm). It shares this test rather than getting its own, since `clean` already runs a
     * full `purge.run()` here; the reclaim assertion is ordered **first** in the block below because it shares
     * the same lever as the isolation asserts (both live and die by the one `reclaim.record` call in
     * `eraseScope`) — reading it after them would let a real regression report on their line instead of its own.
     */
    @Test
    fun purgeIsolatesAScopeItCannotEraseFromTheRestOfTheTick() {
        val (owner, domain) = seedMember()
        val poisoned = createGroupAs(owner, domain)
        val clean = createGroupAs(owner, domain)
        val marker = "refuse-${UUID.randomUUID()}"

        // The one blob `eraseScope` will record for reclaim for `poisoned` — its storage key carries the marker.
        QuarkusTransaction.requiringNew().run {
            BlobEntity().apply {
                id = UUID.randomUUID().toString(); scopeId = poisoned; storageKey = marker
                chunkCount = TestBlob.SMALL.chunkCount; byteSize = TestBlob.SMALL.byteSize; wrappedBlobKey = "Ymxvyi13cmFw"
            }.persist()
        }
        // `clean`'s own blob — unmarked, so the refusing store passes it straight to the real `record`. Proves
        // the success arm this test also exercises actually writes a reclaim row, not just that it isn't blocked.
        val cleanKey = "clean-${UUID.randomUUID()}"
        QuarkusTransaction.requiringNew().run {
            BlobEntity().apply {
                id = UUID.randomUUID().toString(); scopeId = clean; storageKey = cleanKey
                chunkCount = TestBlob.SMALL.chunkCount; byteSize = TestBlob.SMALL.byteSize; wrappedBlobKey = "Y2xlYW4="
            }.persist()
        }
        listOf(poisoned, clean).forEach {
            given().header("Authorization", bearer(owner)).`when`().delete("/api/scopes/$it").then().statusCode(204)
        }
        QuarkusTransaction.requiringNew().run {
            listOf(poisoned, clean).forEach {
                ScopeEntity.findById(it)!!.apply { deletedAt = Instant.now().minus(Duration.ofDays(31)) }.persist()
            }
        }

        val recorder = reclaimStore as RefusingStoreReclaimStore
        recorder.failOnKeyMarker = marker
        try {
            purge.run() // must not throw: a failure is caught, logged and confined to its own scope

            QuarkusTransaction.requiringNew().run {
                // First, so a bite on the shared `record` call reports THIS line rather than being masked by the
                // isolation asserts below (poisoned's isolation depends on the very same call throwing).
                assert(StoreReclaimEntity.count("storageKey", cleanKey) == 1L) {
                    "a scope the sweep can erase must leave a durable reclaim row for every blob it deleted"
                }
                assertNull(ScopeEntity.findById(clean), "a scope the sweep can erase is erased in the same tick")
                assertNotNull(ScopeEntity.findById(poisoned), "the scope whose erasure threw keeps all its rows")
            }
        } finally {
            // Restore the shared singleton in the same edit that mutated it, then leave the JVM-shared H2 clean:
            // `poisoned` is still purgeable and would otherwise meet every later sweep in this JVM.
            recorder.failOnKeyMarker = null
            QuarkusTransaction.requiringNew().run { BlobEntity.delete("scopeId", poisoned) }
            listOf(poisoned, clean).forEach { purge.purge(it) }
        }
    }

    /**
     * BITE-TEST for the share arm of the same closure — a **live defect on a seeded arm**, and the seeding is the
     * honest part rather than a shortcut. `veyq_share.scope_id` references `veyq_scope(id)` with no `ON DELETE`
     * (`fk_share_scope`) and `veyq_share_identity.share_id` references the share (`fk_share_identity_share`),
     * so both refuse the scope row exactly as the job tables did. The rows are seeded rather than made through the
     * API because what this pins is the *schema* requirement: a purge that never learned to delete a share would
     * silently stop erasing every group that ever had a link (ADR 0029 §6). Erasing the share is also what
     * operator-blindness asks for — the row holds the owner, the node and a sealed name belonging to a group whose
     * erasure the owner requested, and there is no capability left to revoke.
     */
    @Test
    fun purgeErasesAGroupThatCarriesAShare() {
        val (owner, domain) = seedMember()
        val scopeId = createGroupAs(owner, domain)
        val nodeId = createNodeIn(owner, scopeId, "shared-memory")
        val shareId = UUID.randomUUID().toString()

        QuarkusTransaction.requiringNew().run {
            ShareEntity().apply {
                id = shareId; ownerAccount = owner; this.scopeId = scopeId; this.nodeId = nodeId
                role = ShareEntity.READ_WRITE; wrap = "c2hhcmUtd3JhcA=="; sealedName = "c2VhbGVkLW1lbW9yeQ=="
                createdAt = Instant.now()
            }.persist()
            ShareIdentityEntity().apply {
                id = UUID.randomUUID().toString(); this.shareId = shareId
                sealedDisplayName = "c2VhbGVkLW5hbWU="; tokenHash = UUID.randomUUID().toString().replace("-", "")
            }.persist()
        }

        given().header("Authorization", bearer(owner)).`when`().delete("/api/scopes/$scopeId").then().statusCode(204)
        QuarkusTransaction.requiringNew().run {
            ScopeEntity.findById(scopeId)!!.apply { deletedAt = Instant.now().minus(Duration.ofDays(31)) }.persist()
        }

        try {
            purge.run()

            QuarkusTransaction.requiringNew().run {
                assertNull(ScopeEntity.findById(scopeId), "the group that carries a share is hard-purged")
                assertEquals(0, ShareEntity.count("scopeId", scopeId), "its shares are erased with it")
                assertEquals(0, ShareIdentityEntity.count("shareId", shareId), "and the identities under them")
            }
        } finally {
            // Same shared-JVM hygiene as the job bite: while the defect is live the scope survives as purgeable
            // and would meet every later sweep in this JVM.
            QuarkusTransaction.requiringNew().run {
                ShareIdentityEntity.delete("shareId", shareId)
                ShareEntity.delete("scopeId", scopeId)
            }
            purge.purge(scopeId)
        }
    }

    /**
     * BITE-TEST for the upload arm of the same erasure — a **live defect**: `eraseScope` deleted the scope's OPEN
     * upload sessions without recording the chunks their clients may already have PUT straight into the store,
     * and the session row is the only pointer to that `storage_key` (ADR 0062 § Context), so those bytes were
     * orphaned for the life of the bucket. The `veyq.blob.upload.retention` sweep does not close it: a session
     * younger than that window when the purge tick lands is still open. Refusing uploads during the grace
     * narrows it to an operator whose scope retention is shorter than the upload one — erasure must not depend on
     * how two independent windows compare.
     *
     * The session goes through [BlobUploadStore.createSession], the persistence half of the real upload `start`. The
     * reclaim row must carry the session's declared `chunkCount`, not only its key: the sweep deletes indices
     * `0 until chunkCount`, so a row recording one chunk reclaims the first and orphans the rest.
     *
     * `fresh` is the control on the same predicate: a purge that recorded every open session it could see would
     * satisfy the positive half perfectly — and hand another group's live upload to the deleter.
     */
    @Test
    fun purgeReclaimsTheStagedObjectsOfAnUploadStillOpenInTheScope() {
        val (owner, domain) = seedMember()
        val expired = createGroupAs(owner, domain)
        val fresh = createGroupAs(owner, domain)
        val expiredKey = UUID.randomUUID().toString()
        val freshKey = UUID.randomUUID().toString()
        val blob3 = TestBlob.ofChunks(3)
        val expiredSession = blobStore.createSession(expired, UploadOpener.Member(owner), expiredKey, blob3.upload("d3JhcA=="))
        val blob2 = TestBlob.ofChunks(2)
        val freshSession = blobStore.createSession(fresh, UploadOpener.Member(owner), freshKey, blob2.upload("d3JhcA=="))

        listOf(expired, fresh).forEach {
            given().header("Authorization", bearer(owner)).`when`().delete("/api/scopes/$it").then().statusCode(204)
        }
        QuarkusTransaction.requiringNew().run {
            ScopeEntity.findById(expired)!!.apply { deletedAt = Instant.now().minus(Duration.ofDays(31)) }.persist()
        }

        purge.run()

        QuarkusTransaction.requiringNew().run {
            assertNull(ScopeEntity.findById(expired), "the group with an open upload is hard-purged")
            assertNull(UploadSessionEntity.findById(expiredSession), "its open session row is erased with it")
            assertEquals(
                listOf(3), StoreReclaimEntity.list("storageKey", expiredKey).map { it.chunkCount },
                "an erased session's staged objects go on the reclaim list — one row, every declared chunk",
            )
            assertNotNull(UploadSessionEntity.findById(freshSession), "an upload in a group still in its grace is left alone")
            assertEquals(0, StoreReclaimEntity.count("storageKey", freshKey), "and none of its objects is reclaimed")
        }
    }

    // --- helpers ---

    /** A minimal FOLDER-node create body; `slug` becomes a base64 slug token (unique per parent). */
    private fun nodeBody(slug: String) =
        """{"nodeId":"${UUID.randomUUID()}","kind":"FOLDER","encryptedName":"$groupName","slugToken":"${Base64.getEncoder().encodeToString(slug.toByteArray())}","orderIndex":0,"wrappedContentKey":"a21lbQ=="}"""

    /** Create a FOLDER node in a scope (a write) → the new nodeId. */
    private fun createNodeIn(owner: String, scopeId: String, slug: String): String =
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body(nodeBody(slug)).`when`().post("/api/scopes/$scopeId/nodes")
            .then().statusCode(201).extract().path("nodeId")

    /** The tenantId encoded in a domain seeded by [seedMember] (`t-<tenantId>.example.com`). */
    private fun tenantIdOf(domain: String) = domain.removePrefix("t-").removeSuffix(".example.com")

    /** Create a GROUP scope as the caller (the seeded name/grant are opaque here) → the new scopeId. */
    private fun createGroupAs(userId: String, domain: String): String =
        given().header("Authorization", bearer(userId))
            .header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"scopeId":"${TestIds.uuid()}","encryptedName":"$groupName","wrappedGrant":"$groupGrant",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"${TestGrants.FIXTURE_DIGEST}"}""")
            .`when`().post("/api/scopes/groups")
            .then().statusCode(201).extract().path("scopeId")

    /** Seed a second tenant member with a per-tenant username profile (the add-by-username target). */
    private fun seedTenantMember(tenantId: String, username: String): String {
        val userId = seedUser()
        QuarkusTransaction.requiringNew().run {
            MembershipEntity().apply {
                id = UUID.randomUUID().toString()
                this.userId = userId
                this.tenantId = tenantId
                scopeId = tenantId
                role = MembershipEntity.MEMBER
                wrappedGrant = "bWVtYmVyLWdyYW50" // "member-grant"
                createdAt = Instant.now()
            }.persist()
            TenantProfileEntity().apply {
                id = UUID.randomUUID().toString()
                this.userId = userId
                this.tenantId = tenantId
                this.username = username
            }.persist()
        }
        return userId
    }

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
