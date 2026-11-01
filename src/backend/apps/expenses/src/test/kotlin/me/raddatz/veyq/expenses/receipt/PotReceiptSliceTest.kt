package me.raddatz.veyq.expenses.receipt

import me.raddatz.veyq.core.applicationblob.ApplicationBlobEntity
import me.raddatz.veyq.core.applicationblob.ApplicationBlobPurge
import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.blobstore.FakePresigningBlobStore
import me.raddatz.veyq.core.blobstore.StoreReclaimEntity
import me.raddatz.veyq.core.content.NodeSubtreePurge
import me.raddatz.veyq.core.moderation.guestwrite.GuestWriteRecordEntity
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds
import me.raddatz.veyq.core.platform.capabilities.TenantCapabilitySwitchFixtures
import me.raddatz.veyq.core.quota.StorageQuota
import me.raddatz.veyq.core.sharing.GuestTreeShare
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.ShareIdentityEntity
import me.raddatz.veyq.core.tenant.TenantEntity
import me.raddatz.veyq.core.testing.FakePresignProfile
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.expenses.ExpensesNodeKinds
import me.raddatz.veyq.expenses.pot.PotRevisionEntity
import me.raddatz.veyq.expenses.pot.PotWire
import me.raddatz.veyq.expenses.pot.PotWire.Rev
import me.raddatz.veyq.expenses.pot.PotWire.append
import me.raddatz.veyq.expenses.receipt.ReceiptKit.blob
import me.raddatz.veyq.expenses.receipt.ReceiptKit.blobOf
import me.raddatz.veyq.expenses.receipt.ReceiptKit.complete
import me.raddatz.veyq.expenses.receipt.ReceiptKit.download
import me.raddatz.veyq.expenses.receipt.ReceiptKit.guestComplete
import me.raddatz.veyq.expenses.receipt.ReceiptKit.guestDownload
import me.raddatz.veyq.expenses.receipt.ReceiptKit.guestList
import me.raddatz.veyq.expenses.receipt.ReceiptKit.guestStart
import me.raddatz.veyq.expenses.receipt.ReceiptKit.guestStartStaged
import me.raddatz.veyq.expenses.receipt.ReceiptKit.list
import me.raddatz.veyq.expenses.receipt.ReceiptKit.receipt
import me.raddatz.veyq.expenses.receipt.ReceiptKit.start
import me.raddatz.veyq.expenses.receipt.ReceiptKit.startStaged
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * A Pot's receipts (ADR 0108 decision 7, docs/apps/expenses.md § Receipts) end to end through the wire the web will
 * use: core's `startBlobUploads` / `startShareBlobUploads` (role `EXPENSES_RECEIPT`, owner the Pot), the chunks staged as
 * an honest client leaves them, core's blob service's completion and reads (ADR 0112: a receipt is an application blob
 * held by its entry), a revision's keep and drop, the service's purge after the trash retention, core's presigned
 * downloads and a content block. Core's service with its first application: it needs Expenses at runtime, so it lives
 * here (src/backend/CLAUDE.md § a test lives where what it exercises runs). [FakePresignProfile] makes
 * declare-then-verify run, so a completion passes the same budget check a real store does. Each refusal sits beside its
 * positive control through the same route.
 */
@QuarkusTest
@TestProfile(FakePresignProfile::class)
class PotReceiptSliceTest {

    @Inject lateinit var fakeStore: FakePresigningBlobStore
    @Inject lateinit var purge: ApplicationBlobPurge
    @Inject lateinit var subtreePurge: NodeSubtreePurge
    @Inject lateinit var quota: StorageQuota

    private val tenMib = ExpensesBlobRoles.RECEIPT_MAX_PLAINTEXT_BYTES

