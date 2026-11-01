package me.raddatz.veyq.core.applicationblob

import me.raddatz.veyq.core.blob.CompleteItemResult
import me.raddatz.veyq.core.blob.UploadCompletions
import me.raddatz.veyq.core.blob.UploadOpener
import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.content.NodesStore
import me.raddatz.veyq.core.http.ClientSeen
import me.raddatz.veyq.core.http.FailureStatus.BAD_REQUEST
import me.raddatz.veyq.core.http.FailureStatus.CONFLICT
import me.raddatz.veyq.core.http.FailureStatus.FORBIDDEN
import me.raddatz.veyq.core.http.FailureStatus.NOT_FOUND
import me.raddatz.veyq.core.http.FailureStatus.UNPROCESSABLE
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.membership.AccessMode.READ
import me.raddatz.veyq.core.membership.AccessMode.WRITE
import me.raddatz.veyq.core.membership.ScopeAccess
import me.raddatz.veyq.core.membership.refusal
import me.raddatz.veyq.core.moderation.block.ContentView
import me.raddatz.veyq.core.moderation.block.ModerationBlocks
import me.raddatz.veyq.core.moderation.guestwrite.GuestWriteObject
import me.raddatz.veyq.core.moderation.guestwrite.GuestWriteRecords
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.platform.capabilities.ApplicationSwitch
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.ShareGuestGate
import me.raddatz.veyq.core.sharing.ShareViewGate
import me.raddatz.veyq.core.sharing.ShareWriteGate
import me.raddatz.veyq.core.sharing.ShareWriteTarget
import jakarta.enterprise.context.ApplicationScoped
import java.time.Duration

/** What an application blob's completion answers, the member's and the guest's alike once past their gates. */
sealed interface CompleteBlobOutcome {
    data class Ok(val blob: ApplicationBlobRow) : CompleteBlobOutcome
    object Suspended : CompleteBlobOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : CompleteBlobOutcome, HttpFailure by Failures.scopeDeleting
    object NoScope : CompleteBlobOutcome, HttpFailure by Failures.scopeNotFound
    /** No live node of this id (in the scope, or inside the share's live subtree) whose application stores blobs. */
    object NoNode : CompleteBlobOutcome, HttpFailure by Failures.nodeNotFound
    object NoUpload : CompleteBlobOutcome, HttpFailure by httpFailure("upload_not_found", NOT_FOUND)
    /** The session was not started for this node in this role, or the role is none the node's application stores
     *  through the service (ADR 0072). */
    object UploadMismatch : CompleteBlobOutcome, HttpFailure by httpFailure("upload_mismatch", UNPROCESSABLE)
    object IdTaken : CompleteBlobOutcome, HttpFailure by Failures.idTaken
    /** The owner holds its role's count of unreleased blobs already. */
    object TooMany : CompleteBlobOutcome, HttpFailure by httpFailure(ApplicationBlobUploadCompletion.TOO_MANY, CONFLICT)
    object StaleEpoch : CompleteBlobOutcome, HttpFailure by Failures.staleEpoch
    object RekeyPending : CompleteBlobOutcome, HttpFailure by Failures.nodeRekeyPending
    /** The stored chunks failed declare-then-verify; the objects are on the reclaim list. */
    object Budget : CompleteBlobOutcome, HttpFailure by httpFailure("budget_exceeded", BAD_REQUEST)
    // The guest's gate.
    object ShareNotFound : CompleteBlobOutcome, HttpFailure by httpFailure("share_not_found", NOT_FOUND)
    object NotContributable : CompleteBlobOutcome, HttpFailure by httpFailure("share_not_contributable", FORBIDDEN)
    object PasswordIncorrect : CompleteBlobOutcome, HttpFailure by httpFailure("share_password_incorrect", FORBIDDEN)
    object RateLimited : CompleteBlobOutcome, HttpFailure by Failures.rateLimited
    /** A moderator blocked the node, a node above it, or the owner the blob is for (`451`). */
    object Blocked : CompleteBlobOutcome, HttpFailure by Failures.blockedByOperator
}

