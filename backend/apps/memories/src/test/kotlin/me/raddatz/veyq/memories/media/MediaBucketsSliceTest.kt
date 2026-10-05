package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.membership.TenantRoots
import me.raddatz.veyq.core.testing.RACE_ROUNDS
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import me.raddatz.veyq.core.testing.whileUncommitted
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Slice tests for media uploader buckets (uc.memory.buckets, en.memory.buckets, ADR 0034, cr-0113). Covers: an
 * upload stamps the member's owner_key ("user:{id}") + materializes a lazy primary; the "Beiträge" list groups by
 * uploader with counts; a sub-bucket is created one level under the primary; a move re-links media; a sub-bucket
 * delete REPARENTS its media to the primary (media survive, never deleted); a member may curate another root's
 * SUB-buckets (rename/delete/create-under) + move WITHIN one root, but never a primary and never across owners
 * (cr-0123). In-memory store (metadata only).
 */
@QuarkusTest
class MediaBucketsSliceTest {

    @Inject
    lateinit var store: MediaStore

    @Inject
    lateinit var handler: MediaHandler

    @Test
    fun uploadStampsOwnerKeyAndMaterializesPrimary() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        completeOne(userId, scopeId, memory, wrap = "YQ==")

        // the media carries the member's owner_key + a concrete bucket (its primary)
        given().header("Authorization", bearer(userId))
            .`when`().get("/api/scopes/$scopeId/nodes/$memory/media")
            .then().statusCode(200)
            .body("media[0].ownerKey", equalTo("user:$userId"))
            .body("media[0].bucketId", notNullValue())

