package me.raddatz.veyq.core.content

import me.raddatz.veyq.core.blobstore.RecordingBlobStore
import me.raddatz.veyq.core.blobstore.StoreReclaimSweep
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.sharing.GuestTreeShare
import me.raddatz.veyq.core.sharing.ShareWriteProof
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import jakarta.inject.Inject
import java.util.Base64
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.hasItem
import org.hamcrest.CoreMatchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Slice tests for the node trash (uc.memory.delete-node, ADR 0061): a delete soft-deletes the whole subtree, the
 * space's trash lists the deleted ROOTS, restore brings the subtree back intact, and `NodeSubtreePurge`
 * hard-erases it past the retention window. The share half lives in
 * `ShareDeletedNodeSliceTest` (`:apps:memories`) — the two guest paths need their own controls.
 *
 * Four of these pin decisions that are load-bearing precisely because they look like details:
 * - the slug is freed by the **write** (`slug_token := the node's own id`), because `ux_node_parent_slug` is a
 *   plain unique index spanning trashed rows and MariaDB has no filtered index;
 * - `slugTaken` therefore must **keep seeing** trashed rows, or validation passes and the database rejects the
 *   insert afterwards — a clean 409 turned into a 500;
 * - the delete's own traversal must **reach already-trashed descendants** and re-stamp them, or the inner rows
 *   age out first and the purge erases a Memory inside a folder still sitting restorable in the trash;
 * - the cover blob is reclaimed by the **purge**, not by the delete, or a restore hands back a Memory whose
 *   cover is already gone.
 *
 * The store half is observed through the [RecordingBlobStore] seam — the in-memory store's `delete` is a no-op
 * that proves nothing — and it takes **two** components since ADR 0062: the purge records the key prefixes in
 * its own transaction, [StoreReclaimSweep] is the only thing that deletes objects, so the purge case drives both.
 * UUID-unique seeds so the JVM-lived H2 mem DB can't bleed across tests/classes.
 *
 * **Split from `NodeTrashSliceTest`** (`src/backend/CLAUDE.md`: a test lives where what it exercises runs): these methods run with core alone, so they live in
 * `:core`; the rest of the class is `NodeTrashSliceTest` in `:apps:memories`. The bodies moved verbatim.
 */
@QuarkusTest
class NodeTrashCoreSliceTest {

    @Inject
    lateinit var purge: NodeSubtreePurge

    @Inject
    lateinit var handler: NodesHandler

