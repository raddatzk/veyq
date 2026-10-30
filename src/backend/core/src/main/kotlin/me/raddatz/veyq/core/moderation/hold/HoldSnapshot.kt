package me.raddatz.veyq.core.moderation.hold

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.persistence.EntityManager
import org.hibernate.Session
import java.sql.Connection
import java.sql.Types
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** What a hold is taken on (en.moderation.hold § The snapshot): every target names the space it lies in. */
sealed interface HoldTarget {
    val scopeId: String

    /** A reported node: the live subtree it opens ([nodeIds], the node itself included). */
    data class Subtree(override val scopeId: String, val nodeIds: List<String>) : HoldTarget

    /** An application's reported item (a Memories medium), by its id. */
    data class Item(override val scopeId: String, val itemId: String) : HoldTarget

    /** A reported upload link, by its share id. */
    data class Link(override val scopeId: String, val shareId: String) : HoldTarget

    /** A reported name (ADR 0093 § 9): its node's one row — the name and its editor — and nothing below it. */
    data class Name(override val scopeId: String, val nodeId: String) : HoldTarget

    /**
     * Everything one uploader uploaded to the space, as a moderator's `UPLOADER_CONTENT` step names it
     * ([me.raddatz.veyq.core.moderation.block.UploaderContent]): core's file nodes ([nodeIds], each with its
     * attachments) and an application's items ([itemIds], a Memories medium with its derivatives and bucket).
     */
    data class Uploads(override val scopeId: String, val nodeIds: List<String>, val itemIds: List<String>) : HoldTarget
}

/**
 * The seam a hold's snapshot is filled through (ADR 0079: core names no application table). Each implementation copies
 * its own rows for a [HoldTarget] into the [HoldSnapshot] and names the blobs and guest identities they refer to; core
 * then copies those blob rows and pins their storage keys, and copies the identities. **Phase-ordered** like
 * `NodeSubtreeEraser` — sorted by [phase] by the caller, never by CDI order, and the phases distinct — and run inside the
 * report's transaction, under the reported space's lock. It reads and copies; it writes nothing but hold rows.
 */
interface HoldSnapshotContributor {
    val phase: Int
    fun contribute(target: HoldTarget, snapshot: HoldSnapshot)
}

/**
 * One hold's snapshot being taken: rows copied as JSON of their columns as stored (opaque ciphertext and metadata —
 * the server opens nothing), plus the blobs and guest identities the copied rows name, which [Holds] copies and pins once
 * every contributor has run. Table and column names are the contributors' own constants, checked to be plain
 * identifiers before they reach a statement; values are bound parameters.
 */
class HoldSnapshot internal constructor(
    private val holdId: String,
    private val em: EntityManager,
    /** Rows the hold already holds, as `table:id` — a widened hold ([Holds.widen]) copies none of them twice. */
    private val already: Set<String> = emptySet(),
) {
    internal val blobIds = LinkedHashSet<String>()
    internal val identityIds = LinkedHashSet<String>()

    /** The id of every row copied so far, whatever its table — what a later contributor's rows may name as their object
     *  (the guest-write record, [me.raddatz.veyq.core.moderation.guestwrite.GuestWriteRecordHoldSnapshot]). */
    internal val copiedIds = LinkedHashSet<String>()

    /** Name a blob a copied row refers to: its row is copied and its storage key pinned. Null is ignored. */
    fun blob(id: String?) {
        if (id != null) blobIds += id
    }

    /** Name a guest identity a copied row refers to (it is deleted once nothing names it): its row is copied. */
    fun identity(id: String?) {
        if (id != null) identityIds += id
    }

    /**
     * Copy every row of [table] whose [column] is one of [values] into the hold, and answer them — each a map of its
     * columns (lower-case names) to their text — so the caller can name what they refer to without reading them twice.
     */
    fun copy(table: String, column: String, values: Collection<String>): List<Map<String, String?>> {
        require(IDENTIFIER.matches(table) && IDENTIFIER.matches(column)) { "not a plain identifier: $table.$column" }
        if (values.isEmpty()) return emptyList()
        val rows = values.distinct().chunked(BATCH).flatMap { batch -> select(table, column, batch) }
        rows.forEach { row ->
            val sourceId = row["id"] ?: row[column] ?: ""
            if ("$table:$sourceId" in already) return@forEach
            HoldRowEntity().apply {
                id = UUID.randomUUID().toString()
                this.holdId = this@HoldSnapshot.holdId
                sourceTable = table
                this.sourceId = sourceId
                payload = json.writeValueAsString(row)
            }.persist()
            row["id"]?.let { copiedIds += it }
        }
        return rows
    }

    private fun select(table: String, column: String, values: List<String>): List<Map<String, String?>> =
        // The parameter is typed: Hibernate 7.4's `ReturningWork` carries Checker Framework type annotations, and an
        // inferred `connection` drags them into a type whose classes are not on the classpath (KT-80247 — a warning
        // now, an error from Kotlin language version 2.4). The same holds for every `doReturningWork` lambda.
        em.unwrap(Session::class.java).doReturningWork { connection: Connection ->
            val marks = values.joinToString(",") { "?" }
            connection.prepareStatement("SELECT * FROM $table WHERE $column IN ($marks)").use { statement ->
                values.forEachIndexed { i, v -> statement.setString(i + 1, v) }
                statement.executeQuery().use { rs ->
                    val meta = rs.metaData
                    val out = mutableListOf<Map<String, String?>>()
                    while (rs.next()) {
                        out += (1..meta.columnCount).associate { i -> meta.getColumnLabel(i).lowercase() to text(rs, meta.getColumnType(i), i) }
                    }
                    out
                }
            }
        }

    /**
     * A column's value as the snapshot keeps it: its text as stored, but a time as the ISO-8601 instant it is (UTC, `Z`).
     * A time column is a `DATETIME` holding the UTC wall clock (inbox #509; H2's test URL keeps its session at UTC too),
     * so it is read as the `LocalDateTime` it is, at UTC — never through `getTimestamp`, whose calendar is the JVM's: the
     * Art. 18 export read a guest's write two hours off on a machine in Berlin while a time was read in the JVM's zone.
     */
    private fun text(rs: java.sql.ResultSet, type: Int, i: Int): String? = when (type) {
        Types.TIMESTAMP -> rs.getObject(i, LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC)?.toString()
        Types.TIMESTAMP_WITH_TIMEZONE -> rs.getObject(i, OffsetDateTime::class.java)?.toInstant()?.toString()
        else -> rs.getString(i)
    }

    private companion object {
        val IDENTIFIER = Regex("^[a-z][a-z0-9_]{0,63}$")
        const val BATCH = 500
        val json = ObjectMapper()
    }
}
