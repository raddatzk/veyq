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
import me.raddatz.veyq.core.moderation.hold.HoldTarget
import me.raddatz.veyq.core.moderation.hold.HoldTrigger
import me.raddatz.veyq.core.moderation.hold.Holds
import me.raddatz.veyq.core.sharing.ShareAccessPolicy
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.ShareGuestGate
import me.raddatz.veyq.core.sharing.SharingStore
import io.quarkus.arc.All
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

/** A report as the wire hands it over, its shape already bounded by the contract. */
data class ReportIn(
    val category: ReportCategory,
    val epoch: Int,
    val sealedReport: String,
    val items: List<ReportItemIn>,
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
    val itemNotFound = httpFailure("report_item_not_found", FailureStatus.NOT_FOUND)
    val epochChanged = httpFailure("moderation_epoch_changed", FailureStatus.CONFLICT)
    val tooManyOpen = httpFailure("too_many_open_reports", FailureStatus.RATE_LIMITED)
    val rateLimited = httpFailure("rate_limited", FailureStatus.RATE_LIMITED)
    val shareNotFound = httpFailure("share_not_found", FailureStatus.NOT_FOUND)
    val sharePasswordIncorrect = httpFailure("share_password_incorrect", FailureStatus.FORBIDDEN)
}

/**
 * Receives a report (ADR 0093 §§ 1–2, 5; uc.moderation.report): checks who may report what, then — in one transaction —
 * stores it, finds or opens one case per item, hands each its report share and takes each case's hold
 * ([Holds], en.moderation.hold). Nothing is blocked; the server reads only
 * the clear fields (category, epoch, time, kinds and ids) and stores the sealed ones as it got them.
 *
 * **Check order** (each refusal stores nothing): the reporter's own gate first — a member's scope access (the gate every
 * scope-addressed operation runs first, ADR 0042), a guest's link, password and write proof — so an id is never an
 * oracle before it; then the request's shape, the epoch, the reach of every item, and a member's cap on open reports.
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
        if (!wellFormed(r, guestLink = false)) return refused(ReportFailures.invalid)
        if (!currentEpoch(r.epoch)) return refused(ReportFailures.epochChanged)
        nodes.lockScope(m.scopeId)
        val placed = r.items.map { item ->
            val inReach = when (item.kind) {
                ReportItemKind.NODE -> nodes.findInScope(m.scopeId, item.id!!) != null
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
        val writeOnly = !ShareAccessPolicy.canView(share.role)
        if (!wellFormed(r, guestLink = writeOnly)) return refused(ReportFailures.invalid)
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
                    ReportItemKind.ITEM -> locate(item.id!!)?.let { it.scopeId == share.scopeId && it.nodeId in reach } == true
                    ReportItemKind.LINK -> false
                }
                if (!inReach) return refused(ReportFailures.itemNotFound)
                item to share.scopeId
            }
        }
        return receive(r, placed, now, reporterAccount = null, reporterShare = share.id)
    }

    /** The live nodes a viewing guest of [share] sees: its root's live subtree, nothing while the root is trashed. */
    private fun reachOf(share: ShareEntity): Set<String> =
        if (sharing.findNode(share.nodeId)?.deletedAt != null) emptySet() else sharing.liveSubtreeIds(share.scopeId, share.nodeId).toSet()

    /**
     * Every item once; a NODE or an ITEM names its id, a LINK none. A LINK only from a guest of a `WRITE_ONLY` link
     * ([guestLink]) — a member reports no link, and a viewing guest reports what the link shows.
     */
    private fun wellFormed(r: ReportIn, guestLink: Boolean): Boolean {
        val shaped = r.items.all { if (it.kind == ReportItemKind.LINK) it.id == null && guestLink else it.id != null }
        val keys = r.items.map { it.kind to it.id }
        return shaped && keys.toSet().size == keys.size
    }

    private fun currentEpoch(epoch: Int): Boolean =
        ModerationEpochEntity.find("order by epoch desc").firstResult()?.epoch == epoch

    private fun locate(itemId: String): ReportableItem? = items.mapNotNull { it.locate(itemId) }.singleOrNull()

    /** The member's reports with at least one case still open (owner decision Q8's cap). */
    private fun openReportsOf(userId: String): Long =
        ModerationReportEntity.getEntityManager().createQuery(
            "select count(distinct r.id) from ModerationReportEntity r, ReportShareEntity s, ModerationCaseEntity c " +
                "where s.reportId = r.id and c.id = s.caseId and r.reporterAccount = :account and c.state = :open",
            java.lang.Long::class.java,
        ).setParameter("account", userId).setParameter("open", CaseState.OPEN.name).singleResult.toLong()

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
            this.reporterAccount = reporterAccount
            this.reporterShare = reporterShare
        }
        report.persist()
        val cases = placed.map { (item, scopeId) ->
            val itemId = item.id ?: reporterShare!!
            val (case, opened) = caseFor(item.kind, itemId, scopeId, r.category)
            // The hold (ADR 0093 § 4): taken with the case, and again by a report that finds the case's hold ended —
            // never twice while one is alive. A case-open hold ends 90 days after the report that took it.
            if (!holds.aliveFor(case.id, now)) {
                holds.take(case.id, holdTarget(item.kind, itemId, scopeId), HoldTrigger.CASE_OPEN, report.receivedAt.plus(Holds.CASE_OPEN))
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

    /** What a held item's snapshot is taken on: a node's live subtree (its `K_node` opens every key below it). */
    private fun holdTarget(kind: ReportItemKind, itemId: String, scopeId: String): HoldTarget = when (kind) {
        ReportItemKind.NODE -> HoldTarget.Subtree(scopeId, sharing.liveSubtreeIds(scopeId, itemId))
        ReportItemKind.ITEM -> HoldTarget.Item(scopeId, itemId)
        ReportItemKind.LINK -> HoldTarget.Link(scopeId, itemId)
    }

    /** The item's open case, its category raised to the more urgent one — or a new one. Under the space lock. */
    private fun caseFor(kind: ReportItemKind, itemId: String, scopeId: String, category: ReportCategory): Pair<ModerationCaseEntity, Boolean> {
        val key = ModerationCaseEntity.openKeyOf(kind, itemId)
        ModerationCaseEntity.find("openKey", key).firstResult()?.let { open ->
            open.category = ReportCategory.valueOf(open.category).mostUrgent(category).name
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
        }
        case.persist()
        return case to true
    }

    private fun refused(failure: HttpFailure) = ReportOutcome.Refused(failure)
}