/** What a read of a node's application blobs answers: every blob as it is served (the resource pages and tags them), or a refusal. */
sealed interface ListBlobsOutcome {
    data class Listed(val blobs: List<ApplicationBlobRow>) : ListBlobsOutcome
    object Suspended : ListBlobsOutcome, HttpFailure by Failures.tenantSuspended
    object NoScope : ListBlobsOutcome, HttpFailure by Failures.scopeNotFound
    object NoNode : ListBlobsOutcome, HttpFailure by Failures.nodeNotFound
    // The guest's gate: one non-enumerable 404 for an unknown share and a node outside its live subtree.
    object ShareNotFound : ListBlobsOutcome, HttpFailure by httpFailure("share_not_found", NOT_FOUND)
    object PasswordIncorrect : ListBlobsOutcome, HttpFailure by httpFailure("share_password_incorrect", FORBIDDEN)
    object RateLimited : ListBlobsOutcome, HttpFailure by Failures.rateLimited
}

/**
 * A node's application blobs, a member's and a link guest's (ADR 0112 decision 3; tags `application-blobs` and
 * `share-application-blobs`): the gate first — core's scope access for a member, core's guest gate for a guest (ADR 0079
 * § Application guest operations) — then the node, whose kind must be one an application stores blobs of through the
 * service, then on a write its application's switch (off, the application is read-only; asked by the node's kind) and a
 * block, then core's completion dispatcher, which reaches [ApplicationBlobUploadCompletion]. The node's kind is checked
 * before the dispatcher is asked, so this route never hands an upload to another completion.
 */
