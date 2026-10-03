package com.veyq.app.features.sharing

import com.veyq.app.core.sharing.ShareEntity
import com.veyq.app.features.content.NodeEntity
import com.veyq.app.features.spaces.scope.RotationDecision
import com.veyq.app.features.spaces.scope.RotationGrant
import com.veyq.app.features.spaces.scope.ScopesStore
import com.veyq.app.shared.TestIds
import com.veyq.app.shared.membership.MembershipEntity
import com.veyq.app.shared.membership.SignedGrant
import com.veyq.app.shared.membership.TestAdmission
import com.veyq.app.shared.scope.ScopeEntity
import com.veyq.app.testing.TestFixtures.CONTENT_KEY
import com.veyq.app.testing.TestFixtures.bearer
import com.veyq.app.testing.TestFixtures.seedNode
import com.veyq.app.testing.TestFixtures.seedTenant
import com.veyq.app.testing.TestFixtures.seedUser
import com.veyq.app.testing.race
import com.veyq.app.testing.whileUncommitted
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.hasItem
import org.hamcrest.CoreMatchers.not
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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

    @Inject
    lateinit var sharing: SharingStore

    @Inject
    lateinit var handler: SharingHandler

    @Inject
    lateinit var scopes: ScopesStore

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
            .body("shareId", not(hasItem(old))) // deleted, not merely off (ADR 0029 §7)
    }

    /** B4 — a voluntary re-issue (nobody left, the copy at the current epoch) used to leave the old link one
     *  re-activation from coming back, for any member: the re-issue now deletes it. */
    @Test
    fun aReissuedLinkCannotComeBack() {
        val (tenantId, owner) = seedActiveTenant("g-reissue-dead.example.com")
        val group = seedGroup(tenantId, owner)
        val old = createShare(owner, seedNode(group, NodeEntity.MEMORY, parentId = group, wrappedContentKey = CONTENT_KEY), copyEpoch = 0)

        reissue(owner, old, epoch = 0).then().statusCode(201)

        reactivate(owner, old).then().statusCode(404).body("code", equalTo("share_not_found"))
        resolves(old, 404)
    }

    /** Inbox #252 — a share is re-issued at most once: a second re-issue of the replaced link is a named conflict
     *  (the caller re-reads the list and finds the new link), and it mints nothing. A non-member still gets the
     *  non-enumerable 404 — the conflict is a member's answer only. */
    @Test
    fun aReissuedShareRefusesASecondReissueWithAConflict() {
        val (tenantId, owner) = seedActiveTenant("g-reissue-twice.example.com")
        val group = seedGroup(tenantId, owner)
        val old = createShare(owner, seedNode(group, NodeEntity.MEMORY, parentId = group, wrappedContentKey = CONTENT_KEY), copyEpoch = 0)
        reissue(owner, old, epoch = 0).then().statusCode(201)
        val second = TestIds.shareId()

        reissue(owner, old, epoch = 0, newId = second).then().statusCode(409).body("code", equalTo("share_deleted"))
        resolves(second, 404)
        reissue(seedUser(email = "out@g-reissue-twice.example.com"), old, epoch = 0)
            .then().statusCode(404).body("code", equalTo("share_not_found"))
    }

    /** Inbox #252 — two re-issues of one share racing each other: exactly one mints its link, the other gets the
     *  conflict a sequential second re-issue gets. A barrier only makes the race likely, so it runs [ROUNDS] times. */
    @Test
    fun twoConcurrentReissuesOfOneShareMintExactlyOneLink() {
        val (tenantId, owner) = seedActiveTenant("g-reissue-race.example.com")
        val group = seedGroup(tenantId, owner)
        val memory = seedNode(group, NodeEntity.MEMORY, parentId = group, wrappedContentKey = CONTENT_KEY)
        repeat(ROUNDS) { round ->
            val old = createShare(owner, memory, copyEpoch = 0)
            val ids = listOf(TestIds.shareId(), TestIds.shareId())
            val answers = race(
                { reissue(owner, old, epoch = 0, newId = ids[0]).let { it.statusCode to it.path<String?>("code") } },
                { reissue(owner, old, epoch = 0, newId = ids[1]).let { it.statusCode to it.path<String?>("code") } },
            )
            assertEquals(listOf(201 to null, 409 to "share_deleted"), answers.sortedBy { it.first }, "round $round")
            val live = ids.filter { given().`when`().get("/api/shares/$it").statusCode == 200 }
            assertEquals(1, live.size, "round $round: one new link, got answers $answers")
            resolves(old, 404)
        }
    }

    // --- delete (ADR 0029 §7, B2) ---

    @Test
    fun theOwnerDeletesALinkAndItIsGoneEverywhere() {
        val (tenantId, owner) = seedActiveTenant("g-delete.example.com")
        val group = seedGroup(tenantId, owner)
        val memory = seedNode(group, NodeEntity.MEMORY, parentId = group, wrappedContentKey = CONTENT_KEY)
        val shareId = createShare(owner, memory, copyEpoch = 0)
        given().header("Authorization", bearer(owner)).`when`().get("/api/shares?nodeId=$memory")
            .then().statusCode(200).body("find { it.shareId == '$shareId' }.deletable", equalTo(true))

        delete(owner, shareId).then().statusCode(204) // enabled: turned off in the same transaction

        resolves(shareId, 404).body("code", equalTo("share_not_found"))
        given().header("Authorization", bearer(owner)).`when`().get("/api/shares?nodeId=$memory")
            .then().statusCode(200).body("shareId", not(hasItem(shareId)))
        given().header("Authorization", bearer(owner)).`when`().get("/api/shares")
            .then().statusCode(200).body("shareId", not(hasItem(shareId)))
        reactivate(owner, shareId).then().statusCode(404).body("code", equalTo("share_not_found"))
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON).body("{}")
            .`when`().patch("/api/shares/$shareId").then().statusCode(404).body("code", equalTo("share_not_found"))
        given().header("Authorization", bearer(owner)).`when`().post("/api/shares/$shareId/revoke")
            .then().statusCode(404).body("code", equalTo("share_not_found"))
        reissue(owner, shareId, epoch = 0).then().statusCode(409).body("code", equalTo("share_deleted")) // inbox #252
        delete(owner, shareId).then().statusCode(404).body("code", equalTo("share_not_found"))
        // the id is never minted again — not even by its owner, on the same Memory
        postShare(owner, memory, shareId).then().statusCode(409).body("code", equalTo("id_taken"))
    }

    /** Another member's link is theirs while they are here — note and password copy included; once they have left
     *  the scope, the orphan is anyone's to delete. The list's `deletable` is the gate's own answer. */
    @Test
    fun aCoMembersLinkIsDeletableOnlyOnceItsOwnerLeft() {
        val (tenantId, owner) = seedActiveTenant("g-delete-co.example.com")
        val member = seedTenantMember(tenantId, "m@g-delete-co.example.com")
        val group = seedGroup(tenantId, owner)
        addMember(tenantId, group, member)
        val memory = seedNode(group, NodeEntity.MEMORY, parentId = group, wrappedContentKey = CONTENT_KEY)
        val theirs = createShare(member, memory)

        given().header("Authorization", bearer(owner)).`when`().get("/api/shares?nodeId=$memory")
            .then().statusCode(200).body("find { it.shareId == '$theirs' }.deletable", equalTo(false))
        delete(owner, theirs).then().statusCode(404).body("code", equalTo("share_not_found"))
        resolves(theirs, 200)

        removeByRotation(owner, group, member).then().statusCode(201)
        given().header("Authorization", bearer(owner)).`when`().get("/api/shares?nodeId=$memory")
            .then().statusCode(200).body("find { it.shareId == '$theirs' }.deletable", equalTo(true))
        delete(owner, theirs).then().statusCode(204)
        given().header("Authorization", bearer(owner)).`when`().get("/api/shares?nodeId=$memory")
            .then().statusCode(200).body("shareId", not(hasItem(theirs)))
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

    // --- a tenant removal marks every link the removed member could copy (ADR 0075 point 4, the removal counter) ---

    /** The co-member's link the removed member could have rebuilt from its recovery copy: the tenant's epoch never
     *  moves on a removal, so only the removal counter can refuse it. The positive control on the same predicate is a
     *  link minted after the removal; the list carries the very predicate the refusal runs. */
    @Test
    fun aCoMembersTenantLinkMintedBeforeARemovalIsNotRevived() {
        val (tenantId, owner) = seedActiveTenant("t-comember.example.com")
        val member = seedTenantMember(tenantId, "m@t-comember.example.com")
        val coMember = seedTenantMember(tenantId, "c@t-comember.example.com")
        val memory = seedNode(tenantId, NodeEntity.MEMORY, parentId = tenantId, wrappedContentKey = CONTENT_KEY)
        val before = createShare(coMember, memory, copyEpoch = 0)
        removeFromTenant(owner, tenantId, member).then().statusCode(204)
        assertEquals(0, TestAdmission.epochOf(tenantId)) // no epoch signal: the counter carries it alone
        val after = createShare(coMember, memory, copyEpoch = 0)
        revoke(coMember, before)
        revoke(coMember, after)

        reactivate(coMember, before).then().statusCode(409).body("code", equalTo("reissue_required"))
        resolves(before, 404)
        given().header("Authorization", bearer(coMember)).`when`().get("/api/shares?nodeId=$memory")
            .then().statusCode(200)
            .body("find { it.shareId == '$before' }.reissueRequired", equalTo(true))
            .body("find { it.shareId == '$after' }.reissueRequired", equalTo(false))
        reactivate(coMember, after).then().statusCode(204)
        resolves(after, 200)
    }

    /** The null-copy control: a link with no recovery copy, minted before the removal, was never rebuildable by
     *  anyone but its owner — who is still here — so the removal says nothing about it. */
    @Test
    fun aCoMembersTenantLinkWithoutACopyIsRevivedAfterARemoval() {
        val (tenantId, owner) = seedActiveTenant("t-nocopy.example.com")
        val member = seedTenantMember(tenantId, "m@t-nocopy.example.com")
        val coMember = seedTenantMember(tenantId, "c@t-nocopy.example.com")
        val memory = seedNode(tenantId, NodeEntity.MEMORY, parentId = tenantId, wrappedContentKey = CONTENT_KEY)
        val noCopy = createShare(coMember, memory)
        removeFromTenant(owner, tenantId, member).then().statusCode(204)
        revoke(coMember, noCopy)

        given().header("Authorization", bearer(coMember)).`when`().get("/api/shares?nodeId=$memory")
            .then().statusCode(200).body("find { it.shareId == '$noCopy' }.reissueRequired", equalTo(false))
        reactivate(coMember, noCopy).then().statusCode(204)
        resolves(noCopy, 200)
    }

    /** A tenant removal cuts the member from a co-shared group too, but only QUEUES that group's rotation — until a
     *  device runs it the group's epoch has not moved, and the counter is what marks the group's links meanwhile. */
    @Test
    fun aCoSharedGroupLinkIsMarkedBeforeItsQueuedRotationRuns() {
        val (tenantId, owner) = seedActiveTenant("t-cogroup.example.com")
        val member = seedTenantMember(tenantId, "m@t-cogroup.example.com")
        val coMember = seedTenantMember(tenantId, "c@t-cogroup.example.com")
        val group = seedGroup(tenantId, coMember)
        addMember(tenantId, group, member)
        val memory = seedNode(group, NodeEntity.MEMORY, parentId = group, wrappedContentKey = CONTENT_KEY)
        val before = createShare(coMember, memory, copyEpoch = 0)
        removeFromTenant(owner, tenantId, member).then().statusCode(204)
        assertEquals(0, TestAdmission.epochOf(group)) // the queued rotation has not run: asserted, not assumed
        val after = createShare(coMember, memory, copyEpoch = 0)
        revoke(coMember, before)
        revoke(coMember, after)

        reactivate(coMember, before).then().statusCode(409).body("code", equalTo("reissue_required"))
        resolves(before, 404)
        reactivate(coMember, after).then().statusCode(204) // control: minted after the removal
        resolves(after, 200)
    }

    /** `revokeLinks` on a tenant removal turns off every TENANT-scope link in the removal's transaction — and only
     *  those: a co-shared group's links are the group owners' to re-issue. A refused removal writes nothing. */
    @Test
    fun aTenantRemovalWithRevokeLinksTurnsOffEveryTenantLinkAndNoGroupLink() {
        val (tenantId, owner) = seedActiveTenant("t-revoke-links.example.com")
        val member = seedTenantMember(tenantId, "m@t-revoke-links.example.com")
        val coMember = seedTenantMember(tenantId, "c@t-revoke-links.example.com")
        val memory = seedNode(tenantId, NodeEntity.MEMORY, parentId = tenantId, wrappedContentKey = CONTENT_KEY)
        val coMemberShare = createShare(coMember, memory, copyEpoch = 0)
        val ownerShare = createShare(owner, memory)
        val group = seedGroup(tenantId, coMember)
        addMember(tenantId, group, member)
        val groupShare = createShare(coMember, seedNode(group, NodeEntity.MEMORY, parentId = group, wrappedContentKey = CONTENT_KEY))

        // the last owner cannot be removed — and the flag riding on that refusal turns nothing off
        removeFromTenant(owner, tenantId, owner, revokeLinks = true).then().statusCode(409).body("code", equalTo("last_owner"))
        listOf(coMemberShare, ownerShare, groupShare).forEach { resolves(it, 200) }

        removeFromTenant(owner, tenantId, member, revokeLinks = true).then().statusCode(204)
        resolves(coMemberShare, 404)
        resolves(ownerShare, 404)
        resolves(groupShare, 200) // TENANT only
    }

    /** The removal's switch-off marks what it turns off, so even a link with no recovery copy — which the removal
     *  counter alone would let any member revive — comes back only by re-issue. Both removal paths. */
    @Test
    fun aRemovalsSwitchOffMarksEveryLinkItTurnsOff() {
        val (tenantId, owner) = seedActiveTenant("t-mark.example.com")
        val member = seedTenantMember(tenantId, "m@t-mark.example.com")
        val coMember = seedTenantMember(tenantId, "c@t-mark.example.com")
        val tenantNoCopy = createShare(coMember, seedNode(tenantId, NodeEntity.MEMORY, parentId = tenantId, wrappedContentKey = CONTENT_KEY))
        val group = seedGroup(tenantId, owner)
        addMember(tenantId, group, member)
        addMember(tenantId, group, coMember)
        val groupNoCopy = createShare(coMember, seedNode(group, NodeEntity.MEMORY, parentId = group, wrappedContentKey = CONTENT_KEY))

        removeByRotation(owner, group, member, revokeLinks = true).then().statusCode(201)
        reactivate(coMember, groupNoCopy).then().statusCode(409).body("code", equalTo("reissue_required"))
        resolves(groupNoCopy, 404)

        val other = seedTenantMember(tenantId, "o@t-mark.example.com")
        removeFromTenant(owner, tenantId, other, revokeLinks = true).then().statusCode(204)
        reactivate(coMember, tenantNoCopy).then().statusCode(409).body("code", equalTo("reissue_required"))
        resolves(tenantNoCopy, 404)
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

    // --- a standalone switch-off by a scope owner (ADR 0075 point 5) ---

    @Test
    fun aScopeOwnersSwitchOffTurnsOffEveryLinkAndItSticks() {
        val (tenantId, owner) = seedActiveTenant("g-switch-off.example.com")
        val coMember = seedTenantMember(tenantId, "c@g-switch-off.example.com")
        val group = seedGroup(tenantId, owner)
        addMember(tenantId, group, coMember)
        val memory = seedNode(group, NodeEntity.MEMORY, parentId = group, wrappedContentKey = CONTENT_KEY)
        val noCopy = createShare(coMember, memory)
        val withCopy = createShare(coMember, memory, copyEpoch = 0)

        // a member who owns nothing of the scope may not; nothing moves
        revokeScopeShares(coMember, group).then().statusCode(403).body("code", equalTo("not_scope_owner"))
        resolves(noCopy, 200)
        resolves(withCopy, 200)

        revokeScopeShares(owner, group).then().statusCode(200).body("revoked", equalTo(2))
        resolves(noCopy, 404)
        resolves(withCopy, 404)
        given().header("Authorization", bearer(owner)).`when`().get("/api/shares?nodeId=$memory")
            .then().statusCode(200)
            .body("find { it.shareId == '$noCopy' }.reissueRequired", equalTo(true))
            .body("find { it.shareId == '$withCopy' }.reissueRequired", equalTo(true))
        // nobody was removed and both owners are members: only the mark refuses these
        reactivate(owner, noCopy).then().statusCode(409).body("code", equalTo("reissue_required"))
        reactivate(coMember, withCopy).then().statusCode(409).body("code", equalTo("reissue_required"))
        revokeScopeShares(owner, group).then().statusCode(200).body("revoked", equalTo(0)) // idempotent

        // control: a link minted after the switch-off is untouched by it
        val after = createShare(coMember, memory, copyEpoch = 0)
        revoke(coMember, after)
        reactivate(coMember, after).then().statusCode(204)
        resolves(after, 200)
    }

    /** Inbox #258 — "turn off every link" means every link: one its owner had already switched off is marked too.
     *  Without the mark, a link with no recovery copy whose owner is still a member came back with one click right
     *  after a switch-off that removed nobody — the very revival the mark exists to stop. The count stays the links
     *  the switch-off turned off. */
    @Test
    fun aSwitchOffAlsoMarksALinkThatWasAlreadyOff() {
        val (tenantId, owner) = seedActiveTenant("g-switch-off-parked.example.com")
        val coMember = seedTenantMember(tenantId, "c@g-switch-off-parked.example.com")
        val group = seedGroup(tenantId, owner)
        addMember(tenantId, group, coMember)
        val memory = seedNode(group, NodeEntity.MEMORY, parentId = group, wrappedContentKey = CONTENT_KEY)
        val parked = createShare(coMember, memory) // no recovery copy: only the mark can refuse it
        val live = createShare(coMember, memory)
        revoke(coMember, parked)

        revokeScopeShares(owner, group).then().statusCode(200).body("revoked", equalTo(1)) // it turned off `live` only

        reactivate(coMember, parked).then().statusCode(409).body("code", equalTo("reissue_required"))
        resolves(parked, 404)
        given().header("Authorization", bearer(coMember)).`when`().get("/api/shares?nodeId=$memory")
            .then().statusCode(200).body("find { it.shareId == '$parked' }.reissueRequired", equalTo(true))
        reactivate(coMember, live).then().statusCode(409).body("code", equalTo("reissue_required")) // control: the one it turned off
    }

    @Test
    fun aTenantOwnerSwitchesOffTheTenantsLinks() {
        val (tenantId, owner) = seedActiveTenant("t-switch-off.example.com")
        val coMember = seedTenantMember(tenantId, "c@t-switch-off.example.com")
        val share = createShare(coMember, seedNode(tenantId, NodeEntity.MEMORY, parentId = tenantId, wrappedContentKey = CONTENT_KEY))

        revokeScopeShares(coMember, tenantId).then().statusCode(403).body("code", equalTo("not_scope_owner"))
        resolves(share, 200)
        revokeScopeShares(owner, tenantId).then().statusCode(200).body("revoked", equalTo(1))
        resolves(share, 404)
    }

    // --- a removal racing a write of the removed member's link (inbox #224) ---

    @Test
    fun anEditOverlappingTheOwnersRemovalLeavesTheLinkOff() {
        // The edit of a link reads it before the owner's removal turns it off and writes after: a full-row UPDATE
        // would write `enabled = true` back and keep the removed member's link live. The edit's row lock (the share
        // write's `liveForUpdate`) and `@DynamicUpdate` each close it on their own.
        repeat(ROUNDS) { round ->
            val domain = "edit-${UUID.randomUUID()}.example.com"
            val (tenantId, owner) = seedActiveTenant(domain)
            val member = seedTenantMember(tenantId, "m@$domain")
            val group = seedGroup(tenantId, owner)
            addMember(tenantId, group, member)
            val memory = seedNode(group, NodeEntity.MEMORY, parentId = group, wrappedContentKey = CONTENT_KEY)
            val shareId = createShare(member, memory)
            val note = "bm90ZQ==" // "note"
            whileUncommitted(
                change = { assertEquals(1, sharing.deactivateSharesByOwnerInScope(member, group), "round $round") },
                overlapping = {
                    sharing.updateShare(member, shareId, note, null, null, null, null, null, null, null)
                },
            )
            QuarkusTransaction.requiringNew().run {
                val share = ShareEntity.findById(shareId)!!
                assertEquals(false, share.enabled, "round $round: the edit turned the removed member's link back on")
                // Control: the edit itself landed.
                assertEquals(note, share.sealedNote, "round $round")
            }
        }
    }

    @Test
    fun aReactivationByAMemberBeingRemovedLeavesTheLinkOff() {
        // The re-activation's checks (membership, the re-issue mark) read before the caller's removal commits, and
        // its write lands after: the removed member then owned a live link in a group they are no longer in — a
        // state neither order of the two produces (reactivate first: the removal turns the adopted link off; the
        // removal first: the caller is no member). Checked under the scope's lock, which every removal holds.
        repeat(ROUNDS) { round ->
            val domain = "react-${UUID.randomUUID()}.example.com"
            val (tenantId, owner) = seedActiveTenant(domain)
            val member = seedTenantMember(tenantId, "m@$domain")
            val group = seedGroup(tenantId, owner)
            addMember(tenantId, group, member)
            val memory = seedNode(group, NodeEntity.MEMORY, parentId = group, wrappedContentKey = CONTENT_KEY)
            val shareId = createShare(owner, memory)
            revoke(owner, shareId)
            val outcome = whileUncommitted(
                change = {
                    val decision = scopes.rotate(
                        group, 1, "cHJldg==", owner, listOf(RotationGrant(owner, SignedGrant("Z3JhbnQ=", TestAdmission.SIGNATURE))),
                        emptyList(), emptySet(), removes = member,
                    )
                    assertTrue(decision is RotationDecision.Commit, "round $round: $decision")
                },
                overlapping = { handler.reactivate(member, shareId) },
            )
            assertEquals(ReactivateShareOutcome.NotFound, outcome, "round $round")
            QuarkusTransaction.requiringNew().run {
                val share = ShareEntity.findById(shareId)!!
                assertEquals(false, share.enabled, "round $round: a removed member re-activated a link")
                assertEquals(owner, share.ownerAccount, "round $round")
            }
            // Control: a member who stays re-activates a link minted after the removal (the one above is now marked
            // for re-issue by the removal, ADR 0075).
            val later = createShare(owner, memory)
            revoke(owner, later)
            assertEquals(ReactivateShareOutcome.Done, handler.reactivate(owner, later), "round $round")
            QuarkusTransaction.requiringNew().run { assertEquals(true, ShareEntity.findById(later)!!.enabled) }
        }
    }

    // --- helpers ---

    /** A share of [role] carrying [keys]; with [copyEpoch], also a recovery copy sealed at that epoch (ADR 0036
     *  point 4). */
    private fun createShare(
        userId: String, nodeId: String, copyEpoch: Int? = null, role: String = "VIEWER", keys: String = """"wrap":"$wrap"""",
    ): String = postShare(userId, nodeId, TestIds.shareId(), copyEpoch, role, keys)
        .then().statusCode(201).body("shareId", notNullValue())
        .extract().path("shareId")

    private fun postShare(
        userId: String, nodeId: String, id: String, copyEpoch: Int? = null, role: String = "VIEWER", keys: String = """"wrap":"$wrap"""",
    ) = given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
        .body(
            """{"shareId":"$id",${ShareWriteProof.field(id)},"nodeId":"$nodeId","role":"$role",$keys,"sealedName":"$sealedName"""" +
                (copyEpoch?.let { ""","sealedShareKey":"$shareKey","sealedShareKeyEpoch":$it""" } ?: "") + "}",
        )
        .`when`().post("/api/shares")

    private fun delete(userId: String, shareId: String) =
        given().header("Authorization", bearer(userId)).`when`().delete("/api/shares/$shareId")

    /** `POST /api/shares/{shareId}/reissue` under [newId], the recovery copy at [epoch] (ADR 0075). */
    private fun reissue(userId: String, shareId: String, epoch: Int, newId: String = TestIds.shareId(), keys: String = """"wrap":"$newWrap"""") =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"shareId":"$newId",${ShareWriteProof.field(newId)},$keys,"sealedName":"$sealedName","sealedShareKey":"$shareKey","sealedShareKeyEpoch":$epoch}""")
            .`when`().post("/api/shares/$shareId/reissue")

    private fun resolves(shareId: String, status: Int) = given().`when`().get("/api/shares/$shareId").then().statusCode(status)

    private fun memberRemovalsOf(scopeId: String): Int =
        QuarkusTransaction.requiringNew().call { ScopeEntity.findById(scopeId)!!.memberRemovals }

    private fun revoke(userId: String, shareId: String) {
        given().header("Authorization", bearer(userId)).`when`().post("/api/shares/$shareId/revoke").then().statusCode(204)
    }

    private fun reactivate(userId: String, shareId: String) =
        given().header("Authorization", bearer(userId)).`when`().post("/api/shares/$shareId/reactivate")

    private fun revokeScopeShares(userId: String, scopeId: String) =
        given().header("Authorization", bearer(userId)).`when`().post("/api/scopes/$scopeId/shares/revoke")

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

    private companion object {
        const val ROUNDS = 5
    }
}
