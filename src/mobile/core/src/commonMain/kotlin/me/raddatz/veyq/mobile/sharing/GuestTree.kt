package me.raddatz.veyq.mobile.sharing

import me.raddatz.veyq.mobile.api.models.ShareNode
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.OpenRefused
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.session.Tree

/**
 * The tree a READ or READ_WRITE link serves, opened (ADR 0150 decision 2; the web's `shareTree.ts` `openShareRoot` and
 * `walkSubtree`, `ShareTreePage`'s key hand-down): the root's key unwrapped from the share's `wrap` under `K_share`, every
 * descendant's key and name under its parent's — a guest's identity folder's entry under its guest's `K_up`, through the
 * parent's drop key ([Tree.childEntryKey]) — each node an [OpenNode] the member's models take. A node whose key or name
 * does not open is left out with everything under it. The keys live in this object, which lives in the open link's model.
 */
class GuestTree private constructor(
    val scopeId: String,
    /** Every node that opened, the root first (its name is the share's own), parents before children. */
    val nodes: List<OpenNode>,
) {
    val root: OpenNode get() = nodes.first()
    fun node(id: String): OpenNode? = nodes.firstOrNull { it.id == id }
    fun children(id: String?): List<OpenNode> = nodes.filter { it.parentId == id }

    /**
     * The labels of [parentId]'s folders where a sibling folder shares the name (`Anna (2)`, the platform's
     * [me.raddatz.veyq.mobile.tree.DisplayNames]), in node-id order — a guest is served no creation instant (the web's
     * `guestAccess` and its level alike, inbox #393/#1062). A folder whose name is its label is not in it.
     */
    fun folderLabels(parentId: String): Map<String, String> =
        me.raddatz.veyq.mobile.tree.DisplayNames.of(children(parentId).filter { it.kind == me.raddatz.veyq.mobile.navigation.NodeRef.FOLDER }, { it.id }, { it.name }, { null }, byCreation = false)
            .filter { (id, label) -> node(id)?.name != label }

    /** Node [id]'s name as a title draws it: a folder's label among its sibling folders ([folderLabels]), else its name. */
    fun displayName(id: String): String? {
        val n = node(id) ?: return null
        val parent = n.parentId ?: return n.name
        return if (n.kind == me.raddatz.veyq.mobile.navigation.NodeRef.FOLDER) folderLabels(parent)[id] ?: n.name else n.name
    }

    /** Every key zeroed: the tree let go. */
    fun close() = nodes.forEach { it.key.fill(0) }

    override fun toString(): String = "GuestTree(${nodes.size})"

    companion object {
        /** A tree of nodes already opened, the root first (a test's). */
        internal fun over(scopeId: String, nodes: List<OpenNode>): GuestTree = GuestTree(scopeId, nodes)

        /** The share's second wrap of the root's key under `K_share` (ADR 0012), its purpose only. */
        private val SHARE_KEY = Context("share-key")

        /** A guest's display name's binding (cr-0101, ADR 0072): under the root's key, or an uploader's own `K_up`. */
        fun identityName(scopeId: String, shareId: String, identityId: String) =
            Context("share-identity-name", "scopeId" to scopeId, "shareId" to shareId, "identityId" to identityId)

        /** The root's key of a READ or READ_WRITE link (a fresh copy, the caller's to zero). Throws [ShareRefused.Unavailable]. */
        fun openRoot(suite: V1, head: ShareHead): ByteArray {
            val wrap = head.share.wrap ?: throw ShareRefused.Unavailable(false)
            return try {
                suite.open(SHARE_KEY, head.kShare, unb64(wrap))
            } catch (e: Exception) {
                throw ShareRefused.Unavailable(false)
            }
        }

        /** The subtree [head] serves, opened top-down from its root's key. Throws [ShareRefused.Unavailable] where the root does not open. */
        fun open(suite: V1, head: ShareHead): GuestTree {
            val rootKey = openRoot(suite, head)
            val rows = head.share.nodes
            val root = rows.firstOrNull { it.parentId == null } ?: run { rootKey.fill(0); throw ShareRefused.Unavailable(false) }
            val scopeId = head.scopeId
            val out = ArrayList<OpenNode>(rows.size)
            fun opened(row: ShareNode, name: String, key: ByteArray) = OpenNode(
                scopeId, row.id, row.parentId, row.kind, name, key, row.epoch, row.wrappedMeta, 0, row.scopeEpoch,
                row.placementRevision ?: 0, row.encryptedName, row.sealedDropPrivateKey, row.shareIdentityId, row.wrappedIdentityKey,
            )
            out += opened(root, head.name, rootKey)
            val byParent = rows.filter { it.parentId != null }.groupBy { it.parentId }
            val queue = ArrayDeque(listOf(out.first()))
            while (queue.isNotEmpty()) {
                val parent = queue.removeFirst()
                for (row in byParent[parent.id].orEmpty().sortedBy { it.orderIndex ?: 0 }) {
                    if (row.blockedByOperator) continue
                    val wrapped = row.wrappedContentKey ?: continue
                    val encName = row.encryptedName ?: continue
                    val placement = row.placementRevision ?: continue
                    val binding = arrayOf<Pair<String, Any?>>(
                        "scopeId" to scopeId, "nodeId" to row.id, "epoch" to row.scopeEpoch.toLong(), "placementRevision" to placement,
                    )
                    val child = try {
                        val entryKey = Tree.childEntryKey(suite, scopeId, parent, parent.key, row.shareIdentityId, row.wrappedIdentityKey)
                        val name = suite.openField(Context("node-name", *binding), entryKey, unb64(encName)).decodeToString()
                        opened(row, name, suite.open(Context("content-key", *binding), entryKey, unb64(wrapped)))
                    } catch (e: OpenRefused) {
                        null
                    } catch (e: IllegalArgumentException) {
                        null
                    } ?: continue
                    out += child
                    queue.addLast(child)
                }
            }
            return GuestTree(scopeId, out)
        }
    }
}
