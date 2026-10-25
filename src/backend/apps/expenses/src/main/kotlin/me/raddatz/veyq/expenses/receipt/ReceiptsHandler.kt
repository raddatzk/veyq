package me.raddatz.veyq.expenses.receipt

import me.raddatz.veyq.core.blob.CompleteItemResult
import me.raddatz.veyq.core.blob.UploadCompletions
import me.raddatz.veyq.core.blob.UploadOpener
import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.content.NodesStore
import me.raddatz.veyq.core.http.ClientSeen
import me.raddatz.veyq.core.http.FailureStatus.BAD_REQUEST
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
import me.raddatz.veyq.core.moderation.block.ModerationBlocks
import me.raddatz.veyq.core.moderation.guestwrite.GuestWriteObject
import me.raddatz.veyq.core.moderation.guestwrite.GuestWriteRecords
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.platform.capabilities.ApplicationSwitch
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.ShareGuestGate
import me.raddatz.veyq.core.sharing.ShareViewGate
import me.raddatz.veyq.core.sharing.ShareWriteGate
import me.raddatz.veyq.core.sharing.ShareWriteTarget
import me.raddatz.veyq.expenses.ExpensesNodeKinds
import me.raddatz.veyq.expenses.pot.PotBlocks
import jakarta.enterprise.context.ApplicationScoped
import java.time.Duration

/** What a receipt's completion answers, the member's and the guest's alike once past their gates. */
sealed interface CompleteReceiptOutcome {
    data class Ok(val receipt: ReceiptRow) : CompleteReceiptOutcome
    object Suspended : CompleteReceiptOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : CompleteReceiptOutcome, HttpFailure by Failures.scopeDeleting
    object NoScope : CompleteReceiptOutcome, HttpFailure by Failures.scopeNotFound
    /** No live `EXPENSES_POT` of this id (in the scope, or inside the share's live subtree). */
    object NoPot : CompleteReceiptOutcome, HttpFailure by Failures.nodeNotFound
    object NoUpload : CompleteReceiptOutcome, HttpFailure by httpFailure("upload_not_found", NOT_FOUND)
    /** The session was not started for this Pot in the role `EXPENSES_RECEIPT` (ADR 0072). */
    object UploadMismatch : CompleteReceiptOutcome, HttpFailure by httpFailure("upload_mismatch", UNPROCESSABLE)
    object IdTaken : CompleteReceiptOutcome, HttpFailure by Failures.idTaken
    object StaleEpoch : CompleteReceiptOutcome, HttpFailure by Failures.staleEpoch
    object RekeyPending : CompleteReceiptOutcome, HttpFailure by Failures.nodeRekeyPending
    /** The stored chunks failed declare-then-verify; the objects are on the reclaim list. */
    object Budget : CompleteReceiptOutcome, HttpFailure by httpFailure("budget_exceeded", BAD_REQUEST)
    // The guest's gate.
    object ShareNotFound : CompleteReceiptOutcome, HttpFailure by httpFailure("share_not_found", NOT_FOUND)
    object NotContributable : CompleteReceiptOutcome, HttpFailure by httpFailure("share_not_contributable", FORBIDDEN)
    object PasswordIncorrect : CompleteReceiptOutcome, HttpFailure by httpFailure("share_password_incorrect", FORBIDDEN)
    object RateLimited : CompleteReceiptOutcome, HttpFailure by Failures.rateLimited
    /** A moderator blocked the Pot, a node above it, or the entry the receipt is for (`451`, [PotBlocks]). */
    object Blocked : CompleteReceiptOutcome, HttpFailure by Failures.blockedByOperator
}

/** What a read of a Pot's receipts answers: every receipt as it is served (the resource pages and tags them), or a refusal. */
sealed interface ListReceiptsOutcome {
    data class Listed(val receipts: List<ReceiptRow>) : ListReceiptsOutcome
    object Suspended : ListReceiptsOutcome, HttpFailure by Failures.tenantSuspended
    object NoScope : ListReceiptsOutcome, HttpFailure by Failures.scopeNotFound
    object NoPot : ListReceiptsOutcome, HttpFailure by Failures.nodeNotFound
    // The guest's gate: one non-enumerable 404 for an unknown share and a Pot outside its live subtree.
    object ShareNotFound : ListReceiptsOutcome, HttpFailure by httpFailure("share_not_found", NOT_FOUND)
    object PasswordIncorrect : ListReceiptsOutcome, HttpFailure by httpFailure("share_password_incorrect", FORBIDDEN)
    object RateLimited : ListReceiptsOutcome, HttpFailure by Failures.rateLimited
}

