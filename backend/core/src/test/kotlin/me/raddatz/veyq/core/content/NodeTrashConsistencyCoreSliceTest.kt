package me.raddatz.veyq.core.content

import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import jakarta.inject.Inject
import java.time.Instant
import java.util.Base64
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.hasItem
import org.hamcrest.CoreMatchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * ADR 0080 Decision 8: with keyed folders a child's wrap lives under its parent's key, so a child row must never outlive
 * the parent whose key wraps its own, and never be live under a trashed one. Delete, restore and purge are subtree-wide
 * on ONE marker (`NodesStore.deleteSubtree` re-stamps every descendant, a narrower earlier delete's included; a restore
 * clears the whole subtree; `NodeSubtreePurge` erases every row the marker selects), and create, move and restore
 * refuse a trashed parent. `veyq_node.parent_id` has no foreign key, so these tests are the guarantee.
 *
 * Bite tests 6 (the create half; the move half is `NodePlacementCoreSliceTest.aMoveIntoATrashedParentIs422`), 7 and 8,
 * and inbox #357's two: a delete frees only its root's slug, so a restored descendant comes back with its own.
 * The races (a trash overlapping a create, a move or a restore under it) are `NodeTreeRaceCoreSliceTest` and
 * `NodeTreeRaceSliceTest`. Core alone: every node is a `FOLDER`.
 */
@QuarkusTest
class NodeTrashConsistencyCoreSliceTest {

    @Inject lateinit var purge: NodeSubtreePurge

    @Inject lateinit var config: VeyqConfig

    /** Bite test 6, the create half: a node created under a trashed parent would wrap its key under a key the purge
     *  erases with its row. Refused as `illegal_structure`, nothing written. */
    @Test
    fun aCreateUnderATrashedParentIs422() {
        val t = tree()
        delete(t, t.folder)
        val child = UUID.randomUUID().toString()
        create(t, child, parent = t.folder).statusCode(422).body("code", equalTo("illegal_structure"))
        assertNull(tx { NodeEntity.findById(child) }, "nothing written")

        // control: the same create under a live folder lands
        val live = UUID.randomUUID().toString()
        create(t, UUID.randomUUID().toString(), parent = null).statusCode(201).extract().path<String>("nodeId").let { parent ->
            create(t, live, parent = parent).statusCode(201)
        }
    }

    /**
     * Bite test 7: a folder purged whose child — or grandchild — a narrower delete trashed EARLIER leaves no row of
     * the subtree behind, and no row whose parent is gone. Driven through the scheduled purge after the retention
     * passed: every marker of the scope is moved back by the same span, so their order is the one the deletes left.
     */
    @Test
    fun aPurgedFolderLeavesNoRowOfASubtreeANarrowerDeleteTrashedEarlier() {
        for (earlier in listOf(Tree::sub, Tree::leaf)) {
            val t = tree()
            val survivor = UUID.randomUUID().toString().also { create(t, it, parent = null).statusCode(201) }
            delete(t, earlier(t))
            delete(t, t.folder)
            ageEveryMarker(t.scopeId)

            purge.run()

            tx {
                listOf(t.folder, t.sub, t.leaf).forEach {
                    assertNull(NodeEntity.findById(it), "${earlier.name} trashed first: a row of the purged subtree survived")
                }
                assertNotNull(NodeEntity.findById(survivor), "control: a live node of the scope is untouched")
            }
            assertNoOrphan(t.scopeId)
        }
    }

