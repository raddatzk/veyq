package me.raddatz.veyq.core.sharing

import io.quarkus.test.junit.QuarkusTest
import java.sql.DriverManager
import java.util.UUID
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * ADR 0114 decision 1: `V202611021647__core_closed_links_mark_due.sql` marks due every node of every scope holding a
 * READ or READ_WRITE link switched off or deleted before ADR 0114's close body existed — `V202611021600` cleared those
 * links' keys and marked nothing, so their holders kept reading what was added under their roots. On a private
 * in-memory H2 brought to the version before it, each scope seeded as the old code left it: a switched-off READ link
 * (its live and trashed nodes all due after), a deleted READ_WRITE link (the same), and the controls beside them — a
 * scope with only an open READ link, one whose only closed link is WRITE_ONLY (its close marks nothing, decision 2),
 * and one whose closed link's root was purged — none of whose nodes is marked.
 */
@QuarkusTest
class ClosedLinksMarkDueMigrationSliceTest {

    @Test
    fun everyNodeOfAScopeWithAClosedViewableLinkIsDue_andNoneElsewhere() {
        val url = "jdbc:h2:mem:closed-links-${UUID.randomUUID()};MODE=MariaDB;DB_CLOSE_DELAY=-1;TIME ZONE=UTC"
        fun flyway(target: String? = null) = Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration")
            .let { if (target != null) it.target(target) else it }.load()
        flyway(BEFORE).migrate()

        val revoked = Scope(url, "READ", enabled = false, deleted = false)
        val deleted = Scope(url, "READ_WRITE", enabled = false, deleted = true)
        val open = Scope(url, "READ", enabled = true, deleted = false)
        val dropBox = Scope(url, "WRITE_ONLY", enabled = false, deleted = false)
        val purged = Scope(url, "READ", enabled = false, deleted = true, rootExists = false)

        flyway().migrate()

        for (s in listOf(revoked, deleted)) assertEquals(s.nodes.toSet(), due(url, s.scopeId), "every node of ${s.role}'s scope, the trashed one included")
        for (s in listOf(open, dropBox, purged)) assertEquals(emptySet<String>(), due(url, s.scopeId), "nothing of ${s.role}'s scope (enabled ${s.enabled})")
    }

    /** One scope as the old code left it: a root folder with a child and a trashed grandchild, a sibling root folder
     *  no link is on, and one link on the first root — its root missing when [rootExists] is false (purged since). */
    private class Scope(url: String, val role: String, val enabled: Boolean, deleted: Boolean, rootExists: Boolean = true) {
        val scopeId = UUID.randomUUID().toString()
        val nodes = mutableListOf<String>()

        init {
            val root = UUID.randomUUID().toString()
            val statements = mutableListOf("SET REFERENTIAL_INTEGRITY FALSE") // no scope, tenant or account rows needed
            if (rootExists) {
                val child = UUID.randomUUID().toString()
                statements += node(root, scopeId, trashed = false)
                statements += node(child, root, trashed = false)
                statements += node(UUID.randomUUID().toString().also { nodes += it }, child, trashed = true)
                nodes += listOf(root, child)
            }
            statements += node(UUID.randomUUID().toString().also { nodes += it }, scopeId, trashed = false)
            statements += "INSERT INTO veyq_share (id, owner_account, scope_id, node_id, role, enabled, deleted, created_at) VALUES " +
                "('${"L".repeat(21)}${UUID.randomUUID().toString().replace("-", "").take(22)}', '${UUID.randomUUID()}', " +
                "'$scopeId', '$root', '$role', $enabled, $deleted, CURRENT_TIMESTAMP)"
            sql(url, statements)
        }

        private fun node(id: String, parentId: String, trashed: Boolean) =
            "INSERT INTO veyq_node (id, scope_id, parent_id, kind, encrypted_name, slug_token, order_index, wrapped_content_key, " +
                "created_at, deleted_at, name_editor_user_id) VALUES ('$id', '$scopeId', '$parentId', 'FOLDER', 'bmFtZQ==', '$id', 0, " +
                "'a2V5', CURRENT_TIMESTAMP, ${if (trashed) "CURRENT_TIMESTAMP" else "NULL"}, '${UUID.randomUUID()}')"
    }

    private fun due(url: String, scopeId: String): Set<String> = DriverManager.getConnection(url, "sa", "").use { c ->
        c.createStatement().use { s ->
            s.executeQuery("SELECT CAST(id AS CHAR(36)) FROM veyq_node WHERE scope_id = '$scopeId' AND rekey_due = TRUE").use { r ->
                buildSet { while (r.next()) add(r.getString(1)) }
            }
        }
    }

    private companion object {
        /** The version below the marks: ADR 0114's share close. */
        const val BEFORE = "202611021600"
    }
}

private fun sql(url: String, statements: List<String>) = DriverManager.getConnection(url, "sa", "").use { c ->
    c.createStatement().use { s -> statements.forEach { s.execute(it) } }
}