    @Test
    fun anElevenMibReceiptIsRefusedAtItsSize_oneOfTenMibLands() {
        val (userId, scopeId) = seedMemberScope()
        val pot = seedNode(scopeId, ExpensesNodeKinds.POT, wrappedContentKey = CONTENT_KEY)
        assertEquals(10_486_880L, ExpensesBlobRoles.RECEIPT_MAX_SEALED_BYTES, "10 MiB of plaintext, sealed in 40 frames")
        // Refused at the start, by its size: no session, nothing reserved.
        start(userId, scopeId, pot, TestBlob.ofPlaintext(11 * 1024 * 1024L)).statusCode(200).body("results[0].error", equalTo("budget_exceeded"))
        start(userId, scopeId, pot, TestBlob.ofPlaintext(tenMib + 1)).statusCode(200).body("results[0].error", equalTo("budget_exceeded"))
        assertEquals(0L, QuarkusTransaction.requiringNew().call { UploadSessionEntity.count("ownerId", pot) }, "no session opened")
        // A guest's start is held to the role's cap too.
        val link = GuestTreeShare.seed(userId, scopeId, pot, ShareEntity.READ_WRITE)
        guestStart(link, pot, TestBlob.ofPlaintext(11 * 1024 * 1024L)).statusCode(200).body("results[0].error", equalTo("budget_exceeded"))

        // Control: exactly 10 MiB of plaintext starts, verifies and lands.
        val entry = UUID.randomUUID().toString()
        val receiptId = UUID.randomUUID().toString()
        val upload = startStaged(fakeStore, userId, scopeId, pot, TestBlob.ofPlaintext(tenMib))
        complete(userId, scopeId, pot, entry, receiptId, upload).statusCode(200)
            .body("id", equalTo(receiptId)).body("ownerId", equalTo(entry))
            .body("byteSize", equalTo(10_486_880)).body("chunkCount", equalTo(40))
            .body("wrappedBlobKey", equalTo(ReceiptKit.WRAP)).body("wrappedBlobMeta", equalTo(ReceiptKit.META))
            .body("writer", equalTo("user:$userId")).body("blockedByOperator", equalTo(false)).body("releasedAt", nullValue())
        // A role without a cap of its own keeps core's per-blob cap only: an 11 MiB cover of a folder starts.
        val folder = seedNode(scopeId, "FOLDER", wrappedContentKey = CONTENT_KEY)
        start(userId, scopeId, folder, TestBlob.ofPlaintext(11 * 1024 * 1024L), role = "COVER").statusCode(200).body("results[0].error", nullValue())
    }

    @Test
    fun aSessionForAnotherNodeOrRoleIsRefused_theRightOneLands() {
        val (userId, scopeId) = seedMemberScope()
        val pot = seedNode(scopeId, ExpensesNodeKinds.POT, wrappedContentKey = CONTENT_KEY)
        val other = seedNode(scopeId, ExpensesNodeKinds.POT, wrappedContentKey = CONTENT_KEY)
        val entry = UUID.randomUUID().toString()

        val forOther = startStaged(fakeStore, userId, scopeId, pot, owner = other)
        complete(userId, scopeId, pot, entry, UUID.randomUUID().toString(), forOther).statusCode(422).body("code", equalTo("upload_mismatch"))
        val asCover = startStaged(fakeStore, userId, scopeId, pot, role = "COVER")
        complete(userId, scopeId, pot, entry, UUID.randomUUID().toString(), asCover).statusCode(422).body("code", equalTo("upload_mismatch"))
        assertEquals(2L, QuarkusTransaction.requiringNew().call { UploadSessionEntity.count("id in ?1", listOf(forOther, asCover)) }, "both stay open")
        assertEquals(0L, QuarkusTransaction.requiringNew().call { ApplicationBlobEntity.count("nodeId", pot) })
        // The other Pot's session lands in the other Pot — its wrap is bound to it.
        complete(userId, scopeId, other, UUID.randomUUID().toString(), UUID.randomUUID().toString(), forOther).statusCode(200)

        // Control: the session for this Pot in the receipt role lands.
        val receiptId = UUID.randomUUID().toString()
        complete(userId, scopeId, pot, entry, receiptId, startStaged(fakeStore, userId, scopeId, pot)).statusCode(200)
        // The same receipt id again, and an entry another Pot holds, are taken; the upload stays open for a retry.
        val again = startStaged(fakeStore, userId, scopeId, pot)
        complete(userId, scopeId, pot, entry, receiptId, again).statusCode(409).body("code", equalTo("id_taken"))
        val elsewhere = UUID.randomUUID().toString()
        append(userId, scopeId, other, listOf(Rev(elsewhere))).statusCode(204)
        complete(userId, scopeId, pot, elsewhere, UUID.randomUUID().toString(), again).statusCode(409).body("code", equalTo("id_taken"))
        complete(userId, scopeId, pot, entry, UUID.randomUUID().toString(), again).statusCode(200)
        // A receipt's id is no record's: an append of a new record under it is taken.
        append(userId, scopeId, pot, listOf(Rev(receiptId))).statusCode(409).body("code", equalTo("id_taken"))
        // A spent upload, an unknown one, and a node that is no Pot.
        complete(userId, scopeId, pot, entry, UUID.randomUUID().toString(), again).statusCode(404).body("code", equalTo("upload_not_found"))
        val folder = seedNode(scopeId, "FOLDER", wrappedContentKey = CONTENT_KEY)
        complete(userId, scopeId, folder, entry, UUID.randomUUID().toString(), startStaged(fakeStore, userId, scopeId, pot))
            .statusCode(404).body("code", equalTo("node_not_found"))
    }

