package me.raddatz.veyq.memories.sharing

import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Test
import me.raddatz.veyq.core.sharing.ShareWriteProof

/**
 * B1 (uc.sharing.share-note, ADR 0036 point 4): the server holds the recovery copy's epoch honestly. A share's
 * recovery copy of `K_share` is sealed under `K_SC` at the scope's **current** epoch, and the epoch is stored beside
 * it — so a create tagged with any other epoch is `409 stale_epoch` with nothing written (ADR 0024 Decision 6: a copy
 * under an epoch a removed member holds would be theirs to open). The copy is mandatory (ADR 0080 decision 12,
 * uc.sharing.share-note:share-key-recoverable-by-members-only): a create without it, a key without its epoch, or the
 * reverse, is a `400` before the handler. This proves nothing against an operator — the epoch check is a server check;
 * the operator-facing guarantee is the web `share.test.ts` W1, which a client runs with a key no link carries.
 */
@QuarkusTest
class ShareKeyRecoveryEpochSliceTest {

    private val wrap = "d3JhcC1rLW1lbS11bmRlci1zaGFyZQ=="
    private val sealedName = "c2VhbGVkLW5hbWU="
    private val shareKey = "cmVjb3ZlcmFibGUta2V5" // K_share under K_SC(epoch), opaque here

    @Test
    fun aCreateAtAnEpochOtherThanTheScopesCurrentIs409StaleEpochAndWritesNothing() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        setScopeEpoch(scopeId, 2)

        for (epoch in listOf(1, 3)) {
            val shareId = TestIds.shareId()
            create(userId, memory, shareId, """"sealedShareKey":"$shareKey","wrappedRecoveryValueKey":"d3JhcHBlZC12YWx1ZS1rZXk=","sealedShareKeyEpoch":$epoch""")
                .statusCode(409).body("code", equalTo("stale_epoch"))
            listed(userId, memory).body("find { it.shareId == '$shareId' }", nullValue())
        }
    }

    @Test
    fun aKeyWithoutItsEpochAndAnEpochWithoutItsKeyAreEach400() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)

        for (fields in listOf(""""sealedShareKey":"$shareKey","wrappedRecoveryValueKey":"d3JhcHBlZC12YWx1ZS1rZXk="""", """"sealedShareKeyEpoch":0""")) {
            val shareId = TestIds.shareId()
            // refused by the request bound before the handler (both fields are required), so no handler code
            create(userId, memory, shareId, fields).statusCode(400)
            listed(userId, memory).body("find { it.shareId == '$shareId' }", nullValue())
        }
    }

    @Test
    fun aCreateAtTheCurrentEpochSucceedsAndTheManageListServesThatEpoch() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        setScopeEpoch(scopeId, 2)
        val shareId = TestIds.shareId()

        create(userId, memory, shareId, """"sealedShareKey":"$shareKey","wrappedRecoveryValueKey":"d3JhcHBlZC12YWx1ZS1rZXk=","sealedShareKeyEpoch":2""").statusCode(201)
        listed(userId, memory)
            .body("find { it.shareId == '$shareId' }.sealedShareKey", equalTo(shareKey))
            .body("find { it.shareId == '$shareId' }.sealedShareKeyEpoch", equalTo(2))
    }

    /** uc.sharing.share-note:share-key-recoverable-by-members-only (ADR 0080 decision 12): every member can always
     *  re-show and edit a link, so a create without the copy is refused and writes nothing; the control is the same
     *  body with the copy, from the same caller on the same Memory. */
    @Test
    fun aCreateWithoutARecoveryCopyIs400AndWritesNothing() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val shareId = TestIds.shareId()

        create(userId, memory, shareId, null).statusCode(400)
        listed(userId, memory).body("find { it.shareId == '$shareId' }", nullValue())

        create(userId, memory, shareId, """"sealedShareKey":"$shareKey","wrappedRecoveryValueKey":"d3JhcHBlZC12YWx1ZS1rZXk=","sealedShareKeyEpoch":0""").statusCode(201)
    }

    // --- helpers ---

    /** Simulate a rotated scope whose re-key is done: bump its current content-key epoch, and every node's with it (a
     *  node still at the old epoch takes no new link, ADR 0109 point 6 — not what these tests are about). */
    private fun setScopeEpoch(scopeId: String, epoch: Int) =
        QuarkusTransaction.requiringNew().run {
            ScopeEntity.findById(scopeId)!!.apply { currentEpoch = epoch }.persist()
            NodeEntity.update("epoch = ?1 where scopeId = ?2", epoch, scopeId)
        }

    private fun nodeEpochOf(nodeId: String): Int = QuarkusTransaction.requiringNew().call { NodeEntity.findById(nodeId)!!.epoch }

    private fun create(userId: String, nodeId: String, shareId: String, recovery: String?) =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"shareId":"$shareId",${ShareWriteProof.field(shareId)},"nodeId":"$nodeId","role":"READ","wrap":"$wrap","sealedName":"$sealedName","nodeEpoch":${nodeEpochOf(nodeId)}${recovery?.let { ",$it" } ?: ""}}""")
            .`when`().post("/api/shares")
            .then()

    private fun listed(userId: String, nodeId: String) =
        given().header("Authorization", bearer(userId)).queryParam("nodeId", nodeId)
            .`when`().get("/api/shares")
            .then().statusCode(200)
}
