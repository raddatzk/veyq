package me.raddatz.veyq.core.quota

import me.raddatz.veyq.core.blob.AttachOutcome
import me.raddatz.veyq.core.blob.AttachmentStore
import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.blob.BlobUploadStore
import me.raddatz.veyq.core.blob.UploadOpener
import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.content.NodeSubtreePurge
import me.raddatz.veyq.core.content.NodesStore
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.ShareIdentityEntity
import me.raddatz.veyq.core.tenant.TenantEntity
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedAttachment
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The attachment term of a link's usage (cr-files-application P3-S5, ADR 0073 §4): core sums the blobs of every
 * `veyq_node_attachment` row one of the link's identities uploaded, beside its open sessions and whatever the target
 * kind's [UploadAdmission] reports. Core alone registers no admission (`CoreAloneSliceTest`), so each case builds a
 * [StorageQuota] over one made-up admission for a made-up kind ([AppTerm]) — the way `UploadAdmissionPartitionSliceTest`
 * forges its lists. That admission reports a constant [APP_BYTES], so every expected value below is `APP_BYTES +` the
 * core terms: an application's term and core's are added, neither replaces the other.
 */
@QuarkusTest
class LinkAttachmentUsageSliceTest {

    @Inject lateinit var em: EntityManager
    @Inject lateinit var usage: StorageUsage
    @Inject lateinit var attachments: AttachmentStore
    @Inject lateinit var blobs: BlobUploadStore
    @Inject lateinit var nodes: NodesStore
    @Inject lateinit var subtreePurge: NodeSubtreePurge
    @Inject lateinit var registered: StorageQuota

    private val s = TestBlob.SMALL
    private val big = TestBlob.ofPlaintext(100) // a size no other blob here has, so a swap is visible in the sum

    /** An application's admission for [KIND] that reports a fixed blob term — what an application measures of its own. */
    private class AppTerm : UploadAdmission {
        override fun supports(nodeKind: String): Boolean = nodeKind == KIND
        override fun linkBlobUsage(shareId: String): Long = APP_BYTES
    }

    private val quota by lazy { StorageQuota(em, usage, mutableListOf(AppTerm())) }

    @Test
    fun aLinksUsageCountsItsIdentitiesAttachmentsAndNoOtherLinksNorAMembers() {
        val f = fixture()
        val (other, otherGuest) = link(f)
        val second = guest(f.shareId)
        seedAttachment(f.scopeId, node(f), SLOT, shareIdentityId = f.guestId)
        seedAttachment(f.scopeId, node(f), SLOT, shareIdentityId = second)
        seedAttachment(f.scopeId, node(f), SLOT, shareIdentityId = otherGuest) // the other link's
        seedAttachment(f.scopeId, node(f), SLOT) // a member's: no identity

        assertEquals(APP_BYTES + 2 * s.byteSize, linkUsage(f.shareId), "both of this link's guests, nothing else")
        assertEquals(APP_BYTES + s.byteSize, linkUsage(other), "the other link holds only its own")
    }

