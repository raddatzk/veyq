package me.raddatz.veyq.memories.media

import io.quarkus.test.junit.QuarkusTest
import java.sql.DriverManager
import java.util.UUID
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * ADR 0113 decision 9: `V202611020620__memories_media_move_to_core.sql` drops `veyq_media.blob_id` and
 * `veyq_media_derivative` and deletes no row — it **refuses to run while a medium, a derivative or a hold's copy of
 * either is left**, so a dev instance not reset first fails its start naming the constraint, instead of leaving the
 * media's blob rows with no arm and their objects in the store. Each refusal on a private in-memory H2 brought to the
 * version before it, the other two tables empty, and each followed by its control: the row deleted, Flyway's `repair`
 * clearing the failed run (as a dev start does, `quarkus.flyway.repair-at-start`), the same migration runs. A refused run
 * leaves no constraint behind (MariaDB's DDL is not transactional, so each check is added and dropped before the next),
 * which the control's success after a refusal at a later check proves. The CHECK mechanism itself on the test engine is
 * `ReceiptsResetMigrationSliceTest`'s.
 */
@QuarkusTest
class MediaResetMigrationSliceTest {

    @Test
    fun theMoveRefusesWhileAMediumIsLeft_andRunsOnceItIsGone() =
        refusesThenRuns("ck_media_reset_first", "veyq_media") { id ->
            "INSERT INTO veyq_media (id, scope_id, node_id, blob_id, updated_at) VALUES " +
                "('$id', '${UUID.randomUUID()}', '${UUID.randomUUID()}', '${UUID.randomUUID()}', CURRENT_TIMESTAMP)"
        }

    @Test
    fun theMoveRefusesWhileADerivativeIsLeft_andRunsOnceItIsGone() =
        refusesThenRuns("ck_media_derivative_reset_first", "veyq_media_derivative") { id ->
            "INSERT INTO veyq_media_derivative (id, media_id, kind, blob_id) VALUES " +
                "('$id', '${UUID.randomUUID()}', 'THUMBNAIL', '${UUID.randomUUID()}')"
        }

    @Test
    fun theMoveRefusesWhileAHoldCopiesAMedium_andRunsOnceTheCopyIsGone() =
        refusesThenRuns("ck_hold_row_media_reset_first", "veyq_hold_row") { id ->
            "INSERT INTO veyq_hold_row (id, hold_id, source_table, source_id, payload) VALUES " +
                "('$id', '${UUID.randomUUID()}', 'veyq_media', '${UUID.randomUUID()}', '{}')"
        }

    private fun refusesThenRuns(constraint: String, table: String, insert: (String) -> String) {
        val url = "jdbc:h2:mem:media-reset-${UUID.randomUUID()};MODE=MariaDB;DB_CLOSE_DELAY=-1;TIME ZONE=UTC"
        fun flyway(target: String? = null) = Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration")
            .let { if (target != null) it.target(target) else it }.load()
        flyway(BEFORE).migrate()
        val id = UUID.randomUUID().toString()
        sql(url, "SET REFERENTIAL_INTEGRITY FALSE", insert(id)) // a row as the old code left it, its FKs aside
        val refused = assertThrows(FlywayException::class.java) { flyway().migrate() }
        assertTrue(
            generateSequence<Throwable>(refused) { it.cause }.any { it.message.orEmpty().contains(constraint, ignoreCase = true) },
            "the refusal names $constraint: $refused",
        )
        assertEquals(1, count(url, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME) = 'veyq_media_derivative'"), "a refused move drops nothing")
        assertEquals(1, blobIdColumns(url), "a refused move keeps veyq_media.blob_id")

        // The documented reset leaves the row gone: the same migration then runs, once the failed run is repaired away.
        sql(url, "DELETE FROM $table WHERE id = '$id'")
        flyway().repair()
        flyway().migrate()
        assertEquals(0, count(url, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME) = 'veyq_media_derivative'"), "the derivatives' table is dropped")
        assertEquals(0, blobIdColumns(url), "veyq_media.blob_id is dropped")
    }

    private fun blobIdColumns(url: String): Int = count(
        url,
        "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE LOWER(TABLE_NAME) = 'veyq_media' AND LOWER(COLUMN_NAME) = 'blob_id'",
    )

    private fun sql(url: String, vararg statements: String) = DriverManager.getConnection(url, "sa", "").use { c ->
        c.createStatement().use { s -> statements.forEach { s.execute(it) } }
    }

    private fun count(url: String, query: String): Int = DriverManager.getConnection(url, "sa", "").use { c ->
        c.createStatement().use { s -> s.executeQuery(query).use { r -> r.next(); r.getInt(1) } }
    }

    private companion object {
        /** The version below the move on this classpath (core and Memories): core's blob service's table. */
        const val BEFORE = "202611012215"
    }
}
