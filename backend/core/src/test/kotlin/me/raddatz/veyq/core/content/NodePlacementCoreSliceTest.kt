package me.raddatz.veyq.core.content

import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import java.util.Base64
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Move and rename are one placement write (ADR 0080 Decision 3): the destination, the content key re-wrapped under its
 * key, the re-sealed name and slug, the epoch and the `placementRevision` the write was built on. The server
 * compare-and-sets the revision (`409 stale_placement`), checks the epoch for equality at the scope root and for "not
 * above the current" everywhere (Decision 4), and
 * refuses a destination that is missing, trashed or inside the node's own subtree (`422 illegal_structure`). Every
 * refusal writes nothing: each test reads the row back, and each has a control that lands.
 *
 * Core alone: every node here is a `FOLDER`. The kind rule at a destination is Memories' (`NodePlacementSliceTest`,
 * `NodeKindMatrixSliceTest.moveFollowsTheMatrix`); the races are `NodePlacementRaceCoreSliceTest`.
 */
@QuarkusTest
class NodePlacementCoreSliceTest {

    /** ADR 0080 bite test 3: a rename built on revision n after a move took the node to n+1 → 409, name unchanged. */
    @Test
    fun aRenameBuiltOnTheRevisionBeforeAMoveIs409AndLeavesTheName() {
        val t = tree()
        place(t, t.child, to = t.b, revision = 0, name = "bW92ZWQ=", wrap = "bW92ZWQtd3JhcA==").statusCode(204)
        assertRow(t.child, parent = t.b, name = "bW92ZWQ=", wrap = "bW92ZWQtd3JhcA==", revision = 1)

        // the rename was sealed for the old parent, at revision 1 — after the move it would not open there
        place(t, t.child, to = t.a, revision = 0, name = "c3RhbGU=", wrap = "c3RhbGUtd3JhcA==")
            .statusCode(409).body("code", equalTo("stale_placement"))
        assertRow(t.child, parent = t.b, name = "bW92ZWQ=", wrap = "bW92ZWQtd3JhcA==", revision = 1)

        // control: rebuilt on the current revision (and the current parent) it lands at 2
        place(t, t.child, to = t.b, revision = 1, name = "cmVuYW1lZA==", wrap = "cmVuYW1lZC13cmFw").statusCode(204)
        assertRow(t.child, parent = t.b, name = "cmVuYW1lZA==", wrap = "cmVuYW1lZC13cmFw", revision = 2)
    }

    /** Bite test 15: a rename without its re-wrapped key is malformed — the old wrap is sealed at the old revision and
     *  would stop opening; so is one without its revision or its epoch. A stale revision is `stale_placement`. */
    @Test
    fun aRenameWithoutItsRewrapOrItsRevisionIs400AndAStaleOneIs409() {
        val t = tree()
        for (body in listOf(
            """{"encryptedName":"bmV3","slugToken":"${slug()}","parentId":"${t.a}","epoch":0,"placementRevision":0}""",
            """{"encryptedName":"bmV3","slugToken":"${slug()}","parentId":"${t.a}","wrappedContentKey":"cmV3","epoch":0}""",
            """{"encryptedName":"bmV3","slugToken":"${slug()}","parentId":"${t.a}","wrappedContentKey":"cmV3","placementRevision":0}""",
            """{"parentId":"${t.b}"}""",
            """{"wrappedContentKey":"cmV3","placementRevision":0,"epoch":0}""",
        )) {
            patch(t, t.child, body).statusCode(400).body("code", equalTo("invalid_request"))
        }
        assertRow(t.child, parent = t.a, name = "Y2hpbGQ=", wrap = CONTENT_KEY, revision = 0)

        place(t, t.child, to = t.a, revision = 3, name = "bmV3", wrap = "cmV3").statusCode(409).body("code", equalTo("stale_placement"))
        assertRow(t.child, parent = t.a, name = "Y2hpbGQ=", wrap = CONTENT_KEY, revision = 0)

        place(t, t.child, to = t.a, revision = 0, name = "bmV3", wrap = "cmV3").statusCode(204) // control
        assertRow(t.child, parent = t.a, name = "bmV3", wrap = "cmV3", revision = 1)
    }

