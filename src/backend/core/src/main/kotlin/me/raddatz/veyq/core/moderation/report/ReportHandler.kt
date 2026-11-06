package me.raddatz.veyq.core.moderation.report

import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.content.NodesStore
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.FailureStatus
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.membership.AccessMode
import me.raddatz.veyq.core.membership.ScopeAccess
import me.raddatz.veyq.core.membership.refusal
import me.raddatz.veyq.core.moderation.ModerationEpochEntity
import me.raddatz.veyq.core.moderation.ModerationEvent
import me.raddatz.veyq.core.moderation.ModerationLog
import me.raddatz.veyq.core.moderation.block.ModerationBlocks
import me.raddatz.veyq.core.moderation.hold.HoldTarget
import me.raddatz.veyq.core.moderation.hold.HoldTrigger
import me.raddatz.veyq.core.moderation.hold.Holds
import me.raddatz.veyq.core.node.CoreNodeKinds
import me.raddatz.veyq.core.sharing.ShareAccessPolicy
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.ShareGuestGate
import me.raddatz.veyq.core.sharing.SharingStore
import io.quarkus.arc.All
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import java.time.Instant
import java.util.UUID

/** A report as the wire hands it over, its shape already bounded by the contract. */
data class ReportIn(
    val category: ReportCategory,
    val epoch: Int,
    val sealedReport: String,
    val items: List<ReportItemIn>,
    val sealedContact: String? = null,
    val receiptAddress: String?,
)

/** One reported item; [id] is null for a LINK (the link the request comes through). */
data class ReportItemIn(val kind: ReportItemKind, val id: String?, val sealedShare: String)

/** A case the report opened or attached to — what the mail and the hold act on after the commit. */
data class ReportedCase(val caseId: String, val opened: Boolean, val category: ReportCategory)

/** What a report answers. */
sealed interface ReportOutcome {
    data class Received(val reportId: String, val receivedAt: Instant, val cases: List<ReportedCase>) : ReportOutcome
    data class Refused(val failure: HttpFailure) : ReportOutcome, HttpFailure by failure
}

/** Who reports: a member through their space, or a guest through a link (with its password and write proof). */
sealed interface Reporter {
    data class Member(val userId: String, val scopeId: String) : Reporter
    data class Guest(val shareId: String, val pwToken: String?, val writeToken: String?) : Reporter
}

object ReportFailures {
    val invalid = httpFailure("invalid_request", FailureStatus.BAD_REQUEST)
    /** A kind and id named twice: a rule across the items no schema states (inbox #507), so not the shape's `400`. */
    val itemRepeated = httpFailure("report_item_repeated", FailureStatus.UNPROCESSABLE)
    val itemNotFound = httpFailure("report_item_not_found", FailureStatus.NOT_FOUND)
    val epochChanged = httpFailure("moderation_epoch_changed", FailureStatus.CONFLICT)
    val tooManyOpen = httpFailure("too_many_open_reports", FailureStatus.RATE_LIMITED)
    val rateLimited = httpFailure("rate_limited", FailureStatus.RATE_LIMITED)
    val shareNotFound = httpFailure("share_not_found", FailureStatus.NOT_FOUND)
    val sharePasswordIncorrect = httpFailure("share_password_incorrect", FailureStatus.FORBIDDEN)
    val reporterBarred = httpFailure("reporter_barred", FailureStatus.FORBIDDEN)
    /** A `NODE` item that is a bookmark (ADR 0120 decision 6): its key opens another link, perhaps another operator's share,
     *  so it is reported by its `NAME` here and its share where the share lives. */
    val targetKind = httpFailure("report_target_kind", FailureStatus.UNPROCESSABLE)
}

