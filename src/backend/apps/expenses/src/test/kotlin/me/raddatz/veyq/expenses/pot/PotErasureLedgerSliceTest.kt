package me.raddatz.veyq.expenses.pot

import me.raddatz.veyq.core.backup.ErasureOutboxEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.expenses.ExpensesNodeKinds
import me.raddatz.veyq.expenses.pot.PotWire.Rev
import me.raddatz.veyq.expenses.pot.PotWire.append
import me.raddatz.veyq.expenses.pot.PotWire.body
import me.raddatz.veyq.expenses.pot.PotWire.erase
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * A participant's erasure survives a backup restore (ADR 0056 decision 8, GDPR Art. 17): the erasure writes its ledger
 * line in its own transaction, and the line's re-application deletes the record's revisions below the erasure again —
 * the name a restore brought back — and nothing else: the erasure revision and another record stay.
 */
@QuarkusTest
class PotErasureLedgerSliceTest {

    @Inject lateinit var erasures: ExpensesErasures

    @Test
    fun anErasureWritesItsLineAndItsReapplicationErasesTheRestoredNameOnly() {
        val (userId, scopeId) = seedMemberScope()
        val pot = seedNode(scopeId, ExpensesNodeKinds.POT, wrappedContentKey = CONTENT_KEY)
        val person = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        append(userId, scopeId, pot, listOf(Rev(person, body = body('P')), Rev(other))).statusCode(204)
        erase(userId, scopeId, pot, person, 2).statusCode(204)
        val line = "$person:2"
        assertEquals(1L, QuarkusTransaction.requiringNew().call { ErasureOutboxEntity.count("kind = ?1 and subjectId = ?2", "EXPENSES_RECORD", line) })

        // A restore from before the erasure brings the first spelling of the name back.
        QuarkusTransaction.requiringNew().run {
            PotRevisionEntity().apply {
                id = UUID.randomUUID().toString(); this.scopeId = scopeId; nodeId = pot; entryId = person; revision = 1
                sealedBody = body('P'); wrappedRevisionKey = PotWire.WRAP; sizeClass = 512; writerUserId = userId; createdAt = Instant.now()
            }.persist()
        }
        assertTrue(QuarkusTransaction.requiringNew().call { erasures.reapply("EXPENSES_RECORD", line, scopeId) })
        assertEquals(listOf("$other:1", "$person:2").sorted(), addresses(pot), "the restored name is gone; the erasure and the other record stay")
        assertFalse(QuarkusTransaction.requiringNew().call { erasures.reapply("EXPENSES_RECORD", line, scopeId) }, "nothing left to erase")
    }

    private fun addresses(pot: String): List<String> =
        QuarkusTransaction.requiringNew().call { PotRevisionEntity.list("nodeId", pot).map { "${it.entryId}:${it.revision}" }.sorted() }
}