    /** Bite test 5: an entry at the scope root lives under `K_SC`, so its rename is sealed at the CURRENT epoch. */
    @Test
    fun aRootRenameAtAStaleEpochIs409() {
        val t = tree()
        rotate(t.scopeId, to = 1)
        place(t, t.a, to = null, revision = 0, name = "b2xk", wrap = "b2xkLXdyYXA=", epoch = 0)
            .statusCode(409).body("code", equalTo("stale_epoch"))
        assertRow(t.a, parent = t.scopeId, name = "YQ==", wrap = CONTENT_KEY, revision = 0, epoch = 0)

        place(t, t.a, to = null, revision = 0, name = "bmV3", wrap = "bmV3LXdyYXA=", epoch = 1).statusCode(204) // control
        assertRow(t.a, parent = t.scopeId, name = "bmV3", wrap = "bmV3LXdyYXA=", revision = 1, epoch = 1)
    }

    /** Bite test 16: a move INTO the root is checked like a root rename; a move between two non-root parents keeps the
     *  stored epoch (a request's epoch below the current is not refused); a move OUT of the root stores the epoch the
     *  client sealed at. */
    @Test
    fun theEpochIsCheckedOnlyAtTheRoot() {
        val t = tree()
        rotate(t.scopeId, to = 1)
        place(t, t.child, to = null, revision = 0, name = "bmV3", wrap = "cmV3", epoch = 0)
            .statusCode(409).body("code", equalTo("stale_epoch"))
        assertRow(t.child, parent = t.a, name = "Y2hpbGQ=", wrap = CONTENT_KEY, revision = 0, epoch = 0)

        // between non-root parents: an epoch below the current one is not refused, and not stored (one above it is —
        // anEpochAboveTheCurrentIsRefusedForAnyDestination)
        place(t, t.child, to = t.b, revision = 0, name = "aW4tYg==", wrap = "aW4tYi13cmFw", epoch = 0).statusCode(204)
        assertRow(t.child, parent = t.b, name = "aW4tYg==", wrap = "aW4tYi13cmFw", revision = 1, epoch = 0)
        place(t, t.child, to = t.a, revision = 1, name = "aW4tYQ==", wrap = "aW4tYS13cmFw", epoch = 1).statusCode(204)
        assertRow(t.child, parent = t.a, name = "aW4tYQ==", wrap = "aW4tYS13cmFw", revision = 2, epoch = 0)

        // into the root at the current epoch: stored
        place(t, t.child, to = null, revision = 2, name = "cm9vdA==", wrap = "cm9vdC13cmFw", epoch = 1).statusCode(204)
        assertRow(t.child, parent = t.scopeId, name = "cm9vdA==", wrap = "cm9vdC13cmFw", revision = 3, epoch = 1)

        // out of the root: the request's epoch is stored (the entry is under the parent's key from now on)
        rotate(t.scopeId, to = 2)
        place(t, t.child, to = t.b, revision = 3, name = "b3V0", wrap = "b3V0LXdyYXA=", epoch = 2).statusCode(204)
        assertRow(t.child, parent = t.b, name = "b3V0", wrap = "b3V0LXdyYXA=", revision = 4, epoch = 2)
    }

