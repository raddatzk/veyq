package com.veyq.app.features.sharing

import com.veyq.app.features.content.NodeEntity
import com.veyq.app.shared.TestIds
import com.veyq.app.testing.RACE_ROUNDS
import com.veyq.app.testing.TestFixtures.CONTENT_KEY
import com.veyq.app.testing.TestFixtures.bearer
import com.veyq.app.testing.TestFixtures.seedMemberScope
import com.veyq.app.testing.TestFixtures.seedNode
import com.veyq.app.testing.answer
import com.veyq.app.testing.assertWinnerAndLoser
import com.veyq.app.testing.race
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The sharing creates raced against themselves (developer-styleguide §12): a double-submitted share or guest identity
 * create must give its loser `id_taken` — what a replay of it gets — never the 500 of the primary key it collided
 * with. Each test ends with a fresh create that succeeds, so a site that refuses everything cannot pass.
 */
@QuarkusTest
class ShareCreateRaceSliceTest {

    private val wrap = "d3JhcC1rLW1lbS11bmRlci1zaGFyZQ=="
    private val sealedName = "c2VhbGVkLW5hbWU="

    @Test
    fun concurrentShareCreatesOfOneIdCreateItOnce() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY, wrappedContentKey = CONTENT_KEY)
        repeat(RACE_ROUNDS) { round ->
            val shareId = TestIds.shareId()
            val answers = race({ createShare(userId, memory, shareId) }, { createShare(userId, memory, shareId) })
            assertWinnerAndLoser(201, 409 to "id_taken", answers, "round $round")
            assertEquals(1L, QuarkusTransaction.requiringNew().call { ShareEntity.count("id", shareId) }, "round $round")
        }
        assertEquals(201 to null, createShare(userId, memory, TestIds.shareId()))
    }

    /** A fresh CONTRIBUTOR link each round: identity creation is rate-limited per link. */
    @Test
    fun concurrentIdentityCreatesOfOneIdCreateItOnce() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY, wrappedContentKey = CONTENT_KEY)
        repeat(RACE_ROUNDS) { round ->
            val shareId = TestIds.shareId()
            assertEquals(201 to null, createShare(userId, memory, shareId, role = "CONTRIBUTOR"))
            val identityId = TestIds.uuid()
            val answers = race({ createIdentity(shareId, identityId) }, { createIdentity(shareId, identityId) })
            assertWinnerAndLoser(201, 409 to "id_taken", answers, "round $round")
        }
        val shareId = TestIds.shareId()
        assertEquals(201 to null, createShare(userId, memory, shareId, role = "CONTRIBUTOR"))
        assertEquals(201 to null, createIdentity(shareId, TestIds.uuid()))
    }

    private fun createShare(userId: String, nodeId: String, shareId: String, role: String = "VIEWER"): Pair<Int, String?> =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"shareId":"$shareId",${ShareWriteProof.field(shareId)},"nodeId":"$nodeId","role":"$role","wrap":"$wrap","sealedName":"$sealedName"}""")
            .`when`().post("/api/shares").then().extract().answer()

    private fun createIdentity(shareId: String, identityId: String): Pair<Int, String?> =
        given().contentType(ContentType.JSON)
            .body("""{"identityId":"$identityId","sealedDisplayName":"c2VhbGVkLWNvbnRyaWJ1dG9yLW5hbWU="}""")
            .header(ShareWriteProof.HEADER, ShareWriteProof.token(shareId))
            .`when`().post("/api/shares/$shareId/identities").then().extract().answer()
}