    @Test
    fun aCompletionStatesThePotsEpochAndARefusalLeavesTheUploadOpen() {
        val (userId, scopeId) = seedMemberScope()
        val pot = seedNode(scopeId, ExpensesNodeKinds.POT, wrappedContentKey = CONTENT_KEY)
        val upload = startStaged(fakeStore, userId, scopeId, pot)
        complete(userId, scopeId, pot, UUID.randomUUID().toString(), UUID.randomUUID().toString(), upload, nodeEpoch = 3)
            .statusCode(409).body("code", equalTo("stale_epoch"))
        assertEquals(1L, QuarkusTransaction.requiringNew().call { UploadSessionEntity.count("id", upload) })
        complete(userId, scopeId, pot, UUID.randomUUID().toString(), UUID.randomUUID().toString(), upload).statusCode(200)
    }

    @Test
    fun aRevisionDropsOnlyItsOwnRecordsReceipts_andADropStaysAtItsFirstTime() {
        val (userId, scopeId) = seedMemberScope()
        val pot = seedNode(scopeId, ExpensesNodeKinds.POT, wrappedContentKey = CONTENT_KEY)
        val a = UUID.randomUUID().toString()
        val b = UUID.randomUUID().toString()
        append(userId, scopeId, pot, listOf(Rev(a), Rev(b))).statusCode(204)
        val ra = ReceiptKit.upload(fakeStore, userId, scopeId, pot, a)
        val rb = ReceiptKit.upload(fakeStore, userId, scopeId, pot, b)

        // b's receipt is no receipt of a; an id the Pot holds none of neither: the batch is refused whole.
        append(userId, scopeId, pot, listOf(Rev(a, 2, drops = listOf(rb)))).statusCode(400).body("code", equalTo("invalid_request"))
        append(userId, scopeId, pot, listOf(Rev(a, 2, drops = listOf(UUID.randomUUID().toString())))).statusCode(400)
        append(userId, scopeId, pot, listOf(Rev(a, 2, drops = listOf(ra, ra)))).statusCode(400)
        assertEquals(1L, QuarkusTransaction.requiringNew().call { PotRevisionEntity.count("entryId", a) }, "nothing of a refused batch is stored")
        assertNull(receipt(ra)!!.releasedAt)

        append(userId, scopeId, pot, listOf(Rev(a, 2, drops = listOf(ra)))).statusCode(204) // control
        val first = receipt(ra)!!.releasedAt
        assertNotNull(first)
        assertNull(receipt(rb)!!.releasedAt, "the other record's receipt is held")
        append(userId, scopeId, pot, listOf(Rev(a, 3, drops = listOf(ra)))).statusCode(204)
        assertEquals(first, receipt(ra)!!.releasedAt, "dropped again, it stays at its first time")
        list(userId, scopeId, pot).statusCode(200)
            .body("blobs.find { it.id == '$ra' }.releasedAt", notNullValue())
            .body("blobs.find { it.id == '$ra' }.purgeAfter", notNullValue())
            .body("blobs.find { it.id == '$rb' }.purgeAfter", nullValue())
    }

