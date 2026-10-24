package me.raddatz.veyq.expenses.pot

import me.raddatz.veyq.core.http.ClientSeen
import me.raddatz.veyq.core.http.FailureStatus.FORBIDDEN
import me.raddatz.veyq.core.http.FailureStatus.NOT_FOUND
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.identity.PrincipalKey
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
import jakarta.enterprise.context.ApplicationScoped

sealed interface SharePotListOutcome {
    /** Every revision of the Pot in the guest's order; the resource pages and tags them. */
    data class Listed(val revisions: List<PotRevisionEntity>) : SharePotListOutcome

    /** Unknown, disabled, expired or deleted share, a space that serves no guests, a `WRITE_ONLY` link, a required
     *  password absent, or no live Pot of that id in the live shared subtree — one non-enumerable 404. */
    object NotFound : SharePotListOutcome, HttpFailure by httpFailure("share_not_found", NOT_FOUND)
    object PasswordIncorrect : SharePotListOutcome, HttpFailure by httpFailure("share_password_incorrect", FORBIDDEN)
    object RateLimited : SharePotListOutcome, HttpFailure by Failures.rateLimited
}

sealed interface SharePotAppendOutcome {
    /** Past the gate: the store's answer, worded as a member's append. */
    data class Written(val outcome: PotWriteOutcome) : SharePotAppendOutcome
    object NotFound : SharePotAppendOutcome, HttpFailure by httpFailure("share_not_found", NOT_FOUND)
    /** A link that does not write (`READ`, `WRITE_ONLY`), an invalid token, or the root in the trash — a 403. */
    object NotContributable : SharePotAppendOutcome, HttpFailure by httpFailure("share_not_contributable", FORBIDDEN)
    object PasswordIncorrect : SharePotAppendOutcome, HttpFailure by httpFailure("share_password_incorrect", FORBIDDEN)
    object RateLimited : SharePotAppendOutcome, HttpFailure by Failures.rateLimited
    object Invalid : SharePotAppendOutcome, HttpFailure by Failures.invalidRequest
}

/**
 * A link guest's Pot (tag `share-expenses`, ADR 0108 decision 6): core's guest gate first ([ShareGuestGate], ADR 0079
 * § Application guest operations), then Expenses' work. A guest reads every revision without its writer — the guest's
 * shape has no such field (inbox #190) — and a `READ_WRITE` guest appends revisions as a member does, each recorded as
 * one guest write (ADR 0093 § 10). A guest erases nothing: there is no guest erasure.
 */
@ApplicationScoped
class SharePotsHandler(
    private val gate: ShareGuestGate,
    private val store: PotRevisionStore,
    private val guestWriteRecords: GuestWriteRecords,
    private val applications: ApplicationSwitch,
) {

    /** Every revision of the live Pot [nodeId] inside the share's live subtree. */
    fun list(shareId: String, pwToken: String?, nodeId: String): SharePotListOutcome {
        val viewable = when (val g = gate.viewGate(shareId, pwToken)) {
            is ShareViewGate.Viewable -> g
            ShareViewGate.WriteOnly, ShareViewGate.NotFound, ShareViewGate.Locked -> return SharePotListOutcome.NotFound
            ShareViewGate.PasswordIncorrect -> return SharePotListOutcome.PasswordIncorrect
            ShareViewGate.RateLimited -> return SharePotListOutcome.RateLimited
        }
        if (nodeId !in viewable.liveNodeIds) return SharePotListOutcome.NotFound
        NodeEntity.findById(nodeId)?.takeIf { it.kind == ExpensesNodeKinds.POT } ?: return SharePotListOutcome.NotFound
        return SharePotListOutcome.Listed(store.allInEntryOrder(nodeId))
    }

    /**
     * A `READ_WRITE` guest's batch: the gate (a write role, `READ_WRITE` only — a Pot is never a `WRITE_ONLY` root, and a
     * drop box's guest writes into no Pot), the switch, the target — a live Pot inside the subtree — and the write, which
     * re-decides link and target under the scope lock ([ShareGuestGate.admittedInto], handed on unevaluated) and records
     * [seen] once per revision it stored.
     */
    fun append(
        shareId: String, pwToken: String?, contributorToken: String, nodeId: String, nodeEpoch: Int, writes: List<RevisionWrite>,
        seen: ClientSeen?,
    ): SharePotAppendOutcome {
        if (!PotRequests.valid(writes)) return SharePotAppendOutcome.Invalid
        // A batch creates rows, so it spends the per-share upload budget a guest's other writes spend (ADR 0051).
        val gated = when (val g = gate.writeGate(shareId, pwToken, contributorToken, spendUploadBudget = true, onlyRole = ShareEntity.READ_WRITE)) {
            is ShareWriteGate.Admitted -> g
            ShareWriteGate.NotFound -> return SharePotAppendOutcome.NotFound
            ShareWriteGate.PasswordIncorrect -> return SharePotAppendOutcome.PasswordIncorrect
            ShareWriteGate.RateLimited -> return SharePotAppendOutcome.RateLimited
            ShareWriteGate.NotContributable -> return SharePotAppendOutcome.NotContributable
        }
        applications.requireWritable(CapabilityIds.EXPENSES, gated.scopeId) // switched off, read-only (uc.platform.switch-application-off)
        val target = when (val t = gate.target(gated, nodeId)) {
            is ShareWriteTarget.Node -> t
            ShareWriteTarget.NotFound -> return SharePotAppendOutcome.Written(PotWriteOutcome.NoPot)
            ShareWriteTarget.NotContributable -> return SharePotAppendOutcome.NotContributable
        }
        if (target.kind != ExpensesNodeKinds.POT) return SharePotAppendOutcome.Written(PotWriteOutcome.NoPot)
        val result = store.append(
            gated.scopeId, target.nodeId, nodeEpoch, writes, PrincipalKey.Share(gated.identityId),
            admitted = gate.admittedInto(gated, target),
            stored = { rowIds -> rowIds.forEach { guestWriteRecords.record(seen, gated.identityId, GuestWriteObject.ITEM, it, null) } },
        )
        return SharePotAppendOutcome.Written(PotRequests.outcome(result))
    }
}