    /**
     * A member's cover (`veyq_node.cover_blob_id`) is no attachment row, so the attachment term never meets it — and no
     * blob is in two arms (every arm's row is born with a fresh blob in the transaction that consumes its session;
     * `ux_attachment_blob`), so the attachment term and an application's term cannot both count one blob.
     */
    @Test
    fun aCoverIsNoAttachmentAndIsNotCounted() {
        val f = fixture()
        val target = node(f)
        seedAttachment(f.scopeId, target, SLOT, shareIdentityId = f.guestId)
        val cover = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            BlobEntity().apply {
                id = cover; scopeId = f.scopeId; storageKey = UUID.randomUUID().toString().replace("-", "")
                chunkCount = big.chunkCount; byteSize = big.byteSize; wrappedBlobKey = "Y292ZXI="
            }.persist()
            NodeEntity.findById(target)!!.coverBlobId = cover
        }
        assertEquals(APP_BYTES + s.byteSize, linkUsage(f.shareId))
    }

    /** The exact boundary, with attachment bytes already held: an upload that exactly fits is admitted, one byte more is not. */
    @Test
    fun anUploadThatExactlyFitsIsAdmittedAndOneByteMoreIsNot() {
        val f = fixture()
        seedAttachment(f.scopeId, node(f), SLOT, shareIdentityId = f.guestId)
        val held = APP_BYTES + s.byteSize

        setLimit(f.tenantId, held + s.byteSize)
        assertTrue(fits(f, s), "held + S at a limit of held + S fits")
        setLimit(f.tenantId, held + s.byteSize - 1)
        assertFalse(fits(f, s), "one byte over is refused")
    }

    /**
     * Through the real write path: the guest's open session counts, the attach turns it into a blob without counting
     * it twice, and a replace counts the new blob only — the old row is gone with `deleteBlobRow`.
     */
    @Test
    fun aReplaceCountsTheNewBlobAndNoLongerTheOld() {
        val f = fixture()
        val target = node(f)
        val first = open(f, target, s)
        assertEquals(APP_BYTES + s.byteSize, linkUsage(f.shareId), "the open session counts")
        assertInstanceOf(AttachOutcome.Attached::class.java, attach(f, target, first, expected = null))
        assertEquals(APP_BYTES + s.byteSize, linkUsage(f.shareId), "the session became the blob: counted once")

        val second = open(f, target, big)
        assertInstanceOf(AttachOutcome.Attached::class.java, attach(f, target, second, expected = first.blobId))

        QuarkusTransaction.requiringNew().run { assertNull(BlobEntity.findById(first.blobId!!), "the old blob row went") }
        assertEquals(APP_BYTES + big.byteSize, linkUsage(f.shareId), "the new blob, not the old one beside it")
    }

    @Test
    fun aPurgedAttachmentNoLongerCounts() {
        val f = fixture()
        val target = node(f)
        seedAttachment(f.scopeId, target, SLOT, shareIdentityId = f.guestId)
        assertEquals(APP_BYTES + s.byteSize, linkUsage(f.shareId), "control: it counted, trashed or not")

        assertEquals(true, QuarkusTransaction.requiringNew().call { nodes.deleteSubtree(f.scopeId, target) })
        assertEquals(APP_BYTES + s.byteSize, linkUsage(f.shareId), "trash is still in the store (ADR 0073 §2)")
        QuarkusTransaction.requiringNew().run { subtreePurge.eraseExpiredIn(f.scopeId, Instant.now().plusSeconds(1)) }

        assertEquals(APP_BYTES, linkUsage(f.shareId), "back to the baseline")
    }

    /**
     * The core term never measures a link on its own: a kind no admission measures stays unmeasured (null) and refuses
     * every link start, however much room the limit leaves and whatever attachments the link holds — the fail-closed
     * rule of [UploadAdmission] is unchanged. Control: the same link through the measured kind fits.
     */
    @Test
    fun aKindNoAdmissionMeasuresStaysClosedDespiteTheCoreTerm() {
        val f = fixture()
        seedAttachment(f.scopeId, node(f), SLOT, shareIdentityId = f.guestId)
        setLimit(f.tenantId, Long.MAX_VALUE / 2)

        for (kind in listOf(NodeEntity.FOLDER, "UNCLAIMED")) {
            assertNull(QuarkusTransaction.requiringNew().call { quota.linkUsage(f.shareId, kind) }, "$kind is measured")
            assertFalse(fits(f, s, kind = kind), "a link start into a $kind is admitted")
        }
        assertFalse(fits(f, s, q = registered), "core's own registration (no admission at all) admits a $KIND link")
        assertTrue(fits(f, s), "control: the measured kind fits")
    }

    // --- fixture ---

    data class Link(val tenantId: String, val scopeId: String, val userId: String, val root: String, val shareId: String, val guestId: String)

    /** A READ_WRITE link on a [KIND] node with one guest identity. */
    private fun fixture(): Link {
        val (userId, scopeId, tenantId) = seedMemberScope()
        val root = seedNode(scopeId, KIND, wrappedContentKey = CONTENT_KEY)
        val partial = Link(tenantId, scopeId, userId, root, shareId = "", guestId = "")
        val (shareId, guestId) = link(partial)
        return partial.copy(shareId = shareId, guestId = guestId)
    }

    /** Another link on [f]'s root with one guest: `shareId to guestId`. */
    private fun link(f: Link): Pair<String, String> {
        val shareId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            ShareEntity().apply {
                id = shareId; ownerAccount = f.userId; scopeId = f.scopeId; nodeId = f.root; role = ShareEntity.READ_WRITE
                wrap = "d3JhcA=="; sealedName = "c2VhbGVk"; createdAt = Instant.now()
            }.persist()
        }
        return shareId to guest(shareId)
    }

    private fun guest(shareId: String): String {
        val guestId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            ShareIdentityEntity().apply {
                id = guestId; this.shareId = shareId; sealedDisplayName = "Z3Vlc3Q="; tokenHash = "hash-$guestId"
            }.persist()
        }
        return guestId
    }

    /** A node of [KIND] below the link's root. */
    private fun node(f: Link): String = seedNode(f.scopeId, KIND, parentId = f.root, wrappedContentKey = CONTENT_KEY)

    /** The guest's open session for [SLOT] of [nodeId], read back detached. */
    private fun open(f: Link, nodeId: String, blob: TestBlob): UploadSessionEntity {
        val id = blobs.createSession(
            f.scopeId, UploadOpener.Contributor(f.guestId), UUID.randomUUID().toString().replace("-", ""),
            blob.upload("YXR0YWNo", ownerId = nodeId, role = SLOT),
        )
        return QuarkusTransaction.requiringNew().call { UploadSessionEntity.findById(id)!! }
    }

    private fun attach(f: Link, nodeId: String, session: UploadSessionEntity, expected: String?): AttachOutcome =
        attachments.attach(f.scopeId, nodeId, setOf(KIND), SLOT, session, null, expected)

    private fun linkUsage(shareId: String): Long? = QuarkusTransaction.requiringNew().call { quota.linkUsage(shareId, KIND) }

    private fun fits(f: Link, blob: TestBlob, kind: String = KIND, q: StorageQuota = quota): Boolean =
        QuarkusTransaction.requiringNew().call {
            q.linkFits(TenantEntity.findById(f.tenantId)!!, f.shareId, kind, listOf(blob.upload("d3JhcA==")))
        }

    private fun setLimit(tenantId: String, bytes: Long) =
        QuarkusTransaction.requiringNew().run { TenantEntity.findById(tenantId)!!.shareLinkLimitBytes = bytes }

    companion object {
        /** A kind no application in this repo declares, and its slot (= the role its uploads are started in). */
        const val KIND = "EXPENSE"
        const val SLOT = "RECEIPT"

        /** What [AppTerm] reports: an odd number, so it never coincides with a blob size. */
        const val APP_BYTES = 7L
    }
}
