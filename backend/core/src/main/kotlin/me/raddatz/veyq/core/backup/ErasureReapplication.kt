package me.raddatz.veyq.core.backup

import me.raddatz.veyq.core.account.erasure.AccountPurge
import me.raddatz.veyq.core.account.login.SessionService
import me.raddatz.veyq.core.admin.tenant.TenantPurge
import me.raddatz.veyq.core.admin.tenant.TenantStore
import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.content.NodeSubtreePurge
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.moderation.ModerationEvent
import me.raddatz.veyq.core.moderation.ModerationEventEntity
import me.raddatz.veyq.core.moderation.ModerationLog
import me.raddatz.veyq.core.moderation.block.BlockKind
import me.raddatz.veyq.core.moderation.block.ModerationBlockEntity
import me.raddatz.veyq.core.moderation.report.CaseOrigin
import me.raddatz.veyq.core.moderation.report.CaseState
import me.raddatz.veyq.core.moderation.report.ModerationCaseEntity
import me.raddatz.veyq.core.moderation.report.ReportCategory
import me.raddatz.veyq.core.moderation.report.ReportItemKind
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.ShareIdentityEntity
import me.raddatz.veyq.core.sharing.SharingStore
import me.raddatz.veyq.core.spaces.scope.ScopePurge
import me.raddatz.veyq.core.tenant.TenantEntity
import io.quarkus.arc.All
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

/** What one start's re-application did — the start's log line. Counts only, never an id (a log names no subject). */
data class Reapplied(
    val lines: Int,
    val erased: Map<String, Int>,
    val reblocked: Int,
    val relifted: Int,
    val caseStubs: Int,
    val unknown: Int,
    val erasedKeys: Int,
    val rowsNamingErasedKeys: Long,
)

/**
 * Re-applies the erasure ledger at start (ADR 0056 decision 8, decision 5 of cr-backup-erasure-and-retention): every
 * listed object whose row exists is erased through its kind's own eraser — the one that erased it the first time — so a
 * restore of a capture older than an erasure does not bring it back; per item the last block or lift line wins; a case
 * the database lacks comes back as a stub. **Idempotent by construction**: every branch asks whether its row exists
 * before it erases, so a line applied twice — a crash between an append and its stamp, a second start — changes nothing.
 * It never decrypts and never deletes an object: an erasure's blobs go onto the reclaim list as the eraser puts them
 * there, and the store reclaim stays the one deleter (ADR 0062).
 *
 * Each line runs in a transaction of its own and a failure is **thrown**: the start fails, and the instance does not
 * serve while an erasure it lists is undone (the readiness of decision 5 is the start itself — Quarkus opens the socket
 * after every `StartupEvent` observer, `AdminListener`'s order).
 */
