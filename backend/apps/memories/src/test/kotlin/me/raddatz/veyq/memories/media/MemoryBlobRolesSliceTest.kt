package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.blob.BlobUploadStore
import me.raddatz.veyq.core.blob.DerivativeDeclare
import me.raddatz.veyq.core.blob.UploadOpener
import me.raddatz.veyq.core.membership.ScopeAccessWorld
import me.raddatz.veyq.core.membership.ScopeAccessWorld.Companion.B64
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.specification.RequestSpecification
import jakarta.inject.Inject
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import me.raddatz.veyq.memories.testing.nodeId
import me.raddatz.veyq.memories.testing.mediaId

/**
 * The blob role is an open token on the wire since cr-files-application P3-S2. Core's member start takes only a role
 * some application declares ([MemoriesMemberBlobRoles], inbox #366) — here, with Memories alone, Memories' four — and
 * Memories' completion attaches a medium's blobs only in the roles it names for a medium ([MemoryBlobRoles]):
 * `upload_mismatch`, the item refused whole and nothing attached. A session in another application's role (Depot's
 * `FILE_CONTENT`, which the composed application starts) is opened through the store here, since this application's
 * start refuses it. (A guest's start, which refuses a role up front, is
 * `ShareContributorSliceTest.guestUploadInARoleMemoriesTakesFromNoGuestIs400`.)
 */
@QuarkusTest
class MemoryBlobRolesSliceTest {

    @Inject
    lateinit var completion: MediaUploadCompletion

    @Inject
    lateinit var sessions: BlobUploadStore

    private lateinit var w: ScopeAccessWorld

    @BeforeEach
    fun seed() {
        w = ScopeAccessWorld().build()
    }

    private fun asOwner(): RequestSpecification =
        given().header("Authorization", bearer(w.live.ownerId)).contentType(ContentType.JSON)

    private fun startRequest(ownerId: String, role: String) =
        asOwner().body("""{"items":[${TestBlob.SMALL.declare(B64, ownerId = ownerId, role = role)}]}""")
            .`when`().post("/api/scopes/${w.live.scopeId}/blob-uploads-batch")

    private fun start(ownerId: String, role: String): String =
        startRequest(ownerId, role).then().statusCode(200).body("results[0].error", nullValue())
            .extract().path("results[0].uploadId")

    /** A member's session bound to [ownerId] in [role], opened past the start's role check — as the composed
     *  application's start opens one in a role another application declares. */
    private fun openPastTheStart(ownerId: String, role: String): String =
        sessions.createSession(
            w.live.scopeId, UploadOpener.Member(w.live.ownerId), TestIds.uuid().replace("-", ""),
            TestBlob.SMALL.upload(B64, ownerId = ownerId, role = role),
        )

    private fun completeOverHttp(mediaId: String, original: String, derivatives: String = "") =
        asOwner().body(
            """{"items":[{"mediaId":"$mediaId","originalUploadId":"$original","nodeId":"${w.live.nodeId}"""" +
                (if (derivatives.isEmpty()) "" else ""","derivatives":[$derivatives]""") + "}]}",
        ).`when`().post("/api/scopes/${w.live.scopeId}/media-batch")

    /** Core's completion seam called as any caller could: the media contract's closed derivative kind is not in the way. */
    private fun completeThroughTheSeam(mediaId: String, original: String, derivatives: List<DerivativeDeclare>): String? =
        QuarkusTransaction.requiringNew().call {
            completion.complete(
                w.live.scopeId, NodeEntity.findById(w.live.nodeId)!!,
                MediaUploadCompletion.item(mediaId, w.live.nodeId, original, derivatives, null, null),
                UploadOpener.Member(w.live.ownerId),
                admitted = { true },
            ).error
        }

    private fun attached(mediaId: String) = QuarkusTransaction.requiringNew().call { MediaEntity.findById(mediaId) }

    /** Inbox #366: a member's start in a role no application here declares is refused whole, and opens nothing. */
    @Test
    fun aMemberStartTakesMemoriesRolesAndNoOther() {
        val mediaId = TestIds.uuid()
        for (role in listOf("FILE_CONTENT", "NOT_A_ROLE")) {
            startRequest(mediaId, role).then().statusCode(400).body("code", equalTo("invalid_request"))
        }
        // refused whole, even beside an admissible item
        asOwner().body(
            """{"items":[${TestBlob.SMALL.declare(B64, ownerId = mediaId)},${TestBlob.SMALL.declare(B64, ownerId = mediaId, role = "NOT_A_ROLE")}]}""",
        ).`when`().post("/api/scopes/${w.live.scopeId}/blob-uploads-batch").then().statusCode(400)
        // control: every role Memories declares opens a session through the same request
        for (role in MemoryBlobRoles.MEMBER) start(TestIds.uuid(), role)
    }

    @Test
    fun anUploadStartedInARoleMemoriesDoesNotNameAttachesNowhere() {
        val mediaId = TestIds.uuid()
        // a session in another application's role: Memories' completion never attaches it as a medium's blob
        val foreign = openPastTheStart(mediaId, "FILE_CONTENT")
        val original = start(mediaId, MemoryBlobRoles.ORIGINAL)

        // as the medium's original
        completeOverHttp(mediaId, foreign).then().statusCode(200).body("results[0].error", equalTo("upload_mismatch"))
        // as a derivative, declared in a kind Memories names
        completeOverHttp(mediaId, original, """{"kind":"THUMBNAIL","uploadId":"$foreign"}""")
            .then().statusCode(200).body("results[0].error", equalTo("upload_mismatch"))
        // a derivative kind outside the media contract's closed `MediaDerivativeKind` never reaches the handler
        completeOverHttp(mediaId, original, """{"kind":"FILE_CONTENT","uploadId":"$foreign"}""").then().statusCode(400)
        assertNull(attached(mediaId), "nothing was attached")

        // control: the same original with a derivative started in its own role
        val thumb = start(mediaId, MemoryBlobRoles.THUMBNAIL)
        completeOverHttp(mediaId, original, """{"kind":"THUMBNAIL","uploadId":"$thumb"}""")
            .then().statusCode(200).body("results[0].error", nullValue()).body("results[0].mediaId", equalTo(mediaId))
    }

    @Test
    fun theCompletionAttachesADerivativeOnlyInARoleMemoriesNamesOne() {
        val mediaId = TestIds.uuid()
        val original = start(mediaId, MemoryBlobRoles.ORIGINAL)
        // each session is bound to this media in exactly the role it is declared in, so only the role list refuses it
        for (role in listOf("FILE_CONTENT", MemoryBlobRoles.ORIGINAL, "COVER")) {
            val derivative = if (role in MemoryBlobRoles.MEMBER) start(mediaId, role) else openPastTheStart(mediaId, role)
            assertEquals(
                "upload_mismatch", completeThroughTheSeam(mediaId, original, listOf(DerivativeDeclare(role, derivative))),
                "a derivative in the role $role",
            )
            assertNull(attached(mediaId), "nothing was attached for $role")
        }

        // control: a PREVIEW in its own role attaches through the same call
        val preview = start(mediaId, MemoryBlobRoles.PREVIEW)
        assertNull(completeThroughTheSeam(mediaId, original, listOf(DerivativeDeclare(MemoryBlobRoles.PREVIEW, preview))))
        assertEquals(w.live.nodeId, attached(mediaId)?.nodeId, "the control attached the medium")
    }
}
