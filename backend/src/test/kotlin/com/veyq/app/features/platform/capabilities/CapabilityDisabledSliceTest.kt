package com.veyq.app.features.platform.capabilities

import com.veyq.app.shared.TestIds
import com.veyq.app.testing.TestFixtures.DROP_PRIVATE_KEY
import com.veyq.app.testing.TestFixtures.DROP_PUBLIC_KEY
import com.veyq.app.testing.TestFixtures.bearer
import com.veyq.app.testing.TestFixtures.seedUser
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.hasItem
import org.hamcrest.CoreMatchers.not
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Proves an operator-disabled capability is enforced BOTH ways (uc.platform.capability-discovery
 * operator-disable-enforced + sharing-first-consumer, cr-0103, ADR 0032): with `sharing.uploader-links`
 * disabled instance-wide, it vanishes from the manifest AND createShare(role=UPLOADER) is rejected
 * `422 capability_disabled` — while the still-active `sharing.public-links` is advertised and its create path
 * passes the gate. The capability check runs before any node lookup, so a disabled role reads as instance
 * policy (a VIEWER share reaches the node lookup and 404s on the unknown node — it got past the gate).
 */
@QuarkusTest
@TestProfile(CapabilityDisabledSliceTest.UploaderDisabled::class)
class CapabilityDisabledSliceTest {

    class UploaderDisabled : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf("veyq.capabilities.disabled" to "sharing.uploader-links")
    }

    private val wrap = "d3JhcC1rLW1lbS11bmRlci1zaGFyZQ=="
    private val sealedName = "c2VhbGVkLW5hbWU="

    @Test
    fun disabledCapabilityIsOmittedFromTheManifestButActiveOnesRemain() {
        given().`when`().get("/api/setup")
            .then().statusCode(200)
            .body("capabilities.id", not(hasItem("sharing.uploader-links")))
            .body("capabilities.id", hasItem("sharing.public-links"))
    }

    @Test
    fun createUploaderShareIsRejectedWhileViewerPassesTheGate() {
        val bearer = bearer(seedUser())
        val someNode = UUID.randomUUID()

        // UPLOADER needs the disabled `sharing.uploader-links` → 422 capability_disabled, checked before any node
        // lookup so it reads as instance policy (not a node/scope error).
        given().header("Authorization", bearer).contentType(ContentType.JSON)
            .body("""{"shareId":"${TestIds.shareId()}","nodeId":"$someNode","role":"UPLOADER","sealedDropPublicKey":"$DROP_PUBLIC_KEY","sealedDropPrivateKey":"$DROP_PRIVATE_KEY","sealedName":"$sealedName"}""")
            .`when`().post("/api/shares")
            .then().statusCode(422).body("code", equalTo("capability_disabled"))

        // VIEWER needs `sharing.public-links` (still active) → the gate passes and the handler proceeds to the
        // node lookup, 404ing on the unknown node. Proves public-links was NOT collaterally disabled.
        given().header("Authorization", bearer).contentType(ContentType.JSON)
            .body("""{"shareId":"${TestIds.shareId()}","nodeId":"$someNode","role":"VIEWER","wrap":"$wrap","sealedName":"$sealedName"}""")
            .`when`().post("/api/shares")
            .then().statusCode(404).body("code", equalTo("node_not_found"))
    }
}
