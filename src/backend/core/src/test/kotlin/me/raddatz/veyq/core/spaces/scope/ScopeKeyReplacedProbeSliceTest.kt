package me.raddatz.veyq.core.spaces.scope

import me.raddatz.veyq.core.membership.TestGrants
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
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * ADR 0115's probes, written against the tree before it is built: a rotation replaces the scope key and re-seals what
 * was under it in its own commit, so a rotation that brings no re-sealed scope-level row is refused, and no chain link is
 * stored. Each case fails on today's code after its positive control passes.
 *
 * - [aRotationThatLeavesAScopeRootEntryUnderTheOldKeyIsRefused] — decision 2: a scope-root node's entry is sealed under
 *   the scope key; a rotation that does not re-seal it is `409 rekey_parts_missing`, nothing written. Today it commits.
 * - [aRotationStoresNoChainLink] — decision 1: nothing seals the old key under the new one. Today the rotation stores
 *   `wrappedPrev` in `veyq_scope_epoch`.
 *
 * Disabled until ADR 0115's backend phase enables them; the red run is quoted in its phase report.
 */
@Disabled("ADR 0115 phase 1 probes: red on the untouched tree by design; its backend phase enables them")
@QuarkusTest
class ScopeKeyReplacedProbeSliceTest {

    @Test
    fun aRotationThatLeavesAScopeRootEntryUnderTheOldKeyIsRefused() {
        val empty = seedMemberScope(ScopeEntity.GROUP)
        rotate(empty.userId, empty.scopeId).statusCode(201) // control: nothing is sealed under the key but the grants

        val group = seedMemberScope(ScopeEntity.GROUP)
        seedNode(group.scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        rotate(group.userId, group.scopeId).statusCode(409).body("code", equalTo("rekey_parts_missing"))
        assertEquals(0, TestGrants.epochOf(group.scopeId), "nothing written")
    }

    @Test
    fun aRotationStoresNoChainLink() {
        val group = seedMemberScope(ScopeEntity.GROUP)
        assertEquals(0L, links(group.scopeId), "control: a fresh scope has no link")
        rotate(group.userId, group.scopeId).statusCode(201)
        assertEquals(1, TestGrants.epochOf(group.scopeId), "control: the rotation committed")
        assertEquals(0L, links(group.scopeId))
    }

    /** Today's rotation body to epoch 1, the owner re-granted, nobody removed or pending. */
    private fun rotate(owner: String, scopeId: String): ValidatableResponse =
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body(
                """{"epoch":1,"wrappedPrev":"cHJldg==","grants":[{"userId":"$owner","wrappedGrant":"bmV3",""" +
                    """${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"${TestGrants.digestOf(owner)}"}],"pendingUserIds":[]}""",
            )
            .`when`().post("/api/scopes/$scopeId/epochs").then()

    private fun links(scopeId: String): Long = QuarkusTransaction.requiringNew().call { ScopeEpochEntity.count("scopeId", scopeId) }
}
