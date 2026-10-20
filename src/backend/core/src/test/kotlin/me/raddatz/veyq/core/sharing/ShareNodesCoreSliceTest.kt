package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.sharing.GuestTreeShare.Link
import me.raddatz.veyq.core.sharing.GuestTreeShare.create
import me.raddatz.veyq.core.sharing.GuestTreeShare.place
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A `READ_WRITE` guest's tree operations on a `FOLDER` share, with core alone (ADR 0080 decisions 6, 9, 10; sub-slice
 * 5a.3 of cr-node-key-hierarchy): `createShareNode` and `updateShareNode`, behind the guest gate, structure only.
 * Bite tests 13 (out of the subtree, the root, a cycle, a WRITE_ONLY guest), 17 (a kind `NodeKindRules` refuses, and
 * before it a kind no application declares guest-creatable),
 * 19 (the root's rename) and 20 (a READ folder share exists although no application writes under it), plus what the
 * slice promises: a guest's tree is a member's tree, a create's epoch is the one it sealed with (at or below the
 * current one), a placement is compare-and-set.
 *
 * The tree: `outer` at the scope root holds the shared `root` and, beside it, `outside`; `root` holds `a` and `b`.
 */
@QuarkusTest
class ShareNodesCoreSliceTest {

    private data class Tree(
        val userId: String, val scopeId: String, val outer: String, val root: String, val outside: String,
        val a: String, val b: String, val link: Link,
    )

    /** The scope at epoch [epoch], the tree, and a link on `root`. */
    private fun tree(role: String = ShareEntity.READ_WRITE, epoch: Int = 0): Tree {
        val (userId, scopeId) = seedMemberScope()
        if (epoch != 0) QuarkusTransaction.requiringNew().run { ScopeEntity.findById(scopeId)!!.currentEpoch = epoch }
        val outer = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val root = seedNode(scopeId, NodeEntity.FOLDER, parentId = outer, wrappedContentKey = CONTENT_KEY)
        val outside = seedNode(scopeId, NodeEntity.FOLDER, parentId = outer, wrappedContentKey = CONTENT_KEY)
        val a = seedNode(scopeId, NodeEntity.FOLDER, parentId = root, wrappedContentKey = CONTENT_KEY)
        val b = seedNode(scopeId, NodeEntity.FOLDER, parentId = root, wrappedContentKey = CONTENT_KEY)
        return Tree(userId, scopeId, outer, root, outside, a, b, GuestTreeShare.seed(userId, scopeId, root, role))
    }

    // --- what a guest builds is a member's tree ---

    @Test
    fun aGuestCreatesAFolderAndANestedNodeAndAMemberListsThem() {
        val t = tree(epoch = 2)
        val f1 = UUID.randomUUID().toString()
        val f2 = UUID.randomUUID().toString()
        create(t.link, t.a, NodeEntity.FOLDER, nodeId = f1, encryptedName = "ZjEtbmFtZQ==", wrappedContentKey = "ZjEtd3JhcA==", epoch = 2)
            .statusCode(201).body("nodeId", equalTo(f1))
        create(t.link, f1, NodeEntity.FOLDER, nodeId = f2, encryptedName = "ZjItbmFtZQ==", wrappedContentKey = "ZjItd3JhcA==", epoch = 2)
            .statusCode(201).body("nodeId", equalTo(f2))

        // A member lists them as stored — the names and wraps the guest sealed, under the parents it named, at the
        // epoch it sealed with (ADR 0080 decision 6), at placement revision 0.
        given().header("Authorization", bearer(t.userId)).`when`().get("/api/scopes/${t.scopeId}/nodes").then().statusCode(200)
            .body("find { it.id == '$f1' }.parentId", equalTo(t.a))
            .body("find { it.id == '$f1' }.encryptedName", equalTo("ZjEtbmFtZQ=="))
            .body("find { it.id == '$f1' }.wrappedContentKey", equalTo("ZjEtd3JhcA=="))
            .body("find { it.id == '$f1' }.epoch", equalTo(2))
            .body("find { it.id == '$f1' }.placementRevision", equalTo(0))
            .body("find { it.id == '$f2' }.parentId", equalTo(f1))
            .body("find { it.id == '$f2' }.encryptedName", equalTo("ZjItbmFtZQ=="))
            .body("find { it.id == '$f2' }.epoch", equalTo(2))
        // …and the guest's own resolve serves them with their names and wraps, down the chain from the root's key.
        given().`when`().get("/api/shares/${t.link.shareId}").then().statusCode(200)
            .body("nodes.find { it.id == '$f2' }.encryptedName", equalTo("ZjItbmFtZQ=="))
            .body("nodes.find { it.id == '$f2' }.wrappedContentKey", equalTo("ZjItd3JhcA=="))
            .body("nodes.find { it.id == '$f2' }.parentId", equalTo(f1))
    }

