package me.raddatz.veyq.memories.sharing

import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.crypto.Tokens
import me.raddatz.veyq.core.sharing.GuestPaths
import me.raddatz.veyq.core.sharing.GuestPaths.Path
import me.raddatz.veyq.core.sharing.GuestTreeShare
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.memories.media.ShareMediaRead
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.Test

/**
 * A share switch reaches existing links at once (ADR 0088 decision 5, inbox #442), on Memories' guest operations —
 * the media read, completing an upload into media, deleting one's own — and on core's upload start and window where
 * Memories is the writer, so a start on a Memory is admitted (a folder's is refused past the gate,
 * `ShareSwitchGuestGateCoreSliceTest`). Three links on one Memory, one per role, seeded as rows ([GuestTreeShare]);
 * each switch refuses its own role's link on every path exactly as an unknown link, leaves the other roles served, and
 * serves its link again once back on ([GuestPaths]).
 */
@QuarkusTest
class ShareSwitchGuestGateMemoriesSliceTest {

    @Test
    fun eachShareSwitchRefusesEveryMemoriesGuestPathOfItsRoleLikeAnUnknownLinkAndServesItAgainOnceBackOn() {
        val (ownerId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val links = listOf(ShareEntity.READ, ShareEntity.READ_WRITE, ShareEntity.WRITE_ONLY)
            .associateWith { GuestTreeShare.seed(ownerId, scopeId, memory, it) }
        GuestPaths.assertEachShareSwitchReachesTheExistingLinksOfItsRole(links.mapValues { it.value.shareId }) { role ->
            paths(memory, links.getValue(role).token)
        }
    }

    /** Memories' guest operations, and core's upload start and window into a Memory, for a guest holding [token]. */
    private fun paths(memory: String, token: String): List<Path> = listOf(
        Path("listShareMedia") { id -> ShareMediaRead.of(id) },
        Path("startShareBlobUploads into a Memory") { id ->
            given().contentType(ContentType.JSON)
                .body("""{"contributorToken":"$token","items":[${TestBlob.SMALL.declare("d3JhcA==", ownerId = TestIds.uuid())}]}""")
                .`when`().post("/api/shares/$id/blob-uploads")
        },
        Path("presignShareBlobUploadWindows into a Memory") { id ->
            given().contentType(ContentType.JSON)
                .body("""{"contributorToken":"$token","nodeId":"$memory","items":[{"uploadId":"${TestIds.uuid()}","fromChunk":0}]}""")
                .`when`().post("/api/shares/$id/blob-upload-windows")
        },
        Path("completeShareMedia") { id ->
            given().contentType(ContentType.JSON)
                .body("""{"contributorToken":"$token","items":[{"mediaId":"${TestIds.uuid()}","originalUploadId":"${TestIds.uuid()}"}]}""")
                .`when`().post("/api/shares/$id/media")
        },
        // The delete answers a link it does not admit and a medium it cannot find with ONE 404 (`share_media_not_found`,
        // non-enumerable), so an unknown medium tells "served" from "refused" apart on no link. A token that names no
        // guest of the link does: an admitted link refuses it with its own 403, past the gate.
        Path("deleteShareMedia with a token of no guest of the link") { id ->
            given().header("X-Contributor-Token", Tokens.random()).`when`().delete("/api/shares/$id/media/${TestIds.uuid()}")
        },
    )
}
