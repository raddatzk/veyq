package me.raddatz.veyq.core.persistence

import io.agroal.api.AgroalDataSource
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import me.raddatz.veyq.core.account.login.LoginChallengeEntity
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.sharing.GuestTreeShare
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.Connection
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

/**
 * Every stored time is a `DATETIME` holding its UTC wall clock (inbox #509). MariaDB's `TIMESTAMP` ended at
 * 2038-01-19T03:14:07Z — a share's `expiresAt` in 2577 answered 500 — and converted every value by the session's
 * `time_zone`; a `DATETIME` holds 1000-01-01 to 9999-12-31 and converts nothing, so what Hibernate writes (an `Instant`
 * as its UTC wall clock, through a UTC calendar) is what a reader in any zone reads back.
 *
 * **Where it bites.** On H2 (`./gradlew check`) the round trips and the H2 session's UTC zone (the test URL's
 * `TIME ZONE=UTC`). The type and the session zone are the production engine's questions: H2 has no `TIMESTAMP` range
 * and keeps its session zone fixed, so those methods run on MariaDB only — `scripts/run_backend_races_on_mariadb.py`
 * runs this class by default. There each negative is held against a positive control on the very lever: the same writes
 * against a scratch `TIMESTAMP(3)` table fail (2577) and move (another session zone), so a green is the column type's,
 * not an inert harness.
 */
@QuarkusTest
class StoredTimeSliceTest {

    @Inject
    lateinit var dataSource: AgroalDataSource

    @Inject
    lateinit var em: EntityManager

