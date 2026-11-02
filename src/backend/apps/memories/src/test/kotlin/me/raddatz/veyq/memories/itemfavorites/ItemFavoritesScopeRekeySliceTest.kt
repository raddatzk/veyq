package me.raddatz.veyq.memories.itemfavorites

import me.raddatz.veyq.core.node.rekey.ScopeRekeyStageEntity
import me.raddatz.veyq.core.membership.TestGrants
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedScopeMember
import me.raddatz.veyq.core.testing.TestScopeRekey
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.not
import org.hamcrest.CoreMatchers.nullValue
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Memories' item marks moved through its scope re-key participant (ADR 0115 decision 2, owner 2026-11-02): each row is
 * sealed under a value key of its own and only that key's wrap is under the scope key, so a rotation's commit swaps in
 * every member's re-wrapped key the rotator staged by its opaque `markId` — **wraps only**: no row's ciphertext is served,
 * staged or rewritten, so a rotator can never rewrite another member's marks — or refuses: a row with no staged wrap
 * `409 rekey_parts_missing`, one written since it was staged (a write mints a fresh key, so a fresh wrap) `409
 * rekey_parts_stale`, a stage that writes anything but a wrap `422 rekey_writes_value` — and a mark wrapped under the old
 * epoch after it is `409 stale_epoch` (decision 3). Each refusal beside its positive control.
 */
@QuarkusTest
class ItemFavoritesScopeRekeySliceTest {

    @Test
    fun everyMembersMarksAreReWrappedThroughTheParticipantAndNeverRewritten() {
        val w = world()
        // The listing names rows by an opaque id, never a member's (ADR 0068 § 9), carries every member's — and serves
        // each row's wrap, never its ciphertext.
        val page = list(w.owner, w.scopeId).statusCode(200).body("currentEpoch", equalTo(0)).body("marks.size()", equalTo(2))
            .body("marks.markId", not(hasItem(w.owner))).body("marks.markId", not(hasItem(w.member)))
            .body("marks[0].ciphertext", nullValue()).body("marks[0].wrappedValueKey", not(nullValue())).extract()
        val markIds = page.path<List<String>>("marks.markId")
        val before = tx { ItemFavoritesEntity.list("scopeId", w.scopeId).associate { it.markId to it.ciphertext } }
        val rekeyId = UUID.randomUUID().toString() // core has no scope-level row here but the grants
        stage(w.owner, w.scopeId, rekeyId, markIds.map { it to REWRAPPED }).statusCode(204)
        rotate(w.owner, w.scopeId, rekeyId).statusCode(201)
        tx {
            val rows = ItemFavoritesEntity.list("scopeId", w.scopeId)
            assertEquals(listOf(REWRAPPED, REWRAPPED), rows.map { it.wrappedValueKey }, "every row's key re-wrapped")
            assertEquals(before, rows.associate { it.markId to it.ciphertext }, "no row's ciphertext rewritten")
        }
        // A mark wrapped under the replaced key is refused; at the current epoch it lands.
        put(w.member, w.scopeId, w.node, epoch = 0).statusCode(409).body("code", equalTo("stale_epoch"))
        put(w.member, w.scopeId, w.node, epoch = 1).statusCode(204)
    }

    /** A rotation re-wraps keys and never rewrites a value: a staged write of a row's ciphertext — what a stage carried
     *  before the value keys, or what a rotator would forge to rewrite another member's marks — is refused, nothing
     *  stored; the schema has no field for it, so it reaches the stage only beside the HTTP surface. */
    @Test
    fun aStageThatRewritesAMembersMarksIsRefusedAndWritesNothing() {
        val w = world()
        val markIds = list(w.owner, w.scopeId).extract().path<List<String>>("marks.markId")
        val memberMark = tx { ItemFavoritesEntity.findById(ItemFavoritesEntity.key(w.member, w.scopeId, w.node))!!.markId }
        val rekeyId = UUID.randomUUID().toString()
        stage(w.owner, w.scopeId, rekeyId, markIds.map { it to REWRAPPED }).statusCode(204)
        tx {
            ScopeRekeyStageEntity().apply {
                id = UUID.randomUUID().toString(); this.rekeyId = rekeyId; scopeId = w.scopeId
                part = ItemFavoritesScopeRekeyParticipant.PART; itemId = memberMark; field = "mark.ciphertext"
                stageValue = "Zm9yZ2Vk"; replaced = null; createdAt = Instant.now()
            }.persist()
        }
        rotate(w.owner, w.scopeId, rekeyId).statusCode(422).body("code", equalTo("rekey_writes_value"))
        assertEquals(0, TestGrants.epochOf(w.scopeId), "nothing written")
        tx {
            val row = ItemFavoritesEntity.findById(ItemFavoritesEntity.key(w.member, w.scopeId, w.node))!!
            assertEquals("bWFya3M=", row.ciphertext, "the member's marks stand")
            assertEquals(0L, ItemFavoritesEntity.count("scopeId = ?1 and wrappedValueKey = ?2", w.scopeId, REWRAPPED), "no wrap swapped in")
        }
        // Control: the same commit carrying only the re-wrapped keys lands.
        tx { ScopeRekeyStageEntity.delete("rekeyId = ?1 and field = ?2", rekeyId, "mark.ciphertext") }
        rotate(w.owner, w.scopeId, rekeyId).statusCode(201)
        tx { assertEquals("bWFya3M=", ItemFavoritesEntity.findById(ItemFavoritesEntity.key(w.member, w.scopeId, w.node))!!.ciphertext) }
    }