    // --- the epoch is the one the guest sealed with (ADR 0080 decision 6): checked as off the root, stored as sent ---

    /** Its name and wrap bind the epoch, so the server stores the one sent — below the current one too, never the
     *  current one in its place — and no payload: `wrappedMeta` is not a guest's to set. */
    @Test
    fun anEpochBelowTheCurrentIsStoredAsSentWithNoPayload() {
        val t = tree(epoch = 3)
        val id = UUID.randomUUID().toString()
        create(t.link, t.a, NodeEntity.FOLDER, nodeId = id, epoch = 1, extra = ""","wrappedMeta":"bWV0YQ=="""")
            .statusCode(201)
        val row = QuarkusTransaction.requiringNew().call { NodeEntity.findById(id)!!.let { it.epoch to it.wrappedMeta } }
        assertEquals(1 to null, row, "stored at the epoch the guest sealed with, with no payload")
    }

    @Test
    fun anEpochAboveTheCurrentIsStaleAndStoresNothing() {
        val t = tree(epoch = 3)
        val id = UUID.randomUUID().toString()
        create(t.link, t.a, NodeEntity.FOLDER, nodeId = id, epoch = 4).statusCode(409).body("code", equalTo("stale_epoch"))
        assertNull(QuarkusTransaction.requiringNew().call { NodeEntity.findById(id) }, "nothing stored")
        create(t.link, t.a, NodeEntity.FOLDER, nodeId = id, epoch = 3).statusCode(201) // control: the current one
    }

    /** A required integer is a presence check, not a silent 0 (src/backend/CLAUDE.md). */
    @Test
    fun aCreateWithoutAnEpochIsMalformed() {
        val t = tree()
        val id = UUID.randomUUID().toString()
        given().contentType("application/json").header("X-Contributor-Token", t.link.token)
            .body("""{"nodeId":"$id","parentId":"${t.a}","kind":"FOLDER","encryptedName":"bmFtZQ==","slugToken":"${GuestTreeShare.slug()}","wrappedContentKey":"d3JhcA=="}""")
            .`when`().post("/api/shares/${t.link.shareId}/nodes").then().statusCode(400)
        assertNull(QuarkusTransaction.requiringNew().call { NodeEntity.findById(id) }, "nothing stored")
    }

    @Test
    fun aGuestMovesAndRenamesInsideTheSubtree() {
        val t = tree(epoch = 1)
        val epochBefore = epochOf(t.a)
        place(t.link, t.a, t.b, placementRevision = 0, encryptedName = "bW92ZWQ=", wrappedContentKey = "bW92ZWQtd3JhcA==")
            .statusCode(204)
        assertEquals(t.b, parentOf(t.a))
        place(t.link, t.a, t.b, placementRevision = 1, encryptedName = "cmVuYW1lZA==").statusCode(204) // a rename: same parent
        given().header("Authorization", bearer(t.userId)).`when`().get("/api/scopes/${t.scopeId}/nodes").then()
            .body("find { it.id == '${t.a}' }.encryptedName", equalTo("cmVuYW1lZA=="))
            .body("find { it.id == '${t.a}' }.placementRevision", equalTo(2))
        assertEquals(epochBefore, epochOf(t.a), "a guest's placement keeps the node's stored epoch")
    }

    @Test
    fun aStalePlacementRevisionIsAConflictAndWritesNothing() {
        val t = tree()
        place(t.link, t.a, t.b, placementRevision = 0).statusCode(204)
        place(t.link, t.a, t.root, placementRevision = 0).statusCode(409).body("code", equalTo("stale_placement"))
        assertEquals(t.b, parentOf(t.a))
        assertEquals(1L, versionOf(t.a))
    }

    @Test
    fun aTakenSlugIsAConflictOnCreateAndOnMove() {
        val t = tree()
        val slug = GuestTreeShare.slug()
        create(t.link, t.a, NodeEntity.FOLDER, slugToken = slug).statusCode(201)
        create(t.link, t.a, NodeEntity.FOLDER, slugToken = slug).statusCode(409).body("code", equalTo("slug_taken"))
        place(t.link, t.b, t.a, slugToken = slug).statusCode(409).body("code", equalTo("slug_taken"))
        assertEquals(t.root, parentOf(t.b))
    }

    // --- bite 13: never out of the subtree, never the root, never a cycle; a WRITE_ONLY guest does nothing ---

    @Test
    fun aGuestNeverMovesANodeOutOfTheSubtreeTheRootOrIntoACycle() {
        val t = tree()
        place(t.link, t.a, t.outside).statusCode(422).body("code", equalTo("illegal_structure"))
        place(t.link, t.a, t.outer).statusCode(422).body("code", equalTo("illegal_structure"))
        place(t.link, t.a, t.scopeId).statusCode(422).body("code", equalTo("illegal_structure"))
        place(t.link, t.root, t.a).statusCode(422).body("code", equalTo("illegal_structure"))
        val child = seedNode(t.scopeId, NodeEntity.FOLDER, parentId = t.a, wrappedContentKey = CONTENT_KEY)
        place(t.link, t.a, child).statusCode(422).body("code", equalTo("illegal_structure")) // a cycle
        place(t.link, t.a, t.a).statusCode(422).body("code", equalTo("illegal_structure"))
        // a node outside is no node of the link's — the same 404 as none at all
        place(t.link, t.outside, t.a).statusCode(404).body("code", equalTo("node_not_found"))
        place(t.link, UUID.randomUUID().toString(), t.a).statusCode(404).body("code", equalTo("node_not_found"))
        // nothing moved, no version spent
        assertEquals(t.root, parentOf(t.a)); assertEquals(0L, versionOf(t.a))
        assertEquals(t.outer, parentOf(t.root)); assertEquals(0L, versionOf(t.root))
        assertEquals(t.outer, parentOf(t.outside))
        // control: the same guest moves inside
        place(t.link, t.a, t.b).statusCode(204)
    }

    @Test
    fun aGuestCreatesNothingOutsideTheSubtree() {
        val t = tree()
        create(t.link, t.outside, NodeEntity.FOLDER).statusCode(422).body("code", equalTo("illegal_structure"))
        create(t.link, t.outer, NodeEntity.FOLDER).statusCode(422).body("code", equalTo("illegal_structure"))
        create(t.link, t.scopeId, NodeEntity.FOLDER).statusCode(422).body("code", equalTo("illegal_structure"))
        create(t.link, UUID.randomUUID().toString(), NodeEntity.FOLDER).statusCode(422).body("code", equalTo("illegal_structure"))
        create(t.link, t.root, NodeEntity.FOLDER).statusCode(201) // control: the root itself is a parent
    }

    @Test
    fun aWriteOnlyOrAReadGuestDoesNoTreeOperation() {
        for (role in listOf(ShareEntity.WRITE_ONLY, ShareEntity.READ)) {
            val t = tree(role = role)
            val id = UUID.randomUUID().toString()
            create(t.link, t.a, NodeEntity.FOLDER, nodeId = id).statusCode(403).body("code", equalTo("share_not_contributable"))
            place(t.link, t.a, t.b).statusCode(403).body("code", equalTo("share_not_contributable"))
            assertNull(QuarkusTransaction.requiringNew().call { NodeEntity.findById(id) }, "$role: nothing created")
            assertEquals(t.root, parentOf(t.a), "$role: nothing moved")
        }
    }

    // --- bite 17: placement by NodeKindRules ---

    /**
     * Two layers refuse a guest's create under a folder, each tested with the other held open. Core's own (owner decision
     * 2026-10-14, [FolderGuestNodeRules]): a kind no application declares guest-creatable is `illegal_scope`, before
     * any placement rule is asked — a `MEMORY` here, which no rule of core alone places either. And `NodeKindRules`
     * behind it (bite test 17): a kind declared guest-creatable ([CoreTestGuestCreatableKinds]) that no rule places is
     * still `illegal_structure`. Neither stores anything.
     */
    @Test
    fun aKindNodeKindRulesDoesNotAdmitUnderTheFolderIsRefused() {
        val t = tree()
        create(t.link, t.a, "MEMORY").statusCode(422).body("code", equalTo("illegal_scope"))
        create(t.link, t.a, CoreTestGuestCreatableKinds.KIND).statusCode(422).body("code", equalTo("illegal_structure"))
        assertEquals(0L, QuarkusTransaction.requiringNew().call { NodeEntity.count("parentId", t.a) }, "a refused create stores nothing")
        create(t.link, t.a, NodeEntity.FOLDER).statusCode(201) // control
    }

    // --- bite 19: the root is never renamed by a guest ---

    @Test
    fun aGuestNeverRenamesTheShareRoot() {
        val t = tree()
        place(t.link, t.root, t.outer).statusCode(422).body("code", equalTo("illegal_structure"))
        assertEquals(0L, versionOf(t.root))
        // control: a member renames it
        given().header("Authorization", bearer(t.userId)).contentType("application/json")
            .body("""{"parentId":"${t.outer}","encryptedName":"cmVuYW1lZA==","slugToken":"${GuestTreeShare.slug()}","wrappedContentKey":"d3JhcA==","epoch":0,"placementRevision":0}""")
            .`when`().patch("/api/scopes/${t.scopeId}/nodes/${t.root}").then().statusCode(204)
    }

    // --- the root must be live ---

    @Test
    fun aTrashedShareRootTakesNoTreeOperation() {
        val t = tree()
        given().header("Authorization", bearer(t.userId)).`when`().delete("/api/scopes/${t.scopeId}/nodes/${t.root}").then().statusCode(204)
        create(t.link, t.root, NodeEntity.FOLDER).statusCode(403).body("code", equalTo("share_not_contributable"))
        place(t.link, t.a, t.b).statusCode(403).body("code", equalTo("share_not_contributable"))
    }

    // --- the gate, mapped as the guest's other writes map it ---

    @Test
    fun theGateRefusesAsTheOtherGuestWritesDo() {
        val t = tree()
        val unknown = GuestTreeShare.Link(me.raddatz.veyq.core.TestIds.shareId(), t.link.token)
        create(unknown, t.a, NodeEntity.FOLDER).statusCode(404).body("code", equalTo("share_not_found"))
        place(unknown, t.a, t.b).statusCode(404).body("code", equalTo("share_not_found"))
        val stranger = t.link.copy(token = me.raddatz.veyq.core.crypto.Tokens.random())
        create(stranger, t.a, NodeEntity.FOLDER).statusCode(403).body("code", equalTo("share_not_contributable"))
        place(stranger, t.a, t.b).statusCode(403).body("code", equalTo("share_not_contributable"))
        given().header("Authorization", bearer(t.userId)).`when`().post("/api/shares/${t.link.shareId}/revoke").then().statusCode(204)
        create(t.link, t.a, NodeEntity.FOLDER).statusCode(404).body("code", equalTo("share_not_found"))
        place(t.link, t.a, t.b).statusCode(404).body("code", equalTo("share_not_found"))
    }

    // --- helpers ---

    private fun parentOf(id: String): String = QuarkusTransaction.requiringNew().call { NodeEntity.findById(id)!!.parentId }
    private fun versionOf(id: String): Long = QuarkusTransaction.requiringNew().call { NodeEntity.findById(id)!!.placementRevision }
    private fun epochOf(id: String): Int = QuarkusTransaction.requiringNew().call { NodeEntity.findById(id)!!.epoch }
}
