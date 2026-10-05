package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.sharing.ShareIdentityEntity
import me.raddatz.veyq.memories.media.MediaEntity
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.testing.RACE_ROUNDS
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.identityKeyJson
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.shareKeysJson
import me.raddatz.veyq.core.testing.whileUncommitted
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A guest write is refused once a delete of its link has committed — also when the write's own gate read the link
 * live before that (developer-styleguide §12). A delete drops each guest identity nothing references any more, and it
 * counts the references when it runs; a guest's upload that passed its gate before the delete and landed after the
 * count kept a WRITE_ONLY photo whose identity — and with it the only wrap of the key that opens it — was gone. A
 * guest naming itself in that window left an identity on a dead link that nothing would ever drop. Neither order of
 * the two writes produces either. Each guest write now takes the share row a delete holds, first, and re-checks it.
 *
 * Each race holds the delete's commit back ([whileUncommitted]) and runs the guest write inside that window. Each has
 * a control: the same write on a link nobody deletes lands.
 */
@QuarkusTest
class GuestWriteRaceSliceTest {

    @Inject lateinit var sharing: SharingStore

    private val sealedName = "c2VhbGVkLW5hbWU="
    private val sealedUploaderName = "c2VhbGVkLXVwbG9hZGVyLW5hbWU="

    @Test
    fun anUploadCompletedWhileItsLinkIsDeletedIsRefused() {
        repeat(RACE_ROUNDS) { round ->
            val (userId, scopeId) = seedMemberScope()
            val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
            val shareId = uploaderShare(userId, memory)
            val (identityId, token) = identity(shareId)
            val uploadId = startUpload(shareId, token)
            val error = whileUncommitted(
                change = { assertEquals(true, sharing.delete(shareId), "round $round") },
                overlapping = { complete(shareId, token, uploadId) },
            )
            assertEquals(200 to "upload_not_found", error, "round $round: an upload landed on a deleted link")
            tx {
                assertEquals(0L, MediaEntity.count("shareIdentityId", identityId), "round $round: a photo without its identity")
                assertNull(ShareIdentityEntity.findById(identityId), "round $round: the delete drops the unused identity")
            }
            // Control: the same upload on a link nobody deletes lands, stamped with a live identity.
            val kept = uploaderShare(userId, memory)
            val (keptIdentity, keptToken) = identity(kept)
            assertEquals(200 to null, complete(kept, keptToken, startUpload(kept, keptToken)), "round $round: control")
            assertNotNull(tx { ShareIdentityEntity.findById(keptIdentity) }, "round $round: control")
        }
    }

    @Test
    fun anIdentityNamedWhileItsLinkIsDeletedIsRefused() {
        repeat(RACE_ROUNDS) { round ->
            val (userId, scopeId) = seedMemberScope()
            val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
            val shareId = uploaderShare(userId, memory)
            val identityId = TestIds.uuid()
            val status = whileUncommitted(
                change = { assertEquals(true, sharing.delete(shareId), "round $round") },
                overlapping = { postIdentity(shareId, identityId).statusCode() },
            )
            assertEquals(404, status, "round $round: a guest named itself on a deleted link")
            assertNull(tx { ShareIdentityEntity.findById(identityId) }, "round $round: nothing written")
            // Control: the same guest on a link nobody deletes is named.
            assertEquals(201, postIdentity(uploaderShare(userId, memory), TestIds.uuid()).statusCode(), "round $round: control")
        }
    }

    // --- helpers ---

    private fun uploaderShare(userId: String, nodeId: String): String {
        val shareId = TestIds.shareId()
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"shareId":"$shareId",${ShareWriteProof.field(shareId)},"nodeId":"$nodeId","role":"WRITE_ONLY",${shareKeysJson("WRITE_ONLY", nodeId)},"sealedName":"$sealedName"}""")
            .`when`().post("/api/shares").then().statusCode(201)
        return shareId
    }

    private fun postIdentity(shareId: String, identityId: String) =
        given().contentType(ContentType.JSON)
            .body("""{"identityId":"$identityId","sealedDisplayName":"$sealedUploaderName"${identityKeyJson("WRITE_ONLY")}}""")
            .header(ShareWriteProof.HEADER, ShareWriteProof.token(shareId)).`when`().post("/api/shares/$shareId/identities")
            .then().extract()

    /** A named guest on [shareId]: (identityId, contributorToken). */
    private fun identity(shareId: String): Pair<String, String> {
        val r = postIdentity(shareId, TestIds.uuid())
        assertEquals(201, r.statusCode())
        return r.path<String>("identityId") to r.path<String>("contributorToken")
    }

    private fun startUpload(shareId: String, token: String): String =
        given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"$token","items":[${TestBlob.SMALL.declare("Ymxvyi13cmFw")}]}""")
            .`when`().post("/api/shares/$shareId/blob-uploads")
            .then().statusCode(200).extract().path("results[0].uploadId")

    /** A guest complete's answer as (status, the item's error). */
    private fun complete(shareId: String, token: String, uploadId: String): Pair<Int, String?> =
        given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"$token","items":[{"mediaId":"${TestBlob.ownerOf(uploadId)}","originalUploadId":"$uploadId"}]}""")
            .`when`().post("/api/shares/$shareId/media").then().extract()
            .let { r -> r.statusCode() to runCatching { r.path<String?>("results[0].error") }.getOrNull() }

    private fun <T> tx(body: () -> T): T = QuarkusTransaction.requiringNew().call(body)
}
