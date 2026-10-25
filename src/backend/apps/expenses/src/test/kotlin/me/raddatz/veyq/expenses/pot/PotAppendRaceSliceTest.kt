package me.raddatz.veyq.expenses.pot

import me.raddatz.veyq.core.identity.PrincipalKey
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.answer
import me.raddatz.veyq.core.testing.raceRounds
import me.raddatz.veyq.core.testing.whileUncommitted
import me.raddatz.veyq.expenses.ExpensesNodeKinds
import me.raddatz.veyq.expenses.pot.PotWire.Rev
import me.raddatz.veyq.expenses.pot.PotWire.append
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Two appends to one Pot at once (CR decision 1, `PotRevisionStore`'s lock order: the records' rows sorted, then the
 * node row, then the item-revision row). The first holds its batch uncommitted ([whileUncommitted]); the second, over
 * the same records, must answer what a replay of it would get — `409 stale_revision` naming each record's latest — and
 * never the `500` of a deadlock or of the unique index the compare-and-set backs (developer-styleguide § 12, the insert
 * half). Each beside its control: the same append with nothing held lands. Run on MariaDB by
 * `scripts/run_backend_races_on_mariadb.py` (the V1 heavy stage), where READ COMMITTED shows what H2 hides.
 */
@QuarkusTest
class PotAppendRaceSliceTest {

    @Inject lateinit var store: PotRevisionStore

    /** Existing records, named in the opposite order: the record rows' one sorted order makes the second wait, then stale. */
    @Test
    fun aBatchOverRecordsAnotherHoldsWaitsAndIsStale_inEitherOrderOfItsRecords() {
        raceRounds { round ->
            val f = fixture()
            val a = UUID.randomUUID().toString()
            val b = UUID.randomUUID().toString()
            append(f.userId, f.scopeId, f.pot, listOf(Rev(a), Rev(b))).statusCode(204)
            val answer = whileUncommitted(
                change = { check(store.append(f.scopeId, f.pot, 0, listOf(write(a, 2), write(b, 2)), f.writer) is PotWriteResult.Stored) },
                overlapping = { append(f.userId, f.scopeId, f.pot, listOf(Rev(b, 2), Rev(a, 2))).extract().answer() },
            )
            assertEquals(409 to "stale_revision", answer, "round $round")
            assertEquals(listOf("$a:1", "$a:2", "$b:1", "$b:2").sorted(), addresses(f.pot), "round $round: the held batch only")
        }
        val f = fixture()
        append(f.userId, f.scopeId, f.pot, listOf(Rev(UUID.randomUUID().toString()))).statusCode(204) // control
    }

    /**
     * A new record twice — a client that sends its create again: no row exists to lock, so the node row orders the two,
     * and the second's insert would meet the first's on `ux_expenses_revision_entry`. It answers as the replay it is.
     */
    @Test
    fun aNewRecordAppendedTwiceAtOnceLandsOnceAndTheOtherIsStale() {
        raceRounds { round ->
            val f = fixture()
            val x = UUID.randomUUID().toString()
            val answer = whileUncommitted(
                change = { check(store.append(f.scopeId, f.pot, 0, listOf(write(x, 1)), f.writer) is PotWriteResult.Stored) },
                overlapping = { append(f.userId, f.scopeId, f.pot, listOf(Rev(x, 1))).extract().answer() },
            )
            assertEquals(409 to "stale_revision", answer, "round $round")
            assertEquals(listOf("$x:1"), addresses(f.pot), "round $round")
        }
        val f = fixture()
        append(f.userId, f.scopeId, f.pot, listOf(Rev(UUID.randomUUID().toString()))).statusCode(204) // control
    }

    private data class Fixture(val userId: String, val scopeId: String, val pot: String) {
        val writer = PrincipalKey.User(userId)
    }

    private fun fixture(): Fixture {
        val (userId, scopeId) = seedMemberScope()
        return Fixture(userId, scopeId, seedNode(scopeId, ExpensesNodeKinds.POT, wrappedContentKey = CONTENT_KEY))
    }

    private fun write(entryId: String, revision: Long) =
        RevisionWrite(entryId, revision, tombstone = false, sealedBody = PotWire.body(), wrappedRevisionKey = PotWire.WRAP)

    private fun addresses(pot: String): List<String> =
        QuarkusTransaction.requiringNew().call { PotRevisionEntity.list("nodeId", pot).map { "${it.entryId}:${it.revision}" }.sorted() }
}
