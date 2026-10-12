package me.raddatz.veyq.memories.content

import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.blob.NodeAttachmentEntity
import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.testing.CoverKit
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.memories.itemfavorites.ItemFavoritesEntity
import me.raddatz.veyq.memories.media.BucketEntity
import me.raddatz.veyq.memories.media.MediaDerivativeEntity
import me.raddatz.veyq.memories.media.MediaEntity
import me.raddatz.veyq.memories.media.MediaRevisionEntity
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.blobstore.BlobStore
import me.raddatz.veyq.core.blobstore.BlobStore.Companion.chunkKeys
import me.raddatz.veyq.core.blobstore.RecordingBlobStore
import me.raddatz.veyq.core.blobstore.StoreReclaimEntity
import me.raddatz.veyq.core.blobstore.StoreReclaimSweep
import me.raddatz.veyq.core.blobstore.ageReclaimRowsPastThePresignBound
import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.hasItem
import org.hamcrest.CoreMatchers.not
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import me.raddatz.veyq.core.content.CreateNodeOutcome
import me.raddatz.veyq.core.content.NodeSubtreePurge
import me.raddatz.veyq.core.content.NodesHandler

/**
 * Slice tests for the node trash (uc.memory.delete-node, ADR 0061): a delete soft-deletes the whole subtree, the
 * space's trash lists the deleted ROOTS, restore brings the subtree back intact, and `NodeSubtreePurge`
 * hard-erases it past the retention window. The share half lives in
 * [me.raddatz.veyq.memories.sharing.ShareDeletedNodeSliceTest] — the two guest paths need their own controls.
 *
 * Four of these pin decisions that are load-bearing precisely because they look like details:
 * - the slug is freed by the **write** (`slug_token := the node's own id`), because `ux_node_parent_slug` is a
 *   plain unique index spanning trashed rows and MariaDB has no filtered index;
 * - `slugTaken` therefore must **keep seeing** trashed rows, or validation passes and the database rejects the
 *   insert afterwards — a clean 409 turned into a 500;
 * - the delete's own traversal must **reach already-trashed descendants** and re-stamp them, or the inner rows
 *   age out first and the purge erases a Memory inside a folder still sitting restorable in the trash;
 * - the cover blob is reclaimed by the **purge**, not by the delete, or a restore hands back a Memory whose
 *   cover is already gone.
 *
 * The store half is observed through the [RecordingBlobStore] seam — the in-memory store's `delete` is a no-op
 * that proves nothing — and it takes **two** components since ADR 0062: the purge records the key prefixes in
 * its own transaction, [StoreReclaimSweep] is the only thing that deletes objects, so the purge case drives both.
 * UUID-unique seeds so the JVM-lived H2 mem DB can't bleed across tests/classes.
 *
 * **Split** (cr-platform-gradle-modules step 8a): the methods that run with core alone are `NodeTrashCoreSliceTest` in `:core`.
 */
@QuarkusTest
class NodeTrashSliceTest {

    @Inject
    lateinit var handler: NodesHandler

    @Inject
    lateinit var purge: NodeSubtreePurge

    @Inject
    lateinit var sweep: StoreReclaimSweep

    @Inject
    lateinit var blobStore: BlobStore

    @Inject
    lateinit var config: VeyqConfig

    private val recorder: RecordingBlobStore get() = blobStore as RecordingBlobStore

    // --- 1. the subtree leaves the tree and shows up in the space's trash ---