    @Test
    fun aMarkWithNoStagedWrapRefusesTheRotation() {
        val w = world()
        val rekeyId = UUID.randomUUID().toString()
        stage(w.owner, w.scopeId, rekeyId, list(w.owner, w.scopeId).extract().path<List<String>>("marks.markId").map { it to REWRAPPED }).statusCode(204)
        // A member marks another Memory after the rotator staged: a row the rotation has no wrap for.
        put(w.member, w.scopeId, UUID.randomUUID().toString(), epoch = 0).statusCode(204)
        rotate(w.owner, w.scopeId, rekeyId).statusCode(409).body("code", equalTo("rekey_parts_missing"))
        assertEquals(0, TestGrants.epochOf(w.scopeId), "nothing written")
        assertEquals(0L, tx { ItemFavoritesEntity.count("scopeId = ?1 and wrappedValueKey = ?2", w.scopeId, REWRAPPED) })
        // Control: the new row staged too, the rotation commits.
        stage(w.owner, w.scopeId, rekeyId, list(w.owner, w.scopeId).extract().path<List<String>>("marks.markId").map { it to REWRAPPED }).statusCode(204)
        rotate(w.owner, w.scopeId, rekeyId).statusCode(201)
    }

    @Test
    fun aMarkRewrittenSinceItWasStagedIsStale() {
        val w = world()
        val rekeyId = UUID.randomUUID().toString()
        stage(w.owner, w.scopeId, rekeyId, list(w.owner, w.scopeId).extract().path<List<String>>("marks.markId").map { it to REWRAPPED }).statusCode(204)
        // A rewrite mints a fresh value key: its wrap is no longer the one the stage replaces.
        put(w.member, w.scopeId, w.node, epoch = 0, ciphertext = "Y2hhbmdlZA==").statusCode(204)
        rotate(w.owner, w.scopeId, rekeyId).statusCode(409).body("code", equalTo("rekey_parts_stale"))
        assertEquals("Y2hhbmdlZA==", tx { ItemFavoritesEntity.findById(ItemFavoritesEntity.key(w.member, w.scopeId, w.node))!!.ciphertext })
        stage(w.owner, w.scopeId, rekeyId, list(w.owner, w.scopeId).extract().path<List<String>>("marks.markId").map { it to REWRAPPED }).statusCode(204)
        rotate(w.owner, w.scopeId, rekeyId).statusCode(201)
    }

    /** The window before the stage: a mark rewritten after the rotator read it is refused by the commit like one after
     *  the stage — the stage carries the wrap it read — and a field the stage does not name is malformed. */
    @Test
    fun aMarkRewrittenBetweenTheReadAndTheStageIsRefusedAtTheCommit() {
        val w = world()
        val rekeyId = UUID.randomUUID().toString()
        val read = list(w.owner, w.scopeId).extract()
        val opened = read.path<List<String>>("marks.markId").zip(read.path<List<String>>("marks.wrappedValueKey")).toMap()
        // A member rewrites their mark after the rotator read the page, before it stages its re-wrap.
        put(w.member, w.scopeId, w.node, epoch = 0, ciphertext = "Y2hhbmdlZA==").statusCode(204)
        stage(w.owner, w.scopeId, rekeyId, opened.keys.map { it to REWRAPPED }, opened).statusCode(204)
        rotate(w.owner, w.scopeId, rekeyId).statusCode(409).body("code", equalTo("rekey_parts_stale"))
        assertEquals("Y2hhbmdlZA==", tx { ItemFavoritesEntity.findById(ItemFavoritesEntity.key(w.member, w.scopeId, w.node))!!.ciphertext })
        // A set smuggled beside a wrap has no field to land in: the batch is refused.
        given().header("Authorization", bearer(w.owner)).contentType(ContentType.JSON)
            .body("""{"fromEpoch":0,"marks":[{"markId":"${opened.keys.first()}","wrappedValueKey":"$REWRAPPED","replacedValueKey":"$REWRAPPED","ciphertext":"Zm9yZ2Vk"}]}""")
            .`when`().post("/api/scopes/${w.scopeId}/item-favorites-rekey/$rekeyId").then()
            .statusCode(400).body("code", equalTo("invalid_request"))
        // Control: read again, the same stage lands, and the rotation commits.
        stage(w.owner, w.scopeId, rekeyId, list(w.owner, w.scopeId).extract().path<List<String>>("marks.markId").map { it to REWRAPPED }).statusCode(204)
        rotate(w.owner, w.scopeId, rekeyId).statusCode(201)
    }

