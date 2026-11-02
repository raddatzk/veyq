package me.raddatz.veyq.memories.itemfavorites

import me.raddatz.veyq.core.node.rekey.ScopeRekeyStage
import me.raddatz.veyq.core.membership.TestGrants
import me.raddatz.veyq.core.node.rekey.ScopeRekeyCommit
import me.raddatz.veyq.core.node.rekey.ScopeRekeyField
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedScopeMember
import me.raddatz.veyq.core.testing.TestScopeRekey
import io.quarkus.arc.Arc
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.not
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Memories' item marks re-sealed through its scope re-key participant (ADR 0115 decision 2): the marks are sealed under
 * the scope key itself, so a rotation's commit swaps in every member's row the rotator staged by its opaque `markId`, or
 * refuses — a row with no staged value `409 rekey_parts_missing`, one written since it was staged `409
 * rekey_parts_stale` — and a mark written under the old epoch after it is `409 stale_epoch` (decision 3). Each staged
 * value names the digest of the ciphertext it was opened from, so a mark rewritten between the rotator's read and its
 * stage is refused at the stage (`409 rekey_parts_stale`), never replaced by a re-seal of what it replaced.
 */
@QuarkusTest
class ItemFavoritesScopeRekeySliceTest {

    @Test
    fun everyMembersMarksAreReSealedThroughTheParticipant() {
        val w = world()
        // The listing names rows by an opaque id, never a member's (ADR 0068 § 9), and carries every member's.
        val page = list(w.owner, w.scopeId).statusCode(200).body("currentEpoch", equalTo(0)).body("marks.size()", equalTo(2))
            .body("marks.markId", not(hasItem(w.owner))).body("marks.markId", not(hasItem(w.member))).extract()
        val markIds = page.path<List<String>>("marks.markId")
        val rekeyId = coreStaged(w.scopeId)
        stage(w.owner, w.scopeId, rekeyId, markIds.map { it to "cmUtc2VhbGVkLSR7aXR9" }).statusCode(204)
        rotate(w.owner, w.scopeId, rekeyId).statusCode(201)
        tx {
            assertEquals(listOf("cmUtc2VhbGVkLSR7aXR9", "cmUtc2VhbGVkLSR7aXR9"), ItemFavoritesEntity.list("scopeId", w.scopeId).map { it.ciphertext })
        }
        // A mark sealed under the replaced key is refused; at the current epoch it lands.
        put(w.member, w.scopeId, w.node, epoch = 0).statusCode(409).body("code", equalTo("stale_epoch"))
        put(w.member, w.scopeId, w.node, epoch = 1).statusCode(204)
    }

    @Test
    fun aMarkWithNoStagedValueRefusesTheRotation() {
        val w = world()
        val rekeyId = coreStaged(w.scopeId)
        stage(w.owner, w.scopeId, rekeyId, list(w.owner, w.scopeId).extract().path<List<String>>("marks.markId").map { it to SEALED }).statusCode(204)
        // A member marks another Memory after the rotator staged: a row the rotation has no value for.
        put(w.member, w.scopeId, UUID.randomUUID().toString(), epoch = 0).statusCode(204)
        rotate(w.owner, w.scopeId, rekeyId).statusCode(409).body("code", equalTo("rekey_parts_missing"))
        assertEquals(0, TestGrants.epochOf(w.scopeId), "nothing written")
        assertEquals(0L, tx { ItemFavoritesEntity.count("scopeId = ?1 and ciphertext = ?2", w.scopeId, SEALED) })
        // Control: the new row staged too, the rotation commits.
        stage(w.owner, w.scopeId, rekeyId, list(w.owner, w.scopeId).extract().path<List<String>>("marks.markId").map { it to SEALED }).statusCode(204)
        rotate(w.owner, w.scopeId, rekeyId).statusCode(201)
    }

