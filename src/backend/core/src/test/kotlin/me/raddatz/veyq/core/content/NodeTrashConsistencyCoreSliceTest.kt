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
 * clears the subtree but a narrower earlier delete's root and what is under it; `NodeSubtreePurge` erases every row
 * the marker selects), and create, move and restore refuse a trashed parent. `veyq_node.parent_id` has no foreign key,
 * so these tests are the guarantee.
 *
 * uc.memory.delete-node:a-trashed-parent-takes-no-child (the create half; the move half is
 * `NodePlacementCoreSliceTest.aMoveIntoATrashedParentIs422`), :a-purge-leaves-no-row-below-it and
 * :only-a-trash-root-restores, and the freed slug's rule: a delete frees only its root's slug, so a restored descendant
 * comes back with its own, and one whose slug an earlier delete freed stays in the trash; no live node ever holds a
 * freed token. The races (a trash overlapping a create, a move or a restore under it) are `NodeTreeRaceCoreSliceTest`
 * and `NodeTreeRaceSliceTest`. Core alone: every node is a `FOLDER`.
 */
@QuarkusTest
class NodeTrashConsistencyCoreSliceTest {

    @Inject lateinit var purge: NodeSubtreePurge

    @Inject lateinit var config: VeyqConfig

    /** uc.memory.delete-node:a-trashed-parent-takes-no-child, the create half: a node created under a trashed parent
     *  would wrap its key under a key the purge erases with its row. Refused as `illegal_structure`, nothing
     *  written. */
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
     * uc.memory.delete-node:a-purge-leaves-no-row-below-it: a folder purged whose child — or grandchild — a narrower
     * delete trashed EARLIER leaves no row of the subtree behind, and no row whose parent is gone. Driven through the
     * scheduled purge after the retention passed: every marker of the scope is moved back by the same span, so their
     * order is the one the deletes left.
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
     * uc.memory.delete-node:only-a-trash-root-restores: a node whose parent is trashed does not restore on its own —
     * live under a trashed parent it is in no listing, and the purge erases its parent's key with the parent. `404
     * node_not_found` (the restore of a node that is no trash root), nothing changed. Both ways it got there: trashed
     * with the parent, or trashed earlier.
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
     * The folder's delete puts its subtree on ONE marker — a child an earlier, narrower delete trashed included,
     * because the folder's delete re-stamped it. A restore of a plain subtree brings it back whole. A child an earlier
     * delete trashed holds that delete's freed token, so it does not come back (inbox #357): it and its own subtree
     * stay on the one marker, and with the folder live it is a trash root again. Either way no node is live under a
     * trashed one, and only the restored root's slug token is the client's new one.
     */
    @Test
    fun restoringTheParentBringsBackTheWholeSubtreeOnItsOneMarker() {
        for (earlier in listOf(false, true)) {
            val t = tree()
            if (earlier) delete(t, t.sub)
            delete(t, t.folder)
            val marker = markers(t).toSet()
            assertEquals(1, marker.size, "sub trashed earlier=$earlier: the folder's delete stamped the subtree on one marker")

            val token = slug()
            restore(t, t.folder, token).statusCode(204)

            if (!earlier) {
                assertEquals(listOf(null, null, null), markers(t), "a plain subtree is live again, whole")
                given().header("Authorization", bearer(t.userId)).`when`().get("/api/scopes/${t.scopeId}/nodes").then().statusCode(200)
                    .body("id", hasItem(t.folder)).body("id", hasItem(t.sub)).body("id", hasItem(t.leaf))
            } else {
                assertEquals(listOf(null) + marker + marker, markers(t), "the earlier-trashed sub stays on the one marker, with its leaf")
                given().header("Authorization", bearer(t.userId)).`when`().get("/api/scopes/${t.scopeId}/nodes").then().statusCode(200)
                    .body("id", hasItem(t.folder)).body("id", not(hasItem(t.sub))).body("id", not(hasItem(t.leaf)))
            }
            given().header("Authorization", bearer(t.userId)).`when`().get("/api/scopes/${t.scopeId}/trash/nodes").then().statusCode(200)
                .body("id", not(hasItem(t.folder))).body("id", if (earlier) hasItem(t.sub) else not(hasItem(t.sub)))
                .body("id", not(hasItem(t.leaf)))
            assertEquals(token, tx { NodeEntity.findById(t.folder)!!.slugToken })
            assertNoLiveFreedToken(t)
            assertNoOrphan(t.scopeId)

            if (earlier) {
                // the sub left behind ages out on its marker, whole, under the live folder
                ageEveryMarker(t.scopeId)
                purge.run()
                tx {
                    assertNotNull(NodeEntity.findById(t.folder), "the restored folder survives the purge")
                    assertNull(NodeEntity.findById(t.sub), "the sub left in the trash is purged")
                    assertNull(NodeEntity.findById(t.leaf), "and its leaf with it")
                }
                assertNoOrphan(t.scopeId)
            }
        }
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
        assertNoLiveFreedToken(t)
        assertNoOrphan(t.scopeId)
    }

    /**
     * inbox #357, the earlier-trashed descendant: `sub` trashed on its own frees its name under the then-live folder, a
     * new sibling takes it, then the folder goes and comes back. `sub` holds its own delete's freed token (its id) and
     * the server cannot re-derive a slug, so it does not come back with the folder — back, it would be a second node
     * of the sibling's name. It stays in the trash with `leaf`, listed as a root now that its parent is live, and
     * restores on its own like any trash root: `slug_taken` while the sibling holds the name, then with a fresh token.
     * `leaf` — a descendant of BOTH deletes — keeps its token through all of it.
     */
    @Test
    fun aDescendantWhoseNameWasFreedStaysInTheTrashWhenItsParentIsRestored() {
        val t = tree()
        delete(t, t.sub)
        val sibling = UUID.randomUUID().toString()
        create(t, sibling, parent = t.folder, token = t.subToken).statusCode(201)
        delete(t, t.folder)

        restore(t, t.folder).statusCode(204)

        assertNoLiveFreedToken(t)
        val marker = markers(t)
        assertNull(marker[0], "the folder is live")
        assertNotNull(marker[1], "the sub whose name was freed stays in the trash")
        assertEquals(marker[1], marker[2], "its leaf stays with it, on the one marker")
        assertNull(tx { NodeEntity.findById(sibling)!!.deletedAt }, "the sibling holding the name came back with the folder")
        given().header("Authorization", bearer(t.userId)).`when`().get("/api/scopes/${t.scopeId}/trash/nodes").then().statusCode(200)
            .body("id", hasItem(t.sub)).body("id", not(hasItem(t.leaf))).body("id", not(hasItem(t.folder)))
        assertNoLiveFreedToken(t)
        assertNoOrphan(t.scopeId)

        restore(t, t.sub, t.subToken).statusCode(409).body("code", equalTo("slug_taken"))
        assertNotNull(markers(t)[1], "a refused restore changed nothing")

        delete(t, sibling)
        val fresh = slug()
        restore(t, t.sub, fresh).statusCode(204)

        assertEquals(listOf(null, null, null), markers(t), "the sub restored on its own, with its leaf")
        assertEquals(listOf(fresh, t.leafToken), tokens(t).drop(1), "the sub took the fresh token, the leaf kept its own")
        create(t, UUID.randomUUID().toString(), parent = t.sub, token = t.leafToken).statusCode(409).body("code", equalTo("slug_taken"))
        assertNoLiveFreedToken(t)
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
        val parentField = parent?.let { "\"parentId\":\"$it\",\"parentEpoch\":0," } ?: ""
        return given().header("Authorization", bearer(t.userId)).contentType(ContentType.JSON)
            .body("""{$parentField"nodeId":"$nodeId","kind":"FOLDER","encryptedName":"Zg==","slugToken":"$token","orderIndex":0,"wrappedContentKey":"aw=="}""")
            .`when`().post("/api/scopes/${t.scopeId}/nodes").then()
    }

    private fun tokens(t: Tree): List<String> = tx { listOf(t.folder, t.sub, t.leaf).map { NodeEntity.findById(it)!!.slugToken } }

    private fun delete(t: Tree, nodeId: String) {
        given().header("Authorization", bearer(t.userId)).`when`().delete("/api/scopes/${t.scopeId}/nodes/$nodeId").then().statusCode(204)
    }

    private fun restore(t: Tree, nodeId: String, token: String = slug()): ValidatableResponse =
        given().header("Authorization", bearer(t.userId)).contentType(ContentType.JSON).body("""{"slugToken":"$token","parentEpoch":0}""")
            .`when`().post("/api/scopes/${t.scopeId}/nodes/$nodeId/restore").then()

    private fun markers(t: Tree): List<Instant?> = tx { listOf(t.folder, t.sub, t.leaf).map { NodeEntity.findById(it)!!.deletedAt } }

    /** The retention passes: every trashed row of the scope moves back by the same span, keeping the deletes' order. */
    private fun ageEveryMarker(scopeId: String) {
        val span = config.node().trash().retention().plusSeconds(60)
        tx { NodeEntity.find("scopeId = ?1 and deletedAt is not null", scopeId).list().forEach { it.deletedAt = it.deletedAt!!.minus(span) } }
    }

    /** inbox #357: no live node holds a freed slug token (its own id) — neither in the listing a client reads, nor in
     *  the database. A freed token is no name: a sibling of that name could stand beside it. */
    private fun assertNoLiveFreedToken(t: Tree) {
        val listed: List<Map<String, Any?>> = given().header("Authorization", bearer(t.userId))
            .`when`().get("/api/scopes/${t.scopeId}/nodes").then().statusCode(200).extract().path("")
        listed.forEach { assertTrue(it["slugToken"] != it["id"], "the listing serves ${it["id"]} live with a freed token") }
        tx {
            NodeEntity.find("scopeId = ?1 and deletedAt is null", t.scopeId).list().forEach {
                assertTrue(it.slugToken != it.id, "node ${it.id} is live with a freed token")
            }
        }
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
