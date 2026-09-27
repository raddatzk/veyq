package com.veyq.app.features.admin.tenant

import com.veyq.app.features.media.StorageQuota
import com.veyq.app.shared.http.FailureStatus
import com.veyq.app.shared.http.Failures
import com.veyq.app.shared.http.HttpFailure
import com.veyq.app.shared.http.httpFailure
import com.veyq.app.shared.membership.AccessMode.READ
import com.veyq.app.shared.membership.AccessMode.WRITE
import com.veyq.app.shared.membership.ScopeAccess
import com.veyq.app.shared.membership.refusal
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

/** A tenant's storage as its owner reads it: the tenant's total usage and the per-link limit (ADR 0073 §4, §6). */
data class TenantStorageView(val usedBytes: Long, val shareLinkLimitBytes: Long)

sealed interface TenantStorageOutcome {
    data class Ok(val storage: TenantStorageView) : TenantStorageOutcome
    data object Suspended : TenantStorageOutcome, HttpFailure by Failures.tenantSuspended
    data object NotFound : TenantStorageOutcome, HttpFailure by Failures.tenantNotFound // no such tenant, or not a member (indistinguishable)
    data object NotOwner : TenantStorageOutcome, HttpFailure by httpFailure("not_tenant_owner", FailureStatus.FORBIDDEN)
}

/**
 * The tenant owner's storage settings (uc.sharing.share-link-storage-limit, ADR 0073 §4): read the tenant's usage,
 * set the one per-link limit every CONTRIBUTOR and UPLOADER link stores at most. The mail-settings shape
 * ([TenantMailSettingsHandler]): membership gate → tenant → owner → store; a non-owner member is 403, a non-member
 * 404. Usage comes from [StorageQuota], the one place a total is summed — this handler never sums on its own.
 *
 * A new limit binds from each link's next start and deletes nothing (ADR 0073 §5). The write is a plain UPDATE of
 * the tenant row, so it waits for, and is waited on by, the row lock a link start holds while it reserves.
 */
@ApplicationScoped
class TenantStorageHandler(
    private val store: TenantStore,
    private val quota: StorageQuota,
    private val access: ScopeAccess,
) {

    fun read(callerId: String, tenantId: String): TenantStorageOutcome {
        access.check(callerId, tenantId, READ)
            .refusal(TenantStorageOutcome.NotFound, TenantStorageOutcome.Suspended)?.let { return it }
        val tenant = store.findTenant(tenantId) ?: return TenantStorageOutcome.NotFound
        if (!store.isOwner(callerId, tenantId)) return TenantStorageOutcome.NotOwner
        return TenantStorageOutcome.Ok(TenantStorageView(quota.tenantUsage(tenantId), tenant.shareLinkLimitBytes))
    }

    @Transactional
    fun updateShareLinkLimit(callerId: String, tenantId: String, shareLinkLimitBytes: Long): TenantStorageOutcome {
        access.check(callerId, tenantId, WRITE)
            .refusal(TenantStorageOutcome.NotFound, TenantStorageOutcome.Suspended)?.let { return it }
        if (store.findTenant(tenantId) == null) return TenantStorageOutcome.NotFound
        if (!store.isOwner(callerId, tenantId)) return TenantStorageOutcome.NotOwner
        store.setShareLinkLimit(tenantId, shareLinkLimitBytes)
        return TenantStorageOutcome.Ok(TenantStorageView(quota.tenantUsage(tenantId), shareLinkLimitBytes))
    }
}
