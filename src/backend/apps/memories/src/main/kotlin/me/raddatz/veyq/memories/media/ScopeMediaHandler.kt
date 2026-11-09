package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.content.NodesStore
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.identity.PrincipalKey
import me.raddatz.veyq.core.items.ItemsRevisions
import me.raddatz.veyq.core.items.NodeItemsRevisions
import me.raddatz.veyq.core.membership.AccessMode.READ
import me.raddatz.veyq.core.membership.ScopeAccess
import me.raddatz.veyq.core.membership.refusal
import me.raddatz.veyq.core.moderation.block.ContentView
import me.raddatz.veyq.core.moderation.block.ModerationBlocks
import me.raddatz.veyq.core.sharing.ShareETag
import jakarta.enterprise.context.ApplicationScoped
import java.security.MessageDigest

sealed interface ListScopeMediaOutcome {
    /**
     * One page of the scope's live media ([rows], in id order) and what opens a WRITE_ONLY guest's row among them:
     * [dropKeys] — the sealed drop private key of each node of the page that has one — and [uploaderKeys] — the wrapped
     * key of each WRITE_ONLY identity that owns a row of the page (ADR 0031 point 7). [blocked]: the ids a measure marks.
     * [etag] is set on a first page (no `after`) only.
     */
    data class Page(
        val rows: List<ShareMediaRow>,
        val blocked: Set<String>,
        val dropKeys: Map<String, String>,
        val uploaderKeys: Map<String, String>,
        val more: Boolean,
        val etag: String?,
    ) : ListScopeMediaOutcome

    /** The client's `If-None-Match` names the scope's tag: nothing the listing depends on moved, and no media was read. */
    data class NotModified(val etag: String) : ListScopeMediaOutcome
    object Suspended : ListScopeMediaOutcome, HttpFailure by Failures.tenantSuspended
    object NoScope : ListScopeMediaOutcome, HttpFailure by Failures.scopeNotFound
}

/**
 * A member's listing of every live medium of a space (`listScopeMedia`, ADR 0125 decision 8a): what the search opens to
 * find a photo of any Memory by its capture time, place and name. Scope READ is the one gate ([ScopeAccess]), asked first;
 * a non-member gets the top-level 404, never a reveal.
 *
 * **The tag ([ScopeMediaTag]) is derived, never a digest of the rows**: a member is told the item revisions already
 * (`listNodeMedia`'s `ETag`), so the tag may be a function of what the listing depends on — the scope's tree revision (a
 * node trashed or restored moves it), every live node's item revisions (every writer of a medium bumps its node's,
 * `NodeItemsRevisionBumpsTest`), and the scope's content blocks (a block marks a row and bumps nothing). That costs an idle
 * read three small reads and no media query, and needs no cache. **The key is read before the rows** (ADR 0081 decision
 * 3's order): a change committing in between lands under the older tag, which the client then keeps, so its next read
 * differs and pulls it.
 */
@ApplicationScoped
class ScopeMediaHandler(
    private val store: MediaStore,
    private val access: ScopeAccess,
    private val nodes: NodesStore,
    private val itemsRevisions: NodeItemsRevisions,
    private val blocks: ModerationBlocks,
) {
    fun list(userId: String, scopeId: String, after: String?, limit: Int, ifNoneMatch: String?): ListScopeMediaOutcome {
        access.check(userId, scopeId, READ).refusal(ListScopeMediaOutcome.NoScope, ListScopeMediaOutcome.Suspended)?.let { return it }
        val view = blocks.contentView(scopeId)
        val etag = if (after == null) ScopeMediaTag.of(nodes.treeRevision(scopeId), itemsRevisions.revisionsOfLiveNodes(scopeId), view) else null
        if (etag != null && ShareETag.matches(ifNoneMatch, etag)) return ListScopeMediaOutcome.NotModified(etag)
        val page = store.listScopeMedia(scopeId, after, limit)
        val rows = page.items
        val uploaderKeys = store.uploaderKeys(rows.map { it.row })
        return ListScopeMediaOutcome.Page(
            rows = rows,
            blocked = rows.filter { view.itemBlocked(it.row.mediaId, it.nodeId) }.mapTo(HashSet()) { it.row.mediaId },
            dropKeys = store.dropKeysOf(rows.map { it.nodeId }),
            uploaderKeys = uploaderKeys,
            more = page.more,
            etag = etag,
        )
    }

    companion object {
        /** The WRITE_ONLY identity a row's `owner_key` names (`share:{identityId}`), or null — `uploaderKeys`' own reading. */
        fun identityOf(row: ShareMediaRow): String? = row.row.ownerKey?.let { (PrincipalKey.parse(it) as? PrincipalKey.Share)?.identityId }
    }
}

/** `listScopeMedia`'s `ETag` (see [ScopeMediaHandler]): truncated SHA-256 of everything the listing is a function of, quoted. */
internal object ScopeMediaTag {
    fun of(treeRevision: Long, liveRevisions: Map<String, ItemsRevisions>, view: ContentView): String {
        val sha = MessageDigest.getInstance("SHA-256")
        fun line(s: String) = sha.update((s + "\n").toByteArray(Charsets.UTF_8))
        line("tree:$treeRevision")
        for ((nodeId, r) in liveRevisions.toSortedMap()) line("items:$nodeId:${r.revision}:${r.deleteRevision}")
        for (nodeId in view.nodeIds.sorted()) line("blocked-node:$nodeId")
        for (itemId in view.itemIds.sorted()) line("blocked-item:$itemId")
        return "\"" + sha.digest().take(16).joinToString("") { "%02x".format(it) } + "\""
    }
}
