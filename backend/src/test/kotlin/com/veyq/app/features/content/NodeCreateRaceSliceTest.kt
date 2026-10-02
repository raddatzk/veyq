package com.veyq.app.features.content

import com.veyq.app.shared.TestIds
import com.veyq.app.testing.RACE_ROUNDS
import com.veyq.app.testing.TestFixtures.CONTENT_KEY
import com.veyq.app.testing.TestFixtures.bearer
import com.veyq.app.testing.TestFixtures.seedMemberScope
import com.veyq.app.testing.answer
import com.veyq.app.testing.assertWinnerAndLoser
import com.veyq.app.testing.race
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.UUID

/**
 * Node create raced against itself (developer-styleguide §12). A node is unique twice over — its client-minted id,
 * and its slug under its parent (`ux_node_parent_slug`) — and the loser of either race answers what a replay gets:
 * the handler asks the slug first, then the id. Each test ends with a fresh create that succeeds, so a site that
 * refuses everything cannot pass.
 */
@QuarkusTest
class NodeCreateRaceSliceTest {

    /** Two creates of one id under different slugs: the loser's replay answers `id_taken`. */
    @Test
    fun concurrentNodeCreatesOfOneIdCreateOneNode() {
        val (userId, scopeId) = seedMemberScope()
        repeat(RACE_ROUNDS) { round ->
            val nodeId = TestIds.uuid()
            val answers = race({ create(userId, scopeId, nodeId, slug()) }, { create(userId, scopeId, nodeId, slug()) })
            assertWinnerAndLoser(201, 409 to "id_taken", answers, "round $round")
        }
        assertEquals(201 to null, create(userId, scopeId, TestIds.uuid(), slug()))
    }

    /** Two creates of different ids under one slug in one parent: the loser's replay answers `slug_taken`. */
    @Test
    fun concurrentNodeCreatesOfOneSlugCreateOneNode() {
        val (userId, scopeId) = seedMemberScope()
        repeat(RACE_ROUNDS) { round ->
            val slug = slug()
            val answers = race({ create(userId, scopeId, TestIds.uuid(), slug) }, { create(userId, scopeId, TestIds.uuid(), slug) })
            assertWinnerAndLoser(201, 409 to "slug_taken", answers, "round $round")
        }
        assertEquals(201 to null, create(userId, scopeId, TestIds.uuid(), slug()))
    }

    /**
     * A double-submitted create — one id, one slug: its replay asks the slug first, so the loser answers
     * `slug_taken`, also when the winner commits between its slug check and the store's own id check.
     */
    @Test
    fun concurrentNodeCreatesOfOneBodyCreateOneNode() {
        val (userId, scopeId) = seedMemberScope()
        repeat(RACE_ROUNDS) { round ->
            val (nodeId, slug) = TestIds.uuid() to slug()
            val answers = race({ create(userId, scopeId, nodeId, slug) }, { create(userId, scopeId, nodeId, slug) })
            assertWinnerAndLoser(201, 409 to "slug_taken", answers, "round $round")
        }
        assertEquals(201 to null, create(userId, scopeId, TestIds.uuid(), slug()))
    }

    private fun slug(): String = Base64.getEncoder().encodeToString(UUID.randomUUID().toString().toByteArray())

    private fun create(userId: String, scopeId: String, nodeId: String, slug: String): Pair<Int, String?> =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"nodeId":"$nodeId","kind":"MEMORY","encryptedName":"bmV3","slugToken":"$slug","orderIndex":0,"wrappedContentKey":"$CONTENT_KEY"}""")
            .`when`().post("/api/scopes/$scopeId/nodes").then().extract().answer()
}
