package me.raddatz.veyq.core.content

import me.raddatz.veyq.core.blob.AttachOutcome
import me.raddatz.veyq.core.blob.AttachmentStore
import me.raddatz.veyq.core.blob.AttachmentUploads
import me.raddatz.veyq.core.blob.BlobUploadStore
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.sharing.GuestTreeShare
import me.raddatz.veyq.core.testing.RACE_ROUNDS
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.race
import me.raddatz.veyq.core.testing.whileUncommitted
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The tree revision under overlap (ADR 0081): every bump is one atomic `UPDATE … revision + 1` under the scope lock
 * every bumping writer holds, so no bump is lost and none dies. Three things could break it, each pinned:
 *
 * - **the first bump of a scope** — no row to update, so two writers each decide to insert: the second dies at the
 *   primary key unless the scope lock serializes them (`createsRacingForAScopesFirstBumpAllCount`);
 * - **a read-add-write** instead of the atomic increment — two writers read N and both write N+1, one change vanishes
 *   from the tag (`writesOfDifferentKindsRacingAllCount`, `anOverlappingCreateWaitsForAHeldOneAndCountsToo`);
 * - **a lock taken after the node row** — the documented order is scope, then node, and the revision row is a leaf
 *   behind the scope lock: a create racing a trash racing an attach would deadlock if any took them the other way
 *   round (the mixed race below finishes at all).
 *
 * MariaDB, not H2, shows what overlap really does (ADR 0078): `python3 scripts/run_backend_races_on_mariadb.py` runs this.
 */
@QuarkusTest
class TreeRevisionRaceCoreSliceTest {

    @Inject lateinit var nodes: NodesStore
    @Inject lateinit var attachments: AttachmentStore
    @Inject lateinit var blobs: BlobUploadStore

    @Test
    fun createsRacingForAScopesFirstBumpAllCount() {
        repeat(RACE_ROUNDS) { round ->
            val (userId, scopeId) = seedMemberScope()
            val statuses = race(*Array(WRITERS) { { createFolder(userId, scopeId) } })
            assertEquals(List(WRITERS) { 201 }, statuses, "round $round: every create lands")
            assertEquals(WRITERS.toLong(), revision(scopeId), "round $round: and every one is counted, on the one row the first made")
        }
    }

    @Test
    fun writesOfDifferentKindsRacingAllCount() {
        repeat(RACE_ROUNDS) { round ->
            val (userId, scopeId) = seedMemberScope()
            val trashed = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
            val renamed = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
            val attachedTo = seedNode(scopeId, "EXPENSE", wrappedContentKey = CONTENT_KEY)
            val upload = AttachmentUploads.open(blobs, userId, scopeId, attachedTo, "RECEIPT")
            val results = race(
                { createFolder(userId, scopeId) },
                { given().header("Authorization", bearer(userId)).`when`().delete("/api/scopes/$scopeId/nodes/$trashed").statusCode() },
                {
                    given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
                        .body("""{"wrappedMeta":"bWV0YQ==","metaRevision":0}""")
                        .`when`().patch("/api/scopes/$scopeId/nodes/$renamed").statusCode()
                },
                {
                    when (attachments.attach(scopeId, attachedTo, setOf("EXPENSE"), "RECEIPT", upload.session, null, null)) {
                        is AttachOutcome.Attached -> 204
                        else -> 500
                    }
                },
            )
            assertEquals(listOf(201, 204, 204, 204), results, "round $round")
            assertEquals(4L, revision(scopeId), "round $round: four changes, four counts — none lost to a read-add-write")
        }
    }

    /** A held create (its scope lock and its bump uncommitted): the overlapping create waits, then counts after it. */
    @Test
    fun anOverlappingCreateWaitsForAHeldOneAndCountsToo() {
        repeat(RACE_ROUNDS) { round ->
            val (userId, scopeId) = seedMemberScope()
            val status = whileUncommitted(
                change = {
                    nodes.lockScope(scopeId)
                    nodes.create(UUID.randomUUID().toString(), scopeId, scopeId, NodeEntity.FOLDER, "YQ==", GuestTreeShare.slug(), 0, 0, CONTENT_KEY, null)
                },
                overlapping = { createFolder(userId, scopeId) },
            )
            assertEquals(201, status, "round $round")
            assertEquals(2L, revision(scopeId), "round $round: the held create and the waiting one are both counted")
        }
    }

    private fun createFolder(userId: String, scopeId: String): Int =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"nodeId":"${UUID.randomUUID()}","kind":"FOLDER","encryptedName":"YQ==","slugToken":"${GuestTreeShare.slug()}","orderIndex":0,"wrappedContentKey":"a21lbQ==","epoch":0}""")
            .`when`().post("/api/scopes/$scopeId/nodes").statusCode()

    private fun revision(scopeId: String): Long = QuarkusTransaction.requiringNew().call { nodes.treeRevision(scopeId) }

    private companion object {
        const val WRITERS = 6
    }
}
