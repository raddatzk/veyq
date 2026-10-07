package me.raddatz.veyq.core.quota

import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.ShareIdentityEntity
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedAttachment
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.memories.media.BucketEntity
import me.raddatz.veyq.memories.media.MediaEntity
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A Memory link's usage with both blob terms at once (cr-files-application P3-S5): Memories' `MediaUploadAdmission`
 * (its guests' media) and core's attachment term (its guests' node attachments) are added, and neither counts a blob
 * of the other — no blob is in two arms (each arm's row is born with a fresh blob; `ux_attachment_blob`). A medium of
 * [media] bytes and an attachment of `SMALL` bytes on the same Memory sum to exactly their sizes. Memories' own
 * media-only cases stay `ShareLinkStorageLimitSliceTest`'s, unchanged.
 */
@QuarkusTest
class LinkUsageTermsSliceTest {

    @Inject lateinit var quota: StorageQuota

    private val media = TestBlob.ofPlaintext(100) // a size the attachment has not, so the sum shows both terms

    @Test
    fun aMemoryLinksMediaAndAttachmentsAddWithoutOverlap() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val shareId = UUID.randomUUID().toString()
        val guestId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            ShareEntity().apply {
                id = shareId; ownerAccount = userId; this.scopeId = scopeId; nodeId = memory; role = ShareEntity.READ_WRITE
                wrap = "d3JhcA=="; sealedName = "c2VhbGVk"; createdAt = Instant.now()
            }.persist()
            ShareIdentityEntity().apply {
                id = guestId; this.shareId = shareId; sealedDisplayName = "Z3Vlc3Q="; tokenHash = "hash-$guestId"; createdAt = Instant.now()
            }.persist()
        }
        seedGuestMedium(scopeId, memory, guestId)
        assertEquals(media.byteSize, usage(shareId), "control: Memories' term alone")

        seedAttachment(scopeId, memory, "FILE_CONTENT", shareIdentityId = guestId)

        assertEquals(media.byteSize + TestBlob.SMALL.byteSize, usage(shareId))
    }

    private fun usage(shareId: String): Long? =
        QuarkusTransaction.requiringNew().call { quota.linkUsage(shareId, MemoriesNodeKinds.MEMORY) }

    /** A medium the guest contributed, as `MediaStore.attach` lands it: in their primary bucket, named by their identity. */
    private fun seedGuestMedium(scopeId: String, nodeId: String, identityId: String) {
        val blobId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            BlobEntity().apply {
                id = blobId; this.scopeId = scopeId; storageKey = UUID.randomUUID().toString().replace("-", "")
                chunkCount = media.chunkCount; byteSize = media.byteSize; wrappedBlobKey = "Ymxvyi13cmFw"; createdAt = Instant.now()
            }.persist()
            val bucket = BucketEntity().apply {
                id = UUID.randomUUID().toString(); this.scopeId = scopeId; this.nodeId = nodeId
                ownerKey = "share:$identityId"; sealedName = "Z3Vlc3Q="; isPrimary = true; createdAt = Instant.now()
            }.also { it.persist() }
            MediaEntity().apply {
                id = UUID.randomUUID().toString(); this.scopeId = scopeId; this.nodeId = nodeId; this.blobId = blobId
                shareIdentityId = identityId; createdBy = bucket.ownerKey; bucketId = bucket.id; createdAt = Instant.now()
            }.persist()
        }
    }
}
