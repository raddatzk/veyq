package me.raddatz.veyq.core.moderation.order

import me.raddatz.veyq.core.moderation.ModerationEventEntity
import me.raddatz.veyq.core.moderation.ModerationPurge
import me.raddatz.veyq.core.moderation.ModerationTestKit
import me.raddatz.veyq.core.moderation.cases.CaseTestKit
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.case
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.export
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.extend
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.handover
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.holdOf
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.lift
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.order
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.orderBody
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.orders
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.queue
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.renewDeferral
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.take
import me.raddatz.veyq.core.moderation.guestwrite.GuestWriteRecordEntity
import me.raddatz.veyq.core.moderation.hold.Holds
import me.raddatz.veyq.core.moderation.report.ReportTestKit.asMember
import me.raddatz.veyq.core.moderation.report.ReportTestKit.body
import me.raddatz.veyq.core.moderation.report.ReportTestKit.item
import me.raddatz.veyq.core.moderation.report.ReportTestKit.openCase
import me.raddatz.veyq.core.moderation.statement.ModerationStatementEntity
import me.raddatz.veyq.core.content.NodeCover
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.sharing.GuestTreeShare
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.ShareIdentityEntity
import me.raddatz.veyq.core.testing.CoverKit
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedAttachment
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.TestIds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import me.raddatz.veyq.core.testing.FakePresignProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * An authority's order (uc.moderation.authority-order; ADR 0093 §§ 4, 7, 12, ADR 0099 § 6) and the report to an
 * authority (uc.moderation.report-to-an-authority; ADR 0093 §§ 11, 13) with core alone. A space's owner shares a folder
 * twice; the order names one link. Every negative has its control on the same predicate: the guest of the ordered link
 * gets `404` while the guest of the other link still resolves it and gets `451` on the content's bytes; the public port
 * answers the Server Owner's door as an unknown route while the admin listener blocks; an extension before the order's
 * end is refused while one after it passes; a deferred statement is not out before its end and is out after it.
 */
@QuarkusTest
@TestProfile(FakePresignProfile::class)
class AuthorityOrdersSliceTest {

    @Inject
    lateinit var purge: ModerationPurge

    private data class World(
        val ownerId: String, val scopeId: String, val root: String, val sub: String, val cover: String,
        val ordered: String, val other: String, val guestIdentity: String, val guestToken: String,
    )

