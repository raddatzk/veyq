package me.raddatz.veyq.core.moderation.block

import me.raddatz.veyq.core.blob.BlobUploadStore
import me.raddatz.veyq.core.moderation.report.ReportableItems
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.sharing.SharedSubtreeContributor
import io.quarkus.arc.All
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance

/**
 * What the content blocks of one space reach (uc.moderation.take-a-measure step 3): every node a `CONTENT_NODE` block
 * names **and every node below it** (trashed ones included, so a restore can be refused), every item a
 * `CONTENT_ITEM` block names, and every node and item a `CONTENT_UPLOADER` block listed when it was taken. [NONE] for a space with no block — the case every listing meets.
 */
class ContentView internal constructor(
    val nodeIds: Set<String>,
    val itemIds: Set<String>,
    /** The stored objects (storage keys) a measure hangs on since its item was erased (ADR 0093 § 6): every row naming
     *  one is blocked. */
    val heldKeys: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = nodeIds.isEmpty() && itemIds.isEmpty() && heldKeys.isEmpty()

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
    private val blobs: BlobUploadStore,
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
            scopeId, listOf(BlockKind.CONTENT_NODE.name, BlockKind.CONTENT_ITEM.name, BlockKind.CONTENT_UPLOADER.name),
        )
        if (rows.isEmpty()) return ContentView.NONE
        // An uploader's block reaches exactly the items it listed when it was taken (`veyq_moderation_block_reach`).
        val reach = rows.filter { it.blockKind == BlockKind.CONTENT_UPLOADER }.map { it.id }.takeIf { it.isNotEmpty() }
            ?.let { ids -> ModerationBlockReachEntity.list("blockId in ?1", ids) }.orEmpty()
        val roots = rows.filter { it.blockKind == BlockKind.CONTENT_NODE }.map { it.targetId } +
            reach.filter { it.itemKind == ModerationBlockReachEntity.NODE }.map { it.targetId }
        val itemIds = rows.filter { it.blockKind == BlockKind.CONTENT_ITEM }.map { it.targetId }.toSet() +
            reach.filter { it.itemKind == ModerationBlockReachEntity.ITEM }.map { it.targetId }
        val held = rows.flatMap { it.heldKeys }.toSet()
        return ContentView(if (roots.isEmpty()) emptySet() else below(scopeId, roots), itemIds, held)
    }

    /** Whether an `UPLOADER_CONTENT` measure of [scopeId] is in force on [uploaderId] (an account or a guest identity). */
    fun uploaderContentBlocked(scopeId: String, uploaderId: String): Boolean =
        ModerationBlockEntity.count("activeKey", ModerationBlockEntity.activeKeyOf(BlockKind.CONTENT_UPLOADER, uploaderId, scopeId)) > 0

    /**
     * Whether a byte read of [blobId] in [scopeId] is refused: it, or another row on the same stored object, hangs under a
     * blocked node or on a blocked item. A block reaches the **object** (ADR 0013 § 5): a cover sharing a blocked
     * medium's object serves the same bytes, so it is refused with it - and still after the medium is erased: its measure
     * then hangs on the object ([ContentView.heldKeys], owner 2026-10-19) until the object's last row goes or a moderator
     * lifts it.
     */
    fun blobBlocked(scopeId: String, blobId: String): Boolean = blobId in blockedBlobs(scopeId, listOf(blobId))

    /**
     * [blobBlocked] for a batch: the ids of [blobIds] whose byte read is refused. Its reads do not grow with the batch
     * (inbox #537): the space's blocks once, then - only in a space that has one - the asked rows' objects in one read and
     * what each block reaches once, every asked id tested against those sets. Asked per id, a member's
     * `blob-downloads-batch` of 256 thumbnails was 256 reads of the blocks, and in a blocked space 256 walks of each arm.
     */
    fun blockedBlobs(scopeId: String, blobIds: Collection<String>): Set<String> {
        if (blobIds.isEmpty()) return emptySet()
        val view = contentView(scopeId)
        if (view.isEmpty) return emptySet()
        val objects = blobs.rowsOnSameObject(scopeId, blobIds)
        val underNodes = if (view.nodeIds.isEmpty()) emptySet() else {
            val nodeIds = view.nodeIds.toList()
            arms.flatMapTo(HashSet()) { arm -> arm.authorizedBlobIds(scopeId, nodeIds) }
        }
        val onItems = view.itemIds.flatMapTo(HashSet()) { itemId -> items.flatMap { item -> item.blobIdsOf(itemId) } }
        return blobIds.filterTo(LinkedHashSet()) { blobId ->
            val obj = objects[blobId]
            if (obj != null && obj.storageKey in view.heldKeys) return@filterTo true
            val rows = obj?.rowIds.orEmpty() + blobId
            rows.any { it in underNodes || it in onItems }
        }
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