    @Test
    fun deleteHidesTheWholeSubtreeAndListsTheRootInTheTrash() {
        val (userId, scopeId) = seedMemberScope()
        val folder = createNode(userId, scopeId, """{"nodeId":"${UUID.randomUUID()}","kind":"FOLDER","encryptedName":"Zg==","slugToken":"Zi1z","orderIndex":0,"wrappedContentKey":"a21lbQ=="}""")
        val memory = createNode(userId, scopeId, """{"parentId":"$folder","nodeId":"${UUID.randomUUID()}","kind":"MEMORY","encryptedName":"bQ==","slugToken":"bS1z","orderIndex":0,"wrappedContentKey":"aw=="}""")
        val chapter = createNode(userId, scopeId, """{"parentId":"$memory","nodeId":"${UUID.randomUUID()}","kind":"CHAPTER","encryptedName":"Yw==","slugToken":"Yy1z","orderIndex":0,"wrappedContentKey":"aw=="}""")

        given().header("Authorization", bearer(userId))
            .`when`().delete("/api/scopes/$scopeId/nodes/$folder").then().statusCode(204)

        // gone from the tree, whole subtree
        given().header("Authorization", bearer(userId)).`when`().get("/api/scopes/$scopeId/nodes")
            .then().statusCode(200).body("id", not(hasItem(folder))).body("id", not(hasItem(memory))).body("id", not(hasItem(chapter)))

        // the trash lists ONLY the root — the descendants went with it and come back with it
        given().header("Authorization", bearer(userId)).`when`().get("/api/scopes/$scopeId/trash/nodes")
            .then().statusCode(200)
            .body("id", hasItem(folder))
            .body("id", not(hasItem(memory)))
            .body("id", not(hasItem(chapter)))
            .body("find { it.id == '$folder' }.deletedAt", notNullValue())
            .body("find { it.id == '$folder' }.purgeAfter", notNullValue())
            .body("find { it.id == '$folder' }.parentId", equalTo(null))
    }

    /** Nothing under the subtree is destroyed by the user's action — that is what makes the restore complete. */
    @Test
    fun deleteDestroysNothingUnderTheSubtree() {
        val (userId, scopeId) = seedMemberScope()
        val memory = createNode(userId, scopeId, """{"nodeId":"${UUID.randomUUID()}","kind":"MEMORY","encryptedName":"bQ==","slugToken":"a2VlcC1z","orderIndex":0,"wrappedContentKey":"aw=="}""")
        val mediaId = completeOne(userId, scopeId, memory, "b3JpZw==")

        given().header("Authorization", bearer(userId))
            .`when`().delete("/api/scopes/$scopeId/nodes/$memory").then().statusCode(204)

        val (media, node) = QuarkusTransaction.requiringNew().call {
            MediaEntity.findById(mediaId) to NodeEntity.findById(memory)
        }
        assertNotNull(media) { "the delete must not destroy the subtree's media rows" }
        assertNotNull(node) { "the delete must not remove the node row" }
        assertNotNull(node!!.deletedAt) { "the node must carry the soft-delete marker" }
        assertNull(media!!.deletedAt) { "the media itself is untouched — only the node was trashed" }
    }

    // --- 2. the slug is freed immediately, by the write ---

    @Test
    fun deletingFreesTheNameImmediately() {
        val (userId, scopeId) = seedMemberScope()
        val first = createNode(userId, scopeId, """{"nodeId":"${UUID.randomUUID()}","kind":"MEMORY","encryptedName":"bQ==","slugToken":"cm9tLTIwMjY=","orderIndex":0,"wrappedContentKey":"aw=="}""")

        given().header("Authorization", bearer(userId))
            .`when`().delete("/api/scopes/$scopeId/nodes/$first").then().statusCode(204)

        // the SAME slug in the SAME parent is immediately reusable — without `slug_token := id` this is a 409
        // (the trashed row still holds the name) or, worse, a constraint violation at flush.
        val second = createNode(userId, scopeId, """{"nodeId":"${UUID.randomUUID()}","kind":"MEMORY","encryptedName":"bTI=","slugToken":"cm9tLTIwMjY=","orderIndex":0,"wrappedContentKey":"aw=="}""")
        assertTrue(second != first) { "the re-created Memory must be a new node" }

        // and the freed token really is the trashed node's own id
        val freed = QuarkusTransaction.requiringNew().call { NodeEntity.findById(first)!!.slugToken }
        assertEquals(first, freed) { "the delete frees the slug by writing the node's own id into slug_token" }
    }

    // --- 3. restore into a taken name is a clean rejection, not a 500 ---

    @Test
    fun restoreIntoATakenNameIsACleanRejection() {
        val (userId, scopeId) = seedMemberScope()
        val original = createNode(userId, scopeId, """{"nodeId":"${UUID.randomUUID()}","kind":"MEMORY","encryptedName":"bQ==","slugToken":"dGFrZW4=","orderIndex":0,"wrappedContentKey":"aw=="}""")

        given().header("Authorization", bearer(userId))
            .`when`().delete("/api/scopes/$scopeId/nodes/$original").then().statusCode(204)

        // somebody re-used the name while the Memory sat in the trash
        createNode(userId, scopeId, """{"nodeId":"${UUID.randomUUID()}","kind":"MEMORY","encryptedName":"bTI=","slugToken":"dGFrZW4=","orderIndex":0,"wrappedContentKey":"aw=="}""")

        // the restore surfaces the SAME disambiguation a create does — a 409 slug_taken, never a 500
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"slugToken":"dGFrZW4="}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$original/restore")
            .then().statusCode(409).body("code", equalTo("slug_taken"))

