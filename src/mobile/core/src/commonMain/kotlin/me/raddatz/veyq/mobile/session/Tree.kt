package me.raddatz.veyq.mobile.session

import me.raddatz.veyq.mobile.api.models.NodeSummary
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.OpenRefused
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.navigation.NodeRef
import me.raddatz.veyq.mobile.navigation.ObjectRef

/** A node whose name and key opened. Its key stays in memory only. */
class OpenNode(
    val scopeId: String,
    val id: String,
    val parentId: String?,
    val kind: String,
    val name: String,
    internal val key: ByteArray,
    /** The node's own key epoch: what a write into it states (`nodeEpoch`). */
    val epoch: Int = 0,
    /** The node's sealed meta (`node-meta` under its key), base64, and the revision it is stored at. */
    internal val wrappedMeta: String? = null,
    val metaRevision: Long = 0,
    /** The scope epoch the node's key was minted under: a write seals under it only at the scope's current epoch. */
    val scopeEpoch: Int = 0,
    /** How often the node was placed (created, renamed, moved): its name and key wraps are bound to it, a placement states it. */
    val placementRevision: Long = 0,
) {
    fun asNode(): NodeRef = NodeRef(scopeId, id, parentId, kind, name)
    fun asObject(): ObjectRef? = asNode().asObject()
}

/**
 * A scope's tree, opened top-down as the web opens it (`src/web/src/core/node/tree.ts`, ADR 0080): a root node's name
 * and key open under the scope key, every child's under its parent's key, each bound to the node's scope, id, scope
 * epoch and placement revision. A node that does not open is left out with everything under it.
 */
object Tree {
    fun open(suite: V1, scopeId: String, scopeKey: ByteArray, rows: List<NodeSummary>): List<OpenNode> {
        val children = rows.groupBy { it.parentId }
        val out = ArrayList<OpenNode>(rows.size)
        fun walk(parentId: String?, parentKey: ByteArray) {
            for (row in children[parentId].orEmpty().sortedBy { it.orderIndex }) {
                if (row.blockedByOperator) continue
                val binding = arrayOf(
                    "scopeId" to scopeId, "nodeId" to row.id, "epoch" to row.scopeEpoch.toLong(), "placementRevision" to row.placementRevision,
                )
                val node = try {
                    val name = suite.openField(Context("node-name", *binding), parentKey, unb64(row.encryptedName)).decodeToString()
                    val key = suite.open(Context("content-key", *binding), parentKey, unb64(row.wrappedContentKey))
                    OpenNode(scopeId, row.id, row.parentId, row.kind, name, key, row.epoch, row.wrappedMeta, row.metaRevision, row.scopeEpoch, row.placementRevision)
                } catch (e: OpenRefused) {
                    null
                } catch (e: IllegalArgumentException) {
                    null
                } ?: continue
                out += node
                walk(node.id, node.key)
            }
        }
        walk(null, scopeKey)
        return out
    }
}