@ApplicationScoped
class ApplicationBlobsHandler(
    private val access: ScopeAccess,
    private val nodes: NodesStore,
    private val completions: UploadCompletions,
    private val service: ApplicationBlobs,
    private val blocks: ModerationBlocks,
    private val applications: ApplicationSwitch,
    private val gate: ShareGuestGate,
    private val guestWriteRecords: GuestWriteRecords,
    config: VeyqConfig,
) {
    private val retention: Duration = config.node().trash().retention()

    /** A member's read: every blob of the node, live or trashed (an application's trash view reads it too). */
    fun list(userId: String, scopeId: String, nodeId: String): ListBlobsOutcome {
        access.check(userId, scopeId, READ).refusal(ListBlobsOutcome.NoScope, ListBlobsOutcome.Suspended)?.let { return it }
        NodeEntity.findById(nodeId)?.takeIf { it.scopeId == scopeId && service.stores(it.kind) } ?: return ListBlobsOutcome.NoNode
        return ListBlobsOutcome.Listed(service.served(service.allOf(nodeId), retention, blocks.contentView(scopeId)))
    }

    /** A guest's read: every blob of the live node [nodeId] inside the share's live subtree. */
    fun listShared(shareId: String, pwToken: String?, nodeId: String): ListBlobsOutcome {
        val viewable = when (val g = gate.viewGate(shareId, pwToken)) {
            is ShareViewGate.Viewable -> g
            ShareViewGate.WriteOnly, ShareViewGate.NotFound, ShareViewGate.Locked -> return ListBlobsOutcome.ShareNotFound
            ShareViewGate.PasswordIncorrect -> return ListBlobsOutcome.PasswordIncorrect
            ShareViewGate.RateLimited -> return ListBlobsOutcome.RateLimited
        }
        if (nodeId !in viewable.liveNodeIds) return ListBlobsOutcome.ShareNotFound
        NodeEntity.findById(nodeId)?.takeIf { service.stores(it.kind) } ?: return ListBlobsOutcome.ShareNotFound
        return ListBlobsOutcome.Listed(service.served(service.allOf(nodeId), retention, blocks.contentView(viewable.scopeId)))
    }

    /** `completeApplicationBlob`: the member's upload [uploadId] becomes the blob [id] in [role] held by [ownerId]. */
    fun complete(
        userId: String, scopeId: String, nodeId: String, id: String, ownerId: String, role: String, uploadId: String, nodeEpoch: Int,
    ): CompleteBlobOutcome {
        access.check(userId, scopeId, WRITE)
            .refusal(CompleteBlobOutcome.NoScope, CompleteBlobOutcome.Suspended, CompleteBlobOutcome.Deleting)?.let { return it }
        val node = nodes.findInScope(scopeId, nodeId)?.takeIf { service.stores(it.kind) } ?: return CompleteBlobOutcome.NoNode
        applications.requireKindWritable(node.kind, scopeId) // switched off, read-only (uc.platform.switch-application-off)
        if (service.roleOf(node.kind, role) == null) return CompleteBlobOutcome.UploadMismatch
        if (refuses(blocks.contentView(scopeId), node.id, ownerId)) return CompleteBlobOutcome.Blocked
        val item = ApplicationBlobUploadCompletion.item(node.id, uploadId, id, ownerId, role, nodeEpoch)
        return outcome(scopeId, completions.complete(scopeId, listOf(item), UploadOpener.Member(userId)).single())
    }

    /**
     * `completeShareApplicationBlob`: a `READ_WRITE` guest's upload — the gate (a write role, `READ_WRITE` only: a guest
     * adds to a record it can read), the target (a live node inside the subtree), the switch, the role (one a guest may
     * add), and the write, which re-decides link and target under the scope lock ([ShareGuestGate.admittedInto], handed on
     * unevaluated) and records [seen] once for the blob it stored (ADR 0093 § 10).
     */
    fun completeShared(
        shareId: String, pwToken: String?, contributorToken: String, nodeId: String, id: String, ownerId: String, role: String,
        uploadId: String, nodeEpoch: Int, seen: ClientSeen?,
    ): CompleteBlobOutcome {
        // Attaching creates a blob row, so it spends the per-share upload budget the start spends (ADR 0051).
        val gated = when (val g = gate.writeGate(shareId, pwToken, contributorToken, spendUploadBudget = true, onlyRole = ShareEntity.READ_WRITE)) {
            is ShareWriteGate.Admitted -> g
            ShareWriteGate.NotFound -> return CompleteBlobOutcome.ShareNotFound
            ShareWriteGate.PasswordIncorrect -> return CompleteBlobOutcome.PasswordIncorrect
            ShareWriteGate.RateLimited -> return CompleteBlobOutcome.RateLimited
            ShareWriteGate.NotContributable -> return CompleteBlobOutcome.NotContributable
        }
        val target = when (val t = gate.target(gated, nodeId)) {
            is ShareWriteTarget.Node -> t
            ShareWriteTarget.NotFound -> return CompleteBlobOutcome.NoNode
            ShareWriteTarget.NotContributable -> return CompleteBlobOutcome.NotContributable
        }
        if (!service.stores(target.kind)) return CompleteBlobOutcome.NoNode
        applications.requireKindWritable(target.kind, gated.scopeId) // switched off, read-only (uc.platform.switch-application-off)
        if (!service.guestMayAdd(target.kind, role)) return CompleteBlobOutcome.UploadMismatch
        if (refuses(blocks.contentView(gated.scopeId), target.nodeId, ownerId)) return CompleteBlobOutcome.Blocked
        val item = ApplicationBlobUploadCompletion.item(target.nodeId, uploadId, id, ownerId, role, nodeEpoch)
        val result = completions.complete(
            gated.scopeId, listOf(item), UploadOpener.Contributor(gated.identityId), gate.admittedInto(gated, target),
            attached = { blobRowId -> guestWriteRecords.record(seen, gated.identityId, GuestWriteObject.ITEM, blobRowId, null) },
        ).single()
        return outcome(gated.scopeId, result)
    }

    /** A blob is new content: refused on a blocked node (or one above it) and on a blocked owner. */
    private fun refuses(view: ContentView, nodeId: String, ownerId: String): Boolean = !view.isEmpty && view.itemBlocked(ownerId, nodeId)

    /** A completed item as the contract answers it; a success re-reads the blob as a read serves it. */
    private fun outcome(scopeId: String, result: CompleteItemResult): CompleteBlobOutcome = when (result.error) {
        null -> {
            val row = checkNotNull(ApplicationBlobEntity.findById(checkNotNull(result.ownerId))) { "an application blob just attached is gone" }
            CompleteBlobOutcome.Ok(service.served(listOf(row), retention, blocks.contentView(scopeId)).single())
        }
        "node_not_found", "illegal_target" -> CompleteBlobOutcome.NoNode
        "upload_not_found" -> CompleteBlobOutcome.NoUpload
        "upload_mismatch" -> CompleteBlobOutcome.UploadMismatch
        "budget_exceeded" -> CompleteBlobOutcome.Budget
        "id_taken" -> CompleteBlobOutcome.IdTaken
        ApplicationBlobUploadCompletion.TOO_MANY -> CompleteBlobOutcome.TooMany
        ApplicationBlobs.STALE_EPOCH -> CompleteBlobOutcome.StaleEpoch
        ApplicationBlobs.NODE_REKEY_PENDING -> CompleteBlobOutcome.RekeyPending
        else -> error("ApplicationBlobUploadCompletion answered a code the contract does not name: ${result.error}")
    }
}