    /**
     * docs/apps/expenses.md § Receipts, the retention: a dropped receipt, a deleted entry's and one whose entry never got a revision
     * go once the retention has passed since the drop, the tombstone or the completion — and not before; a restored
     * entry's stay. Each goes with its blob row and puts its store objects on the reclaim list (ADR 0062).
     */
    @Test
    fun aDroppedADeletedAndAnOrphanedReceiptGoAfterTheRetentionAndNotBefore_aRestoredEntrysStay() {
        val (userId, scopeId) = seedMemberScope()
        val pot = seedNode(scopeId, ExpensesNodeKinds.POT, wrappedContentKey = CONTENT_KEY)
        val kept = UUID.randomUUID().toString()
        val deleted = UUID.randomUUID().toString()
        val restored = UUID.randomUUID().toString()
        append(userId, scopeId, pot, listOf(Rev(kept), Rev(deleted), Rev(restored))).statusCode(204)
        val dropped = ReceiptKit.upload(fakeStore, userId, scopeId, pot, kept)
        val held = ReceiptKit.upload(fakeStore, userId, scopeId, pot, kept)
        val ofDeleted = ReceiptKit.upload(fakeStore, userId, scopeId, pot, deleted)
        val ofRestored = ReceiptKit.upload(fakeStore, userId, scopeId, pot, restored)
        val orphan = ReceiptKit.upload(fakeStore, userId, scopeId, pot, UUID.randomUUID().toString())
        append(userId, scopeId, pot, listOf(Rev(kept, 2, drops = listOf(dropped)), Rev(deleted, 2, tombstone = true), Rev(restored, 2, tombstone = true)))
            .statusCode(204)
        append(userId, scopeId, pot, listOf(Rev(restored, 3))).statusCode(204) // restored within the retention

        val retention = Duration.ofDays(30) // the test profile's veyq.node.trash.retention, core's default
        list(userId, scopeId, pot).statusCode(200)
            .body("blobs.find { it.id == '$ofDeleted' }.purgeAfter", notNullValue())
            .body("blobs.find { it.id == '$orphan' }.purgeAfter", notNullValue())
            .body("blobs.find { it.id == '$ofRestored' }.purgeAfter", nullValue())
        PotWire.list(userId, scopeId, pot).statusCode(200)
            .body("revisions.find { it.entryId == '$deleted' && it.revision == 2 }.purgeAfter", notNullValue())
            .body("revisions.find { it.entryId == '$kept' && it.revision == 2 }.purgeAfter", nullValue())

        val doomed = listOf(dropped, ofDeleted, orphan)
        val keys = doomed.associateWith { storageKeyOf(blobOf(it)) }
        assertEquals(0, purge.purgeBefore(Instant.now().minus(retention), onlyScope = scopeId), "not before the retention")
        assertTrue(doomed.all { receipt(it) != null })

        assertEquals(3, purge.purgeBefore(Instant.now().plusSeconds(1), onlyScope = scopeId), "after it: the dropped, the deleted entry's and the orphan")
        for (id in doomed) {
            assertNull(receipt(id), "$id is gone")
            assertEquals(1L, reclaims(keys.getValue(id)), "its objects are on the reclaim list")
        }
        assertEquals(0L, QuarkusTransaction.requiringNew().call { me.raddatz.veyq.core.blob.BlobEntity.count("storageKey in ?1", keys.values.toList()) })
        for (id in listOf(held, ofRestored)) {
            assertNotNull(receipt(id), "$id stays")
            assertNotNull(blob(blobOf(id)))
            assertEquals(0L, reclaims(storageKeyOf(blobOf(id))))
        }
        list(userId, scopeId, pot).statusCode(200).body("blobs.size()", equalTo(2))
    }