    /** The epoch gap slice 3 left (5a.2): off the root the request's epoch was stored or ignored unchecked, so a move
     *  out of the root could store an epoch past the scope's. Above the current epoch is refused for ANY destination;
     *  below it stays accepted off the root (the entry's own sealed epoch). */
    @Test
    fun anEpochAboveTheCurrentIsRefusedForAnyDestination() {
        val t = tree()
        rotate(t.scopeId, to = 1)
        // out of the root, at an epoch that does not exist yet: refused, the stored epoch unchanged
        place(t, t.a, to = t.b, revision = 0, name = "b3V0", wrap = "b3V0LXdyYXA=", epoch = 2)
            .statusCode(409).body("code", equalTo("stale_epoch"))
        assertRow(t.a, parent = t.scopeId, name = "YQ==", wrap = CONTENT_KEY, revision = 0, epoch = 0)
        // between two non-root parents: refused too
        place(t, t.child, to = t.b, revision = 0, name = "aW4tYg==", wrap = "aW4tYi13cmFw", epoch = 2)
            .statusCode(409).body("code", equalTo("stale_epoch"))
        assertRow(t.child, parent = t.a, name = "Y2hpbGQ=", wrap = CONTENT_KEY, revision = 0, epoch = 0)

        // controls: below the current epoch between non-root parents lands (the stored one kept); out of the root at
        // the current epoch lands and is stored
        place(t, t.child, to = t.b, revision = 0, name = "aW4tYg==", wrap = "aW4tYi13cmFw", epoch = 0).statusCode(204)
        assertRow(t.child, parent = t.b, name = "aW4tYg==", wrap = "aW4tYi13cmFw", revision = 1, epoch = 0)
        place(t, t.a, to = t.b, revision = 0, name = "b3V0", wrap = "b3V0LXdyYXA=", epoch = 1).statusCode(204)
        assertRow(t.a, parent = t.b, name = "b3V0", wrap = "b3V0LXdyYXA=", revision = 1, epoch = 1)
    }

    /** Bite test 11: a cycle-forming move → `illegal_structure`, the placement revision unchanged. */
    @Test
    fun aCycleFormingMoveIs422AndLeavesTheRevision() {
        val t = tree()
        // a → under its own child, and a → under itself
        place(t, t.a, to = t.child, revision = 0, name = "Y3ljbGU=", wrap = "Y3ljbGU=").statusCode(422).body("code", equalTo("illegal_structure"))
        place(t, t.a, to = t.a, revision = 0, name = "Y3ljbGU=", wrap = "Y3ljbGU=").statusCode(422).body("code", equalTo("illegal_structure"))
        assertRow(t.a, parent = t.scopeId, name = "YQ==", wrap = CONTENT_KEY, revision = 0)

        place(t, t.a, to = t.b, revision = 0, name = "dW5kZXItYg==", wrap = "dW5kZXItYg==").statusCode(204) // control
        assertRow(t.a, parent = t.b, name = "dW5kZXItYg==", wrap = "dW5kZXItYg==", revision = 1)
    }

