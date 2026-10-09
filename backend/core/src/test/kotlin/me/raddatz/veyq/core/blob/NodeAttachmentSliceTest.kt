package me.raddatz.veyq.core.blob

import me.raddatz.veyq.core.blobstore.StoreReclaimEntity
import me.raddatz.veyq.core.content.NodeSubtreePurge
import me.raddatz.veyq.core.content.NodesStore
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.ShareIdentityEntity
import me.raddatz.veyq.core.sharing.ShareWriteProof
import me.raddatz.veyq.core.sharing.SharingStore
import me.raddatz.veyq.core.spaces.scope.ScopePurge
import me.raddatz.veyq.core.testing.FakePresignProfile
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedAttachment
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.shareKeysJson
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Core's node attachments (cr-files-application P3-S3) with core alone: the table, its constraints, and the pieces
 * that keep it honest before any write path exists — the two erasers, the share contributor and the guest-identity
 * usage. Rows are seeded through `TestFixtures.seedAttachment`.
 *
 * What each test stands on:
 * - **Freeing, not just deleting.** A purged attachment's blob row must go through `BlobUploadStore.deleteBlobRow`,
 *   which writes the reclaim record of its store objects in the same transaction; a bare row delete leaves the
 *   objects with nothing pointing at them, and there is no sweep. So the purge tests read the reclaim record by the
 *   blob's storage key, not only the missing rows.
 * - **No cascade.** Every foreign key of `veyq_node_attachment` refuses; a cascade would delete the row behind
 *   `deleteBlobRow`'s back. [theDatabaseRefusesToDropANodeOrBlobAnAttachmentNames] asks the database directly.
 *
 * [FakePresignProfile]: a share's blob GET presigns, which the in-memory store cannot.
 */
@QuarkusTest
@TestProfile(FakePresignProfile::class)
class NodeAttachmentSliceTest {

    @Inject lateinit var subtreePurge: NodeSubtreePurge
    @Inject lateinit var scopePurge: ScopePurge
    @Inject lateinit var nodes: NodesStore
    @Inject lateinit var sharing: SharingStore

    /** An application's slot token; core names none, so the test spells one. */
    private val slot = "FILE_CONTENT"

    @Test
    fun aTrashedSubtreeIsPurgedWithItsAttachmentsAndTheirBlobsFreed() {
        val (_, scopeId) = seedMemberScope()
        val parent = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val child = seedNode(scopeId, NodeEntity.FOLDER, parentId = parent, wrappedContentKey = CONTENT_KEY)
        val neighbour = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val onParent = seedAttachment(scopeId, parent, slot)
        val onChild = seedAttachment(scopeId, child, slot)
        val kept = seedAttachment(scopeId, neighbour, slot)

        // The real trash path, then the purge once the marker is past the cutoff.
        assertEquals(true, QuarkusTransaction.requiringNew().call { nodes.deleteSubtree(scopeId, parent) })
        QuarkusTransaction.requiringNew().run { subtreePurge.eraseExpiredIn(scopeId, Instant.now().plusSeconds(1)) }

        QuarkusTransaction.requiringNew().run {
            for (gone in listOf(onParent, onChild)) {
                assertNull(NodeAttachmentEntity.findById(gone.attachmentId), "the attachment row went")
                assertNull(BlobEntity.findById(gone.blobId), "its blob row went with it")
                assertEquals(1L, StoreReclaimEntity.count("storageKey", gone.storageKey),
                    "and its store objects are on the reclaim list, recorded by deleteBlobRow in the same transaction")
            }
            assertNull(NodeEntity.findById(parent), "the node rows went last")
            assertNull(NodeEntity.findById(child))
            assertNotNull(NodeAttachmentEntity.findById(kept.attachmentId), "the neighbour's attachment is untouched")
            assertNotNull(BlobEntity.findById(kept.blobId))
            assertEquals(0L, StoreReclaimEntity.count("storageKey", kept.storageKey))
        }
    }

