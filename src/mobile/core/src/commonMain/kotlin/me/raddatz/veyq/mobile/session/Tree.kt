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
                    OpenNode(scopeId, row.id, row.parentId, row.kind, name, key)
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
