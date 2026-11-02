package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.spaces.scope.ScopesStore
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import jakarta.inject.Inject
import java.util.Base64
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.hasItem
import org.hamcrest.CoreMatchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * ADR 0114's probes, written against the tree before it is built (its phase 1): each case states what closing a link
 * must do and fails on today's code, with a positive control on the same predicate that passes today. Core alone, every
 * node a `FOLDER`.
 *
 * - [aRevokedLinksRootTakesNoWriteUntilItIsReKeyed] — decision 4: a revoke marks the root's subtree due, so a write
 *   into it waits (`node_rekey_pending`). Today the revoke writes `enabled = false` and nothing else, and the write
 *   lands under the key the link's holder has.
 * - [aRevokedLinkPutsItsScopeIntoReKeying] — decisions 4 and 9: the due mark makes the scope *re-keying*, which is what
 *   every member's worker resumes from.
 * - [aRotationReWrapsNoSwitchedOffLink] — decision 5: a re-key lists only the open links for a new wrap. Today it lists
 *   every link not deleted (`RekeyStore.rekeyedShares`), so a rotation hands a revoked link's holder the new key.
 * - [aRotationReSealsTheIdentitiesOfADeletedLink] — decision 5: a contributor's name is the node's field, re-sealed
 *   whatever its link's state. Today the parts serve only the identities of links not deleted, so a contributor of a
 *   re-issued or deleted link keeps a name under the old key.
 * - [aKeyStagedUnderALinkClosedSinceDoesNotLand] — decision 8: a wrap staged before the link closed is refused at the
 *   commit (`rekey_parts_stale`). Today it is swapped into the switched-off row.
 *
 * Disabled until ADR 0114's backend phase enables it: the branch stays green between the phases, and the red run of
 * each case on the untouched tree is quoted in that ADR's phase-1 report.
 */
@Disabled("ADR 0114 phase 1 probes: red on the untouched tree by design; its backend phase enables them")
@QuarkusTest
class LinkCloseRekeyProbeCoreSliceTest {

    @Inject
    lateinit var scopes: ScopesStore

    @Test
    fun aRevokedLinksRootTakesNoWriteUntilItIsReKeyed() {
        val w = world()
        val link = createShare(w, role = "READ")
        meta(w, revision = 0).statusCode(204) // control: an open link's root takes the write
        revoke(w, link).statusCode(204)
        meta(w, revision = 1).statusCode(409).body("code", equalTo("node_rekey_pending"))
    }

    @Test
    fun aRevokedLinkPutsItsScopeIntoReKeying() {
        val w = world()
        val link = createShare(w, role = "READ")
        assertEquals(false, rekeying(w), "control: an open link re-keys nothing")
        revoke(w, link).statusCode(204)
        assertEquals(true, rekeying(w))
    }

    @Test
    fun aRotationReWrapsNoSwitchedOffLink() {
        val w = world()
        val open = createShare(w, role = "READ")
        val revoked = createShare(w, role = "READ")
        revoke(w, revoked).statusCode(204)
        rotate(w)
        parts(w).statusCode(200)
            .body("shares.shareId", hasItem(open)) // control: the open link follows the new key
            .body("shares.shareId", not(hasItem(revoked)))
    }

    @Test
    fun aRotationReSealsTheIdentitiesOfADeletedLink() {
        val w = world()
        val open = createShare(w, role = "READ_WRITE")
        val deleted = createShare(w, role = "READ_WRITE")
        val openGuest = contributor(open)
        val deletedGuest = contributor(deleted)
        // The tombstone's own columns, set directly: a contributor that still names a medium keeps its identity
        // (`SharingStore.dropDeadIdentities`), which core alone has no medium to hold.
        QuarkusTransaction.requiringNew().run {
            ShareEntity.findById(deleted)!!.apply {
                enabled = false; this.deleted = true; wrap = null; sealedShareKey = null; sealedShareKeyEpoch = null
            }
        }
        rotate(w)
        parts(w).statusCode(200)
            .body("identities.identityId", hasItem(openGuest)) // control: an open link's contributor is re-sealed
            .body("identities.identityId", hasItem(deletedGuest))
    }

