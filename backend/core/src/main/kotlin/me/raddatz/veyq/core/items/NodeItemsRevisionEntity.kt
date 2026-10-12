package me.raddatz.veyq.core.items

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.DynamicUpdate

/**
 * A node's item-list counters (inbox #410, ADR 0033) — the platform's change signal for the **records an application
 * keeps inside a node** (Memories' media today; entries, inventory items later). One row per node, two columns, so one
 * read is a **consistent pair**:
 *
 * - [revision] moves on **every** change of the node's items (an add, an edit, a removal, a restore) and is the
 *   list endpoint's `ETag`: an unchanged poll is an O(1) compare and a `304`, no item query.
 * - [deleteRevision] moves **only** on a removal, so a removal moves both and an add or edit only [revision]. It gates
 *   the O(N) id-set reconcile: the server returns the node's current item ids only when a client's echoed value lags it.
 *
 * **No row reads as 0/0**: the node's first change births the row ([NodeItemsRevisions.bump], under the node row's
 * lock), and the node-subtree and scope erasers delete it before the node goes (`fk_items_revision_node`, no cascade).
 * Not the scope's tree revision (ADR 0081), which covers the node list; this covers the items inside one node. Opaque
 * operational metadata (two numbers): operator-blind.
 */
@Entity
@Table(name = "veyq_node_items_revision")
@DynamicUpdate
class NodeItemsRevisionEntity : PanacheEntityBase {
    @Id
    @Column(name = "node_id", columnDefinition = "uuid")
    lateinit var nodeId: String

    @Column(name = "revision", nullable = false)
    var revision: Int = 0

    @Column(name = "delete_revision", nullable = false)
    var deleteRevision: Int = 0

    companion object : PanacheCompanionBase<NodeItemsRevisionEntity, String>
}
