package me.raddatz.veyq.core.moderation.block

import me.raddatz.veyq.core.moderation.report.ReportableItems
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.sharing.SharedSubtreeContributor
import io.quarkus.arc.All
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance

/**
 * What the content blocks of one space reach (uc.moderation.take-a-measure step 3): every node a `CONTENT_NODE` block
 * names **and every node below it** (trashed ones included, so a restore can be refused), and every item a
 * `CONTENT_ITEM` block names. [NONE] for a space with no block — the case every listing meets.
 */
class ContentView internal constructor(val nodeIds: Set<String>, val itemIds: Set<String>) {
    val isEmpty: Boolean get() = nodeIds.isEmpty() && itemIds.isEmpty()

    /** Whether [nodeId] is blocked, itself or through a node above it. */
    fun nodeBlocked(nodeId: String): Boolean = nodeId in nodeIds

    /** Whether an application's item [itemId] on [nodeId] is blocked — itself, or its node. */
    fun itemBlocked(itemId: String, nodeId: String): Boolean = itemId in itemIds || nodeId in nodeIds

    companion object {
        val NONE = ContentView(emptySet(), emptySet())
    }
}

/**
 * The reads every path that honours a measure asks (ADR 0093 § 6, decisions 2-4 of cr-moderation-review-and-measures):
 * a link blocked ([shareBlocked], the guest gate's `OPERATOR_BLOCKED` stop), a guest identity blocked
 * ([identityBlocked]), an account's sign-in blocked ([signInBlocked]), a reporter barred ([reporterBarred]), and content
 * ([contentView], [blobBlocked]). A measure is in force while its row's `active_key` is set; every read here asks that,
 * so a lift serves the target again at once and unchanged — a block deleted nothing.
 *
 * Where a blob is asked about, the answer is derived from what the blocks name, never stored per blob: a node block
 * reaches the blobs core's attachment arm and every application's arm ([SharedSubtreeContributor]) attach under it, an
 * item block the blobs its application names ([ReportableItems.blobIdsOf]) — so an upload into a blocked node after the
 * block is refused too, and the two `presignGets` callers (`PresignGetsHonourContentBlocksTest`) need one question.
 */
@ApplicationScoped
class ModerationBlocks(
    private val arms: Instance<SharedSubtreeContributor>,
    // MutableList, not List: ArC rejects kotlin List with @All (see ScopePurge).
    @All items: MutableList<ReportableItems>,
) {
    private val items: List<ReportableItems> = items

    /** The measures in force on [targetId] of [kinds]. */
    private fun active(targetId: String, vararg kinds: BlockKind): Boolean =
        ModerationBlockEntity.count(
            "targetId = ?1 and kind in ?2 and activeKey is not null", targetId, kinds.map { it.name },
        ) > 0

    /** A link a moderator blocked, or one a blocked guest uploader came through — every guest path answers as unknown. */
    fun shareBlocked(shareId: String): Boolean =
        active(shareId, BlockKind.SHARE) ||
            ModerationBlockEntity.count("shareId = ?1 and kind = ?2 and activeKey is not null", shareId, BlockKind.GUEST_IDENTITY.name) > 0

    /** A link blocked as itself (a `SHARE` measure) — a re-issue is refused only then (decision 4: a guest uploader's
     *  block lets the link's creator issue a new link). */
    fun shareBlockedItself(shareId: String): Boolean = active(shareId, BlockKind.SHARE)

    /** A guest identity a moderator blocked: its token is refused on every write. */
    fun identityBlocked(identityId: String): Boolean = active(identityId, BlockKind.GUEST_IDENTITY)

    /** An account whose sign-in the Server Owner blocked. */
    fun signInBlocked(accountId: String): Boolean = active(accountId, BlockKind.MEMBER_SIGN_IN)

    /** A reporter a moderator barred: a member's account, or the link a guest reports through (ADR 0093 § 5). */
    fun reporterBarred(accountId: String?, shareId: String?): Boolean =
        (accountId != null && active(accountId, BlockKind.REPORTER_ACCOUNT)) || (shareId != null && active(shareId, BlockKind.REPORTER_LINK))

    /** What the content blocks of [scopeId] reach — one indexed read when none exists, which is every space but a blocked one's. */
    fun contentView(scopeId: String): ContentView {
        val rows = ModerationBlockEntity.list(
            "scopeId = ?1 and kind in ?2 and activeKey is not null",
            scopeId, listOf(BlockKind.CONTENT_NODE.name, BlockKind.CONTENT_ITEM.name),
        )
        if (rows.isEmpty()) return ContentView.NONE
        val roots = rows.filter { it.blockKind == BlockKind.CONTENT_NODE }.map { it.targetId }
        val itemIds = rows.filter { it.blockKind == BlockKind.CONTENT_ITEM }.map { it.targetId }.toSet()
        return ContentView(if (roots.isEmpty()) emptySet() else below(scopeId, roots), itemIds)
    }

    /** Whether a byte read of [blobId] in [scopeId] is refused: it hangs under a blocked node or on a blocked item. */
    fun blobBlocked(scopeId: String, blobId: String): Boolean {
        val view = contentView(scopeId)
        if (view.isEmpty) return false
        if (view.nodeIds.isNotEmpty() && arms.any { blobId in it.authorizedBlobIds(scopeId, view.nodeIds.toList()) }) return true
        return view.itemIds.any { itemId -> items.any { blobId in it.blobIdsOf(itemId) } }
    }

    /** [roots] and every node below them in [scopeId], trashed ones included (the tree by parent, breadth first). */
    private fun below(scopeId: String, roots: List<String>): Set<String> {
        val byParent = NodeEntity.find("scopeId", scopeId).list().groupBy { it.parentId }
        val out = LinkedHashSet<String>(roots)
        val queue = ArrayDeque(roots)
        while (queue.isNotEmpty()) {
            byParent[queue.removeFirst()].orEmpty().forEach { if (out.add(it.id)) queue.addLast(it.id) }
        }
        return out
    }
}