    @Test
    fun aShareExpiringIn2577IsStoredAndReadBackAsItsUtcWallClock() {
        val space = seedMemberScope()
        val folder = seedNode(space.scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val share = GuestTreeShare.seed(space.userId, space.scopeId, folder).shareId
        given().header("Authorization", bearer(space.userId)).contentType(ContentType.JSON)
            .body("""{"expiresAt":"2577-12-11T13:05:27+01:00"}""")
            .`when`().patch("/api/shares/$share").then().statusCode(204)
        val stored = QuarkusTransaction.requiringNew().call { ShareEntity.findById(share)!!.expiresAt }
        assertEquals(Instant.parse("2577-12-11T12:05:27Z"), stored)
        assertTrue(text("veyq_share", "id", share, "expires_at").startsWith("2577-12-11 12:05:27"), "the column holds the UTC wall clock")
    }

    @Test
    fun theEndsOfTheRangeARequestMayCarryRoundTrip() {
        // The millisecond column (DATETIME(3)): the ends of StrictJsonTypes' range, exactly.
        val space = seedMemberScope()
        val node = seedNode(space.scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        for (end in listOf("1582-10-15T00:00:00Z", "9999-12-31T23:59:59.999Z", "2038-01-19T03:14:08Z")) {
            val at = Instant.parse(end)
            QuarkusTransaction.requiringNew().run { NodeEntity.findById(node)!!.createdAt = at }
            assertEquals(at, QuarkusTransaction.requiringNew().call { NodeEntity.findById(node)!!.createdAt }, end)
        }
        // The whole-second column (DATETIME): the same ends, to the second.
        for (end in listOf("1582-10-15T00:00:00Z", "9999-12-31T23:59:59Z")) {
            val nonce = challenge(Instant.parse(end))
            assertEquals(Instant.parse(end), QuarkusTransaction.requiringNew().call { LoginChallengeEntity.findById(nonce)!!.expiresAt }, end)
        }
    }

    @Test
    fun theH2SessionKeepsUtcAsMariaDbsColumnsDo() {
        assumeTrue(!onMariaDb(), "the H2 session's zone is the test URL's; MariaDB's is the server's own")
        val nonce = challenge(Instant.parse("2577-12-11T12:05:27Z"))
        assertTrue(text("veyq_login_challenge", "nonce", nonce, "expires_at").startsWith("2577-12-11 12:05:27"),
            "H2 stores the UTC wall clock — without TIME ZONE=UTC it stores the JVM zone's")
    }

    /** Flyway's own `flyway_schema_history.installed_on` is a `TIMESTAMP` of Flyway's DDL, not a veyq column: it is left
     *  to Flyway, and to the MariaDB image, whose `TIMESTAMP` reaches 2106 from 11.5 on 64-bit platforms. */
    @Test
    fun noColumnIsATimestamp() {
        assumeTrue(onMariaDb(), "H2 names DATETIME and TIMESTAMP alike; scripts/validate_time_columns.py reads the declarations")
        val types = dataSource.connection.use { c ->
            c.prepareStatement(
                "SELECT table_name, column_name, data_type FROM information_schema.columns " +
                    "WHERE table_schema = DATABASE() AND data_type IN ('timestamp', 'datetime') AND table_name <> 'flyway_schema_history'",
            ).executeQuery().use { rs -> generateSequence { if (rs.next()) "${rs.getString(1)}.${rs.getString(2)}" to rs.getString(3) else null }.toList() }
        }
        assertEquals(emptyList<String>(), types.filter { it.second.equals("timestamp", ignoreCase = true) }.map { it.first })
        assertTrue(types.size > 50, "the time columns were read (${types.size})")
    }

    @Test
    fun aTimeWrittenUnderAnotherSessionZoneReadsBackUnchanged() {
        assumeTrue(onMariaDb(), "a session time_zone is MariaDB's; H2's is fixed by the test URL")
        val at = Instant.parse("2031-05-06T07:08:09Z")
        val nonce = "stored-time-${UUID.randomUUID()}"
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery("SET time_zone = '-07:00'").executeUpdate()
            try {
                LoginChallengeEntity().apply { this.nonce = nonce; email = "zone@example.com"; expiresAt = at }.persist()
                em.flush()
                val written = em.createNativeQuery("SELECT CAST(expires_at AS CHAR(32)) FROM veyq_login_challenge WHERE nonce = ?1")
                    .setParameter(1, nonce).singleResult as String
                assertTrue(written.startsWith("2031-05-06 07:08:09"), "the column holds the UTC wall clock, not -07:00's: $written")
            } finally {
                em.createNativeQuery("SET time_zone = DEFAULT").executeUpdate()
            }
        }
        dataSource.connection.use { c ->
            c.createStatement().execute("SET time_zone = '+05:30'")
            try {
                assertEquals(at, read(c, "SELECT expires_at FROM veyq_login_challenge WHERE nonce = '$nonce'"), "read under +05:30")
            } finally {
                c.createStatement().execute("SET time_zone = DEFAULT")
            }
        }
        assertEquals(at, QuarkusTransaction.requiringNew().call { LoginChallengeEntity.findById(nonce)!!.expiresAt })
    }

    /** The positive control of the two MariaDB negatives above: the same binding against a `TIMESTAMP(3)` column refuses
     *  2577 and moves a value written in one session zone and read in another — and a `DATETIME(3)` does neither. */
    @Test
    fun aTimestampColumnWouldFailBothControl() {
        assumeTrue(onMariaDb(), "the control is the production engine's TIMESTAMP")
        dataSource.connection.use { c ->
            c.createStatement().execute("CREATE TEMPORARY TABLE stored_time_control (k INT PRIMARY KEY, ts TIMESTAMP(3) NULL, dt DATETIME(3) NULL)")
            try {
                val far = Instant.parse("2577-12-11T12:05:27Z")
                assertThrows<SQLException>("a TIMESTAMP refuses 2577") { write(c, "ts", 1, far) }
                write(c, "dt", 1, far)
                assertEquals(far, read(c, "SELECT dt FROM stored_time_control WHERE k = 1"))

                val at = Instant.parse("2031-05-06T07:08:09Z")
                c.createStatement().execute("SET time_zone = '-07:00'")
                write(c, "ts", 2, at)
                write(c, "dt", 3, at)
                c.createStatement().execute("SET time_zone = '+00:00'")
                assertNotEquals(at, read(c, "SELECT ts FROM stored_time_control WHERE k = 2"), "a TIMESTAMP moves with the session zone")
                assertEquals(at, read(c, "SELECT dt FROM stored_time_control WHERE k = 3"), "a DATETIME does not")
            } finally {
                c.createStatement().execute("SET time_zone = DEFAULT")
                c.createStatement().execute("DROP TEMPORARY TABLE IF EXISTS stored_time_control")
            }
        }
    }

    private fun onMariaDb(): Boolean = dataSource.connection.use { it.metaData.databaseProductName.contains("MariaDB", ignoreCase = true) }

    private fun challenge(expiresAt: Instant): String {
        val nonce = "stored-time-${UUID.randomUUID()}"
        QuarkusTransaction.requiringNew().run {
            LoginChallengeEntity().apply { this.nonce = nonce; email = "time@example.com"; this.expiresAt = expiresAt }.persist()
        }
        return nonce
    }

    /** A time column's stored text — the wall clock the row holds, in no zone. */
    private fun text(table: String, key: String, id: String, column: String): String = QuarkusTransaction.requiringNew().call {
        em.createNativeQuery("SELECT CAST($column AS CHAR(32)) FROM $table WHERE $key = ?1").setParameter(1, id).singleResult as String
    }

    /** Hibernate's own binding of an `Instant` on MariaDB (`TimestampUtcAsJdbcTimestampJdbcType`): a UTC calendar. */
    private fun write(c: Connection, column: String, k: Int, at: Instant) {
        c.prepareStatement("INSERT INTO stored_time_control (k, $column) VALUES (?, ?)").use {
            it.setInt(1, k)
            it.setTimestamp(2, Timestamp.from(at), utc())
            it.executeUpdate()
        }
    }

    private fun read(c: Connection, sql: String): Instant =
        c.createStatement().executeQuery(sql).use { rs -> rs.next(); rs.getTimestamp(1, utc()).toInstant() }

    private fun utc(): Calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
}
