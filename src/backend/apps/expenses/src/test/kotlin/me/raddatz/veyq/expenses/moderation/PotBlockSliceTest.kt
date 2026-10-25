package me.raddatz.veyq.expenses.moderation

import me.raddatz.veyq.core.blobstore.FakePresigningBlobStore
import me.raddatz.veyq.core.moderation.block.BlockKind
import me.raddatz.veyq.core.moderation.block.ModerationBlockEntity
import me.raddatz.veyq.core.sharing.GuestTreeShare
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.testing.FakePresignProfile
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.expenses.ExpensesNodeKinds
import me.raddatz.veyq.expenses.pot.PotRevisionEntity
import me.raddatz.veyq.expenses.pot.PotWire.Rev
import me.raddatz.veyq.expenses.pot.PotWire.append
import me.raddatz.veyq.expenses.pot.PotWire.guestAppend
import me.raddatz.veyq.expenses.receipt.ReceiptKit.complete
import me.raddatz.veyq.expenses.receipt.ReceiptKit.guestComplete
import me.raddatz.veyq.expenses.receipt.ReceiptKit.guestStartStaged
import me.raddatz.veyq.expenses.receipt.ReceiptKit.startStaged
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * What a moderator's content block refuses of a Pot's writers (inbox #542, ADR 0108 decision 6 after ADR 0093 § 6:
 * blocked content is deleted, never replaced or restored), a link guest's write and a member's alike, each `451
 * blocked_by_operator` beside its control: a block of the Pot refuses every revision but a tombstone and every receipt;
 * a block of one entry refuses that entry's edits and receipts and nothing of its neighbours; a lifted block refuses
 * nothing. Nothing of a refused batch is stored.
 */
@QuarkusTest
@TestProfile(FakePresignProfile::class)
class PotBlockSliceTest {

    @Inject lateinit var fakeStore: FakePresigningBlobStore

    @Test
    fun aBlockedPotTakesNoRevisionButATombstoneFromAGuestOrAMember_liftedItTakesAll() {
        val (userId, scopeId) = seedMemberScope()
        val pot = seedNode(scopeId, ExpensesNodeKinds.POT, wrappedContentKey = CONTENT_KEY)
        val link = GuestTreeShare.seed(userId, scopeId, pot, ShareEntity.READ_WRITE)
        val dinner = UUID.randomUUID().toString()
        val taxi = UUID.randomUUID().toString()
        append(userId, scopeId, pot, listOf(Rev(dinner), Rev(taxi))).statusCode(204)

        val block = block(BlockKind.CONTENT_NODE, scopeId, pot)
        guestAppend(link, pot, listOf(Rev(dinner, 2))).statusCode(451).body("code", equalTo("blocked_by_operator"))
        append(userId, scopeId, pot, listOf(Rev(dinner, 2))).statusCode(451).body("code", equalTo("blocked_by_operator"))
        guestAppend(link, pot, listOf(Rev())).statusCode(451) // a new entry is new content too
        // a batch with one edit among its tombstones is refused whole
        append(userId, scopeId, pot, listOf(Rev(dinner, 2, tombstone = true), Rev(taxi, 2))).statusCode(451)
        assertEquals(listOf(1L, 1L), revisionsOf(pot), "nothing of a refused batch is stored")
        // Deleting is no replacing: a tombstone lands, a guest's as a member's.
        guestAppend(link, pot, listOf(Rev(dinner, 2, tombstone = true))).statusCode(204)
        append(userId, scopeId, pot, listOf(Rev(taxi, 2, tombstone = true))).statusCode(204)
        // and the restore after it is refused (a restore is a later live revision)
        append(userId, scopeId, pot, listOf(Rev(dinner, 3))).statusCode(451)

        lift(block)
        append(userId, scopeId, pot, listOf(Rev(dinner, 3))).statusCode(204) // control: lifted, a member's restore lands
        guestAppend(link, pot, listOf(Rev(taxi, 3))).statusCode(204) // and a guest's
    }