    @Test
    fun onlyTheRotatorReadsAndStagesTheMarks() {
        val w = world()
        list(w.member, w.scopeId).statusCode(403).body("code", equalTo("not_scope_owner"))
        stage(w.member, w.scopeId, UUID.randomUUID().toString(), emptyList()).statusCode(403)
        given().header("Authorization", bearer(w.owner)).contentType(ContentType.JSON).body("""{"fromEpoch":1}""")
            .`when`().post("/api/scopes/${w.scopeId}/item-favorites-rekey/${UUID.randomUUID()}").then()
            .statusCode(409).body("code", equalTo("stale_epoch"))
    }

    private data class World(val owner: String, val member: String, val scopeId: String, val node: String)

    /** A group whose owner and member each marked items in one Memory, at epoch 0. */
    private fun world(): World {
        val (owner, scopeId, _) = seedMemberScope(ScopeEntity.GROUP)
        val member = seedScopeMember(scopeId, grantedEpoch = 0)
        val node = UUID.randomUUID().toString()
        put(owner, scopeId, node, epoch = 0).statusCode(204)
        put(member, scopeId, node, epoch = 0).statusCode(204)
        return World(owner, member, scopeId, node)
    }

    /** A member's write of their marks: the ciphertext under a fresh value key, its fresh wrap beside it. */
    private fun put(userId: String, scopeId: String, nodeId: String, epoch: Int, ciphertext: String = "bWFya3M="): ValidatableResponse =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"ciphertext":"$ciphertext","wrappedValueKey":"${TestGrants.freshWrap()}","epoch":$epoch}""")
            .`when`().put("/api/scopes/$scopeId/nodes/$nodeId/item-favorites").then()

    private fun list(caller: String, scopeId: String): ValidatableResponse =
        given().header("Authorization", bearer(caller)).`when`().get("/api/scopes/$scopeId/item-favorites-rekey").then()

    /** Stage [marks] (`markId` to its re-wrapped value key), each beside the wrap it replaces: [opened]'s, or by default
     *  what the row holds as the rotator reads it now. */
    private fun stage(
        caller: String, scopeId: String, rekeyId: String, marks: List<Pair<String, String>>,
        opened: Map<String, String> = currentWraps(scopeId),
    ): ValidatableResponse =
        given().header("Authorization", bearer(caller)).contentType(ContentType.JSON)
            .body(
                """{"fromEpoch":0,"marks":[${marks.joinToString(",") { (id, wrap) ->
                    """{"markId":"$id","wrappedValueKey":"$wrap","replacedValueKey":"${opened[id] ?: "YWJzZW50"}"}"""
                }}]}""",
            )
            .`when`().post("/api/scopes/$scopeId/item-favorites-rekey/$rekeyId").then()

    private fun currentWraps(scopeId: String): Map<String, String> =
        tx { ItemFavoritesEntity.list("scopeId", scopeId).associate { it.markId to it.wrappedValueKey } }

    private fun rotate(owner: String, scopeId: String, rekeyId: String): ValidatableResponse =
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body(
                """{"epoch":1,${TestScopeRekey.SUCCESSION_JSON},"rekeyId":"$rekeyId",""" +
                    """"grants":[{"userId":"$owner","wrappedGrant":"bmV3",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"${TestGrants.digestOf(owner)}"}],""" +
                    """${TestGrants.othersPendingJson(scopeId, owner)}}""",
            )
            .`when`().post("/api/scopes/$scopeId/epochs").then()

    private fun <T> tx(block: () -> T): T = QuarkusTransaction.requiringNew().call(block)

    private companion object {
        const val REWRAPPED = "cmUtd3JhcHBlZA=="
    }
}
