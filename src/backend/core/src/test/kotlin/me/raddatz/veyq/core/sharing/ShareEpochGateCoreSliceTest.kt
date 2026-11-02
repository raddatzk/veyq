package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.WRAPPED_UPLOADER_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * ADR 0114 decision 3 on the sharing writes, with core alone: a share wraps its node's key (`createShare`,
 * `reissueShare`), a guest's name is sealed under its root's key or its `K_up` to the root's drop key
 * (`createShareIdentity`) — each states that node's own epoch and is refused `409 stale_epoch` for another than the
 * stored one and `409 node_rekey_pending` while the node is due for its re-key, with nothing written; the positive
 * control is the same request once the node is re-keyed. And the guest resolve serves the root's own epoch and its
 * scope epoch (`PublicShare.rootEpoch`, `rootScopeEpoch`), in the body its tag digests, so an open page sees a re-key.
 */
@QuarkusTest
class ShareEpochGateCoreSliceTest {

    private data class World(val owner: String, val scopeId: String, val folder: String)

    /** A member's scope at epoch 2 whose folder is due, its own key at epoch 1, minted under scope epoch 1: a rotation
     *  the re-key has not brought to it yet. */
    private fun lagging(): World {
        val (owner, scopeId) = seedMemberScope()
        val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        epochs(scopeId, folder, stored = 1, current = 2)
        return World(owner, scopeId, folder)
    }

    /** The node's own epoch [stored] and the scope's [current]; a node below it is due, its key minted under the epoch
     *  before (ADR 0114 decision 4: the mark, not the comparison, is what the gate reads). */
    private fun epochs(scopeId: String, node: String, stored: Int, current: Int) = QuarkusTransaction.requiringNew().run {
        NodeEntity.findById(node)!!.apply { epoch = stored; scopeEpoch = minOf(stored, current); rekeyDue = stored < current }
        ScopeEntity.findById(scopeId)!!.currentEpoch = current
    }

    /** The node's own commit: its own epoch [to], its scope epoch the current one, no longer due. */
    private fun rekeyed(node: String, to: Int) = QuarkusTransaction.requiringNew().run {
        NodeEntity.findById(node)!!.apply { epoch = to; scopeEpoch = ScopeEntity.findById(this.scopeId)!!.currentEpoch; rekeyDue = false }
    }

    // --- createShare ------------------------------------------------------------------------------------------------

    @Test
    fun aShareStatesItsNodesEpoch() {
        val w = lagging()
        val id = TestIds.shareId()
        // The recovery copy is at the current epoch throughout: the node's epoch is the only predicate that moves.
        createShare(w, id, nodeEpoch = 0).statusCode(409).body("code", equalTo("stale_epoch"))
        createShare(w, id, nodeEpoch = 1).statusCode(409).body("code", equalTo("node_rekey_pending"))
        assertNull(QuarkusTransaction.requiringNew().call { ShareEntity.findById(id) }, "nothing written")

        rekeyed(w.folder, 2)
        createShare(w, id, nodeEpoch = 2).statusCode(201).body("shareId", equalTo(id))
    }

    // --- reissueShare -----------------------------------------------------------------------------------------------

    @Test
    fun aReissueStatesItsNodesEpoch() {
        val (owner, scopeId) = seedMemberScope()
        val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val w = World(owner, scopeId, folder)
        val old = TestIds.shareId()
        createShare(w, old, nodeEpoch = 0, copyEpoch = 0).statusCode(201)
        epochs(scopeId, folder, stored = 1, current = 2)

        val renewed = TestIds.shareId()
        reissue(w, old, renewed, nodeEpoch = 0).statusCode(409).body("code", equalTo("stale_epoch"))
        reissue(w, old, renewed, nodeEpoch = 1).statusCode(409).body("code", equalTo("node_rekey_pending"))
        assertNull(QuarkusTransaction.requiringNew().call { ShareEntity.findById(renewed) }, "nothing written")
        assertEquals(false, QuarkusTransaction.requiringNew().call { ShareEntity.findById(old)!!.deleted }, "the old link is not spent")

        rekeyed(folder, 2)
        reissue(w, old, renewed, nodeEpoch = 2).statusCode(201).body("shareId", equalTo(renewed))
    }

    // --- createShareIdentity ----------------------------------------------------------------------------------------

    @Test
    fun aReadWriteGuestsNameStatesTheRootsEpoch() = identityGate(ShareEntity.READ_WRITE)

    @Test
    fun aWriteOnlyGuestsKeyStatesTheRootsEpoch() = identityGate(ShareEntity.WRITE_ONLY)

    private fun identityGate(role: String) {
        val w = lagging()
        val link = GuestTreeShare.seed(w.owner, w.scopeId, w.folder, role)
        val identity = TestIds.uuid()
        nameYourself(link.shareId, identity, role, nodeEpoch = 0).statusCode(409).body("code", equalTo("stale_epoch"))
        nameYourself(link.shareId, identity, role, nodeEpoch = 1).statusCode(409).body("code", equalTo("node_rekey_pending"))
        assertNull(QuarkusTransaction.requiringNew().call { ShareIdentityEntity.findById(identity) }, "nothing stored")

        rekeyed(w.folder, 2)
        nameYourself(link.shareId, identity, role, nodeEpoch = 2).statusCode(201).body("identityId", equalTo(identity))
    }

    // --- createShareNode -------------------------------------------------------------------------------------------