    @Test
    fun aMarkRewrittenSinceItWasStagedIsStale() {
        val w = world()
        val rekeyId = coreStaged(w.scopeId)
        stage(w.owner, w.scopeId, rekeyId, list(w.owner, w.scopeId).extract().path<List<String>>("marks.markId").map { it to SEALED }).statusCode(204)
        put(w.member, w.scopeId, w.node, epoch = 0, ciphertext = "Y2hhbmdlZA==").statusCode(204)
        rotate(w.owner, w.scopeId, rekeyId).statusCode(409).body("code", equalTo("rekey_parts_stale"))
        assertEquals("Y2hhbmdlZA==", tx { ItemFavoritesEntity.findById(ItemFavoritesEntity.key(w.member, w.scopeId, w.node))!!.ciphertext })
        stage(w.owner, w.scopeId, rekeyId, list(w.owner, w.scopeId).extract().path<List<String>>("marks.markId").map { it to SEALED }).statusCode(204)
        rotate(w.owner, w.scopeId, rekeyId).statusCode(201)
    }

    @Test
    fun aMarkRewrittenBetweenTheReadAndTheStageIsRefusedAtTheStage() {
        val w = world()
        val rekeyId = coreStaged(w.scopeId)
        val read = list(w.owner, w.scopeId).extract()
        val opened = read.path<List<String>>("marks.markId").zip(read.path<List<String>>("marks.ciphertext")).toMap()
        // A member rewrites their mark after the rotator read the page, before it stages its re-seal.
        put(w.member, w.scopeId, w.node, epoch = 0, ciphertext = "Y2hhbmdlZA==").statusCode(204)
        stage(w.owner, w.scopeId, rekeyId, opened.keys.map { it to SEALED }, opened.mapValues { ScopeRekeyStage.digest(it.value)!! })
            .statusCode(409).body("code", equalTo("rekey_parts_stale"))
        assertEquals("Y2hhbmdlZA==", tx { ItemFavoritesEntity.findById(ItemFavoritesEntity.key(w.member, w.scopeId, w.node))!!.ciphertext })
        rotate(w.owner, w.scopeId, rekeyId).statusCode(409).body("code", equalTo("rekey_parts_missing")) // nothing was staged
        // Control: read again, the same stage lands, and the rotation commits.
        stage(w.owner, w.scopeId, rekeyId, list(w.owner, w.scopeId).extract().path<List<String>>("marks.markId").map { it to SEALED }).statusCode(204)
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

    /** Core's scope-level rows of [scopeId] staged under a fresh re-key id (none but the grants here), and the id. */
    private fun coreStaged(scopeId: String): String {
        val rekeyId = UUID.randomUUID().toString()
        tx {
            val commit = Arc.container().instance(ScopeRekeyCommit::class.java).get()
            commit.stage(rekeyId, ScopeEntity.findById(scopeId)!!, emptyList<ScopeRekeyField>())
        }
        return rekeyId
    }

    private fun put(userId: String, scopeId: String, nodeId: String, epoch: Int, ciphertext: String = "bWFya3M="): ValidatableResponse =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"ciphertext":"$ciphertext","epoch":$epoch}""")
            .`when`().put("/api/scopes/$scopeId/nodes/$nodeId/item-favorites").then()

    private fun list(caller: String, scopeId: String): ValidatableResponse =
        given().header("Authorization", bearer(caller)).`when`().get("/api/scopes/$scopeId/item-favorites-rekey").then()

    /** Stage [marks] (`markId` to its re-sealed ciphertext), each naming the digest of the ciphertext it was opened from:
     *  [opened]'s, or by default what the row holds as the rotator reads it now. */
    private fun stage(
        caller: String, scopeId: String, rekeyId: String, marks: List<Pair<String, String>>,
        opened: Map<String, String> = currentDigests(scopeId),
    ): ValidatableResponse =
        given().header("Authorization", bearer(caller)).contentType(ContentType.JSON)
            .body(
                """{"fromEpoch":0,"marks":[${marks.joinToString(",") { (id, c) ->
                    """{"markId":"$id","ciphertext":"$c","replacedDigest":"${opened[id] ?: ScopeRekeyStage.digest("absent")}"}"""
                }}]}""",
            )
            .`when`().post("/api/scopes/$scopeId/item-favorites-rekey/$rekeyId").then()

    private fun currentDigests(scopeId: String): Map<String, String> =
        tx { ItemFavoritesEntity.list("scopeId", scopeId).associate { it.markId to ScopeRekeyStage.digest(it.ciphertext)!! } }

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
        const val SEALED = "cmUtc2VhbGVk"
    }
}
