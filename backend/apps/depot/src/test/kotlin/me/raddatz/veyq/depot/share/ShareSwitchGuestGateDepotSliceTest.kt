package me.raddatz.veyq.depot.share

import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.sharing.GuestPaths
import me.raddatz.veyq.core.sharing.GuestPaths.Path
import me.raddatz.veyq.core.sharing.GuestTreeShare
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.ShareIdentityEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.WRAPPED_UPLOADER_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.depot.DepotNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import java.util.UUID
import org.junit.jupiter.api.Test

/**
 * A share switch reaches existing links at once (ADR 0088 decision 5, inbox #442), on Depot's guest operations — the
 * file listing, completing an upload into a file, and a drop box guest's own folder. A READ and a READ_WRITE link on a
 * folder holding a file, a WRITE_ONLY link on a drop box below it, seeded as rows ([GuestTreeShare]); each switch
 * refuses its own role's link on every path exactly as an unknown link, leaves the other roles served, and serves its
 * link again once back on ([GuestPaths]). An existing drop box node stays either way: creating one is a feature switch
 * (`DropBoxCapabilitySliceTest`).
 */
@QuarkusTest
class ShareSwitchGuestGateDepotSliceTest {

    @Test
    fun eachShareSwitchRefusesEveryDepotGuestPathOfItsRoleLikeAnUnknownLinkAndServesItAgainOnceBackOn() {
        val (ownerId, scopeId) = seedMemberScope()
        val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val file = seedNode(scopeId, DepotNodeKinds.FILE, parentId = folder, wrappedContentKey = CONTENT_KEY)
        val dropBox = seedNode(scopeId, DepotNodeKinds.DROPBOX, parentId = folder, wrappedContentKey = CONTENT_KEY)
        val links = mapOf(
            ShareEntity.READ to GuestTreeShare.seed(ownerId, scopeId, folder, ShareEntity.READ),
            ShareEntity.READ_WRITE to GuestTreeShare.seed(ownerId, scopeId, folder, ShareEntity.READ_WRITE),
            ShareEntity.WRITE_ONLY to GuestTreeShare.seed(ownerId, scopeId, dropBox, ShareEntity.WRITE_ONLY).also { link ->
                // A drop box guest hands its identity key over wrapped to the drop key (ADR 0031 point 7).
                QuarkusTransaction.requiringNew().run {
                    ShareIdentityEntity.find("shareId", link.shareId).firstResult()!!.wrappedIdentityKey = WRAPPED_UPLOADER_KEY
                }
            },
        )
        GuestPaths.assertEachShareSwitchReachesTheExistingLinksOfItsRole(links.mapValues { it.value.shareId }) { role ->
            paths(file, links.getValue(role).token)
        }
    }

    /** Depot's guest operations for a guest holding [token], each well-formed for any share id. */
    private fun paths(file: String, token: String): List<Path> = listOf(
        Path("listShareFiles") { id -> given().`when`().get("/api/shares/$id/files") },
        Path("completeShareFile") { id ->
            given().contentType(ContentType.JSON)
                .body("""{"contributorToken":"$token","uploadId":"${TestIds.uuid()}"}""")
                .`when`().post("/api/shares/$id/files/$file/content")
        },
        Path("ensureShareDropFolder") { id ->
            given().contentType(ContentType.JSON)
                .body(
                    """{"contributorToken":"$token","nodeId":"${UUID.randomUUID()}","encryptedName":"bmFtZQ==",""" +
                        """"slugToken":"${GuestTreeShare.slug()}","epoch":0,"wrappedContentKey":"d3JhcA=="}""",
                )
                .`when`().post("/api/shares/$id/files/drop-folder")
        },
    )
}
