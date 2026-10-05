package me.raddatz.veyq.core.admin.tenant

import me.raddatz.veyq.core.quota.StorageUsage
import me.raddatz.veyq.core.http.FailureStatus
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.membership.AccessMode.READ
import me.raddatz.veyq.core.membership.AccessMode.WRITE
import me.raddatz.veyq.core.membership.ScopeAccess
import me.raddatz.veyq.core.membership.refusal
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

/** A tenant's storage as its owner reads it: the tenant's total usage, its quota and the per-link limit (ADR 0073 §4, §6). */
data class TenantStorageView(val usedBytes: Long, val quotaBytes: Long?, val shareLinkLimitBytes: Long)

sealed interface TenantStorageOutcome {
    data class Ok(val storage: TenantStorageView) : TenantStorageOutcome
    data object Suspended : TenantStorageOutcome, HttpFailure by Failures.tenantSuspended
    data object NotFound : TenantStorageOutcome, HttpFailure by Failures.tenantNotFound // no such tenant, or not a member (indistinguishable)
    data object NotOwner : TenantStorageOutcome, HttpFailure by httpFailure("not_tenant_owner", FailureStatus.FORBIDDEN)
}

/**
 * The tenant owner's storage settings (uc.sharing.share-link-storage-limit, ADR 0073 §4): read the tenant's usage,
 * set the one per-link limit every READ_WRITE and WRITE_ONLY link stores at most. The mail-settings shape
 * ([TenantMailSettingsHandler]): membership gate → tenant → owner → store; a non-owner member is 403, a non-member
 * 404. Usage comes from [StorageUsage], the one place a total is summed — this handler never sums on its own.
 *
 * A new limit binds from each link's next start and deletes nothing (ADR 0073 §5). The write is a plain UPDATE of
 * the tenant row, so it waits for, and is waited on by, the row lock a link start holds while it reserves.
 */
@ApplicationScoped
class TenantStorageHandler(
    private val store: TenantStore,
    private val quota: StorageUsage,
    private val access: ScopeAccess,
) {

    fun read(callerId: String, tenantId: String): TenantStorageOutcome {
        access.check(callerId, tenantId, READ)
            .refusal(TenantStorageOutcome.NotFound, TenantStorageOutcome.Suspended)?.let { return it }
        val tenant = store.findTenant(tenantId) ?: return TenantStorageOutcome.NotFound
        if (!store.isOwner(callerId, tenantId)) return TenantStorageOutcome.NotOwner
        return TenantStorageOutcome.Ok(TenantStorageView(quota.tenantUsage(tenantId), tenant.storageQuotaBytes, tenant.shareLinkLimitBytes))
    }

    @Transactional
    fun updateShareLinkLimit(callerId: String, tenantId: String, shareLinkLimitBytes: Long): TenantStorageOutcome {
        access.check(callerId, tenantId, WRITE)
            .refusal(TenantStorageOutcome.NotFound, TenantStorageOutcome.Suspended)?.let { return it }
        val tenant = store.findTenant(tenantId) ?: return TenantStorageOutcome.NotFound
        if (!store.isOwner(callerId, tenantId)) return TenantStorageOutcome.NotOwner
        store.setShareLinkLimit(tenantId, shareLinkLimitBytes)
        return TenantStorageOutcome.Ok(TenantStorageView(quota.tenantUsage(tenantId), tenant.storageQuotaBytes, shareLinkLimitBytes))
    }

    /**
     * The Server Owner sets, changes or clears (null) a tenant's quota (uc.admin.tenant-storage-quota, ADR 0073 §1).
     * The Server-Owner gate and the admin channel are the resource's; nothing here is owner-gated, and no tenant-owner
     * path reaches it — which is what makes "the tenant owner cannot raise it" structural. Any tenant status: a
     * suspended tenant keeps its quota. Deletes nothing: a quota below the usage refuses the next start.
     */
    fun setQuota(tenantId: String, quotaBytes: Long?): SetQuotaOutcome =
        if (store.setStorageQuota(tenantId, quotaBytes)) SetQuotaOutcome.Done else SetQuotaOutcome.NotFound
}

sealed interface SetQuotaOutcome {
    data object Done : SetQuotaOutcome
    data object NotFound : SetQuotaOutcome, HttpFailure by Failures.tenantNotFound
}
