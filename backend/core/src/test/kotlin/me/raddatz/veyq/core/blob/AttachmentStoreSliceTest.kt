package me.raddatz.veyq.core.blob

import me.raddatz.veyq.core.blobstore.StoreReclaimEntity
import me.raddatz.veyq.core.content.NodesStore
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Core's attachment write path (cr-files-application P3-S4), core alone: [AttachmentStore.attach] directly, and once
 * through the completion dispatcher with an application's completion that calls it — a made-up `EXPENSE` kind with its
 * own slot, as `UploadCompletionGenericItemCoreSliceTest` builds one. The races are `AttachmentRaceSliceTest`'s.
 *
 * Every check reads what is committed, in a fresh transaction: the attachment row, the blob rows and the reclaim
 * records of the storage keys. A freed blob is a missing row AND exactly one reclaim record — a bare delete leaves its
 * objects with nothing pointing at them, and there is no sweep.
 */
@QuarkusTest
class AttachmentStoreSliceTest {

    @Inject lateinit var store: AttachmentStore
    @Inject lateinit var blobs: BlobUploadStore
    @Inject lateinit var uploads: BlobUploadHandler
    @Inject lateinit var nodes: NodesStore

    @Test
    fun aFirstAttachFillsTheEmptySlot() {
        val (userId, scopeId) = seedMemberScope()
        val node = seedNode(scopeId, EXPENSE, wrappedContentKey = CONTENT_KEY)
        val upload = AttachmentUploads.open(blobs, userId, scopeId, node, SLOT)

        val outcome = attach(scopeId, node, upload, expected = null)

        assertEquals(AttachOutcome.Attached(upload.blobId, null), outcome)
        QuarkusTransaction.requiringNew().run {
            val row = NodeAttachmentEntity.find("nodeId = ?1 and slot = ?2", node, SLOT).singleResult()
            assertEquals(upload.blobId, row.blobId)
            assertEquals(scopeId, row.scopeId)
            assertNull(row.shareIdentityId, "a member's upload names no guest")
            val blob = BlobEntity.findById(upload.blobId)!!
            assertEquals(upload.storageKey, blob.storageKey)
            assertEquals(AttachmentUploads.WRAP, blob.wrappedBlobKey, "the session's wrap moved onto the blob as it was")
            assertEquals(0L, StoreReclaimEntity.count("storageKey", upload.storageKey), "nothing is reclaimed")
            assertNull(UploadSessionEntity.findById(upload.session.id), "the session is spent")
        }
    }

    @Test
    fun aReplaceWithTheExpectedBlobSwapsItAndFreesTheOldOne() {
        val (userId, scopeId) = seedMemberScope()
        val node = seedNode(scopeId, EXPENSE, wrappedContentKey = CONTENT_KEY)
        val old = AttachmentUploads.open(blobs, userId, scopeId, node, SLOT)
        attach(scopeId, node, old, expected = null)
        val new = AttachmentUploads.open(blobs, userId, scopeId, node, SLOT)

        val outcome = attach(scopeId, node, new, expected = old.blobId)

        assertEquals(AttachOutcome.Attached(new.blobId, old.blobId), outcome)
        QuarkusTransaction.requiringNew().run {
            assertEquals(new.blobId, NodeAttachmentEntity.find("nodeId", node).singleResult().blobId)
            assertNull(BlobEntity.findById(old.blobId), "the replaced blob's row went")
            assertEquals(1L, StoreReclaimEntity.count("storageKey", old.storageKey), "and its objects are on the reclaim list")
            assertNotNull(BlobEntity.findById(new.blobId))
            assertEquals(0L, StoreReclaimEntity.count("storageKey", new.storageKey))
        }
    }

    @Test
    fun aReplaceWithAStaleExpectedBlobConflictsAndFreesOnlyTheNewUpload() {
        val (userId, scopeId) = seedMemberScope()
        val node = seedNode(scopeId, EXPENSE, wrappedContentKey = CONTENT_KEY)
        val old = AttachmentUploads.open(blobs, userId, scopeId, node, SLOT)
        attach(scopeId, node, old, expected = null)
        val new = AttachmentUploads.open(blobs, userId, scopeId, node, SLOT)

        val outcome = attach(scopeId, node, new, expected = UUID.randomUUID().toString())

        assertEquals(AttachOutcome.Conflict(old.blobId), outcome, "the conflict names what the slot holds")
        QuarkusTransaction.requiringNew().run {
            assertEquals(old.blobId, NodeAttachmentEntity.find("nodeId", node).singleResult().blobId, "the slot is unchanged")
            assertNotNull(BlobEntity.findById(old.blobId))
            assertEquals(0L, StoreReclaimEntity.count("storageKey", old.storageKey))
            assertNull(BlobEntity.findById(new.blobId), "the loser's upload is no blob")
            assertEquals(1L, StoreReclaimEntity.count("storageKey", new.storageKey), "its objects are reclaimed")
            assertNull(UploadSessionEntity.findById(new.session.id), "and its session spent")
        }
    }