    /**
     * A guest knows no scope epoch: it mints a new node's key under its parent's served `scopeEpoch` (`guestTree.ts`).
     * Under a parent still due that is the parent's `node_rekey_pending` — the guest waits until a member's device
     * re-keyed it (ADR 0111 decision 11) — never `stale_epoch`, which a re-read cannot cure while the parent is due. A
     * parent epoch other than the parent's own is still stale, and once the parent is re-keyed a scope epoch from before
     * is stale and the current one lands (the controls).
     */
    @Test
    fun aGuestsCreateUnderAParentNotReKeyedYetWaitsForIt() {
        val w = lagging()
        val link = GuestTreeShare.seed(w.owner, w.scopeId, w.folder, ShareEntity.READ_WRITE)
        GuestTreeShare.create(link, w.folder, NodeEntity.FOLDER, scopeEpoch = 1, parentEpoch = 1).statusCode(409).body("code", equalTo("node_rekey_pending"))
        GuestTreeShare.create(link, w.folder, NodeEntity.FOLDER, scopeEpoch = 1, parentEpoch = 0).statusCode(409).body("code", equalTo("stale_epoch"))
        assertEquals(0L, QuarkusTransaction.requiringNew().call { NodeEntity.count("parentId", w.folder) }, "nothing written")

        rekeyed(w.folder, 2)
        GuestTreeShare.create(link, w.folder, NodeEntity.FOLDER, scopeEpoch = 1, parentEpoch = 2).statusCode(409).body("code", equalTo("stale_epoch"))
        GuestTreeShare.create(link, w.folder, NodeEntity.FOLDER, scopeEpoch = 2, parentEpoch = 1).statusCode(409).body("code", equalTo("stale_epoch"))
        val created = GuestTreeShare.create(link, w.folder, NodeEntity.FOLDER, scopeEpoch = 2, parentEpoch = 2).statusCode(201)
            .extract().path<String>("nodeId")
        QuarkusTransaction.requiringNew().run {
            val node = NodeEntity.findById(created)!!
            assertEquals(0, node.epoch, "a new key's own epoch starts at 0"); assertEquals(2, node.scopeEpoch)
        }
    }

    // --- PublicShare.rootEpoch --------------------------------------------------------------------------------------

    @Test
    fun theResolveServesTheRootsEpochForEveryUnlockedRoleAndItsTagMovesWithIt() {
        val (owner, scopeId) = seedMemberScope()
        val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        epochs(scopeId, folder, stored = 3, current = 3)
        for (role in listOf(ShareEntity.READ, ShareEntity.READ_WRITE, ShareEntity.WRITE_ONLY)) {
            val link = GuestTreeShare.seed(owner, scopeId, folder, role)
            given().`when`().get("/api/shares/${link.shareId}").then().statusCode(200).body("rootEpoch", equalTo(3))
                .body("rootScopeEpoch", equalTo(3))
        }

        // The conditional resolve's tag digests the body: a re-key of the root moves it, and the page re-reads.
        val link = GuestTreeShare.seed(owner, scopeId, folder, ShareEntity.READ)
        val before = given().`when`().get("/api/shares/${link.shareId}").then().statusCode(200).extract().header("ETag")
        given().header("If-None-Match", before).`when`().get("/api/shares/${link.shareId}").then().statusCode(304)
        epochs(scopeId, folder, stored = 4, current = 4)
        val after = given().header("If-None-Match", before).`when`().get("/api/shares/${link.shareId}").then()
            .statusCode(200).body("rootEpoch", equalTo(4)).extract().header("ETag")
        assertNotEquals(before, after)

        // A locked response names none.
        QuarkusTransaction.requiringNew().run {
            ShareEntity.findById(link.shareId)!!.apply { passwordRequired = true; pwSalt = "c2FsdA=="; pwVerifier = "dmVyaWZpZXI=" }
        }
        given().`when`().get("/api/shares/${link.shareId}").then().statusCode(200)
            .body("passwordRequired", equalTo(true)).body("rootEpoch", nullValue())
    }

    // --- HTTP -------------------------------------------------------------------------------------------------------

    private fun createShare(w: World, shareId: String, nodeEpoch: Int, copyEpoch: Int = 2): ValidatableResponse =
        given().header("Authorization", bearer(w.owner)).contentType(ContentType.JSON)
            .body(
                """{"shareId":"$shareId",${ShareWriteProof.field(shareId)},"nodeId":"${w.folder}","role":"READ","wrap":"d3JhcA==",""" +
                    """"sealedName":"c2VhbGVk",${ShareWriteProof.recovery(epoch = copyEpoch, nodeEpoch = nodeEpoch)}}""",
            )
            .`when`().post("/api/shares").then()

    private fun reissue(w: World, old: String, renewed: String, nodeEpoch: Int): ValidatableResponse =
        given().header("Authorization", bearer(w.owner)).contentType(ContentType.JSON)
            .body(
                """{"shareId":"$renewed",${ShareWriteProof.field(renewed)},"wrap":"d3JhcA==","sealedName":"c2VhbGVk",""" +
                    """${ShareWriteProof.recovery(epoch = 2, nodeEpoch = nodeEpoch)}}""",
            )
            .`when`().post("/api/shares/$old/reissue").then()

    private fun nameYourself(shareId: String, identityId: String, role: String, nodeEpoch: Int): ValidatableResponse {
        val key = if (role == ShareEntity.WRITE_ONLY) ""","wrappedIdentityKey":"$WRAPPED_UPLOADER_KEY"""" else ""
        return given().contentType(ContentType.JSON).header(ShareWriteProof.HEADER, ShareWriteProof.token(shareId))
            .body("""{"identityId":"$identityId","sealedDisplayName":"bmFtZQ==","nodeEpoch":$nodeEpoch$key}""")
            .`when`().post("/api/shares/$shareId/identities").then()
    }
}