    /** ADR 0080 Decision 8 / bite test 6, the half that belongs to the placement: a trashed destination is refused —
     *  the move would hang a live node, whose key wraps under the trashed one, below a subtree the purge erases. */
    @Test
    fun aMoveIntoATrashedParentIs422() {
        val t = tree()
        given().header("Authorization", bearer(t.userId)).`when`().delete("/api/scopes/${t.scopeId}/nodes/${t.b}").then().statusCode(204)
        place(t, t.child, to = t.b, revision = 0, name = "dHJhc2g=", wrap = "dHJhc2g=").statusCode(422).body("code", equalTo("illegal_structure"))
        assertRow(t.child, parent = t.a, name = "Y2hpbGQ=", wrap = CONTENT_KEY, revision = 0)

        // a node in another scope is no destination either
        val (_, otherScope) = seedMemberScope()
        val foreign = seedNode(otherScope, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        place(t, t.child, to = foreign, revision = 0, name = "Zm9yZWlnbg==", wrap = "Zm9yZWlnbg==").statusCode(422)
        assertRow(t.child, parent = t.a, name = "Y2hpbGQ=", wrap = CONTENT_KEY, revision = 0)
    }

    /** A trashed node is not placed: it is restored. And a slug taken at the destination is the ordinary 409. */
    @Test
    fun aTrashedNodeIsNotMovableAndATakenSlugIs409() {
        val t = tree()
        val taken = slug()
        place(t, t.b, to = t.a, revision = 0, name = "Yg==", wrap = "Yg==", slugToken = taken).statusCode(204)
        place(t, t.child, to = t.a, revision = 0, name = "eA==", wrap = "eA==", slugToken = taken)
            .statusCode(409).body("code", equalTo("slug_taken"))
        assertRow(t.child, parent = t.a, name = "Y2hpbGQ=", wrap = CONTENT_KEY, revision = 0)

        given().header("Authorization", bearer(t.userId)).`when`().delete("/api/scopes/${t.scopeId}/nodes/${t.child}").then().statusCode(204)
        place(t, t.child, to = t.a, revision = 0, name = "eA==", wrap = "eA==").statusCode(404)
    }

    /** The listing serves the revision every reader binds the name and the wrap to; a reorder or a meta write leaves it. */
    @Test
    fun theListingServesThePlacementRevisionAndOnlyAPlacementMovesIt() {
        val t = tree()
        patch(t, t.child, """{"orderIndex":4}""").statusCode(204)
        patch(t, t.child, """{"wrappedMeta":"bWV0YQ==","metaRevision":0}""").statusCode(204)
        listedRevision(t, t.child, 0)
        place(t, t.child, to = t.a, revision = 0, name = "bmV3", wrap = "cmV3").statusCode(204)
        listedRevision(t, t.child, 1)
    }

    // --- helpers ---

    private data class Tree(val userId: String, val scopeId: String, val a: String, val b: String, val child: String)

    /** Two root folders a and b, and a child folder under a — every name, wrap and slug opaque bytes. */
    private fun tree(): Tree {
        val (userId, scopeId) = seedMemberScope()
        val a = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY, encryptedName = "YQ==")
        val b = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY, encryptedName = "Yg==")
        val child = seedNode(scopeId, NodeEntity.FOLDER, parentId = a, wrappedContentKey = CONTENT_KEY, encryptedName = "Y2hpbGQ=")
        return Tree(userId, scopeId, a, b, child)
    }

    private fun place(
        t: Tree, node: String, to: String?, revision: Long, name: String, wrap: String, epoch: Int = 0, slugToken: String = slug(),
    ): ValidatableResponse {
        val parent = to?.let { "\"parentId\":\"$it\"," } ?: ""
        return patch(
            t, node,
            """{$parent"encryptedName":"$name","slugToken":"$slugToken","wrappedContentKey":"$wrap","epoch":$epoch,"placementRevision":$revision}""",
        )
    }

    private fun patch(t: Tree, node: String, body: String): ValidatableResponse =
        given().header("Authorization", bearer(t.userId)).contentType(ContentType.JSON).body(body)
            .`when`().patch("/api/scopes/${t.scopeId}/nodes/$node").then()

    private fun listedRevision(t: Tree, node: String, expect: Int) {
        given().header("Authorization", bearer(t.userId)).`when`().get("/api/scopes/${t.scopeId}/nodes")
            .then().statusCode(200).body("find { it.id == '$node' }.placementRevision", equalTo(expect))
    }

    private fun assertRow(node: String, parent: String, name: String, wrap: String, revision: Long, epoch: Int? = null) {
        val row = QuarkusTransaction.requiringNew().call { NodeEntity.findById(node)!! }
        assertEquals(listOf(parent, name, wrap, revision), listOf(row.parentId, row.encryptedName, row.wrappedContentKey, row.placementRevision))
        epoch?.let { assertEquals(it, row.epoch, "epoch") }
    }

    private fun rotate(scopeId: String, to: Int) {
        QuarkusTransaction.requiringNew().run { ScopeEntity.findById(scopeId)!!.currentEpoch = to }
    }

    private fun slug(): String = Base64.getEncoder().encodeToString(UUID.randomUUID().toString().toByteArray())
}