    /**
     * Bite test 8: a node whose parent is trashed does not restore on its own — live under a trashed parent it is in no
     * listing, and the purge erases its parent's key with the parent. `404 node_not_found` (the restore of a node that
     * is no trash root), nothing changed. Both ways it got there: trashed with the parent, or trashed earlier.
     */
    @Test
    fun aChildWhoseParentIsTrashedDoesNotRestore() {
        for (earlier in listOf(false, true)) {
            val t = tree()
            if (earlier) delete(t, t.sub)
            delete(t, t.folder)
            val before = markers(t)

            restore(t, t.sub).statusCode(404).body("code", equalTo("node_not_found"))
            restore(t, t.leaf).statusCode(404).body("code", equalTo("node_not_found"))

            assertEquals(before, markers(t), "sub trashed earlier=$earlier: a refused restore changed a marker")
            assertTrue(before.all { it != null }, "sub trashed earlier=$earlier: the whole subtree is trashed")
        }
    }

    /**
     * A restore of the parent brings back its whole subtree on its one marker — a child an earlier, narrower delete
     * trashed included, because the parent's delete re-stamped it — and leaves no live node under a trashed one. Only
     * the restored root's slug token is the client's new one.
     */
    @Test
    fun restoringTheParentBringsBackTheWholeSubtreeOnItsOneMarker() {
        val t = tree()
        delete(t, t.sub)
        delete(t, t.folder)
        val marker = markers(t).toSet()
        assertEquals(1, marker.size, "the folder's delete re-stamped the earlier-trashed child: one marker")

        val token = slug()
        restore(t, t.folder, token).statusCode(204)

        assertEquals(listOf(null, null, null), markers(t), "the whole subtree is live again")
        given().header("Authorization", bearer(t.userId)).`when`().get("/api/scopes/${t.scopeId}/nodes").then().statusCode(200)
            .body("id", hasItem(t.folder)).body("id", hasItem(t.sub)).body("id", hasItem(t.leaf))
        given().header("Authorization", bearer(t.userId)).`when`().get("/api/scopes/${t.scopeId}/trash/nodes").then().statusCode(200)
            .body("id", not(hasItem(t.folder))).body("id", not(hasItem(t.sub)))
        assertEquals(token, tx { NodeEntity.findById(t.folder)!!.slugToken })
        assertNoOrphan(t.scopeId)
    }

    /**
     * inbox #357: a delete frees only its ROOT's name — the one name under a live parent. Every descendant's name lives
     * under a trashed parent, where nothing can claim it while the subtree sits in the trash, so it keeps its token and
     * comes back with it: after the restore a new sibling taking a descendant's name is the ordinary `slug_taken`.
     * Freed too, the descendant would return holding no name, and the sibling would land beside it as a duplicate.
     */
    @Test
    fun aRestoredDescendantKeepsItsNameAndASiblingCannotTakeIt() {
        val t = tree()
        delete(t, t.folder)
        assertEquals(listOf(t.folder, t.subToken, t.leafToken), tokens(t), "the delete freed the root's name only")

        restore(t, t.folder).statusCode(204)

        assertEquals(listOf(t.subToken, t.leafToken), tokens(t).drop(1), "the descendants came back with their own tokens")
        create(t, UUID.randomUUID().toString(), parent = t.folder, token = t.subToken).statusCode(409).body("code", equalTo("slug_taken"))
        create(t, UUID.randomUUID().toString(), parent = t.sub, token = t.leafToken).statusCode(409).body("code", equalTo("slug_taken"))
        assertNoOrphan(t.scopeId)
    }

    /**
     * inbox #357, the earlier-trashed descendant: `sub` trashed on its own frees its name under the then-live folder, a
     * new sibling takes it, then the folder goes and comes back. `leaf` — a descendant of BOTH deletes — keeps its
     * token through both and the restore. `sub` itself returns holding its own delete's freed token (its id): the
     * server cannot re-derive a slug, and whether that node should stay in the trash or be re-named by the client is
     * the part of #357 still open, so this test pins neither.
     */
    @Test
    fun aDescendantOfTwoDeletesKeepsItsTokenThroughBoth() {
        val t = tree()
        delete(t, t.sub)
        create(t, UUID.randomUUID().toString(), parent = t.folder, token = t.subToken).statusCode(201)
        delete(t, t.folder)

        restore(t, t.folder).statusCode(204)

        assertEquals(t.leafToken, tokens(t)[2], "the leaf under the earlier-trashed sub kept its token")
        create(t, UUID.randomUUID().toString(), parent = t.sub, token = t.leafToken).statusCode(409).body("code", equalTo("slug_taken"))
        assertNoOrphan(t.scopeId)
    }

