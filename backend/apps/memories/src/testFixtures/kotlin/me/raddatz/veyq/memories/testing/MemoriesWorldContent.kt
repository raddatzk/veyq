package me.raddatz.veyq.memories.testing

import me.raddatz.veyq.core.testing.TestFixtures.FIXTURE_EDITOR
import io.quarkus.arc.Unremovable
import jakarta.inject.Singleton
import java.time.Instant
import java.util.UUID
import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.membership.ScopeAccessWorld
import me.raddatz.veyq.core.membership.ScopeAccessWorld.Companion.B64
import me.raddatz.veyq.core.membership.WorldContent
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.sharing.ShareWriteProof
import me.raddatz.veyq.core.testing.TestFixtures.shareKeysJson
import io.quarkus.narayana.jta.QuarkusTransaction
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.Matchers.notNullValue

/**
 * Memories' content in every [ScopeAccessWorld] space (inbox #335): a `MEMORY` node, a medium on it (its original's
 * blob), a sub-bucket and a share of the node. A CDI bean of `:apps:memories`' test fixtures, so the world finds it
 * wherever Memories is part of the application under test — `:apps:memories`' tests and the root's whole-app
 * `OperatorBlindProbeTest` (`testImplementation(testFixtures(project(":apps:memories")))`) — and never in `:core`'s.
 * Read back through the typed accessors below (`space.nodeId`, …).
 *
 * The node is persisted as a row (the world's owner writes it before any gate state flips); the rest goes through
 * the API as the space's owner.
 */
@Singleton
@Unremovable
class MemoriesWorldContent : WorldContent {

    override fun seed(space: ScopeAccessWorld.Space, auth: String): Map<String, String> {
        val scopeId = space.scopeId
        val nodeId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            NodeEntity().apply {
                id = nodeId; this.scopeId = scopeId; parentId = scopeId; kind = "MEMORY"
                encryptedName = B64; slugToken = "c2x1Zw$nodeId".take(120); orderIndex = 0
                wrappedContentKey = B64; createdAt = Instant.now(); nameWrittenBy(FIXTURE_EDITOR)
            }.persist()
        }
        val mediaId = TestIds.uuid()
        val original = ScopeAccessWorld.startUpload(auth, scopeId, mediaId)
        val done = given().header("Authorization", auth).contentType(ContentType.JSON)
            .body("""{"items":[{"mediaId":"$mediaId","originalUploadId":"$original","nodeId":"$nodeId"}]}""")
            .`when`().post("/api/scopes/$scopeId/media-batch")
            .then().statusCode(200).body("results[0].mediaId", notNullValue()).extract()
        val bucketId = given().header("Authorization", auth).contentType(ContentType.JSON)
            .body("""{"bucketId":"${TestIds.uuid()}","sealedName":"$B64"}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$nodeId/buckets")
            .then().statusCode(200).extract().path<String>("bucketId")
        val shareId = given().header("Authorization", auth).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"$nodeId","role":"READ",${shareKeysJson("READ", nodeId, B64)},"sealedName":"$B64",${ShareWriteProof.recovery()}}""")
            .`when`().post("/api/shares")
            .then().statusCode(201).extract().path<String>("shareId")
        return mapOf(
            NODE_ID to nodeId,
            MEDIA_ID to done.path("results[0].mediaId"),
            BLOB_ID to done.path("results[0].blobId"),
            BUCKET_ID to bucketId,
            SHARE_ID to shareId,
        )
    }

    companion object {
        const val NODE_ID = "memories.nodeId"
        const val MEDIA_ID = "memories.mediaId"
        const val BLOB_ID = "memories.blobId"
        const val BUCKET_ID = "memories.bucketId"
        const val SHARE_ID = "memories.shareId"

        /** Every content name this contributes — a placeholder space for a probe's route check needs them all. */
        val NAMES = listOf(NODE_ID, MEDIA_ID, BLOB_ID, BUCKET_ID, SHARE_ID)
    }
}

/** The space's `MEMORY` node. */
val ScopeAccessWorld.Space.nodeId: String get() = this[MemoriesWorldContent.NODE_ID]

/** The medium on [nodeId]. */
val ScopeAccessWorld.Space.mediaId: String get() = this[MemoriesWorldContent.MEDIA_ID]

/** The blob of [mediaId]'s original. */
val ScopeAccessWorld.Space.blobId: String get() = this[MemoriesWorldContent.BLOB_ID]

/** A sub-bucket of [nodeId]. */
val ScopeAccessWorld.Space.bucketId: String get() = this[MemoriesWorldContent.BUCKET_ID]

/** A share of [nodeId], of the [MemoriesWorldContent]'s role. */
val ScopeAccessWorld.Space.shareId: String get() = this[MemoriesWorldContent.SHARE_ID]
