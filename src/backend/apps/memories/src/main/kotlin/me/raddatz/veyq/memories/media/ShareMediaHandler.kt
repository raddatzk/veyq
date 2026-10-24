package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.platform.capabilities.ApplicationSwitch
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds
import me.raddatz.veyq.core.blob.CompleteItemResult
import me.raddatz.veyq.core.blob.DerivativeDeclare
import me.raddatz.veyq.core.blob.UploadCompletions
import me.raddatz.veyq.core.blob.UploadOpener
import me.raddatz.veyq.core.http.FailureStatus.FORBIDDEN
import me.raddatz.veyq.core.http.FailureStatus.NOT_FOUND
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.ClientSeen
import me.raddatz.veyq.core.moderation.guestwrite.GuestWriteObject
import me.raddatz.veyq.core.moderation.guestwrite.GuestWriteRecords
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.sharing.ShareGuestGate
import me.raddatz.veyq.core.sharing.ShareViewGate
import me.raddatz.veyq.core.sharing.ShareWriteGate
import me.raddatz.veyq.core.sharing.ShareWriteTarget
import me.raddatz.veyq.core.moderation.block.ContentView
import me.raddatz.veyq.core.moderation.block.ModerationBlocks
import me.raddatz.veyq.core.items.ItemsPage
import me.raddatz.veyq.core.items.NodeItemsRevisions
import me.raddatz.veyq.core.sharing.ShareETag
import me.raddatz.veyq.core.node.rekey.NodeEpochGate
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped

sealed interface ShareMediaListOutcome {
    /** [uploaderKeys]: identity id → wrapped identity key, the only thing a guest is served about any identity. Every
     *  [media] row's `identityId` is one of its keys or null. */
    /** [blocked] holds the media a moderator blocked (uc.moderation.take-a-measure): kept in [media], marked. */
    /** [more]: media remain past this page (ADR 0033 decision 6) — the guest asks again after the last one. */
    /** [etag]: the digest of the whole listing ([ShareMediaTag]) on a read without `after`; null on a later page. */
    data class Listed(
        val media: List<ShareMediaRow>,
        val uploaderKeys: Map<String, String>,
        val blocked: Set<String> = emptySet(),
        val more: Boolean = false,
        val etag: String? = null,
    ) : ShareMediaListOutcome
    /** The guest's `If-None-Match` names what the read would serve now (inbox #497): a `304`, no page read. */
    data class NotModified(val etag: String) : ShareMediaListOutcome
    /** Unknown, disabled, expired or deleted share, a space that serves no guests, or password-protected with no token
     *  — the non-enumerable 404 (the password challenge is the resolve's locked shell; this read serves none). */
    object NotFound : ShareMediaListOutcome, HttpFailure by httpFailure("share_not_found", NOT_FOUND)
    /** Password-protected + a wrong token (cr-0118) — a 403. */
    object PasswordIncorrect : ShareMediaListOutcome, HttpFailure by httpFailure("share_password_incorrect", FORBIDDEN)
    /** Too many wrong password attempts on this share (cr-0165) — a 429. */
    object RateLimited : ShareMediaListOutcome, HttpFailure by Failures.rateLimited
}