    /** A Pot purged out of the trash takes its receipts, their blob rows and their guest-write records (control: another Pot's stay). */
    @Test
    fun aPurgedPotTakesItsReceiptsAndTheirObjects() {
        val (userId, scopeId) = seedMemberScope()
        val pot = seedNode(scopeId, ExpensesNodeKinds.POT, wrappedContentKey = CONTENT_KEY)
        val other = seedNode(scopeId, ExpensesNodeKinds.POT, wrappedContentKey = CONTENT_KEY)
        val link = GuestTreeShare.seed(userId, scopeId, pot, ShareEntity.READ_WRITE)
        val mine = ReceiptKit.upload(fakeStore, userId, scopeId, pot, UUID.randomUUID().toString())
        val theirs = UUID.randomUUID().toString()
        guestComplete(link, pot, UUID.randomUUID().toString(), theirs, guestStartStaged(fakeStore, link, pot), xff = "203.0.113.91").statusCode(200)
        val stays = ReceiptKit.upload(fakeStore, userId, scopeId, other, UUID.randomUUID().toString())
        val keys = listOf(mine, theirs).map { storageKeyOf(blobOf(it)) }
        given().header("Authorization", bearer(userId)).`when`().delete("/api/scopes/$scopeId/nodes/$pot").then().statusCode(204)
        QuarkusTransaction.requiringNew().run { subtreePurge.eraseExpiredIn(scopeId, Instant.now().plusSeconds(1)) }
        assertNull(receipt(mine))
        assertNull(receipt(theirs))
        keys.forEach { assertEquals(1L, reclaims(it)) }
        assertEquals(0L, QuarkusTransaction.requiringNew().call { GuestWriteRecordEntity.count("clientIp", "203.0.113.91") })
        assertNotNull(receipt(stays), "control: the live Pot keeps its receipt")
    }

    @Test
    fun aGuestsReceiptCountsAgainstTheLinksLimitAndIsRecordedAsAGuestWrite() {
        val (userId, scopeId, tenantId) = seedMemberScope()
        val pot = seedNode(scopeId, ExpensesNodeKinds.POT, wrappedContentKey = CONTENT_KEY)
        val link = GuestTreeShare.seed(userId, scopeId, pot, ShareEntity.READ_WRITE)
        val identity = QuarkusTransaction.requiringNew().call { ShareIdentityEntity.find("shareId", link.shareId).firstResult()!!.id }
        ReceiptKit.upload(fakeStore, userId, scopeId, pot, UUID.randomUUID().toString(), TestBlob.ofChunks(2)) // a member's: not the link's
        val heldBlob = TestBlob.ofChunks(1)
        val receiptId = UUID.randomUUID().toString()
        guestComplete(link, pot, UUID.randomUUID().toString(), receiptId, guestStartStaged(fakeStore, link, pot, heldBlob), xff = "203.0.113.81:41000")
            .statusCode(200).body("id", equalTo(receiptId)).body("containsKey('writer')", equalTo(false))
        val records = QuarkusTransaction.requiringNew().call { GuestWriteRecordEntity.list("clientIp", "203.0.113.81") }
        assertEquals(listOf(Triple("ITEM", receiptId, identity)), records.map { Triple(it.objectKind, it.objectId, it.shareIdentityId) })
        assertEquals(heldBlob.byteSize, QuarkusTransaction.requiringNew().call { quota.linkUsage(link.shareId, ExpensesNodeKinds.POT) })

        // One byte short of held + next: refused whole; exactly: admitted.
        val next = TestBlob.SMALL
        setLimit(tenantId, heldBlob.byteSize + next.byteSize - 1)
        guestStart(link, pot, next).statusCode(200).body("results[0].error", equalTo("quota_exceeded"))
        setLimit(tenantId, heldBlob.byteSize + next.byteSize)
        guestStart(link, pot, next).statusCode(200).body("results[0].error", nullValue())

        // The guest's read names no writer; the member's names the link identity.
        guestList(link.shareId, pot).statusCode(200).body("blobs.size()", equalTo(2)).body("blobs[0].containsKey('writer')", equalTo(false))
        list(userId, scopeId, pot).statusCode(200).body("blobs.find { it.id == '$receiptId' }.writer", equalTo("share:$identity"))
        // A READ link completes nothing.
        val read = GuestTreeShare.seed(userId, scopeId, pot, ShareEntity.READ)
        guestComplete(read, pot, UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString())
            .statusCode(403).body("code", equalTo("share_not_contributable"))
    }

