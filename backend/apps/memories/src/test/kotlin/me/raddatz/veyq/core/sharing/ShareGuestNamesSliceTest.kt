package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.sharing.ShareIdentityEntity
import me.raddatz.veyq.memories.media.BucketEntity
import me.raddatz.veyq.memories.media.MediaEntity
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.WRAPPED_UPLOADER_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.identityKeyJson
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.shareKeysJson
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import java.time.Instant
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Test

/**
 * A guest's name reaches the Memory's members only (uc.memory.buckets `guest-names-for-members-only`, ADR 0030
 * point 8): no public-share resolve serves a guest's sealed name, nor a READ_WRITE guest's identity — also not on
 * a READ link that was once a READ_WRITE link, which keeps its identities — and only an uploader's media keeps
 * its `identityId`, because a viewer opens that media through the identity's wrapped key (ADR 0031 point 7).
 *
 * Every negative carries a positive on the same predicate: the identity set is asserted to be EXACTLY the
 * uploader's (an empty list would satisfy "no name" vacuously), the name's absence is asserted on that present
 * identity, and each media is found by its id before its `identityId` is read. The member bucket list is the
 * control that the names were posted and still reach the people they are for.
 */
@QuarkusTest
class ShareGuestNamesSliceTest {

    private val wrap = "d3JhcC1rLW1lbS11bmRlci1zaGFyZQ=="
    private val sealedName = "c2VhbGVkLW5hbWU="
    private val contributorName = "c2VhbGVkLWNvbnRyaWJ1dG9yLW5hbWU="
    private val uploaderName = "c2VhbGVkLXVwbG9hZGVyLW5hbWU="

    private data class Memory(
        val userId: String, val scopeId: String, val memory: String,
        val contributorLink: String, val otherContributorLink: String, val viewerLink: String, val flippedLink: String,
        val contributor: String, val uploader: String, val contributorMedia: String, val uploaderMedia: String,
    )

