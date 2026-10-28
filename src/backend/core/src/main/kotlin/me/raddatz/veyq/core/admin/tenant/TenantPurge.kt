package me.raddatz.veyq.core.admin.tenant

import me.raddatz.veyq.core.account.erasure.TermsEvidence
import me.raddatz.veyq.core.backup.ErasureKind
import me.raddatz.veyq.core.backup.ErasureLedger
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
 * scope erasure ([ScopePurge.purge], which puts their blobs onto the reclaim list), the tenant's profiles, invites and
 * terms acceptance rows, and the tenant row, whose mail settings, capability switches and requirements cascade with it.
 * Pending or complete, never partial: a failure rolls the tenant back, is logged, and the next tick tries again.
 *
 * What stays: holds and the moderation records hang on cases, not tenants, so they survive to their own ends; the terms
 * acceptance rows of a member a case names stay as an account's erasure keeps them ([TermsEvidence]); every account
 * stays, also one this leaves in no tenant (owner, Q20). `scripts/validate_account_erasure_arity.py` holds
 * that every table reachable by foreign key from `veyq_tenant` is deleted here, is the scope erasure's, or cascades.
 * Operator-blind: nothing is decrypted; store objects go only through the reclaim list (ADR 0062).
 */
@ApplicationScoped
class TenantPurge(
    private val scopePurge: ScopePurge,
    private val store: TenantStore,
    private val steps: SignInBlocks,
    private val ledger: ErasureLedger,
) {
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

    /**
     * The erasure ledger's re-application (ADR 0056 decision 8): the tenant [tenantId] was erased, and a restore brought
     * its row back. One never accepted (`PENDING`) goes as its revoke took it ([TenantStore.deleteTenant]); any other is
     * put back into `PENDING_DELETION`, due now, and the one purge erases it as it did then. False when no tenant row
     * exists. In the caller's transaction; the start's routing projection runs after it (`TenantIngressProjector`).
     */
    @Transactional(Transactional.TxType.MANDATORY)
    fun reapply(tenantId: String): Boolean {
        val tenant = TenantEntity.findById(tenantId, LockModeType.PESSIMISTIC_WRITE) ?: return false
        if (tenant.status == TenantEntity.PENDING) {
            store.deleteTenant(tenantId)
            return true
        }
        tenant.status = TenantEntity.PENDING_DELETION
        tenant.deletionDueAt = Instant.now()
        eraseTenant(tenantId, null)
        return true
    }

    private fun eraseTenant(tenantId: String, cutoff: Instant?) {
        // The tenant row's lock, and the work list's predicate again under it: a stop that landed in between wins.
        val tenant = TenantEntity.findById(tenantId, LockModeType.PESSIMISTIC_WRITE) ?: return
        if (tenant.status != TenantEntity.PENDING_DELETION) return
        if (cutoff != null && tenant.deletionDueAt?.isAfter(cutoff) != false) return
        // The terms acceptance history, as an account's erasure keeps it (ADR 0101 decision 6): a member a moderation
        // case names keeps their rows here past the tenant, under the opaque ids, until AccountPurge's sweep finds no case
        // naming them. Asked before the scopes go, whose purge ends the measures on their content: the question is about
        // the state the erasure found, and a measure it ends still counts.
        val members = TenantTermsAcceptanceEntity.list("tenantId", tenantId).map { it.userId }.distinct()
        val evidence = members.filter { TermsEvidence.namesAtErasure(it) }
        // Every scope, the TENANT scope last: ascending ids for the groups and personal scopes, the order every
        // multi-scope lock takes, then the root the others' members joined through.
        ScopeEntity.list("tenantId", tenantId).map { it.id }.filter { it != tenantId }.sorted()
            .forEach { scopePurge.purge(it) }
        if (ScopeEntity.findById(tenantId) != null) scopePurge.purge(tenantId)
        TenantProfileEntity.delete("tenantId", tenantId)
        TenantInviteEntity.delete("tenantId", tenantId)
        if (evidence.isEmpty()) {
            TenantTermsAcceptanceEntity.delete("tenantId", tenantId)
        } else {
            TenantTermsAcceptanceEntity.delete("tenantId = ?1 and userId not in ?2", tenantId, evidence)
        }
        // A suspension's or this deletion's record over content ends with the tenant, so its records run to their date.
        steps.targetErased(tenantId, listOf(BlockKind.TENANT_OFFLINE, BlockKind.TENANT_DELETED), Instant.now())
        // The erasure ledger's line (ADR 0056 decision 8, ADR 0101 decision 8), in this transaction, before the row goes.
        ledger.erased(ErasureKind.TENANT, tenantId)
        TenantEntity.deleteById(tenantId)
        log.infof("tenant %s was erased", tenantId)
    }
}
