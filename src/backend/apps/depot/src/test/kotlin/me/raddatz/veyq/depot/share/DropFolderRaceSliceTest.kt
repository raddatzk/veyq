package me.raddatz.veyq.depot.share

import me.raddatz.veyq.core.content.NodesHandler
import me.raddatz.veyq.core.content.UpdateNodeOutcome
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.sharing.GuestTreeShare
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.testing.RACE_ROUNDS
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.placementTo
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.race
import me.raddatz.veyq.core.testing.whileUncommitted
import me.raddatz.veyq.core.testing.raceRounds
import me.raddatz.veyq.depot.DepotNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Races of a drop-box guest's identity folder (cr-files-application F3, ADR 0080 decision 11), on the handlers the
 * routes call:
 *
 * - **Concurrent first uploads of ONE identity** (two tabs of one guest, or the queue's parallel jobs) each ask
 *   `ensureShareDropFolder` before any folder exists: exactly one folder is made and every call answers it. Held by two
 *   things, each alone sufficient (measured by lever, see the CR's F3 note): the decision under the scope lock
 *   (`IdentityFolders.ensure`), and `ux_node_parent_identity`, whose loser answers as its replay.
 * - **A guest's create in its folder while a member moves the folder out** (held uncommitted): the guest's create
 *   decides its boundary — the identity's live folder in the drop box — under the scope lock, so it sees the move and
 *   is refused, never landing in a folder that left the drop box.
 */
@QuarkusTest
class DropFolderRaceSliceTest {

    @Inject lateinit var folders: DropFolderHandler
    @Inject lateinit var nodes: NodesHandler

    private data class Tree(val userId: String, val scopeId: String, val dropBox: String, val other: String, val link: GuestTreeShare.Link)

    private fun tree(): Tree {
        val (userId, scopeId, _) = seedMemberScope()
        val dropBox = seedNode(scopeId, DepotNodeKinds.DROPBOX, wrappedContentKey = CONTENT_KEY)
        val other = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val link = GuestTreeShare.seed(userId, scopeId, dropBox, ShareEntity.WRITE_ONLY)
        return Tree(userId, scopeId, dropBox, other, link)
    }

    private fun ensure(link: GuestTreeShare.Link): DropFolderOutcome =
        folders.ensure(link.shareId, null, link.token, UUID.randomUUID().toString(), "bmFtZQ==", GuestTreeShare.slug(), 0, "d3JhcA==", seen = null)

    @Test
    fun concurrentFirstCallsOfOneIdentityMakeOneFolder() {
        repeat(RACE_ROUNDS) { round ->
            val t = tree()
            // Over the route: the race's threads carry no request context, as no request thread lacks one.
            val answers = race({ ensureOverHttp(t.link) }, { ensureOverHttp(t.link) }, { ensureOverHttp(t.link) })
            assertTrue(answers.all { it.first == 200 }, "round $round: every call answers a folder: $answers")
            val folderIds = answers.map { it.second }
            assertEquals(1, folderIds.toSet().size, "round $round: one folder for all: $folderIds")
            assertEquals(1, answers.count { it.third == true }, "round $round: exactly one call made it: $answers")
            assertEquals(listOf(folderIds.first()), tx { NodeEntity.find("parentId", t.dropBox).list().map { it.id } }, "round $round: one row")
        }
    }

    @Test
    fun aGuestsCreateWhileAMemberMovesItsFolderOutIsRefused() {
        raceRounds { round ->
            val t = tree()
            val folder = (ensure(t.link) as DropFolderOutcome.Folder).folder.nodeId
            val answer = whileUncommitted(
                change = { assertEquals(UpdateNodeOutcome.Updated, nodes.update(t.userId, t.scopeId, folder, placementTo(t.other), null, null, null, null)) },
                overlapping = { GuestTreeShare.create(t.link, folder, DepotNodeKinds.FILE).extract().statusCode() },
            )
            assertEquals(403, answer, "round $round: the guest's create after the move out")
            assertEquals(0L, tx { NodeEntity.count("parentId", folder) }, "round $round: nothing landed in the moved folder")
        }
        // control: without the move, the create lands
        val t = tree()
        val folder = (ensure(t.link) as DropFolderOutcome.Folder).folder.nodeId
        assertEquals(201, GuestTreeShare.create(t.link, folder, DepotNodeKinds.FILE).extract().statusCode())
    }

    /** `ensureShareDropFolder` over HTTP: (status, folder id, created). */
    private fun ensureOverHttp(link: GuestTreeShare.Link): Triple<Int, String?, Boolean?> {
        val r = given().contentType(ContentType.JSON)
            .body(
                """{"contributorToken":"${link.token}","nodeId":"${UUID.randomUUID()}","encryptedName":"bmFtZQ==",""" +
                    """"slugToken":"${GuestTreeShare.slug()}","epoch":0,"wrappedContentKey":"d3JhcA=="}""",
            )
            .`when`().post("/api/shares/${link.shareId}/files/drop-folder").then().extract()
        return Triple(r.statusCode(), r.path<String?>("nodeId"), r.path<Boolean?>("created"))
    }

    private fun <T> tx(body: () -> T): T = QuarkusTransaction.requiringNew().call(body)
}
