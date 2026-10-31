package me.raddatz.veyq.core.moderation.statement

import me.raddatz.veyq.core.content.NodeCover
import me.raddatz.veyq.core.crypto.Tokens
import me.raddatz.veyq.core.moderation.ModerationPurge
import me.raddatz.veyq.core.moderation.cases.CaseTestKit
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.PROVISION
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.case
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.lift
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.order
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.orderBody
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.take
import me.raddatz.veyq.core.moderation.report.ReportTestKit.asMember
import me.raddatz.veyq.core.moderation.report.ReportTestKit.body
import me.raddatz.veyq.core.moderation.report.ReportTestKit.item
import me.raddatz.veyq.core.moderation.report.ReportTestKit.openCase
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.sharing.GuestTreeShare
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.ShareIdentityEntity
import me.raddatz.veyq.core.testing.FakePresignProfile
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedAttachment
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.response.Response
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * A guest uploader told of a moderator's measure on the link it came through (ADR 0093 § 7, inbox #813;
 * uc.moderation.object-to-a-measure step 5): the statement's clear fields, never its facts, read by the contributor
 * token its client holds — even while the link is blocked — and the link's ordinary unknown answer for everyone else.
 * Every negative has its control on the same request: another identity of the same link, an unknown token, and the
 * same token on another link of the same folder get `404`, while the uploader's own token gets the statement; a
 * deferred statement is not read before its end and is after it; a lifted measure is not read any more.
 */
@QuarkusTest
@TestProfile(FakePresignProfile::class)
class GuestStatementsSliceTest {

    @Inject
    lateinit var purge: ModerationPurge

    private data class World(
        val ownerId: String, val scopeId: String, val root: String, val link: String, val other: String,
        val guestIdentity: String, val guestToken: String,
    )