    /** One Memory with every kind of guest and every readable link a guest can hold. */
    private fun memory(): Memory {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val contributorLink = createShare(userId, memory, "READ_WRITE")
        val contributor = createIdentity(contributorLink, "READ_WRITE", contributorName)
        createIdentity(contributorLink, "READ_WRITE", contributorName) // a second guest on the same link, no media
        val otherContributorLink = createShare(userId, memory, "READ_WRITE")
        val otherContributor = createIdentity(otherContributorLink, "READ_WRITE", contributorName)
        val uploader = createIdentity(createShare(userId, memory, "WRITE_ONLY"), "WRITE_ONLY", uploaderName)
        val viewerLink = createShare(userId, memory, "READ")
        val flippedLink = createShare(userId, memory, "READ_WRITE")
        createIdentity(flippedLink, "READ_WRITE", contributorName)
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON).body("""{"role":"READ"}""")
            .`when`().patch("/api/shares/$flippedLink").then().statusCode(204)
        val contributorMedia = seedMedia(scopeId, memory, contributor)
        seedMedia(scopeId, memory, otherContributor)
        val uploaderMedia = seedMedia(scopeId, memory, uploader)
        return Memory(
            userId, scopeId, memory, contributorLink, otherContributorLink, viewerLink, flippedLink,
            contributor, uploader, contributorMedia, uploaderMedia,
        )
    }

    /** The identity set is exactly the uploader's, with its key and without a name. */
    private fun assertServesOnlyTheUploader(shareId: String, m: Memory) {
        given().`when`().get("/api/shares/$shareId").then().statusCode(200)
            .body("identities.size()", equalTo(1))
            .body("identities[0].identityId", equalTo(m.uploader))
            .body("identities[0].wrappedIdentityKey", equalTo(WRAPPED_UPLOADER_KEY))
            .body("identities[0].containsKey('sealedDisplayName')", equalTo(false))
    }

    @Test
    fun aViewerLinkIsServedTheUploadersKeyButNoName() {
        val m = memory()
        // the name first: on this link the identity set is already exactly the uploader's
        given().`when`().get("/api/shares/${m.viewerLink}").then().statusCode(200)
            .body("identities.find { it.identityId == '${m.uploader}' }.wrappedIdentityKey", equalTo(WRAPPED_UPLOADER_KEY))
            .body("identities.find { it.identityId == '${m.uploader}' }.containsKey('sealedDisplayName')", equalTo(false))
        assertServesOnlyTheUploader(m.viewerLink, m)
    }

    @Test
    fun aContributorLinkIsServedNoneOfItsOwnGuests() {
        val m = memory()
        assertServesOnlyTheUploader(m.contributorLink, m)
        assertServesOnlyTheUploader(m.otherContributorLink, m)
    }

    @Test
    fun aViewerLinkThatWasAContributorLinkKeepsNoGuest() {
        val m = memory()
        given().`when`().get("/api/shares/${m.flippedLink}").then().statusCode(200).body("role", equalTo("READ"))
        assertServesOnlyTheUploader(m.flippedLink, m)
    }

    @Test
    fun onlyAnUploadersMediaNamesItsIdentity() {
        val m = memory()
        for (link in listOf(m.viewerLink, m.contributorLink)) {
            given().`when`().get("/api/shares/$link").then().statusCode(200)
                .body("media.find { it.mediaId == '${m.contributorMedia}' }.nodeId", equalTo(m.memory))
                .body("media.find { it.mediaId == '${m.contributorMedia}' }.identityId", nullValue())
                .body("media.find { it.mediaId == '${m.uploaderMedia}' }.identityId", equalTo(m.uploader))
        }
    }

    @Test
    fun theMembersStillReadBothNames() {
        val m = memory()
        given().header("Authorization", bearer(m.userId)).`when`().get("/api/scopes/${m.scopeId}/nodes/${m.memory}/buckets")
            .then().statusCode(200)
            .body("buckets.find { it.ownerKey == 'share:${m.contributor}' }.sealedName", equalTo(contributorName))
            .body("buckets.find { it.ownerKey == 'share:${m.uploader}' }.sealedName", equalTo(uploaderName))
            .body("buckets.find { it.ownerKey == 'share:${m.uploader}' }.wrappedIdentityKey", notNullValue())
    }

    // --- helpers ---

    private fun createShare(userId: String, nodeId: String, role: String): String =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"$nodeId","role":"$role",${shareKeysJson(role, nodeId, wrap)},"sealedName":"$sealedName"}""")
            .`when`().post("/api/shares").then().statusCode(201).extract().path("shareId")

    private fun createIdentity(shareId: String, role: String, name: String): String =
        given().contentType(ContentType.JSON)
            .body("""{"identityId":"${TestIds.uuid()}","sealedDisplayName":"$name"${identityKeyJson(role)}}""")
            .header(ShareWriteProof.HEADER, ShareWriteProof.token(shareId)).`when`().post("/api/shares/$shareId/identities").then().statusCode(201).extract().path("identityId")

    private fun seedMedia(scopeId: String, nodeId: String, identityId: String): String {
        val mediaId = UUID.randomUUID().toString()
        val blobId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            BlobEntity().apply {
                id = blobId; this.scopeId = scopeId; storageKey = UUID.randomUUID().toString()
                chunkCount = TestBlob.SMALL.chunkCount; byteSize = TestBlob.SMALL.byteSize; wrappedBlobKey = "Ymxvyi13cmFw"; createdAt = Instant.now()
            }.persist()
            // as `MediaStore.attach` does: a guest's upload lands in their primary bucket, named by their identity
            val bucket = BucketEntity().apply {
                id = UUID.randomUUID().toString(); this.scopeId = scopeId; this.nodeId = nodeId
                ownerKey = "share:$identityId"; sealedName = ShareIdentityEntity.findById(identityId)?.sealedDisplayName
                isPrimary = true; createdAt = Instant.now()
            }.also { it.persist() }
            MediaEntity().apply {
                id = mediaId; this.scopeId = scopeId; this.nodeId = nodeId; this.blobId = blobId
                shareIdentityId = identityId; createdBy = bucket.ownerKey; bucketId = bucket.id; createdAt = Instant.now()
            }.persist()
        }
        return mediaId
    }
}