    /**
     * ADR 0108 decision 7: a moderator's content block of a receipt, or of the entry that holds it, answers
     * `blocked_by_operator` for its bytes — a member's presign per item, a guest's GET `451` — and the reads mark it;
     * control: an unblocked receipt of the same Pot presigns for both.
     */
    @Test
    fun aBlockedReceiptOrEntryIs451ForAMemberAndAGuest_unblockedItPresigns() {
        val (userId, scopeId) = seedMemberScope()
        val pot = seedNode(scopeId, ExpensesNodeKinds.POT, wrappedContentKey = CONTENT_KEY)
        val link = GuestTreeShare.seed(userId, scopeId, pot, ShareEntity.READ)
        val entry = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        val loose = UUID.randomUUID().toString()
        append(userId, scopeId, pot, listOf(Rev(entry), Rev(other), Rev(loose))).statusCode(204)
        val byItself = ReceiptKit.upload(fakeStore, userId, scopeId, pot, entry)
        val byEntry = ReceiptKit.upload(fakeStore, userId, scopeId, pot, other)
        val free = ReceiptKit.upload(fakeStore, userId, scopeId, pot, loose)
        for (r in listOf(byItself, byEntry, free)) {
            download(userId, scopeId, blobOf(r)).statusCode(200).body("results[0].error", nullValue()).body("results[0].presignedGets", notNullValue())
            guestDownload(link.shareId, blobOf(r)).statusCode(200)
        }
        val tag = list(userId, scopeId, pot).statusCode(200).extract().header("ETag")

        ReceiptKit.block(scopeId, byItself)
        ReceiptKit.block(scopeId, other)
        for (r in listOf(byItself, byEntry)) {
            download(userId, scopeId, blobOf(r)).statusCode(200).body("results[0].error", equalTo("blocked_by_operator"))
            guestDownload(link.shareId, blobOf(r)).statusCode(451).body("code", equalTo("blocked_by_operator"))
        }
        download(userId, scopeId, blobOf(free)).statusCode(200).body("results[0].presignedGets", notNullValue()) // control
        guestDownload(link.shareId, blobOf(free)).statusCode(200)
        list(userId, scopeId, pot, ifNoneMatch = tag).statusCode(200)
            .body("blobs.find { it.id == '$byItself' }.blockedByOperator", equalTo(true))
            .body("blobs.find { it.id == '$byEntry' }.blockedByOperator", equalTo(true))
            .body("blobs.find { it.id == '$free' }.blockedByOperator", equalTo(false))
        guestList(link.shareId, pot).statusCode(200).body("blobs.find { it.id == '$byEntry' }.blockedByOperator", equalTo(true))
        // A receipt outside the link's subtree is no blob of the link.
        val elsewhere = seedNode(scopeId, ExpensesNodeKinds.POT, wrappedContentKey = CONTENT_KEY)
        val outside = ReceiptKit.upload(fakeStore, userId, scopeId, elsewhere, UUID.randomUUID().toString())
        guestDownload(link.shareId, blobOf(outside)).statusCode(404)
    }

    @Test
    fun theReadsPageByIdAndTagWhatTheyServe() {
        val (userId, scopeId) = seedMemberScope()
        val pot = seedNode(scopeId, ExpensesNodeKinds.POT, wrappedContentKey = CONTENT_KEY)
        val link = GuestTreeShare.seed(userId, scopeId, pot, ShareEntity.READ)
        val ids = List(3) { ReceiptKit.upload(fakeStore, userId, scopeId, pot, UUID.randomUUID().toString()) }.sorted()
        val first = list(userId, scopeId, pot, "?limit=2").statusCode(200)
            .body("blobs.id", equalTo(ids.take(2))).body("more", equalTo(true)).body("cursor", equalTo(ids[1])).extract()
        list(userId, scopeId, pot, "?limit=2&after=${ids[1]}").statusCode(200)
            .body("blobs.id", equalTo(listOf(ids[2]))).body("more", equalTo(false)).header("ETag", nullValue())
        val tag = first.header("ETag")
        list(userId, scopeId, pot, ifNoneMatch = tag).statusCode(304)
        val guestTag = guestList(link.shareId, pot).statusCode(200).extract().header("ETag")
        assertNotEquals(tag, guestTag, "the guest's digest is over its own shape")
        guestList(link.shareId, pot, ifNoneMatch = guestTag).statusCode(304)
        // A drop changes what is served: a new tag.
        val entry = QuarkusTransaction.requiringNew().call { ApplicationBlobEntity.findById(ids[0])!!.ownerId }
        append(userId, scopeId, pot, listOf(Rev(entry), )).statusCode(204)
        append(userId, scopeId, pot, listOf(Rev(entry, 2, drops = listOf(ids[0])))).statusCode(204)
        list(userId, scopeId, pot, ifNoneMatch = tag).statusCode(200)
        // A Pot outside the link's subtree answers as an unknown link; no Pot, no scope.
        val elsewhere = seedNode(scopeId, ExpensesNodeKinds.POT, wrappedContentKey = CONTENT_KEY)
        guestList(link.shareId, elsewhere).statusCode(404).body("code", equalTo("share_not_found"))
        list(userId, scopeId, seedNode(scopeId, "FOLDER", wrappedContentKey = CONTENT_KEY)).statusCode(404).body("code", equalTo("node_not_found"))
        val (stranger, _) = seedMemberScope()
        list(stranger, scopeId, pot).statusCode(404).body("code", equalTo("scope_not_found"))
    }

