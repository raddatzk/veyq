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
    /** The name as stored (`node-name` under the parent's key), base64: what a report of the name hands over a key to. */
    internal val encryptedName: String? = null,
    /** A drop box's private drop key, sealed under its key (`drop-private-key`), base64; null for any other node. */
    internal val sealedDropPrivateKey: String? = null,
    /** A guest's identity folder (ADR 0080 decision 11): the identity it belongs to, and that identity's key `K_up` sealed
     *  to its drop box's public drop key (`upload-identity-key`), under which the folder's own entry lives. */
    val shareIdentityId: String? = null,
    internal val wrappedIdentityKey: String? = null,
    /** When the server created the node (ISO-8601): a drop box's guests' folders are ordered by it. */
    val createdAt: String? = null,
) {
    fun asNode(): NodeRef = NodeRef(scopeId, id, parentId, kind, name)
    fun asObject(): ObjectRef? = asNode().asObject()
}

/**
 * A scope's tree, opened top-down as the web opens it (`src/web/src/core/node/tree.ts`, ADR 0080): a root node's name
 * and key open under the scope key, every child's under its parent's key, each bound to the node's scope, id, scope
 * epoch and placement revision — except a guest's identity folder in a drop box, whose entry the guest sealed under its
 * own identity key `K_up`, opened through the drop box's drop key ([childEntryKey]). A node that does not open is left
 * out with everything under it (a guest-sealed entry that does not open drops that folder, never the tree).
 */
object Tree {
    fun open(suite: V1, scopeId: String, scopeKey: ByteArray, rows: List<NodeSummary>): List<OpenNode> {
        val children = rows.groupBy { it.parentId }
        val out = ArrayList<OpenNode>(rows.size)
        fun walk(parent: OpenNode?, parentKey: ByteArray) {
            for (row in children[parent?.id].orEmpty().sortedBy { it.orderIndex }) {
                if (row.blockedByOperator) continue
                val binding = arrayOf(
                    "scopeId" to scopeId, "nodeId" to row.id, "epoch" to row.scopeEpoch.toLong(), "placementRevision" to row.placementRevision,
                )
                val node = try {
                    val entryKey = childEntryKey(suite, scopeId, parent, parentKey, row.shareIdentityId, row.wrappedIdentityKey)
                    val name = suite.openField(Context("node-name", *binding), entryKey, unb64(row.encryptedName)).decodeToString()
                    val key = suite.open(Context("content-key", *binding), entryKey, unb64(row.wrappedContentKey))
                    OpenNode(
                        scopeId, row.id, row.parentId, row.kind, name, key, row.epoch, row.wrappedMeta, row.metaRevision, row.scopeEpoch,
                        row.placementRevision, row.encryptedName, row.sealedDropPrivateKey, row.shareIdentityId, row.wrappedIdentityKey, row.createdAt,
                    )
                } catch (e: OpenRefused) {
                    null
                } catch (e: IllegalArgumentException) {
                    null
                } ?: continue
                out += node
                walk(node, node.key)
            }
        }
        walk(null, scopeKey)
        return out
    }

    /**
     * The key a child's directory entry lives under (the web's `childEntryKey`): its parent's content key — except for a
     * guest's identity folder, whose entry is under the guest's `K_up`: the drop box's private drop key opened under the
     * drop box's key (`drop-private-key`), then the identity key sealed to its public half opened with it
     * (`upload-identity-key`, `v1.json#drop_key`). Throws [OpenRefused] where that chain does not open.
     */
    fun childEntryKey(suite: V1, scopeId: String, parent: OpenNode?, parentKey: ByteArray, shareIdentityId: String?, wrappedIdentityKey: String?): ByteArray {
        if (shareIdentityId == null) return parentKey
        val sealedDrop = parent?.sealedDropPrivateKey ?: throw OpenRefused("content-key")
        val wrapped = wrappedIdentityKey ?: throw OpenRefused("content-key")
        val seed = suite.deserializeDropKey(suite.open(Context("drop-private-key", "scopeId" to scopeId, "nodeId" to parent.id), parentKey, unb64(sealedDrop)))
        return suite.openAs(
            Context("upload-identity-key", "scopeId" to scopeId, "nodeId" to parent.id, "identityId" to shareIdentityId),
            suite.xwingKey(seed), unb64(wrapped),
        )
    }
}
