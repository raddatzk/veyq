package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestNodeRekey
import me.raddatz.veyq.core.testing.raceRounds
import me.raddatz.veyq.core.testing.whileUncommitted
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import java.util.Base64
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A link's close against the writers it races (ADR 0114 decision 8; developer-styleguide §12): the close and every due
 * mark land in one transaction, under the scope row's lock, then the share row's, then the node rows' — every writer's
 * order. Each round holds the close uncommitted ([whileUncommitted]) and runs the other writer in that window; it waits
 * on the scope row and then meets the close's state, never the state before it. Each with a control: the same write with
 * nothing overlapping lands.
 *
 * 1. A member's write under a key of the subtree: `node_rekey_pending` — nothing lands under the key the link's holder has.
 * 2. A node re-key commit carrying a wrap for the link: `rekey_parts_stale` — the new key never lands under the closed
 *    link.
 */
@QuarkusTest
class LinkCloseRaceCoreSliceTest {

    @Inject lateinit var store: SharingStore

    @Test
    fun aMemberWriteIntoTheSubtreeWhileTheCloseCommitsWaitsForTheReKey() = raceRounds { round ->
        val w = world()
        val link = createShare(w)
        val answer = whileUncommitted(
            change = { assertTrue(store.revoke(w.owner, link), "round $round") },
            overlapping = { meta(w, w.child).statusCode() },
        )
        assertEquals(409, answer, "round $round: a write landed under a key the closed link's holder has")
        assertNull(QuarkusTransaction.requiringNew().call { NodeEntity.findById(w.child)!!.wrappedMeta }, "round $round")
        val kept = world() // control: with no close overlapping, the same write lands
        createShare(kept)
        assertEquals(204, meta(kept, kept.child).statusCode(), "round $round: control")
    }

    @Test
    fun aReKeyCommitCarryingTheLinksWrapWhileTheCloseCommitsIsStale() = raceRounds { round ->
        val w = world()
        val link = createShare(w)
        markDue(w)
        val rekeyId = UUID.randomUUID()
        assertEquals(204, stage(w, rekeyId, link), "round $round")
        val answer = whileUncommitted(
            change = { assertTrue(store.revoke(w.owner, link), "round $round") },
            overlapping = { commitRoot(w, rekeyId) },
        )
        assertEquals(409, answer, "round $round: the new key landed under the closed link")
        assertNull(QuarkusTransaction.requiringNew().call { ShareEntity.findById(link)!!.wrap }, "round $round")
        val kept = world() // control: with no close overlapping, the commit lands the open link's new wrap
        val keptLink = createShare(kept)
        markDue(kept)
        val keptId = UUID.randomUUID()
        assertEquals(204, stage(kept, keptId, keptLink), "round $round: control")
        assertEquals(204, commitRoot(kept, keptId), "round $round: control")
    }

    // --- helpers ---

    private data class World(val owner: String, val scopeId: String, val root: String, val child: String)

    private fun world(): World {
        val (owner, scopeId, _) = seedMemberScope()
        val root = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val child = seedNode(scopeId, NodeEntity.FOLDER, parentId = root, wrappedContentKey = CONTENT_KEY)
        return World(owner, scopeId, root, child)
    }

    private fun createShare(w: World): String {
        val id = TestIds.shareId()
        given().header("Authorization", bearer(w.owner)).contentType(ContentType.JSON)
            .body(
                """{"shareId":"$id",${ShareWriteProof.field(id)},"nodeId":"${w.root}","role":"READ","wrap":"d3JhcA==",""" +
                    """"sealedName":"c2VhbGVk",${ShareWriteProof.recovery(epoch = 0, nodeEpoch = 0)}}""",
            )
            .`when`().post("/api/shares").then().statusCode(201)
        return id
    }

    /** Every node of the scope due, as a rotation's commit leaves it. */
    private fun markDue(w: World) = QuarkusTransaction.requiringNew().run {
        NodeEntity.update("rekeyDue = true where scopeId = ?1", w.scopeId)
    }

    private fun meta(w: World, nodeId: String) =
        given().header("Authorization", bearer(w.owner)).contentType(ContentType.JSON)
            .body("""{"wrappedMeta":"bWV0YQ==","metaRevision":0,"nodeEpoch":${TestNodeRekey.epochOf(nodeId)}}""")
            .`when`().patch("/api/scopes/${w.scopeId}/nodes/$nodeId").then().extract()

    private fun stage(w: World, rekeyId: UUID, shareId: String): Int =
        given().header("Authorization", bearer(w.owner)).contentType(ContentType.JSON)
            .body(
                """{"fromEpoch":0,"shares":[{"shareId":"$shareId","wrap":"bmV3LXNoYXJlLXdyYXA="}],""" +
                    """"children":[{"nodeId":"${w.child}","placementRevision":0,"encryptedName":"bmV3","slugToken":"${slug()}",""" +
                    """"wrappedContentKey":"bmV3LXdyYXA="}]}""",
            )
            .`when`().post("/api/scopes/${w.scopeId}/rekey/nodes/${w.root}/$rekeyId/parts").then().extract().statusCode()

    private fun commitRoot(w: World, rekeyId: UUID): Int =
        given().header("Authorization", bearer(w.owner)).contentType(ContentType.JSON)
            .body(
                """{"nodeEpoch":0,"scopeEpoch":0,"placementRevision":0,"metaRevision":0,"encryptedName":"bmV3LXJvb3Q=",""" +
                    """"slugToken":"${slug()}","wrappedContentKey":"bmV3LXJvb3Qtd3JhcA=="}""",
            )
            .`when`().post("/api/scopes/${w.scopeId}/rekey/nodes/${w.root}/$rekeyId").then().extract().statusCode()

    private fun slug(): String = Base64.getEncoder().encodeToString(UUID.randomUUID().toString().toByteArray())
}