sealed interface ShareMediaCompleteOutcome {
    data class Results(val items: List<CompleteItemResult>) : ShareMediaCompleteOutcome
    /** Unknown, disabled, expired or deleted share, a space that serves no guests, or password-protected with no
     *  token (cr-0164) — the non-enumerable 404. */
    object NotFound : ShareMediaCompleteOutcome, HttpFailure by httpFailure("share_not_found", NOT_FOUND)
    /** The mode takes no guest writes, the contributor token is invalid, the target is no Memory or chapter, or the
     *  gate refused the target ([ShareWriteTarget.NotContributable]) — a 403. */
    object NotContributable : ShareMediaCompleteOutcome, HttpFailure by httpFailure("share_not_contributable", FORBIDDEN)
    /** The named target is no live node inside the share's subtree — a 404, as the guest transport answers it. */
    object NodeNotFound : ShareMediaCompleteOutcome, HttpFailure by Failures.nodeNotFound
    /** Password-protected + a wrong token (cr-0164) — a 403. */
    object PasswordIncorrect : ShareMediaCompleteOutcome, HttpFailure by httpFailure("share_password_incorrect", FORBIDDEN)
    /** Too many wrong password attempts (cr-0165), or the per-share upload budget spent (ADR 0051) — a 429. */
    object RateLimited : ShareMediaCompleteOutcome, HttpFailure by Failures.rateLimited
    /** The batch's `nodeEpoch` is not the target node's stored epoch (ADR 0109 point 6) — nothing attached; re-read. */
    object StaleEpoch : ShareMediaCompleteOutcome, HttpFailure by Failures.staleEpoch
    /** The target node is not re-keyed since the scope's last rotation yet (ADR 0109 point 6) — nothing attached; retry. */
    object RekeyPending : ShareMediaCompleteOutcome, HttpFailure by Failures.nodeRekeyPending
}

sealed interface ShareMediaDeleteOutcome {
    object Done : ShareMediaDeleteOutcome
    /** No such share/media, the media is not owned by this identity, or password-protected with no token
     *  (cr-0164) — a non-enumerable 404, one code for all of them. */
    object NotFound : ShareMediaDeleteOutcome, HttpFailure by httpFailure("share_media_not_found", NOT_FOUND)
    /** The mode takes no guest writes, or the contributor token is invalid — a 403. */
    object NotContributable : ShareMediaDeleteOutcome, HttpFailure by httpFailure("share_not_contributable", FORBIDDEN)
    /** Password-protected + a wrong token (cr-0164) — a 403. */
    object PasswordIncorrect : ShareMediaDeleteOutcome, HttpFailure by httpFailure("share_password_incorrect", FORBIDDEN)
    /** Too many wrong password attempts on this share (cr-0165) — a 429. */
    object RateLimited : ShareMediaDeleteOutcome, HttpFailure by Failures.rateLimited
}

/** One guest upload to complete: the original session + its role-tagged derivatives. The node is server-set. */
data class ShareMediaItem(
    val mediaId: String,
    val originalUploadId: String,
    val derivatives: List<DerivativeDeclare>,
    val wrappedMeta: String?,
    val wrappedExif: String?,
)

/**
 * Memories' guest operations on a share link (uc.sharing.share-public-viewer, uc.sharing.share-contributor,
 * uc.sharing.share-uploader; ADR 0029/0030/0031): list the media under the shared subtree, complete a guest's uploads
 * into media on the shared Memory or chapter, and delete one's own. Each passes core's guest gate first
 * ([ShareGuestGate.viewGate] / [ShareGuestGate.writeGate], ADR 0079 § Application guest operations) and maps its
 * refusal onto this operation's own answer — for the writes, the statuses and codes they answered while core served
 * them; for the list, those of the resolve it was part of and of the blob GET for a missing password.
 */