/**
 * A Pot's receipts, a member's and a link guest's (ADR 0108 decisions 6-7; tags `expenses` and `share-expenses`): the
 * gate first — core's scope access for a member, core's guest gate for a guest (ADR 0079 § Application guest
 * operations) — then the application switch on a write (off, Expenses is read-only), then the Pot, then core's
 * completion dispatcher, which reaches [ReceiptUploadCompletion]. The node must be a Pot before the dispatcher is asked,
 * so this route never hands an upload to another application's completion.
 */
@ApplicationScoped
class ReceiptsHandler(
    private val access: ScopeAccess,
    private val nodes: NodesStore,
    private val completions: UploadCompletions,
    private val store: PotReceiptStore,
    private val blocks: ModerationBlocks,
    private val applications: ApplicationSwitch,
    private val gate: ShareGuestGate,
    private val guestWriteRecords: GuestWriteRecords,
    config: VeyqConfig,
) {
    private val retention: Duration = config.node().trash().retention()

    /** A member's read: every receipt of the Pot, live or trashed (its trash view reads it too). */
    fun list(userId: String, scopeId: String, nodeId: String): ListReceiptsOutcome {
        access.check(userId, scopeId, READ).refusal(ListReceiptsOutcome.NoScope, ListReceiptsOutcome.Suspended)?.let { return it }
        NodeEntity.findById(nodeId)?.takeIf { it.scopeId == scopeId && it.kind == ExpensesNodeKinds.POT } ?: return ListReceiptsOutcome.NoPot
        return ListReceiptsOutcome.Listed(store.rows(store.allOf(nodeId), retention, blocks.contentView(scopeId)))
    }

    /** A guest's read: every receipt of the live Pot [nodeId] inside the share's live subtree. */
    fun listShared(shareId: String, pwToken: String?, nodeId: String): ListReceiptsOutcome {
        val viewable = when (val g = gate.viewGate(shareId, pwToken)) {
            is ShareViewGate.Viewable -> g
            ShareViewGate.WriteOnly, ShareViewGate.NotFound, ShareViewGate.Locked -> return ListReceiptsOutcome.ShareNotFound
            ShareViewGate.PasswordIncorrect -> return ListReceiptsOutcome.PasswordIncorrect
            ShareViewGate.RateLimited -> return ListReceiptsOutcome.RateLimited
        }
        if (nodeId !in viewable.liveNodeIds) return ListReceiptsOutcome.ShareNotFound
        NodeEntity.findById(nodeId)?.takeIf { it.kind == ExpensesNodeKinds.POT } ?: return ListReceiptsOutcome.ShareNotFound
        return ListReceiptsOutcome.Listed(store.rows(store.allOf(nodeId), retention, blocks.contentView(viewable.scopeId)))
    }

    /** `completePotReceipt`: the member's upload [uploadId] becomes the receipt [receiptId] of [entryId]. */
    fun complete(
        userId: String, scopeId: String, nodeId: String, entryId: String, receiptId: String, uploadId: String, nodeEpoch: Int,
    ): CompleteReceiptOutcome {
        access.check(userId, scopeId, WRITE)
            .refusal(CompleteReceiptOutcome.NoScope, CompleteReceiptOutcome.Suspended, CompleteReceiptOutcome.Deleting)?.let { return it }
        applications.requireWritable(CapabilityIds.EXPENSES, scopeId) // switched off, read-only (uc.platform.switch-application-off)
        val node = nodes.findInScope(scopeId, nodeId)?.takeIf { it.kind == ExpensesNodeKinds.POT } ?: return CompleteReceiptOutcome.NoPot
        if (PotBlocks.refusesReceipt(blocks.contentView(scopeId), node.id, entryId)) return CompleteReceiptOutcome.Blocked
        val item = ReceiptUploadCompletion.item(node.id, uploadId, receiptId, entryId, nodeEpoch)
        return outcome(scopeId, completions.complete(scopeId, listOf(item), UploadOpener.Member(userId)).single())
    }

    /**
     * `completeSharePotReceipt`: a `READ_WRITE` guest's upload — the gate (a write role, `READ_WRITE` only: a Pot is never
     * a `WRITE_ONLY` root, and a drop box's guest writes into no Pot), the switch, the target (a live Pot inside the
     * subtree), and the write, which re-decides link and target under the scope lock ([ShareGuestGate.admittedInto], handed
     * on unevaluated) and records [seen] once for the receipt it stored (ADR 0093 § 10).
     */
    fun completeShared(
        shareId: String, pwToken: String?, contributorToken: String, nodeId: String, entryId: String, receiptId: String,
        uploadId: String, nodeEpoch: Int, seen: ClientSeen?,
    ): CompleteReceiptOutcome {
        // Attaching creates the receipt row, so it spends the per-share upload budget the start spends (ADR 0051).
        val gated = when (val g = gate.writeGate(shareId, pwToken, contributorToken, spendUploadBudget = true, onlyRole = ShareEntity.READ_WRITE)) {
            is ShareWriteGate.Admitted -> g
            ShareWriteGate.NotFound -> return CompleteReceiptOutcome.ShareNotFound
            ShareWriteGate.PasswordIncorrect -> return CompleteReceiptOutcome.PasswordIncorrect
            ShareWriteGate.RateLimited -> return CompleteReceiptOutcome.RateLimited
            ShareWriteGate.NotContributable -> return CompleteReceiptOutcome.NotContributable
        }
        applications.requireWritable(CapabilityIds.EXPENSES, gated.scopeId) // switched off, read-only (uc.platform.switch-application-off)
        val target = when (val t = gate.target(gated, nodeId)) {
            is ShareWriteTarget.Node -> t
            ShareWriteTarget.NotFound -> return CompleteReceiptOutcome.NoPot
            ShareWriteTarget.NotContributable -> return CompleteReceiptOutcome.NotContributable
        }
        if (target.kind != ExpensesNodeKinds.POT) return CompleteReceiptOutcome.NoPot
        if (PotBlocks.refusesReceipt(blocks.contentView(gated.scopeId), target.nodeId, entryId)) return CompleteReceiptOutcome.Blocked
        val item = ReceiptUploadCompletion.item(target.nodeId, uploadId, receiptId, entryId, nodeEpoch)
        val result = completions.complete(
            gated.scopeId, listOf(item), UploadOpener.Contributor(gated.identityId), gate.admittedInto(gated, target),
            attached = { id -> guestWriteRecords.record(seen, gated.identityId, GuestWriteObject.ITEM, id, null) },
        ).single()
        return outcome(gated.scopeId, result)
    }

    /** A completed item as the Expenses contract answers it; a success re-reads the receipt as a read serves it. */
    private fun outcome(scopeId: String, result: CompleteItemResult): CompleteReceiptOutcome = when (result.error) {
        null -> {
            val receipt = checkNotNull(PotReceiptEntity.findById(checkNotNull(result.ownerId))) { "a receipt just attached is gone" }
            CompleteReceiptOutcome.Ok(store.rows(listOf(receipt), retention, blocks.contentView(scopeId)).single())
        }
        "node_not_found", "illegal_target" -> CompleteReceiptOutcome.NoPot
        "upload_not_found" -> CompleteReceiptOutcome.NoUpload
        "upload_mismatch" -> CompleteReceiptOutcome.UploadMismatch
        "budget_exceeded" -> CompleteReceiptOutcome.Budget
        "id_taken" -> CompleteReceiptOutcome.IdTaken
        PotReceiptStore.STALE_EPOCH -> CompleteReceiptOutcome.StaleEpoch
        PotReceiptStore.NODE_REKEY_PENDING -> CompleteReceiptOutcome.RekeyPending
        else -> error("ReceiptUploadCompletion answered a code the Expenses contract does not name: ${result.error}")
    }
}
