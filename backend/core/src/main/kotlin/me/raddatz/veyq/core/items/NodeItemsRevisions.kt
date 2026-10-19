package me.raddatz.veyq.core.items

import me.raddatz.veyq.core.node.NodeEntity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import jakarta.transaction.Transactional

/** A node's item-list counters read as one row: a consistent pair, `(0, 0)` before the node's first change. */
data class ItemsRevisions(val revision: Int, val deleteRevision: Int)

/**
 * The keyset cursor of an item list (ADR 0033 decisions 3 and 6): the last `(position, id)` a client has been given. An
 * item's **position** is the node's item revision its last change was stamped with — what [NodeItemsRevisions.bump]
 * returned, taken under the revision row's lock that bump holds to the commit — so positions become visible in the order
 * they were handed out, and a client that has passed one never sees an item land behind it later. A timestamp cannot
 * promise that: a writer that took its stamp first and committed last landed behind a cursor a poll in between had
 * already passed (`MediaKeysetRaceSliceTest`). The item query is the application's (`position > cursor.position or
 * (position = … and id > cursor.id)`, ordered by both); the wire form `"position,id"`, its parse and the position's
 * meaning are the platform's, so every list reads the same cursor. Opaque to the client: a page answers it, a client
 * echoes it.
 */
data class ItemsCursor(val position: Long, val id: String) {
    /** The wire form a page answers as its `cursor` and a client echoes as `since`. */
    fun wire(): String = "$position,$id"

    companion object {
        /** Parse the wire cursor `"position,id"`; null (⇒ from the beginning) when absent or malformed. */
        fun parse(raw: String?): ItemsCursor? {
            val s = raw?.trim().orEmpty()
            val comma = s.indexOf(',')
            if (comma <= 0 || comma == s.length - 1) return null
            val position = s.substring(0, comma).trim().toLongOrNull()?.takeIf { it >= 0 } ?: return null
            return ItemsCursor(position, s.substring(comma + 1).trim())
        }
    }
}

/** The list's `ETag`: the node's [ItemsRevisions.revision], quoted. */
object ItemsETag {
    fun of(revision: Int): String = "\"$revision\""

    /** The client's `If-None-Match` echo — lenient (strips a weak marker and quotes); null when absent or unparseable ⇒ never a 304. */
    fun parse(raw: String?): Int? = raw?.trim()?.removePrefix("W/")?.trim()?.trim('"')?.toIntOrNull()
}

/**
 * One page of an item list (ADR 0033 decision 6): at most a limit of items in cursor order, and whether more remain past
 * them. Every read of a list is a page — the first load as much as a poll's delta — so one answer, and the server's memory
 * for it, stay bounded however long the list grows.
 */
data class ItemsPage<out T>(val items: List<T>, val more: Boolean, val cursor: ItemsCursor? = null) {
    companion object {
        /** The most items one page carries, and the limit of a read that names none: the contract's `ItemsLimit`
         *  maximum, which every paged item array declares as its `maxItems`. */
        const val CAP = 1000

        /** The limit a read asks for: [requested], or the cap when it named none (the contract bounds it to 1..CAP). */
        fun limitOf(requested: Int?): Int = (requested ?: CAP).coerceIn(1, CAP)

        /** The page of [fetched], read with a limit of `limit + 1`: the one row past the page only says more remain. */
        fun <T> of(fetched: List<T>, limit: Int): ItemsPage<T> = ItemsPage(fetched.take(limit), fetched.size > limit)
    }
}

/** What one conditional poll of an item list answers (see [NodeItemsRevisions.read]). */
sealed interface ItemsRead<out T> {
    /** The client's `If-None-Match` still equals the revision: nothing changed, and no item query ran. */
    object NotModified : ItemsRead<Nothing>

    /** One page of the keyset [delta], both counters and — only when a removal happened since the client last synced —
     *  the node's current [ids]. */
    data class Changed<T>(val revisions: ItemsRevisions, val delta: ItemsPage<T>, val ids: List<String>?) : ItemsRead<T>
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
     * the caller's transaction. Returns the new revision: a writer that moves an item to the end of the list stamps it
     * as the item's position ([ItemsCursor]), after this call and in the same transaction.
     *
     * **The node's first change births the row.** An UPDATE that matches nothing takes no lock under READ COMMITTED
     * (ADR 0078), so the insert is decided under the node row's lock and the UPDATE asked again beneath it: of two
     * first bumps the second waits, then finds the row the first committed and bumps it — never a second insert at the
     * primary key. [scopeId] binds the lock to the node's own scope; a node of another scope is no node here.
     */
    @Transactional
    fun bump(scopeId: String, nodeId: String, deleted: Boolean = false): Int {
        if (bumpRow(nodeId, deleted) == 1) return revisionHeld(nodeId)
        NodeEntity.find("id = ?1 and scopeId = ?2", nodeId, scopeId).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult()
        if (bumpRow(nodeId, deleted) == 1) return revisionHeld(nodeId)
        NodeItemsRevisionEntity().apply {
            this.nodeId = nodeId
            revision = 1
            deleteRevision = if (deleted) 1 else 0
        }.persist()
        return 1
    }

    /**
     * The revision this transaction's [bump] just wrote, read under the row lock that bump holds to the commit — so it is
     * the item's **position** ([ItemsCursor]): no other writer of the node can take a revision between this one and the
     * commit, and the positions of a node's items become visible in the order they were handed out. A scalar query, not
     * the entity: a bulk UPDATE leaves an entity this session already holds as it was.
     */
    private fun revisionHeld(nodeId: String): Int =
        NodeItemsRevisionEntity.getEntityManager()
            .createQuery("select r.revision from NodeItemsRevisionEntity r where r.nodeId = ?1", Int::class.javaObjectType)
            .setParameter(1, nodeId).singleResult

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
     * equals the revision ⇒ [ItemsRead.NotModified] (the idle fast path: neither lambda runs). Otherwise [delta] — one
     * page of the application's keyset query past the client's cursor — and, **only when [sinceDelete] lags the node's
     * `deleteRevision`** (a removal happened since), [ids] — the node's current item ids, which the client diffs to drop
     * what it no longer sees (a net-zero delete+add included: a set, not a count). Pure adds and edits stay delta-only.
     * Access to the node is the caller's check, made before this is reached.
     *
     * **The counters are read before the rows** (ADR 0081 decision 3's order, ADR 0033 decision 6): a change committing
     * in between lands under the older revision the client keeps, so its next poll differs and pulls it. A client that
     * pages keeps the first page's counters for the same reason, and asks for the later pages without `If-None-Match`.
     */
    fun <T> read(nodeId: String, ifNoneMatch: Int?, sinceDelete: Int, delta: () -> ItemsPage<T>, ids: () -> List<String>): ItemsRead<T> {
        val revisions = revisions(nodeId)
        if (ifNoneMatch != null && ifNoneMatch == revisions.revision) return ItemsRead.NotModified
        val rows = delta()
        return ItemsRead.Changed(revisions, rows, if (sinceDelete < revisions.deleteRevision) ids() else null)
    }
}