@ApplicationScoped
class ShareMediaHandler(
    private val gate: ShareGuestGate,
    private val completions: UploadCompletions,
    private val guestWrites: MediaShareGuestWrites,
    private val store: MediaStore,
    private val blocks: ModerationBlocks,
    private val guestWriteRecords: GuestWriteRecords,
    private val items: NodeItemsRevisions,
    private val tags: ShareMediaTags,
    private val epochs: NodeEpochGate,
    private val applications: ApplicationSwitch,
) {

    /**
     * The media under a share's live subtree and the wrapped keys of the WRITE_ONLY identities whose media those are.
     * **The privacy rule travels with the rows (ADR 0030 point 8):** a medium names its identity only where the guest
     * needs it to find that key (ADR 0031 point 7) — a READ_WRITE guest's opens under the node's content key, and its
     * id would only tell a guest which uploads came from one person. Both come from the one [keys] map, so the served
     * identities and the served ids can never disagree. A WRITE_ONLY link is served nothing (ADR 0031).
     *
     * One page of at most [limit] media past [after], in id order (ADR 0033 decision 6), of the whole subtree or — with
     * [nodeId] — of the one node of it the guest has open; a node outside the live subtree is the same 404 as an
     * unknown share, so the read tells nothing about a node the link does not serve.
     *
     * **The live refresh (inbox #497, ADR 0081 decision 4).** A read without [after] is conditional: its tag is a digest
     * of the whole listing as served ([ShareMediaTag]), never a revision, so a guest polling it learns of a change only
     * when what it is shown changed. [ifNoneMatch] equal to it answers [ShareMediaListOutcome.NotModified] without a
     * page. The digest is remembered by what it is a function of ([ShareMediaTags]): an idle poll reads the gate, the
     * blocks and the asked nodes' item revisions; only a changed key reads the whole listing — and then serves the first
     * page from that read rather than a second one.
     */
    fun list(shareId: String, pwToken: String?, nodeId: String?, after: String?, limit: Int, ifNoneMatch: String? = null): ShareMediaListOutcome {
        val viewable = when (val g = gate.viewGate(shareId, pwToken)) {
            is ShareViewGate.Viewable -> g
            ShareViewGate.WriteOnly -> return when {
                after != null -> ShareMediaListOutcome.Listed(emptyList(), emptyMap())
                ShareETag.matches(ifNoneMatch, NOTHING) -> ShareMediaListOutcome.NotModified(NOTHING)
                else -> ShareMediaListOutcome.Listed(emptyList(), emptyMap(), etag = NOTHING)
            }
            ShareViewGate.NotFound, ShareViewGate.Locked -> return ShareMediaListOutcome.NotFound
            ShareViewGate.PasswordIncorrect -> return ShareMediaListOutcome.PasswordIncorrect
            ShareViewGate.RateLimited -> return ShareMediaListOutcome.RateLimited
        }
        val nodeIds = when {
            nodeId == null -> viewable.liveNodeIds
            nodeId in viewable.liveNodeIds -> listOf(nodeId)
            else -> return ShareMediaListOutcome.NotFound
        }
        val view = blocks.contentView(viewable.scopeId)
        if (after != null) {
            val page = store.listShareMedia(viewable.scopeId, nodeIds, after, limit)
            return served(page.items, view).page(limit, page.more, etag = null)
        }
        // The key before the rows (ADR 0081 decision 3's order): see ShareMediaTags.
        val revisions = items.revisionsOf(nodeIds)
        val key = ShareMediaTags.Key(
            nodes = nodeIds.sorted().map { it to revisions.getValue(it) },
            blockedNodes = nodeIds.filterTo(HashSet()) { view.nodeBlocked(it) },
            blockedItems = view.itemIds,
        )
        val known = tags.get(key)
        if (known != null) {
            if (ShareETag.matches(ifNoneMatch, known)) return ShareMediaListOutcome.NotModified(known)
            val page = store.listShareMedia(viewable.scopeId, nodeIds, null, limit)
            return served(page.items, view).page(limit, page.more, known)
        }
        val whole = served(wholeListing(viewable.scopeId, nodeIds), view)
        val etag = ShareMediaTag.of(whole.media.map { ShareMediaWire.media(it, it.row.mediaId in whole.blocked) }, ShareMediaWire.identities(whole.keys))
        tags.put(key, etag)
        if (ShareETag.matches(ifNoneMatch, etag)) return ShareMediaListOutcome.NotModified(etag)
        return whole.page(limit, whole.media.size > limit, etag)
    }

    /** Every page of the listing of [nodeIds], at the cap — what the tag is a digest of. */
    private fun wholeListing(scopeId: String, nodeIds: List<String>): List<ShareMediaRow> {
        val all = ArrayList<ShareMediaRow>()
        var after: String? = null
        do {
            val page = store.listShareMedia(scopeId, nodeIds, after, ItemsPage.CAP)
            all += page.items
            after = page.items.lastOrNull()?.row?.mediaId
        } while (page.more && after != null)
        return all
    }

    /** [rows] as a guest is served them: a medium names its identity only where its wrapped key is served beside it (see
     *  [list]), and a blocked one is marked. */
    private fun served(rows: List<ShareMediaRow>, view: ContentView): Served {
        val keys = store.uploaderKeysOf(rows.mapNotNull { it.identityId }.toSet())
        val blocked = rows.filter { view.itemBlocked(it.row.mediaId, it.nodeId) }.mapTo(HashSet()) { it.row.mediaId }
        return Served(rows.map { if (it.identityId in keys) it else it.copy(identityId = null) }, keys, blocked)
    }

    private class Served(val media: List<ShareMediaRow>, val keys: Map<String, String>, val blocked: Set<String>) {
        /** The first [limit] of these as one page, with only the identities and marks of its own rows. */
        fun page(limit: Int, more: Boolean, etag: String?): ShareMediaListOutcome.Listed {
            val rows = media.take(limit)
            val named = rows.mapNotNullTo(HashSet()) { it.identityId }
            val ids = rows.mapTo(HashSet()) { it.row.mediaId }
            return ShareMediaListOutcome.Listed(rows, keys.filterKeys { it in named }, blocked.filterTo(HashSet()) { it in ids }, more, etag)
        }
    }

    private companion object {
        /** A `WRITE_ONLY` link's tag: it is served nothing, so its tag never changes. */
        val NOTHING = ShareMediaTag.of(emptyList(), emptyList())
    }

    /**
     * Complete a guest upload — attach each item to the target node, stamped with the guest identity: the share's root
     * (a Memory or a chapter) when [nodeId] is null, else the Memory or chapter below the root the guest names (ADR 0080
     * decision 6, [ShareGuestGate.target]; a `WRITE_ONLY` link only its root). The kind decides as the start's did: no
     * Memory or chapter, not contributable. Each medium attached records [seen] in its attach's transaction (the
     * guest-write record, ADR 0093 § 10: the upload and the meta it carries are the guest's).
     *
     * [nodeEpoch] is the target node's epoch as the guest read it (ADR 0109 point 6) — stated by a `WRITE_ONLY` guest
     * too, whose wraps are under its own `K_up`. The batch is refused whole (`409`) when the target refuses it now, and
     * each item's attach asks again in its own transaction, under the node's row lock after the scope and link locks
     * ([MediaStore.attach]): a re-key committing between two items refuses the rest per item, nothing of them stored.
     */
    fun complete(
        shareId: String, pwToken: String?, contributorToken: String, items: List<ShareMediaItem>, nodeId: String?,
        seen: ClientSeen?, nodeEpoch: Int,
    ): ShareMediaCompleteOutcome {
        // Attaching is what creates the media rows, so it spends the per-share upload budget the start spends
        // (ADR 0051, `@RateLimit(key = SHARE)`): the two are one contributor write flow.
        val gated = when (val g = gate.writeGate(shareId, pwToken, contributorToken, spendUploadBudget = true)) {
            is ShareWriteGate.Admitted -> g
            ShareWriteGate.NotFound -> return ShareMediaCompleteOutcome.NotFound
            ShareWriteGate.PasswordIncorrect -> return ShareMediaCompleteOutcome.PasswordIncorrect
            ShareWriteGate.RateLimited -> return ShareMediaCompleteOutcome.RateLimited
            ShareWriteGate.NotContributable -> return ShareMediaCompleteOutcome.NotContributable
        }
        applications.requireWritable(CapabilityIds.MEMORIES, gated.scopeId) // switched off, read-only (uc.platform.switch-application-off)
        val target = when (val t = gate.target(gated, nodeId)) {
            is ShareWriteTarget.Node -> t
            ShareWriteTarget.NotFound -> return ShareMediaCompleteOutcome.NodeNotFound
            ShareWriteTarget.NotContributable -> return ShareMediaCompleteOutcome.NotContributable
        }
        // A node Memories does not write into is not contributable here — a folder, the root of a folder link included.
        // With no node named the gate names the root's kind as stored, so a Memory or chapter in the trash still
        // passes: its items then answer `node_not_found` (the dispatcher takes live nodes only).
        if (!guestWrites.supports(target.kind)) return ShareMediaCompleteOutcome.NotContributable
        // The batch's answer (the contract's top-level 409); the write's own check is each attach's, below.
        when (QuarkusTransaction.requiringNew().call { epochs.check(gated.scopeId, target.nodeId, nodeEpoch) }) {
            NodeEpochGate.Verdict.OK -> Unit
            NodeEpochGate.Verdict.STALE -> return ShareMediaCompleteOutcome.StaleEpoch
            NodeEpochGate.Verdict.PENDING -> return ShareMediaCompleteOutcome.RekeyPending
            NodeEpochGate.Verdict.MISSING -> return ShareMediaCompleteOutcome.NodeNotFound
        }
        val completeItems = items.map {
            MediaUploadCompletion.item(
                mediaId = it.mediaId,
                nodeId = target.nodeId, // the root, or the node inside the subtree the gate checked — re-decided in the write
                originalUploadId = it.originalUploadId,
                derivatives = it.derivatives,
                wrappedMeta = it.wrappedMeta,
                wrappedExif = it.wrappedExif,
                nodeEpoch = nodeEpoch,
            )
        }
        // Each item's attach re-checks first, in its own transaction ([ShareGuestGate.admittedInto]): the scope lock,
        // then the link ([ShareWriteGate.Admitted.admitted], under the row lock its delete holds), then a named target
        // still live inside the subtree. Gated only above, an upload landed after a delete counted the identity's media
        // and dropped it — a photo nobody could open — and one into a Memory trashed or moved out of the folder since
        // landed where the link no longer reaches. Refused, the item answers `upload_not_found`. Handed on
        // **unevaluated**: `MediaStore.attach` asks it first in its transaction; asked here, a delete, a trash or a move
        // committing in between is missed (`GuestCompleteAfterDeleteRaceSliceTest`, `GuestCompleteTargetRaceSliceTest`).
        return ShareMediaCompleteOutcome.Results(
            completions.complete(
                gated.scopeId, completeItems, UploadOpener.Contributor(gated.identityId), gate.admittedInto(gated, target),
                attached = { mediaId -> guestWriteRecords.record(seen, gated.identityId, GuestWriteObject.ITEM, mediaId, null) },
            ),
        )
    }

    /**
     * Delete one of your own contributions (cr-0102 delete-own) — [MediaShareGuestWrites.deleteOwn]'s single-row
     * ownership check, never gallery filtering. A foreign or unknown media is the non-enumerable 404.
     */
    fun delete(shareId: String, pwToken: String?, contributorToken: String, mediaId: String): ShareMediaDeleteOutcome {
        // A delete spends no upload budget (`@RateLimitExempt` on the route): it is bounded by what the caller added.
        val gated = when (val g = gate.writeGate(shareId, pwToken, contributorToken, spendUploadBudget = false)) {
            is ShareWriteGate.Admitted -> g
            ShareWriteGate.NotFound -> return ShareMediaDeleteOutcome.NotFound
            ShareWriteGate.PasswordIncorrect -> return ShareMediaDeleteOutcome.PasswordIncorrect
            ShareWriteGate.RateLimited -> return ShareMediaDeleteOutcome.RateLimited
            ShareWriteGate.NotContributable -> return ShareMediaDeleteOutcome.NotContributable
        }
        // Dispatched by the kind of the medium's node, not the root's (ADR 0080 decision 6): a folder link's guest deletes
        // its own in the Memories below — `deleteOwn` checks the kind and the subtree with the ownership, one 404 for all.
        if (!guestWrites.deleteOwn(gated.scopeId, gated.rootNodeId, mediaId, gated.identityId)) return ShareMediaDeleteOutcome.NotFound
        return ShareMediaDeleteOutcome.Done
    }
}
