package me.raddatz.veyq.expenses.pot

import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.node.rekey.NodeRekeyStage
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds
import me.raddatz.veyq.core.platform.capabilities.TenantCapabilitySwitchFixtures
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.expenses.ExpensesNodeKinds
import me.raddatz.veyq.expenses.pot.PotWire.Rev
import me.raddatz.veyq.expenses.pot.PotWire.append
import me.raddatz.veyq.expenses.pot.PotWire.body
import me.raddatz.veyq.expenses.receipt.PotReceiptEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Expenses' part of a Pot's re-key (ADR 0109 point 2, ADR 0108 decision 4): `listPotRekey` serves every revision's
 * `K_rev` wrap, and the participant in core's `commitNodeRekey` rewrites those wraps and nothing else. **A re-key changes
 * no body**: the stage's wire carries no body field, so the one way a part could name one is a stage row of another
 * field — which the commit refuses whole (`rekey_parts_stale`, nothing landed, core's parts rolled back with it), beside
 * its control: the wraps alone land and every body stays byte-identical. A wrap not staged is `rekey_parts_missing`.
 */
@QuarkusTest
class PotRekeySliceTest {

    @Inject lateinit var stage: NodeRekeyStage

    @Test
    fun theListingServesEveryRevisionsWrapInPages() {
        val w = world()
        val first = list(w, "?limit=2").statusCode(200).body("revisions.size()", equalTo(2)).body("more", equalTo(true)).extract()
        list(w, "?limit=2&since=${first.path<String>("cursor")}").statusCode(200)
            .body("revisions.size()", equalTo(1)).body("more", equalTo(false))
        list(w, "").statusCode(200).body("revisions.wrappedRevisionKey", equalTo(List(3) { PotWire.WRAP }))
        QuarkusTransaction.requiringNew().run { MembershipEntity.update("grantedEpoch = 0 where scopeId = ?1 and userId = ?2", w.scopeId, w.owner) }
        list(w, "").statusCode(403).body("code", equalTo("grant_behind"))
    }

    @Test
    fun aPartNamingABodyIsRefusedAndLandsNothing_theWrapsAloneLandAndNoBodyMoves() {
        val w = world()
        val before = rows(w)
        val refused = UUID.randomUUID()
        stageWraps(w, refused).statusCode(204)
        // A part that names a body, written past the wire (whose stage has no body field): the commit refuses it whole.
        // Staged with that revision's wrap in one put — a put replaces an item's earlier values — so every wrap is
        // there and only the body field can refuse it.
        val ref = before.first()
        val item = "${ref.entryId}:${ref.revision}"
        stage.put(
            refused.toString(), w.scopeId, w.pot, PotRekeyRows.PART,
            mapOf(
                NodeRekeyStage.Key(item, PotRekeyRows.WRAP) to NodeRekeyStage.Value(newWrap(ref.entryId, ref.revision), null),
                NodeRekeyStage.Key(item, "revision.sealedBody") to NodeRekeyStage.Value(body('Z'), null),
            ),
        )
        commit(w, refused).statusCode(409).body("code", equalTo("rekey_parts_stale"))
        assertEquals(before, rows(w), "nothing landed: no wrap, no body")
        assertEquals(0, QuarkusTransaction.requiringNew().call { NodeEntity.findById(w.pot)!!.epoch }, "core's parts rolled back")

        val missing = UUID.randomUUID()
        stageWraps(w, missing, skip = 1).statusCode(204)
        commit(w, missing).statusCode(409).body("code", equalTo("rekey_parts_missing"))
        assertEquals(before, rows(w))

        val control = UUID.randomUUID()
        stageWraps(w, control).statusCode(204)
        commit(w, control).statusCode(204)
        val after = rows(w)
        assertEquals(before.map { it.copy(wrap = newWrap(it.entryId, it.revision)) }, after, "every wrap re-wrapped, every body byte-identical")
        assertEquals(1, QuarkusTransaction.requiringNew().call { NodeEntity.findById(w.pot)!!.epoch })
        // an open list's next poll carries the new wraps: the commit moved the Pot's item revision
        PotWire.list(w.owner, w.scopeId, w.pot, ifNoneMatch = "\"2\"").statusCode(200)
            .body("revisions.size()", equalTo(3)).body("ledgerRevision", equalTo(3))
    }

    /**
     * A receipt's `K_blob` is re-wrapped in the same participant (ADR 0109 decision 3, cr-expenses-receipts decision 3):
     * the listing serves every receipt's wrap after the revisions', a stage that misses one is `rekey_parts_missing`, one
     * naming a receipt the Pot does not hold is `rekey_parts_stale`, and the control re-wraps every receipt's blob row —
     * its stored object, size and meta untouched.
     */
    @Test
    fun aRekeyRewrapsEveryReceiptsKeyAfterTheRevisions_andMissesNone() {
        val w = world()
        val receipts = List(2) { seedReceipt(w) }.sorted()
        val page = list(w, "?limit=4").statusCode(200)
            .body("revisions.size()", equalTo(3)).body("receipts.size()", equalTo(1)).body("more", equalTo(true))
            .body("receipts[0].receiptId", equalTo(receipts[0])).body("receipts[0].wrappedBlobKey", equalTo(OLD_BLOB_WRAP)).extract()
        list(w, "?limit=4&since=${page.path<String>("cursor")}").statusCode(200)
            .body("revisions.size()", equalTo(0)).body("receipts.receiptId", equalTo(listOf(receipts[1]))).body("more", equalTo(false))
        val before = blobRows(receipts)

        val missing = UUID.randomUUID()
        stageWraps(w, missing, receipts = receipts.take(1)).statusCode(204)
        commit(w, missing).statusCode(409).body("code", equalTo("rekey_parts_missing"))
        val stale = UUID.randomUUID()
        stageWraps(w, stale, receipts = receipts + UUID.randomUUID().toString()).statusCode(204)
        commit(w, stale).statusCode(409).body("code", equalTo("rekey_parts_stale"))
        assertEquals(before, blobRows(receipts), "nothing landed")
        // a stage of nothing is no stage
        post(w, "/pots/${w.pot}/rekey/${UUID.randomUUID()}", """{"fromEpoch":0,"revisions":[]}""").statusCode(400)

        val control = UUID.randomUUID()
        stageWraps(w, control, receipts = receipts).statusCode(204)
        commit(w, control).statusCode(204)
        assertEquals(before.map { it.copy(wrap = newBlobWrap(it.receiptId)) }, blobRows(receipts), "every receipt re-wrapped, its object untouched")
    }

    /** A rotation's re-key reaches a switched-off Expenses (uc.platform.switch-application-off E1), while its append is refused. */
    @Test
    fun aRekeyLandsWhileExpensesIsSwitchedOff() {
        val w = world()
        val tenantId = QuarkusTransaction.requiringNew().call { ScopeEntity.findById(w.scopeId)!!.tenantId }
        val rekeyId = UUID.randomUUID()
        TenantCapabilitySwitchFixtures.with(tenantId, CapabilityIds.EXPENSES) {
            append(w.owner, w.scopeId, w.pot, listOf(Rev()), nodeEpoch = 0).statusCode(422).body("code", equalTo("capability_disabled"))
            stageWraps(w, rekeyId).statusCode(204)
            commit(w, rekeyId).statusCode(204)
        }
        assertEquals(1, QuarkusTransaction.requiringNew().call { NodeEntity.findById(w.pot)!!.epoch })
    }

    // --- the world --------------------------------------------------------------------------------------------------

    private data class World(val owner: String, val scopeId: String, val pot: String)

    private data class Row(val entryId: String, val revision: Long, val body: String, val wrap: String)

    /** An owner's scope with a Pot holding three revisions of two records at epoch 0; then the scope rotates to 1. */
    private fun world(): World {
        val (owner, scopeId) = seedMemberScope()
        val pot = seedNode(scopeId, ExpensesNodeKinds.POT, wrappedContentKey = CONTENT_KEY)
        val a = UUID.randomUUID().toString()
        append(owner, scopeId, pot, listOf(Rev(a, body = body('A')), Rev(body = body('B')))).statusCode(204)
        append(owner, scopeId, pot, listOf(Rev(a, 2, body = body('C')))).statusCode(204)
        QuarkusTransaction.requiringNew().run {
            ScopeEntity.findById(scopeId)!!.currentEpoch = 1
            MembershipEntity.update("grantedEpoch = 1 where scopeId = ?1 and userId = ?2", scopeId, owner)
        }
        return World(owner, scopeId, pot)
    }

    private fun rows(w: World): List<Row> = QuarkusTransaction.requiringNew().call {
        PotRevisionEntity.list("nodeId", w.pot).map { Row(it.entryId, it.revision, it.sealedBody, it.wrappedRevisionKey) }
            .sortedWith(compareBy({ it.entryId }, { it.revision }))
    }

    private fun stageWraps(w: World, rekeyId: UUID, skip: Int? = null, receipts: List<String> = emptyList()): ValidatableResponse {
        val wraps = rows(w).filterIndexed { i, _ -> i != skip }
            .joinToString(",") { """{"entryId":"${it.entryId}","revision":${it.revision},"wrappedRevisionKey":"${newWrap(it.entryId, it.revision)}"}""" }
        val receiptWraps = receipts.joinToString(",") { """{"receiptId":"$it","wrappedBlobKey":"${newBlobWrap(it)}"}""" }
        return post(w, "/pots/${w.pot}/rekey/$rekeyId", """{"fromEpoch":0,"revisions":[$wraps],"receipts":[$receiptWraps]}""")
    }

    private data class BlobRow(val receiptId: String, val storageKey: String, val byteSize: Long, val meta: String?, val wrap: String)

    /** A receipt of the world's Pot, seeded as its completion leaves it: a blob row and the receipt row naming it. */
    private fun seedReceipt(w: World): String = QuarkusTransaction.requiringNew().call {
        val blobId = UUID.randomUUID().toString()
        BlobEntity().apply {
            id = blobId; scopeId = w.scopeId; storageKey = UUID.randomUUID().toString(); chunkCount = 1; byteSize = 44
            wrappedBlobKey = OLD_BLOB_WRAP; wrappedBlobMeta = "bWV0YQ=="
        }.persist()
        PotReceiptEntity().apply {
            id = UUID.randomUUID().toString(); scopeId = w.scopeId; nodeId = w.pot; entryId = UUID.randomUUID().toString()
            this.blobId = blobId; writerUserId = w.owner; createdAt = Instant.now()
        }.also { it.persist() }.id
    }

    private fun blobRows(receipts: List<String>): List<BlobRow> = QuarkusTransaction.requiringNew().call {
        receipts.map { id ->
            val b = BlobEntity.findById(PotReceiptEntity.findById(id)!!.blobId)!!
            BlobRow(id, b.storageKey, b.byteSize, b.wrappedBlobMeta, b.wrappedBlobKey)
        }
    }

    private fun newBlobWrap(receiptId: String): String = Base64.getEncoder().encodeToString("blob-${receiptId.take(8)}".toByteArray())

    private fun commit(w: World, rekeyId: UUID): ValidatableResponse = post(
        w, "/rekey/nodes/${w.pot}/$rekeyId",
        """{"nodeEpoch":0,"epoch":1,"placementRevision":0,"metaRevision":0,"encryptedName":"bmV3LXBvdA==","slugToken":"${slug()}","wrappedContentKey":"bmV3LWtleQ=="}""",
    )

    private fun list(w: World, query: String): ValidatableResponse =
        given().header("Authorization", bearer(w.owner)).`when`().get("/api/scopes/${w.scopeId}/pots/${w.pot}/rekey$query").then()

    private fun post(w: World, path: String, body: String): ValidatableResponse =
        given().header("Authorization", bearer(w.owner)).contentType(ContentType.JSON).body(body)
            .`when`().post("/api/scopes/${w.scopeId}$path").then()

    private fun slug(): String = Base64.getEncoder().encodeToString(UUID.randomUUID().toString().toByteArray())

    private companion object {
        const val OLD_BLOB_WRAP = "b2xkLWJsb2Itd3JhcA=="
    }

    /** A distinct re-wrap per revision, so a wrap landing on the wrong revision reads back wrong. */
    private fun newWrap(entryId: String, revision: Long): String =
        Base64.getEncoder().encodeToString("new-${entryId.take(8)}-$revision".toByteArray())
}