    /** A space's folder with a subfolder and a guest-uploaded cover, shared by two links. */
    private fun world(): World {
        val m = seedMemberScope(ScopeEntity.GROUP)
        val root = seedNode(m.scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val sub = seedNode(m.scopeId, NodeEntity.FOLDER, parentId = root, wrappedContentKey = CONTENT_KEY)
        val link = GuestTreeShare.seed(m.userId, m.scopeId, root, ShareEntity.READ_WRITE)
        val ordered = link.shareId
        val other = GuestTreeShare.seed(m.userId, m.scopeId, root, ShareEntity.READ).shareId
        val identity = QuarkusTransaction.requiringNew().call { ShareIdentityEntity.find("shareId", ordered).firstResult()!!.id }
        val cover = seedAttachment(m.scopeId, root, NodeCover.SLOT, shareIdentityId = identity).blobId
        return World(m.userId, m.scopeId, root, sub, cover, ordered, other, identity, link.token)
    }

    private fun resolve(shareId: String): Response = given().`when`().get("/api/shares/$shareId")

    /** A guest's notice on [shareId], as the client holding [token] asks it (TCO Art. 11, ADR 0093 § 12). */
    private fun notice(shareId: String, token: String): Response =
        given().header("X-Contributor-Token", token).`when`().get("/api/shares/$shareId/guest-notice")

    /** A second guest identity on [shareId] that uploaded nothing — its token, as its client holds it. */
    private fun otherGuest(shareId: String): String {
        val token = me.raddatz.veyq.core.crypto.Tokens.random()
        QuarkusTransaction.requiringNew().run {
            ShareIdentityEntity().apply {
                id = UUID.randomUUID().toString(); this.shareId = shareId; sealedDisplayName = "bmFtZQ=="
                tokenHash = me.raddatz.veyq.core.crypto.Tokens.hash(token)
            }.persist()
        }
        return token
    }

    private fun bytes(shareId: String, blobId: String): Response = given().`when`().get("/api/shares/$shareId/blobs/$blobId")

    private fun asServerOwner(ownerId: String) = ModerationTestKit.asOwner(ownerId)

    private fun events(caseId: String?): List<ModerationEventEntity> = QuarkusTransaction.requiringNew().call {
        if (caseId == null) ModerationEventEntity.list("caseId is null and event = ?1", "ORDER_RECEIVED")
        else ModerationEventEntity.list("caseId = ?1 order by occurredAt", caseId)
    }

    private fun statementsOf(caseId: String): List<ModerationStatementEntity> =
        QuarkusTransaction.requiringNew().call { ModerationStatementEntity.list("caseId = ?1 order by id", caseId) }

    private fun time(r: Response, path: String): Instant = OffsetDateTime.parse(r.path<String>(path)).toInstant()

    @Test
    fun aRemovalOrderOnTheAdminChannelBlocksTheShareAndItsContentWithNoKeyWhileThePublicPortAnswersAsUnknown() {
        val mod = CaseTestKit.moderator()
        val w = world()
        // Controls before the order: both guests resolve the folder and read its bytes; the member downloads them.
        resolve(w.ordered).then().statusCode(200)
        bytes(w.other, w.cover).then().statusCode(200)
        assertNull(CoverKit.downloadError(w.ownerId, w.scopeId, w.cover))

        // The Server Owner's door on the public port is an unknown route, and nothing is blocked by it.
        val refused = orderBody(w.ordered)
        ModerationTestKit.asUser(mod.id).contentType(ContentType.JSON).body(refused)
            .post("/api/instance/moderation/orders").then().statusCode(404)
        resolve(w.ordered).then().statusCode(200)

        // On the admin listener, with no key and no proof in the request, it blocks at once.
        val entered = asServerOwner(mod.id).body(orderBody(w.ordered, receivedAt = Instant.now().minusSeconds(600).toString()))
            .post("/api/instance/moderation/orders")
        entered.then().statusCode(201)
            .body("kind", equalTo("REMOVAL")).body("found", equalTo(true)).body("shareId", equalTo(w.ordered))
            .body("blockedAt", notNullValue()).body("caseId", notNullValue()).body("enteredBy", equalTo(mod.id))
            .body("sealedReference", equalTo(CaseTestKit.SEALED_REFERENCE)).body("statementDeferredUntil", nullValue())
        val caseId = entered.path<String>("caseId")
        val blockedAt = time(entered, "blockedAt")
        assertEquals(blockedAt.plus(Holds.SIX_MONTHS), time(entered, "holdUntil"))

        // The ordered link answers its guest as unknown; the other link still resolves, but the content's bytes are 451,
        // and so is the member's own download.
        resolve(w.ordered).then().statusCode(404).body("code", equalTo("share_not_found"))
        resolve(w.other).then().statusCode(200)
        bytes(w.other, w.cover).then().statusCode(451).body("code", equalTo("blocked_by_operator"))
        assertEquals("blocked_by_operator", CoverKit.downloadError(w.ownerId, w.scopeId, w.cover))

        // A case of its own, decided, with no report, carrying the order, two measures and their statements (basis ORDER).
        mod.case(caseId).then().statusCode(200)
            .body("origin", equalTo("ORDER")).body("state", equalTo("DECIDED")).body("reports.size()", equalTo(0))
            .body("category", equalTo("TERRORIST_CONTENT"))
            .body("orders[0].id", equalTo(entered.path<String>("id")))
            .body("measures.size()", equalTo(2))
            .body("measures.find { it.kind == 'SHARE' }.targetId", equalTo(w.ordered))
            .body("measures.find { it.kind == 'CONTENT' }.targetId", equalTo(w.root))
            .body("measures[0].statement.basis", equalTo("ORDER"))
            .body("measures[0].statement.ground", equalTo("TERRORIST_CONTENT"))
            .body("hold.trigger", equalTo("ORDER"))
            .body("hold.floorUntil", notNullValue())
        assertTrue(events(caseId).any { it.event == "ORDER_RECEIVED" && it.targetId == entered.path<String>("id") })
        // The space's owner — the links' creator and the space's owner — is told, in the app.
        ModerationTestKit.asUser(w.ownerId).get("/api/statements").then().statusCode(200)
            .body("statements.size()", equalTo(2)).body("statements[0].basis", equalTo("ORDER"))
    }

    @Test
    fun aGuestUploaderIsToldOfARemovalOrderOnItsBlockedLinkAndNobodyElseIs() {
        val mod = CaseTestKit.moderator()
        val w = world()
        val stranger = otherGuest(w.ordered)
        // Before any order there is nothing to tell: the link's ordinary unknown answer.
        notice(w.ordered, w.guestToken).then().statusCode(404).body("code", equalTo("share_not_found"))
        val receivedAt = Instant.now().minusSeconds(900).truncatedTo(ChronoUnit.MILLIS)
        mod.order(orderBody(w.ordered, receivedAt = receivedAt.toString())).then().statusCode(201)
        // The link itself is blocked for everyone; the uploading identity is told when the order was received and where
        // to ask for the reasons or a copy.
        resolve(w.ordered).then().statusCode(404)
        val told = notice(w.ordered, w.guestToken).then().statusCode(200)
            .body("removals.size()", equalTo(1))
            // Nothing else is served to it (§ 12): the order's own statement is no moderator's statement of reasons.
            .body("statements.size()", equalTo(0))
            .extract().response()
        assertEquals(receivedAt, time(told, "removals[0].orderReceivedAt"))
        // Control: another identity's token on the same link, and an unknown one, get the link's ordinary unknown answer.
        notice(w.ordered, stranger).then().statusCode(404).body("code", equalTo("share_not_found"))
        notice(w.ordered, me.raddatz.veyq.core.crypto.Tokens.random()).then().statusCode(404).body("code", equalTo("share_not_found"))
        // The identity's token names it on its own link only.
        notice(w.other, w.guestToken).then().statusCode(404)
    }

    @Test
    fun aDeferredOrderTellsTheGuestNothingUntilItsEnd() {
        val mod = CaseTestKit.moderator()
        val w = world()
        val until = Instant.now().plus(10, ChronoUnit.DAYS)
        val caseId = mod.order(orderBody(w.ordered, deferUntil = until.toString())).then().statusCode(201).extract().path<String>("caseId")
        // The order's statements name the provision the removal relies on (DSA Art. 17(3)(d)).
        assertTrue(statementsOf(caseId).all { it.provision == AuthorityOrderHandler.REMOVAL_PROVISION })
        // While the authority's deferral runs, the guest is told nothing (TCO Art. 11(3)).
        notice(w.ordered, w.guestToken).then().statusCode(404).body("code", equalTo("share_not_found"))
        // Once it ended and the cleanup released the statement, it is told.
        purge.sweep(until.plus(1, ChronoUnit.HOURS))
        assertTrue(statementsOf(caseId).all { it.deferredUntil == null })
        notice(w.ordered, w.guestToken).then().statusCode(200).body("removals.size()", equalTo(1))
        // A lifted measure tells it nothing any more: the content is served again.
        mod.case(caseId).path<List<String>>("measures.id").forEach { mod.lift(caseId, it).then().statusCode(200) }
        notice(w.ordered, w.guestToken).then().statusCode(404)
    }

    @Test
    fun theModeratorsDoorNeedsTheKeyProofAndRunsTheSameHandler() {
        val mod = CaseTestKit.moderator()
        val w = world()
        // Without the proof: refused, nothing blocked.
        ModerationTestKit.asUser(mod.id).contentType(ContentType.JSON).body(orderBody(w.ordered))
            .post("/api/moderation/orders").then().statusCode(403).body("code", equalTo("moderation_proof_invalid"))
        resolve(w.ordered).then().statusCode(200)
        // With it: blocked.
        mod.order(orderBody(w.ordered)).then().statusCode(201).body("found", equalTo(true))
        resolve(w.ordered).then().statusCode(404)
    }

    @Test
    fun anOrdersHoldRunsToTheEndItSetsAndNoLiftOrShorterExtensionEndsItSooner() {
        val mod = CaseTestKit.moderator()
        val w = world()
        val entered = mod.order(orderBody(w.ordered)).then().statusCode(201).extract().response()
        val caseId = entered.path<String>("caseId")
        val floor = time(entered, "holdUntil")
        // An extension shorter than the order's end is refused.
        mod.extend(caseId, floor.minus(1, ChronoUnit.DAYS).toString()).then().statusCode(400)
            .body("code", equalTo("extension_out_of_bounds"))
        assertEquals(floor, holdOf(caseId)!!.expiresAt)
        // Lifting both measures (an objection upheld, a revoked order) ends no hold before the order's end, and the case
        // stays decided while it holds.
        val measures = mod.case(caseId).path<List<String>>("measures.id")
        measures.forEach { mod.lift(caseId, it).then().statusCode(200) }
        resolve(w.ordered).then().statusCode(200)
        val held = holdOf(caseId)!!
        assertEquals(floor, held.expiresAt)
        assertEquals(floor, held.floorUntil)
        mod.case(caseId).then().body("state", equalTo("DECIDED")).body("hold.endsAt", notNullValue())
        // Control: a report's case with no order ends its hold with the lift of its last measure.
        asMember(w.ownerId, w.scopeId, body(listOf(item("NODE", w.sub)))).then().statusCode(201)
        val reported = openCase("NODE", w.sub)!!.id
        val measure = mod.take(reported, "CONTENT", targetId = w.sub).then().statusCode(201).extract().path<String>("measures[0].id")
        mod.lift(reported, measure).then().statusCode(200)
        assertNull(holdOf(reported))
        mod.case(reported).then().body("state", equalTo("CLOSED"))
    }

    @Test
    fun aPreservationOrderAndARequestForInformationHoldWithoutABlockForTheirDurations() {
        val mod = CaseTestKit.moderator()
        val w = world()
        val received = Instant.parse("2026-10-18T07:00:00Z")
        val preserve = mod.order(orderBody(w.ordered, kind = "PRESERVATION", receivedAt = received.toString())).then().statusCode(201)
            .body("blockedAt", nullValue()).extract().response()
        assertEquals(received.plus(Holds.PRESERVATION), time(preserve, "holdUntil"))
        resolve(w.ordered).then().statusCode(200)
        mod.case(preserve.path("caseId")).then().body("measures.size()", equalTo(0)).body("state", equalTo("DECIDED"))
        val named = mod.order(orderBody(w.other, kind = "INFORMATION", receivedAt = received.toString(), holdDays = 30)).then()
            .statusCode(201).extract().response()
        assertEquals(received.plus(30, ChronoUnit.DAYS), time(named, "holdUntil"))
        val unnamed = mod.order(orderBody(w.other, kind = "INFORMATION", receivedAt = received.toString())).then()
            .statusCode(201).extract().response()
        assertEquals(received.plus(Holds.SIX_MONTHS), time(unnamed, "holdUntil"))
        resolve(w.other).then().statusCode(200)
    }

    @Test
    fun anUnknownShareIsRecordedWithNoCaseAndAMalformedOrderIsRefused() {
        val mod = CaseTestKit.moderator()
        val w = world()
        val unknown = TestIds.shareId()
        val id = UUID.randomUUID().toString()
        mod.order(orderBody(unknown, orderId = id)).then().statusCode(201)
            .body("found", equalTo(false)).body("caseId", nullValue()).body("blockedAt", nullValue()).body("holdUntil", nullValue())
        mod.orders().then().statusCode(200).body("orders.find { it.id == '$id' }.shareId", equalTo(unknown))
        assertTrue(events(null).any { it.targetId == id })
        // The same order again — a retry — changes nothing.
        mod.order(orderBody(unknown, orderId = id)).then().statusCode(409).body("code", equalTo("order_exists"))
        // The kind's own fields, the deferral's bounds and the epoch, each refused alone; the control passes.
        mod.order(orderBody(w.ordered, holdDays = 30)).then().statusCode(400).body("code", equalTo("invalid_request"))
        mod.order(orderBody(w.ordered, kind = "PRESERVATION", deferUntil = Instant.now().plus(7, ChronoUnit.DAYS).toString()))
            .then().statusCode(400).body("code", equalTo("invalid_request"))
        mod.order(orderBody(w.ordered, deferUntil = Instant.now().plus(43, ChronoUnit.DAYS).toString()))
            .then().statusCode(400).body("code", equalTo("deferral_out_of_bounds"))
        mod.order(orderBody(w.ordered, receivedAt = Instant.now().plus(1, ChronoUnit.HOURS).toString()))
            .then().statusCode(400).body("code", equalTo("invalid_request"))
        mod.order(orderBody(w.ordered, epoch = 2)).then().statusCode(409).body("code", equalTo("moderation_epoch_changed"))
        resolve(w.ordered).then().statusCode(200)
        mod.order(orderBody(w.ordered, deferUntil = Instant.now().plus(41, ChronoUnit.DAYS).toString())).then().statusCode(201)
    }

    @Test
    fun aDeferredStatementDoesNotGoOutBeforeItsEndAndDoesAfterIt() {
        val mod = CaseTestKit.moderator()
        val w = world()
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val until = now.plus(10, ChronoUnit.DAYS)
        val entered = mod.order(orderBody(w.ordered, deferUntil = until.toString())).then().statusCode(201)
            .body("statementDeferredUntil", notNullValue()).extract().response()
        val caseId = entered.path<String>("caseId")
        // Held back: the owner reads nothing, no STATEMENT_SENT, and the hold reaches six months past the deferral's end.
        ModerationTestKit.asUser(w.ownerId).get("/api/statements").then().body("statements.size()", equalTo(0))
        assertTrue(events(caseId).none { it.event == "STATEMENT_SENT" })
        assertTrue(events(caseId).any { it.event == "STATEMENT_DEFERRED" })
        assertTrue(!holdOf(caseId)!!.expiresAt.isBefore(until.plus(Holds.SIX_MONTHS)))
        // Two blocks, two statements, both held: one is renewed, the other is the control that goes out at its own end.
        val (renewedOne, control) = statementsOf(caseId)
        assertNull(renewedOne.sentAt)
        assertNull(control.sentAt)

        // Renewed once, at most six weeks ahead; a second renewal is refused.
        mod.renewDeferral(caseId, renewedOne.id, now.plus(50, ChronoUnit.DAYS).toString()).then().statusCode(400)
            .body("code", equalTo("deferral_out_of_bounds"))
        val renewedUntil = now.plus(20, ChronoUnit.DAYS)
        mod.renewDeferral(caseId, renewedOne.id, renewedUntil.toString()).then().statusCode(200)
            .body("measures.find { it.statement.id == '${renewedOne.id}' }.statement.renewed", equalTo(true))
        mod.renewDeferral(caseId, renewedOne.id, now.plus(30, ChronoUnit.DAYS).toString()).then().statusCode(409)
            .body("code", equalTo("deferral_renewed"))

        // The cleanup before the first end: both still held.
        purge.sweep(until.minus(1, ChronoUnit.HOURS))
        assertTrue(statementsOf(caseId).all { it.sentAt == null })
        ModerationTestKit.asUser(w.ownerId).get("/api/statements").then().body("statements.size()", equalTo(0))
        // After the first end, before the renewed one: the control goes out, the renewed one stays held.
        purge.sweep(renewedUntil.minus(1, ChronoUnit.DAYS))
        assertNotNull(statementsOf(caseId).single { it.id == control.id }.sentAt)
        assertNull(statementsOf(caseId).single { it.id == renewedOne.id }.sentAt)
        ModerationTestKit.asUser(w.ownerId).get("/api/statements").then().body("statements.size()", equalTo(1))
        // After the renewed end: out, logged, and the hold counts from the sending.
        val after = renewedUntil.plus(1, ChronoUnit.MINUTES)
        purge.sweep(after)
        val sent = statementsOf(caseId).single { it.id == renewedOne.id }
        assertNotNull(sent.sentAt)
        assertNull(sent.deferredUntil)
        assertTrue(events(caseId).any { it.event == "STATEMENT_SENT" && it.targetId == sent.id })
        assertTrue(!holdOf(caseId)!!.expiresAt.isBefore(after.plus(Holds.SIX_MONTHS)))
        // A statement already out takes no renewal.
        mod.renewDeferral(caseId, sent.id, after.plus(5, ChronoUnit.DAYS).toString()).then().statusCode(409)
            .body("code", equalTo("statement_not_deferred"))
    }

    @Test
    fun aModeratorsMeasureCarriesAnAuthoritysDeferral() {
        val mod = CaseTestKit.moderator()
        val w = world()
        asMember(w.ownerId, w.scopeId, body(listOf(item("NODE", w.root)))).then().statusCode(201)
        val caseId = openCase("NODE", w.root)!!.id
        mod.take(caseId, "CONTENT", targetId = w.root, deferUntil = Instant.now().plus(60, ChronoUnit.DAYS).toString())
            .then().statusCode(400).body("code", equalTo("deferral_out_of_bounds"))
        mod.take(caseId, "CONTENT", targetId = w.root, deferUntil = Instant.now().plus(14, ChronoUnit.DAYS).toString())
            .then().statusCode(201)
            .body("measures[0].statement.sentAt", nullValue())
            .body("measures[0].statement.deferredUntil", notNullValue())
        ModerationTestKit.asUser(w.ownerId).get("/api/statements").then().body("statements.size()", equalTo(0))
        mod.queue().then().body("cases.find { it.id == '$caseId' }.statementDeferredUntil", notNullValue())
    }

    @Test
    fun theExportCarriesTheClearFieldsAndTheHeldGuestWriteRecordAndAHandoverChangesNoHold() {
        val mod = CaseTestKit.moderator()
        val w = world()
        val observed = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(3600)
        QuarkusTransaction.requiringNew().run {
            GuestWriteRecordEntity().apply {
                id = UUID.randomUUID().toString()
                shareIdentityId = w.guestIdentity
                objectKind = "NODE"
                objectId = w.sub
                clientIp = "203.0.113.77"
                clientPort = 51234
                requestOrigin = "https://pages.attacker.example"
                observedAt = observed
                expiresAt = observed.plus(7, ChronoUnit.DAYS)
            }.persist()
            // Control: a later write whose request carried no Origin.
            GuestWriteRecordEntity().apply {
                id = UUID.randomUUID().toString()
                shareIdentityId = w.guestIdentity
                objectKind = "NODE"
                objectId = w.sub
                clientIp = "203.0.113.78"
                observedAt = observed.plusSeconds(1)
                expiresAt = observed.plus(7, ChronoUnit.DAYS)
            }.persist()
        }
        val caseId = mod.order(orderBody(w.ordered)).then().statusCode(201).extract().path<String>("caseId")
        // Behind the key proof only.
        ModerationTestKit.asUser(mod.id).get("/api/moderation/cases/$caseId/export").then().statusCode(403)
        val export = mod.export(caseId).then().statusCode(200)
            .body("caseId", equalTo(caseId)).body("category", equalTo("TERRORIST_CONTENT")).body("origin", equalTo("ORDER"))
            .body("timeZone", equalTo("UTC")).body("held", equalTo(true)).body("reportedAt.size()", equalTo(0))
            .body("measures.size()", equalTo(2))
            .body("uploaders.find { it.kind == 'GUEST' }.id", equalTo(w.guestIdentity))
            .body("uploaders.find { it.kind == 'GUEST' }.shareId", equalTo(w.ordered))
            .body("guestWrites.size()", equalTo(2))
            .body("guestWrites[0].address", equalTo("203.0.113.77"))
            .body("guestWrites[0].port", equalTo(51234))
            .body("guestWrites[0].origin", equalTo("https://pages.attacker.example"))
            .body("guestWrites[1].address", equalTo("203.0.113.78"))
            .body("guestWrites[1].origin", nullValue())
            .body("guestWrites[0].identityId", equalTo(w.guestIdentity))
            .body("guestWrites[0].objectId", equalTo(w.sub))
            .extract().response()
        // The time is the one observed, to the millisecond, in UTC — not shifted by the server's own zone.
        assertEquals(observed, time(export, "guestWrites[0].observedAt"))
        // Nothing of content: no key, no sealed field, no blob of the case is in the export.
        assertTrue(!export.asString().contains(w.cover))
        mod.export(UUID.randomUUID().toString()).then().statusCode(404)

        // A handover is logged with its reference sealed, and changes no hold.
        val before = holdOf(caseId)!!.expiresAt
        mod.handover(caseId, "REPORTED").then().statusCode(201)
        mod.handover(caseId, "INFORMATION_GIVEN").then().statusCode(201)
        assertEquals(before, holdOf(caseId)!!.expiresAt)
        val logged = events(caseId)
        assertTrue(logged.any { it.event == "HANDED_OVER" && it.sealedText == CaseTestKit.SEALED_NOTE })
        assertTrue(logged.any { it.event == "INFORMATION_GIVEN" && it.sealedText == CaseTestKit.SEALED_NOTE })
    }
}
