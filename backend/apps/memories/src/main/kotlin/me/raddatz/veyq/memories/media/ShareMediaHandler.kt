package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.blob.CompleteItemResult
import me.raddatz.veyq.core.blob.DerivativeDeclare
import me.raddatz.veyq.core.blob.UploadCompletions
import me.raddatz.veyq.core.blob.UploadOpener
import me.raddatz.veyq.core.http.FailureStatus.FORBIDDEN
import me.raddatz.veyq.core.http.FailureStatus.NOT_FOUND
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.sharing.ShareGuestGate
import me.raddatz.veyq.core.sharing.ShareViewGate
import me.raddatz.veyq.core.sharing.ShareWriteGate
import jakarta.enterprise.context.ApplicationScoped

sealed interface ShareMediaListOutcome {
    /** [uploaderKeys]: identity id → wrapped identity key, the only thing a guest is served about any identity. Every
     *  [media] row's `identityId` is one of its keys or null. */
    data class Listed(val media: List<ShareMediaRow>, val uploaderKeys: Map<String, String>) : ShareMediaListOutcome
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
    /** The mode takes no guest writes, the contributor token is invalid, or the root is no Memory or chapter — a 403. */
    object NotContributable : ShareMediaCompleteOutcome, HttpFailure by httpFailure("share_not_contributable", FORBIDDEN)
    /** Password-protected + a wrong token (cr-0164) — a 403. */
    object PasswordIncorrect : ShareMediaCompleteOutcome, HttpFailure by httpFailure("share_password_incorrect", FORBIDDEN)
    /** Too many wrong password attempts (cr-0165), or the per-share upload budget spent (ADR 0051) — a 429. */
    object RateLimited : ShareMediaCompleteOutcome, HttpFailure by Failures.rateLimited
}

sealed interface ShareMediaDeleteOutcome {
    object Done : ShareMediaDeleteOutcome
    /** No such share/media, the media is not owned by this identity, or password-protected with no token
     *  (cr-0164) — a non-enumerable 404, one code for all of them. */
    object NotFound : ShareMediaDeleteOutcome, HttpFailure by httpFailure("share_media_not_found", NOT_FOUND)
    /** The mode takes no guest writes, the contributor token is invalid, or the root is no Memory or chapter — a 403. */
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
) {

    /**
     * The media under a share's live subtree and the wrapped keys of the WRITE_ONLY identities whose media those are.
     * **The privacy rule travels with the rows (ADR 0030 point 8):** a medium names its identity only where the guest
     * needs it to find that key (ADR 0031 point 7) — a READ_WRITE guest's opens under the node's content key, and its
     * id would only tell a guest which uploads came from one person. Both come from the one [keys] map, so the served
     * identities and the served ids can never disagree. A WRITE_ONLY link is served nothing (ADR 0031).
     */
    fun list(shareId: String, pwToken: String?): ShareMediaListOutcome {
        val viewable = when (val g = gate.viewGate(shareId, pwToken)) {
            is ShareViewGate.Viewable -> g
            ShareViewGate.WriteOnly -> return ShareMediaListOutcome.Listed(emptyList(), emptyMap())
            ShareViewGate.NotFound, ShareViewGate.Locked -> return ShareMediaListOutcome.NotFound
            ShareViewGate.PasswordIncorrect -> return ShareMediaListOutcome.PasswordIncorrect
            ShareViewGate.RateLimited -> return ShareMediaListOutcome.RateLimited
        }
        val rows = store.listShareMedia(viewable.scopeId, viewable.liveNodeIds)
        val keys = store.uploaderKeysOf(rows.mapNotNull { it.identityId }.toSet())
        return ShareMediaListOutcome.Listed(rows.map { if (it.identityId in keys) it else it.copy(identityId = null) }, keys)
    }

    /** Complete a guest upload — attach each item to the share's own root (a Memory or a chapter), stamped with the
     *  guest identity. */
    fun complete(shareId: String, pwToken: String?, contributorToken: String, items: List<ShareMediaItem>): ShareMediaCompleteOutcome {
        // Attaching is what creates the media rows, so it spends the per-share upload budget the start spends
        // (ADR 0051, `@RateLimit(key = SHARE)`): the two are one contributor write flow.
        val gated = when (val g = gate.writeGate(shareId, pwToken, contributorToken, spendUploadBudget = true)) {
            is ShareWriteGate.Admitted -> g
            ShareWriteGate.NotFound -> return ShareMediaCompleteOutcome.NotFound
            ShareWriteGate.PasswordIncorrect -> return ShareMediaCompleteOutcome.PasswordIncorrect
            ShareWriteGate.RateLimited -> return ShareMediaCompleteOutcome.RateLimited
            ShareWriteGate.NotContributable -> return ShareMediaCompleteOutcome.NotContributable
        }
        // A share Memories does not write under is not contributable here. The gate names the root's kind as stored,
        // so a Memory or chapter in the trash still passes: its items then answer `node_not_found` (the dispatcher takes live
        // nodes only).
        if (!guestWrites.supports(gated.rootKind)) return ShareMediaCompleteOutcome.NotContributable
        val completeItems = items.map {
            MediaUploadCompletion.item(
                mediaId = it.mediaId,
                nodeId = gated.rootNodeId, // a guest attaches to the shared root (Memory or chapter) — server-set, not client-chosen
                originalUploadId = it.originalUploadId,
                derivatives = it.derivatives,
                wrappedMeta = it.wrappedMeta,
                wrappedExif = it.wrappedExif,
            )
        }
        // Each item's attach re-checks the link first ([ShareWriteGate.Admitted.admitted], under the row lock its
        // delete holds): gated only above, an upload landed after a delete counted the identity's media and dropped
        // it — a photo nobody could open. Refused, the item answers `upload_not_found`. Handed on **unevaluated**:
        // `MediaStore.attach` asks it first in its transaction; asked here, a delete committing in between is missed
        // (`GuestCompleteAfterDeleteRaceSliceTest`).
        return ShareMediaCompleteOutcome.Results(
            completions.complete(gated.scopeId, completeItems, UploadOpener.Contributor(gated.identityId), gated.admitted),
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
        if (!guestWrites.supports(gated.rootKind)) return ShareMediaDeleteOutcome.NotContributable
        if (!guestWrites.deleteOwn(gated.scopeId, mediaId, gated.identityId)) return ShareMediaDeleteOutcome.NotFound
        return ShareMediaDeleteOutcome.Done
    }
}