    @Test
    fun aBlockedEntryTakesNoEditButItsTombstone_itsNeighboursTakeAll() {
        val (userId, scopeId) = seedMemberScope()
        val pot = seedNode(scopeId, ExpensesNodeKinds.POT, wrappedContentKey = CONTENT_KEY)
        val link = GuestTreeShare.seed(userId, scopeId, pot, ShareEntity.READ_WRITE)
        val dinner = UUID.randomUUID().toString()
        val taxi = UUID.randomUUID().toString()
        append(userId, scopeId, pot, listOf(Rev(dinner), Rev(taxi))).statusCode(204)

        block(BlockKind.CONTENT_ITEM, scopeId, dinner)
        guestAppend(link, pot, listOf(Rev(dinner, 2))).statusCode(451).body("code", equalTo("blocked_by_operator"))
        append(userId, scopeId, pot, listOf(Rev(dinner, 2))).statusCode(451)
        guestAppend(link, pot, listOf(Rev(taxi, 2))).statusCode(204) // control: its neighbour is not blocked
        append(userId, scopeId, pot, listOf(Rev(dinner, 2, tombstone = true))).statusCode(204) // its deletion is
    }

    @Test
    fun aBlockedPotOrEntryAttachesNoReceiptForAGuestOrAMember_anotherEntryDoes() {
        val (userId, scopeId) = seedMemberScope()
        val pot = seedNode(scopeId, ExpensesNodeKinds.POT, wrappedContentKey = CONTENT_KEY)
        val link = GuestTreeShare.seed(userId, scopeId, pot, ShareEntity.READ_WRITE)
        val dinner = UUID.randomUUID().toString()
        val taxi = UUID.randomUUID().toString()
        append(userId, scopeId, pot, listOf(Rev(dinner), Rev(taxi))).statusCode(204)

        block(BlockKind.CONTENT_ITEM, scopeId, dinner)
        val guestUpload = guestStartStaged(fakeStore, link, pot)
        guestComplete(link, pot, dinner, UUID.randomUUID().toString(), guestUpload).statusCode(451).body("code", equalTo("blocked_by_operator"))
        val memberUpload = startStaged(fakeStore, userId, scopeId, pot)
        complete(userId, scopeId, pot, dinner, UUID.randomUUID().toString(), memberUpload).statusCode(451)
        // control: the upload stays open, and attached to an entry no block reaches it lands
        guestComplete(link, pot, taxi, UUID.randomUUID().toString(), guestUpload).statusCode(200)
        complete(userId, scopeId, pot, taxi, UUID.randomUUID().toString(), memberUpload).statusCode(200)

        block(BlockKind.CONTENT_NODE, scopeId, pot)
        guestComplete(link, pot, taxi, UUID.randomUUID().toString(), guestStartStaged(fakeStore, link, pot)).statusCode(451)
        complete(userId, scopeId, pot, taxi, UUID.randomUUID().toString(), startStaged(fakeStore, userId, scopeId, pot)).statusCode(451)
    }

    /** A moderator's content block, as `takeMeasure` leaves its row; returns its id. */
    private fun block(kind: BlockKind, scopeId: String, targetId: String): String = QuarkusTransaction.requiringNew().call {
        ModerationBlockEntity().apply {
            id = UUID.randomUUID().toString(); this.kind = kind.name; this.targetId = targetId; this.scopeId = scopeId
            takenBy = UUID.randomUUID().toString(); takenAt = Instant.now()
            activeKey = ModerationBlockEntity.activeKeyOf(kind, targetId)
        }.also { it.persist() }.id
    }

    /** The block lifted: the row stays, no longer active. */
    private fun lift(blockId: String) = QuarkusTransaction.requiringNew().run {
        ModerationBlockEntity.findById(blockId)!!.activeKey = null
    }

    private fun revisionsOf(pot: String): List<Long> = QuarkusTransaction.requiringNew().call {
        PotRevisionEntity.list("nodeId", pot).map { it.revision }.sorted()
    }
}