        // and the subtree is still in the trash, restorable under another name — the retry re-seals the name in
        // the SAME write as the token derived from it, so the node is never live with a mismatched pair.
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"slugToken":"dGFrZW4tMg==","encryptedName":"cmVuYW1lZA=="}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$original/restore")
            .then().statusCode(204)

        given().header("Authorization", bearer(userId)).`when`().get("/api/scopes/$scopeId/nodes")
            .then().statusCode(200)
            .body("find { it.id == '$original' }.slugToken", equalTo("dGFrZW4tMg=="))
            .body("find { it.id == '$original' }.encryptedName", equalTo("cmVuYW1lZA=="))
    }

    @Test
    fun restoreReturnsTheWholeSubtreeIntact() {
        val (userId, scopeId) = seedMemberScope()
        val memory = createNode(userId, scopeId, """{"nodeId":"${UUID.randomUUID()}","kind":"MEMORY","encryptedName":"bQ==","slugToken":"cmVzdC1z","orderIndex":0,"wrappedContentKey":"aw=="}""")
        val chapter = createNode(userId, scopeId, """{"parentId":"$memory","nodeId":"${UUID.randomUUID()}","kind":"CHAPTER","encryptedName":"Yw==","slugToken":"cmVzdC1j","orderIndex":0,"wrappedContentKey":"aw=="}""")
        val mediaId = completeOne(userId, scopeId, chapter, "b3JpZw==")

        given().header("Authorization", bearer(userId))
            .`when`().delete("/api/scopes/$scopeId/nodes/$memory").then().statusCode(204)
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"slugToken":"cmVzdC1z"}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$memory/restore").then().statusCode(204)

        given().header("Authorization", bearer(userId)).`when`().get("/api/scopes/$scopeId/nodes")
            .then().statusCode(200).body("id", hasItem(memory)).body("id", hasItem(chapter))
        given().header("Authorization", bearer(userId)).`when`().get("/api/scopes/$scopeId/trash/nodes")
            .then().statusCode(200).body("id", not(hasItem(memory)))
        // the chapter's gallery is reachable again, with its media still in it
        given().header("Authorization", bearer(userId)).`when`().get("/api/scopes/$scopeId/nodes/$chapter/media")
            .then().statusCode(200).body("media.mediaId", hasItem(mediaId))
    }

    // --- 4. slugTaken must NOT learn the predicate ---

    /**
     * The trap, pinned at the handler rather than over HTTP. `ux_node_parent_slug` spans trashed rows, and a
     * freed slug token IS the trashed node's own id — a UUID, which the contract's base64 `Pattern` rejects at
     * the boundary, so no HTTP client can reach the collision. A handler-level caller can, and that is exactly
     * the path a future "tidy up the reads" change would break: teach `slugTaken` the `deletedAt is null`
     * predicate and this stops being a clean `SlugTaken` and becomes a constraint violation at flush — a 500.
     */
    @Test
    fun slugTakenStillSeesTrashedRowsSoTheDatabaseNeverThrows() {
        val (userId, scopeId) = seedMemberScope()
        val trashed = createNode(userId, scopeId, """{"nodeId":"${UUID.randomUUID()}","kind":"MEMORY","encryptedName":"bQ==","slugToken":"dHJhcC1z","orderIndex":0,"wrappedContentKey":"aw=="}""")
        given().header("Authorization", bearer(userId))
            .`when`().delete("/api/scopes/$scopeId/nodes/$trashed").then().statusCode(204)

        // the freed token is the trashed node's own id; creating a sibling that claims it must be REJECTED,
        // cleanly, by validation — not accepted and then thrown out by the unique index.
        val outcome = handler.create(
            userId, scopeId, UUID.randomUUID().toString(), null, MemoriesNodeKinds.MEMORY, "bTI=", trashed, 0, 0, "aw==", null,
        )
        assertTrue(outcome is CreateNodeOutcome.SlugTaken) {
            "a slug already held by a TRASHED sibling must be a clean rejection; got $outcome"
        }
    }

    // --- 5. the delete's traversal must reach already-trashed descendants ---

    /**
     * Delete an inner Memory, let it age, then delete the folder around it. The folder's traversal must reach
     * the already-trashed Memory and re-stamp it, so the whole subtree ages out on ONE timestamp. If the
     * traversal skipped trashed rows, the inner Memory would keep its older marker, the purge would erase it
     * first, and restoring the folder would hand back an empty folder — the "stops early and strands the rest"
     * failure, observable exactly here and nowhere in the marker itself.
     */
    @Test
    fun deletingAroundAnAlreadyTrashedSubtreeMakesTheWholeThingAgeOutTogether() {
        val (userId, scopeId) = seedMemberScope()
        val folder = createNode(userId, scopeId, """{"nodeId":"${UUID.randomUUID()}","kind":"FOLDER","encryptedName":"Zg==","slugToken":"c3RyLWY=","orderIndex":0,"wrappedContentKey":"a21lbQ=="}""")
        val memory = createNode(userId, scopeId, """{"parentId":"$folder","nodeId":"${UUID.randomUUID()}","kind":"MEMORY","encryptedName":"bQ==","slugToken":"c3RyLW0=","orderIndex":0,"wrappedContentKey":"aw=="}""")
        val chapter = createNode(userId, scopeId, """{"parentId":"$memory","nodeId":"${UUID.randomUUID()}","kind":"CHAPTER","encryptedName":"Yw==","slugToken":"c3RyLWM=","orderIndex":0,"wrappedContentKey":"aw=="}""")

        given().header("Authorization", bearer(userId))
            .`when`().delete("/api/scopes/$scopeId/nodes/$memory").then().statusCode(204)

        // the inner delete happened long ago — past the retention window
        val stale = Instant.now().minus(config.node().trash().retention()).minusSeconds(60)
        QuarkusTransaction.requiringNew().run {
            listOf(memory, chapter).forEach { NodeEntity.findById(it)!!.deletedAt = stale }
        }

        // now the folder around it goes
        given().header("Authorization", bearer(userId))
            .`when`().delete("/api/scopes/$scopeId/nodes/$folder").then().statusCode(204)

        purge.run()

        // nothing was purged: the inner nodes were re-stamped with the folder's fresh marker
        val survivors = QuarkusTransaction.requiringNew().call {
            listOf(folder, memory, chapter).map { NodeEntity.findById(it) }
        }
        assertTrue(survivors.all { it != null }) {
            "re-deleting around an already-trashed subtree must re-stamp it, or the purge strands the inner rows"
        }

        // restoring the folder brings the folder back; the Memory its own earlier delete trashed holds that delete's
        // freed slug token, so it stays in the trash with its chapter — a root of its own now (inbox #357) — and
        // comes back on its own restore, the whole thing still there
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"slugToken":"c3RyLWY="}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$folder/restore").then().statusCode(204)
        given().header("Authorization", bearer(userId)).`when`().get("/api/scopes/$scopeId/nodes")
            .then().statusCode(200).body("id", hasItem(folder)).body("id", not(hasItem(memory))).body("id", not(hasItem(chapter)))
        given().header("Authorization", bearer(userId)).`when`().get("/api/scopes/$scopeId/trash/nodes")
            .then().statusCode(200).body("id", hasItem(memory)).body("id", not(hasItem(chapter)))
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"slugToken":"c3RyLW0="}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$memory/restore").then().statusCode(204)
        given().header("Authorization", bearer(userId)).`when`().get("/api/scopes/$scopeId/nodes")
            .then().statusCode(200).body("id", hasItem(folder)).body("id", hasItem(memory)).body("id", hasItem(chapter))
    }

    /** Deleting an already-trashed subtree changes nothing (E3) — including its marker. */
    @Test
    fun reDeletingAnAlreadyTrashedSubtreeIsANoOp() {
        val (userId, scopeId) = seedMemberScope()
        val memory = createNode(userId, scopeId, """{"nodeId":"${UUID.randomUUID()}","kind":"MEMORY","encryptedName":"bQ==","slugToken":"aWRlbXA=","orderIndex":0,"wrappedContentKey":"aw=="}""")

        given().header("Authorization", bearer(userId))
            .`when`().delete("/api/scopes/$scopeId/nodes/$memory").then().statusCode(204)
        val first = QuarkusTransaction.requiringNew().call { NodeEntity.findById(memory)!!.deletedAt }

        given().header("Authorization", bearer(userId))
            .`when`().delete("/api/scopes/$scopeId/nodes/$memory").then().statusCode(204)
        val second = QuarkusTransaction.requiringNew().call { NodeEntity.findById(memory)!!.deletedAt }

        assertEquals(first, second) { "a re-delete of an already-trashed subtree must not move its marker" }
    }

    // --- 6. the purge reclaims all three arms ---

    /**
     * The leak this whole change closes (inbox #131): today's hard delete removed the node rows and the cover
     * blobs and left `veyq_media`, `veyq_media_derivative` and `veyq_bucket` pointing at a node id that no longer
     * existed — unreachable by every read and unreclaimable by every purge. The purge now owns the whole subtree.
     * Observed through the store seam, not from the DB alone: the in-memory store's `delete` is a no-op, so a
     * DB-only assertion would prove the keys were *computed* and never that they arrived.
     */
    @Test
    fun purgeReclaimsMediaDerivativesBucketsCoverAndTheStoreObjects() {
        val (userId, scopeId) = seedMemberScope()
        val memory = createNode(userId, scopeId, """{"nodeId":"${UUID.randomUUID()}","kind":"MEMORY","encryptedName":"bQ==","slugToken":"cHVyZ2Utcw==","orderIndex":0,"wrappedContentKey":"aw=="}""")
        val survivor = createNode(userId, scopeId, """{"nodeId":"${UUID.randomUUID()}","kind":"MEMORY","encryptedName":"cw==","slugToken":"cHVyZ2Utdg==","orderIndex":1,"wrappedContentKey":"aw=="}""")

        val original = startOne(userId, scopeId, TestBlob.ofChunks(2), "b3JpZw==")
        val thumb = startOne(userId, scopeId, TestBlob.SMALL, "dGh1bWI=", TestBlob.ownerOf(original), "THUMBNAIL")
        val mediaId = given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"nodeId":"$memory","mediaId":"${TestBlob.ownerOf(original)}","originalUploadId":"$original","derivatives":[{"kind":"THUMBNAIL","uploadId":"$thumb"}]}]}""")
            .`when`().post("/api/scopes/$scopeId/media-batch")
            .then().statusCode(200).body("results[0].error", nullValue())
            .extract().path<String>("results[0].mediaId")
        val coverBlob = commitCover(userId, scopeId, memory, startOne(userId, scopeId, TestBlob.SMALL, "Y292ZXI=", memory, "COVER"))
        val subBucket = given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"bucketId":"${TestIds.uuid()}","sealedName":"YnVja2V0"}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$memory/buckets")
            .then().statusCode(200).extract().path<String>("bucketId")

        // item favorites on BOTH subtrees: the doomed one's must go, the in-window one's must not (ADR 0068).
        // Keyed on the node, so the purge is the only thing that reaches them — the scope survives.
        for (n in listOf(memory, survivor)) {
            given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
                .body("""{"ciphertext":"bWFya3M="}""")
                .`when`().put("/api/scopes/$scopeId/nodes/$n/item-favorites").then().statusCode(204)
        }

        // a control subtree, trashed but still INSIDE the window — the purge must not touch it
        val freshMediaId = completeOne(userId, scopeId, survivor, "ZnJlc2g=")
        given().header("Authorization", bearer(userId))
            .`when`().delete("/api/scopes/$scopeId/nodes/$survivor").then().statusCode(204)

        given().header("Authorization", bearer(userId))
            .`when`().delete("/api/scopes/$scopeId/nodes/$memory").then().statusCode(204)

        // the cover blob must SURVIVE the delete — reclaiming it there would make a restore return a Memory
        // whose cover is already gone (ADR 0061 Decision 6)
        assertNotNull(QuarkusTransaction.requiringNew().call { BlobEntity.findById(coverBlob) }) {
            "the cover blob is reclaimed by the purge, not by the delete"
        }

        val keys = QuarkusTransaction.requiringNew().call {
            val media = MediaEntity.findById(mediaId)!!
            val derivBlobIds = MediaDerivativeEntity.find("mediaId = ?1", mediaId).list().map { it.blobId }
            val all = (listOf(media.blobId) + derivBlobIds + listOf(coverBlob)).map { BlobEntity.findById(it)!! }
            NodeEntity.findById(memory)!!.deletedAt =
                Instant.now().minus(config.node().trash().retention()).minusSeconds(60)
            all.flatMap { b -> chunkKeys(b.storageKey, b.chunkCount) }
        }

        // Drop the JVM-shared reclaim backlog: the sweep takes a bounded batch of the OLDEST due rows, and this
        // case's rows are the newest in a table every earlier test class has been writing to.
        QuarkusTransaction.requiringNew().run { StoreReclaimEntity.deleteAll() }
        recorder.reset()
        purge.run()
        ageReclaimRowsPastThePresignBound(config) // past the not-before, so the sweep may delete
        sweep.run()

        QuarkusTransaction.requiringNew().run {
            assertNull(NodeEntity.findById(memory)) { "the node row must be gone" }
            assertNull(MediaEntity.findById(mediaId)) { "the subtree's media row must be gone" }
            assertTrue(MediaDerivativeEntity.find("mediaId = ?1", mediaId).list().isEmpty()) { "derivative rows must be gone" }
            assertTrue(BucketEntity.find("nodeId = ?1", memory).list().isEmpty()) { "bucket rows must be gone" }
            assertNull(BucketEntity.findById(subBucket)) { "the sub-bucket row must be gone" }
            assertNull(BlobEntity.findById(coverBlob)) { "the cover blob row must be gone" }
            // inbox #341: the cover is the node's COVER attachment and the gallery counters Memories' own row — both
            // name the node by a foreign key, so the node delete above would have been refused had either survived
            assertEquals(0L, NodeAttachmentEntity.count("nodeId", memory), "the cover's attachment row must be gone")
            assertNull(MediaRevisionEntity.findById(memory)) { "the purged node's gallery counters must be gone" }
            assertTrue(ItemFavoritesEntity.find("nodeId = ?1", memory).list().isEmpty()) {
                "the purged subtree's item-favorite blobs must be gone"
            }

            // control: the in-window subtree is untouched on every arm
            assertNotNull(NodeEntity.findById(survivor)) { "a trashed subtree still inside the window must survive" }
            assertNotNull(MediaEntity.findById(freshMediaId)) { "its media must survive too" }
            assertNotNull(MediaRevisionEntity.findById(survivor)) { "and its gallery counters" }
            assertTrue(ItemFavoritesEntity.find("nodeId = ?1", survivor).list().isNotEmpty()) {
                "and its item favorites must survive — a restore inside the window hands the marks back"
            }
        }
        assertTrue(recorder.deletedKeys.containsAll(keys)) {
            "the purge + sweep must hand every reclaimed chunk key (original + derivative + cover) to the store; " +
                "expected $keys, handed ${recorder.deletedKeys}"
        }
    }

    // --- helpers ---

    private fun createNode(userId: String, scopeId: String, body: String): String =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON).body(body)
            .`when`().post("/api/scopes/$scopeId/nodes")
            .then().statusCode(201).body("nodeId", notNullValue())
            .extract().path("nodeId")

    private fun startOne(
        userId: String, scopeId: String, blob: TestBlob, wrap: String,
        ownerId: String = UUID.randomUUID().toString(), role: String = "ORIGINAL",
    ): String =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[${blob.declare(wrap, ownerId = ownerId, role = role)}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch")
            .then().statusCode(200).body("results[0].uploadId", notNullValue())
            .extract().path("results[0].uploadId")

    private fun completeOne(userId: String, scopeId: String, nodeId: String, wrap: String): String {
        val uploadId = startOne(userId, scopeId, TestBlob.SMALL, wrap)
        return given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"mediaId":"${TestBlob.ownerOf(uploadId)}","originalUploadId":"$uploadId","nodeId":"$nodeId"}]}""")
            .`when`().post("/api/scopes/$scopeId/media-batch")
            .then().statusCode(200).body("results[0].error", nullValue())
            .extract().path("results[0].mediaId")
    }

    /** Commit an upload as the node's cover through core's `setNodeCover` and return the committed blob's id. */
    private fun commitCover(userId: String, scopeId: String, nodeId: String, uploadId: String): String {
        val blobId = QuarkusTransaction.requiringNew().call { UploadSessionEntity.findById(uploadId)!!.blobId!! }
        CoverKit.set(userId, scopeId, nodeId, uploadId, expectedBlobId = null).statusCode(204)
        return blobId
    }
}