    /** A space's folder with a guest-uploaded cover, shared by two links; the guest came through [World.link]. */
    private fun world(): World {
        val m = seedMemberScope(ScopeEntity.GROUP)
        val root = seedNode(m.scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val link = GuestTreeShare.seed(m.userId, m.scopeId, root, ShareEntity.READ_WRITE)
        val other = GuestTreeShare.seed(m.userId, m.scopeId, root, ShareEntity.READ).shareId
        val identity = QuarkusTransaction.requiringNew().call { ShareIdentityEntity.find("shareId", link.shareId).firstResult()!!.id }
        seedAttachment(m.scopeId, root, NodeCover.SLOT, shareIdentityId = identity)
        return World(m.userId, m.scopeId, root, link.shareId, other, identity, link.token)
    }

    /** A second guest identity on [shareId] that uploaded nothing — its token, as its client holds it. */
    private fun otherGuest(shareId: String): String {
        val token = Tokens.random()
        QuarkusTransaction.requiringNew().run {
            ShareIdentityEntity().apply {
                id = UUID.randomUUID().toString(); this.shareId = shareId; sealedDisplayName = "bmFtZQ=="; tokenHash = Tokens.hash(token)
            }.persist()
        }
        return token
    }

    private fun notice(shareId: String, token: String): Response =
        given().header("X-Contributor-Token", token).`when`().get("/api/shares/$shareId/guest-notice")

    /** A case on the world's folder, reported by its owner. */
    private fun reported(w: World): String {
        asMember(w.ownerId, w.scopeId, body(listOf(item("NODE", w.root)))).then().statusCode(201)
        return openCase("NODE", w.root)!!.id
    }

    @Test
    fun aGuestUploaderReadsAModeratorsStatementOnItsLinkWithoutTheFactsAndNobodyElseDoes() {
        val mod = CaseTestKit.moderator()
        val w = world()
        val stranger = otherGuest(w.link)
        val caseId = reported(w)
        // Before any measure there is nothing to tell: the link's ordinary unknown answer.
        notice(w.link, w.guestToken).then().statusCode(404).body("code", equalTo("share_not_found"))

        val takenAt = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        mod.take(caseId, "CONTENT", targetId = w.root, ground = "ILLEGAL_CONTENT").then().statusCode(201)
        val told = notice(w.link, w.guestToken).then().statusCode(200)
            .body("removals.size()", equalTo(0))
            .body("statements.size()", equalTo(1))
            .body("statements[0].measureKind", equalTo("CONTENT"))
            .body("statements[0].targetId", equalTo(w.root))
            .body("statements[0].shareId", nullValue())
            .body("statements[0].ground", equalTo("ILLEGAL_CONTENT"))
            .body("statements[0].provision", equalTo(PROVISION))
            // The facts are sealed to the members' identity keys; a guest identity has none, so none of it is served.
            .body("statements[0].sealedFacts", nullValue())
            .body("statements[0].wrappedFactsKey", nullValue())
            .body("statements[0].caseId", nullValue())
            .extract().response()
        assertEquals(false, OffsetDateTime.parse(told.path<String>("statements[0].takenAt")).toInstant().isBefore(takenAt))

        // Control: another identity's token on the same link, and an unknown one, get the link's ordinary unknown answer;
        // the identity's token names it on its own link only.
        notice(w.link, stranger).then().statusCode(404).body("code", equalTo("share_not_found"))
        notice(w.link, Tokens.random()).then().statusCode(404).body("code", equalTo("share_not_found"))
        notice(w.other, w.guestToken).then().statusCode(404).body("code", equalTo("share_not_found"))

        // The link blocked too: its resolve answers as unknown, and the guest still reads both statements on it.
        mod.take(caseId, "SHARE", shareId = w.link).then().statusCode(201)
        given().`when`().get("/api/shares/${w.link}").then().statusCode(404)
        notice(w.link, w.guestToken).then().statusCode(200)
            .body("statements.size()", equalTo(2))
            .body("statements.find { it.measureKind == 'SHARE' }.shareId", equalTo(w.link))

        // Lifted, nothing is told any more.
        mod.case(caseId).path<List<String>>("measures.id").forEach { mod.lift(caseId, it).then().statusCode(200) }
        notice(w.link, w.guestToken).then().statusCode(404).body("code", equalTo("share_not_found"))
    }

    @Test
    fun aBlockedGuestIdentityReadsItsStatementOnItsBlockedLink() {
        val mod = CaseTestKit.moderator()
        val w = world()
        val caseId = reported(w)
        mod.take(caseId, "GUEST_IDENTITY", targetId = w.guestIdentity).then().statusCode(201)
        notice(w.link, w.guestToken).then().statusCode(200)
            .body("statements.size()", equalTo(1))
            .body("statements[0].measureKind", equalTo("GUEST_IDENTITY"))
            .body("statements[0].targetId", equalTo(w.guestIdentity))
            .body("statements[0].shareId", equalTo(w.link))
    }

    @Test
    fun aDeferredStatementReachesTheGuestOnlyOnceTheDeferralEnds() {
        val mod = CaseTestKit.moderator()
        val w = world()
        val caseId = reported(w)
        val until = Instant.now().plus(14, ChronoUnit.DAYS)
        mod.take(caseId, "CONTENT", targetId = w.root, deferUntil = until.toString()).then().statusCode(201)
        // While the authority's deferral runs, the guest is told nothing (ADR 0093 § 7), as no member is.
        notice(w.link, w.guestToken).then().statusCode(404).body("code", equalTo("share_not_found"))
        // Once it ended and the cleanup released the statement, it is told.
        purge.sweep(until.plus(1, ChronoUnit.HOURS))
        notice(w.link, w.guestToken).then().statusCode(200).body("statements.size()", equalTo(1))
    }

    @Test
    fun aModeratorsMeasureOnAPreservationOrdersCaseIsAStatementAndNoRemoval() {
        val mod = CaseTestKit.moderator()
        val w = world()
        // A preservation order blocks nothing and opens a case; a moderator then blocks the content on that case. No
        // removal order took the upload down, so no removal is told — the moderator's statement is.
        val caseId = mod.order(orderBody(w.link, kind = "PRESERVATION")).then().statusCode(201).extract().path<String>("caseId")
        notice(w.link, w.guestToken).then().statusCode(404).body("code", equalTo("share_not_found"))
        mod.take(caseId, "CONTENT", targetId = w.root).then().statusCode(201)
        notice(w.link, w.guestToken).then().statusCode(200)
            .body("removals.size()", equalTo(0))
            .body("statements.size()", equalTo(1))
    }
}