    @Test
    fun aDeadLinksGuestIsDroppedWithTheLastAttachmentTheyUploaded() {
        val (userId, scopeId) = seedMemberScope()
        val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val (shareId, guestId) = seedDeletedLinkWithGuest(userId, scopeId, folder)
        seedAttachment(scopeId, folder, slot, shareIdentityId = guestId)
        QuarkusTransaction.requiringNew().run { NodeEntity.findById(folder)!!.deletedAt = Instant.now().minusSeconds(60) }

        // The link was deleted earlier, so only the purge's final drop sees this guest — with the set the attachment
        // eraser returned, read before its rows went.
        QuarkusTransaction.requiringNew().run { subtreePurge.eraseExpiredIn(scopeId, Instant.now()) }

        QuarkusTransaction.requiringNew().run {
            assertEquals(0L, NodeAttachmentEntity.count("shareIdentityId", guestId))
            assertNull(ShareIdentityEntity.findById(guestId), "nothing of the guest's remains and the link is gone: dropped")
            assertNotNull(ShareEntity.findById(shareId), "the link stays a tombstone")
        }
    }

    @Test
    fun aScopePurgeErasesItsAttachmentsBeforeTheBlobNodeAndIdentityRows() {
        val (userId, scopeId) = seedMemberScope()
        val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val (_, guestId) = seedDeletedLinkWithGuest(userId, scopeId, folder)
        val byGuest = seedAttachment(scopeId, folder, slot, shareIdentityId = guestId)
        val byMember = seedAttachment(scopeId, folder, "SECOND_SLOT")

        scopePurge.purge(scopeId) // throws on an FK refusal if the attachment step ran after any of those deletes

        QuarkusTransaction.requiringNew().run {
            assertNull(ScopeEntity.findById(scopeId), "the scope is gone")
            assertEquals(0L, NodeAttachmentEntity.count("scopeId", scopeId))
            assertEquals(0L, BlobEntity.count("scopeId", scopeId))
            for (a in listOf(byGuest, byMember)) {
                assertEquals(1L, StoreReclaimEntity.count("storageKey", a.storageKey), "the scope purge recorded the blob")
            }
            assertNull(ShareIdentityEntity.findById(guestId))
        }
    }

    @Test
    fun aGuestOfAShareAboveTheNodeReadsItsAttachmentAndNothingOutsideTheLiveSubtree() {
        val (userId, scopeId) = seedMemberScope(ScopeEntity.PERSONAL)
        val outer = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val root = seedNode(scopeId, NodeEntity.FOLDER, parentId = outer, wrappedContentKey = CONTENT_KEY)
        val child = seedNode(scopeId, NodeEntity.FOLDER, parentId = root, wrappedContentKey = CONTENT_KEY)
        val trashed = seedNode(scopeId, NodeEntity.FOLDER, parentId = root, wrappedContentKey = CONTENT_KEY)
        val sibling = seedNode(scopeId, NodeEntity.FOLDER, parentId = outer, wrappedContentKey = CONTENT_KEY)
        val inside = seedAttachment(scopeId, child, slot)
        val onRoot = seedAttachment(scopeId, root, slot)
        val onTrashed = seedAttachment(scopeId, trashed, slot)
        val beside = seedAttachment(scopeId, sibling, slot)
        val above = seedAttachment(scopeId, outer, slot)
        QuarkusTransaction.requiringNew().run { NodeEntity.findById(trashed)!!.deletedAt = Instant.now() }
        val shareId: String = postShare(userId, root, "READ").statusCode(201).extract().path("shareId")

        for (served in listOf(inside, onRoot)) {
            given().`when`().get("/api/shares/$shareId/blobs/${served.blobId}").then().statusCode(200)
        }
        for (refused in listOf(onTrashed, beside, above)) {
            given().`when`().get("/api/shares/$shareId/blobs/${refused.blobId}")
                .then().statusCode(404).body("code", equalTo("share_not_found"))
        }
    }

