package com.veyq.app.features.sharing

import com.veyq.app.shared.TestIds
import com.veyq.app.shared.scope.ScopeEntity
import com.veyq.app.testing.TestFixtures.CONTENT_KEY
import com.veyq.app.testing.TestFixtures.bearer
import com.veyq.app.testing.TestFixtures.seedMemberScope
import com.veyq.app.testing.TestFixtures.seedNode
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Test

/**
 * B1 (uc.sharing.share-note, ADR 0036 point 4): the server holds the recovery copy's epoch honestly. A share's
 * recovery copy of `K_share` is sealed under `K_SC` at the scope's **current** epoch, and the epoch is stored beside
 * it — so a create tagged with any other epoch is `409 stale_epoch` with nothing written (ADR 0024 Decision 6: a copy
 * under an epoch a removed member holds would be theirs to open), and a key without its epoch, or the reverse, is a
 * `400`. This proves nothing against an operator — the epoch check is a server check; the operator-facing guarantee
 * is the web `share.test.ts` W1, which a client runs with a key no link carries.
 */
@QuarkusTest
class ShareKeyRecoveryEpochSliceTest {

    private val wrap = "d3JhcC1rLW1lbS11bmRlci1zaGFyZQ=="
    private val sealedName = "c2VhbGVkLW5hbWU="
    private val shareKey = "cmVjb3ZlcmFibGUta2V5" // K_share under K_SC(epoch), opaque here

    @Test
    fun aCreateAtAnEpochOtherThanTheScopesCurrentIs409StaleEpochAndWritesNothing() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, wrappedContentKey = CONTENT_KEY)
        setScopeEpoch(scopeId, 2)

        for (epoch in listOf(1, 3)) {
            val shareId = TestIds.shareId()
            create(userId, memory, shareId, """"sealedShareKey":"$shareKey","sealedShareKeyEpoch":$epoch""")
                .statusCode(409).body("code", equalTo("stale_epoch"))
            listed(userId, memory).body("find { it.shareId == '$shareId' }", nullValue())
        }
    }

    @Test
    fun aKeyWithoutItsEpochAndAnEpochWithoutItsKeyAreEach400() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, wrappedContentKey = CONTENT_KEY)

        for (fields in listOf(""""sealedShareKey":"$shareKey"""", """"sealedShareKeyEpoch":0""")) {
            val shareId = TestIds.shareId()
            create(userId, memory, shareId, fields).statusCode(400).body("code", equalTo("invalid_request"))
            listed(userId, memory).body("find { it.shareId == '$shareId' }", nullValue())
        }
    }

    @Test
    fun aCreateAtTheCurrentEpochSucceedsAndTheManageListServesThatEpoch() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, wrappedContentKey = CONTENT_KEY)
        setScopeEpoch(scopeId, 2)
        val shareId = TestIds.shareId()

        create(userId, memory, shareId, """"sealedShareKey":"$shareKey","sealedShareKeyEpoch":2""").statusCode(201)
        listed(userId, memory)
            .body("find { it.shareId == '$shareId' }.sealedShareKey", equalTo(shareKey))
            .body("find { it.shareId == '$shareId' }.sealedShareKeyEpoch", equalTo(2))
    }

    @Test
    fun aShareWithoutARecoveryCopyCarriesNoEpoch() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, wrappedContentKey = CONTENT_KEY)
        val shareId = TestIds.shareId()

        create(userId, memory, shareId, null).statusCode(201)
        listed(userId, memory)
            .body("find { it.shareId == '$shareId' }.sealedShareKey", nullValue())
            .body("find { it.shareId == '$shareId' }.sealedShareKeyEpoch", nullValue())
    }

    // --- helpers ---

    /** Simulate a rotated scope: bump its current content-key epoch. */
    private fun setScopeEpoch(scopeId: String, epoch: Int) =
        QuarkusTransaction.requiringNew().run { ScopeEntity.findById(scopeId)!!.apply { currentEpoch = epoch }.persist() }

    private fun create(userId: String, nodeId: String, shareId: String, recovery: String?) =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"shareId":"$shareId","nodeId":"$nodeId","role":"VIEWER","wrap":"$wrap","sealedName":"$sealedName"${recovery?.let { ",$it" } ?: ""}}""")
            .`when`().post("/api/shares")
            .then()

    private fun listed(userId: String, nodeId: String) =
        given().header("Authorization", bearer(userId)).queryParam("nodeId", nodeId)
            .`when`().get("/api/shares")
            .then().statusCode(200)
}