    /** A restore of a subtree the purge already ran on is a non-enumerable 404 (uc.memory.delete-node A2). */
    @Test
    fun restoreOfAnUnknownSubtreeIs404() {
        val (userId, scopeId) = seedMemberScope()
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"slugToken":"Z29uZQ==","parentEpoch":0}""")
            .`when`().post("/api/scopes/$scopeId/nodes/${UUID.randomUUID()}/restore")
            .then().statusCode(404).body("code", equalTo("node_not_found"))
    }

    // --- 1. the subtree leaves the tree and shows up in the space's trash ---

    // --- 2. the slug is freed immediately, by the write ---

    // --- 3. restore into a taken name is a clean rejection, not a 500 ---

    // --- 4. slugTaken must NOT learn the predicate ---

    /**
     * The trap, from both sides (inbox #777). A delete frees its root's name by writing the root's own id as its token,
     * in the slot the root keeps (`NodesStore.deleteSubtree`), and `ux_node_parent_slug` spans trashed rows. Over HTTP
     * that id is no token: a UUID's `-` is outside the contract's base64 `Pattern`, so a create claiming it is a 400 at
     * the boundary and no client reaches the collision. A handler-level caller does: through [NodesHandler.create],
     * claiming it in that slot — the scope root's and a folder's — answers `slug_taken`, the 409 a client's colliding
     * create gets, with nothing written. Teach [NodesStore.slugTaken] the `deletedAt is null` predicate and the insert
     * dies on the index at commit instead; the create's replay asks the same read, cannot explain the failure, and
     * re-throws it — a 500.
     */
    @Test
    fun aTrashRootsFreedTokenIsTakenInItsSlotAndNoClientCanSendIt() {
        val (userId, scopeId) = seedMemberScope()
        val folder = UUID.randomUUID().toString().also { create(userId, scopeId, it, parent = null).statusCode(201) }
        val atRoot = UUID.randomUUID().toString().also { create(userId, scopeId, it, parent = null).statusCode(201) }
        val inFolder = UUID.randomUUID().toString().also { create(userId, scopeId, it, parent = folder).statusCode(201) }
        for (trashed in listOf(atRoot, inFolder)) {
            given().header("Authorization", bearer(userId))
                .`when`().delete("/api/scopes/$scopeId/nodes/$trashed").then().statusCode(204)
        }

        for ((trashRoot, parent) in listOf(atRoot to null, inFolder to folder)) {
            val slot = parent ?: scopeId
            assertEquals(
                slot to trashRoot, tx { NodeEntity.findById(trashRoot)!!.let { it.parentId to it.slugToken } },
                "the trash root keeps its slot and holds its own id as its token",
            )

            // Over HTTP the freed token is refused before any handler runs.
            val viaHttp = UUID.randomUUID().toString()
            create(userId, scopeId, viaHttp, parent, token = trashRoot).statusCode(400).body("code", equalTo("invalid_request"))
            assertNull(tx { NodeEntity.findById(viaHttp) }, "the boundary's refusal wrote nothing")

            // A handler-level caller reaches the collision, and gets the clean conflict — not a commit failure.
            val viaHandler = UUID.randomUUID().toString()
            val outcome = runCatching {
                handler.create(
                    userId, scopeId, viaHandler, parent, NodeEntity.FOLDER, "Zg==", trashRoot, 0, 0, "aw==", null,
                    parentEpoch = parent?.let { 0 },
                )
            }
            assertEquals(Result.success(CreateNodeOutcome.SlugTaken), outcome, "claiming the trash root's token in slot $slot")
            val failure = outcome.getOrThrow() as HttpFailure
            assertEquals(409 to "slug_taken", failure.status.httpStatus to failure.code, "the problem a client's colliding create gets")
            assertNull(tx { NodeEntity.findById(viaHandler) }, "a refused create wrote nothing")
        }
    }

    // --- 5. the delete's traversal must reach already-trashed descendants ---

    // --- 6. the purge reclaims all three arms ---

    // --- 7. a member purges a trash root now (`purgeNode`) ---

    /**
     * A member's purge erases a trash root with its whole subtree at once (the scheduled purge's erase, the window not
     * over), and nothing else: a trashed sibling still inside its window and a live node stay. After it the subtree is
     * gone from the trash, a restore is the post-purge 404 (A2) and a second purge is a 404 too — nothing left to erase.
     */
    @Test
    fun aMemberPurgeErasesTheTrashRootAndItsSubtreeNowAndNothingElse() {
        val (userId, scopeId) = seedMemberScope()
        val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val inner = seedNode(scopeId, NodeEntity.FOLDER, parentId = folder, wrappedContentKey = CONTENT_KEY)
        val sibling = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val live = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        for (n in listOf(folder, sibling)) trash(userId, scopeId, n)

        purgeOf(userId, scopeId, folder).statusCode(204)

        assertEquals(listOf(null, null), tx { listOf(folder, inner).map { NodeEntity.findById(it) } }, "the root and its subtree are erased")
        tx {
            assertNotNull(NodeEntity.findById(sibling)?.deletedAt, "a trashed sibling in its window stays in the trash")
            assertNull(NodeEntity.findById(live)!!.deletedAt, "a live node stays live")
        }
        given().header("Authorization", bearer(userId)).`when`().get("/api/scopes/$scopeId/trash/nodes")
            .then().statusCode(200).body("id", not(hasItem(folder))).body("id", hasItem(sibling))
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"slugToken":"${slug()}","parentEpoch":0}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$folder/restore")
            .then().statusCode(404).body("code", equalTo("node_not_found"))
        purgeOf(userId, scopeId, folder).statusCode(404).body("code", equalTo("node_not_found"))
    }

    /** Destruction goes trash → purge, never in one step: a live node is a 404 and stays; the same node once trashed is
     *  purged (the control on the same predicate). */
    @Test
    fun aLiveNodeIsNotPurged() {
        val (userId, scopeId) = seedMemberScope()
        val node = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)

        purgeOf(userId, scopeId, node).statusCode(404).body("code", equalTo("node_not_found"))
        assertNull(tx { NodeEntity.findById(node)!!.deletedAt }, "the live node is untouched")

        trash(userId, scopeId, node)
        purgeOf(userId, scopeId, node).statusCode(204)
        assertNull(tx { NodeEntity.findById(node) }, "control: trashed, it is purged")
    }

    /** Only a trash root is purged — the row the trash lists. A node inside a trashed subtree goes with its root: on its
     *  own it is a 404 and nothing is erased; the root's purge then takes both. */
    @Test
    fun aNodeInsideATrashedSubtreeIsNoTrashRoot() {
        val (userId, scopeId) = seedMemberScope()
        val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val inner = seedNode(scopeId, NodeEntity.FOLDER, parentId = folder, wrappedContentKey = CONTENT_KEY)
        trash(userId, scopeId, folder)

        purgeOf(userId, scopeId, inner).statusCode(404).body("code", equalTo("node_not_found"))
        tx { listOf(folder, inner).forEach { assertNotNull(NodeEntity.findById(it)?.deletedAt, "nothing of the subtree went") } }

        purgeOf(userId, scopeId, folder).statusCode(204)
        assertEquals(listOf(null, null), tx { listOf(folder, inner).map { NodeEntity.findById(it) } }, "control: its root's purge takes both")
    }

    /**
     * Who may not: a member of ANOTHER scope — on this scope's path (the gate's 404) and on its own scope's path naming
     * this node (the node is bound to the scope it lives in: the same 404) — and a share guest, who holds no bearer (the
     * route is a member's, `401`) whatever link credentials it sends. Nothing is erased; the owner's purge (control) is.
     */
    @Test
    fun anotherScopesMemberAndAShareGuestPurgeNothing() {
        val (owner, scopeId) = seedMemberScope()
        val node = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val link = GuestTreeShare.seed(owner, scopeId, node)
        trash(owner, scopeId, node)
        val (stranger, ownScope) = seedMemberScope()

        purgeOf(stranger, scopeId, node).statusCode(404).body("code", equalTo("node_not_found"))
        purgeOf(stranger, ownScope, node).statusCode(404).body("code", equalTo("node_not_found"))
        given().header("X-Contributor-Token", link.token).header(ShareWriteProof.HEADER, ShareWriteProof.token(link.shareId))
            .`when`().post("/api/scopes/$scopeId/nodes/$node/purge").then().statusCode(401)
        assertNotNull(tx { NodeEntity.findById(node)?.deletedAt }, "the trashed node is still in the trash")

        purgeOf(owner, scopeId, node).statusCode(204)
        assertNull(tx { NodeEntity.findById(node) }, "control: a member of its scope purges it")
    }

    // --- helpers ---

    private fun trash(userId: String, scopeId: String, nodeId: String) {
        given().header("Authorization", bearer(userId)).`when`().delete("/api/scopes/$scopeId/nodes/$nodeId").then().statusCode(204)
    }

    private fun purgeOf(userId: String, scopeId: String, nodeId: String): ValidatableResponse =
        given().header("Authorization", bearer(userId)).`when`().post("/api/scopes/$scopeId/nodes/$nodeId/purge").then()

    private fun create(userId: String, scopeId: String, nodeId: String, parent: String?, token: String = slug()): ValidatableResponse {
        val parentField = parent?.let { "\"parentId\":\"$it\",\"parentEpoch\":0," } ?: ""
        return given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{$parentField"nodeId":"$nodeId","kind":"FOLDER","encryptedName":"Zg==","slugToken":"$token","orderIndex":0,"wrappedContentKey":"aw=="}""")
            .`when`().post("/api/scopes/$scopeId/nodes").then()
    }

    private fun slug(): String = Base64.getEncoder().encodeToString(UUID.randomUUID().toString().toByteArray())

    private fun <T> tx(body: () -> T): T = QuarkusTransaction.requiringNew().call(body)
}
