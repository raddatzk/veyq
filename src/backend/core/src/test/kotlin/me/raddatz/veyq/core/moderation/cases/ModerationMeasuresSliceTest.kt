package me.raddatz.veyq.core.moderation.cases

import me.raddatz.veyq.core.testing.TestNodeRekey
import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.membership.TestGrants
import me.raddatz.veyq.core.content.NodeCover
import me.raddatz.veyq.core.moderation.ModerationTestKit
import me.raddatz.veyq.core.moderation.block.BlockKind
import me.raddatz.veyq.core.moderation.block.ModerationBlockEntity
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.case
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.lift
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.propose
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.take
import me.raddatz.veyq.core.moderation.report.ReportTestKit.asMember
import me.raddatz.veyq.core.moderation.report.ReportTestKit.body
import me.raddatz.veyq.core.moderation.report.ReportTestKit.item
import me.raddatz.veyq.core.moderation.report.ReportTestKit.openCase
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.sharing.GuestPaths
import me.raddatz.veyq.core.sharing.GuestPaths.Path
import me.raddatz.veyq.core.sharing.GuestTreeShare
import me.raddatz.veyq.core.sharing.ReissueShareOutcome
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.ShareIdentityEntity
import me.raddatz.veyq.core.sharing.ShareWriteProof
import me.raddatz.veyq.core.sharing.SharingHandler
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedAttachment
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.hasItem
import org.hamcrest.CoreMatchers.not
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.containsInAnyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * The measures (uc.moderation.take-a-measure; ADR 0093 § 6, ADR 0099 § 6) on core's paths, each bite with its control:
 * a blocked link answers every guest path exactly as an unknown link and serves again once lifted; a blocked guest
 * uploader's token is refused and its link with it, while the link's creator re-issues one whose new guest writes; the
 * Server Owner's steps are proposals on the admin channel, a sign-in block ends every session and refuses every sign-in
 * until it is lifted.
 */
@QuarkusTest
class ModerationMeasuresSliceTest {

    @Inject
    lateinit var sharing: SharingHandler

    private data class World(val ownerId: String, val scopeId: String, val tenantId: String, val root: String, val cover: String)

