package me.raddatz.veyq.core.spaces.scope

import me.raddatz.veyq.core.membership.TestGrants
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestScopeRekey
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * ADR 0115's probes: a rotation replaces the scope key and re-seals what was under it in its own commit, so a rotation
 * that brings no re-sealed scope-level row is refused, and no chain link is stored. Written against the tree before it
 * was built (red there after their positive controls), rewritten to the contract's wire when it was.
 *
 * - [aRotationThatLeavesAScopeRootEntryUnderTheOldKeyIsRefused] — decision 2: a scope-root node's entry is sealed under
 *   the scope key; a rotation that does not re-seal it is `409 rekey_parts_missing`, nothing written.
 * - [aRotationStoresNoChainLink] — decision 1: nothing seals the old key under the new one; the epoch's succession
 *   statement is what the commit stores, and the chain's table is gone.
 */
@QuarkusTest
class ScopeKeyReplacedProbeSliceTest {

    @Test
    fun aRotationThatLeavesAScopeRootEntryUnderTheOldKeyIsRefused() {
        val empty = seedMemberScope(ScopeEntity.GROUP)
        rotate(empty.userId, empty.scopeId, UUID.randomUUID().toString())
            .statusCode(201) // control: nothing is sealed under the key but the grants, so nothing needs staging

        val group = seedMemberScope(ScopeEntity.GROUP)
        val root = seedNode(group.scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        rotate(group.userId, group.scopeId, UUID.randomUUID().toString())
            .statusCode(409).body("code", equalTo("rekey_parts_missing"))
        assertEquals(0, TestGrants.epochOf(group.scopeId), "nothing written")
        QuarkusTransaction.requiringNew().run {
            val node = NodeEntity.findById(root)!!
            assertEquals(CONTENT_KEY, node.wrappedContentKey, "the root's entry is untouched")
            assertFalse(node.rekeyDue, "no node is marked due by a refused rotation")
            assertEquals(0L, ScopeSuccessionEntity.count("scopeId", group.scopeId), "no succession statement stored")
        }

        // Control on the same scope: the root's entry staged, the rotation commits.
        rotate(group.userId, group.scopeId, TestScopeRekey.stageAll(group.scopeId)).statusCode(201)
        assertEquals(1, TestGrants.epochOf(group.scopeId))
    }

    @Test
    fun aRotationStoresNoChainLink() {
        val group = seedMemberScope(ScopeEntity.GROUP)
        assertEquals(0L, successions(group.scopeId), "control: a fresh scope has no statement")
        rotate(group.userId, group.scopeId, TestScopeRekey.stageAll(group.scopeId)).statusCode(201)
        assertEquals(1, TestGrants.epochOf(group.scopeId), "control: the rotation committed")
        assertEquals(1L, successions(group.scopeId), "control: the commit stored the epoch's succession statement")
        assertEquals(0L, tables("veyq_scope_epoch"), "no table holds an old key sealed under a new one")
        assertEquals(1L, tables("veyq_scope_succession"), "control: the query sees the schema")
    }

    /** A rotation body to epoch 1 under [rekeyId], the owner re-granted, nobody removed or pending. */
    private fun rotate(owner: String, scopeId: String, rekeyId: String): ValidatableResponse =
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body(
                """{"epoch":1,${TestScopeRekey.SUCCESSION_JSON},"rekeyId":"$rekeyId","grants":[{"userId":"$owner","wrappedGrant":"bmV3",""" +
                    """${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"${TestGrants.digestOf(owner)}"}],"pendingUserIds":[]}""",
            )
            .`when`().post("/api/scopes/$scopeId/epochs").then()

    private fun successions(scopeId: String): Long =
        QuarkusTransaction.requiringNew().call { ScopeSuccessionEntity.count("scopeId", scopeId) }

    private fun tables(name: String): Long = QuarkusTransaction.requiringNew().call {
        (ScopeSuccessionEntity.getEntityManager()
            .createNativeQuery("select count(*) from information_schema.tables where lower(table_name) = :name")
            .setParameter("name", name).singleResult as Number).toLong()
    }
}