    /**
     * ADR 0112 decision 2: an entry holds at most 100 receipts not released (Expenses' count per owner, its sealed list's
     * bound) — the 101st is `409 too_many_blobs` and its upload stays open; a released one no longer counts, so after a
     * drop the same upload lands. Another entry is not held to the first one's count.
     */
    @Test
    fun anEntryHoldsAHundredReceipts_aDropMakesRoomForTheNext() {
        val (userId, scopeId) = seedMemberScope()
        val pot = seedNode(scopeId, ExpensesNodeKinds.POT, wrappedContentKey = CONTENT_KEY)
        val entry = UUID.randomUUID().toString()
        append(userId, scopeId, pot, listOf(Rev(entry))).statusCode(204)
        val held = List(ExpensesBlobRoles.RECEIPTS_PER_ENTRY) { ReceiptKit.seed(scopeId, pot, entry, userId).first }
        val upload = startStaged(fakeStore, userId, scopeId, pot)
        complete(userId, scopeId, pot, entry, UUID.randomUUID().toString(), upload).statusCode(409).body("code", equalTo("too_many_blobs"))
        assertEquals(1L, QuarkusTransaction.requiringNew().call { UploadSessionEntity.count("id", upload) }, "the upload stays open")
        // Control: another entry's receipt lands beside the full one.
        ReceiptKit.upload(fakeStore, userId, scopeId, pot, UUID.randomUUID().toString())
        append(userId, scopeId, pot, listOf(Rev(entry, 2, drops = listOf(held.first())))).statusCode(204)
        complete(userId, scopeId, pot, entry, UUID.randomUUID().toString(), upload).statusCode(200)
    }

    @Test
    fun offExpensesTakesNoReceipt_theReadsStillServe() {
        val (userId, scopeId, tenantId) = seedMemberScope()
        val pot = seedNode(scopeId, ExpensesNodeKinds.POT, wrappedContentKey = CONTENT_KEY)
        val link = GuestTreeShare.seed(userId, scopeId, pot, ShareEntity.READ_WRITE)
        val upload = startStaged(fakeStore, userId, scopeId, pot)
        val guestUpload = guestStartStaged(fakeStore, link, pot)
        TenantCapabilitySwitchFixtures.with(tenantId, CapabilityIds.EXPENSES) {
            complete(userId, scopeId, pot, UUID.randomUUID().toString(), UUID.randomUUID().toString(), upload)
                .statusCode(422).body("code", equalTo("capability_disabled"))
            guestComplete(link, pot, UUID.randomUUID().toString(), UUID.randomUUID().toString(), guestUpload)
                .statusCode(422).body("code", equalTo("capability_disabled"))
            start(userId, scopeId, pot).statusCode(422).body("code", equalTo("capability_disabled"))
            list(userId, scopeId, pot).statusCode(200)
        }
        assertFalse(QuarkusTransaction.requiringNew().call { ApplicationBlobEntity.count("nodeId", pot) > 0 })
        complete(userId, scopeId, pot, UUID.randomUUID().toString(), UUID.randomUUID().toString(), upload).statusCode(200) // control
    }

    // --- committed state -----------------------------------------------------------------------------------------

    private fun storageKeyOf(blobId: String): String = checkNotNull(blob(blobId)).storageKey

    private fun reclaims(storageKey: String): Long = QuarkusTransaction.requiringNew().call { StoreReclaimEntity.count("storageKey", storageKey) }

    private fun setLimit(tenantId: String, bytes: Long) = QuarkusTransaction.requiringNew().run { TenantEntity.findById(tenantId)!!.shareLinkLimitBytes = bytes }
}