    @Test
    fun aKeyStagedUnderALinkClosedSinceDoesNotLand() {
        val control = world()
        val controlLink = createShare(control, role = "READ")
        rotate(control)
        val controlRekey = UUID.randomUUID()
        stage(control, controlRekey, controlLink).statusCode(204)
        commitRoot(control, controlRekey).statusCode(204) // control: nothing closed between stage and commit

        val w = world()
        val link = createShare(w, role = "READ")
        rotate(w)
        val rekeyId = UUID.randomUUID()
        stage(w, rekeyId, link).statusCode(204)
        revoke(w, link).statusCode(204)
        commitRoot(w, rekeyId).statusCode(409).body("code", equalTo("rekey_parts_stale"))
    }

    // --- helpers ---

    private data class World(val owner: String, val scopeId: String, val tenantId: String, val root: String)

    /** A member's personal scope at epoch 0 with one root folder, its key minted at epoch 0. */
    private fun world(): World {
        val (owner, scopeId, tenantId) = seedMemberScope()
        val root = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        return World(owner, scopeId, tenantId, root)
    }

    /** A link on the root, created through the API at epoch 0; returns its id. */
    private fun createShare(w: World, role: String): String {
        val id = TestIds.shareId()
        given().header("Authorization", bearer(w.owner)).contentType(ContentType.JSON)
            .body(
                """{"shareId":"$id",${ShareWriteProof.field(id)},"nodeId":"${w.root}","role":"$role","wrap":"d3JhcA==",""" +
                    """"sealedName":"c2VhbGVk",${ShareWriteProof.recovery(epoch = 0, nodeEpoch = 0)}}""",
            )
            .`when`().post("/api/shares").then().statusCode(201)
        return id
    }

    /** A READ_WRITE guest's identity on [shareId], its name sealed under the root's key; returns its id. */
    private fun contributor(shareId: String): String {
        val id = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            ShareIdentityEntity().apply {
                this.id = id; this.shareId = shareId; sealedDisplayName = "bmFtZQ=="; tokenHash = "h-$id".take(64)
            }.persist()
        }
        return id
    }

    private fun revoke(w: World, shareId: String): ValidatableResponse =
        given().header("Authorization", bearer(w.owner)).`when`().post("/api/shares/$shareId/revoke").then()

    /** The scope moves to epoch 1 and the owner's grant follows; the root still carries its key from epoch 0. */
    private fun rotate(w: World) = QuarkusTransaction.requiringNew().run {
        ScopeEntity.findById(w.scopeId)!!.currentEpoch = 1
        MembershipEntity.update("grantedEpoch = 1 where scopeId = ?1 and userId = ?2", w.scopeId, w.owner)
    }

    private fun meta(w: World, revision: Long): ValidatableResponse =
        given().header("Authorization", bearer(w.owner)).contentType(ContentType.JSON)
            .body("""{"wrappedMeta":"bWV0YQ==","metaRevision":$revision,"nodeEpoch":0}""")
            .`when`().patch("/api/scopes/${w.scopeId}/nodes/${w.root}").then()

    private fun rekeying(w: World): Boolean? =
        QuarkusTransaction.requiringNew().call { scopes.listMine(w.owner, w.tenantId).single { it.id == w.scopeId }.rekeying }

    private fun parts(w: World): ValidatableResponse =
        given().header("Authorization", bearer(w.owner)).`when`().get("/api/scopes/${w.scopeId}/rekey/nodes/${w.root}").then()

    private fun stage(w: World, rekeyId: UUID, shareId: String): ValidatableResponse =
        given().header("Authorization", bearer(w.owner)).contentType(ContentType.JSON)
            .body("""{"fromEpoch":0,"shares":[{"shareId":"$shareId","wrap":"bmV3LXNoYXJlLXdyYXA="}]}""")
            .`when`().post("/api/scopes/${w.scopeId}/rekey/nodes/${w.root}/$rekeyId/parts").then()

    private fun commitRoot(w: World, rekeyId: UUID): ValidatableResponse =
        given().header("Authorization", bearer(w.owner)).contentType(ContentType.JSON)
            .body(
                """{"nodeEpoch":0,"scopeEpoch":1,"placementRevision":0,"metaRevision":0,"encryptedName":"bmV3LXJvb3Q=",""" +
                    """"slugToken":"${slug()}","wrappedContentKey":"bmV3LXJvb3Qtd3JhcA=="}""",
            )
            .`when`().post("/api/scopes/${w.scopeId}/rekey/nodes/${w.root}/$rekeyId").then()

    private fun slug(): String = Base64.getEncoder().encodeToString(UUID.randomUUID().toString().toByteArray())
}
