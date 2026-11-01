package me.raddatz.veyq.expenses.receipt

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * ADR 0112 decision 5: `V202611012216__expenses_receipts_move_to_core.sql` drops `veyq_expenses_receipt` and deletes no
 * row — it **refuses to run while the table holds one**, so a dev instance not reset first fails its start, naming
 * `ck_expenses_receipt_reset_first`, instead of leaving the receipts' blob rows with no arm and their objects in the
 * store. Two halves:
 *
 * - the migration itself, through Flyway, on a private in-memory H2 brought to the version before it: with a receipt row
 *   it is refused and the table stays; emptied, the same migration runs and the table is gone — after Flyway's `repair`
 *   clears the failed run from the schema history, as a dev instance's start does (`application-dev.properties`:
 *   `quarkus.flyway.repair-at-start`);
 * - the mechanism it rests on — a CHECK no row can meet, added to a table that holds a row, is refused — on the engine
 *   the test datasource runs (H2 here; MariaDB under `scripts/run_backend_races_on_mariadb.py`, which points the
 *   datasource at its throwaway container), since MariaDB's DDL is not H2's.
 */
@QuarkusTest
class ReceiptsResetMigrationSliceTest {

    @Inject lateinit var dataSource: DataSource

    @Test
    fun theMoveRefusesWhileAReceiptRowStands_andRunsOnceTheTableIsEmpty() {
        val url = "jdbc:h2:mem:receipts-reset-${UUID.randomUUID()};MODE=MariaDB;DB_CLOSE_DELAY=-1;TIME ZONE=UTC"
        fun flyway(target: String? = null) = Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration")
            .let { if (target != null) it.target(target) else it }.load()
        flyway(BEFORE).migrate()
        DriverManager.getConnection(url, "sa", "").use { c ->
            c.createStatement().use { s ->
                s.execute("SET REFERENTIAL_INTEGRITY FALSE") // a receipt row as the old completion left it, its FKs aside
                s.execute(
                    "INSERT INTO veyq_expenses_receipt (id, scope_id, node_id, entry_id, blob_id, writer_user_id, created_at) VALUES " +
                        "('${UUID.randomUUID()}', '${UUID.randomUUID()}', '${UUID.randomUUID()}', '${UUID.randomUUID()}', " +
                        "'${UUID.randomUUID()}', '${UUID.randomUUID()}', CURRENT_TIMESTAMP)",
                )
            }
        }
        val refused = assertThrows(FlywayException::class.java) { flyway().migrate() }
        assertTrue(generateSequence<Throwable>(refused) { it.cause }.any { it.message.orEmpty().contains(CONSTRAINT, ignoreCase = true) }, "the refusal names $CONSTRAINT: $refused")
        assertTrue(tableExists(url), "a refused move drops nothing")

        // The documented reset leaves the table empty: the same migration then runs, once the failed run is repaired away.
        DriverManager.getConnection(url, "sa", "").use { c -> c.createStatement().use { it.execute("DELETE FROM veyq_expenses_receipt") } }
        flyway().repair()
        flyway().migrate()
        assertFalse(tableExists(url), "the emptied table is dropped")
    }

    @Test
    fun aCheckNoRowMeetsIsRefusedOnATableThatHoldsARow_onTheTestEngine() {
        val table = "veyq_reset_probe_" + UUID.randomUUID().toString().replace("-", "").take(12)
        dataSource.connection.use { c ->
            c.autoCommit = true
            c.createStatement().use { s ->
                s.execute("CREATE TABLE $table (id UUID NOT NULL)")
                try {
                    s.execute("INSERT INTO $table (id) VALUES ('${UUID.randomUUID()}')")
                    assertThrows(SQLException::class.java) { s.execute("ALTER TABLE $table ADD CONSTRAINT ck_${table}_x CHECK (id IS NULL)") }
                    assertEquals(1, s.executeQuery("SELECT COUNT(*) FROM $table").use { r -> r.next(); r.getInt(1) }, "the refused ALTER changed nothing")
                    s.execute("DELETE FROM $table")
                    s.execute("ALTER TABLE $table ADD CONSTRAINT ck_${table}_y CHECK (id IS NULL)") // control: empty, it is added
                } finally {
                    s.execute("DROP TABLE $table")
                }
            }
        }
    }

    private fun tableExists(url: String): Boolean = DriverManager.getConnection(url, "sa", "").use { c ->
        c.createStatement().use { s ->
            s.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME) = 'veyq_expenses_receipt'").use { r ->
                r.next()
                r.getInt(1) == 1
            }
        }
    }

    private companion object {
        /** The version below the move: the core blob service's table. */
        const val BEFORE = "202611012215"
        const val CONSTRAINT = "ck_expenses_receipt_reset_first"
    }
}