    private fun world(uploader: String = UUID.randomUUID().toString()): World {
        val m = seedMemberScope(ScopeEntity.GROUP)
        val root = seedNode(m.scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        seedNode(m.scopeId, NodeEntity.FOLDER, parentId = root, wrappedContentKey = CONTENT_KEY)
        val cover = seedAttachment(m.scopeId, root, NodeCover.SLOT, uploaderUserId = uploader)
        return World(m.userId, m.scopeId, m.tenantId, root, cover.blobId)
    }

    private fun reportOf(w: World): String {
        asMember(w.ownerId, w.scopeId, body(listOf(item("NODE", w.root)))).then().statusCode(201)
        return openCase("NODE", w.root)!!.id
    }

    private fun guestPaths(w: World): List<Path> = listOf(
        Path("resolveShare") { id -> given().`when`().get("/api/shares/$id") },
        Path("getShareTerms") { id -> given().`when`().get("/api/shares/$id/terms") },
        Path("resolveShareBlob") { id -> given().`when`().get("/api/shares/$id/blobs/${w.cover}") },
        Path("createShareIdentity") { id ->
            given().contentType(ContentType.JSON).header(ShareWriteProof.HEADER, ShareWriteProof.token(id))
                .body("""{"identityId":"${TestIds.uuid()}","sealedDisplayName":"bmFtZQ==","nodeEpoch":0}""")
                .`when`().post("/api/shares/$id/identities")
        },
    )

    private fun reissue(ownerId: String, shareId: String): Pair<ReissueShareOutcome, String> {
        val newId = TestIds.shareId()
        return sharing.reissue(
            ownerId, shareId, newId, wrap = "d3JhcA==", sealedDropPublicKey = null, sealedName = "c2VhbGVk", expiresAt = null,
            sealedNote = null, sealedShareKey = "cmVjb3Zlcnk=", wrappedRecoveryValueKey = TestGrants.freshWrap(),
            sealedShareKeyEpoch = 0, sealedShareName = null,
            passwordRequired = false, pwSalt = null, pwVerifier = null, sealedPassword = null,
            writeVerifier = ShareWriteProof.verifier(newId), nodeEpoch = 0,
        ) to newId
    }

    @Test
    fun aBlockedShareAnswersEveryGuestPathAsAnUnknownLinkAndServesAgainOnceLifted() {
        val mod = CaseTestKit.moderator()
        val w = world()
        val link = GuestTreeShare.seed(w.ownerId, w.scopeId, w.root, ShareEntity.READ_WRITE)
        val caseId = reportOf(w)
        // Control: before the block every path serves the link.
        assertEquals(emptyList<String>(), GuestPaths.served(link.shareId, guestPaths(w), "before the block"))
        val measure = mod.take(caseId, "SHARE", shareId = link.shareId).then().statusCode(201).extract().path<String>("measures[0].id")
        val refused = GuestPaths.refusedLikeAnUnknownLink(link.shareId, guestPaths(w), "while blocked")
        assertTrue(refused.isEmpty(), refused.joinToString("\n"))
        // The creator's list says why; the owner can neither reactivate nor re-issue it.
        given().header("Authorization", bearer(w.ownerId)).`when`().get("/api/shares?nodeId=${w.root}")
            .then().statusCode(200).body("find { it.shareId == '${link.shareId}' }.guestStops.reason", hasItem("operatorBlocked"))
        given().header("Authorization", bearer(w.ownerId)).contentType(ContentType.JSON).body(TestNodeRekey.reopenBody(link.shareId)).`when`().post("/api/shares/${link.shareId}/reactivate")
            .then().statusCode(409).body("code", equalTo("share_blocked_by_operator"))
        assertEquals(ReissueShareOutcome.Blocked, reissue(w.ownerId, link.shareId).first)
        // Lifted, the same link serves again — nothing was written onto it.
        mod.lift(caseId, measure).then().statusCode(200)
        assertEquals(emptyList<String>(), GuestPaths.served(link.shareId, guestPaths(w), "after the lift"))
        given().header("Authorization", bearer(w.ownerId)).`when`().get("/api/shares?nodeId=${w.root}")
            .then().body("find { it.shareId == '${link.shareId}' }.guestStops.reason", not(hasItem("operatorBlocked")))
    }

    @Test
    fun aBlockedGuestUploaderIsRefusedWithItsLinkWhileTheCreatorsReissuedLinkTakesANewGuest() {
        val mod = CaseTestKit.moderator()
        val w = world()
        val link = GuestTreeShare.seed(w.ownerId, w.scopeId, w.root, ShareEntity.READ_WRITE)
        val identityId = QuarkusTransaction.requiringNew().call { ShareIdentityEntity.find("shareId", link.shareId).firstResult()!!.id }
        // The guest uploaded something into the reported subtree before the report: the held rows name it.
        GuestTreeShare.create(link, w.root, NodeEntity.FOLDER).statusCode(201)
        val caseId = reportOf(w)
        mod.case(caseId).then().body("targets.uploaders.find { it.id == '$identityId' }.kind", equalTo("GUEST_IDENTITY"))
        mod.take(caseId, "GUEST_IDENTITY", targetId = identityId).then().statusCode(201)
            .body("targets.uploaders.find { it.id == '$identityId' }.blocked", equalTo(true))
            .body("targets.links.find { it.shareId == '${link.shareId}' }.blocked", equalTo(true))
        // Its token writes no more, and its link answers as unknown — every guest path.
        GuestTreeShare.create(link, w.root, NodeEntity.FOLDER).statusCode(404).body("code", equalTo("share_not_found"))
        assertTrue(GuestPaths.refusedLikeAnUnknownLink(link.shareId, guestPaths(w), "with its uploader blocked").isEmpty())
        // The creator may re-issue it (decision 4): the new link is a member's new decision, and a new guest writes.
        val (outcome, newId) = reissue(w.ownerId, link.shareId)
        assertTrue(outcome is ReissueShareOutcome.Created, "$outcome")
        // The re-issue closed the old link and marked the root due (ADR 0114 decision 1); a member's worker re-keys it,
        // and the new link's guest writes under the root's new key.
        val rootEpoch = TestNodeRekey.settled(w.root)
        val token = given().contentType(ContentType.JSON).header(ShareWriteProof.HEADER, ShareWriteProof.token(newId))
            .body("""{"identityId":"${TestIds.uuid()}","sealedDisplayName":"bmFtZQ==","nodeEpoch":$rootEpoch}""")
            .`when`().post("/api/shares/$newId/identities").then().statusCode(201).extract().path<String>("contributorToken")
        GuestTreeShare.create(GuestTreeShare.Link(newId, token), w.root, NodeEntity.FOLDER, parentEpoch = rootEpoch).statusCode(201)
    }

    /** The identity check on its own (decision 4): a block naming the identity alone refuses its token, its link still served. */
    @Test
    fun anIdentityBlockAloneRefusesItsTokenWhileTheLinkStillServes() {
        CaseTestKit.moderator()
        val w = world()
        val link = GuestTreeShare.seed(w.ownerId, w.scopeId, w.root, ShareEntity.READ_WRITE)
        val identityId = QuarkusTransaction.requiringNew().call { ShareIdentityEntity.find("shareId", link.shareId).firstResult()!!.id }
        GuestTreeShare.create(link, w.root, NodeEntity.FOLDER).statusCode(201) // control
        // A forged state no decision writes (the link is not named), so exactly the identity check is left to refuse.
        QuarkusTransaction.requiringNew().run {
            ModerationBlockEntity().apply {
                id = UUID.randomUUID().toString(); kind = BlockKind.GUEST_IDENTITY.name; targetId = identityId
                takenBy = UUID.randomUUID().toString(); takenAt = Instant.now()
                activeKey = ModerationBlockEntity.activeKeyOf(BlockKind.GUEST_IDENTITY, identityId)
            }.persist()
        }
        given().`when`().get("/api/shares/${link.shareId}").then().statusCode(200)
        GuestTreeShare.create(link, w.root, NodeEntity.FOLDER).statusCode(403).body("code", equalTo("share_not_contributable"))
    }

    @Test
    fun theServerOwnersStepsAreProposalsTakenOrDeclinedOnTheAdminChannelOnly() {
        val mod = CaseTestKit.moderator()
        val member = seedUser()
        val w = world(uploader = member)
        val caseId = reportOf(w)
        // A member uploader the held rows name; any other account is no target.
        mod.propose(caseId, "MEMBER_SIGN_IN", targetId = UUID.randomUUID().toString()).then().statusCode(404)
            .body("code", equalTo("moderation_target_unknown"))
        mod.propose(caseId, "MEMBER_SIGN_IN", targetId = member).then().statusCode(201).body("proposals[0].outcome", equalTo("OPEN"))
        mod.propose(caseId, "MEMBER_SIGN_IN", targetId = member).then().statusCode(409).body("code", equalTo("moderation_proposal_exists"))
        mod.propose(caseId, "TENANT_OFFLINE").then().statusCode(201)
        // A moderator who is not the Server Owner holds no Server Owner's step on either listener; the Server Owner's own
        // session reaches them only on the admin channel — on the public listener they answer as an unknown route.
        val deputy = seedUser(email = "deputy-${UUID.randomUUID()}@example.com")
        ModerationTestKit.appointAndAccept(mod.id, emailOf(deputy), deputy)
        ModerationTestKit.asUser(deputy).contentType(ContentType.JSON).body("""{"address":"${emailOf(member)}"}""")
            .`when`().post("/api/instance/moderation/sign-in-blocks").then().statusCode(403).body("code", equalTo("not_server_owner"))
        ModerationTestKit.asOwner(deputy).body("""{"address":"${emailOf(member)}"}""")
            .`when`().post("/api/instance/moderation/sign-in-blocks").then().statusCode(403).body("code", equalTo("not_server_owner"))
        ModerationTestKit.asUser(mod.id).contentType(ContentType.JSON).body("""{"address":"${emailOf(member)}"}""")
            .`when`().post("/api/instance/moderation/sign-in-blocks").then().statusCode(404)
        ModerationTestKit.asUser(mod.id).`when`().get("/api/instance/moderation/steps").then().statusCode(404)
        // Control: the Server Owner on the admin channel sees both.
        ModerationTestKit.asOwner(mod.id).`when`().get("/api/instance/moderation/steps").then().statusCode(200)
            .body("proposals.size()", equalTo(2))
            .body("proposals.find { it.step == 'TENANT_OFFLINE' }.tenantDomain", not(equalTo(null)))
            .body("proposals.find { it.step == 'MEMBER_SIGN_IN' }.address", equalTo(emailOf(member)))
        // Suspending the tenant takes its proposal — over content, so only with its statement of reasons (ADR 0093 § 7);
        // declining the other settles it.
        ModerationTestKit.asOwner(mod.id).`when`().post("/api/tenants/${w.tenantId}/suspend").then().statusCode(400)
            .body("code", equalTo("statement_required"))
        ModerationTestKit.asOwner(mod.id).body(
            """{"statement":{"statementId":"${UUID.randomUUID()}","ground":"TERMS_BREACH","provision":"Space terms, clause 4",""" +
                """"facts":"what was found","factsForLog":{"epoch":1,"sealedFacts":"c2VhbGVk"}}}""",
        ).`when`().post("/api/tenants/${w.tenantId}/suspend").then().statusCode(200)
        val memberProposal = ModerationTestKit.asOwner(mod.id).`when`().get("/api/instance/moderation/steps").then()
            .body("proposals.size()", equalTo(1)).extract().path<String>("proposals[0].id")
        ModerationTestKit.asOwner(mod.id).`when`().post("/api/instance/moderation/proposals/$memberProposal/decline")
            .then().statusCode(200).body("proposals.size()", equalTo(0))
        mod.case(caseId).then()
            .body("proposals.find { it.step == 'TENANT_OFFLINE' }.outcome", equalTo("TAKEN"))
            .body("proposals.find { it.step == 'MEMBER_SIGN_IN' }.outcome", equalTo("DECLINED"))
            // The log reads back each step: both proposals, the one taken and the one declined, each naming its step and target.
            .body("events.findAll { it.event == 'PROPOSED' }.detail", containsInAnyOrder("MEMBER_SIGN_IN $member", "TENANT_OFFLINE ${w.tenantId}"))
            .body("events.findAll { it.event == 'PROPOSAL_TAKEN' }.detail", contains("TENANT_OFFLINE ${w.tenantId}"))
            .body("events.findAll { it.event == 'PROPOSAL_DECLINED' }.detail", contains("MEMBER_SIGN_IN $member"))
    }

    @Test
    fun aSignInBlockEndsEverySessionAndRefusesEverySignInUntilLifted() {
        val mod = CaseTestKit.moderator()
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val email = "blocked-${UUID.randomUUID()}@example.com"
        val member = seedUser(email = email, authPublicKey = b64(kp.public.encoded.let { it.copyOfRange(it.size - 32, it.size) }))
        val session = bearer(member)
        given().header("Authorization", session).`when`().get("/api/account/email").then().statusCode(200) // control
        login(email, kp).then().statusCode(200) // control
        val blockId = ModerationTestKit.asOwner(mod.id).body("""{"address":"$email"}""")
            .`when`().post("/api/instance/moderation/sign-in-blocks").then().statusCode(200)
            .body("steps.signInBlocks.size()", equalTo(1)).extract().path<String>("steps.signInBlocks[0].id")
        given().header("Authorization", session).`when`().get("/api/account/email").then().statusCode(401)
        login(email, kp).then().statusCode(401).body("code", equalTo("authentication_failed"))
        ModerationTestKit.asOwner(mod.id).body("""{"address":"$email"}""").`when`().post("/api/instance/moderation/sign-in-blocks")
            .then().statusCode(409).body("code", equalTo("sign_in_block_exists"))
        ModerationTestKit.asOwner(mod.id).body("""{"address":"${emailOf(mod.id)}"}""").`when`().post("/api/instance/moderation/sign-in-blocks")
            .then().statusCode(409).body("code", equalTo("cannot_block_server_owner"))
        ModerationTestKit.asOwner(mod.id).`when`().delete("/api/instance/moderation/sign-in-blocks/$blockId")
            .then().statusCode(200).body("signInBlocks.size()", equalTo(0))
        login(email, kp).then().statusCode(200)
    }

    private fun emailOf(userId: String): String =
        QuarkusTransaction.requiringNew().call { me.raddatz.veyq.core.identity.VeyqUser.findById(userId)!!.email }

    private fun login(email: String, kp: KeyPair): io.restassured.response.Response {
        val nonce = given().contentType(ContentType.JSON).body("""{"email":"$email"}""").`when`().post("/api/login/challenge")
            .then().statusCode(200).extract().path<String>("nonce")
        val sig = Signature.getInstance("Ed25519").run { initSign(kp.private); update(Base64.getDecoder().decode(nonce)); sign() }
        return given().contentType(ContentType.JSON).body("""{"email":"$email","nonce":"$nonce","signature":"${b64(sig)}"}""")
            .`when`().post("/api/login/verify")
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
