package me.raddatz.veyq.core.admin.tenant

import me.raddatz.veyq.core.identity.TenantProfileEntity
import me.raddatz.veyq.core.moderation.block.BlockKind
import me.raddatz.veyq.core.moderation.cases.SignInBlocks
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.spaces.scope.ScopePurge
import me.raddatz.veyq.core.tenant.TenantEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import jakarta.transaction.Transactional
import java.time.Instant
import org.jboss.logging.Logger

/**
 * The purge of a deleted tenant (uc.admin.delete-tenant step 5, ADR 0101 decision 10): once a `PENDING_DELETION`
 * tenant is due — the deletion plus the scope trash's retention — one transaction erases every scope of it through the
 * scope erasure ([ScopePurge.purge], which puts their blobs onto the reclaim list), the tenant's profiles and invites,
 * and the tenant row, whose terms rows, mail settings, capability switches and requirements cascade with it. Pending
 * or complete, never partial: a failure rolls the tenant back, is logged, and the next tick tries again.
 *
 * What stays: holds and the moderation records hang on cases, not tenants, so they survive to their own ends; every
 * account stays, also one this leaves in no tenant (owner, Q20). `scripts/validate_account_erasure_arity.py` holds
 * that every table reachable by foreign key from `veyq_tenant` is deleted here, is the scope erasure's, or cascades.
 * Operator-blind: nothing is decrypted; store objects go only through the reclaim list (ADR 0062).
 */
@ApplicationScoped
class TenantPurge(private val scopePurge: ScopePurge, private val store: TenantStore, private val steps: SignInBlocks) {
    private val log = Logger.getLogger(TenantPurge::class.java)

    @Scheduled(every = "{veyq.tenant.deletion.purge.every}")
    fun run() {
        val now = Instant.now()
        val due = QuarkusTransaction.requiringNew().call { store.dueForDeletion(now) }
        due.forEach { tenantId ->
            try {
                QuarkusTransaction.requiringNew().run { eraseTenant(tenantId, now) }
            } catch (e: Exception) {
                log.error("the erasure of tenant $tenantId failed; it stays pending deletion and the purge continues", e)
            }
        }
    }

    /** Erase one tenant pending deletion now, due or not (tests, a forced run) — transactional. */
    @Transactional
    fun purge(tenantId: String) = eraseTenant(tenantId, null)

    private fun eraseTenant(tenantId: String, cutoff: Instant?) {
        // The tenant row's lock, and the work list's predicate again under it: a stop that landed in between wins.
        val tenant = TenantEntity.findById(tenantId, LockModeType.PESSIMISTIC_WRITE) ?: return
        if (tenant.status != TenantEntity.PENDING_DELETION) return
        if (cutoff != null && tenant.deletionDueAt?.isAfter(cutoff) != false) return
        // Every scope, the TENANT scope last: ascending ids for the groups and personal scopes, the order every
        // multi-scope lock takes, then the root the others' members joined through.
        ScopeEntity.list("tenantId", tenantId).map { it.id }.filter { it != tenantId }.sorted()
            .forEach { scopePurge.purge(it) }
        if (ScopeEntity.findById(tenantId) != null) scopePurge.purge(tenantId)
        TenantProfileEntity.delete("tenantId", tenantId)
        TenantInviteEntity.delete("tenantId", tenantId)
        // A suspension's or this deletion's record over content ends with the tenant, so its records run to their date.
        steps.targetErased(tenantId, listOf(BlockKind.TENANT_OFFLINE, BlockKind.TENANT_DELETED), Instant.now())
        // ERASURE LEDGER — the one hook for the TENANT kind: cr-backup-erasure-and-retention writes its outbox entry
        // (kind TENANT, this id) here, in this transaction, before the row goes.
        TenantEntity.deleteById(tenantId)
        log.infof("tenant %s was erased", tenantId)
    }
}