/**
 * Receives a report (ADR 0093 §§ 1–2, 5; uc.moderation.report): checks who may report what, then — in one transaction —
 * stores it, finds or opens one case per item, hands each its report share and takes each case's hold
 * ([Holds], en.moderation.hold). Nothing is blocked; the server reads only
 * the clear fields (category, epoch, time, kinds and ids) and stores the sealed ones as it got them.
 *
 * **Check order** (each refusal stores nothing): the reporter's own gate first — a member's scope access (the gate every
 * scope-addressed operation runs first, ADR 0042), a guest's link, password and write proof — so an id is never an
 * oracle before it; then the request's shape (`400`, the schema's own rule), an item named twice (`422`), the epoch, the
 * reach of every item — a `LINK` is in reach only of a guest of a `WRITE_ONLY` link, the link it came through, so a
 * member's or a viewing guest's is `404 report_item_not_found` like any other item they cannot report — and a member's
 * cap on open reports.
 *
 * **Lock: the item's space row** ([NodesStore.lockScope]), taken before the reach is read and held while the cases are
 * found or opened. Every report of an item is in that item's space, so two reports of one item run one after the other
 * and the second finds the first's open case — "one case per item" holds without a retry on the unique `open_key`, which
 * stays the database's backstop. It is the lock every tree writer takes first (developer-styleguide § 12), so the reach
 * read under it cannot be overtaken by a trash or a move, and taking it first keeps the one lock order.
 */
