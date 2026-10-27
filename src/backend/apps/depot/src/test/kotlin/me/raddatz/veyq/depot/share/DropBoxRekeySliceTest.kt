package me.raddatz.veyq.depot.share

import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.ShareIdentityEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.seedScopeMember
import me.raddatz.veyq.depot.DepotNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import java.time.Instant
import java.util.Base64
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A Depot drop box after a rotation (ADR 0111 decision 11, last bullet; ADR 0080 Decision 11): members place nothing of
 * their own in it, so the drop box itself — its kind declares identity folders through Depot's `WriteOnlyGuestKinds` —
 * and its guests' identity folders are re-keyed by an owner only. The listing marks them for a member, who is refused
 * `not_scope_owner` at their parts and commit with nothing moved; a `FILE` beside the box is the member's, and an owner
 * reads the box's parts — each the control on the same predicate. A Memory that carries a drop key only for its uploader
 * link is any member's (`MediaNodeRekeySliceTest.aMemberReKeysAMemoryAContributorLinkGaveADropKey`); the identity
 * folder walk on its own is `RekeyCoreSliceTest`'s.
 */
@QuarkusTest
class DropBoxRekeySliceTest {

    @Test
    fun aMemberIsRefusedTheDropBoxAndItsIdentityFolderAndAnOwnerIsNot() {
        val w = world()
        val member = seedScopeMember(w.scopeId, grantedEpoch = 1)
        val listing = get(w, "/rekey/nodes", member).statusCode(200)
        for (n in listOf(w.box, w.folder)) listing.body("nodes.find { it.id == '$n' }.ownerOnly", equalTo(true))
        listing.body("nodes.find { it.id == '${w.file}' }.ownerOnly", equalTo(false))

        for (n in listOf(w.box, w.folder)) {
            get(w, "/rekey/nodes/$n", member).statusCode(403).body("code", equalTo("not_scope_owner"))
            post(w, "/rekey/nodes/$n/${UUID.randomUUID()}/parts", """{"fromEpoch":0}""", member)
                .statusCode(403).body("code", equalTo("not_scope_owner"))
        }
        post(w, "/rekey/nodes/${w.box}/${UUID.randomUUID()}", boxCommit(), member).statusCode(403).body("code", equalTo("not_scope_owner"))
        QuarkusTransaction.requiringNew().run {
            val box = NodeEntity.findById(w.box)!!
            assertEquals(listOf<Any?>(0, OLD_DROP_PRIVATE), listOf(box.epoch, box.sealedDropPrivateKey), "a refused re-key moves nothing")
        }

        // controls: the file beside the box is the member's; the box's parts are an owner's to read
        get(w, "/rekey/nodes/${w.file}", member).statusCode(200)
        get(w, "/rekey/nodes/${w.box}", w.owner).statusCode(200).body("shares[0].hasDropPublicKey", equalTo(true))
    }

    // --- the world --------------------------------------------------------------------------------------------------

    /** An owner's GROUP scope rotated to epoch 1 (the owner's grant follows): a drop box at the root with its drop key,
     *  one `WRITE_ONLY` link and one guest identity whose identity folder is in it, and a `FILE` beside the box. */
    private data class World(val owner: String, val scopeId: String, val box: String, val folder: String, val file: String)

    private fun world(): World {
        val (owner, scopeId) = seedMemberScope(ScopeEntity.GROUP)
        val box = seedNode(scopeId, DepotNodeKinds.DROPBOX, wrappedContentKey = CONTENT_KEY)
        val file = seedNode(scopeId, DepotNodeKinds.FILE, wrappedContentKey = CONTENT_KEY)
        val shareId = Base64.getUrlEncoder().withoutPadding().encodeToString(UUID.randomUUID().toString().toByteArray()).take(43)
        val identityId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            NodeEntity.findById(box)!!.sealedDropPrivateKey = OLD_DROP_PRIVATE
            ShareEntity().apply {
                id = shareId; ownerAccount = owner; this.scopeId = scopeId; nodeId = box; role = ShareEntity.WRITE_ONLY
                sealedDropPublicKey = "b2xkLWRyb3AtcHVi"; sealedShareKey = "cmVjb3Zlcnk="; sealedShareKeyEpoch = 0; createdAt = Instant.now()
            }.persist()
            ShareIdentityEntity().apply {
                id = identityId; this.shareId = shareId; sealedDisplayName = "Z3Vlc3QtbmFtZQ=="; tokenHash = "h-$identityId".take(64)
                wrappedIdentityKey = "b2xkLWstdXA="
            }.persist()
        }
        val folder = seedNode(scopeId, NodeEntity.FOLDER, parentId = box, wrappedContentKey = CONTENT_KEY)
        QuarkusTransaction.requiringNew().run {
            NodeEntity.findById(folder)!!.shareIdentityId = identityId
            ScopeEntity.findById(scopeId)!!.currentEpoch = 1
            MembershipEntity.update("grantedEpoch = 1 where scopeId = ?1 and userId = ?2", scopeId, owner)
        }
        return World(owner, scopeId, box, folder, file)
    }

    private fun boxCommit(): String =
        """{"nodeEpoch":0,"epoch":1,"placementRevision":0,"metaRevision":0,"encryptedName":"Ym94","slugToken":"${slug()}","wrappedContentKey":"Ym94LXdyYXA=","sealedDropPrivateKey":"bmV3LWRyb3AtcHJpdg=="}"""

    private fun get(w: World, path: String, caller: String): ValidatableResponse =
        given().header("Authorization", bearer(caller)).`when`().get("/api/scopes/${w.scopeId}$path").then()

    private fun post(w: World, path: String, body: String, caller: String): ValidatableResponse =
        given().header("Authorization", bearer(caller)).contentType(ContentType.JSON).body(body)
            .`when`().post("/api/scopes/${w.scopeId}$path").then()

    private fun slug(): String = Base64.getEncoder().encodeToString(UUID.randomUUID().toString().toByteArray())

    private companion object {
        const val OLD_DROP_PRIVATE = "b2xkLWRyb3AtcHJpdg=="
    }
}
