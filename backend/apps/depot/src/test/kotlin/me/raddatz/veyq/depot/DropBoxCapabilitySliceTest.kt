package me.raddatz.veyq.depot

import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.PUBLIC_SHARES
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.PUBLIC_SHARES_DROP_BOX
import me.raddatz.veyq.core.platform.capabilities.CapabilitySwitchFixtures
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import java.util.Base64
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Test

/**
 * The drop-box creation gate (ADR 0088, #440 gap 2): Depot's [DepotNodeKindRules.capabilityToCreate] names
 * `core.public-shares.drop-box` for a `DEPOT_DROPBOX`, and core's `NodesHandler.createInScope` refuses a new one with
 * `422 capability_disabled` while that id — or its parent, by the AND along the path — is switched off. Each state is
 * a stored switch row ([CapabilitySwitchFixtures]), read by the production switches, as the Server Owner's Admin API
 * leaves it.
 *
 * **Every negative has a positive control on the same predicate**: a `FOLDER` created at the same parent under the
 * same switch (the parent, the caller and the route are fine — only the kind's capability is refused), and the very
 * same request, same node id and slug, created once the switch is back on (so the refusal stored nothing: a stored row
 * would answer `id_taken`). Only creation is refused: a drop box that exists is renamed under the switch as before
 * (ADR 0088 decision 5, a feature switch refuses only what is new).
 */
@QuarkusTest
class DropBoxCapabilitySliceTest {

    private val capabilities = CapabilitySwitchFixtures

    @Test
    fun dropBoxOffRefusesANewDropBoxAndCreatesAFolderAtTheSameParent() {
        val (userId, scopeId) = seedMemberScope()
        val parent = create(userId, scopeId, null, NodeEntity.FOLDER).statusCode(201).extractId()
        val dropBox = UUID.randomUUID().toString()
        val slug = slug()

        capabilities.with(PUBLIC_SHARES_DROP_BOX) {
            create(userId, scopeId, parent, DepotNodeKinds.DROPBOX, dropBox, slug)
                .statusCode(422).body("code", equalTo("capability_disabled"))
            // Control: the same parent, caller and route take a folder.
            create(userId, scopeId, parent, NodeEntity.FOLDER).statusCode(201)
        }
        // Control: the same request with the switch back on is created — and the refusal above stored nothing.
        create(userId, scopeId, parent, DepotNodeKinds.DROPBOX, dropBox, slug).statusCode(201)
    }

    @Test
    fun aSwitchedOffParentRefusesANewDropBoxThroughThePath() {
        val (userId, scopeId) = seedMemberScope()
        capabilities.with(PUBLIC_SHARES) {
            // `.drop-box`'s own entry is on; the AND along the path refuses it.
            create(userId, scopeId, null, DepotNodeKinds.DROPBOX).statusCode(422).body("code", equalTo("capability_disabled"))
            create(userId, scopeId, null, NodeEntity.FOLDER).statusCode(201) // control
        }
    }

    @Test
    fun anExistingDropBoxIsRenamedWhileTheSwitchIsOff() {
        val (userId, scopeId) = seedMemberScope()
        val dropBox = create(userId, scopeId, null, DepotNodeKinds.DROPBOX).statusCode(201).extractId()
        capabilities.with(PUBLIC_SHARES_DROP_BOX) {
            given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
                .body("""{"encryptedName":"cmVuYW1lZA==","slugToken":"${slug()}","wrappedContentKey":"cmUtd3JhcA==","epoch":0,"placementVersion":0}""")
                .`when`().patch("/api/scopes/$scopeId/nodes/$dropBox")
                .then().statusCode(204)
        }
    }

    private fun create(
        userId: String, scopeId: String, parent: String?, kind: String,
        nodeId: String = UUID.randomUUID().toString(), slug: String = slug(),
    ): ValidatableResponse {
        val parentField = parent?.let { "\"parentId\":\"$it\"," } ?: ""
        return given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{$parentField"nodeId":"$nodeId","kind":"$kind","encryptedName":"bmFtZQ==","slugToken":"$slug","orderIndex":0,"wrappedContentKey":"a21lbQ=="}""")
            .`when`().post("/api/scopes/$scopeId/nodes").then()
    }

    private fun slug(): String = Base64.getEncoder().encodeToString(UUID.randomUUID().toString().toByteArray())

    private fun ValidatableResponse.extractId(): String = extract().path("nodeId")
}