@ApplicationScoped
class ReportHandler(
    private val access: ScopeAccess,
    private val nodes: NodesStore,
    private val sharing: SharingStore,
    private val gate: ShareGuestGate,
    private val limiter: ReportRateLimiter,
    private val log: ModerationLog,
    private val holds: Holds,
    private val blocks: ModerationBlocks,
    // MutableList, not List: ArC rejects kotlin List with @All (see ScopePurge).
    @All reportable: MutableList<ReportableItems>,
    config: VeyqConfig,
) {
    private val items: List<ReportableItems> = reportable
    private val openCap = config.moderation().report().openCap()

    /** Every refusal is decided before the first write ([receive]), so a refused report commits nothing. */
    fun report(reporter: Reporter, report: ReportIn, now: Instant = Instant.now()): ReportOutcome =
        QuarkusTransaction.requiringNew().call {
            when (reporter) {
                is Reporter.Member -> member(reporter, report, now)
                is Reporter.Guest -> guest(reporter, report, now)
            }
        }

    private fun member(m: Reporter.Member, r: ReportIn, now: Instant): ReportOutcome {
        access.check(m.userId, m.scopeId, AccessMode.READ).refusal(Failures.scopeNotFound, Failures.tenantSuspended)
            ?.let { return refused(it) }
        // ADR 0093 § 5: a barred member reports nothing — after the space's gate, so the bar is no oracle for a stranger.
        if (blocks.reporterBarred(m.userId, null)) return refused(ReportFailures.reporterBarred)
        malformed(r)?.let { return refused(it) }
        if (!currentEpoch(r.epoch)) return refused(ReportFailures.epochChanged)
        nodes.lockScope(m.scopeId)
        val placed = r.items.map { item ->
            val inReach = when (item.kind) {
                ReportItemKind.NODE -> {
                    val node = nodes.findInScope(m.scopeId, item.id!!)
                    if (node?.kind == CoreNodeKinds.SHARE_BOOKMARK) return refused(ReportFailures.targetKind)
                    node != null
                }
                ReportItemKind.NAME -> nodes.findInScope(m.scopeId, item.id!!) != null
                ReportItemKind.ITEM -> locate(item.id!!)?.let { it.scopeId == m.scopeId && nodes.findInScope(m.scopeId, it.nodeId) != null } == true
                ReportItemKind.LINK -> false
            }
            if (!inReach) return refused(ReportFailures.itemNotFound)
            item to m.scopeId
        }
        if (openReportsOf(m.userId) >= openCap) return refused(ReportFailures.tooManyOpen)
        return receive(r, placed, now, reporterAccount = m.userId, reporterShare = null)
    }

    private fun guest(g: Reporter.Guest, r: ReportIn, now: Instant): ReportOutcome {
        val share = gate.activeShare(g.shareId) ?: return refused(ReportFailures.shareNotFound)
        when (gate.passwordCheck(share, g.pwToken)) {
            ShareGuestGate.PwCheck.MISSING -> return refused(ReportFailures.shareNotFound)
            ShareGuestGate.PwCheck.WRONG -> return refused(ReportFailures.sharePasswordIncorrect)
            ShareGuestGate.PwCheck.TOO_MANY -> return refused(ReportFailures.rateLimited)
            ShareGuestGate.PwCheck.OK -> {}
        }
        // ADR 0076: only a holder of the whole link reports through it — the id alone (a log line, a referrer) is inert.
        val verifier = share.writeVerifier
        if (verifier == null || g.writeToken.isNullOrBlank() || !gate.matchesVerifier(g.writeToken, verifier)) {
            return refused(ReportFailures.shareNotFound)
        }
        // ADR 0093 § 5: reports through a barred link are refused — after the whole link was proved, so no oracle.
        if (blocks.reporterBarred(null, share.id)) return refused(ReportFailures.reporterBarred)
        val writeOnly = !ShareAccessPolicy.canView(share.role)
        malformed(r)?.let { return refused(it) }
        if (!limiter.allowShare(share.id)) return refused(ReportFailures.rateLimited)
        if (!currentEpoch(r.epoch)) return refused(ReportFailures.epochChanged)
        nodes.lockScope(share.scopeId)
        val placed = if (writeOnly) {
            // A WRITE_ONLY guest sees only its own uploads, so it reports the link and nothing else (ADR 0093 § 1).
            if (r.items.any { it.kind != ReportItemKind.LINK }) return refused(ReportFailures.itemNotFound)
            r.items.map { it to share.scopeId }
        } else {
            val reach = reachOf(share)
            r.items.map { item ->
                val inReach = when (item.kind) {
                    ReportItemKind.NODE -> item.id in reach
                    // The name a guest reads at the link's root is the link's (`share-memory-name`), and the root's own
                    // name is sealed under a parent key the guest never holds: a name report of it is no name they saw.
                    ReportItemKind.NAME -> item.id in reach && item.id != share.nodeId
                    ReportItemKind.ITEM -> locate(item.id!!)?.let { it.scopeId == share.scopeId && it.nodeId in reach } == true
                    ReportItemKind.LINK -> false
                }
                if (!inReach) return refused(ReportFailures.itemNotFound)
                item to share.scopeId
            }
        }
        return receive(r, placed, now, reporterAccount = null, reporterShare = share.id)
    }

    /** The live nodes a viewing guest of [share] sees: its root's live subtree, nothing while the root is trashed — and no
     *  bookmark, which no link serves (`SharingStore.liveSubtreeIds`, ADR 0120 decision 7). */
    private fun reachOf(share: ShareEntity): Set<String> =
        if (sharing.findNode(share.nodeId)?.deletedAt != null) emptySet() else sharing.liveSubtreeIds(share.scopeId, share.nodeId).toSet()

    /**
     * The request's shape (`400`) and its items each named once (`422`), or null. A NODE, an ITEM or a NAME names its
     * id, a LINK none — `ReportItemSubmission`'s own `if`/`then`/`else`, so a body the contract admits never fails here. Whether
     * the reporter may report a LINK at all is the reach's question, not the shape's: only a guest of a `WRITE_ONLY` link
     * has one in reach (`404 report_item_not_found` for everyone else, as for any item outside their reach).
     */
    private fun malformed(r: ReportIn): HttpFailure? {
        if (!r.items.all { if (it.kind == ReportItemKind.LINK) it.id == null else it.id != null }) return ReportFailures.invalid
        val keys = r.items.map { it.kind to it.id }
        return ReportFailures.itemRepeated.takeIf { keys.toSet().size != keys.size }
    }

    private fun currentEpoch(epoch: Int): Boolean =
        ModerationEpochEntity.find("order by epoch desc").firstResult()?.epoch == epoch

    private fun locate(itemId: String): ReportableItem? = items.mapNotNull { it.locate(itemId) }.singleOrNull()

    /** The member's reports with at least one case still open (owner decision Q8's cap). */
    private fun openReportsOf(userId: String): Long =
        ModerationReportEntity.getEntityManager().createQuery(
            "select count(distinct r.id) from ModerationReportEntity r, ReportShareEntity s, ModerationCaseEntity c " +
                "where s.reportId = r.id and c.id = s.caseId and r.reporterAccount = :account and c.state = :open",
            Long::class.javaObjectType,
        ).setParameter("account", userId).setParameter("open", CaseState.OPEN.name).singleResult

    private fun receive(
        r: ReportIn,
        placed: List<Pair<ReportItemIn, String>>,
        now: Instant,
        reporterAccount: String?,
        reporterShare: String?,
    ): ReportOutcome {
        val report = ModerationReportEntity().apply {
            id = UUID.randomUUID().toString()
            epoch = r.epoch
            receivedAt = now
            category = r.category.name
            sealedReport = r.sealedReport
            sealedContact = r.sealedContact
            this.reporterAccount = reporterAccount
            this.reporterShare = reporterShare
        }
        report.persist()
        val cases = placed.map { (item, scopeId) ->
            val itemId = item.id ?: reporterShare!!
            val (case, opened) = caseFor(item.kind, itemId, scopeId, r.category)
            // The hold (ADR 0093 § 4): taken with the case, and again by a report that finds the case's hold ended —
            // never twice while one is alive. A case-open hold ends 90 days after the report that took it. A case of
            // child sexual abuse bounds its hold to 90 days after the report that took it or made it one: no measure or
            // statement keeps it longer (§ 184b(5) StGB), and the handover ends it (CaseDecisions.handover).
            val csamBound = report.receivedAt.plus(Holds.CHILD_SEXUAL_ABUSE).takeIf { case.category == ReportCategory.CHILD_SEXUAL_ABUSE.name }
            if (!holds.aliveFor(case.id, now)) {
                holds.take(
                    case.id, holdTarget(item.kind, itemId, scopeId), HoldTrigger.CASE_OPEN, report.receivedAt.plus(Holds.CASE_OPEN),
                    ceilingUntil = csamBound,
                )
            } else if (csamBound != null) {
                holds.aliveOf(case.id, now)?.let { holds.capAt(it, csamBound) }
            }
            ReportShareEntity().apply {
                id = UUID.randomUUID().toString()
                reportId = report.id
                caseId = case.id
                sealedKeys = item.sealedShare
            }.persist()
            log.appendCase(if (opened) ModerationEvent.RECEIVED else ModerationEvent.REPORT_ATTACHED, now, case.id, reporterAccount)
            ReportedCase(case.id, opened, ReportCategory.valueOf(case.category))
        }
        return ReportOutcome.Received(report.id, now, cases)
    }

    /**
     * What a held item's snapshot is taken on: a node's live subtree (its `K_node` opens every key below it); a name's
     * node row alone (its field key opens that one name, nothing below it).
     */
    private fun holdTarget(kind: ReportItemKind, itemId: String, scopeId: String): HoldTarget = when (kind) {
        ReportItemKind.NODE -> HoldTarget.Subtree(scopeId, sharing.liveSubtreeIds(scopeId, itemId))
        ReportItemKind.NAME -> HoldTarget.Name(scopeId, itemId)
        ReportItemKind.ITEM -> HoldTarget.Item(scopeId, itemId)
        ReportItemKind.LINK -> HoldTarget.Link(scopeId, itemId)
    }

    /** The item's open case, its category raised to the more urgent one — or a new one. Under the space lock. */
    private fun caseFor(kind: ReportItemKind, itemId: String, scopeId: String, category: ReportCategory): Pair<ModerationCaseEntity, Boolean> {
        val key = ModerationCaseEntity.openKeyOf(kind, itemId)
        // Locked: a decision locks the case row first (`CaseDecisions`), so a dismissal committing meanwhile is seen here
        // — the key no longer matches and a new case opens — and one that comes after waits for this report.
        ModerationCaseEntity.find("openKey", key).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult()?.let { open ->
            open.category = ReportCategory.valueOf(open.category).mostUrgent(category).name
            // A report that makes an unmailed case urgent mails it at once rather than in the day's bundle.
            if (open.noticeDue == ModerationCaseEntity.NOTICE_BUNDLE && ReportCategory.valueOf(open.category).urgent) {
                open.noticeDue = ModerationCaseEntity.NOTICE_URGENT
            }
            return open to false
        }
        val case = ModerationCaseEntity().apply {
            id = UUID.randomUUID().toString()
            itemKind = kind.name
            this.itemId = itemId
            this.scopeId = scopeId
            this.category = category.name
            state = CaseState.OPEN.name
            openKey = key
            noticeDue = if (category.urgent) ModerationCaseEntity.NOTICE_URGENT else ModerationCaseEntity.NOTICE_BUNDLE
        }
        case.persist()
        return case to true
    }

    private fun refused(failure: HttpFailure) = ReportOutcome.Refused(failure)
}