    // --- helpers ---

    /** folder (root) > sub > leaf, all `FOLDER`, created over HTTP; the two descendants' slug tokens kept. */
    private data class Tree(
        val userId: String, val scopeId: String, val folder: String, val sub: String, val leaf: String,
        val subToken: String, val leafToken: String,
    )

    private fun tree(): Tree {
        val (userId, scopeId) = seedMemberScope()
        val ids = List(3) { UUID.randomUUID().toString() }
        val t = Tree(userId, scopeId, ids[0], ids[1], ids[2], slug(), slug())
        create(t, t.folder, parent = null).statusCode(201)
        create(t, t.sub, parent = t.folder, token = t.subToken).statusCode(201)
        create(t, t.leaf, parent = t.sub, token = t.leafToken).statusCode(201)
        return t
    }

    private fun create(t: Tree, nodeId: String, parent: String?, token: String = slug()): ValidatableResponse {
        val parentField = parent?.let { "\"parentId\":\"$it\"," } ?: ""
        return given().header("Authorization", bearer(t.userId)).contentType(ContentType.JSON)
            .body("""{$parentField"nodeId":"$nodeId","kind":"FOLDER","encryptedName":"Zg==","slugToken":"$token","orderIndex":0,"wrappedContentKey":"aw=="}""")
            .`when`().post("/api/scopes/${t.scopeId}/nodes").then()
    }

    private fun tokens(t: Tree): List<String> = tx { listOf(t.folder, t.sub, t.leaf).map { NodeEntity.findById(it)!!.slugToken } }

    private fun delete(t: Tree, nodeId: String) {
        given().header("Authorization", bearer(t.userId)).`when`().delete("/api/scopes/${t.scopeId}/nodes/$nodeId").then().statusCode(204)
    }

    private fun restore(t: Tree, nodeId: String, token: String = slug()): ValidatableResponse =
        given().header("Authorization", bearer(t.userId)).contentType(ContentType.JSON).body("""{"slugToken":"$token"}""")
            .`when`().post("/api/scopes/${t.scopeId}/nodes/$nodeId/restore").then()

    private fun markers(t: Tree): List<Instant?> = tx { listOf(t.folder, t.sub, t.leaf).map { NodeEntity.findById(it)!!.deletedAt } }

    /** The retention passes: every trashed row of the scope moves back by the same span, keeping the deletes' order. */
    private fun ageEveryMarker(scopeId: String) {
        val span = config.node().trash().retention().plusSeconds(60)
        tx { NodeEntity.find("scopeId = ?1 and deletedAt is not null", scopeId).list().forEach { it.deletedAt = it.deletedAt!!.minus(span) } }
    }

    /** No row of the scope hangs under a parent that is gone, and none is live under a trashed one. */
    private fun assertNoOrphan(scopeId: String) = tx {
        val rows = NodeEntity.find("scopeId", scopeId).list().associateBy { it.id }
        rows.values.forEach { n ->
            if (n.parentId == scopeId) return@forEach
            val parent = rows[n.parentId]
            assertNotNull(parent, "node ${n.id} outlived its parent ${n.parentId}")
            if (n.deletedAt == null) assertNull(parent!!.deletedAt, "node ${n.id} is live under the trashed ${n.parentId}")
        }
    }

    private fun slug(): String = Base64.getEncoder().encodeToString(UUID.randomUUID().toString().toByteArray())

    private fun <T> tx(body: () -> T): T = QuarkusTransaction.requiringNew().call(body)
}
