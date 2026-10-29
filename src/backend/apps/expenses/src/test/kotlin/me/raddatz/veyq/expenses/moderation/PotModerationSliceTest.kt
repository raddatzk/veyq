package me.raddatz.veyq.expenses.moderation

import me.raddatz.veyq.core.moderation.cases.CaseTestKit
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.call
import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.moderation.hold.HoldPinEntity
import me.raddatz.veyq.core.moderation.hold.HoldRowEntity
import me.raddatz.veyq.core.moderation.report.ReportTestKit.asMember
import me.raddatz.veyq.core.moderation.report.ReportTestKit.body
import me.raddatz.veyq.core.moderation.report.ReportTestKit.item
import me.raddatz.veyq.core.moderation.report.ReportTestKit.openCase
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.expenses.ExpensesNodeKinds
import me.raddatz.veyq.expenses.pot.PotRevisionEntity
import me.raddatz.veyq.expenses.pot.PotWire.Rev
import me.raddatz.veyq.expenses.pot.PotWire.append
import me.raddatz.veyq.expenses.pot.PotWire.body
import me.raddatz.veyq.expenses.pot.PotWire.erase
import me.raddatz.veyq.expenses.receipt.PotReceiptEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.Matchers.containsInAnyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * A Pot's entry as a reported item (uc.moderation.report, ADR 0108 decision 6): the report reaches the whole entry, its hold
 * copies every revision of it and nothing of another entry, and what the hold copied outlives the participant's erasure
 * and the Pot's purge — the moderator's review reads it from the snapshot, without a writer.
 */
@QuarkusTest
class PotModerationSliceTest {

    @Test
    fun aReportedEntrysHoldCopiesEveryRevisionAndOutlivesTheErasure() {
        val mod = CaseTestKit.moderator()
        val m = seedMemberScope(ScopeEntity.GROUP)
        val pot = seedNode(m.scopeId, ExpensesNodeKinds.POT, wrappedContentKey = CONTENT_KEY)
        val person = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        append(m.userId, m.scopeId, pot, listOf(Rev(person, body = body('P')), Rev(other))).statusCode(204)
        append(m.userId, m.scopeId, pot, listOf(Rev(person, 2, body = body('Q')))).statusCode(204)

        asMember(m.userId, m.scopeId, body(listOf(item("ITEM", person)))).then().statusCode(201)
        val caseId = openCase("ITEM", person)!!.id
        val held = heldRevisions()
        val personRows = QuarkusTransaction.requiringNew().call { PotRevisionEntity.list("entryId", person).map { it.id }.toSet() }
        assertEquals(personRows, held.toSet(), "every revision of the reported entry, and none of another")

        erase(m.userId, m.scopeId, pot, person, 3).statusCode(204)
        assertEquals(held.toSet(), heldRevisions().toSet(), "the erasure leaves the hold's copies")
        given().header("Authorization", bearer(m.userId)).`when`().delete("/api/scopes/${m.scopeId}/nodes/$pot").then().statusCode(204)

        mod.call("listCasePotRevisions", caseId, "GET", "/api/moderation/cases/$caseId/pots").then().statusCode(200)
            .body("revisions.size()", equalTo(2))
            .body("revisions.revision", containsInAnyOrder(1, 2))
            .body("revisions.sealedBody", containsInAnyOrder(body('P'), body('Q')))
            .body("revisions[0].nodeId", equalTo(pot)).body("revisions[0].entryId", equalTo(person))
            .body("revisions[0].containsKey('writer')", equalTo(false))
    }

