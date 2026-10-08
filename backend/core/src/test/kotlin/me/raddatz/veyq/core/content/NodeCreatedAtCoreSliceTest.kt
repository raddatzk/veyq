package me.raddatz.veyq.core.content

import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A node's `createdAt` on the members' listing (`NodeSummary.createdAt`, ADR 0015): the instant the server stored the
 * node, to the millisecond — what clients order equal-named siblings by, then by id. Server-observed: a client cannot
 * set it (no request field; one sent anyway changes nothing), and nothing after the create moves it (a rename, a
 * move, a trash and a restore keep it).
 *
 * **The precision test bites only on MariaDB.** [aMillisecondIsListedExactly] reads back a stored `.123`: on MariaDB a
 * `created_at TIMESTAMP` (no precision) keeps whole seconds and lists `.000`, but H2 gives a bare `TIMESTAMP` six
 * digits, so on H2 it is green either way. This class therefore runs on MariaDB too
 * (`python3 scripts/run_backend_races_on_mariadb.py --tests '*NodeCreatedAtCoreSliceTest'`).
 */
@QuarkusTest
class NodeCreatedAtCoreSliceTest {

    @Test
    fun aMillisecondIsListedExactly() {
        val (userId, scopeId) = seedMemberScope()
        val node = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val stored = Instant.parse("2026-10-08T10:15:30.123Z")
        QuarkusTransaction.requiringNew().run { NodeEntity.findById(node)!!.createdAt = stored }
        assertEquals(stored, listed(userId, scopeId, node))
        assertEquals(stored, row(node), "the column keeps the millisecond")
    }

    @Test
    fun aCreateListsTheInstantTheServerStoredItAt() {
        val (userId, scopeId) = seedMemberScope()
        val before = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val node = create(userId, scopeId)
        val after = Instant.now()
        val listed = listed(userId, scopeId, node)
        assertEquals(row(node), listed, "the listing serves the stored instant")
        assertTrue(!listed.isBefore(before) && !listed.isAfter(after), "$listed is not within [$before, $after]")
        assertEquals(0, listed.nano % 1_000_000, "cut to the millisecond, so both engines store and list the same value")
    }

    /** No request carries `createdAt`: one sent on a create or an update anyway is ignored, and the server's stands. */
    @Test
    fun aClientSentCreatedAtChangesNothing() {
        val (userId, scopeId) = seedMemberScope()
        val forged = "2001-01-01T00:00:00.000Z"
        val before = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val node = create(userId, scopeId, extra = ""","createdAt":"$forged"""")
        val born = listed(userId, scopeId, node)
        assertTrue(!born.isBefore(before), "a forged createdAt was stored: $born")
        patch(userId, scopeId, node, """{"orderIndex":3,"createdAt":"$forged"}""")
        assertEquals(born, listed(userId, scopeId, node))
    }

    @Test
    fun aRenameAMoveATrashAndARestoreKeepIt() {
        val (userId, scopeId) = seedMemberScope()
        val other = create(userId, scopeId)
        val node = create(userId, scopeId)
        val born = listed(userId, scopeId, node)
        Thread.sleep(5) // a write that re-stamped it would now land on a later millisecond
        patch(userId, scopeId, node, place(parent = null, version = 0)) // rename in place
        patch(userId, scopeId, node, place(parent = other, version = 1)) // move
        assertEquals(born, listed(userId, scopeId, node))
        given().header("Authorization", bearer(userId)).`when`().delete("/api/scopes/$scopeId/nodes/$node").then().statusCode(204)
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON).body("""{"slugToken":"${slug()}"}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$node/restore").then().statusCode(204)
        assertEquals(born, listed(userId, scopeId, node))
        assertEquals(born, row(node))
    }

    // --- helpers ---

    private fun create(userId: String, scopeId: String, extra: String = ""): String {
        val id = UUID.randomUUID().toString()
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"nodeId":"$id","kind":"FOLDER","encryptedName":"bg==","slugToken":"${slug()}","orderIndex":0,"wrappedContentKey":"a21lbQ==","epoch":0$extra}""")
            .`when`().post("/api/scopes/$scopeId/nodes").then().statusCode(201)
        return id
    }

    private fun place(parent: String?, version: Long): String {
        val to = parent?.let { "\"parentId\":\"$it\"," } ?: ""
        return """{$to"encryptedName":"cmVuYW1lZA==","slugToken":"${slug()}","wrappedContentKey":"cmV3cmFw","epoch":0,"placementVersion":$version}"""
    }

    private fun patch(userId: String, scopeId: String, node: String, body: String) {
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON).body(body)
            .`when`().patch("/api/scopes/$scopeId/nodes/$node").then().statusCode(204)
    }

    private fun listed(userId: String, scopeId: String, node: String): Instant =
        OffsetDateTime.parse(
            given().header("Authorization", bearer(userId)).`when`().get("/api/scopes/$scopeId/nodes")
                .then().statusCode(200).extract().path<String>("find { it.id == '$node' }.createdAt"),
        ).toInstant()

    private fun row(node: String): Instant = QuarkusTransaction.requiringNew().call { NodeEntity.findById(node)!!.createdAt }

    private fun slug(): String = Base64.getEncoder().encodeToString(UUID.randomUUID().toString().toByteArray())
}