    @Test
    fun aFirstAttachIntoAnOccupiedSlotConflictsAndAReplaceOfAnEmptyOneToo() {
        val (userId, scopeId) = seedMemberScope()
        val node = seedNode(scopeId, EXPENSE, wrappedContentKey = CONTENT_KEY)
        val empty = seedNode(scopeId, EXPENSE, wrappedContentKey = CONTENT_KEY)
        val held = AttachmentUploads.open(blobs, userId, scopeId, node, SLOT)
        attach(scopeId, node, held, expected = null)
        val second = AttachmentUploads.open(blobs, userId, scopeId, node, SLOT)
        val intoEmpty = AttachmentUploads.open(blobs, userId, scopeId, empty, SLOT)

        assertEquals(AttachOutcome.Conflict(held.blobId), attach(scopeId, node, second, expected = null))
        assertEquals(AttachOutcome.Conflict(null), attach(scopeId, empty, intoEmpty, expected = held.blobId))

        QuarkusTransaction.requiringNew().run {
            assertEquals(held.blobId, NodeAttachmentEntity.find("nodeId", node).singleResult().blobId)
            assertEquals(0L, NodeAttachmentEntity.count("nodeId", empty))
            for (lost in listOf(second, intoEmpty)) {
                assertNull(BlobEntity.findById(lost.blobId))
                assertEquals(1L, StoreReclaimEntity.count("storageKey", lost.storageKey))
            }
        }
    }

    @Test
    fun aTrashedNodeAnotherKindAndAnotherScopeAreRefusedAndWriteNothing() {
        val (userId, scopeId) = seedMemberScope()
        val trashed = seedNode(scopeId, EXPENSE, wrappedContentKey = CONTENT_KEY)
        val held = AttachmentUploads.open(blobs, userId, scopeId, trashed, SLOT)
        attach(scopeId, trashed, held, expected = null)
        QuarkusTransaction.requiringNew().run { NodeEntity.findById(trashed)!!.deletedAt = Instant.now() }
        val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val (_, otherScope) = seedMemberScope()
        val foreign = seedNode(otherScope, EXPENSE, wrappedContentKey = CONTENT_KEY)

        val replace = AttachmentUploads.open(blobs, userId, scopeId, trashed, SLOT)
        val first = AttachmentUploads.open(blobs, userId, scopeId, trashed, "SECOND")
        val wrongKind = AttachmentUploads.open(blobs, userId, scopeId, folder, SLOT)
        val crossScope = AttachmentUploads.open(blobs, userId, scopeId, foreign, SLOT)

        assertEquals(AttachOutcome.NodeNotFound, attach(scopeId, trashed, replace, expected = held.blobId), "a replace on a trashed node")
        assertEquals(AttachOutcome.NodeNotFound, attach(scopeId, trashed, first, expected = null, slot = "SECOND"), "a first attach too")
        assertEquals(AttachOutcome.IllegalTarget, attach(scopeId, folder, wrongKind, expected = null))
        assertEquals(AttachOutcome.NodeNotFound, attach(scopeId, foreign, crossScope, expected = null), "another scope's node")

        assertNothingWritten(replace, first, wrongKind, crossScope)
        QuarkusTransaction.requiringNew().run {
            assertEquals(held.blobId, NodeAttachmentEntity.find("nodeId", trashed).singleResult().blobId, "the trashed node keeps its blob")
        }
    }

    @Test
    fun aSessionAttachesOnceAndASecondAttachIsUploadNotFound() {
        val (userId, scopeId) = seedMemberScope()
        val node = seedNode(scopeId, EXPENSE, wrappedContentKey = CONTENT_KEY)
        val other = seedNode(scopeId, EXPENSE, wrappedContentKey = CONTENT_KEY)
        val upload = AttachmentUploads.open(blobs, userId, scopeId, node, SLOT)

        assertInstanceOf(AttachOutcome.Attached::class.java, attach(scopeId, node, upload, expected = null))
        // Replayed as a replace of what it attached: the spend decides first, so the slot is never touched again.
        assertEquals(AttachOutcome.UploadNotFound, attach(scopeId, node, upload, expected = upload.blobId))
        assertEquals(AttachOutcome.UploadNotFound, attach(scopeId, node, upload, expected = null))

        QuarkusTransaction.requiringNew().run {
            assertEquals(upload.blobId, NodeAttachmentEntity.find("nodeId", node).singleResult().blobId)
            assertEquals(0L, NodeAttachmentEntity.count("nodeId", other))
            assertEquals(0L, StoreReclaimEntity.count("storageKey", upload.storageKey))
        }
    }

