package me.raddatz.veyq.core.items

import me.raddatz.veyq.core.node.NodeEntity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import jakarta.transaction.Transactional
import java.time.Instant
import java.time.OffsetDateTime

/** A node's item-list counters read as one row: a consistent pair, `(0, 0)` before the node's first change. */
data class ItemsRevisions(val revision: Int, val deleteRevision: Int)

/**
 * The keyset cursor of an item list (ADR 0033): the newest `(updatedAt, id)` a client has already seen. The item query
 * is the application's (`updatedAt > cursor.updatedAt or (updatedAt = … and id > cursor.id)`, ordered by both); the
 * wire form `"updatedAt,id"` and its parse are the platform's, so every list reads the same cursor.
 */
data class ItemsCursor(val updatedAt: Instant, val id: String) {
    companion object {
        /**
         * Parse the wire cursor `"updatedAt,id"`; null (⇒ the full list) when absent or malformed. Robust to the
         * timestamp's wire form — an offset date-time (`…Z` / `…+00:00`, what a summary's `updatedAt` serializes as) or a
         * bare instant — so a client can echo back exactly what it received.
         */
        fun parse(raw: String?): ItemsCursor? {
            val s = raw?.trim().orEmpty()
            val comma = s.indexOf(',')
            if (comma <= 0 || comma == s.length - 1) return null
            val ts = s.substring(0, comma).trim()
            val id = s.substring(comma + 1).trim()
            val instant = runCatching { OffsetDateTime.parse(ts).toInstant() }
                .recoverCatching { Instant.parse(ts) }
                .getOrNull() ?: return null
            return ItemsCursor(instant, id)
        }
    }
}

/** The list's `ETag`: the node's [ItemsRevisions.revision], quoted. */
object ItemsETag {
    fun of(revision: Int): String = "\"$revision\""

    /** The client's `If-None-Match` echo — lenient (strips a weak marker and quotes); null when absent or unparseable ⇒ never a 304. */
    fun parse(raw: String?): Int? = raw?.trim()?.removePrefix("W/")?.trim()?.trim('"')?.toIntOrNull()
}

/** What one conditional poll of an item list answers (see [NodeItemsRevisions.read]). */
sealed interface ItemsRead<out T> {
    /** The client's `If-None-Match` still equals the revision: nothing changed, and no item query ran. */
    object NotModified : ItemsRead<Nothing>

    /** The keyset [delta], both counters and — only when a removal happened since the client last synced — the node's current [ids]. */
    data class Changed<T>(val revisions: ItemsRevisions, val delta: List<T>, val ids: List<String>?) : ItemsRead<T>
}

/**
 * The platform's way for an application to publish a list of **records** (not nodes) inside a node: a per-node
 * revision, a conditional GET, a keyset delta and a delete-gated id-set (ADR 0033). An application owns its records
 * and their keyset query; core owns the two counters ([NodeItemsRevisionEntity]), the bump a writer calls, and the
 * answer to "did it change, and what must the client be told" ([read]). Core names no application: the node is any
 * node, the items are whatever the caller's lambdas return.
 *
 * **Lock order (the table is a leaf).** [bump] runs in the writer's transaction, after the item rows it changed.
 * Item writers do not hold the scope lock (unlike the tree revision's `NodesStore.bumpTree`), so the first-bump insert
 * is decided under the **node row's** lock — `item rows → node row → revision row`, the order every Memories gallery
 * writer already takes (`MediaLockOrderRaceSliceTest`). The revision row is taken last and nothing waits on it while
 * holding anything else.
 */
@ApplicationScoped
class NodeItemsRevisions {

    /**
     * Move the node's counters in **one atomic UPDATE**, so the pair is always consistent: every change of the node's
     * items moves [ItemsRevisions.revision]; a removal ([deleted] = true) additionally moves
     * [ItemsRevisions.deleteRevision]. **Every writer of an item list calls this in the transaction that makes the
     * change**: a writer that forgets is a live view that never learns of it, with no failing request to show it. Joins
     * the caller's transaction.
     *
     * **The node's first change births the row.** An UPDATE that matches nothing takes no lock under READ COMMITTED
     * (ADR 0078), so the insert is decided under the node row's lock and the UPDATE asked again beneath it: of two
     * first bumps the second waits, then finds the row the first committed and bumps it — never a second insert at the
     * primary key. [scopeId] binds the lock to the node's own scope; a node of another scope is no node here.
     */
    @Transactional
    fun bump(scopeId: String, nodeId: String, deleted: Boolean = false) {
        if (bumpRow(nodeId, deleted) == 1) return
        NodeEntity.find("id = ?1 and scopeId = ?2", nodeId, scopeId).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult()
        if (bumpRow(nodeId, deleted) == 1) return
        NodeItemsRevisionEntity().apply {
            this.nodeId = nodeId
            revision = 1
            deleteRevision = if (deleted) 1 else 0
        }.persist()
    }

    private fun bumpRow(nodeId: String, deleted: Boolean): Int =
        if (deleted) {
            NodeItemsRevisionEntity.update("revision = revision + 1, deleteRevision = deleteRevision + 1 where nodeId = ?1", nodeId)
        } else {
            NodeItemsRevisionEntity.update("revision = revision + 1 where nodeId = ?1", nodeId)
        }

    /** The node's counters — one row, so a consistent pair; `(0, 0)` before its first change. */
    fun revisions(nodeId: String): ItemsRevisions =
        NodeItemsRevisionEntity.findById(nodeId)?.let { ItemsRevisions(it.revision, it.deleteRevision) } ?: ItemsRevisions(0, 0)

    /**
     * One conditional poll of a node's item list. Reads the counters as a consistent pair; when [ifNoneMatch] still
     * equals the revision ⇒ [ItemsRead.NotModified] (the idle fast path: neither lambda runs). Otherwise [delta] — the
     * application's keyset query past the client's cursor — and, **only when [sinceDelete] lags the node's
     * `deleteRevision`** (a removal happened since), [ids] — the node's current item ids, which the client diffs to drop
     * what it no longer sees (a net-zero delete+add included: a set, not a count). Pure adds and edits stay delta-only.
     * Access to the node is the caller's check, made before this is reached.
     */
    fun <T> read(nodeId: String, ifNoneMatch: Int?, sinceDelete: Int, delta: () -> List<T>, ids: () -> List<String>): ItemsRead<T> {
        val revisions = revisions(nodeId)
        if (ifNoneMatch != null && ifNoneMatch == revisions.revision) return ItemsRead.NotModified
        val rows = delta()
        return ItemsRead.Changed(revisions, rows, if (sinceDelete < revisions.deleteRevision) ids() else null)
    }
}
