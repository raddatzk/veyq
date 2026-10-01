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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Slice tests for shared-scope public shares (uc.sharing.share-shared-scope, cr-0104, ADR 0029 §6): any member of
 * a GROUP or TENANT scope may public-share a Memory there (lifting the personal-scope-only gate); removing a
 * member soft-revokes the shares that member created **in that scope** (their links then 404), leaving other
 * members' shares untouched; any remaining member may re-activate a deactivated share, which **transfers its
 * ownership** — unless a departed member may hold its link, which is then only re-issued (ADR 0075: a new id and
 * key, the old one off, one transaction), and a removal can opt to turn off every link of the group. `K_mem` is
 * untouched — deactivation is the honest-server revocation layer. Distinct domains keep the JVM-lived H2 mem DB
 * from bleeding state between tests.
 */
@QuarkusTest
class ShareSharedScopeSliceTest {

    private val wrap = "d3JhcC1rLW1lbS11bmRlci1zaGFyZQ=="
    private val sealedName = "c2VhbGVkLW5hbWU="
    private val shareKey = "cmVjb3ZlcmFibGUtc2hhcmUta2V5"
    private val newWrap = "bmV3LXdyYXAtdW5kZXItbmV3LWstc2hhcmU="
    private val dropPublic = "c2VhbGVkLWRyb3AtcHVibGljLWtleQ=="
    private val dropPrivate = "c2VhbGVkLWRyb3AtcHJpdmF0ZS1rZXk="

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

    /** ADR 0075, the owner-membership half: a removed member's own link is never revived. Its share carries no
     *  recovery copy, so only this half can refuse it. */
    @Test
    fun reactivatingARemovedMembersShareIs409ReissueRequired() {
        val (tenantId, owner) = seedActiveTenant("g-revive.example.com")
        val member = seedTenantMember(tenantId, "m@g-revive.example.com")
        val group = seedGroup(tenantId, owner)
        addMember(tenantId, group, member)
        val shareId = createShare(member, seedNode(group, NodeEntity.MEMORY, parentId = group, wrappedContentKey = CONTENT_KEY))
        removeByRotation(owner, group, member).then().statusCode(201)
        given().`when`().get("/api/shares/$shareId").then().statusCode(404)

        given().header("Authorization", bearer(owner)).`when`().post("/api/shares/$shareId/reactivate")
            .then().statusCode(409).body("code", equalTo("reissue_required"))
        given().`when`().get("/api/shares/$shareId").then().statusCode(404)
    }

    /** ADR 0075, the epoch half: a co-member who is still in the group self-revoked a share whose recovery copy is
     *  sealed at epoch 0; a removal moved the group to epoch 1, so the removed member could have rebuilt the link. */
    @Test
    fun reactivatingAShareSealedBeforeTheKeyChangedIs409ReissueRequired() {
        val (tenantId, owner) = seedActiveTenant("g-epoch.example.com")
        val member = seedTenantMember(tenantId, "m@g-epoch.example.com")
        val coMember = seedTenantMember(tenantId, "c@g-epoch.example.com")
        val group = seedGroup(tenantId, owner)
        addMember(tenantId, group, member)
        addMember(tenantId, group, coMember)
        val shareId = createShare(coMember, seedNode(group, NodeEntity.MEMORY, parentId = group, wrappedContentKey = CONTENT_KEY), copyEpoch = 0)
        given().header("Authorization", bearer(coMember)).`when`().post("/api/shares/$shareId/revoke").then().statusCode(204)
        removeByRotation(owner, group, member).then().statusCode(201)

        given().header("Authorization", bearer(coMember)).`when`().post("/api/shares/$shareId/reactivate")
            .then().statusCode(409).body("code", equalTo("reissue_required"))
        given().`when`().get("/api/shares/$shareId").then().statusCode(404)
    }