    @Test
    fun aRefusedGateAMismatchedSessionAndABadSlotWriteNothing() {
        val (userId, scopeId) = seedMemberScope()
        val node = seedNode(scopeId, EXPENSE, wrappedContentKey = CONTENT_KEY)
        val refused = AttachmentUploads.open(blobs, userId, scopeId, node, SLOT)
        val otherSlot = AttachmentUploads.open(blobs, userId, scopeId, node, "SECOND")
        val otherOwner = AttachmentUploads.open(blobs, userId, scopeId, UUID.randomUUID().toString(), SLOT)

        assertEquals(AttachOutcome.NotAdmitted, attach(scopeId, node, refused, expected = null, admitted = { false }))
        assertEquals(AttachOutcome.UploadMismatch, attach(scopeId, node, otherSlot, expected = null, slot = SLOT), "sealed for another slot")
        assertEquals(AttachOutcome.UploadMismatch, attach(scopeId, node, otherOwner, expected = null), "sealed for another node")
        for (bad in listOf("file_content", "1X", "A".repeat(17), "FILE\n")) {
            assertThrows(IllegalArgumentException::class.java, { attach(scopeId, node, refused, expected = null, slot = bad) }, bad)
        }

        assertNothingWritten(refused, otherSlot, otherOwner)
        QuarkusTransaction.requiringNew().run { assertEquals(0L, NodeAttachmentEntity.count("nodeId", node)) }
    }

    /**
     * The integration point: an application's [UploadCompletion] reached through core's dispatcher calls
     * [AttachmentStore.attach] with the expected blob it carries in its own payloads — first attach, then a replace.
     */
    @Test
    fun anApplicationsCompletionAttachesAndReplacesThroughTheDispatcher() {
        val (userId, scopeId) = seedMemberScope()
        val node = seedNode(scopeId, EXPENSE, wrappedContentKey = CONTENT_KEY)
        val dispatcher = UploadCompletions(nodes, mutableListOf(ExpenseAttachmentCompletion(blobs, uploads, store)))
        val opener = UploadOpener.Member(userId)
        val first = AttachmentUploads.open(blobs, userId, scopeId, node, SLOT)
        val second = AttachmentUploads.open(blobs, userId, scopeId, node, SLOT)

        fun complete(uploadId: String, expected: String?) = QuarkusTransaction.requiringNew().call {
            dispatcher.complete(scopeId, listOf(ExpenseAttachmentCompletion.item(node, uploadId, expected)), opener)
        }

        val attached = complete(first.session.id, null)
        val replaced = complete(second.session.id, first.blobId)
        val replayed = complete(second.session.id, first.blobId)

        assertEquals(CompleteItemResult(first.blobId, node, null), attached.single())
        assertEquals(CompleteItemResult(second.blobId, node, null), replaced.single())
        assertEquals(CompleteItemResult(null, null, "upload_not_found"), replayed.single())
        QuarkusTransaction.requiringNew().run {
            assertEquals(second.blobId, NodeAttachmentEntity.find("nodeId", node).singleResult().blobId)
            assertEquals(1L, StoreReclaimEntity.count("storageKey", first.storageKey))
        }
    }

    private fun attach(
        scopeId: String, node: String, upload: AttachmentUploads.Opened, expected: String?,
        slot: String = upload.slot, admitted: () -> Boolean = { true },
    ): AttachOutcome = store.attach(scopeId, node, setOf(EXPENSE), slot, upload.session, null, expected, admitted)

    /** Each refused upload: no blob row, no reclaim record, and its session still open (the spend rolled back). */
    private fun assertNothingWritten(vararg refused: AttachmentUploads.Opened) =
        QuarkusTransaction.requiringNew().run {
            for (u in refused) {
                assertNull(BlobEntity.findById(u.blobId), "no blob row")
                assertEquals(0L, NodeAttachmentEntity.count("blobId", u.blobId))
                assertEquals(0L, StoreReclaimEntity.count("storageKey", u.storageKey))
                assertNotNull(UploadSessionEntity.findById(u.session.id), "the session is still open")
            }
        }

    private companion object {
        const val EXPENSE = ExpenseAttachmentCompletion.EXPENSE
        const val SLOT = ExpenseAttachmentCompletion.SLOT
    }
}