    @Test
    fun anIdentityAnAttachmentNamesIsKeptAndAnUnreferencedOneDropped() {
        val (userId, scopeId) = seedMemberScope()
        val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val (shareId, named) = seedDeletedLinkWithGuest(userId, scopeId, folder)
        val unnamed = seedGuest(shareId)
        seedAttachment(scopeId, folder, slot, shareIdentityId = named)

        QuarkusTransaction.requiringNew().run { sharing.dropDeadIdentities(listOf(named, unnamed)) }

        QuarkusTransaction.requiringNew().run {
            assertNotNull(ShareIdentityEntity.findById(named), "an attachment still names it: kept")
            assertNull(ShareIdentityEntity.findById(unnamed), "nothing names it and its link is gone: dropped")
        }
    }

    @Test
    fun theDatabaseRefusesToDropANodeOrBlobAnAttachmentNames() {
        val (_, scopeId) = seedMemberScope()
        val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val attached = seedAttachment(scopeId, folder, slot)

        assertThrows(Exception::class.java, { QuarkusTransaction.requiringNew().run { NodeEntity.delete("id", folder) } },
            "fk_attachment_node refuses: no cascade drops the attachment behind deleteBlobRow's back")
        assertThrows(Exception::class.java, { QuarkusTransaction.requiringNew().run { BlobEntity.delete("id", attached.blobId) } },
            "fk_attachment_blob refuses")

        QuarkusTransaction.requiringNew().run {
            assertNotNull(NodeEntity.findById(folder), "both deletes rolled back")
            assertNotNull(NodeAttachmentEntity.findById(attached.attachmentId))
            assertNotNull(BlobEntity.findById(attached.blobId))
        }
    }

    @Test
    fun aSlotHoldsOneAttachmentAndABlobBelongsToOne() {
        val (_, scopeId) = seedMemberScope()
        val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val other = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val first = seedAttachment(scopeId, folder, slot)

        // control: another slot on the same node is a row of its own
        seedAttachment(scopeId, folder, "SECOND_SLOT")
        assertThrows(Exception::class.java, { seedAttachment(scopeId, folder, slot) }, "ux_attachment_node_slot")
        assertThrows(Exception::class.java, { attach(scopeId, other, slot, first.blobId) }, "ux_attachment_blob")

        QuarkusTransaction.requiringNew().run {
            assertEquals(2L, NodeAttachmentEntity.count("nodeId", folder))
            assertEquals(0L, NodeAttachmentEntity.count("nodeId", other))
        }
    }

    private fun attach(scopeId: String, nodeId: String, slot: String, blobId: String) =
        QuarkusTransaction.requiringNew().run {
            NodeAttachmentEntity().apply {
                id = UUID.randomUUID().toString(); this.scopeId = scopeId; this.nodeId = nodeId; this.slot = slot
                this.blobId = blobId
            }.persist()
        }

    /** A link on [nodeId] deleted earlier (a tombstone) and one guest identity on it: `shareId to guestId`. */
    private fun seedDeletedLinkWithGuest(userId: String, scopeId: String, nodeId: String): Pair<String, String> {
        val shareId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            ShareEntity().apply {
                id = shareId; ownerAccount = userId; this.scopeId = scopeId; this.nodeId = nodeId; role = ShareEntity.READ_WRITE
                enabled = false; createdAt = Instant.now(); deletedAt = Instant.now().minusSeconds(7200)
            }.persist()
        }
        return shareId to seedGuest(shareId)
    }

    private fun seedGuest(shareId: String): String {
        val guestId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            ShareIdentityEntity().apply {
                id = guestId; this.shareId = shareId; sealedDisplayName = "Z3Vlc3Q="; tokenHash = "hash-$guestId"
            }.persist()
        }
        return guestId
    }

    private fun postShare(userId: String, nodeId: String, role: String) =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"$nodeId","role":"$role",${shareKeysJson(role, nodeId)},"sealedName":"c2VhbGVk",${ShareWriteProof.recovery()}}""")
            .`when`().post("/api/shares")
            .then()
}