    /** The positive control on the same predicate: a share minted at the current epoch by a current member. */
    @Test
    fun reactivatingOwnShareSealedAtTheCurrentEpochResolves() {
        val (tenantId, owner) = seedActiveTenant("g-epoch-ok.example.com")
        val group = seedGroup(tenantId, owner)
        val shareId = createShare(owner, seedNode(group, NodeEntity.MEMORY, parentId = group, wrappedContentKey = CONTENT_KEY), copyEpoch = 0)
        given().header("Authorization", bearer(owner)).`when`().post("/api/shares/$shareId/revoke").then().statusCode(204)

        given().header("Authorization", bearer(owner)).`when`().post("/api/shares/$shareId/reactivate").then().statusCode(204)
        given().`when`().get("/api/shares/$shareId").then().statusCode(200)
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

    // --- re-issue (ADR 0075, B2) ---

    @Test
    fun reissueReplacesTheLinkInOneRequestAndKeepsTheRoleAndMemory() {
        val (tenantId, owner) = seedActiveTenant("g-reissue.example.com")
        val group = seedGroup(tenantId, owner)
        val memory = seedNode(group, NodeEntity.MEMORY, parentId = group, wrappedContentKey = CONTENT_KEY)
        val old = createShare(owner, memory, copyEpoch = 0, role = "CONTRIBUTOR")
        val newId = TestIds.shareId()

        reissue(owner, old, epoch = 0, newId = newId).then().statusCode(201).body("shareId", equalTo(newId))

        resolves(newId, 200).body("role", equalTo("CONTRIBUTOR")).body("wrap", equalTo(newWrap))
        resolves(old, 404).body("code", equalTo("share_not_found"))
        given().header("Authorization", bearer(owner)).`when`().get("/api/shares?nodeId=$memory")
            .then().statusCode(200)
            .body("find { it.shareId == '$newId' }.nodeId", equalTo(memory))
            .body("find { it.shareId == '$newId' }.mine", equalTo(true))
            .body("find { it.shareId == '$newId' }.sealedShareKeyEpoch", equalTo(0))
            .body("find { it.shareId == '$old' }.enabled", equalTo(false))
    }

    @Test
    fun reissueAtAStaleEpochIs409AndWritesNothing() {
        val (tenantId, owner) = seedActiveTenant("g-reissue-stale.example.com")
        val group = seedGroup(tenantId, owner)
        removeByRotation(owner, group, member = null).then().statusCode(201) // the group is at epoch 1 now
        val old = createShare(owner, seedNode(group, NodeEntity.MEMORY, parentId = group, wrappedContentKey = CONTENT_KEY), copyEpoch = 1)
        val newId = TestIds.shareId()

        reissue(owner, old, epoch = 0, newId = newId).then().statusCode(409).body("code", equalTo("stale_epoch"))
        resolves(old, 200)
        resolves(newId, 404)

        // control: the same request at the current epoch goes through
        reissue(owner, old, epoch = 1, newId = newId).then().statusCode(201)
        resolves(newId, 200)
        resolves(old, 404)
    }

    @Test
    fun aCoMembersEnabledShareIsNotReissuableButADisabledOneIsAdopted() {
        val (tenantId, owner) = seedActiveTenant("g-reissue-adopt.example.com")
        val member = seedTenantMember(tenantId, "m@g-reissue-adopt.example.com")
        val group = seedGroup(tenantId, owner)
        addMember(tenantId, group, member)
        val memory = seedNode(group, NodeEntity.MEMORY, parentId = group, wrappedContentKey = CONTENT_KEY)
        val old = createShare(member, memory, copyEpoch = 0)

        reissue(owner, old, epoch = 0).then().statusCode(404).body("code", equalTo("share_not_found"))
        resolves(old, 200)

        given().header("Authorization", bearer(member)).`when`().post("/api/shares/$old/revoke").then().statusCode(204)
        val newId = TestIds.shareId()
        reissue(owner, old, epoch = 0, newId = newId).then().statusCode(201)
        given().header("Authorization", bearer(owner)).`when`().get("/api/shares?nodeId=$memory")
            .then().statusCode(200).body("find { it.shareId == '$newId' }.mine", equalTo(true))
    }

    @Test
    fun anUploaderReissueSealsTheExistingDropKeyAndNeverAWrap() {
        val (tenantId, owner) = seedActiveTenant("g-reissue-drop.example.com")
        val group = seedGroup(tenantId, owner)
        val memory = seedNode(group, NodeEntity.MEMORY, parentId = group, wrappedContentKey = CONTENT_KEY)
        val old = createShare(
            owner, memory, copyEpoch = 0, role = "UPLOADER",
            keys = """"sealedDropPublicKey":"$dropPublic","sealedDropPrivateKey":"$dropPrivate"""",
        )

        reissue(owner, old, epoch = 0).then().statusCode(400).body("code", equalTo("invalid_request")) // a wrap, no drop key
        reissue(owner, old, epoch = 0, keys = """"wrap":"$newWrap","sealedDropPublicKey":"$dropPublic"""")
            .then().statusCode(400).body("code", equalTo("invalid_request"))
        resolves(old, 200)

        val newId = TestIds.shareId()
        reissue(owner, old, epoch = 0, newId = newId, keys = """"sealedDropPublicKey":"$dropPublic"""").then().statusCode(201)
        resolves(newId, 200).body("role", equalTo("UPLOADER"))
        QuarkusTransaction.requiringNew().run { assertEquals(dropPrivate, NodeEntity.findById(memory)!!.sealedDropPrivateKey) }
    }

    /** The refusal that comes AFTER the old row was read: nothing of the re-issue may land, the old link least of all. */
    @Test
    fun aReissueIntoATakenIdIs409AndLeavesTheOldLinkOn() {
        val (tenantId, owner) = seedActiveTenant("g-reissue-taken.example.com")
        val group = seedGroup(tenantId, owner)
        val memory = seedNode(group, NodeEntity.MEMORY, parentId = group, wrappedContentKey = CONTENT_KEY)
        val old = createShare(owner, memory, copyEpoch = 0)
        val taken = createShare(owner, memory, copyEpoch = 0)

        reissue(owner, old, epoch = 0, newId = taken).then().statusCode(409).body("code", equalTo("id_taken"))
        resolves(old, 200)
        resolves(taken, 200).body("wrap", equalTo(wrap))
    }

    // --- a removal that turns off every link (ADR 0075, B3) ---

    @Test
    fun aRemovalWithRevokeLinksTurnsOffEveryLinkOfTheGroup() {
        val (tenantId, owner) = seedActiveTenant("g-revoke-links.example.com")
        val member = seedTenantMember(tenantId, "m@g-revoke-links.example.com")
        val coMember = seedTenantMember(tenantId, "c@g-revoke-links.example.com")
        val group = seedGroup(tenantId, owner)
        addMember(tenantId, group, member)
        addMember(tenantId, group, coMember)
        val memory = seedNode(group, NodeEntity.MEMORY, parentId = group, wrappedContentKey = CONTENT_KEY)
        val ownerShare = createShare(owner, memory)
        val coMemberShare = createShare(coMember, memory)
        val memberShare = createShare(member, memory)

        removeByRotation(owner, group, member, revokeLinks = true).then().statusCode(201)

        resolves(ownerShare, 404)
        resolves(coMemberShare, 404)
        resolves(memberShare, 404)
    }

    @Test
    fun revokeLinksWithoutARemovalIs400AndStoresNothing() {
        val (tenantId, owner) = seedActiveTenant("g-revoke-links-400.example.com")
        val group = seedGroup(tenantId, owner)
        val share = createShare(owner, seedNode(group, NodeEntity.MEMORY, parentId = group, wrappedContentKey = CONTENT_KEY))

        removeByRotation(owner, group, member = null, revokeLinks = true).then().statusCode(400).body("code", equalTo("invalid_request"))
        assertEquals(0, TestAdmission.epochOf(group))
        resolves(share, 200)
    }

    // --- the tenant half (ADR 0075, B4) ---

    /** A tenant removal never rotates (ADR 0025), so only the owner-membership half can refuse this. */
    @Test
    fun reactivatingARemovedTenantMembersTenantShareIs409ReissueRequired() {
        val (tenantId, owner) = seedActiveTenant("t-revive.example.com")
        val member = seedTenantMember(tenantId, "m@t-revive.example.com")
        val share = createShare(member, seedNode(tenantId, NodeEntity.MEMORY, parentId = tenantId, wrappedContentKey = CONTENT_KEY), copyEpoch = 0)
        given().header("Authorization", bearer(owner)).`when`().delete("/api/tenants/$tenantId/members/$member").then().statusCode(204)

        given().header("Authorization", bearer(owner)).`when`().post("/api/shares/$share/reactivate")
            .then().statusCode(409).body("code", equalTo("reissue_required"))
        resolves(share, 404)
        assertEquals(0, TestAdmission.epochOf(tenantId)) // the tenant epoch never moved
    }

    // --- helpers ---

    /** A share of [role] carrying [keys]; with [copyEpoch], also a recovery copy sealed at that epoch (ADR 0036
     *  point 4). */
    private fun createShare(
        userId: String, nodeId: String, copyEpoch: Int? = null, role: String = "VIEWER", keys: String = """"wrap":"$wrap"""",
    ): String {
        val copy = copyEpoch?.let { ""","sealedShareKey":"$shareKey","sealedShareKeyEpoch":$it""" } ?: ""
        return given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"shareId":"${TestIds.shareId()}","nodeId":"$nodeId","role":"$role",$keys,"sealedName":"$sealedName"$copy}""")
            .`when`().post("/api/shares")
            .then().statusCode(201).body("shareId", notNullValue())
            .extract().path("shareId")
    }

    /** `POST /api/shares/{shareId}/reissue` under [newId], the recovery copy at [epoch] (ADR 0075). */
    private fun reissue(userId: String, shareId: String, epoch: Int, newId: String = TestIds.shareId(), keys: String = """"wrap":"$newWrap"""") =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"shareId":"$newId",$keys,"sealedName":"$sealedName","sealedShareKey":"$shareKey","sealedShareKeyEpoch":$epoch}""")
            .`when`().post("/api/shares/$shareId/reissue")

    private fun resolves(shareId: String, status: Int) = given().`when`().get("/api/shares/$shareId").then().statusCode(status)

    /** The owner removes [member] from [group] — a removal-rotation to the next epoch, the one way a keyed member
     *  leaves (ADR 0024 Decision 4); [revokeLinks] is the ADR 0075 opt-in, omitted when null. */
    private fun removeByRotation(owner: String, group: String, member: String?, revokeLinks: Boolean? = null) =
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body(
                """{"epoch":${TestAdmission.epochOf(group) + 1},"wrappedPrev":"cHJldg==",""" +
                    """"grants":[{"userId":"$owner","wrappedGrant":"Z3JhbnQ=",${TestAdmission.SIGNATURE_JSON}}],""" +
                    TestAdmission.openJson(group) +
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