        // the Beiträge list shows exactly one primary bucket (the uploader's) with a count of 1
        given().header("Authorization", bearer(userId))
            .`when`().get("/api/scopes/$scopeId/nodes/$memory/buckets")
            .then().statusCode(200)
            .body("buckets.size()", equalTo(1))
            .body("buckets[0].isPrimary", equalTo(true))
            .body("buckets[0].ownerKey", equalTo("user:$userId"))
            .body("buckets[0].mediaCount", equalTo(1))
            .body("buckets[0].parentBucketId", nullValue())
    }

    @Test
    fun createSubBucketMoveThenDeleteReparents() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val a = completeOne(userId, scopeId, memory, wrap = "YQ==")
        val primary = firstPrimaryId(userId, scopeId, memory)

        // create a sub-bucket under the caller's primary (one level, owner inherited)
        val sub = given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"bucketId":"${TestIds.uuid()}","sealedName":"YnVja2V0"}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$memory/buckets")
            .then().statusCode(200)
            .body("isPrimary", equalTo(false))
            .body("parentBucketId", equalTo(primary))
            .body("ownerKey", equalTo("user:$userId"))
            .extract().path<String>("bucketId")

        // move the only medium into the sub-bucket → primary count 0, sub count 1
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"mediaIds":["$a"],"targetBucketId":"$sub"}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$memory/media-moves")
            .then().statusCode(204)
        given().header("Authorization", bearer(userId))
            .`when`().get("/api/scopes/$scopeId/nodes/$memory/buckets")
            .then().statusCode(200)
            .body("buckets.find { it.bucketId == '$primary' }.mediaCount", equalTo(0))
            .body("buckets.find { it.bucketId == '$sub' }.mediaCount", equalTo(1))

        // delete the sub-bucket → its media REPARENT to the primary (never deleted); the sub-bucket is gone
        given().header("Authorization", bearer(userId))
            .`when`().delete("/api/scopes/$scopeId/nodes/$memory/buckets/$sub")
            .then().statusCode(204)
        given().header("Authorization", bearer(userId))
            .`when`().get("/api/scopes/$scopeId/nodes/$memory/buckets")
            .then().statusCode(200)
            .body("buckets.size()", equalTo(1))
            .body("buckets[0].bucketId", equalTo(primary))
            .body("buckets[0].mediaCount", equalTo(1))
        // the medium itself still lives in the gallery
        given().header("Authorization", bearer(userId))
            .`when`().get("/api/scopes/$scopeId/nodes/$memory/media")
            .then().statusCode(200)
            .body("media.size()", equalTo(1))
            .body("media[0].mediaId", equalTo(a))
    }

    @Test
    fun renameSubBucketUpdatesSealedName() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        completeOne(userId, scopeId, memory, wrap = "YQ==")
        val sub = createSub(userId, scopeId, memory, "YnVja2V0")

        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"sealedName":"cmVuYW1lZA=="}""")
            .`when`().patch("/api/scopes/$scopeId/nodes/$memory/buckets/$sub")
            .then().statusCode(200)
            .body("bucketId", equalTo(sub))
            .body("sealedName", equalTo("cmVuYW1lZA=="))
    }

    @Test
    fun memberMayManageAnotherRootsSubBucket() {
        val (alice, scopeId, tenantId) = seedMemberScope()
        val bob = addMemberToScope(scopeId, tenantId)
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        completeOne(alice, scopeId, memory, wrap = "YQ==") // alice's primary
        val aliceSub = createSub(alice, scopeId, memory, "YWxpY2U=")

        // cr-0123: a fellow member may rename + delete another root's SUB-bucket (curating contributions) → 200 / 204
        given().header("Authorization", bearer(bob)).contentType(ContentType.JSON)
            .body("""{"sealedName":"Ym9i"}""")
            .`when`().patch("/api/scopes/$scopeId/nodes/$memory/buckets/$aliceSub")
            .then().statusCode(200).body("sealedName", equalTo("Ym9i"))
        given().header("Authorization", bearer(bob))
            .`when`().delete("/api/scopes/$scopeId/nodes/$memory/buckets/$aliceSub")
            .then().statusCode(204)
    }

    @Test
    fun primaryBucketNotRenameableNorDeletable() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        completeOne(userId, scopeId, memory, wrap = "YQ==")
        val primary = firstPrimaryId(userId, scopeId, memory)

        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"sealedName":"eA=="}""")
            .`when`().patch("/api/scopes/$scopeId/nodes/$memory/buckets/$primary")
            .then().statusCode(403).body("code", equalTo("illegal_target"))
        given().header("Authorization", bearer(userId))
            .`when`().delete("/api/scopes/$scopeId/nodes/$memory/buckets/$primary")
            .then().statusCode(403).body("code", equalTo("illegal_target"))
    }

    @Test
    fun memberCreatesSubUnderAnotherRootInheritingItsOwner() {
        val (alice, scopeId, tenantId) = seedMemberScope()
        val bob = addMemberToScope(scopeId, tenantId)
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        completeOne(alice, scopeId, memory, wrap = "YQ==")
        val alicePrimary = firstPrimaryId(alice, scopeId, memory)

        // cr-0123: bob nests a sub under alice's primary root (via parentBucketId) → it inherits alice's owner_key
        given().header("Authorization", bearer(bob)).contentType(ContentType.JSON)
            .body("""{"bucketId":"${TestIds.uuid()}","sealedName":"c3Vi","parentBucketId":"$alicePrimary"}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$memory/buckets")
            .then().statusCode(200)
            .body("ownerKey", equalTo("user:$alice"))
            .body("parentBucketId", equalTo(alicePrimary))
            .body("isPrimary", equalTo(false))
    }

    @Test
    fun createSubRejectsNonPrimaryParent() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        completeOne(userId, scopeId, memory, wrap = "YQ==")
        val sub = createSub(userId, scopeId, memory, "c3Vi")

        // a sub-bucket can't be a parent — sub-buckets nest one level under a primary → 403
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"bucketId":"${TestIds.uuid()}","sealedName":"eA==","parentBucketId":"$sub"}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$memory/buckets")
            .then().statusCode(403).body("code", equalTo("illegal_target"))
    }

    @Test
    fun crossOwnerMoveIsRejected() {
        val (alice, scopeId, tenantId) = seedMemberScope()
        val bob = addMemberToScope(scopeId, tenantId)
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val aliceMedia = completeOne(alice, scopeId, memory, wrap = "YQ==")
        val bobMedia = completeOne(bob, scopeId, memory, wrap = "Yg==")

        // one move may not span two owners (alice + bob) — a cross-owner move → 403 illegal_target
        given().header("Authorization", bearer(alice)).contentType(ContentType.JSON)
            .body("""{"mediaIds":["$aliceMedia","$bobMedia"],"targetBucketId":null}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$memory/media-moves")
            .then().statusCode(403).body("code", equalTo("illegal_target"))
    }

    @Test
    fun listBucketsByNonMemberIs404() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        completeOne(userId, scopeId, memory, wrap = "YQ==")
        val outsider = seedUser()
        given().header("Authorization", bearer(outsider))
            .`when`().get("/api/scopes/$scopeId/nodes/$memory/buckets")
            .then().statusCode(404).body("code", equalTo("node_not_found"))
    }

    // --- a bucket delete racing a managed write of its media (inbox #224) ---

    @Test
    fun aMetaEditOverlappingASubBucketDeleteKeepsTheReparent() {
        // The meta edit reads the medium before the delete's reparent commits and flushes after it. A full-row UPDATE
        // wrote the deleted bucket's id back onto the medium.
        repeat(RACE_ROUNDS) { round ->
            val (userId, scopeId) = seedMemberScope()
            val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
            val media = completeOne(userId, scopeId, memory, wrap = "YQ==")
            val primary = firstPrimaryId(userId, scopeId, memory)
            val sub = createSub(userId, scopeId, memory, "c3Vi")
            assertTrue(store.moveMedia(scopeId, memory, listOf(media), sub))
            val meta = "bWV0YQ==" // "meta"
            whileUncommitted(
                change = { store.deleteBucketReparent(sub) },
                overlapping = { assertTrue(store.setMeta(scopeId, media, meta, 0L) is MediaStore.SetMetaResult.Written) },
            )
            QuarkusTransaction.requiringNew().run {
                val row = MediaEntity.findById(media)!!
                assertEquals(primary, row.bucketId, "round $round: the meta edit put the medium back in the deleted bucket")
                // Control: the edit itself landed.
                assertEquals(meta, row.wrappedMeta, "round $round")
            }
        }
    }

    @Test
    fun aMoveIntoASubBucketBeingDeletedIsRefused() {
        // The move reads its target bucket before the bucket's delete commits and writes after it: the medium was
        // left pointing at a bucket that no longer exists. The move must see the delete and answer as it would for a
        // bucket that is gone (Forbidden), leaving the medium where it was.
        repeat(RACE_ROUNDS) { round ->
            val (userId, scopeId) = seedMemberScope()
            val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
            val media = completeOne(userId, scopeId, memory, wrap = "YQ==")
            val primary = firstPrimaryId(userId, scopeId, memory)
            val doomed = createSub(userId, scopeId, memory, "ZG9vbWVk")
            val outcome = whileUncommitted(
                change = { store.deleteBucketReparent(doomed) },
                overlapping = { handler.moveMedia(userId, scopeId, memory, listOf(media), doomed) },
            )
            assertEquals(MoveMediaOutcome.Forbidden, outcome, "round $round")
            QuarkusTransaction.requiringNew().run {
                assertEquals(primary, MediaEntity.findById(media)!!.bucketId, "round $round: the medium points at a deleted bucket")
                assertNull(BucketEntity.findById(doomed), "round $round")
            }
            // Control: a move into a bucket that stays lands.
            val kept = createSub(userId, scopeId, memory, "a2VwdA==")
            assertEquals(MoveMediaOutcome.Done, handler.moveMedia(userId, scopeId, memory, listOf(media), kept), "round $round")
            QuarkusTransaction.requiringNew().run { assertEquals(kept, MediaEntity.findById(media)!!.bucketId) }
        }
    }

    @Test
    fun aSubBucketDeleteOverlappingAMoveIntoItCarriesTheMovedMedium() {
        // The other order: the move commits first. The delete must wait for it and carry the moved medium to the
        // primary like every other medium of the bucket, not leave it behind for the foreign key to null out.
        repeat(RACE_ROUNDS) { round ->
            val (userId, scopeId) = seedMemberScope()
            val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
            val media = completeOne(userId, scopeId, memory, wrap = "YQ==")
            val primary = firstPrimaryId(userId, scopeId, memory)
            val doomed = createSub(userId, scopeId, memory, "ZG9vbWVk")
            whileUncommitted(
                change = { assertTrue(store.moveMedia(scopeId, memory, listOf(media), doomed), "round $round") },
                overlapping = { store.deleteBucketReparent(doomed) },
            )
            QuarkusTransaction.requiringNew().run {
                assertEquals(primary, MediaEntity.findById(media)!!.bucketId, "round $round: the delete left the moved medium behind")
                // Control: the delete itself landed.
                assertNull(BucketEntity.findById(doomed), "round $round")
            }
        }
    }

    // --- helpers (mirror MediaTrashSliceTest) ---

    private fun createSub(userId: String, scopeId: String, nodeId: String, sealedName: String): String =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"bucketId":"${TestIds.uuid()}","sealedName":"$sealedName"}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$nodeId/buckets")
            .then().statusCode(200).extract().path("bucketId")

    private fun firstPrimaryId(userId: String, scopeId: String, nodeId: String): String =
        given().header("Authorization", bearer(userId))
            .`when`().get("/api/scopes/$scopeId/nodes/$nodeId/buckets")
            .then().statusCode(200).extract().path("buckets.find { it.isPrimary == true }.bucketId")

    private fun completeOne(userId: String, scopeId: String, nodeId: String, wrap: String): String {
        val uploadId = startOne(userId, scopeId, wrap)
        return given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"mediaId":"${TestBlob.ownerOf(uploadId)}","originalUploadId":"$uploadId","nodeId":"$nodeId"}]}""")
            .`when`().post("/api/scopes/$scopeId/media-batch")
            .then().statusCode(200).body("results[0].error", nullValue())
            .extract().path("results[0].mediaId")
    }

    private fun startOne(userId: String, scopeId: String, wrap: String): String =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[${TestBlob.SMALL.declare(wrap)}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch")
            .then().statusCode(200).body("results[0].uploadId", notNullValue())
            .extract().path("results[0].uploadId")

    private fun addMemberToScope(scopeId: String, tenantId: String): String {
        val userId = seedUser()
        QuarkusTransaction.requiringNew().run {
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); this.userId = userId; this.tenantId = tenantId
                this.scopeId = scopeId; role = MembershipEntity.MEMBER; wrappedGrant = "Z3JhbnQ="; createdAt = Instant.now()
            }.persist()
            TenantRoots.join(userId, tenantId)
        }
        return userId
    }
}