@ApplicationScoped
class ErasureReapplication(
    private val accounts: AccountPurge,
    private val tenants: TenantPurge,
    private val tenantStore: TenantStore,
    private val scopes: ScopePurge,
    private val nodes: NodeSubtreePurge,
    private val sharing: SharingStore,
    private val sessions: SessionService,
    private val log: ModerationLog,
    private val config: VeyqConfig,
    // MutableList, not List: ArC rejects kotlin.collections.List with @All (ScopePurge says why).
    @All applications: MutableList<ApplicationErasures>,
) {
    private val byKind: Map<String, ApplicationErasures> = applications.flatMap { app -> app.kinds.map { it to app } }.toMap()

    fun apply(lines: List<LedgerLine>, now: Instant = Instant.now()): Reapplied {
        val erased = linkedMapOf<String, Int>()
        var unknown = 0
        val erasedKeys = HashSet<String>()

        // Moderation first, so a block can name its case and an erasure below can end a block on what it erases.
        val stubs = lines.filter { it.kind == ErasureKind.CASE.name }.associateBy { it.id }.values
            .count { line -> tx { restoreCase(line, now) } }
        var reblocked = 0
        var relifted = 0
        lines.filter { it.kind == ErasureKind.BLOCK.name || it.kind == ErasureKind.LIFT.name }
            .associateBy { "${it["item"]}:${it["target"]}" }.values
            .forEach { last ->
                if (last.kind == ErasureKind.BLOCK.name) { if (tx { reblock(last) }) reblocked++ } else { if (tx { relift(last) }) relifted++ }
            }

        for (line in lines) {
            val core = ErasureKind.entries.firstOrNull { it.name == line.kind }
            val done = if (core != null) {
                when (core) {
                    ErasureKind.ACCOUNT -> tx { accounts.reapply(line.id, line.at) }
                    ErasureKind.TENANT -> tx { tenants.reapply(line.id) }
                    ErasureKind.SCOPE -> tx { ScopeEntity.findById(line.id) != null && run { scopes.purge(line.id); true } }
                    ErasureKind.NODE -> tx { line["scope"]?.let { nodes.eraseNodes(it, listOf(line.id)) > 0 } ?: false }
                    ErasureKind.SHARE -> tx { sharing.delete(line.id) }
                    ErasureKind.STORAGE_KEY -> { erasedKeys += line.id; false }
                    // Applied above, last line per item or case.
                    ErasureKind.BLOCK, ErasureKind.LIFT, ErasureKind.CASE -> false
                }
            } else {
                val app = byKind[line.kind]
                if (app == null) {
                    unknown++
                    false
                } else {
                    tx { app.reapply(line.kind, line.id, line["scope"]) }
                }
            }
            if (done) erased.merge(line.kind, 1, Int::plus)
        }

        // A key the store reclaim deleted after the capture, still named by a restored row with no line of its own (an
        // old version a Depot replace dropped): served as a missing object is (ADR 0056 Context 2), and counted here.
        val naming = if (erasedKeys.isEmpty()) 0L else tx {
            erasedKeys.chunked(500).sumOf { BlobEntity.count("storageKey in ?1", it) }
        }
        return Reapplied(lines.size, erased, reblocked, relifted, stubs, unknown, erasedKeys.size, naming)
    }

    /** A case the restored database lacks, as a stub: no report, no sealed content, its retention kept (decision 7). */
    private fun restoreCase(line: LedgerLine, now: Instant): Boolean {
        if (ModerationCaseEntity.findById(line.id) != null) return false
        val scopeId = line["scope"] ?: return false
        val retainUntil = line["retainUntil"]?.let(Instant::parse)
        if (retainUntil != null && !retainUntil.isAfter(now)) return false // its records were due to go: nothing to keep
        val open = line["state"] == CaseState.OPEN.name
        val opened = line["openedAt"]?.let(Instant::parse) ?: line.at
        ModerationCaseEntity().apply {
            id = line.id
            itemKind = line["item"] ?: ReportItemKind.NODE.name
            itemId = line["target"] ?: line.id
            this.scopeId = scopeId
            category = line["category"] ?: ReportCategory.ILLEGAL_CONTENT.name
            origin = CaseOrigin.RESTORED.name
            if (open) {
                // Still owed a decision, as it was: in the queue, but never the item's one open case — a new report on
                // the item opens a case of its own, with a report a moderator can review.
                state = CaseState.OPEN.name
                openKey = "RESTORED:${line.id}"
            } else {
                // Decided or closed: its hold was a part of what the capture lacked, so nothing holds it open any more —
                // closed now, or on the date it had; a measure re-applied below still keeps its records (ADR 0093 § 11).
                state = CaseState.CLOSED.name
                decidedAt = opened
                closedAt = now
            }
        }.persist()
        ModerationEventEntity().apply {
            id = UUID.randomUUID().toString()
            event = ModerationEvent.RESTORED.name
            occurredAt = opened
            caseId = line.id
            expiresAt = if (open) null else retainUntil ?: ModerationLog.retentionEnd(now)
        }.persist()
        return true
    }

    /** The item's last line is a block, and the database does not block it: block it again, on the case if it is there. */
    private fun reblock(line: LedgerLine): Boolean {
        val kind = line["item"]?.let { k -> BlockKind.entries.firstOrNull { it.name == k } } ?: return false
        val target = line["target"] ?: return false
        val key = ModerationBlockEntity.activeKeyOf(kind, target)
        if (ModerationBlockEntity.count("activeKey", key) > 0) return false
        val exists = when (kind) {
            BlockKind.SHARE, BlockKind.REPORTER_LINK -> ShareEntity.findById(target) != null
            BlockKind.GUEST_IDENTITY -> ShareIdentityEntity.findById(target) != null
            BlockKind.CONTENT_NODE -> NodeEntity.findById(target) != null
            // An application's item: core cannot ask for its row, the space must be there.
            BlockKind.CONTENT_ITEM -> line["scope"]?.let { ScopeEntity.findById(it) != null } ?: true
            BlockKind.MEMBER_SIGN_IN, BlockKind.REPORTER_ACCOUNT -> VeyqUser.findById(target) != null
            BlockKind.TENANT_OFFLINE, BlockKind.TENANT_DELETED -> TenantEntity.findById(target) != null
        }
        if (!exists) return false // created after the capture: the restored database cannot hold what it stood on
        val caseId = line["case"]?.takeIf { ModerationCaseEntity.findById(it) != null }
        val takenAt = line.at
        val row = ModerationBlockEntity().apply {
            id = line.id.takeIf { ModerationBlockEntity.findById(it) == null } ?: UUID.randomUUID().toString()
            this.kind = kind.name
            targetId = target
            shareId = line["share"]
            scopeId = line["scope"]
            this.caseId = caseId
            takenBy = line["by"] ?: line.id
            this.takenAt = takenAt
            activeKey = key
        }
        row.persist()
        // What the block did besides its row, again: a member's sessions end; a tenant goes offline, or is deleted, as
        // its status — not its record — is what refuses it (ADR 0066, ADR 0101 decision 10).
        when (kind) {
            BlockKind.MEMBER_SIGN_IN -> sessions.revokeAllForUser(target)
            BlockKind.TENANT_OFFLINE -> tenantStore.suspend(target)
            BlockKind.TENANT_DELETED -> tenantStore.markForDeletion(target, takenAt.plus(config.scope().delete().retention()))
            BlockKind.SHARE, BlockKind.CONTENT_NODE, BlockKind.CONTENT_ITEM, BlockKind.GUEST_IDENTITY,
            BlockKind.REPORTER_ACCOUNT, BlockKind.REPORTER_LINK -> Unit
        }
        if (caseId != null) {
            log.appendCase(ModerationEvent.MEASURE_TAKEN, takenAt, caseId, moderatorId = row.takenBy, reason = kind.name, targetId = target)
        }
        return true
    }

    /** The item's last line is a lift, and the database still blocks it: the lift stands — a lift is never undone. */
    private fun relift(line: LedgerLine): Boolean {
        val kind = line["item"]?.let { k -> BlockKind.entries.firstOrNull { it.name == k } } ?: return false
        val target = line["target"] ?: return false
        val row = ModerationBlockEntity.find("activeKey", ModerationBlockEntity.activeKeyOf(kind, target)).firstResult() ?: return false
        row.liftedAt = line.at
        row.activeKey = null
        row.caseId?.let { log.appendCase(ModerationEvent.MEASURE_LIFTED, line.at, it, reason = kind.name, targetId = target) }
        return true
    }

    private fun <T> tx(body: () -> T): T = QuarkusTransaction.requiringNew().call(body)
}
