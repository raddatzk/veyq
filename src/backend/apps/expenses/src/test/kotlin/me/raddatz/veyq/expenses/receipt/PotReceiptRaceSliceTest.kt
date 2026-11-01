package me.raddatz.veyq.expenses.receipt

import me.raddatz.veyq.core.applicationblob.ApplicationBlobEntity
import me.raddatz.veyq.core.applicationblob.ApplicationBlobPurge
import me.raddatz.veyq.core.identity.PrincipalKey
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.raceRounds
import me.raddatz.veyq.core.testing.whileUncommitted
import me.raddatz.veyq.expenses.ExpensesNodeKinds
import me.raddatz.veyq.expenses.pot.PotRevisionStore
import me.raddatz.veyq.expenses.pot.PotWire
import me.raddatz.veyq.expenses.pot.PotWire.Rev
import me.raddatz.veyq.expenses.pot.PotWire.append
import me.raddatz.veyq.expenses.pot.PotWriteResult
import me.raddatz.veyq.expenses.pot.RevisionWrite
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Core's blob service's purge against the writes it races ([ApplicationBlobPurge]'s lock order: the scope row, the
 * service's rows, the nodes' rows — the order every writer of a node's blobs takes, ADR 0112). A restore of a deleted
 * entry held uncommitted ([whileUncommitted]) while the purge runs: the restore keeps its entry's receipts under the
 * service's row locks, the purge waits on them, then judges what is committed — the receipts are kept again, they stay
 * (docs/apps/expenses.md § Receipts: a restore keeps what the tombstone released). Beside its control: without the
 * restore, the same purge takes them. Run on MariaDB by `scripts/run_backend_races_on_mariadb.py` (the V1 heavy stage),
 * where READ COMMITTED shows what H2 hides.
 */
@QuarkusTest
class PotReceiptRaceSliceTest {

    @Inject lateinit var revisions: PotRevisionStore
    @Inject lateinit var purge: ApplicationBlobPurge

    @Test
    fun aRestoreThePurgeWaitsForKeepsTheEntrysReceipts() {
        raceRounds { round ->
            val f = deletedEntry()
            val purged = whileUncommitted(
                change = { check(revisions.append(f.scopeId, f.pot, 0, listOf(write(f.entry, 3)), f.writer) is PotWriteResult.Stored) },
                overlapping = { purge.purgeBefore(Instant.now().plusSeconds(1), onlyScope = f.scopeId) },
            )
            assertEquals(0, purged, "round $round: the restored entry's receipt is not due")
            assertNotNull(receipt(f.receipt), "round $round")
        }
        val f = deletedEntry() // control: no restore, the same purge takes it
        assertEquals(1, purge.purgeBefore(Instant.now().plusSeconds(1), onlyScope = f.scopeId))
        assertNull(receipt(f.receipt))
    }

    private data class Fixture(val scopeId: String, val pot: String, val entry: String, val receipt: String, val userId: String) {
        val writer = PrincipalKey.User(userId)
    }

    /** A Pot whose entry is deleted (revision 2, a tombstone) and holds one receipt. */
    private fun deletedEntry(): Fixture {
        val f = heldReceipt()
        append(f.userId, f.scopeId, f.pot, listOf(Rev(f.entry, 2, tombstone = true))).statusCode(204)
        return f
    }

    /** A Pot whose live entry holds one receipt (seeded as its completion leaves it: born kept). */
    private fun heldReceipt(): Fixture {
        val (userId, scopeId) = seedMemberScope()
        val pot = seedNode(scopeId, ExpensesNodeKinds.POT, wrappedContentKey = CONTENT_KEY)
        val entry = UUID.randomUUID().toString()
        append(userId, scopeId, pot, listOf(Rev(entry))).statusCode(204)
        val (receipt, _) = ReceiptKit.seed(scopeId, pot, entry, userId)
        return Fixture(scopeId, pot, entry, receipt, userId)
    }

    private fun write(entryId: String, revision: Long, drops: List<String> = emptyList()) =
        RevisionWrite(entryId, revision, tombstone = false, sealedBody = PotWire.body(), wrappedRevisionKey = PotWire.WRAP, droppedReceipts = drops)

    private fun receipt(id: String): ApplicationBlobEntity? = QuarkusTransaction.requiringNew().call { ApplicationBlobEntity.findById(id) }
}