    /**
     * ADR 0108 decisions 6 and 7: a report of an entry reaches its receipts too — the hold copies
     * every receipt of it and pins each one's blob — and a receipt is an `ITEM` on its own, held with its blob and nothing
     * else; the moderator reads both from the snapshot after the Pot is gone.
     */
    @Test
    fun aReportedEntryHoldsItsReceipts_aReportedReceiptHoldsItselfAlone() {
        val mod = CaseTestKit.moderator()
        val m = seedMemberScope(ScopeEntity.GROUP)
        val pot = seedNode(m.scopeId, ExpensesNodeKinds.POT, wrappedContentKey = CONTENT_KEY)
        val entry = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        append(m.userId, m.scopeId, pot, listOf(Rev(entry), Rev(other))).statusCode(204)
        val (r1, b1) = seedReceipt(m.scopeId, pot, entry, m.userId)
        val (r2, b2) = seedReceipt(m.scopeId, pot, entry, m.userId)
        val (alone, aloneBlob) = seedReceipt(m.scopeId, pot, other, m.userId)

        asMember(m.userId, m.scopeId, body(listOf(item("ITEM", entry)))).then().statusCode(201)
        val entryCase = openCase("ITEM", entry)!!.id
        assertEquals(setOf(r1, r2), held(HeldRevisions.RECEIPTS), "every receipt of the entry, and none of another")
        assertEquals(setOf(b1, b2), held("veyq_blob"), "each receipt's blob row is copied")
        assertEquals(2L, QuarkusTransaction.requiringNew().call { HoldPinEntity.count("storageKey in ?1", keysOf(listOf(b1, b2))) }, "and pinned")

        asMember(m.userId, m.scopeId, body(listOf(item("ITEM", alone)))).then().statusCode(201) // a receipt on its own
        val receiptCase = openCase("ITEM", alone)!!.id
        assertEquals(setOf(r1, r2, alone), held(HeldRevisions.RECEIPTS))
        assertEquals(setOf(b1, b2, aloneBlob), held("veyq_blob"))

        given().header("Authorization", bearer(m.userId)).`when`().delete("/api/scopes/${m.scopeId}/nodes/$pot").then().statusCode(204)
        mod.call("listCasePotRevisions", entryCase, "GET", "/api/moderation/cases/$entryCase/pots").then().statusCode(200)
            .body("receipts.receiptId", containsInAnyOrder(r1, r2)).body("receipts[0].entryId", equalTo(entry))
            .body("receipts[0].nodeId", equalTo(pot)).body("receipts[0].wrappedBlobKey", equalTo(BLOB_WRAP))
            .body("receipts[0].containsKey('writer')", equalTo(false))
        mod.call("listCasePotRevisions", receiptCase, "GET", "/api/moderation/cases/$receiptCase/pots").then().statusCode(200)
            .body("receipts.receiptId", equalTo(listOf(alone))).body("revisions.size()", equalTo(0))
    }

    @Test
    fun anIdNoPotHoldsIsNoReportableItem() {
        CaseTestKit.moderator()
        val m = seedMemberScope(ScopeEntity.GROUP)
        // an id no Pot holds is no reportable item: refused as such, beside the control above
        asMember(m.userId, m.scopeId, body(listOf(item("ITEM", UUID.randomUUID().toString())))).then().statusCode(404)
    }

    private fun held(table: String): Set<String> =
        QuarkusTransaction.requiringNew().call { HoldRowEntity.list("sourceTable", table).map { it.sourceId }.toSet() }

    private fun keysOf(blobIds: List<String>): List<String> =
        QuarkusTransaction.requiringNew().call { BlobEntity.list("id in ?1", blobIds).map { it.storageKey } }

    /** A receipt of [entryId], seeded as its completion leaves it: (receipt id, blob id). */
    private fun seedReceipt(scopeId: String, pot: String, entryId: String, writer: String): Pair<String, String> =
        QuarkusTransaction.requiringNew().call {
            val blobId = UUID.randomUUID().toString()
            BlobEntity().apply {
                id = blobId; this.scopeId = scopeId; storageKey = UUID.randomUUID().toString(); chunkCount = 1; byteSize = 44
                wrappedBlobKey = BLOB_WRAP
            }.persist()
            val receipt = PotReceiptEntity().apply {
                id = UUID.randomUUID().toString(); this.scopeId = scopeId; nodeId = pot; this.entryId = entryId
                this.blobId = blobId; writerUserId = writer; createdAt = Instant.now()
            }
            receipt.persist()
            receipt.id to blobId
        }

    private companion object {
        const val BLOB_WRAP = "d3JhcC1rLWJsb2I="
    }

    private fun heldRevisions(): List<String> =
        QuarkusTransaction.requiringNew().call { HoldRowEntity.list("sourceTable", HeldRevisions.TABLE).map { it.sourceId } }
}
